#!/usr/bin/python3 -I
"""
nova-api helper — the ONLY privileged code the Nova app can reach.
Runs as root via one sudoers rule; the API itself runs as the unprivileged `nova-api` user.

Every verb is hard-coded below. Arguments are validated against live state (container names
must exist and be compose-managed, store items / programs must be in the server-side catalog,
drives are addressed by serial and protected mounts refuse). No shell, no caller-supplied paths
or commands. Output is one JSON object on stdout.
"""
import glob, json, os, re, secrets, shutil, socket, string, subprocess, sys, time

STORE = os.path.join(os.path.dirname(os.path.realpath(__file__)), "store")
UNMOUNTED = "/run/nova-unmounted.json"           # intentionally unmounted (monitor stays quiet)
SITE = {}
try: SITE = json.load(open("/etc/nova-api/config.json"))
except Exception: pass
# Site-specific storage layout (config.json); defaults are safe for any machine.
PROTECTED_MOUNTS = set(SITE.get("protected_mounts", ["/", "/boot", "/boot/efi"])) | {"/", "/boot", "/boot/efi"}
PROTECTED_PREFIXES = tuple(SITE.get("protected_prefixes", []))
BACKUP_MOUNT = SITE.get("backup_mount", "")
NAME_RE = r"[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"


def out(obj, code=0):
    print(json.dumps(obj)); sys.exit(code)

def fail(msg, code=1): out({"ok": False, "error": msg}, code)

def run(cmd, timeout=120, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, **kw)
    return r.returncode, r.stdout, r.stderr

def _tool(*paths):
    return next((p for p in paths if os.path.exists(p)), None)

def notify(level, title, detail=""):
    """Queue an alert (Inbox, phones, Discord). Never fails the action if alerts aren't set up."""
    t = _tool("/usr/sbin/nova-alert", "/usr/local/bin/nova-alert")
    if t:
        try: run([t, level, title, detail])
        except Exception: pass

def changelog(msg):
    """A line in the server's change log (nova-log if installed, else /var/log/nova-api/changes.log)."""
    t = _tool("/usr/local/bin/nova-log", "/usr/bin/nova-log")
    try:
        if t: run([t, "app", msg]); return
        with open("/var/log/nova-api/changes.log", "a") as f: f.write(time.strftime("%Y-%m-%d %H:%M ") + msg + "\n")
    except Exception: pass

def load_json(p, d):
    try: return json.load(open(p))
    except Exception: return d

# ── containers ───────────────────────────────────────────────────────────────
def inspect_all():
    rc, ids, _ = run(["docker", "ps", "-aq"])
    if rc != 0 or not ids.strip(): return []
    rc, js, _ = run(["docker", "inspect"] + ids.split())
    return [c for c in json.loads(js or "[]") if "com.docker.compose.project" in (c["Config"].get("Labels") or {})]

def summary(c):
    lab = c["Config"].get("Labels") or {}
    return {"name": c["Name"].lstrip("/"), "stack": lab["com.docker.compose.project"],
            "service": lab.get("com.docker.compose.service", ""),
            "image": c["Config"]["Image"].split("@")[0], "state": c["State"]["Status"],
            "health": (c["State"].get("Health") or {}).get("Status", ""),
            "started": c["State"].get("StartedAt", "")[:19],
            "store": os.path.exists(f"/opt/{lab['com.docker.compose.project']}/.nova-store"),
            "custom": os.path.exists(f"/opt/{lab['com.docker.compose.project']}/.nova-custom")}

def find(name):
    if not re.fullmatch(NAME_RE, name or ""): fail("bad name", 2)
    for c in inspect_all():
        if c["Name"].lstrip("/") == name: return c
    fail("unknown container", 2)

SECRETISH = re.compile(r"PASS|SECRET|TOKEN|KEY|PWD|AUTH|CREDENTIAL", re.I)

def info(c):
    s = summary(c)
    hc = c["HostConfig"]
    ports = []
    for k, v in (c["NetworkSettings"].get("Ports") or {}).items():
        for b in v or []: ports.append(f"{b.get('HostIp') or '0.0.0.0'}:{b.get('HostPort')} → {k}")
    env = []
    for e in c["Config"].get("Env") or []:
        k, _, v = e.partition("=")
        if k in ("PATH", "HOME", "HOSTNAME", "LANG", "TERM") or k.startswith(("GPG_", "PYTHON")): continue
        env.append({"key": k, "value": "••••••" if SECRETISH.search(k) else v[:120]})
    mounts = [{"source": m.get("Source", m.get("Name", "")), "target": m["Destination"], "rw": m.get("RW", True)}
              for m in c.get("Mounts", [])]
    rc, st, _ = run(["docker", "stats", "--no-stream", "--format", "{{json .}}", s["name"]], timeout=20)
    try: stats = json.loads(st.strip() or "{}")
    except Exception: stats = {}
    lab = c["Config"].get("Labels") or {}
    s.update({"created": c["Created"][:19], "restart_policy": hc["RestartPolicy"]["Name"] or "no",
              "privileged": hc.get("Privileged", False), "network": hc.get("NetworkMode", ""),
              "ports": ports, "env": env[:60], "mounts": mounts[:30],
              "compose_dir": lab.get("com.docker.compose.project.working_dir", ""),
              "cpu": stats.get("CPUPerc", ""), "mem": stats.get("MemUsage", ""), "net": stats.get("NetIO", ""),
              "pids": stats.get("PIDs", ""), "restarts": c.get("RestartCount", 0)})
    return s

# ── store ────────────────────────────────────────────────────────────────────
def catalog():
    items = {}
    for p in sorted(glob.glob(f"{STORE}/docker/*.json")):
        t = json.load(open(p)); items[t["id"]] = t
    return items

