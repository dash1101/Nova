"""
Nova storage: the storage map, suggestions, and the operations behind the storage wizards
(format a drive, combine drives into one, RAID, add/replace drives, remove a pool).

Imported by the root helper (read-only map) and by tasks.py (operations, run as background tasks).
Every operation re-checks the live state itself: the boot drive, swap and anything mounted or in
an array are refused no matter what the caller asks for, and destructive steps need the exact list
of drive serials the user confirmed.

Test mode (NOVA_STORAGE_TEST=1, root shell only): loop devices count as drives, and fstab /
mdadm.conf are redirected (NOVA_FSTAB, NOVA_MDADM_CONF), so the real system is never touched.
"""
import glob, json, os, re, shutil, subprocess, time

TEST = os.environ.get("NOVA_STORAGE_TEST") == "1"
FSTAB = os.environ.get("NOVA_FSTAB", "/etc/fstab") if TEST else "/etc/fstab"
MDCONF = os.environ.get("NOVA_MDADM_CONF", "/etc/mdadm/mdadm.conf") if TEST else "/etc/mdadm/mdadm.conf"
SITE = {}
try: SITE = json.load(open("/etc/nova-api/config.json"))
except Exception: pass
SYSTEM_MOUNTS = {"/", "/boot", "/boot/efi", "/usr", "/var", "/home"}
PROTECTED = set(SITE.get("protected_mounts", [])) | SYSTEM_MOUNTS
NAME_RE = re.compile(r"[a-z0-9][a-z0-9_-]{0,23}")
FILESYSTEMS = ("ext4", "xfs", "btrfs", "exfat")
LEVELS = {"combine": None, "raid0": 0, "raid1": 1, "raid5": 5, "raid6": 6, "raid10": 10}
MIN_DRIVES = {"combine": 2, "raid0": 2, "raid1": 2, "raid5": 3, "raid6": 4, "raid10": 4}

# The packages behind each feature (installed when first needed; nova-setup offers them up front).
TOOLS = {"mergerfs": "mergerfs", "mdadm": "mdadm", "mkfs.xfs": "xfsprogs", "mkfs.btrfs": "btrfs-progs",
         "mkfs.exfat": "exfatprogs", "parted": "parted", "wipefs": "util-linux", "mount.nfs": "nfs-common",
         "mount.cifs": "cifs-utils", "rsync": "rsync", "fio": "fio", "stress-ng": "stress-ng",
         "smartctl": "smartmontools", "tracepath": "iputils-tracepath", "setfattr": "attr",
         "mkfs.vfat": "dosfstools", "ddrescue": "gddrescue", "sgdisk": "gdisk", "fatlabel": "dosfstools", "tune.exfat": "exfatprogs", "ntfslabel": "ntfs-3g"}


def run(cmd, timeout=600, check=False, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=timeout, **kw)   # tools like e2fsck can print bytes that aren't UTF-8
    if check and r.returncode != 0:
        raise RuntimeError(f"{os.path.basename(cmd[0])}: {(r.stderr or r.stdout).strip()[-300:]}")
    return r.returncode, r.stdout, r.stderr

def have(tool):
    return bool(shutil.which(tool) or any(os.path.exists(p + tool) for p in ("/sbin/", "/usr/sbin/")))

def missing_packages(tools):
    return sorted({TOOLS[t] for t in tools if t in TOOLS and not have(t)})     # (Debian's names; pkg.py maps them for other distros)

def ensure_tools(tools, log=print):
    """Install whatever is missing for these tools with the server's package manager (no-op when all are there)."""
    import pkg
    pk = missing_packages(tools)
    if not pk: return
    log(f"Installing {', '.join(pk)}…")
    rc, out = pkg.install(pk, recommends=False)
    if rc != 0:                                          # stale package lists: refresh once and retry
        pkg.refresh(); rc, out = pkg.install(pk, recommends=False)
        if rc != 0: raise RuntimeError(f"couldn't install {', '.join(pk)}: {out.strip()[-300:]}")

def load_json(p, d):
    try: return json.load(open(p))
    except Exception: return d

def human(n):
    for u in ("B", "KB", "MB", "GB", "TB", "PB"):
        if n < 1000 or u == "PB": return f"{n:.0f} {u}" if u in ("B", "KB") or n >= 100 else f"{n:.1f} {u}"
        n /= 1000

def usage(path):
    try:
        s = os.statvfs(path); tot = s.f_blocks * s.f_frsize; free = s.f_bavail * s.f_frsize
        return {"total": tot, "used": tot - free, "free": free}
    except OSError: return None


# ── reading the system ──────────────────────────────────────────────────────────────────
def lsblk():
    rc, js, _ = run(["lsblk", "-J", "-b", "-o", "NAME,PATH,SIZE,MODEL,SERIAL,TRAN,TYPE,MOUNTPOINTS,FSTYPE,LABEL,UUID,ROTA,HOTPLUG,PKNAME"], timeout=30)
    return json.loads(js).get("blockdevices", []) if rc == 0 else []

def fstab_lines():
    try: return open(FSTAB).read().splitlines()
    except OSError: return []

def fstab_entries():
    out = []
    for i, l in enumerate(fstab_lines()):
        f = l.split()
        if len(f) >= 3 and not l.lstrip().startswith("#"):
            out.append({"line": i, "src": f[0], "mount": f[1], "type": f[2], "opts": f[3] if len(f) > 3 else "defaults"})
    return out

def mounts():
    res = {}
    for l in open("/proc/mounts"):
        f = l.split()
        if len(f) >= 3: res[f[1].replace("\\040", " ")] = {"src": f[0], "type": f[2], "opts": f[3]}
    return res

def mdstat():
    """{md127: {"level": "raid1", "members": ["sdb1",…], "state": "active", "degraded": bool, "sync": 34.5|None}}"""
    arrays = {}
    try: text = open("/proc/mdstat").read()
    except OSError: return arrays
    cur = None
    for line in text.splitlines():
        m = re.match(r"(md\d+)\s*:\s*(\w+)\s+(\(\w+\)\s+)?(raid\d+|linear)?\s*(.*)", line)
        if m:
            cur = m.group(1)
            members = [re.sub(r"\[\d+\].*", "", x) for x in m.group(5).split()]
            arrays[cur] = {"level": m.group(4) or "", "members": members, "state": m.group(2), "degraded": False, "sync": None,
                           "failed": [re.sub(r"\[\d+\].*", "", x) for x in m.group(5).split() if "(F)" in x]}
            continue
        if cur:
            u = re.search(r"\[([U_]+)\]", line)
            if u and "_" in u.group(1): arrays[cur]["degraded"] = True
            s = re.search(r"(resync|recovery|reshape|check)\s*=\s*([\d.]+)%", line)
            if s: arrays[cur]["sync"] = float(s.group(2)); arrays[cur]["sync_kind"] = s.group(1)
    for k, a in arrays.items():
        name = ""
        try: name = open(f"/sys/block/{k}/md/array_state").read().strip() and ""
        except OSError: pass
        for p in glob.glob("/dev/md/*"):
            if os.path.realpath(p) == f"/dev/{k}": name = os.path.basename(p)
        a["name"] = name or k
    return arrays

def mergerfs_branches(mount, fstab_src=""):
    for key in ("user.mergerfs.branches", "user.mergerfs.srcmounts"):
        try:
            v = os.getxattr(os.path.join(mount, ".mergerfs"), key).decode()
            return [re.sub(r"=(RW|RO|NC)$", "", b) for b in v.split(":") if b]
        except OSError: pass
    if True:
        out = []
        for b in fstab_src.split(":"):
            out += sorted(glob.glob(b)) if any(c in b for c in "*?[") else [b]
        return [b for b in out if b]

def docker_users():
    """{host path: [container names]} for bind mounts, so the map can say what uses a drive."""
    rc, ids, _ = run(["docker", "ps", "-q"], timeout=20) if shutil.which("docker") else (1, "", "")
    if rc != 0 or not ids.split(): return {}
    rc, js, _ = run(["docker", "inspect"] + ids.split(), timeout=30)
    res = {}
    try:
        for c in json.loads(js):
            for m in c.get("Mounts", []):
                if m.get("Type") == "bind": res.setdefault(m["Source"], set()).add(c["Name"].lstrip("/"))
    except Exception: pass
    return {k: sorted(v) for k, v in res.items()}

