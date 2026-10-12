#!/usr/bin/python3 -I
"""
Free up space (and memory) in one go: what Nova can safely remove, measured first, removed when you say so.

Never touched: your files, your apps' data, containers (running or stopped) and their volumes, the
versions Nova keeps for rolling a container back, and the newest backups.

  scan()             each thing that can go, with how much space it holds (and whether it's ticked by default)
  run(spec, …)       remove the ones in spec["items"] (a background task: tasks.py "freeup")
"""
import json, os, pwd, re, shutil, subprocess, sys, time

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)

JOURNAL_KEEP = 200 * 1024 * 1024
ITEMS = ("images", "rollback", "buildcache", "apt", "journal", "leftovers", "trash", "autoremove", "snapshots", "memory")
NOVA_LEFTOVERS = ("/var/lib/nova-api/crashes", "/var/lib/nova-api/apk")


def run(cmd, timeout=120):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout, r.stderr
    except (OSError, subprocess.TimeoutExpired) as e: return 1, "", str(e)

def du(path, timeout=60):
    if not os.path.exists(path): return 0
    rc, so, _ = run(["du", "-sb", "--one-file-system", path], timeout)
    try: return int(so.split()[0]) if rc == 0 else 0
    except (IndexError, ValueError): return 0

def _bytes(s):
    """'1.2GB' / '532.4MB' / '0B' (docker's sizes) → bytes."""
    m = re.match(r"([\d.]+)\s*([kKMGT]?)i?B", str(s).strip())
    if not m: return 0
    return int(float(m.group(1)) * {"": 1, "k": 1e3, "K": 1e3, "M": 1e6, "G": 1e9, "T": 1e12}[m.group(2)])

def _docker(): return shutil.which("docker") is not None

def _rollbacks():
    """The versions Nova keeps for "Go back to the previous version" (nova-rollback/<app>:previous)."""
    rc, so, _ = run(["docker", "image", "ls", "--format", "{{json .}}", "nova-rollback/*"], 60)
    out = []
    for line in so.splitlines():
        try: i = json.loads(line)
        except ValueError: continue
        if str(i.get("Repository", "")).startswith("nova-rollback/"):
            out.append({"ref": f"{i['Repository']}:{i.get('Tag', 'previous')}", "size": _bytes(i.get("Size", "0"))})
    return out

def _people_homes():
    ok = set(l.strip() for l in open("/etc/shells") if l.startswith("/"))
    return [(u.pw_name, u.pw_dir) for u in pwd.getpwall() if 1000 <= u.pw_uid < 60000 and u.pw_shell in ok and os.path.isdir(u.pw_dir)]

def _rotated_logs():
    out = []
    for root, dirs, files in os.walk("/var/log"):
        if root.count(os.sep) > 4: dirs[:] = []
        dirs[:] = [d for d in dirs if d not in ("nova-api", "nova-alerts")]      # Nova's audit trail and alert history stay
        out += [os.path.join(root, f) for f in files if re.search(r"\.(\d+|\d+\.gz|gz|old)$", f)]
    return out

def _old_snapshots():
    """Backup snapshots beyond the newest three of each backup (what 'keep the newest 3' would remove)."""
    try:
        import backups
        out = []
        for j in backups.load_jobs():
            d = j.get("dest") or {}
            if d.get("type") != "local" or not d.get("path"): continue        # (NAS backups: not mounted outside a run)
            folder = os.path.join(d["path"], "nova-backups", j["name"].replace(" ", "-"))
            if not os.path.isdir(folder) or backups.owner_mount(d["path"]) == "/": continue
            snaps = backups.snapshots(folder)
            out += [os.path.join(folder, s) for s in snaps[:-3]]
        return out
    except Exception:
        return []


def scan():
    items = []
    def add(id, title, about, size, default=True, count=None, unknown=False):
        items.append({"id": id, "title": title, "about": about, "size": size, "default": default, "count": count, "unknown": unknown})
    if _docker():
        try:
            import labs
            im = labs.images()["images"]
            add("images", "Container images no app uses", "Old versions left behind by updates, and images nothing runs. The version each app can roll back to stays.",
                sum(_bytes(i.get("size")) for i in im), True, len(im))
        except Exception: pass
        rb = _rollbacks()
        if rb:
            add("rollback", "Rollback copies", "The previous version of each app Nova updated, kept so you can go back in one tap. "
                "Without them, going back means finding the old version yourself.", sum(r["size"] for r in rb), False, len(rb))
        rc, so, _ = run(["docker", "system", "df", "--format", "{{json .}}"], 60)
        for line in so.splitlines():
            try: d = json.loads(line)
            except ValueError: continue
            if d.get("Type") == "Build Cache":
                add("buildcache", "Docker build cache", "Leftovers from building container images. Rebuilt when needed.", _bytes(str(d.get("Reclaimable", "0")).split(" ")[0]))
    if os.path.isdir("/var/cache/apt/archives"):
        add("apt", "Downloaded package files", "Installers apt keeps after installing updates. Not needed once installed.", du("/var/cache/apt/archives"))
    rc, so, _ = run(["journalctl", "--disk-usage"], 30)
    m = re.search(r"take up ([\d.]+[KMGT]?)", so)
    if m:
        size = _bytes(m.group(1) + "B")
        add("journal", "System logs", "Keeps the newest 200 MB of the system log, removes older entries.", max(0, size - JOURNAL_KEEP), True)
    logs = _rotated_logs()
    left = sum(os.path.getsize(p) for p in logs if os.path.isfile(p))
    for d in NOVA_LEFTOVERS:
        if os.path.isdir(d): left += sum(os.path.getsize(os.path.join(d, f)) for f in _nova_old(d))
    add("leftovers", "Old rotated logs and Nova's leftovers", "Archived log files (.gz, .1), old crash reports and app downloads Nova no longer needs.", left, True, len(logs))
    trash = sum(du(os.path.join(h, ".local/share/Trash")) for _, h in _people_homes())
    if trash: add("trash", "Trash", "Files you deleted in Nova's file manager (or a desktop). They're gone for good afterwards.", trash, False)
    rc, so, _ = run(["apt-get", "-s", "autoremove"], 60)
    n = len(re.findall(r"^Remv ", so, re.M))
    if n: add("autoremove", "Packages nothing needs anymore", "Old kernels and libraries apt marks as no longer needed.", 0, False, n, unknown=True)
    snaps = _old_snapshots()
    if snaps: add("snapshots", "Older backup snapshots", "Keeps the newest three of each backup and removes older ones. You can't restore from those afterwards.", 0, False, len(snaps), unknown=True)
    m = open("/proc/meminfo").read()
    kb = lambda k: int(re.search(rf"^{k}:\s+(\d+)", m, re.M).group(1)) * 1024
    return {"items": items, "free": shutil.disk_usage("/").free,
            "memory": {"total": kb("MemTotal"), "available": kb("MemAvailable"), "cached": kb("Cached") + kb("Buffers"),
                       "swap_used": kb("SwapTotal") - kb("SwapFree")}}

