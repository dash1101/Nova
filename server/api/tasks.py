#!/usr/bin/python3 -I
"""
Nova background tasks: storage operations, backups, restores and diagnostics.

Each task runs as its own transient systemd unit (nova-task-<id>), so it keeps going if the API
restarts and can be stopped from the app. Progress is a small JSON file in /run/nova-tasks that the
helper reads back for the app (title, state, percent, current step, log lines, result).

The spec of every kind is checked here before anything starts; the operations check the live system
again themselves (storage.py, backups.py, diag.py).

  tasks.py start <kind> <spec-json>   (helper)   → {"id": …}
  tasks.py exec <id>                  (systemd)
  tasks.py run <kind> <spec-json>     (root shell: inline, for testing)
"""
import fcntl, json, os, re, secrets, subprocess, sys, time

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)
DIR = "/run/nova-tasks"
STORAGE_KINDS = {"format", "combine", "raid", "pool-remove", "pool-add", "fstab-nofail", "fstab-add", "repair", "clone", "swap-setup", "partition-apply", "partition-restore", "mount-set", "mount-clear"}
DIAG_KINDS = {"net-internet", "disk-speed", "cpu-stress", "mem-test"}
BACKUP_KINDS = {"backup-run", "restore", "tools"}
UPDATE_KINDS = {"updates-check", "apt-upgrade", "containers-update"}
LABS_KINDS = {"images-prune", "freeup", "brain-install", "voice-install"}
INSTALL_KINDS = {"run-command", "store-install", "store-uninstall", "custom-install", "custom-uninstall", "program-install", "program-remove", "share-setup"}
WATCH_KINDS = {"drive-test"}                          # follows something the hardware does by itself (a SMART self-test)
KINDS = STORAGE_KINDS | DIAG_KINDS | BACKUP_KINDS | UPDATE_KINDS | INSTALL_KINDS | LABS_KINDS | WATCH_KINDS
TITLES = {"repair": "Repair a drive", "clone": "Clone a drive", "format": "Set up a drive", "combine": "Combine drives", "raid": "Create a RAID array", "pool-remove": "Remove a pool",
          "pool-add": "Add a drive", "fstab-nofail": "Boot without missing drives", "fstab-add": "Keep a drive mounted", "swap-setup": "Set up swap", "partition-apply": "Change partitions", "partition-restore": "Restore a partition table", "mount-set": "Change where a drive is mounted", "mount-clear": "Clear a leftover mount",
          "net-internet": "Internet speed test", "disk-speed": "Drive speed test", "cpu-stress": "CPU stress test", "mem-test": "Memory test",
          "backup-run": "Backup", "restore": "Restore from backup", "tools": "Install tools",
          "updates-check": "Check for updates", "apt-upgrade": "Update packages", "containers-update": "Update containers", "images-prune": "Clean up old images", "freeup": "Free up space", "drive-test": "Drive health test", "brain-install": "Download the assistant's brain", "voice-install": "Download voice for the assistant"}
GROUP = {**{k: "storage" for k in STORAGE_KINDS}, **{k: "diag" for k in DIAG_KINDS}, "restore": "storage", "tools": "storage",
         **{k: "updates" for k in UPDATE_KINDS | LABS_KINDS}, "program-install": "updates", "program-remove": "updates"}     # one apt at a time


# a finished task becomes an ordinary Inbox event (these kinds already send their own)
ANNOUNCES_DONE = {"store-install", "custom-install", "share-setup", "backup-run", "restore", "updates-check"}
ANNOUNCES_FAIL = {"backup-run"}


def path(tid, ext="json"): return f"{DIR}/{tid}.{ext}"
def load(tid):
    try: return json.load(open(path(tid)))
    except Exception: return None

def save(t):
    os.makedirs(DIR, exist_ok=True); os.chmod(DIR, 0o755)
    tmp = path(t["id"]) + ".tmp"
    with open(tmp, "w") as f: json.dump(t, f)
    os.chmod(tmp, 0o644); os.replace(tmp, path(t["id"]))

def all_tasks():
    out = []
    for f in sorted(os.listdir(DIR)) if os.path.isdir(DIR) else []:
        if f.endswith(".json"):
            t = load(f[:-5])
            if t: out.append(t)
    return sorted(out, key=lambda t: -t["started"])

def active_for(kind, key=None):
    for t in all_tasks():
        if t["state"] == "running" and t["kind"] == kind and (key is None or t.get("key") == key): return t
    return None


