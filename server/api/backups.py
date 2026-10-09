#!/usr/bin/python3 -I
"""
Nova backups — scheduled, versioned copies of folders to a local drive or a NAS (NFS / SMB).

  * Every run is a dated snapshot that is a complete, plain folder (browse or copy it back with any
    tool). Unchanged files are hard-linked to the previous snapshot, so a run costs only what changed.
    Destinations without hard links (some SMB servers) get one mirror plus dated "versions" folders
    holding the files that changed or were deleted.
  * Safety: a source that is missing, or suddenly has far fewer files than last time (what a dropped
    drive looks like), is not backed up from — the previous copy stays and you get an alert.
  * Old snapshots are thinned to the newest N daily / weekly / monthly; the newest three always stay.

Config: /etc/nova-backup/jobs.json (root only); SMB passwords in /etc/nova-backup/secrets/<id>.cred.
State:  /var/lib/nova-backups/<id>/state.json and history.jsonl.
CLI (root):  backups.py tick | run <id> | list
"""
import datetime as dt, fcntl, json, os, re, shutil, subprocess, sys, time, uuid

CONF_DIR = "/etc/nova-backup"; JOBS = f"{CONF_DIR}/jobs.json"; SECRETS = f"{CONF_DIR}/secrets"
STATE = "/var/lib/nova-backups"; MNT = "/run/nova-backup"
SNAP_RE = re.compile(r"^\d{4}-\d\d-\d\d_\d{4}(\d\d)?$")
FORBIDDEN = ("/proc", "/sys", "/dev", "/run", "/tmp", "/var/tmp", "/lost+found")
DEFAULT_EXCLUDES = ["lost+found/", ".Trash-*/", "*.tmp", ".cache/", "node_modules/", "__pycache__/"]


def load_json(p, d):
    try: return json.load(open(p))
    except Exception: return d

def save_json(p, obj, mode=0o600):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    tmp = p + ".tmp"
    with open(tmp, "w") as f: json.dump(obj, f, indent=1)
    os.chmod(tmp, mode); os.replace(tmp, p)

def load_jobs():
    return load_json(JOBS, {}).get("jobs", [])

def save_jobs(jobs):
    os.makedirs(CONF_DIR, exist_ok=True); os.chmod(CONF_DIR, 0o700)
    save_json(JOBS, {"jobs": jobs})

def job_state(jid): return load_json(f"{STATE}/{jid}/state.json", {})
def human(n):
    for u in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1000 or u == "TB": return f"{n:.0f} {u}" if u in ("B", "KB") or abs(n) >= 100 else f"{n:.1f} {u}"
        n /= 1000

def run(cmd, timeout=600, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, **kw)
    return r.returncode, r.stdout, r.stderr

def notify(level, title, detail=""):
    t = next((p for p in ("/usr/sbin/nova-alert", "/usr/local/bin/nova-alert") if os.path.exists(p)), None)
    if t:
        try: run([t, level, title, detail], timeout=30)
        except Exception: pass


# ── validation (everything the app can set) ────────────────────────────────────────────
def _path(p, what):
    p = os.path.normpath(str(p))
    if not p.startswith("/") or "\n" in p or len(p) > 400: raise ValueError(f"{what}: give a full path")
    if p != "/" and any(p == f or p.startswith(f + "/") for f in FORBIDDEN): raise ValueError(f"{what}: {p} can't be backed up")
    return p

def owner_mount(path):
    p = os.path.realpath(path)
    while not os.path.ismount(p): p = os.path.dirname(p)
    return p

