"""Partitions: a drive's partition table, edited the way KDE Partition Manager does it — look at the layout, queue up
changes (new table, create, delete, format, label, flags, grow, shrink), see the result, then apply them all at once.

The safety lives here, not in the apps:
  * a drive is only editable when nothing on it is in use: not the system drive, nothing mounted, not active swap, not
    part of a pool / RAID / LVM, and no program holding it open;
  * every plan is checked op by op against the real layout (alignment, overlaps, sizes, what each filesystem can do);
  * Apply re-reads the drive and refuses if it changed since the plan was made (a fingerprint of the layout);
  * the old table is saved before anything is touched, and can be put back ("Restore the previous table").
Moving a partition (changing where it starts) isn't offered: it means copying every byte, and a cut-off halfway loses
the partition. Shrinking leaves the start where it is.

Test mode (NOVA_STORAGE_TEST=1, see storage.py): loop devices count as drives and fstab is redirected."""
import hashlib, json, os, re, time
import storage as S

MiB = 1 << 20
DATA = "/var/lib/nova-api"
BACKUPS = os.environ.get("NOVA_PART_BACKUPS", f"{DATA}/partition-backups") if S.TEST else f"{DATA}/partition-backups"
NEW_FS = ("ext4", "xfs", "btrfs", "exfat", "ntfs", "vfat", "swap", "none")      # what Create / Format can make ("none": leave it empty)
MIN_SIZE = {"ext4": 32 * MiB, "xfs": 300 * MiB, "btrfs": 256 * MiB, "exfat": 8 * MiB, "ntfs": 8 * MiB, "vfat": 33 * MiB, "swap": 16 * MiB, "none": 1 * MiB}
GROWS = {"ext4", "xfs", "btrfs"}          # filesystems Nova can grow with their partition
SHRINKS = {"ext4"}                         # …and shrink (XFS can't shrink at all; btrfs only while mounted)
LABEL_MAX = {"ext4": 16, "xfs": 12, "btrfs": 255, "exfat": 11, "ntfs": 32, "vfat": 11, "swap": 15}
FLAGS = ("none", "esp", "bios_grub", "raid", "lvm")
GPT_TYPES = {"esp": "C12A7328-F81F-11D2-BA4B-00A0C93EC93B", "bios_grub": "21686148-6449-6E6F-744E-656564454649",
             "raid": "A19D880F-05FC-4D3B-A006-743F0F84911E", "lvm": "E6D6D379-F507-44C2-A23C-238F2A3DF928",
             "swap": "0657FD6D-A4AB-43C4-84E5-0933C84B4F4F", "linux": "0FC63DAF-8483-4772-8E79-3D69D8477DE4",
             "msdata": "EBD0A0A2-B9E5-4433-87C0-68B6B72699C7"}
DOS_TYPES = {"esp": "ef", "raid": "fd", "lvm": "8e", "swap": "82", "linux": "83", "msdata": "7"}
TYPE_NAMES = {GPT_TYPES["esp"]: "EFI system", GPT_TYPES["bios_grub"]: "BIOS boot", GPT_TYPES["raid"]: "Linux RAID", GPT_TYPES["lvm"]: "Linux LVM",
              GPT_TYPES["swap"]: "Linux swap", GPT_TYPES["linux"]: "Linux data", GPT_TYPES["msdata"]: "Windows / shared data",
              "ef": "EFI system", "fd": "Linux RAID", "8e": "Linux LVM", "82": "Linux swap", "83": "Linux data", "7": "Windows / shared data",
              "c": "FAT32", "b": "FAT32", "5": "Extended", "f": "Extended"}


# ── reading ─────────────────────────────────────────────────────────────────────────────
def _sector(name):
    try: return int(open(f"/sys/block/{name}/queue/logical_block_size").read())
    except (OSError, ValueError): return 512

def _table(path):
    rc, so, _ = S.run(["sfdisk", "-J", path], timeout=30)
    if rc != 0 or not so.strip(): return None
    return json.loads(so).get("partitiontable")

def _dump(path):
    rc, so, _ = S.run(["sfdisk", "-d", path], timeout=30)
    return so if rc == 0 else ""

def _flag_of(t):
    t = (t or "").upper() if len(t or "") > 4 else (t or "").lower()
    for f in ("esp", "bios_grub", "raid", "lvm"):
        if t in (GPT_TYPES.get(f, "-"), DOS_TYPES.get(f, "-")): return f
    return "none"