def check_spec(kind, spec):
    """Shape checks (types, sizes); the operation itself checks the live system again."""
    if kind not in KINDS: raise ValueError("unknown task")
    if not isinstance(spec, dict) or len(json.dumps(spec)) > 8000: raise ValueError("bad request")
    serial = re.compile(r"[A-Za-z0-9_.:/-]{1,128}")
    for k in ("drives", "keep", "confirm"):
        v = spec.get(k)
        if v is not None and not (isinstance(v, list) and len(v) <= 16 and all(isinstance(x, str) and serial.fullmatch(x) for x in v)) \
                and not (k == "keep" and isinstance(v, str) and serial.fullmatch(v)):
            raise ValueError(f"bad {k}")
    if kind == "tools":
        import storage
        bad = [t for t in spec.get("tools", []) if t not in storage.TOOLS]
        if bad or not spec.get("tools"): raise ValueError("unknown tool")
    if kind in INSTALL_KINDS:
        import installs
        if kind.startswith("store-") and not re.fullmatch(r"[a-z0-9-]{1,40}", str(spec.get("id", ""))): raise ValueError("bad app id")
        if kind == "run-command" and not re.fullmatch(r"custom-[0-9a-f]{8}|plugin-[a-z0-9-]{1,40}--[a-z0-9-]{1,40}", str(spec.get("id", ""))): raise ValueError("bad app id")
        if kind.startswith("program-") and not re.fullmatch(installs.PKG_RE, str(spec.get("pkg", ""))): raise ValueError("bad package name")
        if kind == "custom-install": installs.custom_spec(spec)
        if kind == "custom-uninstall" and not re.fullmatch(installs.CUSTOM_NAME, str(spec.get("name", ""))): raise ValueError("bad name")
        if kind == "share-setup":
            if not re.fullmatch(r"[a-z0-9-]{1,40}", str(spec.get("companion", ""))) or not re.fullmatch(r"[0-9]{1,5}", str(spec.get("app_port", ""))): raise ValueError("bad share setup")
            spec["name"] = str(spec.get("for_host") or spec.get("share_host"))[:253]
    if kind == "swap-setup":
        import storage
        if spec.get("gb") not in storage.SWAP_SIZES: raise ValueError("pick a swap size")
    if kind == "freeup":
        import freeup
        it = spec.get("items")
        if not (isinstance(it, list) and it and all(isinstance(x, str) and x in freeup.ITEMS for x in it)): raise ValueError("pick what to remove")
    if kind == "brain-install" and spec.get("model") not in ("smart", "light"): raise ValueError("pick Smart or Light")
    if kind == "drive-test":
        if not re.fullmatch(r"[A-Za-z0-9_.:-]{1,64}", str(spec.get("serial", ""))) or spec.get("type") not in ("short", "long"): raise ValueError("bad drive test")
        spec["minutes"] = max(1, min(2000, int(spec.get("minutes") or (2 if spec["type"] == "short" else 120))))
    if kind in ("backup-run", "restore"):
        if not re.fullmatch(r"[a-z0-9]{1,12}", str(spec.get("job", ""))): raise ValueError("bad backup id")
    return spec