def legacy_backup():
    """A hand-written nova-backup script (like the one this project started from): what it copies where."""
    p = "/usr/local/bin/nova-backup"
    try: s = open(p).read()
    except OSError: return None
    dest = re.search(r"^DEST_MNT=(\S+)", s, re.M)
    sets = []
    m = re.search(r"^SETS=\((.*?)^\)", s, re.M | re.S)
    if m:
        for line in m.group(1).splitlines():
            line = line.strip().strip('"')
            if "|" in line and not line.startswith("#"):
                f = line.split("|"); sets.append({"name": f[0], "sources": f[1].split()})
    return {"script": p, "dest": dest.group(1) if dest else "", "sets": sets,
            "status": load_json("/var/lib/nova-backup/backup_status.json", {})}

def backup_jobs():
    try:
        import backups
        return backups.load_jobs()
    except Exception:
        return []

def disks_of(path):
    """The physical drives a folder really lives on (through pools and arrays)."""
    mp = mounts(); m = owner_mount(os.path.realpath(path), list(mp))
    info = mp.get(m, {}); src = info.get("src", "")
    if info.get("type") == "fuse.mergerfs":
        out = set()
        for b in mergerfs_branches(m, next((e["src"] for e in fstab_entries() if e["mount"] == m), "")): out |= disks_of(b)
        return out
    dev = os.path.realpath(src) if src.startswith("/dev/") else ""
    if not dev: return set()
    name = os.path.basename(dev)
    if name.startswith("md"):
        out = set()
        for slave in glob.glob(f"/sys/block/{name}/slaves/*"): out |= disks_of_dev(os.path.basename(slave))
        return out
    return disks_of_dev(name)

def disks_of_dev(name):
    p = os.path.realpath(f"/sys/class/block/{name}")
    if os.path.exists(os.path.join(p, "partition")): return {os.path.basename(os.path.dirname(p))}
    return {name}

def health_pct(sm, ssd):
    """A rough "how healthy is it" number from SMART, for the drive list (100 = like new).
    SSDs: the wear the drive reports itself. All drives lose points for bad sectors and read errors;
    a failed SMART verdict caps it at 5. An estimate, not a promise — drives can die at 100%."""
    if not sm: return None
    h = sm.get("life") if sm.get("life") is not None else 100
    h -= min(50, 2 * (sm.get("realloc") or 0)) + min(30, 6 * (sm.get("pending") or 0)) + min(30, 6 * (sm.get("uncorrect") or 0)) + min(40, 4 * (sm.get("media_errors") or 0))
    if sm.get("passed") is False: h = min(h, 5)
    return max(0, min(100, int(round(h))))

def owner_mount(path, mps):
    """The mount point a path lives on (longest prefix)."""
    best = "/"
    for m in mps:
        if (path == m or path.startswith(m.rstrip("/") + "/")) and len(m) > len(best): best = m
    return best


def storage_map():
    bl, mp, fst, md = lsblk(), mounts(), fstab_entries(), mdstat()
    fst_by_mount = {e["mount"]: e for e in fst}
    smart = load_json("/var/lib/nova-alerts/state.json", {}).get("counters", {}).get("smart", {})
    users = docker_users(); legacy = legacy_backup(); jobs = backup_jobs()
    # Pools ──────────────────────────────────────────────────────────────────────────────
    pools = []
    for m, info in mp.items():
        if info["type"] == "fuse.mergerfs":
            fe = fst_by_mount.get(m, {})
            br = mergerfs_branches(m, fe.get("src", ""))
            pools.append({"id": "mergerfs:" + m, "name": os.path.basename(m) or m, "mount": m, "type": "combine",
                          "members": br, "usage": usage(m), "fstab": bool(fe), "redundancy": 0,
                          "policy": (re.search(r"category\.create=(\w+)", fe.get("opts", "") + "," + info["opts"]) or [None, "epmfs"])[1]})
    for k, a in md.items():
        dev = f"/dev/{k}"
        mnt = next((m for m, i in mp.items() if os.path.realpath(i["src"]) == dev), "")
        lvl = a["level"]; n = len(a["members"])
        red = {"raid1": n - 1, "raid5": 1, "raid6": 2, "raid10": 1}.get(lvl, 0)
        pools.append({"id": "md:" + k, "name": a["name"], "dev": dev, "mount": mnt, "type": lvl or "raid", "members": a["members"],
                      "usage": usage(mnt) if mnt else None, "fstab": mnt in fst_by_mount, "redundancy": red,
                      "degraded": a["degraded"], "failed": a["failed"], "sync": a["sync"], "sync_kind": a.get("sync_kind"), "state": a["state"]})
    for p in pools:
        p["used_by"] = sorted({c for src, cs in users.items() if p["mount"] and (src == p["mount"] or src.startswith(p["mount"].rstrip("/") + "/")) for c in cs})
        p["backed_up"] = None
    branch_of = {b: p for p in pools if p["type"] == "combine" for b in p["members"]}
    md_of = {mem: p for p in pools if p.get("dev") for mem in p["members"]}
    backup_dests = {}
    if legacy and legacy["dest"]: backup_dests[legacy["dest"]] = "nova-backup (your backup script)"
    for j in jobs:
        if j.get("dest", {}).get("type") == "local": backup_dests[j["dest"]["path"]] = j.get("name", "backup")
    # Drives ─────────────────────────────────────────────────────────────────────────────
    drives = []
    for d in bl:
        if d.get("type") == "loop" and TEST and os.path.exists(f"/sys/block/{d['name']}/loop/backing_file"): pass   # test drives
        elif d.get("type") != "disk" or d["name"].startswith(("zram", "sr", "ram", "loop")): continue
        parts = d.get("children") or []
        everything = [d] + parts
        mps = sorted({m for p in everything for m in (p.get("mountpoints") or []) if m})
        roles, uses, warn = [], [], []
        system = any(m in SYSTEM_MOUNTS or m == "[SWAP]" for m in mps)
        if system: roles.append("system")
        member_of = None
        for p in everything:
            if p["name"] in md_of: member_of = md_of[p["name"]]
            for c in p.get("children") or []:
                if c.get("type", "").startswith("raid") or c["name"].startswith("md"):
                    member_of = next((x for x in pools if x.get("dev") == f"/dev/{c['name']}"), member_of)
        if member_of: roles.append("raid-member"); uses.append(f"Part of {member_of['name']}")
        for m in mps:
            if m in branch_of: roles.append("pool-member"); uses.append(f"Part of the {branch_of[m]['name']} pool ({m})")
            if m in backup_dests: roles.append("backup"); uses.append(f"Backups go here ({backup_dests[m]})")
        for m in mps:
            for src, cs in users.items():
                if owner_mount(src, mps + ["/"]) == m and m != "/": uses.append("Used by " + ", ".join(cs[:4]))
        has_fs = [p for p in everything if p.get("fstype") and p.get("fstype") != "linux_raid_member"]
        if not mps and not member_of: roles.append("unused")
        elif mps and not system and not roles: roles.append("data")
        sm = smart.get(d.get("serial") or d["name"], {})
        bus = d.get("tran") or ("nvme" if d["name"].startswith("nvme") else "")
        if bus == "usb" and member_of: warn.append("USB drives can drop out of RAID arrays — a SATA port is safer")
        if sm.get("passed") is False: warn.append("SMART says this drive is failing — copy its data off and replace it")
        elif (sm.get("realloc") or 0) > 0 or (sm.get("pending") or 0) > 0: warn.append("Some bad sectors — keep an eye on it")
        for m in mps:
            fe = fst_by_mount.get(m)
            if not system and m not in mp: continue
            if not system and fe is None: warn.append(f"{m} isn't in /etc/fstab, so it won't come back after a restart")
            elif not system and fe and "nofail" not in fe["opts"]: warn.append(f"If this drive is missing at boot, the server waits for it (no 'nofail' for {m})")
        erasable = not system and not member_of and not mps
        drives.append({"name": d["name"], "path": d["path"], "serial": d.get("serial") or d["name"], "model": (d.get("model") or "").strip(),
                       "size": d["size"], "size_text": human(d["size"]), "bus": bus, "ssd": not d.get("rota"), "removable": bool(d.get("hotplug")),
                       "partitions": [{"name": p["name"], "path": p["path"], "size": p["size"], "fstype": p.get("fstype") or "", "label": p.get("label") or "",
                                       "uuid": p.get("uuid") or "", "mounts": [m for m in (p.get("mountpoints") or []) if m and m != "[SWAP]"],
                                       "swap": "[SWAP]" in (p.get("mountpoints") or []), "usage": next((usage(m) for m in (p.get("mountpoints") or []) if m and m != "[SWAP]"), None)}
                                      for p in parts] if parts else ([{"name": d["name"], "path": d["path"], "size": d["size"], "fstype": d.get("fstype") or "",
                                                                       "label": d.get("label") or "", "uuid": d.get("uuid") or "", "mounts": mps, "swap": False,
                                                                       "usage": next((usage(m) for m in mps), None)}] if d.get("fstype") else []),
                       "mounts": [m for m in mps if m != "[SWAP]"], "roles": sorted(set(roles)), "uses": sorted(set(uses)), "warnings": warn,
                       "system": system, "erasable": erasable, "has_data": bool(has_fs), "pool": member_of["id"] if member_of else None,
                       "smart": {k: sm.get(k) for k in ("passed", "temp", "realloc", "pending", "uncorrect", "crc", "life", "hours")} if sm else None,
                       "health": health_pct(sm, not d.get("rota")) if sm else None})
    # Network shares ─────────────────────────────────────────────────────────────────────
    network = [{"mount": m, "src": i["src"], "type": i["type"], "usage": usage(m)} for m, i in mp.items() if i["type"] in ("nfs", "nfs4", "cifs", "smb3")]
    res = {"drives": drives, "pools": pools, "network": network, "legacy_backup": legacy, "jobs": [{"id": j["id"], "name": j.get("name"), "sources": j.get("sources", []),
            "dest": j.get("dest", {})} for j in jobs], "tools": {t: have(t) for t in ("mergerfs", "mdadm", "mkfs.xfs", "mkfs.btrfs", "mkfs.exfat", "mount.cifs", "mount.nfs", "fio", "stress-ng")},
           "test": TEST}
    res["suggestions"] = suggestions(res)
    return res