def busy_reasons(d):
    """Why this drive can't be edited right now (empty: it can be)."""
    why = []
    if d["system"]: why.append("it's the system drive — the server is running from it")
    if d["mounts"]: why.append("it's mounted at " + ", ".join(d["mounts"]) + " — unmount it first")
    if any(p.get("swap") for p in d["partitions"]): why.append("it has active swap on it")
    if d["pool"] or any(r in d["roles"] for r in ("raid-member", "pool-member")): why.append("it's part of a pool or array")
    if any(p.get("fstype") in ("LVM2_member", "linux_raid_member") for p in d["partitions"]): why.append("it's part of LVM or RAID")
    try: users = S.device_users(d)
    except Exception: users = []
    if users: why.append("it's in use by " + ", ".join(users[:3]))
    return why

def layout(serial):
    d = S.find_drive(serial)
    ss = _sector(d["name"]); total = d["size"] // ss
    t = _table(d["path"])
    by_node = {p["path"]: p for p in d["partitions"]}
    parts = []
    if t:
        for p in t.get("partitions", []):
            node = p["node"]; info = by_node.get(node, {})
            n = int(re.search(r"(\d+)$", node).group(1))
            ptype = p.get("type", "")
            parts.append({"n": n, "node": node, "start": p["start"], "end": p["start"] + p["size"] - 1, "size": p["size"] * ss,
                          "type": ptype, "type_name": TYPE_NAMES.get(ptype.upper() if len(ptype) > 4 else ptype.lower(), ptype),
                          "flag": _flag_of(ptype), "name": p.get("name", ""), "fs": info.get("fstype") or "", "label": info.get("label") or "",
                          "uuid": info.get("uuid") or "", "mounts": info.get("mounts") or [], "used": (info.get("usage") or {}).get("used")})
        parts.sort(key=lambda p: p["start"])
    first = (t or {}).get("firstlba", 2048 if not t else 34); last = (t or {}).get("lastlba", total - 1)
    res = {"serial": d["serial"], "name": d["name"], "path": d["path"], "model": d["model"], "size": d["size"], "size_text": d["size_text"],
           "sector": ss, "sectors": total, "table": (t or {}).get("label"), "first": first, "last": last, "partitions": parts}
    res["gaps"] = gaps(res)
    res["fingerprint"] = hashlib.sha256((_dump(d["path"]) + json.dumps([(p["node"], p["fs"], p["uuid"]) for p in parts])).encode()).hexdigest()[:24]
    res["busy"] = busy_reasons(d); res["editable"] = not res["busy"]
    res["backups"] = backups(d["serial"])
    return res