def port_free(port):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try: s.bind(("0.0.0.0", port)); return True
        except OSError: return False

def programs():
    return {p["pkg"]: p for p in load_json(f"{STORE}/programs.json", [])}

# ── drives ───────────────────────────────────────────────────────────────────
ROLES = {"/": "Boot drive", **SITE.get("drive_roles", {})}

def disks():
    rc, js, _ = run(["lsblk", "-J", "-b", "-o", "NAME,SIZE,MODEL,SERIAL,TRAN,TYPE,MOUNTPOINTS,FSTYPE,LABEL,UUID,ROTA"])
    smart = load_json("/var/lib/nova-alerts/state.json", {}).get("counters", {}).get("smart", {})
    res = []
    for d in json.loads(js).get("blockdevices", []):
        if d.get("type") != "disk" or d["name"].startswith(("loop", "zram")): continue
        mps = []
        for part in [d] + d.get("children", []):
            mps += [m for m in (part.get("mountpoints") or []) if m]
        usage = []
        for m in mps:
            if m == "[SWAP]": continue
            try:
                st = os.statvfs(m); tot = st.f_blocks * st.f_frsize; free = st.f_bavail * st.f_frsize
                usage.append({"mount": m, "total": tot, "used": tot - free, "free": free})
            except Exception: pass
        sm = smart.get(d.get("serial") or d["name"], {})
        role = next((ROLES[m] for m in mps if m in ROLES), "Not in use" if not mps else "Other")
        protected = any(m in PROTECTED_MOUNTS or (PROTECTED_PREFIXES and m.startswith(PROTECTED_PREFIXES)) or m == "[SWAP]" for m in mps)
        res.append({"name": d["name"], "serial": d.get("serial") or d["name"], "model": (d.get("model") or "").strip(),
                    "size": d["size"], "bus": d.get("tran") or "", "ssd": not d.get("rota"),
                    "role": role, "mounts": [m for m in mps if m != "[SWAP]"], "usage": usage,
                    "protected": protected, "temp": sm.get("temp"), "smart_passed": sm.get("passed"),
                    "realloc": sm.get("realloc"), "pending": sm.get("pending"), "uncorrect": sm.get("uncorrect"),
                    "crc": sm.get("crc")})
    return res

def disk_by_serial(serial):
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", serial or ""): fail("bad serial", 2)
    for d in disks():
        if d["serial"] == serial: return d
    fail("no such drive", 2)

def set_unmounted(mounts, add):
    cur = set(load_json(UNMOUNTED, []))
    cur = cur | set(mounts) if add else cur - set(mounts)
    with open(UNMOUNTED, "w") as f: json.dump(sorted(cur), f)

# ── dispatch ─────────────────────────────────────────────────────────────────
argv = sys.argv[1:]
if not argv: fail("no verb", 2)
verb, args = argv[0], argv[1:]

if verb == "containers" and not args:
    out({"containers": sorted([summary(c) for c in inspect_all()], key=lambda x: (x["stack"], x["name"]))})

if verb == "container" and len(args) == 2:
    action, name = args
    if action not in ("start", "stop", "restart"): fail("bad action", 2)
    find(name)
    rc, so, se = run(["docker", action, name], timeout=180)
    if rc == 0:
        notify("info", f"App: {action} {name}", "Done from the Nova app.")
        changelog(f"{action} container {name} (from the Nova app)")
    out({"ok": rc == 0, "error": se.strip()[:200] if rc else ""}, 0 if rc == 0 else 1)

if verb == "container-info" and len(args) == 1:
    out(info(find(args[0])))