# ── suggestions ─────────────────────────────────────────────────────────────────────────
def covered_by_backup(path, m):
    srcs = [s for j in m["jobs"] for s in j["sources"]]
    if m["legacy_backup"]: srcs += [s for st in m["legacy_backup"]["sets"] for s in st["sources"]]
    return any(s == path or s.startswith(path.rstrip("/") + "/") or path.startswith(s.rstrip("/") + "/") for s in srcs)

def suggestions(m):
    out = []
    unused = [d for d in m["drives"] if "unused" in d["roles"] and not d["system"]]
    if unused:
        names = ", ".join(f"{d['size_text']} {d['model'] or d['name']}" for d in unused)
        acts = [{"label": "Use it for backups", "action": "wizard", "goal": "backup", "drives": [d["serial"] for d in unused[:1]]}]
        if len(unused) >= 2: acts.append({"label": "Combine them into one big drive", "action": "wizard", "goal": "combine", "drives": [d["serial"] for d in unused]})
        if len(unused) >= 2: acts.append({"label": "Mirror them (safe if one fails)", "action": "wizard", "goal": "raid1", "drives": [d["serial"] for d in unused[:2]]})
        acts.append({"label": "Just make it a drive", "action": "wizard", "goal": "single", "drives": [d["serial"] for d in unused[:1]]})
        for p in m["pools"]:
            if p["type"] == "combine": acts.append({"label": f"Add to {p['name']}", "action": "wizard", "goal": "grow", "pool": p["id"], "drives": [d["serial"] for d in unused[:1]]})
        out.append({"id": "unused", "level": "info", "title": f"{len(unused)} drive{'s' if len(unused) > 1 else ''} not in use",
                    "detail": f"{names}. " + ("Some still hold old files — setting them up erases them." if any(d["has_data"] for d in unused) else "Ready to set up."), "actions": acts})
    for p in m["pools"]:
        if p.get("degraded"):
            out.append({"id": "degraded:" + p["id"], "level": "critical", "title": f"{p['name']} is missing a drive",
                        "detail": "It still works, but one more failure loses data. Replace the drive now.", "actions": [{"label": "Replace a drive", "action": "wizard", "goal": "replace", "pool": p["id"]}]})
        if p.get("sync") is not None:
            out.append({"id": "sync:" + p["id"], "level": "info", "title": f"{p['name']}: building redundancy {p['sync']:.0f}%",
                        "detail": "Usable now; it's safe against a drive failure once this finishes.", "actions": []})
        if p["mount"] and p["redundancy"] == 0 and not covered_by_backup(p["mount"], m):
            out.append({"id": "nobackup:" + p["id"], "level": "warning", "title": f"Nothing backs up {p['name']}",
                        "detail": f"{'If any one drive in it fails, the files on that drive are gone.' if p['type'] in ('combine', 'raid0') else ''} Add a backup to another drive or a NAS.",
                        "actions": [{"label": "Back it up", "action": "backup-wizard", "sources": [p["mount"]]}]})
        u = p.get("usage")
        if u and u["total"] and u["free"] / u["total"] < 0.08:
            out.append({"id": "full:" + p["id"], "level": "warning", "title": f"{p['name']} is almost full",
                        "detail": f"{human(u['free'])} free of {human(u['total'])}.", "actions": [{"label": "Add a drive", "action": "wizard", "goal": "grow", "pool": p["id"]}] if p["type"] == "combine" else []})
    for d in m["drives"]:
        for w in d["warnings"]:
            if "nofail" in w and d["mounts"]:
                out.append({"id": "nofail:" + d["serial"], "level": "info", "title": "Don't let a missing drive stop the boot",
                            "detail": f"{d['model'] or d['name']}: {w}. Adding 'nofail' lets the server start anyway and Nova tells you it's missing.",
                            "actions": [{"label": "Fix it", "action": "task", "kind": "fstab-nofail", "spec": {"mounts": [x for x in d["mounts"] if x not in SYSTEM_MOUNTS]}}]})
            elif "isn't in /etc/fstab" in w:
                out.append({"id": "fstab:" + d["serial"], "level": "warning", "title": "Mounted only until the next restart",
                            "detail": f"{d['model'] or d['name']}: {w}.", "actions": [{"label": "Keep it mounted", "action": "task", "kind": "fstab-add", "spec": {"mounts": d["mounts"]}}]})
            elif "SMART" in w:
                out.append({"id": "smart:" + d["serial"], "level": "critical", "title": f"{d['model'] or d['name']} is failing", "detail": w, "actions": []})
        if d["mounts"] and not d["system"] and "backup" not in d["roles"] and "pool-member" not in d["roles"] and not d["pool"] \
                and not any(covered_by_backup(x, m) for x in d["mounts"]) and (d["partitions"] and any((p.get("usage") or {}).get("used", 0) > 1e9 for p in d["partitions"])):
            out.append({"id": "nobackup:" + d["serial"], "level": "info", "title": f"Nothing backs up {', '.join(d['mounts'])}",
                        "detail": f"{d['size_text']} {d['model'] or d['name']}.", "actions": [{"label": "Back it up", "action": "backup-wizard", "sources": d["mounts"]}]})
    for j in m["jobs"]:
        dest = j["dest"]
        if dest.get("type") == "local":
            u = usage(dest.get("path", ""))
            need = sum((usage(s) or {}).get("used", 0) for s in j["sources"])
            if u and need and u["total"] < need:
                out.append({"id": "small:" + j["id"], "level": "warning", "title": f"{j['name']}: the backup drive is smaller than what it backs up",
                            "detail": f"{human(need)} to back up, {human(u['total'])} on the drive.", "actions": []})
    branch_mounts = {b for p in m["pools"] for b in p["members"]}
    nf = [x for x in out if x["id"].startswith("nofail:")]
    if len(nf) > 1:
        out = [x for x in out if not x["id"].startswith("nofail:")]
        ms = [mm for x in nf for mm in x["actions"][0]["spec"]["mounts"]]
        out.append({"id": "nofail", "level": "info", "title": "Don't let a missing drive stop the boot",
                    "detail": f"{len(ms)} drives ({', '.join(ms)}) make the server wait at startup if they're missing. With 'nofail' it starts anyway and Nova tells you which drive is gone"
                              + (" (a pool then starts without that drive: its files are hidden until it's back)." if any(mm in branch_mounts for mm in ms) else "."),
                    "actions": [{"label": "Fix it", "action": "task", "kind": "fstab-nofail", "spec": {"mounts": ms}}]})
    order = {"critical": 0, "warning": 1, "info": 2}
    return sorted(out, key=lambda s: order[s["level"]])


# ── checks shared by the operations ───────────────────────────────────────────────────
def find_drive(serial, smap=None):
    smap = smap or storage_map()
    d = next((x for x in smap["drives"] if x["serial"] == serial), None)
    if not d: raise ValueError(f"no drive {serial} (was it unplugged?)")
    return d

