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

def notify(level, title, detail=""):
    run(["/usr/local/bin/nova-alert", level, title, detail])

def changelog(msg):
    run(["/usr/local/bin/nova-log", "app", msg])

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
            "store": os.path.exists(f"/opt/{lab['com.docker.compose.project']}/.nova-store")}

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

if verb == "backup-now" and not args:
    rc, so, _ = run(["systemctl", "is-active", "nova-backup.service"])
    if so.strip() in ("active", "activating"): out({"ok": True, "note": "A backup is already running"})
    rc, _, se = run(["systemctl", "start", "--no-block", "nova-backup.service"])
    out({"ok": rc == 0, "error": se.strip()[:200]}, 0 if rc == 0 else 1)

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
    with open("/proc/sys/vm/drop_caches", "w") as f: f.write("1\n")
    out({"ok": True, "freed_mb": max(0, (mem("MemFree") - before) // 1024), "available_mb": mem("MemAvailable") // 1024})

if verb == "notify-paired" and len(args) == 1:
    name = "".join(ch for ch in args[0] if ch.isalnum() or ch in " -_")[:40] or "device"
    notify("warning", f"New device paired with the Nova app: {name}",
           f"If this wasn't you, run: sudo nova-api revoke '{name}'")
    changelog(f"New device paired: {name}")
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

if verb == "store-install" and len(args) == 1:
    cat = catalog(); t = cat.get(args[0]) or fail("not in the catalog", 2)
    d = f"/opt/{t['id']}"
    if os.path.exists(f"{d}/.nova-store"): fail("already installed")
    if os.path.exists(f"{d}/docker-compose.yml"): fail(f"{d} already has a compose file that isn't from the store")
    for p in [t["port"]] + t.get("extra_ports", []):
        if not port_free(p): fail(f"port {p} is already in use")
    os.makedirs(f"{d}/data", exist_ok=True)
    alphabet = string.ascii_letters + string.digits
    subs = {"DATA": f"{d}/data", "PORT": str(t["port"])}
    for i in range(1, 4): subs[f"SECRET{i}"] = "".join(secrets.choice(alphabet) for _ in range(32))
    compose = t["compose"]
    for k, v in subs.items(): compose = compose.replace("{" + k + "}", v)
    with open(f"{d}/docker-compose.yml", "w") as f: f.write(compose)
    if "SECRET1" in t["compose"]:
        with open(f"{d}/.nova-secrets", "w") as f:
            f.write("".join(f"{k}={subs[k]}\n" for k in ("SECRET1", "SECRET2", "SECRET3") if "{" + k + "}" in t["compose"]))
        os.chmod(f"{d}/.nova-secrets", 0o600)
    rc, so, se = run(["docker", "compose", "up", "-d"], cwd=d, timeout=1800)
    if rc != 0:
        run(["docker", "compose", "down"], cwd=d, timeout=300)
        os.remove(f"{d}/docker-compose.yml")
        fail("install failed: " + se.strip()[-300:])
    with open(f"{d}/.nova-store", "w") as f: json.dump({"id": t["id"], "installed": time.strftime("%F %T")}, f)
    changelog(f"Installed {t['name']} from the app store → /opt/{t['id']} (port {t['port']})")
    host = SITE.get("lan_host") or "localhost"
    notify("info", f"Installed {t['name']}", f"http://{host}:{t['port']}{t.get('path', '')}")
    out({"ok": True, "url": f"http://{host}:{t['port']}{t.get('path', '')}"})

if verb == "store-uninstall" and len(args) == 1:
    t = catalog().get(args[0]) or fail("not in the catalog", 2)
    d = f"/opt/{t['id']}"
    if not os.path.exists(f"{d}/.nova-store"): fail("not installed from the store")
    rc, so, se = run(["docker", "compose", "down"], cwd=d, timeout=600)
    if rc != 0: fail("couldn't stop it: " + se.strip()[-200:])
    # Keep the data (renamed) so an uninstall is never destructive; compose file removed.
    keep = f"/opt/.nova-uninstalled/{t['id']}-{time.strftime('%Y%m%d-%H%M%S')}"
    os.makedirs(os.path.dirname(keep), exist_ok=True); shutil.move(d, keep)
    changelog(f"Uninstalled {t['name']} (data kept in {keep})")
    out({"ok": True, "kept": keep})

if verb == "programs" and not args:
    rc, so, _ = run(["dpkg-query", "-W", "-f", "${Package} ${db:Status-Abbrev}\n"])
    inst = {l.split()[0] for l in so.splitlines() if len(l.split()) > 1 and l.split()[1].startswith("ii")}
    out({"items": [p | {"installed": p["pkg"] in inst} for p in programs().values()]})

if verb in ("program-install", "program-remove") and len(args) == 1:
    p = programs().get(args[0]) or fail("not in the catalog", 2)
    env = {**os.environ, "DEBIAN_FRONTEND": "noninteractive"}
    if verb == "program-install":
        run(["apt-get", "update", "-qq"], timeout=300, env=env)
        rc, so, se = run(["apt-get", "install", "-y", "-qq", p["pkg"]], timeout=1800, env=env)
    else:
        if p.get("protected"): fail("this one is used by the server and can't be removed from the app")
        rc, so, se = run(["apt-get", "remove", "-y", "-qq", p["pkg"]], timeout=900, env=env)
    if rc == 0: changelog(f"{'Installed' if verb == 'program-install' else 'Removed'} program {p['pkg']} (from the Nova app)")
    out({"ok": rc == 0, "error": se.strip()[-300:] if rc else ""}, 0 if rc == 0 else 1)

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
    rc, so, se = run(["systemd-run", "--on-active=5", "--unit=nova-app-power", "systemctl", args[0]])
    out({"ok": rc == 0, "in_seconds": 5, "error": se.strip()[:200]}, 0 if rc == 0 else 1)

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

fail("unknown verb", 2)