def start(kind, spec):
    check_spec(kind, spec)
    group = GROUP.get(kind)
    if group:
        busy = next((t for t in all_tasks() if t["state"] == "running" and GROUP.get(t["kind"]) == group), None)
        if busy: raise ValueError(f"wait for “{busy['title']}” to finish first")
    tid = secrets.token_hex(6)
    title = TITLES.get(kind, kind)
    if kind in INSTALL_KINDS:
        import installs
        busy = next((t for t in all_tasks() if t["state"] == "running" and t["kind"] == kind and t.get("key") == (spec.get("id") or spec.get("pkg") or spec.get("name"))), None)
        if busy: raise ValueError("that's already in progress")
        what = spec.get("pkg") or spec.get("name") or (installs.catalog().get(spec.get("id"), {}).get("name")) or spec.get("id")
        if kind == "run-command":
            cfg = json.load(open(installs.APPS)) if os.path.exists(installs.APPS) else {}
            import plugins
            pc = plugins.command(spec.get("id"))
            what = (pc or {}).get("name") or next((c.get("name") for c in cfg.get("custom", []) if c.get("id") == spec.get("id")), None) or (_ for _ in ()).throw(ValueError("no such command app"))
        title = f"{installs.TITLES[kind]} {what}"
    if kind == "drive-test": title = f"{'Full' if spec['type'] == 'long' else 'Quick'} drive test: {spec['serial']}"
    if kind == "backup-run":
        import backups
        j = next((x for x in backups.load_jobs() if x["id"] == spec["job"]), None)
        if not j: raise ValueError("no such backup")
        title = f"Backup: {j['name']}"
    t = {"id": tid, "kind": kind, "title": title, "state": "running", "pct": 0, "step": "Starting…", "log": [], "started": time.time(),
         "key": spec.get("job") or spec.get("pool") or spec.get("path") or spec.get("id") or spec.get("pkg") or spec.get("name") or ""}
    save(t)
    fd = os.open(path(tid, "spec"), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f: json.dump({"kind": kind, "spec": spec}, f)
    if os.environ.get("NOVA_TASKS_INLINE") == "1":
        execute(tid); return load(tid)
    rc = subprocess.run(["systemd-run", f"--unit=nova-task-{tid}", "--collect", "--quiet", "--property=KillMode=mixed",
                         "--property=TimeoutStopSec=30", "/usr/bin/python3", "-I", os.path.join(HERE, "tasks.py"), "exec", tid],
                        capture_output=True, text=True, timeout=30)
    if rc.returncode != 0:
        t.update(state="failed", error="couldn't start: " + rc.stderr.strip()[-200:], finished=time.time()); save(t)
    return t

def execute(tid):
    t = load(tid); sp = json.load(open(path(tid, "spec"))); kind, spec = sp["kind"], sp["spec"]
    last = [0.0]
    def log(msg):
        t["log"] = (t["log"] + [time.strftime("%H:%M:%S ") + str(msg)])[-200:]; t["step"] = str(msg); save(t)
    def progress(pct, note=""):
        t["pct"] = round(max(0, min(100, pct)), 1)
        if note: t["note"] = note
        if time.time() - last[0] > 0.7: last[0] = time.time(); save(t)
    def sample(s):                                   # live chart points for the stress tests
        t.setdefault("samples", []).append(s); t["samples"] = t["samples"][-900:]
    try:
        if kind in STORAGE_KINDS:
            import storage
            res = storage.OPS[kind](spec, log, progress)
            if kind in storage.DESTRUCTIVE: changelog(f"Storage: {t['title']} — {json.dumps({k: v for k, v in spec.items() if k != 'confirm'})} (from the Nova app)")
        elif kind in DIAG_KINDS:
            import diag
            res = diag.TESTS[kind](spec, log, progress, sample) if kind in ("cpu-stress", "mem-test") else diag.TESTS[kind](spec, log, progress)
        elif kind == "drive-test":
            res = watch_drive_test(spec, log, progress)
        elif kind == "backup-run":
            import backups
            j = next(x for x in backups.load_jobs() if x["id"] == spec["job"])
            res = backups.run_job(j, log, progress, force=bool(spec.get("force")))
        elif kind == "restore":
            import backups
            j = next((x for x in backups.load_jobs() if x["id"] == spec["job"]), None)
            if not j: raise ValueError("no such backup")
            res = backups.restore(j, str(spec.get("snapshot", "")), str(spec.get("path", "")), str(spec.get("to", "beside")), log, progress)
        elif kind in UPDATE_KINDS:
            import updates
            res = {"updates-check": updates.check, "apt-upgrade": updates.apt_upgrade, "containers-update": updates.containers}[kind](spec, log, progress)
            if kind != "updates-check": changelog(f"{t['title']}: {json.dumps(spec)[:200]} (from the Nova app)")
        elif kind in INSTALL_KINDS:
            import installs
            res = installs.OPS[kind](spec, log, progress)
        elif kind == "images-prune":
            import labs
            res = labs.prune(spec, log, progress)
        elif kind == "freeup":
            import freeup
            res = freeup.run_freeup(spec, log, progress)
            changelog(f"Free up space: {', '.join(spec.get('items') or [])} — freed {res['freed'] / 1e9:.1f} GB (from the Nova app)")
        elif kind == "brain-install":
            import brain
            res = brain.install(spec, log, progress)
            changelog(f"Assistant's brain installed ({spec.get('model')}) from the Nova app")
        elif kind == "voice-install":
            import brain
            res = brain.install_voice(spec, log, progress); changelog("Assistant voice installed from the Nova app")
        elif kind == "tools":
            import storage
            storage.ensure_tools(spec["tools"], log); res = {"installed": spec["tools"]}
        summary = (t["log"][-1][9:] if t["log"] else "")[:300]
        t.update(state="done", pct=100, result=res, step="Done")
        if kind not in ANNOUNCES_DONE: notify("info", f"Done: {t['title']}", summary, "task")
    except ValueError as e:
        if isinstance(e, UnicodeError):              # a crash reading a tool's output, not a refusal: it may have done things already
            t.update(state="failed", error=f"Nova couldn't read the result: {e}"[:500], step="Failed")
            notify("warning", f"{t['title']} failed", t["error"][:300], "task")
        else:                                        # refused by a safety check: the app shows why
            t.update(state="failed", error=str(e)[:500], step="Not started", refused=True)     # (shown right away where it was started)
    except Exception as e:
        t.update(state="failed", error=str(e)[:500], step="Failed")
        if kind not in ANNOUNCES_FAIL: notify("warning", f"{t['title']} failed", str(e)[:300], "task")
    t["finished"] = time.time(); save(t)
    try: os.remove(path(tid, "spec"))
    except OSError: pass
    for old in all_tasks()[40:]:
        for ext in ("json", "spec"):
            try: os.remove(path(old["id"], ext))
            except OSError: pass

def watch_drive_test(spec, log, progress):
    """The drive runs its own self-test; this only asks it how far along it is, once a minute, until it's done
    (or twice the time it said, plus half an hour). Read-only."""
    helper = os.path.join(HERE, "helper")
    what = "full" if spec["type"] == "long" else "quick"
    log(f"The drive is testing itself ({what} test, about {spec['minutes']} minutes) — you can keep using it")
    end = time.time() + spec["minutes"] * 120 + 1800
    last = None
    while time.time() < end:
        time.sleep(20 if spec["type"] == "short" else 60)
        try: r = json.loads(subprocess.run([helper, "smart-tests", spec["serial"]], capture_output=True, text=True, timeout=90).stdout or "{}")
        except Exception: continue
        if r.get("running"):
            pct = r.get("progress_pct")
            if pct is None and r.get("remaining_pct") is not None: pct = 100 - r["remaining_pct"]
            if pct is not None: progress(min(99, pct), f"{pct}% done")
            continue
        last = (r.get("log") or [None])[0]
        break
    if not last: raise RuntimeError("the drive stopped reporting its test — look at its page for the result")
    ok = last.get("passed") is not False and "completed without error" in (last.get("result") or "").lower() or last.get("passed") is True
    if not ok: raise RuntimeError(f"the drive's test didn't pass: {last.get('result') or 'see its page'}")
    progress(100, "Passed"); log(f"Passed — {last.get('result') or 'completed without error'}")
    return {"summary": f"Passed — {last.get('result') or 'completed without error'}"}

def stop(tid):
    t = load(tid)
    if not t or t["state"] != "running": raise ValueError("not running")
    if t["kind"] in ("format", "combine", "raid", "pool-remove", "pool-add", "repair"): raise ValueError("drive setup can't be stopped halfway — it finishes in a moment")
    subprocess.run(["systemctl", "stop", f"nova-task-{tid}.service"], capture_output=True, timeout=60)
    t.update(state="stopped", step="Stopped", finished=time.time()); save(t)
    return t

def notify(level, title, detail="", category="script"):
    p = next((x for x in ("/usr/sbin/nova-alert", "/usr/local/bin/nova-alert") if os.path.exists(x)), None)
    try:
        if p: subprocess.run([p, level, title, detail], capture_output=True, timeout=30, env={**os.environ, "NOVA_ALERT_CATEGORY": category})
        subprocess.run(["systemctl", "start", "--no-block", "nova-alerts.service"], capture_output=True, timeout=10)   # into the Inbox now, not at the next minute
    except Exception: pass

def changelog(msg):
    p = next((x for x in ("/usr/local/bin/nova-log", "/usr/bin/nova-log") if os.path.exists(x)), None)
    try:
        if p: subprocess.run([p, "app", msg], capture_output=True, timeout=30)
        else:
            import safeio; safeio.append("/var/log/nova-api/changes.log", time.strftime("%Y-%m-%d %H:%M ") + msg + "\n")
    except Exception: pass

def reap():
    """A task whose unit died (server restart, kill) is marked failed instead of 'running' forever."""
    for t in all_tasks():
        if t["state"] == "running" and time.time() - t["started"] > 5:
            rc = subprocess.run(["systemctl", "is-active", f"nova-task-{t['id']}.service"], capture_output=True, text=True).stdout.strip()
            if rc not in ("active", "activating", "deactivating"):
                t.update(state="failed", error="stopped unexpectedly (restart?)", finished=time.time()); save(t)
                notify("warning", f"{t['title']} stopped unexpectedly", "The server or Nova restarted while it was running — start it again.", "task")


if __name__ == "__main__":
    a = sys.argv[1:]
    try:
        if a[:1] == ["exec"] and len(a) == 2 and re.fullmatch(r"[0-9a-f]{12}", a[1]): execute(a[1])
        elif a[:1] in (["start"], ["run"]) and len(a) == 3:
            if a[0] == "run": os.environ["NOVA_TASKS_INLINE"] = "1"
            print(json.dumps(start(a[1], json.loads(a[2])), indent=1))
        else: sys.exit("usage: tasks.py start|run <kind> <spec-json> | exec <id>")
    except ValueError as e:
        print(json.dumps({"ok": False, "error": str(e)})); sys.exit(1)