def check_erasable(serials, confirm):
    """Every drive must exist, be idle (nothing mounted, not in an array, not the system drive) and be
    exactly the set the user confirmed."""
    if not serials: raise ValueError("pick at least one drive")
    if sorted(serials) != sorted(confirm or []): raise ValueError("the drives changed since you confirmed — start again")
    smap = storage_map(); ds = []
    for s in serials:
        d = find_drive(s, smap)
        if d["system"]: raise ValueError(f"{d['model'] or d['name']} is the system drive")
        if d["mounts"]: raise ValueError(f"{d['model'] or d['name']} is mounted at {', '.join(d['mounts'])} — unmount it first")
        if d["pool"]: raise ValueError(f"{d['model'] or d['name']} is part of an array")
        ds.append(d)
    return ds

def check_name(name):
    if not NAME_RE.fullmatch(name or ""): raise ValueError("name: lowercase letters, numbers, - and _ (up to 24)")
    return name

def check_mountpoint(mp):
    if not re.fullmatch(r"/(mnt|srv|media|data)/[A-Za-z0-9][A-Za-z0-9_.-]{0,40}", mp or ""): raise ValueError("mount point must be like /mnt/name")
    if mp in PROTECTED: raise ValueError("that's a protected location")
    if os.path.ismount(mp): raise ValueError(f"something is already mounted at {mp}")
    if os.path.isdir(mp) and os.listdir(mp): raise ValueError(f"{mp} already has files in it")
    if any(e["mount"] == mp for e in fstab_entries()): raise ValueError(f"{mp} is already in /etc/fstab")
    return mp

def fstab_append(line, comment):
    if TEST and FSTAB == "/etc/fstab": raise RuntimeError("test mode must not write /etc/fstab")
    shutil.copy2(FSTAB, FSTAB + ".nova-bak") if os.path.exists(FSTAB) else None
    with open(FSTAB, "a") as f: f.write(f"# nova: {comment}\n{line}\n")

def fstab_remove(mount):
    lines = fstab_lines(); out = []; skip_comment = False
    for i, l in enumerate(lines):
        f = l.split()
        if len(f) >= 2 and not l.lstrip().startswith("#") and f[1] == mount:
            if out and out[-1].startswith("# nova:"): out.pop()
            continue
        out.append(l)
    shutil.copy2(FSTAB, FSTAB + ".nova-bak")
    tmp = FSTAB + ".nova-tmp"; open(tmp, "w").write("\n".join(out) + "\n"); os.replace(tmp, FSTAB)

def fstab_edit_opts(mount, fn):
    lines = fstab_lines(); changed = False
    for i, l in enumerate(lines):
        f = l.split()
        if len(f) >= 4 and not l.lstrip().startswith("#") and f[1] == mount:
            new = fn(f[3])
            if new != f[3]: f[3] = new; lines[i] = "  ".join(f); changed = True
    if changed:
        shutil.copy2(FSTAB, FSTAB + ".nova-bak")
        tmp = FSTAB + ".nova-tmp"; open(tmp, "w").write("\n".join(lines) + "\n"); os.replace(tmp, FSTAB)
    return changed

def reload_systemd():
    if not TEST: run(["systemctl", "daemon-reload"], timeout=60)

def settle():
    run(["udevadm", "settle", "--timeout=20"], timeout=30)

def blkid_uuid(dev):
    for _ in range(10):
        rc, so, _ = run(["blkid", "-s", "UUID", "-o", "value", dev], timeout=20)
        if so.strip(): return so.strip()
        time.sleep(0.5); settle()
    raise RuntimeError(f"no filesystem UUID on {dev}")

def part_path(disk_path, n=1):
    return f"{disk_path}p{n}" if re.search(r"\d$", disk_path) else f"{disk_path}{n}"


# ── operations (called from tasks.py; `log(msg)` and `progress(pct)` report back) ─────
def wipe_and_partition(d, log, label="nova", raid=False):
    log(f"Erasing {d['size_text']} {d['model'] or d['name']}…")
    run(["wipefs", "-a", "-f", *[p["path"] for p in d["partitions"] if p["path"] != d["path"]], d["path"]], timeout=120, check=True)
    run(["parted", "-s", "-a", "optimal", d["path"], "mklabel", "gpt", "mkpart", label, "1MiB", "100%"] + (["set", "1", "raid", "on"] if raid else []), timeout=120, check=True)
    run(["partprobe", d["path"]], timeout=60); settle()
    p = part_path(d["path"])
    for _ in range(20):
        if os.path.exists(p): break
        time.sleep(0.5); settle()
    return p

def mkfs(dev, fs, label, log):
    log(f"Creating the {fs} filesystem…")
    lab = label[:16]
    cmd = {"ext4": ["mkfs.ext4", "-F", "-m", "1", "-L", lab, "-E", "lazy_itable_init=1,lazy_journal_init=1", dev],
           "xfs": ["mkfs.xfs", "-f", "-L", lab[:12], dev], "btrfs": ["mkfs.btrfs", "-f", "-L", lab, dev],
           "exfat": ["mkfs.exfat", "-L", lab[:11], dev]}[fs]
    run(cmd, timeout=3600, check=True)

def fs_opts(fs):
    return {"ext4": "defaults,noatime", "xfs": "defaults,noatime", "btrfs": "defaults,noatime,compress=zstd:1", "exfat": "defaults,noatime,uid=1000,gid=1000,umask=002"}[fs] \
        + ",nofail,x-systemd.device-timeout=10s"

def mount_new(dev, mp, fs, comment, log):
    uuid = blkid_uuid(dev)
    os.makedirs(mp, exist_ok=True)
    fstab_append(f"UUID={uuid}  {mp}  {fs}  {fs_opts(fs)}  0  {0 if fs in ('btrfs', 'exfat') else 2}", comment)
    reload_systemd()
    log(f"Mounting at {mp}…")
    run(["mount", "-t", fs, "-o", fs_opts(fs).replace(",nofail,x-systemd.device-timeout=10s", ""), dev, mp], timeout=120, check=True)
    owner = SITE.get("ssh_user") or ""
    if owner and fs != "exfat":
        try: shutil.chown(mp, owner, owner)
        except (LookupError, OSError): pass
    return uuid

def op_format(spec, log, progress):
    """One drive → one filesystem mounted at a folder (e.g. a backup drive)."""
    fs = spec.get("fs", "ext4"); name = check_name(spec["name"]); mp = check_mountpoint(spec.get("mount") or f"/mnt/{name}")
    if fs not in FILESYSTEMS: raise ValueError("unknown filesystem")
    ensure_tools(["parted", "wipefs"] + ({"xfs": ["mkfs.xfs"], "btrfs": ["mkfs.btrfs"], "exfat": ["mkfs.exfat"]}.get(fs, [])), log)
    (d,) = check_erasable(spec["drives"][:1], spec.get("confirm"))
    progress(10); p = wipe_and_partition(d, log, label=name)
    progress(30); mkfs(p, fs, name, log)
    progress(80); mount_new(p, mp, fs, f"drive {name} ({d['model'] or d['serial']})", log)
    progress(100); log(f"Done — {d['size_text']} ready at {mp}.")
    return {"mount": mp}

def op_combine(spec, log, progress):
    """Several drives → one big folder (mergerfs). Drives are either erased first, or kept with their
    files (`keep`: list of serials whose existing mounted filesystem is used as-is)."""
    name = check_name(spec["name"]); mp = check_mountpoint(spec.get("mount") or f"/mnt/{name}")
    fs = spec.get("fs", "ext4")
    if fs not in ("ext4", "xfs", "btrfs"): raise ValueError("pools need ext4, xfs or btrfs")
    erase, keep = list(spec.get("drives", [])), list(spec.get("keep", []))
    if len(erase) + len(keep) < 2: raise ValueError("pick at least two drives")
    ensure_tools(["mergerfs", "parted", "wipefs"] + ({"xfs": ["mkfs.xfs"], "btrfs": ["mkfs.btrfs"]}.get(fs, [])), log)
    smap = storage_map(); branches = []
    for s in keep:
        d = find_drive(s, smap)
        if d["system"] or d["pool"] or not d["mounts"]: raise ValueError(f"{d['model'] or s}: only a mounted data drive can be added with its files")
        if any(r in d["roles"] for r in ("pool-member", "backup")): raise ValueError(f"{d['model'] or s} is already in use ({', '.join(d['uses'])})")
        branches.append(d["mounts"][0])
    ds = check_erasable(erase, spec.get("confirm")) if erase else []
    for i, d in enumerate(ds, 1):
        progress(5 + 70 * (i - 1) / max(1, len(ds)))
        bmp = check_mountpoint(f"/mnt/{name}-disk{i}")
        p = wipe_and_partition(d, log, label=f"{name}{i}"); mkfs(p, fs, f"{name}{i}", log)
        mount_new(p, bmp, fs, f"{name} pool member {i} ({d['model'] or d['serial']})", log); branches.append(bmp)
    progress(80); log("Combining them into one folder…")
    policy = spec.get("policy", "mfs")
    if policy not in ("mfs", "epmfs", "lfs", "pfrd", "rand"): raise ValueError("unknown placement policy")
    smallest = min((usage(b) or {"total": 0})["total"] for b in branches) or 0
    minfree = max(64, min(4096, int(smallest * 0.02 / 1e6)))          # 2% of the smallest drive, 64 MB – 4 GB
    opts = f"defaults,allow_other,use_ino,cache.files=off,dropcacheonclose=true,category.create={policy},moveonenospc=true,minfreespace={minfree}M,fsname=nova-{name},nofail"
    reqs = ",".join(f"x-systemd.requires-mounts-for={b}" for b in branches)
    os.makedirs(mp, exist_ok=True)
    fstab_append(f"{':'.join(branches)}  {mp}  fuse.mergerfs  {opts},{reqs}  0  0", f"pool {name} (combined drives)")
    reload_systemd()
    run(["mergerfs", "-o", opts.replace(",nofail", ""), ":".join(branches), mp], timeout=60, check=True)
    progress(100); log(f"Done — {len(branches)} drives as one at {mp}.")
    return {"mount": mp, "branches": branches}