def validate(job, existing=None):
    j = {"id": str(job.get("id") or (existing or {}).get("id") or uuid.uuid4().hex[:8])[:12]}
    if not re.fullmatch(r"[a-z0-9]{1,12}", j["id"]): raise ValueError("bad id")
    name = "".join(c for c in str(job.get("name", "")).strip() if c.isalnum() or c in " -_")[:40]
    if not name: raise ValueError("give the backup a name")
    j["name"] = name; j["enabled"] = bool(job.get("enabled", True))
    src = [_path(s, "source") for s in job.get("sources", [])][:20]
    if not src: raise ValueError("pick at least one folder to back up")
    for s in src:
        if not os.path.isdir(s): raise ValueError(f"{s} isn't a folder on the server")
    j["sources"] = src
    ex = [str(e).strip() for e in job.get("excludes", DEFAULT_EXCLUDES) if str(e).strip()][:60]
    if any("\n" in e or len(e) > 200 for e in ex): raise ValueError("bad exclude pattern")
    j["excludes"] = ex
    d = job.get("dest", {}); t = d.get("type")
    if t == "local":
        p = _path(d.get("path", ""), "destination")
        if not os.path.isdir(p): raise ValueError(f"{p} isn't a folder on the server")
        m = owner_mount(p)
        if m == "/": raise ValueError("pick a different drive for backups — not the system drive")
        try:
            import storage
            dest_disks = storage.disks_of(p)
        except Exception: dest_disks = set()
        for s in src:
            if owner_mount(s) == m or (dest_disks and dest_disks & storage.disks_of(s)):
                raise ValueError(f"{s} is on the same drive as the destination — a backup there doesn't survive that drive failing")
            if p.startswith(s.rstrip("/") + "/") or p == s: raise ValueError("the destination can't be inside a folder you're backing up")
        j["dest"] = {"type": "local", "path": p}
    elif t in ("nfs", "smb"):
        host = str(d.get("host", "")).strip()
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.-]{0,252}|\[?[0-9a-fA-F:]+\]?", host): raise ValueError("NAS address: a name or IP")
        share = str(d.get("share", "")).strip().strip("/")
        if not re.fullmatch(r"[A-Za-z0-9 _.$/-]{1,200}", share): raise ValueError("share/export: letters, numbers and / only")
        sub = str(d.get("subdir", "")).strip().strip("/")
        if sub and not re.fullmatch(r"[A-Za-z0-9 _./-]{1,120}", sub) or ".." in sub.split("/"): raise ValueError("bad folder on the NAS")
        j["dest"] = {"type": t, "host": host, "share": share, "subdir": sub}
        if t == "smb":
            user = str(d.get("user", "")).strip()
            if user and not re.fullmatch(r"[A-Za-z0-9 _.@\\-]{1,64}", user): raise ValueError("bad user name")
            j["dest"]["user"] = user
            dom = str(d.get("domain", "")).strip()
            if dom and not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", dom): raise ValueError("bad domain")
            j["dest"]["domain"] = dom
            j["_password"] = d.get("password")                           # stored separately, never returned
    else:
        raise ValueError("destination: a drive (local) or a NAS (nfs / smb)")
    sc = job.get("schedule", {"days": list(range(7)), "time": "03:30"})
    if sc.get("manual"): j["schedule"] = {"manual": True}
    elif sc.get("every_hours"):
        h = int(sc["every_hours"])
        if h not in (1, 2, 3, 4, 6, 8, 12): raise ValueError("every 1, 2, 3, 4, 6, 8 or 12 hours")
        j["schedule"] = {"every_hours": h}
    else:
        tm = str(sc.get("time", "03:30"))
        if not re.fullmatch(r"([01]\d|2[0-3]):[0-5]\d", tm): raise ValueError("time HH:MM")
        days = sorted({int(x) for x in sc.get("days", range(7)) if 0 <= int(x) <= 6}) or list(range(7))
        j["schedule"] = {"time": tm, "days": days}
    k = job.get("keep", {})
    j["keep"] = {"hourly": max(0, min(168, int(k.get("hourly", 24 if j["schedule"].get("every_hours") else 0)))),
                 "daily": max(1, min(90, int(k.get("daily", 14)))), "weekly": max(0, min(104, int(k.get("weekly", 8)))),
                 "monthly": max(0, min(120, int(k.get("monthly", 12))))}
    j["guard_pct"] = max(0, min(90, int(job.get("guard_pct", 20))))
    j["notify"] = job.get("notify", "failures") if job.get("notify") in ("failures", "always", "never") else "failures"
    j["one_filesystem"] = bool(job.get("one_filesystem", True))
    j["bwlimit_mb"] = max(0, min(10000, int(job.get("bwlimit_mb", 0) or 0)))
    return j

