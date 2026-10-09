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
         "smartctl": "smartmontools", "tracepath": "iputils-tracepath", "setfattr": "attr"}


def run(cmd, timeout=600, check=False, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, **kw)
    if check and r.returncode != 0:
        raise RuntimeError(f"{os.path.basename(cmd[0])}: {(r.stderr or r.stdout).strip()[-300:]}")
    return r.returncode, r.stdout, r.stderr

def have(tool):
    return bool(shutil.which(tool) or any(os.path.exists(p + tool) for p in ("/sbin/", "/usr/sbin/")))

def missing_packages(tools):
    return sorted({TOOLS[t] for t in tools if t in TOOLS and not have(t)})

def ensure_tools(tools, log=print):
    """apt-get install whatever is missing for these tools (no-op when all are there)."""
    pk = missing_packages(tools)
    if not pk: return
    log(f"Installing {', '.join(pk)}…")
    env = {**os.environ, "DEBIAN_FRONTEND": "noninteractive"}
    rc, _, se = run(["apt-get", "install", "-y", "--no-install-recommends", *pk], timeout=900, env=env)
    if rc != 0:
        run(["apt-get", "update"], timeout=600, env=env)
        run(["apt-get", "install", "-y", "--no-install-recommends", *pk], timeout=900, env=env, check=True)

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
                       "smart": {k: sm.get(k) for k in ("passed", "temp", "realloc", "pending", "uncorrect", "crc")} if sm else None})
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

OPS = {"format": op_format, "combine": op_combine, "raid": op_raid, "pool-remove": op_pool_remove, "pool-add": op_pool_add,
       "fstab-nofail": op_fstab_nofail, "fstab-add": op_fstab_add}
DESTRUCTIVE = {"format", "combine", "raid", "pool-remove", "pool-add"}