if verb == "container-logs" and len(args) == 2:
    c = find(args[0]); n = max(10, min(2000, int(args[1]) if args[1].isdigit() else 200))
    rc, so, se = run(["docker", "logs", "--tail", str(n), "--timestamps", c["Name"].lstrip("/")], timeout=30)
    text = (so + se)[-200_000:]
    out({"ok": True, "lines": [l for l in re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", text).splitlines()][-n:]})

if verb == "container-update" and len(args) == 1:
    c = find(args[0]); lab = c["Config"]["Labels"]; wd = lab.get("com.docker.compose.project.working_dir", "")
    svc = lab.get("com.docker.compose.service", "")
    if not (wd.startswith("/opt/") and os.path.isdir(wd) and re.fullmatch(NAME_RE, svc)): fail("can't find its compose stack")
    rc, so, se = run(["docker", "compose", "pull", svc], timeout=1800, cwd=wd)
    if rc != 0: fail("pull failed: " + se.strip()[-300:])
    rc, so, se = run(["docker", "compose", "up", "-d", svc], timeout=600, cwd=wd)
    if rc == 0:
        changelog(f"Updated container {args[0]} to the newest image (from the Nova app)")
        notify("info", f"App: updated {args[0]}", "Pulled the newest image and recreated it.")
    out({"ok": rc == 0, "error": se.strip()[-300:] if rc else ""}, 0 if rc == 0 else 1)

if verb == "container-policy" and len(args) == 2:
    c = find(args[0]); pol = args[1]
    if pol not in ("no", "always", "unless-stopped", "on-failure"): fail("bad policy", 2)
    rc, so, se = run(["docker", "update", "--restart", pol, args[0]])
    if rc == 0: changelog(f"Restart policy of {args[0]} → {pol} (from the Nova app)")
    out({"ok": rc == 0, "error": se.strip()[:200]}, 0 if rc == 0 else 1)

if verb == "shell" and len(args) == 1:
    # Long-lived: stdin/stdout are wired to the API, which streams them to the app.
    c = find(args[0]); name = c["Name"].lstrip("/")
    if c["State"]["Status"] != "running": fail("container isn't running")
    os.execvp("docker", ["docker", "exec", "-i", "-e", "TERM=dumb", "-e", "PS1=\\u@\\h:\\w\\$ ", name, "sh", "-c",
                         "if command -v bash >/dev/null 2>&1; then exec bash -i 2>&1; else exec sh -i 2>&1; fi"])

def shell_user():
    """Whose account the server terminal, file manager and command apps use: config "shell_user",
    else the only person account on the machine (uid 1000+ with a login shell). Never root."""
    import pwd
    want = SITE.get("shell_user", "")
    if want:
        try: u = pwd.getpwnam(want)
        except KeyError: return None
        return u if u.pw_uid >= 1000 else None
    ok = set(l.strip() for l in open("/etc/shells") if l.startswith("/")) if os.path.exists("/etc/shells") else {"/bin/bash", "/bin/sh"}
    people = [u for u in pwd.getpwall() if 1000 <= u.pw_uid < 60000 and u.pw_shell in ok]
    return people[0] if len(people) == 1 else None

# ── the file manager: as your normal account (files.py) ──
def _files_user():
    sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
    import files
    if SITE.get("file_manager", True) is False: fail("the file manager is turned off (file_manager in /etc/nova-api/config.json)")
    u = shell_user()
    if not u: fail("set shell_user in /etc/nova-api/config.json to the account the file manager should use")
    files.drop(u); return files, u

if verb in ("files", "files-data") and len(args) == 1:
    try: a = json.loads(args[0])
    except ValueError: fail("bad request", 2)
    files, u = _files_user()
    try:
        if verb == "files": fn = files.OPS.get(str(a.get("op"))) or fail("unknown operation", 2); out(fn(a, u.pw_dir))
        else:
            fn = files.DATA_OPS.get(str(a.get("op"))) or fail("unknown operation", 2)
            out(fn(a, u.pw_dir, sys.stdin.buffer.read(6 * 1024 * 1024)))
    except (ValueError, OSError) as e:
        msg = str(e)
        if isinstance(e, PermissionError): msg = "you don't have permission to do that there"
        elif isinstance(e, FileNotFoundError): msg = "it isn't there any more"
        elif isinstance(e, IsADirectoryError): msg = "that's a folder"
        elif isinstance(e, OSError) and not isinstance(e, ValueError): msg = (e.strerror or msg)
        fail(msg)

if verb == "files-get" and len(args) == 1:
    # Streaming: a JSON header line, then the file's bytes.
    files, u = _files_user()
    try:
        p = files.norm(args[0], u.pw_dir)
        f = open(p, "rb"); size = os.fstat(f.fileno()).st_size
        if os.path.isdir(p): raise IsADirectoryError
    except Exception as e:
        os.write(1, (json.dumps({"error": "you don't have permission to read that" if isinstance(e, PermissionError) else "can't open that file"}) + "\n").encode()); sys.exit(1)
    import mimetypes
    os.write(1, (json.dumps({"size": size, "name": os.path.basename(p), "mime": mimetypes.guess_type(p)[0] or "application/octet-stream"}) + "\n").encode())
    while True:
        b = f.read(1 << 20)
        if not b: break
        os.write(1, b)
    sys.exit(0)

if verb == "host-shell" and len(args) == 1:
    # Long-lived: a login shell on the server in a real terminal, as a normal user — never root
    # (sudo asks for that user's password as usual). stdin/stdout are wired to the API.
    # Window size arrives in-band as ESC ] 7799 ; COLS ; ROWS BEL and is applied, not passed on.
    import pty, select, fcntl, termios, struct
    if SITE.get("host_shell", True) is False: fail("the server terminal is turned off (host_shell in /etc/nova-api/config.json)")
    u = shell_user()
    if not u: fail("set shell_user in /etc/nova-api/config.json to the account the terminal should open as")
    who = "".join(c for c in args[0] if c.isalnum() or c in " -_.'")[:60] or "a device"
    notify("warning", f"Server terminal opened by {who}", f"A shell as {u.pw_name}. If this wasn't you, remove that device in Users & devices.")
    changelog(f"Server terminal opened as {u.pw_name} from {who}")
    pid, fd = pty.fork()
    if pid == 0:
        os.execve("/usr/sbin/runuser", ["runuser", "-l", u.pw_name],
                  {"TERM": "xterm-256color", "LANG": "C.UTF-8", "PATH": "/usr/local/bin:/usr/bin:/bin"})
    RESIZE = re.compile(rb"\x1b\]7799;(\d{1,4});(\d{1,4})\x07")
    def winsize(cols, rows):
        try: fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", max(2, min(rows, 500)), max(2, min(cols, 1000)), 0, 0))
        except OSError: pass
    winsize(100, 30)
    try:
        while True:
            r, _, _ = select.select([0, fd], [], [], 60)
            if fd in r:
                try: data = os.read(fd, 65536)
                except OSError: break
                if not data: break
                os.write(1, data)
            if 0 in r:
                data = os.read(0, 65536)
                if not data: break
                for m in RESIZE.finditer(data): winsize(int(m.group(1)), int(m.group(2)))
                data = RESIZE.sub(b"", data)
                if data: os.write(fd, data)
    finally:
        try: os.kill(pid, 1); os.waitpid(pid, 0)
        except OSError: pass
    sys.exit(0)

if verb == "backup-now" and not args and not os.path.exists("/usr/local/bin/nova-backup"):
    sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
    import backups, tasks
    started = []
    for j in backups.load_jobs():
        if j.get("enabled", True):
            try: tasks.start("backup-run", {"job": j["id"]}); started.append(j["name"])
            except ValueError: pass
    out({"ok": bool(started), "note": f"Started: {', '.join(started)}" if started else "No backups set up yet — add one in Storage → Backups"})

if verb == "backup-now" and not args:
    rc, so, _ = run(["systemctl", "is-active", "nova-backup.service"])
    if so.strip() in ("active", "activating"): out({"ok": True, "note": "A backup is already running"})
    rc, _, se = run(["systemctl", "start", "--no-block", "nova-backup.service"])
    out({"ok": rc == 0, "error": se.strip()[:200]}, 0 if rc == 0 else 1)

def jobs_status():
    """Home's backup line on servers without a hand-written nova-backup script: from Nova's own jobs."""
    sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
    import backups, tasks
    jobs = backups.overview()
    if not jobs: return {"none": True}
    lasts = [j["last"] for j in jobs if j.get("last")]
    st = {"jobs": len(jobs), "sets": {j["name"]: ("ok" if (j.get("last") or {}).get("ok") else "partial" if (j.get("last") or {}).get("partial")
                                       else "failed" if j.get("last") else "never") for j in jobs}}
    if lasts: st["time"] = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(max(l["t"] for l in lasts)))
    run_t = next((t for t in tasks.all_tasks() if t["state"] == "running" and t["kind"] == "backup-run"), None)
    st["running"] = bool(run_t)
    if run_t: st["progress"] = {"phase": "copying", "pct": run_t.get("pct", 0), "set": run_t["title"].replace("Backup: ", ""), "rate": run_t.get("note", "")}
    return st

if verb == "backup-status" and not args and not os.path.exists("/usr/local/bin/nova-backup"):
    out(jobs_status())

if verb == "backup-status" and not args:
    st = load_json("/var/lib/nova-backup/backup_status.json", {})
    rc, so, _ = run(["systemctl", "is-active", "nova-backup.service"])
    st["running"] = so.strip() in ("active", "activating")
    st["verify"] = load_json("/var/lib/nova-backup/photos_last_verify.json", {})
    if st["running"]:
        pr = load_json("/var/lib/nova-backup/progress.json", None)
        if pr and time.time() - pr.get("t", 0) < 900: st["progress"] = pr
        else:                     # older script / between steps: say which sets are done, from the log
            done = []
            try:
                lines = open("/var/log/nova-backup.log", errors="replace").read().splitlines()[-400:]
                start = max(i for i, l in enumerate(lines) if "backup start" in l)
                done = [re.search(r"\[(\w+)\]", l)[1] for l in lines[start:] if re.search(r"^\S+ \S+ \[\w+\] ", l)]
                st["started_at"] = lines[start][:16]
            except Exception: pass
            st["progress"] = {"phase": "running", "done": done}
        try:                      # bytes on the backup drive: grows steadily during a full copy
            u = shutil.disk_usage(BACKUP_MOUNT or "/nonexistent"); st["progress"]["drive_used"] = u.used; st["progress"]["drive_total"] = u.total
        except OSError: pass
    out(st)

if verb == "free-ram" and not args:
    def mem(k):
        for l in open("/proc/meminfo"):
            if l.startswith(k + ":"): return int(l.split()[1])
    before = mem("MemFree")      # no sync first: it can block for minutes behind a backup; only clean pages are dropped anyway
    try:
        with open("/proc/sys/vm/drop_caches", "w") as f: f.write("1\n")
    except OSError as e: fail(f"this system doesn't allow dropping the cache ({e.strerror})")
    out({"ok": True, "freed_mb": max(0, (mem("MemFree") - before) // 1024), "available_mb": mem("MemAvailable") // 1024})

if verb == "notify-paired" and len(args) == 1:
    name = "".join(ch for ch in args[0] if ch.isalnum() or ch in " -_")[:40] or "device"
    notify("warning", f"New device paired with the Nova app: {name}",
           f"If this wasn't you, run: sudo nova remove '{name}'")
    changelog(f"New device paired: {name}")
    out({"ok": True})

if verb == "notify-ssh-key" and len(args) == 1:
    name = "".join(c for c in args[0] if c.isprintable())[:60]
    notify("warning", f"SSH access added for {name}", "Its key was installed from the Nova app with fingerprint confirmation. Removing the device in Nova removes the key too.")
    out({"ok": True})

if verb == "notify-revoked" and len(args) == 1 and args[0].isdigit():
    notify("warning", f"Nova app: {args[0]} paired device(s) removed", "Done from the app with fingerprint confirmation.")
    changelog(f"{args[0]} paired device(s) removed from the Nova app")
    out({"ok": True})

NOTIFY_KEYS = {"push_min_level": ("info", "warning", "critical"), "push_logins": bool, "push_usb": bool,
               "discord_paused": bool}
if verb == "get-notify" and not args:
    c = load_json("/etc/nova-alerts/config.json", {})
    out({k: c.get(k, {"push_min_level": "info", "push_logins": True, "push_usb": True, "discord_paused": False}[k])
         for k in NOTIFY_KEYS})
if verb == "set-notify" and len(args) == 2:
    k, v = args
    if k not in NOTIFY_KEYS: fail("unknown setting", 2)
    if NOTIFY_KEYS[k] is bool:
        if v not in ("true", "false"): fail("bad value", 2)
        val = v == "true"
    else:
        if v not in NOTIFY_KEYS[k]: fail("bad value", 2)
        val = v
    p = "/etc/nova-alerts/config.json"; c = load_json(p, {}); c[k] = val
    tmp = p + ".tmp"; json.dump(c, open(tmp, "w"), indent=2); os.chmod(tmp, 0o600); os.replace(tmp, p)
    out({"ok": True, k: val})

DISCORD_RE = r"https://(discord|discordapp)\.com/api/webhooks/\d{5,25}/[A-Za-z0-9_-]{20,100}"
if verb == "discord-status" and not args:
    w = load_json("/etc/nova-alerts/config.json", {}).get("discord_webhook", "")
    m = re.match(r"https://[^/]+/api/webhooks/(\d+)/", w or "")
    out({"configured": bool(re.fullmatch(DISCORD_RE, w or "")), "hint": f"webhook …{m.group(1)[-4:]}" if m else ""})       # never the secret part

if verb == "discord-set" and len(args) == 1:
    w = args[0].strip()
    if w and not re.fullmatch(DISCORD_RE, w): fail("that isn't a Discord webhook link (Channel → Edit → Integrations → Webhooks → Copy URL)")
    p = "/etc/nova-alerts/config.json"; c = load_json(p, {}); c["discord_webhook"] = w
    tmp = p + ".tmp"; json.dump(c, open(tmp, "w"), indent=2); os.chmod(tmp, 0o600); os.replace(tmp, p)
    changelog("Discord alerts " + ("set up" if w else "turned off") + " (from the Nova app)")
    out({"ok": True, "configured": bool(w)})

if verb == "discord-test" and not args:
    import urllib.request
    w = load_json("/etc/nova-alerts/config.json", {}).get("discord_webhook", "")
    if not re.fullmatch(DISCORD_RE, w or ""): fail("Discord isn't set up")
    req = urllib.request.Request(w, data=json.dumps({"content": f"✅ Nova on **{socket.gethostname()}** can send alerts to this channel."}).encode(),
                                 headers={"Content-Type": "application/json", "User-Agent": "nova"})
    try: urllib.request.urlopen(req, timeout=15).read(); out({"ok": True})
    except Exception as e: fail(f"Discord said no: {e}")

if verb == "store-list" and not args:
    res = []
    for t in catalog().values():
        inst = os.path.exists(f"/opt/{t['id']}/.nova-store")
        state = ""
        if inst:
            rc, so, _ = run(["docker", "compose", "ps", "--format", "{{.State}}"], cwd=f"/opt/{t['id']}", timeout=20)
            state = "running" if "running" in so else "stopped"
        res.append({k: t.get(k) for k in ("id", "name", "description", "category", "icon", "port", "path", "notes", "image")}
                   | {"installed": inst, "state": state, "port_free": inst or port_free(t["port"])})
    out({"items": res})

if verb == "custom-check" and len(args) == 1:
    sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
    import installs
    try: name, d, compose, _ = installs.custom_spec(json.loads(args[0]))
    except ValueError as e: fail(str(e))
    if os.path.exists(d): fail(f"{d} already exists — pick another name")
    out({"ok": True, "compose": compose})

if verb.startswith("cf-") and len(args) <= 1:
    # Labs · Cloudflare auto-setup (cfsync.py)
    sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
    import cfsync
    try:
        if verb == "cf-status": out(cfsync.status())
        if verb == "cf-token" and args: cfsync.save_token(args[0]); out({"ok": True, **cfsync.status()})
        if verb in ("cf-plan", "cf-apply") and args:
            a = json.loads(args[0]); fn = cfsync.plan if verb == "cf-plan" else cfsync.apply
            r = fn(a.get("host", ""), a.get("service", ""), bool(a.get("no_tls_verify")))
            if verb == "cf-apply": changelog(f"Cloudflare: published {r['host']} → {a.get('service')} behind Access (from the Nova app)")
            out(r)
        if verb == "cf-remove" and args: r = cfsync.remove(args[0]); changelog(f"Cloudflare: removed {args[0]} (from the Nova app)"); out(r)
        fail("bad request", 2)
    except ValueError as e: fail(str(e))

if verb == "program-search" and len(args) == 1:
    sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
    import installs
    out({"items": installs.search_programs(args[0])})

if verb == "programs" and not args:
    rc, so, _ = run(["dpkg-query", "-W", "-f", "${Package} ${db:Status-Abbrev}\n"])
    inst = {l.split()[0] for l in so.splitlines() if len(l.split()) > 1 and l.split()[1].startswith("ii")}
    out({"items": [p | {"installed": p["pkg"] in inst} for p in programs().values()]})

if verb == "drives" and not args:
    out({"drives": disks(), "unmounted": load_json(UNMOUNTED, []),
         "fans": {"supported": False,
                  "note": "This board's fan chip isn't supported by the built-in Linux driver yet."}})

if verb == "drive-unmount" and len(args) == 1:
    d = disk_by_serial(args[0])
    if d["protected"]: fail(f"{d['role']} can't be unmounted while the server is running")
    if not d["mounts"]: fail("it isn't mounted")
    if BACKUP_MOUNT and BACKUP_MOUNT in d["mounts"]:
        rc, so, _ = run(["systemctl", "is-active", "nova-backup.service"])
        if so.strip() in ("active", "activating"): fail("a backup is writing to it right now")
    for m in d["mounts"]:
        rc, so, se = run(["fuser", "-vm", m])
        users = sorted({l.split()[-1] for l in se.splitlines()[1:] if l.strip() and "kernel" not in l})
        if users: fail(f"{m} is in use by: {', '.join(users)}")
    for m in sorted(d["mounts"], key=len, reverse=True):
        rc, so, se = run(["umount", m])
        if rc != 0: fail(f"umount {m}: {se.strip()[:150]}")
    set_unmounted(d["mounts"] + [d["serial"]], True)
    unplug = False
    if d["bus"] == "usb":
        run(["sync"])
        try:
            open(f"/sys/block/{d['name']}/device/delete", "w").write("1\n"); unplug = True
        except Exception: pass
    changelog(f"Unmounted {d['model']} ({', '.join(d['mounts'])}) from the Nova app")
    out({"ok": True, "safe_to_unplug": unplug or d["bus"] == "usb",
         "note": "Safe to unplug." if unplug else "Unmounted. It stays connected until you power off or unplug it."})

if verb == "drive-mount" and len(args) == 1:
    d = disk_by_serial(args[0])
    rc, js, _ = run(["lsblk", "-J", "-o", "NAME,SERIAL,UUID"])
    uuids = set()
    for b in json.loads(js)["blockdevices"]:
        if (b.get("serial") or b["name"]) == d["serial"]:
            uuids |= {c.get("uuid") for c in [b] + b.get("children", []) if c.get("uuid")}
    targets = []
    for l in open("/etc/fstab"):
        f = l.split()
        if len(f) > 2 and not l.lstrip().startswith("#") and f[0].startswith("UUID=") and f[0][5:] in uuids:
            targets.append(f[1])
    if not targets: fail("no /etc/fstab entry for this drive")
    for m in targets:
        rc, so, se = run(["mount", m])
        if rc != 0 and "already mounted" not in se: fail(f"mount {m}: {se.strip()[:150]}")
    set_unmounted(targets + [d["serial"]], False)
    changelog(f"Mounted {d['model']} ({', '.join(targets)}) from the Nova app")
    out({"ok": True, "mounted": targets})

if verb == "power" and len(args) == 1 and args[0] in ("reboot", "poweroff"):
    changelog(f"Server {args[0]} requested from the Nova app")
    notify("warning", f"Server {'restarting' if args[0] == 'reboot' else 'shutting down'} (from the Nova app)", "")
    # nova-alert only queues the message (the monitor sends once a minute), so push it out now —
    # otherwise phones and Discord hear about the shutdown after the next boot.
    unit = SITE.get("alerts_unit", "nova-alerts.service")
    if run(["systemctl", "cat", unit])[0] == 0:
        for _ in range(2):            # a pass already running when we queued may have missed it
            try: run(["systemctl", "start", unit], timeout=40)
            except Exception: break
            if not glob.glob("/var/lib/nova-alerts/spool/*.json"): break
    rc, so, se = run(["systemd-run", "--on-active=8", "--unit=nova-app-power", "systemctl", args[0]])
    out({"ok": rc == 0, "in_seconds": 8, "error": se.strip()[:200]}, 0 if rc == 0 else 1)

# ── alerts: dismiss / delete (the monitor keeps the state) ─────────────────────────
MONITOR = _tool("/usr/lib/nova-api/monitor/nova_alerts.py", "/usr/local/lib/nova-alerts/nova_alerts.py")
ALERT_KEY_RE = r"[a-z]+:[\w./:@+-]{1,200}"
if verb == "alerts-dismiss" and len(args) == 1 and re.fullmatch(ALERT_KEY_RE, args[0]):
    if not MONITOR: fail("monitoring isn't installed")
    rc, so, se = run(["python3", MONITOR, "dismiss", args[0]], timeout=150)
    if rc != 0: fail((se or so).strip()[-200:] or "couldn't dismiss")
    changelog(f"Alert dismissed from the Nova app: {args[0]}")
    out(json.loads(so.strip().splitlines()[-1]))
if verb == "events-delete" and len(args) == 1 and re.fullmatch(r"all|[\d.]+(,[\d.]+){0,199}", args[0]):
    if not MONITOR: fail("monitoring isn't installed")
    rc, so, se = run(["python3", MONITOR, "delete-event", args[0]], timeout=150)
    if rc != 0: fail((se or so).strip()[-200:] or "couldn't delete")
    out(json.loads(so.strip().splitlines()[-1]))

# ── mount a drive that's in /etc/fstab (e.g. plugged in after boot) ─────────────────
def fstab_points():
    pts = []
    for line in open("/etc/fstab"):
        f = line.split()
        if len(f) >= 3 and not f[0].startswith("#") and f[1].startswith("/") and f[2] not in ("swap", "none"): pts.append(f[1])
    return pts
if verb == "mount-fstab" and len(args) == 1:
    mp = args[0]
    if mp not in fstab_points(): fail("that isn't a mount point in /etc/fstab")
    if os.path.ismount(mp): out({"ok": True, "already": True})
    rc, so, se = run(["mount", mp], timeout=60)
    if rc != 0: fail(f"mount {mp}: {(se or so).strip()[:200]}")
    set_unmounted([mp], False)
    changelog(f"Mounted {mp} from the Nova app"); notify("info", f"Mounted {mp}", "From the Nova app.")
    out({"ok": True})

# ── drive health tests (SMART self-tests: read-only, safe while the drive is in use) ──
def smart_dev(serial):
    d = disk_by_serial(serial)
    if not d: fail("no such drive")
    return f"/dev/{d['name']}", d
if verb == "smart-test" and len(args) == 2 and args[1] in ("short", "long", "abort"):
    dev, d = smart_dev(args[0])
    rc, so, se = run(["smartctl", "-X" if args[1] == "abort" else "-t", *([] if args[1] == "abort" else [args[1]]), dev], timeout=60)
    if rc & 0b11: fail((so + se).strip().splitlines()[-1][:200] if (so + se).strip() else "smartctl failed")
    m = re.search(r"Please wait (\d+) minutes", so)
    if args[1] != "abort": changelog(f"Started a {args[1]} SMART test on {d.get('model')} ({args[0]}) from the Nova app")
    out({"ok": True, "minutes": int(m.group(1)) if m else None})
if verb == "smart-tests" and len(args) == 1:
    dev, d = smart_dev(args[0])
    rc, so, _ = run(["smartctl", "-j", "-c", "-A", "-l", "selftest", dev], timeout=60)
    try: j = json.loads(so)
    except Exception: fail("smartctl gave no answer")
    st = (j.get("ata_smart_data") or {}).get("self_test", {}).get("status", {})
    nv = j.get("nvme_self_test_log") or {}
    running = st.get("remaining_percent") is not None and "in progress" in (st.get("string") or "").lower() \
        or bool(nv.get("current_self_test_operation", {}).get("value"))
    log = []
    for t in ((j.get("ata_smart_self_test_log") or {}).get("standard") or {}).get("table", [])[:10]:
        log.append({"type": t.get("type", {}).get("string", ""), "result": t.get("status", {}).get("string", ""),
                    "passed": t.get("status", {}).get("passed"), "hours": t.get("lifetime_hours")})
    for t in (nv.get("table") or [])[:10]:
        log.append({"type": t.get("self_test_code", {}).get("string", ""), "result": t.get("self_test_result", {}).get("string", ""),
                    "passed": t.get("self_test_result", {}).get("value") == 0, "hours": t.get("power_on_hours")})
    caps = (j.get("ata_smart_data") or {}).get("self_test", {}).get("polling_minutes", {})
    out({"running": running, "remaining_pct": st.get("remaining_percent") if running else None,
         "progress_pct": nv.get("current_self_test_completion_percent") if running and nv else None,
         "status": st.get("string") or "", "log": log, "power_on_hours": (j.get("power_on_time") or {}).get("hours"),
         "short_minutes": caps.get("short"), "long_minutes": caps.get("extended"), "supported": bool(caps) or bool(nv)})

# ── SSH: a phone's own key in the terminal user's authorized_keys ─────────────────
# The key comes from the phone's hardware keystore over the signed, fingerprint-confirmed API,
# so nobody has to copy and paste it. Each line is tagged with the device id, so removing the
# device in Nova also removes its SSH access. Forwarding is disabled: the terminal needs a shell only.
DEV_RE = r"[A-Za-z0-9_-]{8,40}"
SSH_OPTS = "no-port-forwarding,no-agent-forwarding,no-X11-forwarding"

def _ak_path():
    import pwd
    user = SITE.get("ssh_user", "")
    if not re.fullmatch(r"[a-z_][a-z0-9_-]{0,31}", user or ""): fail("no terminal user set (sudo nova-setup)")
    try: pw = pwd.getpwnam(user)
    except KeyError: fail(f"user {user} doesn't exist")
    return pw, os.path.join(pw.pw_dir, ".ssh"), os.path.join(pw.pw_dir, ".ssh", "authorized_keys")

def _ak_write(pw, d, f, lines):
    if os.path.islink(d) or os.path.islink(f): fail("~/.ssh is a symlink — refusing")
    os.makedirs(d, mode=0o700, exist_ok=True); os.chown(d, pw.pw_uid, pw.pw_gid); os.chmod(d, 0o700)
    tmp = f + ".nova-tmp"
    with open(tmp, "w") as fh: fh.write("".join(l if l.endswith("\n") else l + "\n" for l in lines))
    os.chown(tmp, pw.pw_uid, pw.pw_gid); os.chmod(tmp, 0o600); os.replace(tmp, f)

def _ak_lines(f):
    try: return open(f).read().splitlines(True)
    except FileNotFoundError: return []

if verb == "ssh-authorize" and len(args) == 2 and re.fullmatch(DEV_RE, args[0]):
    import base64
    parts = args[1].split()
    if len(parts) < 2 or parts[0] != "ecdsa-sha2-nistp256" or not re.fullmatch(r"[A-Za-z0-9+/]{100,200}={0,2}", parts[1]): fail("not a Nova phone key")
    blob = base64.b64decode(parts[1])
    if blob[4:4 + 19] != b"ecdsa-sha2-nistp256": fail("not a Nova phone key")
    pw, d, f = _ak_path(); tag = f"nova-{args[0]}"
    keep = [l for l in _ak_lines(f) if not l.rstrip().endswith(" " + tag)]       # replaces this phone's old key
    _ak_write(pw, d, f, keep + [f"{SSH_OPTS} {parts[0]} {parts[1]} {tag}"])
    changelog(f"SSH key of Nova device {args[0]} added to {pw.pw_name}'s authorized_keys (from the Nova app)")
    out({"ok": True, "user": pw.pw_name})

if verb == "ssh-unauthorize" and len(args) == 1 and re.fullmatch(DEV_RE, args[0]):
    pw, d, f = _ak_path(); tag = f"nova-{args[0]}"
    lines = _ak_lines(f); keep = [l for l in lines if not l.rstrip().endswith(" " + tag)]
    if len(keep) != len(lines):
        _ak_write(pw, d, f, keep); changelog(f"SSH key of removed Nova device {args[0]} taken out of authorized_keys")
    out({"ok": True, "removed": len(lines) - len(keep)})

if verb == "ssh-key-status" and len(args) == 1 and re.fullmatch(DEV_RE, args[0]):
    pw, d, f = _ak_path()
    out({"installed": any(l.rstrip().endswith(f" nova-{args[0]}") for l in _ak_lines(f)), "user": pw.pw_name})

# ── self-update (signed releases only; see nova-update) ─────────────────────────
if verb == "update-status" and not args:
    st = load_json("/var/lib/nova-api/update.json", {})
    rc, v, _ = run(["dpkg-query", "-W", "-f=${Version}", "nova-server"])
    st["installed"] = v.strip() if rc == 0 else ""
    st["source"] = SITE.get("update_source", "")
    rc, so, _ = run(["systemctl", "is-active", "nova-self-update.service"])
    st["running"] = so.strip() in ("active", "activating")
    out(st)
if verb == "update-check" and not args:
    rc, so, se = run(["/usr/sbin/nova-update", "--check"], timeout=180)
    out({"ok": rc == 0, "message": (so or se).strip()[-300:]}, 0 if rc == 0 else 1)
if verb == "update-start" and not args:
    # In its own transient unit so it survives nova-api restarting during the upgrade.
    run(["systemctl", "reset-failed", "nova-self-update.service"])
    rc, so, se = run(["systemd-run", "--unit=nova-self-update", "--collect", "--quiet", "/usr/sbin/nova-update"])
    changelog("Server update started from the Nova app")
    out({"ok": rc == 0, "error": se.strip()[:200]}, 0 if rc == 0 else 1)

# ── storage, backups, diagnostics (modules next to this file) ───────────────────────
sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
JOB_RE = r"[a-z0-9]{1,12}"
def _guard(fn):
    try: out(fn())
    except ValueError as e: fail(str(e))
    except RuntimeError as e: fail(str(e))

if verb == "list-dirs" and len(args) == 1:
    # Folder names under a path, for the backup folder picker (no files, no system folders).
    def go():
        p = os.path.realpath("/" + args[0].strip("/")) if args[0] != "/" else "/"
        if any(p == x or p.startswith(x + "/") for x in ("/proc", "/sys", "/dev", "/run")): raise ValueError("not a folder you can back up")
        if not os.path.isdir(p): raise ValueError("no such folder")
        out = []
        with os.scandir(p) as it:
            for e in it:
                if e.is_dir(follow_symlinks=False) and not (p == "/" and e.name in ("proc", "sys", "dev", "run", "lost+found")):
                    out.append(e.name)
                if len(out) >= 500: break
        return {"path": p, "dirs": sorted(out, key=str.lower)}
    _guard(go)

if verb == "apps-detect" and not args:
    import apps; _guard(lambda: {"apps": apps.detect()})

if verb == "storage-map" and not args:
    import storage; _guard(storage.storage_map)

if verb == "task-start" and len(args) == 2:
    import tasks
    def go():
        try: spec = json.loads(args[1])
        except ValueError: raise ValueError("bad request")
        return tasks.start(args[0], spec)
    _guard(go)

if verb == "task-list" and not args:
    import tasks
    def go(): tasks.reap(); return {"tasks": [{**{k: v for k, v in t.items() if k not in ("samples", "log")}, "log": t.get("log", [])[-3:]} for t in tasks.all_tasks()[:30]]}
    _guard(go)

if verb == "task-status" and len(args) == 1 and re.fullmatch(r"[0-9a-f]{12}", args[0]):
    import tasks
    def go():
        tasks.reap(); t = tasks.load(args[0])
        if not t: raise ValueError("no such task")
        return t
    _guard(go)

if verb == "task-stop" and len(args) == 1 and re.fullmatch(r"[0-9a-f]{12}", args[0]):
    import tasks; _guard(lambda: tasks.stop(args[0]))

if verb == "backups-list" and not args:
    import backups, storage
    _guard(lambda: {"jobs": backups.overview(), "legacy": storage.legacy_backup()})

if verb == "backup-put" and len(args) == 1:
    import backups
    def go():
        try: job = json.loads(args[0])
        except ValueError: raise ValueError("bad request")
        j = backups.put_job(job); changelog(f"Backup '{j['name']}' saved (from the Nova app)"); return {"ok": True, "job": j}
    _guard(go)

if verb == "backup-delete" and len(args) == 1 and re.fullmatch(JOB_RE, args[0]):
    import backups
    def go(): backups.delete_job(args[0]); changelog(f"Backup job {args[0]} removed (its copies stay on the drive)"); return {"ok": True}
    _guard(go)

if verb in ("backup-snapshots", "backup-browse") and 1 <= len(args) <= 3 and re.fullmatch(JOB_RE, args[0]):
    import backups
    def go():
        j = next((x for x in backups.load_jobs() if x["id"] == args[0]), None)
        if not j: raise ValueError("no such backup")
        if verb == "backup-snapshots": return {"snapshots": backups.list_snapshots(j, log=lambda m: None)}
        return backups.browse(j, args[1], args[2] if len(args) > 2 else "/", log=lambda m: None)
    _guard(go)

if verb == "backup-test" and len(args) == 1:
    import backups
    def go():
        try: job = json.loads(args[0])
        except ValueError: raise ValueError("bad request")
        tid = "tst" + secrets.token_hex(4)
        j = backups.validate({**job, "id": tid}); pw = j.pop("_password", None)
        cred = f"{backups.SECRETS}/{tid}.cred"
        try:
            if j["dest"]["type"] == "smb":
                old = f"{backups.SECRETS}/{job.get('id')}.cred" if re.fullmatch(JOB_RE, str(job.get("id", ""))) else ""
                os.makedirs(backups.SECRETS, exist_ok=True); os.chmod(backups.SECRETS, 0o700)
                if pw is None and old and os.path.exists(old): shutil.copy(old, cred)
                else:
                    fd = os.open(cred, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                    with os.fdopen(fd, "w") as f: f.write(f"username={j['dest'].get('user') or 'guest'}\npassword={pw or ''}\n")
            return backups.test_dest(j, log=lambda m: None)
        finally:
            try: os.remove(cred)
            except OSError: pass
    _guard(go)

if verb == "backup-suggest" and not args:
    import backups; _guard(backups.suggest_sources)

if verb == "diag-quick" and 2 <= len(args) <= 3 and args[0] in ("ping", "trace", "dns", "port"):
    import diag
    _guard(lambda: getattr(diag, args[0])(*args[1:]))

if verb == "diag-top" and not args:
    import diag; _guard(diag.top)

if verb == "tools-status" and not args:
    import storage
    _guard(lambda: {"tools": {t: storage.have(t) for t in storage.TOOLS}, "missing": storage.missing_packages(list(storage.TOOLS))})

fail("unknown verb", 2)