def put_job(job):
    jobs = load_jobs(); old = next((x for x in jobs if x["id"] == job.get("id")), None)
    j = validate(job, old); pw = j.pop("_password", None)
    if j["dest"]["type"] == "smb":
        os.makedirs(SECRETS, exist_ok=True); os.chmod(SECRETS, 0o700)
        cred = f"{SECRETS}/{j['id']}.cred"
        if pw is not None or not os.path.exists(cred):
            if pw is not None and ("\n" in str(pw) or len(str(pw)) > 256): raise ValueError("bad password")
            body = f"username={j['dest'].get('user') or 'guest'}\npassword={pw or ''}\n" + (f"domain={j['dest']['domain']}\n" if j["dest"].get("domain") else "")
            fd = os.open(cred + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f: f.write(body)
            os.replace(cred + ".tmp", cred)
    jobs = [x for x in jobs if x["id"] != j["id"]] + [j]
    save_jobs(jobs); return j

def delete_job(jid):
    jobs = load_jobs()
    if not any(x["id"] == jid for x in jobs): raise ValueError("no such backup")
    save_jobs([x for x in jobs if x["id"] != jid])
    for p in (f"{SECRETS}/{jid}.cred",):
        try: os.remove(p)
        except OSError: pass


# ── destinations ──────────────────────────────────────────────────────────────────────
class Dest:
    """Context manager: mounts a NAS share for the duration of a run, yields the job's folder."""
    def __init__(self, job, log=print): self.job, self.log, self.mnt = job, log, None
    def __enter__(self):
        d = self.job["dest"]
        if d["type"] == "local":
            root = d["path"]
            if owner_mount(root) == "/": raise RuntimeError(f"the backup drive isn't mounted at {root}")
        else:
            import storage
            storage.ensure_tools(["mount.nfs" if d["type"] == "nfs" else "mount.cifs"], self.log)
            self.mnt = f"{MNT}/{self.job['id']}"; os.makedirs(self.mnt, exist_ok=True)
            if not os.path.ismount(self.mnt):
                self.log(f"Connecting to {d['host']}…")
                if d["type"] == "nfs":
                    cmd = ["mount", "-t", "nfs", "-o", "soft,timeo=150,retrans=3,noatime", f"{d['host']}:/{d['share']}", self.mnt]
                else:
                    cmd = ["mount", "-t", "cifs", f"//{d['host']}/{d['share']}", self.mnt, "-o",
                           f"credentials={SECRETS}/{self.job['id']}.cred,iocharset=utf8,uid=0,gid=0,file_mode=0600,dir_mode=0700,noperm,vers=3.0"]
                rc, _, se = run(cmd, timeout=90)
                if rc != 0:
                    e = se.strip()
                    why = ("the NAS turned down the user name or password" if "error(13)" in e or "Permission denied" in e and d["type"] == "smb" else
                           "the NAS didn't answer — is it on, and is the address right?" if re.search(r"error\((112|113|115|110)\)|timed out|No route|Connection refused", e) else
                           "there's no share/export with that name on the NAS" if "error(2)" in e or "No such file" in e or "access denied by server" in e else e.splitlines()[0][:200] if e else "mount failed")
                    raise RuntimeError(f"couldn't connect to the NAS: {why}")
            root = os.path.join(self.mnt, d.get("subdir", ""))
        folder = os.path.join(root, "nova-backups", self.job["name"].replace(" ", "-"))
        try: os.makedirs(folder, exist_ok=True)
        except OSError as e:
            self.__exit__()
            raise RuntimeError("the backup folder can't be created there — " + ("the NAS account isn't allowed to write to that share" if isinstance(e, PermissionError) else str(e)))
        return folder
    def __exit__(self, *a):
        if self.mnt and os.path.ismount(self.mnt): run(["umount", "-l", self.mnt], timeout=60)

def snap_time(s):
    return dt.datetime.strptime(s, "%Y-%m-%d_%H%M%S" if len(s) == 17 else "%Y-%m-%d_%H%M")

def hardlinks_work(folder):
    a, b = os.path.join(folder, ".nova-link-test"), os.path.join(folder, ".nova-link-test2")
    try:
        open(a, "w").write("x"); os.link(a, b); ok = os.stat(a).st_nlink == 2
    except OSError: ok = False
    for p in (a, b):
        try: os.remove(p)
        except OSError: pass
    return ok

def snapshots(folder):
    try: return sorted(n for n in os.listdir(folder) if SNAP_RE.match(n) and os.path.isdir(os.path.join(folder, n)))
    except OSError: return []


# ── one run ───────────────────────────────────────────────────────────────────────────
def count_files(path, limit=20_000_000, deadline=600):
    n, t0 = 0, time.time()
    for root, dirs, files in os.walk(path):
        n += len(files)
        if n > limit or time.time() - t0 > deadline: return None
    return n

def prune(folder, keep, log):
    snaps = snapshots(folder)
    if len(snaps) <= 3: return []
    dates = {s: snap_time(s) for s in snaps}
    keepset = set(snaps[-3:])
    def newest_per(keyfn, n):
        seen = {}
        for s in reversed(snaps):
            k = keyfn(dates[s])
            if k not in seen and len(seen) < n: seen[k] = s
        return set(seen.values())
    keepset |= newest_per(lambda d: (d.date(), d.hour), keep.get("hourly", 0))
    keepset |= newest_per(lambda d: d.date(), keep["daily"])
    keepset |= newest_per(lambda d: d.isocalendar()[:2], keep["weekly"])
    keepset |= newest_per(lambda d: (d.year, d.month), keep["monthly"])
    gone = [s for s in snaps if s not in keepset]
    for s in gone:
        log(f"Removing old snapshot {s}"); shutil.rmtree(os.path.join(folder, s), ignore_errors=True)
    vdir = os.path.join(folder, "versions")                                # mirror mode
    if os.path.isdir(vdir):
        vs = sorted(n for n in os.listdir(vdir) if SNAP_RE.match(n))
        for s in vs[:-max(3, keep["daily"] + keep["weekly"])]: shutil.rmtree(os.path.join(vdir, s), ignore_errors=True)
    return gone

def rsync_cmd(job, extra):
    import storage
    storage.ensure_tools(["rsync"])
    cmd = ["rsync", "-aHAX", "--relative", "--numeric-ids", "--delete", "--delete-excluded", "--info=progress2,stats2", "--no-inc-recursive",
           "--partial-dir=.rsync-partial"]
    if job.get("one_filesystem", True): cmd.append("--one-file-system")
    if job.get("bwlimit_mb"): cmd.append(f"--bwlimit={job['bwlimit_mb'] * 1000}")
    if job["dest"]["type"] == "smb": cmd = [c for c in cmd if c != "-aHAX"] + ["-rlt", "--no-perms", "--no-owner", "--no-group"]
    for e in job.get("excludes", []): cmd.append(f"--exclude={e}")
    return cmd + extra

def run_rsync(cmd, progress, log):
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, bufsize=1)
    stats, buf = {}, ""
    while True:
        ch = p.stdout.read(1)
        if not ch: break
        if ch in "\r\n":
            line = buf.strip(); buf = ""
            m = re.match(r"([\d,]+)\s+(\d+)%\s+(\S+/s)", line)
            if m: progress(int(m.group(2)), f"{human(int(m.group(1).replace(',', '')))} · {m.group(3)}")
            for k, rx in (("files", r"Number of files: ([\d,]+)"), ("transferred", r"Number of regular files transferred: ([\d,]+)"),
                          ("size", r"Total file size: ([\d,]+)"), ("sent", r"Total transferred file size: ([\d,]+)")):
                mm = re.search(rx, line)
                if mm: stats[k] = int(mm.group(1).replace(",", ""))
        else: buf += ch
    err = p.stderr.read(); rc = p.wait()
    return rc, stats, err