def _nova_old(d):
    """In Nova's own folders: crash reports older than a week; app downloads except the newest."""
    fs = sorted(os.listdir(d), key=lambda f: os.path.getmtime(os.path.join(d, f)))
    if d.endswith("/apk"):
        keep = set()
        try: keep.add(json.load(open(os.path.join(d, "latest.json"))).get("file", ""))
        except Exception: pass
        return [f for f in fs if f.endswith(".apk") and f not in keep]
    return [f for f in fs if time.time() - os.path.getmtime(os.path.join(d, f)) > 7 * 86400]


def run_freeup(spec, log=print, progress=lambda p, n="": None):
    want = set(spec.get("items") or [])
    if not want or not want <= set(ITEMS): raise ValueError("pick what to remove")
    before = shutil.disk_usage("/").free
    steps = [w for w in ITEMS if w in want]
    for i, w in enumerate(steps):
        progress(int(100 * i / len(steps)), w)
        if w == "images":
            import labs
            labs.prune({}, log, lambda p, n="": None)                     # (the same cleanup as Settings → Old images)
        elif w == "rollback":
            for r in _rollbacks():
                rc, _, se = run(["docker", "rmi", r["ref"]], 300)          # never forced: a copy something still uses stays
                log(f"Removed the rollback copy {r['ref'].split('/', 1)[1]}" if rc == 0 else f"Kept {r['ref']}: {se.strip()[-120:]}")
            run(["docker", "image", "prune", "-f"], 300)
        elif w == "buildcache":
            log("Clearing Docker's build cache"); run(["docker", "builder", "prune", "-af"], 600)
        elif w == "apt":
            log("Clearing downloaded package files"); run(["apt-get", "clean"], 300)
        elif w == "journal":
            log("Trimming the system log to 200 MB"); run(["journalctl", f"--vacuum-size={JOURNAL_KEEP // (1024 * 1024)}M"], 300)
        elif w == "leftovers":
            n = 0
            for p in _rotated_logs():
                try: os.remove(p); n += 1
                except OSError: pass
            for d in NOVA_LEFTOVERS:
                if os.path.isdir(d):
                    for f in _nova_old(d):
                        try: os.remove(os.path.join(d, f)); n += 1
                        except OSError: pass
            log(f"Removed {n} old log and leftover files")
        elif w == "trash":
            for user, h in _people_homes():
                t = os.path.join(h, ".local/share/Trash")
                for sub in ("files", "info", "expunged"):
                    p = os.path.join(t, sub)
                    if os.path.isdir(p) and not os.path.islink(p):
                        shutil.rmtree(p, ignore_errors=True); os.makedirs(p, exist_ok=True)
                        try: u = pwd.getpwnam(user); os.chown(p, u.pw_uid, u.pw_gid)
                        except Exception: pass
                log(f"Emptied {user}'s Trash")
        elif w == "autoremove":
            log("Removing packages nothing needs"); rc, so, se = run(["apt-get", "-y", "autoremove"], 1800)
            if rc != 0: log("apt: " + (se or so).strip()[-300:])
        elif w == "snapshots":
            for p in _old_snapshots():
                log(f"Removing old backup snapshot {os.path.basename(p)}"); shutil.rmtree(p, ignore_errors=True)
        elif w == "memory":
            log("Freeing memory the system can let go of"); os.sync()
            try:
                with open("/proc/sys/vm/drop_caches", "w") as f: f.write("3\n")
            except OSError: pass
            try:
                with open("/proc/sys/vm/compact_memory", "w") as f: f.write("1\n")
            except OSError: pass
    freed = max(0, shutil.disk_usage("/").free - before)
    progress(100, "done"); log(f"Freed {freed / 1e9:.1f} GB on the system drive")
    return {"freed": freed, "free": shutil.disk_usage("/").free}


if __name__ == "__main__":
    print(json.dumps(scan(), indent=1))