def op_raid(spec, log, progress):
    """RAID 0/1/5/6/10 with mdadm, one filesystem on top."""
    level = spec["level"]; name = check_name(spec["name"]); mp = check_mountpoint(spec.get("mount") or f"/mnt/{name}")
    fs = spec.get("fs", "ext4")
    if level not in LEVELS or level == "combine": raise ValueError("unknown RAID level")
    if fs not in ("ext4", "xfs", "btrfs"): raise ValueError("RAID needs ext4, xfs or btrfs")
    ensure_tools(["mdadm", "parted", "wipefs"] + ({"xfs": ["mkfs.xfs"], "btrfs": ["mkfs.btrfs"]}.get(fs, [])), log)
    ds = check_erasable(spec["drives"], spec.get("confirm"))
    if len(ds) < MIN_DRIVES[level]: raise ValueError(f"{level.upper()} needs at least {MIN_DRIVES[level]} drives")
    if os.path.exists(f"/dev/md/{name}"): raise ValueError(f"an array called {name} already exists")
    parts = []
    for i, d in enumerate(ds):
        progress(5 + 30 * i / len(ds)); parts.append(wipe_and_partition(d, log, label=f"{name}{i + 1}", raid=True))
        run(["mdadm", "--zero-superblock", "--force", parts[-1]], timeout=60)
    progress(40); log(f"Building the {level.upper()} array…")
    run(["mdadm", "--create", f"/dev/md/{name}", "--run", "--metadata=1.2", f"--name={name}", f"--level={LEVELS[level]}",
         f"--raid-devices={len(parts)}", *parts], timeout=300, check=True)
    settle()
    md = os.path.realpath(f"/dev/md/{name}")
    progress(60); mkfs(md, fs, name, log)
    rc, so, _ = run(["mdadm", "--detail", "--brief", md], timeout=60)
    if so.strip():
        if TEST and MDCONF == "/etc/mdadm/mdadm.conf": raise RuntimeError("test mode must not write mdadm.conf")
        os.makedirs(os.path.dirname(MDCONF), exist_ok=True)
        with open(MDCONF, "a") as f: f.write(f"# nova: array {name}\n{so.strip()}\n")
    progress(75); mount_new(md, mp, fs, f"array {name} ({level})", log)
    if not TEST:
        log("Updating the boot image so the array assembles at startup…")
        run(["update-initramfs", "-u"], timeout=900)
    progress(100)
    log(f"Done — usable now at {mp}." + (" Redundancy finishes building in the background." if level != "raid0" else ""))
    return {"mount": mp, "dev": md}

def op_pool_remove(spec, log, progress):
    """Take a pool apart. Combined pools: the drives keep their files. Arrays: stopped; `erase` also wipes the members."""
    smap = storage_map()
    p = next((x for x in smap["pools"] if x["id"] == spec.get("pool")), None)
    if not p: raise ValueError("no such pool")
    if p["mount"] in PROTECTED: raise ValueError("that's a protected location")
    if sorted(spec.get("confirm") or []) != [p["id"]]: raise ValueError("confirm the pool to remove")
    users = [c for src, cs in docker_users().items() if p["mount"] and (src == p["mount"] or src.startswith(p["mount"] + "/")) for c in cs]
    if users and not spec.get("force"): raise ValueError(f"in use by {', '.join(sorted(set(users)))} — stop them first")
    if p["mount"]:
        log(f"Unmounting {p['mount']}…")
        rc, _, se = run(["umount", p["mount"]], timeout=120)
        if rc != 0 and os.path.ismount(p["mount"]): raise RuntimeError("couldn't unmount (something is using it): " + se.strip()[-200:])
        fstab_remove(p["mount"]); reload_systemd()
    progress(50)
    if p["type"] != "combine":
        rc, so, _ = run(["mdadm", "--detail", "--export", p["dev"]], timeout=60)
        md_uuid = (re.search(r"^MD_UUID=(\S+)", so, re.M) or [None, ""])[1]
        log("Stopping the array…")
        run(["mdadm", "--stop", p["dev"]], timeout=120, check=True)
        if md_uuid and os.path.exists(MDCONF):
            out = []
            for l in open(MDCONF).read().splitlines():
                if md_uuid in l:
                    if out and out[-1].startswith("# nova:"): out.pop()
                    continue
                out.append(l)
            open(MDCONF, "w").write("\n".join(out) + "\n")
        if spec.get("erase"):
            for mem in p["members"]:
                log(f"Wiping {mem}…"); run(["mdadm", "--zero-superblock", "--force", f"/dev/{mem}"], timeout=60); run(["wipefs", "-a", "-f", f"/dev/{mem}"], timeout=60)
        if not TEST: run(["update-initramfs", "-u"], timeout=900)
    progress(100); log("Done." + (" The drives still hold their files and stay mounted where they were." if p["type"] == "combine" else ""))
    return {}

def op_pool_add(spec, log, progress):
    """Add a drive to a combined pool (optionally erasing it first), or a replacement/spare to an array."""
    smap = storage_map()
    p = next((x for x in smap["pools"] if x["id"] == spec.get("pool")), None)
    if not p: raise ValueError("no such pool")
    if p["type"] == "combine":
        if spec.get("keep"):
            d = find_drive(spec["keep"], smap)
            if d["system"] or d["pool"] or not d["mounts"] or d["roles"] != ["data"]: raise ValueError("only an unused mounted data drive can be added with its files")
            bmp = d["mounts"][0]
        else:
            (d,) = check_erasable(spec["drives"][:1], spec.get("confirm"))
            fs = spec.get("fs", "ext4"); ensure_tools(["parted", "wipefs"] + ({"xfs": ["mkfs.xfs"], "btrfs": ["mkfs.btrfs"]}.get(fs, [])), log)
            i = len(p["members"]) + 1
            while os.path.exists(f"/mnt/{p['name']}-disk{i}"): i += 1
            bmp = check_mountpoint(f"/mnt/{p['name']}-disk{i}")
            part = wipe_and_partition(d, log, label=f"{p['name']}{i}"); mkfs(part, fs, f"{p['name']}{i}", log)
            mount_new(part, bmp, fs, f"{p['name']} pool member {i} ({d['model'] or d['serial']})", log)
        progress(80); log(f"Adding {bmp} to {p['name']}…")
        try: os.setxattr(os.path.join(p["mount"], ".mergerfs"), "user.mergerfs.branches", f"+>{bmp}".encode())
        except OSError:
            try: os.setxattr(os.path.join(p["mount"], ".mergerfs"), "user.mergerfs.srcmounts", f"+>{bmp}".encode())
            except OSError as e: log(f"(couldn't add it to the running pool: {e}; it joins after a restart)")
        fstab_edit_line_src(p["mount"], lambda src: src if bmp in src.split(":") else src + ":" + bmp, bmp)
        progress(100); log("Done — the pool is bigger now. New files spread onto the new drive.")
        return {"branch": bmp}
    (d,) = check_erasable(spec["drives"][:1], spec.get("confirm"))
    ensure_tools(["mdadm", "parted", "wipefs"], log)
    part = wipe_and_partition(d, log, label=f"{p['name']}x", raid=True)
    progress(50); log(f"Adding it to {p['name']} — the array rebuilds onto it in the background…")
    run(["mdadm", "--manage", p["dev"], "--add", part], timeout=120, check=True)
    for f in p.get("failed", []): run(["mdadm", "--manage", p["dev"], "--remove", f"/dev/{f}"], timeout=60)
    progress(100); log("Done. Rebuild progress shows on the storage map.")
    return {}