def run_job(job, log=print, progress=lambda pct, note="": None, force=False):
    st_dir = f"{STATE}/{job['id']}"; os.makedirs(st_dir, exist_ok=True)
    lock = open(f"{st_dir}/.lock", "w")
    try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError: raise RuntimeError("this backup is already running")
    prev = job_state(job["id"]); t0 = time.time(); stamp = time.strftime("%Y-%m-%d_%H%M%S")
    counts, skipped, ok_sources = dict(prev.get("counts", {})), [], []
    for s in job["sources"]:
        if not os.path.isdir(s):
            skipped.append(f"{s} is missing"); continue
        log(f"Checking {s}…"); n = count_files(s)
        last = prev.get("counts", {}).get(s)
        if n == 0 and last and not force: skipped.append(f"{s} is empty now (had {last} files) — is its drive missing?"); continue
        if n is not None and last and job["guard_pct"] and not force and n < last * (1 - job["guard_pct"] / 100):
            skipped.append(f"{s} has {n} files, down from {last} — not backed up from it until you check (a dropped drive looks like this)"); continue
        if n is not None: counts[s] = n
        ok_sources.append(s)
    result = {"t": t0, "stamp": stamp, "ok": False, "skipped": skipped}
    try:
        if not ok_sources: raise RuntimeError("nothing to back up: " + "; ".join(skipped))
        with Dest(job, log) as folder:
            snaps = snapshots(folder); k = 0
            while os.path.exists(os.path.join(folder, stamp)):          # two runs within the same second
                k += 1; stamp = time.strftime("%Y-%m-%d_%H%M%S", time.localtime(t0 + k))
            result["stamp"] = stamp
            if hardlinks_work(folder):
                result["mode"] = "snapshots"
                target = os.path.join(folder, stamp + ".partial")
                stale = sorted(n for n in os.listdir(folder) if n.endswith(".partial") and SNAP_RE.match(n[:-8]))
                if stale:                                   # an interrupted run: finish it instead of starting over
                    log("Picking up where the last, unfinished run stopped…")
                    os.rename(os.path.join(folder, stale[-1]), target)
                    for x in stale[:-1]: shutil.rmtree(os.path.join(folder, x), ignore_errors=True)
                os.makedirs(target, exist_ok=True)
                link = ["--link-dest=" + os.path.join(folder, snaps[-1])] if snaps else []
                # A skipped source keeps last time's copy: hard-link it in unchanged.
                for s in [x for x in job["sources"] if x not in ok_sources]:
                    old = os.path.join(folder, snaps[-1]) + s if snaps else ""
                    if old and os.path.isdir(old):
                        log(f"Keeping last time's copy of {s}")
                        parent = os.path.dirname(target + s); os.makedirs(parent, exist_ok=True)
                        subprocess.run(["cp", "-al", old, parent + "/"], timeout=7200)
                log(f"Copying to {job['dest'].get('path') or job['dest']['host']}…")
                rc, stats, err = run_rsync(rsync_cmd(job, link + ok_sources + [target + "/"]), progress, log)
                if rc not in (0, 24): raise RuntimeError(f"rsync failed ({rc}): {err.strip()[-300:]}")
                os.rename(target, os.path.join(folder, stamp))
                latest = os.path.join(folder, "latest")
                try:
                    if os.path.islink(latest): os.remove(latest)
                    os.symlink(stamp, latest)
                except OSError: pass
            else:
                result["mode"] = "mirror"
                target = os.path.join(folder, "current"); os.makedirs(target, exist_ok=True)
                vdir = os.path.join(folder, "versions", stamp)
                rc, stats, err = run_rsync(rsync_cmd(job, ["--backup", f"--backup-dir={vdir}"] + ok_sources + [target + "/"]), progress, log)
                if rc not in (0, 24): raise RuntimeError(f"rsync failed ({rc}): {err.strip()[-300:]}")
            result.update(stats=stats); pruned = prune(folder, job["keep"], log); result["pruned"] = len(pruned)
            u = shutil.disk_usage(folder); result["dest_free"] = u.free; result["dest_total"] = u.total
        result["ok"] = not skipped
        result["partial"] = bool(skipped)
    except Exception as e:
        result["error"] = str(e)[:400]
    result["duration"] = round(time.time() - t0)
    save_json(f"{st_dir}/state.json", {"last": result, "counts": counts, "last_ok": result["t"] if result["ok"] or result.get("partial") else prev.get("last_ok")}, 0o644)
    with open(f"{st_dir}/history.jsonl", "a") as f: f.write(json.dumps(result) + "\n")
    s = result.get("stats", {})
    if result.get("error"): notify("warning", f"Backup failed: {job['name']}", result["error"])
    elif skipped: notify("warning", f"Backup incomplete: {job['name']}", "; ".join(skipped))
    elif job.get("notify") == "always": notify("info", f"Backup done: {job['name']}", f"{s.get('transferred', 0)} files changed ({human(s.get('sent', 0))}) in {result['duration']} s")
    if result.get("error"): raise RuntimeError(result["error"])
    return result