def gaps(lay):
    """Free space big enough to use (1 MiB or more), aligned to 1 MiB."""
    if not lay["table"]: return []
    al = MiB // lay["sector"]; out = []; cur = max(lay["first"], al)
    for p in sorted(lay["partitions"], key=lambda p: p["start"]) + [{"start": lay["last"] + 1, "end": lay["last"]}]:
        a = -(-cur // al) * al; b = p["start"] - 1
        if b - a + 1 >= al: out.append({"start": a, "end": b, "size": (b - a + 1) * lay["sector"]})
        cur = max(cur, p["end"] + 1)
    return out


# ── planning: check every op against the layout as it will be at that point ───────────────
def _pending(lay):
    return {"table": lay["table"], "sector": lay["sector"], "first": lay["first"], "last": lay["last"], "sectors": lay["sectors"],
            "partitions": [dict(p) for p in lay["partitions"]]}

def _label_ok(fs, label):
    if label and not re.fullmatch(r"[A-Za-z0-9 ._-]{1,%d}" % LABEL_MAX.get(fs, 16), label): raise ValueError(f"a {fs} label can be up to {LABEL_MAX.get(fs, 16)} letters, numbers, spaces, . _ -")

def plan(lay, ops):
    """Simulate the ops in order. Returns (result layout, plain-language steps). Raises ValueError("step N: why")."""
    if not isinstance(ops, list) or not ops: raise ValueError("nothing to do")
    if len(ops) > 32: raise ValueError("too many changes at once — apply some first")
    L = _pending(lay); ss = L["sector"]; al = MiB // ss; steps = []
    def find(n):
        p = next((p for p in L["partitions"] if p["n"] == n), None)
        if not p: raise ValueError(f"there's no partition {n}")
        return p
    for i, op in enumerate(ops, 1):
        try:
            k = op.get("op") if isinstance(op, dict) else None
            if k == "table":
                ty = op.get("type")
                if ty not in ("gpt", "dos"): raise ValueError("the table is GPT (recommended) or MBR")
                L["table"] = ty; L["partitions"] = []
                L["first"] = 2048 if ty == "dos" else 34; L["last"] = L["sectors"] - 1 - (33 if ty == "gpt" else 0)
                steps.append(f"New {'GPT' if ty == 'gpt' else 'MBR'} partition table — everything on the drive is erased")
                continue
            if not L["table"]: raise ValueError("the drive has no partition table yet — make one first")
            if k == "create":
                fs = op.get("fs", "ext4"); label = str(op.get("label", ""))[:255]
                if fs not in NEW_FS: raise ValueError(f"Nova can't make {fs}")
                _label_ok(fs, label)
                start = int(op.get("start_mib", -1)) * al; size = int(op.get("size_mib", 0)) * al
                if start < max(L["first"], al): raise ValueError("a partition starts 1 MiB in, at the earliest")
                if size * ss < MIN_SIZE[fs]: raise ValueError(f"{fs} needs at least {S.human(MIN_SIZE[fs])}")
                end = start + size - 1
                if end > L["last"]: raise ValueError("it doesn't fit on the drive")
                if any(not (end < p["start"] or start > p["end"]) for p in L["partitions"]): raise ValueError("it overlaps another partition")
                if L["table"] == "dos" and len(L["partitions"]) >= 4: raise ValueError("an MBR table holds 4 partitions — use GPT for more")
                used_n = {p["n"] for p in L["partitions"]}; n = next(x for x in range(1, 129) if x not in used_n)
                L["partitions"].append({"n": n, "node": "", "start": start, "end": end, "size": size * ss, "fs": "" if fs == "none" else fs, "label": label,
                                        "flag": "none", "new": True, "used": 0, "mounts": []})
                L["partitions"].sort(key=lambda p: p["start"])
                steps.append(f"Create partition {n}: {S.human(size * ss)}" + ("" if fs == "none" else f", {fs}") + (f" labelled “{label}”" if label else ""))
            elif k == "delete":
                p = find(int(op.get("n", -1))); L["partitions"].remove(p)
                steps.append(f"Delete partition {p['n']} ({S.human(p['size'])}{', ' + p['fs'] if p['fs'] else ''}{' “' + p['label'] + '”' if p['label'] else ''}) — its files are gone (until something new is made there, Restore can bring it back)")
            elif k == "format":
                p = find(int(op.get("n", -1))); fs = op.get("fs", "ext4"); label = str(op.get("label", ""))[:255]
                if fs not in NEW_FS or fs == "none": raise ValueError(f"Nova can't make {fs}")
                _label_ok(fs, label)
                if p["size"] < MIN_SIZE[fs]: raise ValueError(f"{fs} needs at least {S.human(MIN_SIZE[fs])}")
                steps.append(f"Format partition {p['n']} as {fs}" + (f" “{label}”" if label else "") + " — what's on it now is erased")
                p.update(fs=fs, label=label, used=0, formatted=True)
            elif k == "label":
                p = find(int(op.get("n", -1))); label = str(op.get("label", ""))[:255]
                if not p["fs"] or p["fs"] not in LABEL_MAX: raise ValueError(f"Nova can't label a {p['fs'] or 'empty'} partition")
                _label_ok(p["fs"], label); p["label"] = label
                steps.append(f"Label partition {p['n']} “{label}”" if label else f"Clear partition {p['n']}'s label")
            elif k == "flag":
                p = find(int(op.get("n", -1))); f = op.get("flag")
                if f not in FLAGS: raise ValueError("unknown flag")
                if f == "bios_grub" and L["table"] != "gpt": raise ValueError("BIOS boot is for GPT tables")
                p["flag"] = f
                steps.append(f"Mark partition {p['n']} as " + {"none": "a normal data partition", "esp": "the EFI system partition", "bios_grub": "BIOS boot",
                                                              "raid": "a RAID member", "lvm": "an LVM member"}[f])
            elif k == "resize":
                p = find(int(op.get("n", -1))); size = int(op.get("size_mib", 0)) * al; old = p["end"] - p["start"] + 1
                if p.get("new") or p.get("formatted"): raise ValueError("set its size when you create it (a partition made in the same plan isn't resized)")
                if size <= 0 or size == old: raise ValueError("pick a new size")
                end = p["start"] + size - 1
                if size > old:
                    nxt = min([q["start"] for q in L["partitions"] if q["start"] > p["start"]] + [L["last"] + 1])
                    if end >= nxt: raise ValueError("there isn't that much free space right after it (Nova doesn't move partitions)")
                    if p["fs"] and p["fs"] not in GROWS: raise ValueError(f"Nova can't grow a {p['fs']} filesystem — format it instead, or leave it")
                else:
                    if p["fs"] and p["fs"] not in SHRINKS:
                        raise ValueError("XFS can't shrink at all" if p["fs"] == "xfs" else f"Nova can only shrink ext4 (this is {p['fs']})")
                    if size * ss < MIN_SIZE.get(p["fs"] or "none", MiB): raise ValueError("that's too small for it")
                    if p.get("min_size") and size * ss < p["min_size"]: raise ValueError(f"its files need at least {S.human(p['min_size'])}")
                steps.append(f"{'Grow' if size > old else 'Shrink'} partition {p['n']} from {S.human(old * ss)} to {S.human(size * ss)}"
                             + (" (its files stay)" if p["fs"] else ""))
                p.update(end=end, size=size * ss, resized=True)
            else:
                raise ValueError("unknown change")
        except ValueError as e:
            raise ValueError(f"step {i}: {e}")
        except (TypeError, KeyError):
            raise ValueError(f"step {i}: that change is missing something")
    L["gaps"] = gaps({**L}); return L, steps

def with_min_sizes(lay, ops):
    """Shrinking ext4: how small its files let it get (resize2fs -P, measured once, only for partitions being shrunk)."""
    want = {int(o.get("n", -1)) for o in ops if isinstance(o, dict) and o.get("op") == "resize"}
    for p in lay["partitions"]:
        if p["n"] in want and p["fs"] == "ext4" and p["node"]:
            rc, so, _ = S.run(["resize2fs", "-P", p["node"]], timeout=120)
            m = re.search(r"(\d+)\s*$", so.strip())
            bs = re.search(r"Block size:\s+(\d+)", S.run(["dumpe2fs", "-h", p["node"]], timeout=60)[1] or "")
            if m and bs: p["min_size"] = int(int(m.group(1)) * int(bs.group(1)) * 1.1) + 32 * MiB      # a margin: never cut it close
    return lay


# ── applying ────────────────────────────────────────────────────────────────────────────
def backups(serial):
    try: return sorted([f for f in os.listdir(BACKUPS) if f.startswith(re.sub(r"[^A-Za-z0-9_-]", "_", serial) + "--")],
                       key=lambda f: os.path.getmtime(f"{BACKUPS}/{f}"), reverse=True)[:5]
    except OSError: return []

def save_backup(lay):
    os.makedirs(BACKUPS, exist_ok=True); os.chmod(BACKUPS, 0o700)
    base = f"{BACKUPS}/{re.sub(r'[^A-Za-z0-9_-]', '_', lay['serial'])}--{time.strftime('%Y%m%d-%H%M%S')}"
    f = base + ".sfdisk"; k = 1
    while os.path.exists(f): k += 1; f = f"{base}-{k}.sfdisk"             # never write over an earlier one (two changes in one second)
    with open(f, "w") as fh: fh.write(_dump(lay["path"]) or "# no partition table\n")
    os.chmod(f, 0o600); return os.path.basename(f)

def _reread(path):
    S.run(["partprobe", path], timeout=60); S.run(["partx", "-u", path], timeout=60); S.settle()

def _node(path, n):
    for _ in range(20):
        rc, so, _ = S.run(["lsblk", "-J", "-o", "PATH,PARTN", path], timeout=30)
        try:
            bd = json.loads(so)["blockdevices"]
            for c in bd + [k for b in bd for k in (b.get("children") or [])]:        # listed flat or nested, depending on the columns
                if str(c.get("partn")) == str(n) and c.get("path") != path: return c["path"]
        except Exception: pass
        time.sleep(0.5); _reread(path)
    raise RuntimeError(f"partition {n} didn't show up")

def _type_for(table, fs, flag):
    key = flag if flag != "none" else ("swap" if fs == "swap" else "msdata" if fs in ("exfat", "vfat", "ntfs") else "linux")
    return (GPT_TYPES if table == "gpt" else DOS_TYPES).get(key, GPT_TYPES["linux"] if table == "gpt" else "83")

def _mkfs(node, fs, label, log):
    S.run(["wipefs", "-a", "-f", node], timeout=120, check=True)
    if fs in ("ext4", "xfs", "btrfs", "exfat"): S.mkfs(node, fs, label or "", log); return
    log(f"Creating the {fs} filesystem…")
    if fs == "ntfs":                                     # for drives Windows reads too (ntfs-3g; "fast": no zeroing the whole drive)
        S.ensure_tools(["mkfs.ntfs"], log); S.run(["mkfs.ntfs", "-f", "-F"] + (["-L", label[:32]] if label else []) + [node], timeout=600, check=True); return
    S.ensure_tools(["mkfs.vfat"] if fs == "vfat" else [], log)
    cmd = ["mkfs.vfat", "-F", "32"] + (["-n", label[:11].upper()] if label else []) + [node] if fs == "vfat" else ["mkswap"] + (["-L", label[:15]] if label else []) + [node]
    S.run(cmd, timeout=600, check=True)

def _set_label(node, fs, label, log):
    cmd = {"ext4": ["e2label", node, label], "xfs": ["xfs_admin", "-L", label or "--", node], "btrfs": ["btrfs", "filesystem", "label", node, label],
           "exfat": ["tune.exfat", "-L", label, node], "ntfs": ["ntfslabel", node, label], "vfat": ["fatlabel", node, label.upper() or "NO NAME"], "swap": ["swaplabel", "-L", label, node]}[fs]
    S.run(cmd, timeout=120, check=True)

def _grow_fs(node, fs, log):
    if fs == "ext4":
        S.run(["e2fsck", "-f", "-y", node], timeout=7200); S.run(["resize2fs", node], timeout=7200, check=True); return
    tmp = f"/run/nova-part-{os.path.basename(node)}"; os.makedirs(tmp, exist_ok=True)
    try:
        S.run(["mount", node, tmp], timeout=120, check=True)
        try: S.run(["xfs_growfs", tmp] if fs == "xfs" else ["btrfs", "filesystem", "resize", "max", tmp], timeout=3600, check=True)
        finally: S.run(["umount", tmp], timeout=120)
    finally:
        try: os.rmdir(tmp)
        except OSError: pass

def _drop_fstab(p, log):
    for e in S.fstab_entries():
        if p.get("uuid") and e["src"] in (f"UUID={p['uuid']}", p["node"]) or (p.get("node") and e["src"] == p["node"]):
            S.fstab_remove(e["mount"]); log(f"{e['mount']} no longer mounts at boot (its partition is gone)")

def apply(spec, log, progress):
    serial = str(spec.get("serial", "")); ops = spec.get("ops")
    if spec.get("confirm") != [serial]: raise ValueError("type the drive's serial to confirm")
    lay = layout(serial)
    if lay["busy"]: raise ValueError("can't change this drive: " + "; ".join(lay["busy"]))
    if spec.get("fingerprint") != lay["fingerprint"]: raise ValueError("the drive changed since you planned this — look at it again and re-check your changes")
    _, steps = plan(with_min_sizes(lay, ops), ops)               # checked again here: the apps are never trusted
    path = lay["path"]; ss = lay["sector"]; al = MiB // ss
    log(f"Saved the current partition table ({save_backup(lay)}) — “Restore the previous table” puts it back")
    cur = {p["n"]: dict(p) for p in lay["partitions"]}; table = lay["table"]
    for i, (op, step) in enumerate(zip(ops, steps), 1):
        log(f"{i}/{len(ops)} · {step}"); progress(5 + int(90 * (i - 1) / len(ops)))
        k = op["op"]
        if k == "table":
            for p in cur.values(): _drop_fstab(p, log)
            S.run(["wipefs", "-a", "-f", *[p["node"] for p in cur.values() if p.get("node")], path], timeout=120, check=True)
            S.run(["sfdisk", "--wipe", "always", path], input=f"label: {op['type']}\n", timeout=120, check=True)
            table = op["type"]; cur = {}; _reread(path)
        elif k == "create":
            fs = op.get("fs", "ext4"); start = int(op["start_mib"]) * al; size = int(op["size_mib"]) * al
            before = set(cur)
            S.run(["sfdisk", "--append", "--wipe-partitions", "always", path], input=f"start={start}, size={size}, type={_type_for(table, fs, 'none')}\n", timeout=120, check=True)
            _reread(path)
            t = _table(path) or {}
            new = [q for q in t.get("partitions", []) if int(re.search(r"(\d+)$", q["node"]).group(1)) not in before and q["start"] == start]
            if not new: raise RuntimeError("the new partition didn't appear")
            n = int(re.search(r"(\d+)$", new[0]["node"]).group(1)); node = _node(path, n)
            cur[n] = {"n": n, "node": node, "fs": "", "uuid": ""}
            if fs != "none": _mkfs(node, fs, op.get("label", ""), log); cur[n]["fs"] = fs
        elif k == "delete":
            # only the table entry goes (the bytes stay until something new is made there), so "Restore the previous table" can bring it back
            p = cur.pop(int(op["n"])); _drop_fstab(p, log)
            S.run(["sfdisk", "--delete", path, str(p["n"])], timeout=120, check=True); _reread(path)
        elif k == "format":
            p = cur[int(op["n"])]; node = p.get("node") or _node(path, p["n"]); _drop_fstab(p, log)
            _mkfs(node, op["fs"], op.get("label", ""), log); p["fs"] = op["fs"]
            S.run(["sfdisk", "--part-type", path, str(p["n"]), _type_for(table, op["fs"], p.get("flag", "none"))], timeout=60); _reread(path)
        elif k == "label":
            p = cur[int(op["n"])]; _set_label(p.get("node") or _node(path, p["n"]), p["fs"], op.get("label", ""), log)
        elif k == "flag":
            p = cur[int(op["n"])]; p["flag"] = op["flag"]
            S.run(["sfdisk", "--part-type", path, str(p["n"]), _type_for(table, p.get("fs", ""), op["flag"])], timeout=60, check=True); _reread(path)
        elif k == "resize":
            p = cur[int(op["n"])]; t = _table(path) or {}
            q = next(x for x in t.get("partitions", []) if x["node"].endswith(str(p["n"])) and int(re.search(r"(\d+)$", x["node"]).group(1)) == p["n"])
            size = int(op["size_mib"]) * al; node = p.get("node") or _node(path, p["n"]); fs = p.get("fs", "")
            if size < q["size"] and fs == "ext4":           # shrink: the filesystem first, then the partition
                S.run(["e2fsck", "-f", "-y", node], timeout=7200)
                S.run(["resize2fs", node, f"{size * ss // 1024}K"], timeout=7200, check=True)
            S.run(["sfdisk", "-N", str(p["n"]), "--no-reread", "--wipe-partitions", "never", path], input=f"start={q['start']}, size={size}\n", timeout=120, check=True)
            _reread(path)
            if size > q["size"] and fs in GROWS: _grow_fs(node, fs, log)      # grow: the partition first, then the filesystem
    progress(100); log("Done — the drive's new layout is in place.")
    return {"steps": steps}

def restore(spec, log, progress):
    """Put a saved partition table back (the one Nova saved before its last change). Filesystems on the old partitions
    come back if nothing has written over them since."""
    serial = str(spec.get("serial", "")); name = str(spec.get("backup", ""))
    if spec.get("confirm") != [serial]: raise ValueError("type the drive's serial to confirm")
    if name not in backups(serial): raise ValueError("no such saved table")
    lay = layout(serial)
    if lay["busy"]: raise ValueError("can't change this drive: " + "; ".join(lay["busy"]))
    data = open(f"{BACKUPS}/{name}").read()
    log(f"Saved the table as it is now first ({save_backup(lay)})")
    if data.startswith("# no partition table"):
        S.run(["wipefs", "-a", "-f", lay["path"]], timeout=120, check=True)
    else:
        S.run(["sfdisk", "--wipe-partitions", "never", lay["path"]], input=data, timeout=120, check=True)
    _reread(lay["path"]); progress(100); log("Done — the saved partition table is back.")
    return {"restored": name}