def fstab_edit_line_src(mount, fn, branch):
    lines = fstab_lines()
    for i, l in enumerate(lines):
        f = l.split()
        if len(f) >= 4 and not l.lstrip().startswith("#") and f[1] == mount:
            f[0] = fn(f[0])
            if f"x-systemd.requires-mounts-for={branch}" not in f[3]: f[3] += f",x-systemd.requires-mounts-for={branch}"
            lines[i] = "  ".join(f)
    shutil.copy2(FSTAB, FSTAB + ".nova-bak")
    tmp = FSTAB + ".nova-tmp"; open(tmp, "w").write("\n".join(lines) + "\n"); os.replace(tmp, FSTAB)
    reload_systemd()

def op_fstab_nofail(spec, log, progress):
    ms = [m for m in spec.get("mounts", []) if m not in SYSTEM_MOUNTS and m not in PROTECTED]
    if not ms: raise ValueError("nothing to change")
    for m in ms:
        if fstab_edit_opts(m, lambda o: o if "nofail" in o else o + ",nofail,x-systemd.device-timeout=10s"): log(f"{m}: won't hold up the boot any more")
    reload_systemd(); progress(100); return {}

def op_fstab_add(spec, log, progress):
    mp = mounts()
    for m in spec.get("mounts", []):
        if m in PROTECTED or m not in mp or any(e["mount"] == m for e in fstab_entries()): continue
        src = mp[m]["src"]; t = mp[m]["type"]
        if not src.startswith("/dev/") or t not in FILESYSTEMS: raise ValueError(f"{m}: only drive filesystems can be added here")
        mount_uuid = blkid_uuid(src)
        fstab_append(f"UUID={mount_uuid}  {m}  {t}  {fs_opts(t)}  0  {0 if t in ('btrfs', 'exfat') else 2}", f"kept mounted ({m})")
        log(f"{m} now comes back after a restart")
    reload_systemd(); progress(100); return {}

# ── repair and clone ─────────────────────────────────────────────────────────────────────
def device_users(d):
    """Programs (other than the kernel's own mounts) that have this drive or one of its partitions open —
    a running dd/ddrescue clone, a disk tool… Repair and clone refuse to touch a drive someone else is using."""
    names = {os.path.basename(p["path"]) for p in d["partitions"]} | {d["name"]}
    out = []
    for pid in os.listdir("/proc"):
        if not pid.isdigit() or int(pid) == os.getpid(): continue
        try:
            for fd in os.listdir(f"/proc/{pid}/fd"):
                try: t = os.readlink(f"/proc/{pid}/fd/{fd}")
                except OSError: continue
                if t.startswith("/dev/") and os.path.basename(t) in names:
                    out.append(f"{open(f'/proc/{pid}/comm').read().strip()} (pid {pid})"); break
        except OSError: continue
    for n in names:
        if glob.glob(f"/sys/class/block/{n}/holders/*"): out.append(f"{n} is held by {', '.join(os.path.basename(h) for h in glob.glob(f'/sys/class/block/{n}/holders/*'))}")
    return out

def check_touchable(d, what):
    if d["system"]: raise ValueError(f"{d['model'] or d['name']} is the system drive — Nova won't {what} the drive the server is running from")
    if d["pool"]: raise ValueError(f"{d['model'] or d['name']} is part of an array — repair or replace it from the array's page instead")
    if any("pool-member" in r for r in d["roles"]): raise ValueError(f"{d['model'] or d['name']} is part of a pool — take it out of the pool first")
    busy = device_users(d)
    if busy: raise ValueError(f"{d['model'] or d['name']} is in use by {', '.join(busy[:3])} — wait for that to finish (or stop it) first")

def unmount_all(d, log):
    """Unmount every filesystem on the drive; returns the mount points so they can be put back."""
    was = []
    for p in d["partitions"]:
        for m in p["mounts"]:
            if m in PROTECTED: raise ValueError(f"{m} is a protected location")
            log(f"Unmounting {m}…")
            rc, _, err = run(["umount", m], timeout=120)
            if rc != 0:
                remount(was, log)
                raise ValueError(f"{m} is busy ({err.strip()[-120:] or 'in use'}) — stop the apps using it, then try again")
            was.append((p["path"], m))
    return was

def remount(mps, log):
    """Mount each one back from its own device — not through fstab's UUID=, which a fresh clone shares until its IDs change."""
    fe = {e["mount"]: e for e in fstab_entries()}
    for dev, m in mps:
        e = fe.get(m); opts = [o for o in (e or {}).get("opts", "").split(",") if o and o not in ("defaults", "nofail", "auto", "noauto") and not o.startswith("x-systemd")]
        cmd = ["mount"] + (["-t", e["type"]] if e and e.get("type") not in (None, "", "auto") else []) + (["-o", ",".join(opts)] if opts else []) + [dev, m]
        rc, _, err = run(cmd, timeout=120)
        log(f"Mounted {m} again" if rc == 0 else f"Couldn't mount {m} again: {err.strip()[-160:]}")

FSCK = {"ext2": ["e2fsck", "-f", "-y"], "ext3": ["e2fsck", "-f", "-y"], "ext4": ["e2fsck", "-f", "-y"], "xfs": ["xfs_repair"],
        "vfat": ["fsck.vfat", "-a", "-w"], "exfat": ["fsck.exfat", "-y"], "ntfs": ["ntfsfix", "-d"], "btrfs": ["btrfs", "check", "--readonly"]}
FSCK_PKG = {"e2fsck": "e2fsprogs", "xfs_repair": "xfsprogs", "fsck.vfat": "dosfstools", "fsck.exfat": "exfatprogs", "ntfsfix": "ntfs-3g", "btrfs": "btrfs-progs"}

def repair_fs(dev, fs, log):
    """Check one filesystem and fix what can be fixed. Returns (ok, note)."""
    cmd = FSCK.get(fs)
    if not cmd: return True, f"{fs or 'no filesystem'} — nothing to check"
    TOOLS.setdefault(cmd[0], FSCK_PKG[cmd[0]]); ensure_tools([cmd[0]], log)
    log(f"Checking {dev} ({fs})…")
    rc, so, se = run(cmd + [dev], timeout=6 * 3600)
    out = (so + se).strip()
    if fs == "xfs" and rc != 0 and "mount the filesystem" in out:            # a dirty log: replaying it by mounting is the safe way
        tmp = f"/run/nova-repair-{os.path.basename(dev)}"; os.makedirs(tmp, exist_ok=True)
        if run(["mount", "-t", "xfs", dev, tmp], timeout=300)[0] == 0: run(["umount", tmp], timeout=300)
        os.rmdir(tmp); rc, so, se = run(cmd + [dev], timeout=6 * 3600); out = (so + se).strip()
    if fs.startswith("ext"): ok = rc in (0, 1, 2); fixed = rc in (1, 2)          # e2fsck: 1 = fixed, 2 = fixed, reboot advised; 4+ = left unfixed
    elif fs == "btrfs": ok = rc == 0; fixed = False
    else: ok = rc == 0; fixed = ok and bool(re.search(r"fix|correct|repair|recover", out, re.I))
    last = out.splitlines()[-1][:200] if out else ""
    if not ok and fs == "btrfs": return False, "Btrfs reports problems. Nova doesn't run its risky --repair; copy the files off (Clone) and set the drive up again"
    return ok, ("fixed problems" if fixed else "no problems found") if ok else f"couldn't fix everything: {last}"

def op_repair(spec, log, progress):
    """Unmount, check and fix every filesystem on the drive, mount it back, then ask the drive to test itself."""
    d = find_drive(spec["drives"][0]) if spec.get("drives") else None
    if not d: raise ValueError("pick a drive")
    if sorted(spec.get("confirm") or []) != [d["serial"]]: raise ValueError("the drive changed since you confirmed — start again")
    check_touchable(d, "repair")
    parts = [p for p in d["partitions"] if p["fstype"] and not p["swap"]]
    if not parts: raise ValueError("there's no filesystem on this drive to repair")
    progress(5); was = unmount_all(d, log); results = []
    try:
        for i, p in enumerate(parts):
            progress(10 + 75 * i / len(parts))
            ok, note = repair_fs(p["path"], p["fstype"], log); results.append({"part": p["path"], "fs": p["fstype"], "ok": ok, "note": note})
            log(f"{p['path']}: {note}")
    finally:
        progress(88); remount(was, log)
    if have("smartctl") and not d["name"].startswith(("loop", "md")):
        rc, _, _ = run(["smartctl", "-t", "short", d["path"]], timeout=60)
        if rc in (0, 4): log("Started the drive's own 2-minute self-test — the result shows on the drive's page")
    progress(100)
    bad = [r for r in results if not r["ok"]]
    log(f"Done — {'some problems are left: ' + bad[0]['note'] if bad else 'every filesystem checked and fixed where needed'}.")
    return {"results": results}