# ── scheduling ────────────────────────────────────────────────────────────────────────
def due(job, now=None):
    """True when a scheduled time has passed since the last run (so runs missed while the server was
    off happen right after it starts)."""
    sc = job.get("schedule", {})
    if not job.get("enabled", True) or sc.get("manual"): return False
    now = now or dt.datetime.now(); last = job_state(job["id"]).get("last", {}).get("t")
    last = dt.datetime.fromtimestamp(last) if last else None
    if sc.get("every_hours"):
        return last is None or (now - last).total_seconds() >= sc["every_hours"] * 3600 - 60
    hh, mm = map(int, sc["time"].split(":"))
    for back in range(8):                                    # most recent scheduled moment
        day = now - dt.timedelta(days=back)
        if day.weekday() not in sc["days"]: continue
        slot = day.replace(hour=hh, minute=mm, second=0, microsecond=0)
        if slot > now: continue
        return last is None and back == 0 and (now - slot).total_seconds() < 120 or (last is not None and last < slot)
    return False

def next_run(job):
    sc = job.get("schedule", {})
    if sc.get("manual") or not job.get("enabled", True): return None
    last = job_state(job["id"]).get("last", {}).get("t") or time.time()
    if sc.get("every_hours"): return last + sc["every_hours"] * 3600
    now = dt.datetime.now(); hh, mm = map(int, sc["time"].split(":"))
    for d in range(8):
        day = now + dt.timedelta(days=d)
        slot = day.replace(hour=hh, minute=mm, second=0, microsecond=0)
        if day.weekday() in sc["days"] and slot > now: return slot.timestamp()
    return None

def running(jid):
    try:
        lock = open(f"{STATE}/{jid}/.lock", "w"); fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB); lock.close(); return False
    except OSError: return True

def tick():
    import tasks
    for j in load_jobs():
        if due(j) and not running(j["id"]) and not tasks.active_for("backup-run", j["id"]):
            tasks.start("backup-run", {"job": j["id"], "scheduled": True})


# ── restore ───────────────────────────────────────────────────────────────────────────
def list_snapshots(job, log=print):
    with Dest(job, log) as folder:
        snaps = snapshots(folder)
        out = [{"id": s, "t": snap_time(s).timestamp()} for s in reversed(snaps)]
        if os.path.isdir(os.path.join(folder, "current")):
            out.insert(0, {"id": "current", "t": os.path.getmtime(os.path.join(folder, "current"))})
        return out

def _inside(snapdir, rel):
    full = os.path.realpath(os.path.join(snapdir, rel.lstrip("/")))
    if not (full == os.path.realpath(snapdir) or full.startswith(os.path.realpath(snapdir) + "/")): raise ValueError("bad path")
    return full

def browse(job, snap, rel, log=print):
    if snap != "current" and not SNAP_RE.match(snap): raise ValueError("bad snapshot")
    with Dest(job, log) as folder:
        base = os.path.join(folder, snap); full = _inside(base, rel or "/")
        items = []
        with os.scandir(full) as it:
            for e in it:
                if e.name.startswith(".rsync-partial"): continue
                try: st = e.stat(follow_symlinks=False)
                except OSError: continue
                items.append({"name": e.name, "dir": e.is_dir(follow_symlinks=False), "size": st.st_size, "mtime": st.st_mtime})
                if len(items) >= 1000: break
        rp = os.path.relpath(full, base)
        return {"path": "/" if rp == "." else "/" + rp,
                "items": sorted(items, key=lambda x: (not x["dir"], x["name"].lower()))}

def restore(job, snap, rel, to, log=print, progress=lambda pct, note="": None):
    """Copy a file or folder back. to="original" puts it back where it was (newer files are kept —
    nothing is deleted); to="beside" puts it in a "restored-<date>" folder next to the original."""
    if snap != "current" and not SNAP_RE.match(snap): raise ValueError("bad snapshot")
    rel = "/" + rel.strip("/")
    if not any(rel == s or rel.startswith(s.rstrip("/") + "/") for s in job["sources"]): raise ValueError("that isn't inside this backup")
    import storage
    storage.ensure_tools(["rsync"], log)
    with Dest(job, log) as folder:
        src = _inside(os.path.join(folder, snap), rel)
        if not os.path.exists(src): raise ValueError("not in that snapshot")
        if to == "original": target = rel
        elif to == "beside": target = os.path.join(os.path.dirname(rel), f"restored-{snap}", os.path.basename(rel))
        else: raise ValueError("restore to: original or beside")
        os.makedirs(os.path.dirname(target) or "/", exist_ok=True)
        log(f"Restoring {rel} from {snap} to {target}…")
        cmd = ["rsync", "-aHAX", "--info=progress2", "--no-inc-recursive"] + (["--ignore-existing"] if to == "original" and job.get("keep_newer", True) else [])
        rc, stats, err = run_rsync(cmd + ([src + "/", target + "/"] if os.path.isdir(src) else [src, target]), progress, log)
        if rc not in (0, 24): raise RuntimeError(f"restore failed: {err.strip()[-300:]}")
        notify("info", f"Restored from backup: {job['name']}", f"{rel} ({snap}) → {target}")
        return {"target": target}