def used_bytes(p):
    """How much is stored on a partition (mounting it read-only for a moment if it isn't mounted)."""
    if p["usage"]: return p["usage"]["used"]
    tmp = f"/run/nova-clone-src-{os.path.basename(p['path'])}"; os.makedirs(tmp, exist_ok=True)
    try:
        if run(["mount", "-o", "ro", p["path"], tmp], timeout=120)[0] != 0: return None
        try: return usage(tmp)["used"]
        finally: run(["umount", tmp], timeout=120)
    finally:
        try: os.rmdir(tmp)
        except OSError: pass

def new_ids(dev, fs, log):
    """A clone must not share filesystem UUIDs with its source, or fstab would mount the wrong one."""
    rnd = os.urandom(4).hex()
    cmd = {"ext2": ["tune2fs", "-U", "random"], "ext3": ["tune2fs", "-U", "random"], "ext4": ["tune2fs", "-U", "random"], "xfs": ["xfs_admin", "-U", "generate"],
           "btrfs": ["btrfstune", "-f", "-u"], "vfat": ["fatlabel", "-i", "-r"], "exfat": ["tune.exfat", "-I", "0x" + rnd], "ntfs": ["ntfslabel", "--new-serial"]}.get(fs)
    if not cmd: return
    if fs.startswith("ext"): run(["e2fsck", "-f", "-p", dev], timeout=3600)
    if not have(cmd[0]): log(f"Can't give {dev} a new ID ({cmd[0]} isn't installed)"); return
    rc, _, err = run(cmd + [dev], timeout=3600)
    if rc != 0: log(f"Couldn't give {dev} a new ID: {err.strip()[-120:]}")

def grow_last(dst, fs_by_n, log):
    """After an exact copy to a bigger drive: move the partition table's backup copy to the real end, then let the
    last partition and its filesystem use the extra space."""
    if have("sgdisk"): run(["sgdisk", "-e", dst["path"]], timeout=60)
    else: subprocess.run(["parted", "---pretend-input-tty", dst["path"], "print"], input="Fix\n", capture_output=True, text=True, errors="replace", timeout=60)
    run(["partprobe", dst["path"]], timeout=60); settle()
    rc, so, _ = run(["lsblk", "-J", "-b", "-o", "NAME,PATH,FSTYPE,TYPE", dst["path"]], timeout=30)
    kids = [c for c in (json.loads(so)["blockdevices"][0].get("children") or []) if c["type"] == "part"] if rc == 0 else []
    if not kids: return
    last = kids[-1]; n = re.search(r"(\d+)$", last["name"]).group(1); fs = last.get("fstype") or ""
    if fs not in ("ext2", "ext3", "ext4", "xfs", "btrfs"): log("The copy is exact; the extra space is left free (that filesystem can't be grown here)"); return
    log("Using the extra space…")
    if run(["parted", "-s", dst["path"], "resizepart", n, "100%"], timeout=120)[0] != 0: log("Couldn't grow the last partition — the extra space is left free"); return
    run(["partprobe", dst["path"]], timeout=60); settle()
    if fs.startswith("ext"): run(["e2fsck", "-f", "-p", last["path"]], timeout=3600); run(["resize2fs", last["path"]], timeout=3600)
    else:
        tmp = f"/run/nova-clone-grow"; os.makedirs(tmp, exist_ok=True)
        if run(["mount", last["path"], tmp], timeout=120)[0] == 0:
            run(["xfs_growfs", tmp] if fs == "xfs" else ["btrfs", "filesystem", "resize", "max", tmp], timeout=3600); run(["umount", tmp], timeout=120)
        os.rmdir(tmp)