def test_dest(job, log=print):
    """Connect, write and delete a test file — before saving a NAS destination."""
    with Dest(job, log) as folder:
        p = os.path.join(folder, ".nova-write-test"); open(p, "w").write("ok"); os.remove(p)
        u = shutil.disk_usage(folder)
        return {"ok": True, "free": u.free, "total": u.total, "hardlinks": hardlinks_work(folder)}


# ── what the app shows ────────────────────────────────────────────────────────────────
def overview():
    out = []
    for j in load_jobs():
        st = job_state(j["id"]); pub = {k: v for k, v in j.items() if not k.startswith("_")}
        if pub["dest"].get("type") == "smb": pub["dest"]["has_password"] = os.path.exists(f"{SECRETS}/{j['id']}.cred")
        hist = []
        try: hist = [json.loads(l) for l in open(f"{STATE}/{j['id']}/history.jsonl").read().splitlines()[-30:]]
        except OSError: pass
        out.append({**pub, "last": st.get("last"), "last_ok": st.get("last_ok"), "next": next_run(j), "running": running(j["id"]),
                    "history": list(reversed(hist))})
    return out

def suggest_sources():
    """Folders worth backing up on this machine, with rough sizes, for the backup wizard."""
    import storage
    out, seen = [], set()
    def add(path, label, why, size=None):
        if path in seen or not os.path.isdir(path): return
        seen.add(path); out.append({"path": path, "label": label, "why": why, "size": size})
    m = storage.storage_map()
    for p in m["pools"]:
        if p["mount"]: add(p["mount"], f"{p['name']} pool", ", ".join(p["used_by"]) and f"Used by {', '.join(p['used_by'][:3])}" or "All files in the pool", (p.get("usage") or {}).get("used"))
    for d in m["drives"]:
        if "data" in d["roles"]:
            for mp in d["mounts"]: add(mp, os.path.basename(mp), f"{d['size_text']} {d['model']}", (storage.usage(mp) or {}).get("used"))
    add("/opt", "App settings (/opt)", "Docker compose files and app data for your containers")
    add("/etc", "Server settings (/etc)", "System configuration — small, worth keeping")
    for h in sorted(os.listdir("/home")) if os.path.isdir("/home") else []:
        add(f"/home/{h}", f"{h}'s home folder", "Documents, scripts and settings")
    add("/var/lib/docker/volumes", "Docker volumes", "Data some containers keep inside Docker")
    for s in out:
        if s["size"] is None:
            try:
                rc, so, _ = run(["du", "-sb", "--one-file-system", s["path"]], timeout=8)
                s["size"] = int(so.split()[0]) if rc == 0 else None
            except Exception: pass
    destinations = [{"path": d["mounts"][0], "label": f"{d['size_text']} {d['model'] or d['name']}", "free": (storage.usage(d["mounts"][0]) or {}).get("free"),
                     "backup": "backup" in d["roles"]} for d in m["drives"] if d["mounts"] and not d["system"] and "pool-member" not in d["roles"]]
    destinations += [{"path": p["mount"], "label": f"{p['name']} pool", "free": (p.get("usage") or {}).get("free"), "backup": False} for p in m["pools"] if p["mount"]]
    return {"sources": out, "destinations": destinations}


if __name__ == "__main__":
    sys.path.insert(0, "/usr/lib/nova-api")
    cmd = sys.argv[1:] or ["list"]
    if cmd[0] == "tick": tick()
    elif cmd[0] == "run" and len(cmd) == 2:
        j = next((x for x in load_jobs() if x["id"] == cmd[1]), None)
        if not j: sys.exit("no such job")
        print(json.dumps(run_job(j), indent=1))
    else: print(json.dumps(overview(), indent=1))