def op_clone(spec, log, progress):
    """Copy one drive onto another (which is erased). Same size or bigger: an exact copy, then the last partition grows
    into the extra space. Smaller: if the files fit, each filesystem is recreated at a fitting size and the files copied in."""
    sv = spec.get("drives") or []
    if len(sv) != 2 or sv[0] == sv[1]: raise ValueError("pick the drive to copy and a different drive to copy it to")
    smap = storage_map(); src = find_drive(sv[0], smap)
    (dst,) = check_erasable([sv[1]], spec.get("confirm"))
    check_touchable(src, "clone"); check_touchable(dst, "erase")
    parts = [p for p in src["partitions"] if not p["swap"]]
    if not parts: raise ValueError("the drive you're copying is empty")
    was = []
    try:
        progress(2); was = unmount_all(src, log)                                     # nothing changes on it while it's copied
        if dst["size"] >= src["size"]:
            gpt = run(["lsblk", "-dno", "PTTYPE", src["path"]], timeout=30)[1].strip() == "gpt"
            ensure_tools(["parted"] + (["sgdisk"] if gpt else []), log)
            try: ensure_tools(["ddrescue"], log)                                       # skips bad spots and says exactly what couldn't be read
            except RuntimeError as e: log(f"Couldn't install ddrescue ({e}) — copying with dd, which can't report unreadable spots")
            rescue = have("ddrescue")
            log(f"Copying {src['size_text']} {src['model'] or src['name']} onto {dst['size_text']} {dst['model'] or dst['name']} — every sector, exactly…")
            run(["wipefs", "-a", "-f", *[p["path"] for p in dst["partitions"] if p["path"] != dst["path"]], dst["path"]], timeout=120)
            mapf = f"/run/nova-clone-{src['name']}-{dst['name']}.map"                 # a fresh map: an old one would make ddrescue skip everything
            try: os.remove(mapf)
            except OSError: pass
            cmd = ["ddrescue", "-f", "-n", src["path"], dst["path"], mapf] if rescue else \
                  ["dd", f"if={src['path']}", f"of={dst['path']}", "bs=16M", "conv=noerror,sync,fsync", "status=progress"]
            pr = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace", bufsize=1)   # ddrescue reports on stdout, dd on stderr
            buf, t0 = "", time.time()
            for ch in iter(lambda: pr.stdout.read(1), ""):                          # dd/ddrescue redraw with \r
                if ch in "\r\n":
                    m = re.search(r"^(\d+) bytes", buf.strip()) or re.search(r"rescued:\s*([\d.]+)\s*([kMGT]?)B", buf)
                    if m:
                        done = int(m.group(1)) if m.re.pattern.startswith("^") else float(m.group(1)) * {"": 1, "k": 1e3, "M": 1e6, "G": 1e9, "T": 1e12}[m.group(2)]
                        el = max(1, time.time() - t0); pct = done / src["size"]
                        progress(5 + 85 * pct, f"{human(done)} of {src['size_text']} · {human(done / el)}/s" + (f" · about {int((src['size'] - done) / max(1, done / el) / 60)} min left" if done else ""))
                    buf = ""
                else: buf += ch
            rc = pr.wait()
            unreadable = 0
            if rescue:
                try:                                                                 # map lines: pos size status — '-' bad, '*' '/' not read
                    for line in open(mapf):
                        f = line.split()
                        if len(f) == 3 and not line.startswith("#") and f[2] in "-*/": unreadable += int(f[1], 16)
                except (OSError, ValueError): pass
                try: os.remove(mapf)
                except OSError: pass
            if rc != 0: raise RuntimeError(f"{cmd[0]} stopped with an error — the drive you're copying may be failing" + (" (install gddrescue for a copy that skips bad spots)" if not rescue else ""))
            run(["sync"], timeout=600); run(["partprobe", dst["path"]], timeout=60); settle()
            if gpt and have("sgdisk"): run(["sgdisk", "-G", dst["path"]], timeout=60)  # new partition-table IDs too
            elif not gpt and have("sfdisk"): run(["sfdisk", "--disk-id", dst["path"], "0x" + os.urandom(4).hex()], timeout=60)   # MBR: the disk id makes the PARTUUIDs
            run(["partprobe", dst["path"]], timeout=60); settle()
            progress(92)
            for p in parts: new_ids(part_path(dst["path"], int(re.search(r"(\d+)$", p["name"]).group(1))) if p["path"] != src["path"] else dst["path"], p["fstype"], log)
            if dst["size"] > src["size"] * 1.01 and gpt: grow_last(dst, {}, log)
            elif dst["size"] > src["size"] * 1.01: log("The copy is exact; the extra space is left free (only GPT drives are grown automatically)")
            mode = "exact"
            if unreadable: log(f"⚠ {human(unreadable)} of {src['model'] or src['name']} couldn't be read — those spots are empty on the copy. Files there may be damaged.")
        else:
            # Smaller target: rebuild each filesystem at a size that fits, then copy the files
            ensure_tools(["parted", "wipefs", "rsync"], log)
            need = []
            for p in parts:
                if p["fstype"] not in FILESYSTEMS + ("ext2", "ext3", "vfat"): raise ValueError(f"{p['path']} ({p['fstype'] or 'no filesystem'}) can only be copied exactly — that needs a drive at least {src['size_text']}")
                u = used_bytes(p)
                if u is None: raise ValueError(f"couldn't read {p['path']} to see how much is on it")
                need.append(u)
            room = dst["size"] - 64 * 2**20
            if sum(need) * 1.05 > room: raise ValueError(f"{dst['size_text']} is too small: the files take {human(sum(need))} (plus a little room)")
            spare = room - sum(n * 1.05 for n in need)                                 # share what's left by each partition's original size
            sizes = [n * 1.05 + spare * p["size"] / sum(q["size"] for q in parts) for n, p in zip(need, parts)]
            log(f"{dst['size_text']} is smaller than {src['size_text']}, but the files ({human(sum(need))}) fit — copying them file by file")
            run(["wipefs", "-a", "-f", *[p["path"] for p in dst["partitions"] if p["path"] != dst["path"]], dst["path"]], timeout=120, check=True)
            cmd = ["parted", "-s", "-a", "optimal", dst["path"], "mklabel", "gpt"]; start = 1
            for i, (p, sz) in enumerate(zip(parts, sizes)):
                end = "100%" if i == len(parts) - 1 else f"{start + int(sz / 2**20)}MiB"
                cmd += ["mkpart", (p["label"] or f"part{i + 1}")[:30], f"{start}MiB", end]; start += int(sz / 2**20)
            run(cmd, timeout=120, check=True); run(["partprobe", dst["path"]], timeout=60); settle(); time.sleep(1)
            done_bytes = 0
            for i, p in enumerate(parts):
                tp = part_path(dst["path"], i + 1); fs = p["fstype"]
                if fs in ("ext2", "ext3"): fs = "ext4"
                if fs == "vfat":
                    ensure_tools(["mkfs.vfat"], log); run(["mkfs.vfat", "-F", "32", "-n", (p["label"] or "NOVA")[:11].upper(), tp], timeout=600, check=True)
                else: mkfs(tp, fs, p["label"] or f"clone{i + 1}", log)
                a, b = f"/run/nova-clone-a{i}", f"/run/nova-clone-b{i}"; os.makedirs(a, exist_ok=True); os.makedirs(b, exist_ok=True)
                run(["mount", "-o", "ro", p["path"], a], timeout=120, check=True)
                try:
                    run(["mount", tp, b], timeout=120, check=True)
                    try:
                        log(f"Copying the files from {p['path']}…")
                        pr = subprocess.Popen(["rsync", "-aHAX", "--numeric-ids", "--info=progress2", "--no-inc-recursive", a + "/", b + "/"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace", bufsize=1)
                        buf = ""
                        for ch in iter(lambda: pr.stdout.read(1), ""):
                            if ch in "\r\n":
                                m = re.search(r"^\s*([\d,]+)\s+(\d+)%", buf)
                                if m:
                                    cur = done_bytes + int(m.group(1).replace(",", ""))
                                    progress(5 + 88 * cur / max(1, sum(need)), f"{human(cur)} of {human(sum(need))}")
                                buf = ""
                            else: buf += ch
                        rc = pr.wait()
                        if rc not in (0, 24): raise RuntimeError(f"copying {p['path']} failed: {pr.stderr.read().strip()[-200:]}" + (" — some files couldn't be read; the drive may be failing" if rc == 23 else ""))
                    finally: run(["umount", b], timeout=300)
                finally:
                    run(["umount", a], timeout=300); os.rmdir(a); os.rmdir(b)
                done_bytes += need[i]
            mode = "files"
        progress(97)
    finally:
        remount(was, log)
    # the clone must not share IDs with the original, or the wrong one could be mounted (now or at the next boot)
    settle()
    def ids(dev):
        rc, so, _ = run(["lsblk", "-rno", "UUID,PARTUUID", dev], timeout=30)
        return {x for x in so.split() if x}
    shared = ids(src["path"]) & ids(dst["path"])
    if shared: log(f"⚠ The copy still shares an ID with the original ({', '.join(sorted(shared))[:80]}) — don't leave both plugged in at a restart, or change the copy's ID first")
    if mode == "exact" and unreadable:
        log(f"Done, with gaps — {dst['size_text']} {dst['model'] or dst['name']} has everything that could be read from {src['model'] or src['name']}; {human(unreadable)} couldn't be. It isn't mounted.")
        progress(100); return {"mode": mode, "unreadable": unreadable, "shared_ids": sorted(shared)}
    log(f"Done — {dst['size_text']} {dst['model'] or dst['name']} is now a copy of {src['model'] or src['name']}"
        + (" (exact copy)" if mode == "exact" else " (files copied; the filesystems were made to fit)") + ". It isn't mounted — set it up from Storage when you want to use it.")
    progress(100)
    return {"mode": mode, "unreadable": 0, "shared_ids": sorted(shared)}


# ── swap ────────────────────────────────────────────────────────────────────────────
SWAP_SIZES = (1, 2, 4, 8, 16)
SWAPFILE = os.environ.get("NOVA_SWAPFILE", "/swapfile") if TEST else "/swapfile"

def swap_active():
    try: return len(open("/proc/swaps").read().strip().splitlines()) > 1
    except OSError: return False

def op_swap_setup(spec, log, progress):
    """A swap file on the system drive, for a server that has no swap at all: made, switched on, kept after a restart."""
    gb = spec.get("gb")
    if gb not in SWAP_SIZES: raise ValueError("pick a swap size")
    if swap_active() and not TEST: raise ValueError("this server already has swap")
    if os.path.lexists(SWAPFILE): raise ValueError(f"{SWAPFILE} already exists")
    folder = os.path.dirname(SWAPFILE) or "/"
    if shutil.disk_usage(folder).free < (gb + 5) * 2**30: raise ValueError(f"not enough free space for {gb} GB of swap (it keeps 5 GB spare)")
    fs = run(["findmnt", "-no", "FSTYPE", "-T", folder])[1].strip()
    log(f"Making a {gb} GB swap file ({SWAPFILE})"); progress(10)
    try:
        if fs == "btrfs": run(["btrfs", "filesystem", "mkswapfile", "--size", f"{gb}g", SWAPFILE], timeout=900, check=True)
        else:
            if run(["fallocate", "-l", f"{gb}G", SWAPFILE], timeout=300)[0] != 0:       # filesystems without fallocate: write it out
                run(["dd", "if=/dev/zero", f"of={SWAPFILE}", "bs=1M", f"count={gb * 1024}", "status=none"], timeout=1800, check=True)
            os.chmod(SWAPFILE, 0o600); run(["mkswap", SWAPFILE], check=True)
        progress(70)
        if not TEST: run(["swapon", SWAPFILE], check=True)
        if not any(e.get("src") == SWAPFILE for e in fstab_entries()): fstab_append(f"{SWAPFILE}  none  swap  sw  0  0", "swap file")
    except Exception:
        if not TEST: run(["swapoff", SWAPFILE])
        try: os.remove(SWAPFILE)
        except OSError: pass
        raise
    log(f"Done — {gb} GB of swap is on and comes back after a restart"); progress(100)
    return {"gb": gb, "file": SWAPFILE}


def op_partition_apply(spec, log, progress):
    import partitions; return partitions.apply(spec, log, progress)

def op_partition_restore(spec, log, progress):
    import partitions; return partitions.restore(spec, log, progress)


OPS = {"format": op_format, "combine": op_combine, "raid": op_raid, "pool-remove": op_pool_remove, "pool-add": op_pool_add,
       "fstab-nofail": op_fstab_nofail, "fstab-add": op_fstab_add, "repair": op_repair, "clone": op_clone, "swap-setup": op_swap_setup, "partition-apply": op_partition_apply, "partition-restore": op_partition_restore}
DESTRUCTIVE = {"format", "combine", "raid", "pool-remove", "pool-add", "repair", "clone", "partition-apply", "partition-restore"}
