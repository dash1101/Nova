"""
machost — Nova on a Mac: everything the Linux code reads from /proc, systemd, lsblk or apt, done the macOS way.

The same three groups as winhost.py, all reached through plat.py or helper.py (nothing else imports this file):

  1. live numbers for the status page (the same function names server.py uses on Linux)
  2. what the machine is: os_name(), cpu_model(), board(), look(), meminfo(), uptime_s()
  3. the privileged verbs (drives, processes, power, keep awake…): verb() is called by helper.py, which runs as root

Needs psutil (the Mac installer puts it next to cryptography). macOS's own tools do the rest — diskutil, launchctl,
caffeinate, shutdown — always as a fixed argument list: nothing from the app is ever put into a command line unchecked.
"""
import json, os, platform, plistlib, re, shutil, subprocess, sys, time

import plat

try: import psutil
except ImportError: psutil = None              # only the installer's own checks run without it

LABEL = "app.novalabs.nova"                    # launchd: app.novalabs.nova.api / .helper / .alerts (in /Library/LaunchDaemons)
PARTS = ("api", "helper", "alerts")
TOOL_PATH = "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"      # launchd gives daemons a bare PATH


# ── running things ────────────────────────────────────────────────────────────────
def run(cmd, timeout=120, input=None):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=timeout, input=input)
        return r.returncode, r.stdout, r.stderr
    except FileNotFoundError: return 127, "", f"{cmd[0]} isn't installed"
    except subprocess.TimeoutExpired: return 124, "", "it took too long"

def plist(cmd, timeout=60):
    """A command that prints a property list (diskutil -plist, system_profiler -xml) → what's in it, or {}."""
    try:
        r = subprocess.run(cmd, capture_output=True, timeout=timeout)
        return plistlib.loads(r.stdout) if r.returncode == 0 and r.stdout else {}
    except (OSError, subprocess.SubprocessError, ValueError, plistlib.InvalidFileException): return {}

def sysctl(name):
    return run(["/usr/sbin/sysctl", "-n", name], 10)[1].strip()


# ── 1. live numbers (same names and shapes as the Linux functions in server.py) ─────────
def _hwmon(name, label=None): return None       # a Mac's sensors aren't readable without Apple's private interfaces
def _cpu_temp(): return None
def _gpu(): return None

def _cpu_times():
    t = psutil.cpu_times()
    return int(sum(t) * 1000), int(t.idle * 1000)                      # total, idle (ms)

def _core_times():
    return [(int(sum(t) * 1000), int(t.idle * 1000)) for t in psutil.cpu_times(percpu=True)]

def _default_iface(): return ""

def _net(dev=None):
    """Bytes in and out over the real network ports (en0, en1…) — not loopback, VPN tunnels or AirDrop's link."""
    rx = tx = 0
    for name, c in psutil.net_io_counters(pernic=True).items():
        if name.startswith("en"): rx += c.bytes_recv; tx += c.bytes_sent
    return rx, tx

def _disks():
    """{disk0: (sectors read + written, ms spent on I/O)} — the same shape as /proc/diskstats gives on Linux."""
    out = {}
    try:
        for name, c in (psutil.disk_io_counters(perdisk=True) or {}).items():
            out[name] = ((c.read_bytes + c.write_bytes) // 512, int(getattr(c, "read_time", 0) + getattr(c, "write_time", 0)))
    except Exception: pass
    return out


# ── 2. what the machine is ────────────────────────────────────────────────────────────
NAMES = {"26": "Tahoe", "15": "Sequoia", "14": "Sonoma", "13": "Ventura", "12": "Monterey", "11": "Big Sur"}
def os_name():
    v = platform.mac_ver()[0]
    name = NAMES.get(v.split(".")[0], "")
    return f"macOS {name} {v}".replace("  ", " ").strip() if v else "macOS"

_facts = {}
def _hardware():
    """Apple's own description of this Mac (model name and identifier), asked once."""
    if "hw" not in _facts:
        items = plist(["/usr/sbin/system_profiler", "-xml", "SPHardwareDataType"], 30)
        try: _facts["hw"] = items[0]["_items"][0]
        except (LookupError, TypeError): _facts["hw"] = {}
    return _facts["hw"]

def cpu_model():
    if "cpu" not in _facts: _facts["cpu"] = sysctl("machdep.cpu.brand_string") or str(_hardware().get("chip_type", ""))
    return _facts["cpu"]

def board():
    """"Apple Mac mini (Macmini9,1)" — what a Mac has instead of a motherboard name."""
    hw = _hardware(); name = str(hw.get("machine_name", "")).strip(); ident = str(hw.get("machine_model", "") or sysctl("hw.model")).strip()
    what = f"{name} ({ident})" if name and ident else name or ident
    return what if what.startswith("Apple") else ("Apple " + what).strip()

def look():
    """Which picture the Home page shows until someone picks: a laptop for a MacBook, a mini PC for a Mac mini or Studio, else a tower."""
    name = (str(_hardware().get("machine_name", "")) + " " + sysctl("hw.model")).lower()
    if "macbook" in name: return "laptop"
    if "mini" in name or "studio" in name: return "mini"
    return "tower"

def meminfo():
    vm, sw = psutil.virtual_memory(), psutil.swap_memory()
    return {"MemTotal": vm.total // 1024, "MemAvailable": vm.available // 1024, "SwapTotal": sw.total // 1024, "SwapFree": sw.free // 1024}

def uptime_s(): return int(time.time() - psutil.boot_time())

def docker_sock():
    """Docker's socket: the usual place, or where Docker Desktop, OrbStack or Colima keep theirs in someone's home folder."""
    import glob
    for p in ["/var/run/docker.sock"] + sorted(glob.glob("/Users/*/.docker/run/docker.sock") + glob.glob("/Users/*/.orbstack/run/docker.sock")
                                              + glob.glob("/Users/*/.colima/default/docker.sock")):
        if os.path.exists(p): return p
    return ""

def docker_running(): return bool(docker_sock())

def service_state(part):
    """One of Nova's three background services: "active" while it runs, "inactive" when it's loaded but stopped, "missing"."""
    if part not in PARTS: return "missing"
    rc, so, _ = run(["/bin/launchctl", "print", f"system/{LABEL}.{part}"], 20)
    if rc != 0: return "missing"
    return "active" if re.search(r"^\s*state = running", so, re.M) else "inactive"

def is_admin(): return os.geteuid() == 0

def service(action):
    """Start, stop or restart Nova (the buttons in the Nova Server window) → (rc, what went wrong).
    Stopped, the API stays off until Start or the next boot; the helper and the monitor keep running."""
    def up(part, restart=False):
        if service_state(part) == "missing":
            return run(["/bin/launchctl", "bootstrap", "system", f"/Library/LaunchDaemons/{LABEL}.{part}.plist"], 30)
        return run(["/bin/launchctl", "kickstart"] + (["-k"] if restart else []) + [f"system/{LABEL}.{part}"], 30)
    if action == "stop": rc, so, se = run(["/bin/launchctl", "bootout", f"system/{LABEL}.api"], 30)
    elif action == "restart": rc, so, se = up("api", restart=True)
    else:
        rc, se = 0, ""
        for part in ("helper", "api", "alerts"):
            r = up(part)
            if r[0] != 0: rc, se = r[0], r[2]
    return rc, se

def console_user():
    """Whoever is signed in at the Mac's screen ("" when nobody is)."""
    try:
        import pwd
        u = pwd.getpwuid(os.stat("/dev/console").st_uid).pw_name
        return "" if u == "root" else u
    except (OSError, KeyError): return ""

def doctor():
    """`nova doctor` on a Mac: what's wrong, each with the fix in plain words — [{"level": ok|warn|stop, "title", "fix"}]."""
    import socket, autosetup
    out = []
    add = lambda lv, t, fix="": out.append({"level": lv, "title": t, "fix": fix})
    try: cfg = json.load(open(plat.p("/etc/nova-api/config.json")))
    except (OSError, ValueError): cfg = None
    if cfg is None:
        add("stop", "Nova isn't set up yet", "Run the Nova installer again — it writes the settings."); return out
    states = {t: service_state(t) for t in PARTS}
    if states["api"] == "active": add("ok", "Nova is running")
    else: add("stop", "Nova isn't running" if states["api"] != "missing" else "Nova's background service is missing",
              "sudo nova gui → Start (or restart the Mac)" if states["api"] != "missing" else "Run the Nova installer again")
    if states["helper"] != "active": add("stop", "Nova's helper (drives, power, processes) isn't running", "sudo nova gui → Restart")
    if states["alerts"] != "active": add("warn", "The monitor behind the Inbox isn't running", "sudo nova gui → Restart")
    host, port = cfg.get("lan_host", ""), int(cfg.get("tls_port", 8495))
    if states["api"] == "active" and host:
        try:
            socket.create_connection((host, port), timeout=3).close(); add("ok", f"Listening for phones and browsers on port {port}")
        except OSError: add("stop", f"Nothing is listening on port {port}", f"See {os.path.join(plat.p('/var/log/nova-api'), 'api.log')} for why")
    if host and psutil:
        mine = {a.address for addrs in psutil.net_if_addrs().values() for a in addrs}
        if host in mine: add("ok", f"Home address {host} is this Mac's")
        else: add("stop", f"Phones look for this server at {host}, but that's no longer its address", "sudo nova gui → Where to find it → Look again")
        th = cfg.get("tailnet_host", "")
        if th and th != host:
            now = autosetup.tailnet()[0]
            if th in mine:
                try: socket.create_connection((th, port), timeout=3).close(); add("ok", f"Tailscale address {th} is this Mac's, and Nova answers there")
                except OSError: add("warn", f"Nova isn't answering on its Tailscale address {th} yet", "it picks it up within half a minute; if not: sudo nova gui → Restart")
            elif now: add("warn", f"Tailscale's address changed: Nova uses {th}, this Mac now has {now}", "sudo nova gui → Where to find it → Look again")
            else: add("warn", f"Tailscale isn't connected on this Mac (its address {th} isn't here), so phones away from home can't reach Nova", "Open Tailscale and connect")
    rc, so, _ = run(["/usr/libexec/ApplicationFirewall/socketfilterfw", "--getglobalstate"], 15)
    if rc == 0 and "enabled" in so.lower():
        add("warn", "The Mac's firewall is on — it may stop phones from reaching Nova",
            "If the app can't connect: System Settings → Network → Firewall → Options → allow incoming connections for Python (Nova)")
    try: n = len(json.load(open(plat.p("/var/lib/nova-api/devices.json"))))
    except (OSError, ValueError): n = 0
    add("ok", f"{n} paired device{'s' if n != 1 else ''}") if n else add("warn", "No devices are paired yet", "sudo nova add   (or Pair a phone in the Nova Server window)")
    try:
        ends = [t for t in json.load(open(plat.p("/var/lib/nova-alerts/state.json"))).get("counters", {}).get("bad_ends", []) if time.time() - t < 7 * 86400]
        if ends: add("warn", f"The Mac went off without shutting down {len(ends)} time{'s' if len(ends) > 1 else ''} in the last 7 days", "the Inbox says why each time (a crash, a power cut…)")
    except (OSError, ValueError): pass
    if not docker_running(): add("ok", "Docker isn't running (Nova works without it; the Containers page stays empty)")
    return out


# ── 3. the privileged verbs ──────────────────────────────────────────────────────────
# Shared with Linux (helper.py's own code runs): everything Docker, the terminal and the file manager (as your own
# account), the phone's SSH key, notification settings, Discord, dismissing alerts. Listed so a verb that hasn't been
# looked at on a Mac says so instead of failing oddly.
SHARED = {"containers", "containers-stats", "container", "container-info", "container-logs", "container-diagnose", "container-policy",
          "shell", "host-shell", "files", "files-data", "files-get", "ssh-authorize", "ssh-unauthorize", "ssh-key-status",
          "notify-paired", "notify-ssh-key", "notify-revoked", "get-notify", "set-notify", "discord-status", "discord-set", "discord-test",
          "alerts-dismiss", "alerts-undismiss", "events-delete", "events-restore", "recommendations-snooze"}

LIB = os.path.dirname(os.path.realpath(__file__))
MONITOR = next((p for p in (os.path.join(LIB, "monitor", "nova_alerts.py"), os.path.join(LIB, "..", "monitor", "nova_alerts.py")) if os.path.exists(p)), None)

def notify(level, title, detail=""):
    """Queue an alert (Inbox, phones, Discord) through the monitor's spool."""
    if MONITOR:
        try: run([sys.executable, MONITOR, "notify", level, title, detail], 20)
        except Exception: pass

def changelog(msg):
    try:
        os.makedirs(plat.p("/var/log/nova-api"), exist_ok=True)
        with open(os.path.join(plat.p("/var/log/nova-api"), "changes.log"), "a", encoding="utf-8") as f: f.write(time.strftime("%Y-%m-%d %H:%M ") + msg + "\n")
    except OSError: pass

def _detached(cmd):
    """Start something that outlives this helper call (a delayed restart, caffeinate)."""
    return subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True, start_new_session=True, cwd="/")

def drives():
    """Physical drives with their volumes, space and health — the same fields the Linux `drives` verb returns."""
    everything = plist(["/usr/sbin/diskutil", "list", "-plist"])
    whole = plist(["/usr/sbin/diskutil", "list", "-plist", "physical"]).get("WholeDisks") or []
    stores = {}                                             # physical partition (disk0s2) → where its APFS volumes are mounted
    for d in everything.get("AllDisksAndPartitions") or []:
        mounts = [v.get("MountPoint") for v in d.get("APFSVolumes") or [] if v.get("MountPoint")]
        for s in d.get("APFSPhysicalStores") or []: stores.setdefault(str(s.get("DeviceIdentifier", "")), []).extend(mounts)
    res = []
    for name in whole:
        if not re.fullmatch(r"disk\d+", str(name)): continue
        info = plist(["/usr/sbin/diskutil", "info", "-plist", name])
        if not info: continue
        entry = next((d for d in everything.get("AllDisksAndPartitions") or [] if d.get("DeviceIdentifier") == name), {})
        mounts = []
        for p in entry.get("Partitions") or []:
            if p.get("MountPoint"): mounts.append(p["MountPoint"])
            mounts += stores.get(str(p.get("DeviceIdentifier", "")), [])
        boot = "/" in mounts
        # one line per place a person would recognize: the Mac's own volume as "/", then anything under /Volumes
        shown = (["/"] if boot else []) + sorted(m for m in mounts if m.startswith("/Volumes/"))
        usage = []
        for m in shown:
            try:
                du = shutil.disk_usage(m); usage.append({"mount": m, "total": du.total, "used": du.total - du.free, "free": du.free})
            except OSError: pass
        bus = str(info.get("BusProtocol") or "").lower(); smart = str(info.get("SMARTStatus") or "")
        passed = True if smart == "Verified" else False if smart == "Failing" else None
        res.append({"name": name, "serial": re.sub(r"[^A-Za-z0-9_.-]", "", str(info.get("DiskUUID") or name))[:64] or name,
                    "model": str(info.get("MediaName") or info.get("IORegistryEntryName") or "").strip(), "size": int(info.get("TotalSize") or info.get("Size") or 0),
                    "bus": "nvme" if "pci" in bus or "fabric" in bus or "nvme" in bus else "usb" if "usb" in bus else "sata" if "sata" in bus else bus,
                    "ssd": bool(info.get("SolidState")), "role": "Boot drive" if boot else "Not in use" if not shown else "Other", "mounts": shown, "usage": usage,
                    "protected": bool(boot), "temp": None, "smart_passed": passed, "realloc": None, "pending": None, "uncorrect": None, "life": None, "hours": None,
                    "health": 100 if passed else 20 if passed is False else None, "worn": False, "crc": None, "sector": int(info.get("DeviceBlockSize") or 512),
                    "status": smart, "removable": bool(info.get("RemovableMedia") or info.get("Ejectable") or not info.get("Internal", True))})
    return res

SYSTEM_PROCS = {"kernel_task", "launchd", "windowserver", "loginwindow", "logd", "configd", "opendirectoryd", "securityd", "sshd", "diskarbitrationd",
                "coreservicesd", "powerd", "notifyd", "syslogd", "mds", "mds_stores", "com.docker.backend", "tailscaled"}

def proc_list():
    me = {os.getpid(), os.getppid()}
    procs = list(psutil.process_iter())
    for p in procs:
        try: p.cpu_percent(None)
        except psutil.Error: pass
    time.sleep(.5); now = time.time(); out = []
    for p in procs:
        if p.pid in me: continue
        try:
            with p.oneshot():
                name = p.name(); cpu = p.cpu_percent(None) if p.pid else 0.0      # (pid 0 is the kernel itself: kernel_task)
                try: user = p.username() or ""
                except psutil.Error: user = "root"
                try: cmd = " ".join(p.cmdline())
                except psutil.Error: cmd = ""
                try: mem = p.memory_info().rss
                except psutil.Error: mem = 0
                try: threads = p.num_threads()
                except psutil.Error: threads = 0
                out.append({"pid": p.pid, "ppid": p.ppid(), "user": user, "cpu": round(cpu, 1), "mem": mem, "threads": threads,
                            "up_s": int(max(0, now - p.create_time())), "state": {"running": "R", "stopped": "T", "zombie": "Z"}.get(p.status(), "S"),
                            "name": name[:60], "cmd": (cmd or name)[:400], "kernel": p.pid == 0 or name == "kernel_task"})
        except psutil.Error: continue
    return out

def proc_signal(pid, how, fail):
    try: p = psutil.Process(pid); name = p.name()
    except psutil.Error: fail("that process isn't running anymore")
    if pid <= 1 or pid in (os.getpid(), os.getppid()): fail("that one keeps the system running — it can't be stopped from here")
    if name.lower() in SYSTEM_PROCS: fail(f"{name} keeps macOS (or your way in) working — stop it on the Mac itself if you're sure")
    try:
        if any(LIB in (a or "") for a in p.cmdline()): fail("that's part of Nova — use Power → Restart Nova instead")
    except psutil.Error: pass
    try: p.kill() if how == "kill" else p.terminate()
    except psutil.NoSuchProcess: fail("that process isn't running anymore")
    except psutil.AccessDenied: fail("not allowed to stop that process")
    changelog(f"Process {pid} ({name}) {'force-stopped' if how == 'kill' else 'ended'} from the Nova app (Task manager)")
    return {"ok": True, "pid": pid, "name": name}

# scheduled restarts / shutdowns: a list in Nova's data folder; the helper service (helper-mac.py, always running as root)
# looks at it twice a minute and does what's due — so a schedule survives a restart before its time, like the Linux timers
SCHED_FILE = plat.p("/var/lib/nova-api/power-sched.json")
def scheds():
    try: s = json.load(open(SCHED_FILE))
    except Exception: s = []
    return [x for x in s if isinstance(x, dict) and x.get("at", 0) > time.time() - 120 and x.get("action") in ("reboot", "poweroff")]
def save_scheds(s):
    os.makedirs(os.path.dirname(SCHED_FILE), exist_ok=True)
    with open(SCHED_FILE + ".tmp", "w") as f: json.dump(s, f)
    os.replace(SCHED_FILE + ".tmp", SCHED_FILE)

def power_schedule(action, at, fail):
    if not time.time() + 30 <= at <= time.time() + 367 * 86400: fail("pick a time from a minute from now to a year from now")
    s = scheds()
    if len(s) >= 10: fail("there are already 10 scheduled — cancel one first")
    sid = os.urandom(4).hex()
    save_scheds(s + [{"id": sid, "at": at, "action": action}])
    changelog(f"Server {'restart' if action == 'reboot' else 'shutdown'} scheduled for {time.strftime('%Y-%m-%d %H:%M', time.localtime(at))} from the Nova app")
    return {"ok": True, "id": sid, "at": at, "action": action}

def power_unschedule(sid, fail):
    s = scheds()
    if not any(x.get("id") == sid for x in s): fail("no such schedule")
    save_scheds([x for x in s if x.get("id") != sid]); changelog(f"Scheduled restart/shutdown {sid} cancelled from the Nova app")
    return {"ok": True}

def power_now(action):
    """Restart or shut the Mac down in a few seconds (so the answer gets back to the app first)."""
    _detached(["/bin/sh", "-c", f"sleep 6; exec /sbin/shutdown {'-r' if action == 'reboot' else '-h'} now"])

# keep awake: macOS's own caffeinate, holding the Mac (and its disks) awake until its time is up
KEEP_STATE = plat.p("/run/nova/keep-awake.json")
def _keep_proc():
    try: st = json.load(open(KEEP_STATE))
    except (OSError, ValueError): return None, {}
    try:
        p = psutil.Process(int(st.get("pid", 0)))
        if p.name() == "caffeinate": return p, st
    except (psutil.Error, ValueError): pass
    return None, st

def keep_awake_status():
    p, st = _keep_proc()
    return {"on": bool(p), "until": st.get("until") if p else None, "forever": bool(p and not st.get("until")), "supported": True}

def keep_awake(arg, fail):
    p, _ = _keep_proc()
    if p:
        try: p.kill()
        except psutil.Error: pass
    try: os.remove(KEEP_STATE)
    except OSError: pass
    if arg == "0": changelog("Keep awake turned off (from the Nova app)"); return {"ok": True, **keep_awake_status()}
    secs = None if arg == "forever" else min(int(arg), 7 * 86400)
    until = int(time.time() + secs) if secs else None
    q = _detached(["/usr/bin/caffeinate", "-ims"] + (["-t", str(secs)] if secs else []))     # -i idle, -m disks, -s the whole system (on power)
    os.makedirs(os.path.dirname(KEEP_STATE), exist_ok=True)
    with open(KEEP_STATE, "w") as f: json.dump({"pid": q.pid, "until": until}, f)
    changelog("Kept awake from the Nova app" + (f" until {time.strftime('%a %H:%M', time.localtime(until))}" if until else ""))
    time.sleep(.3)
    return {"ok": True, **keep_awake_status()}

def which(tool):
    return shutil.which(tool, path=TOOL_PATH)

def tools_status():
    have = {"docker": bool(which("docker") or docker_running()), "brew": bool(which("brew")),
            "tailscale": bool(which("tailscale") or os.path.exists("/Applications/Tailscale.app")), "cloudflared": bool(which("cloudflared"))}
    return {"tools": have, "missing": []}

def list_dirs(path, fail):
    """Folder picker: people's home folders and connected drives at the top, then the folders inside one."""
    if path in ("", "/"):
        import glob
        tops = sorted(p for p in glob.glob("/Users/*") if os.path.isdir(p) and os.path.basename(p) not in ("Shared", "Guest") and not os.path.basename(p).startswith("."))
        tops += sorted(p for p in glob.glob("/Volumes/*") if os.path.isdir(p) and not os.path.islink(p))
        return {"path": "/", "parent": None, "dirs": [{"name": p, "path": p} for p in tops]}
    path = os.path.realpath(path)
    if not os.path.isdir(path) or any(path == x or path.startswith(x + "/") for x in ("/dev", "/private/var/db", "/System")): fail("no such folder")
    dirs = []
    try:
        for e in sorted(os.scandir(path), key=lambda e: e.name.lower()):
            try:
                if e.is_dir(follow_symlinks=False) and not e.name.startswith("."): dirs.append({"name": e.name, "path": e.path})
            except OSError: pass
    except PermissionError: fail("macOS doesn't let Nova look in that folder (System Settings → Privacy & Security → Full Disk Access)")
    parent = os.path.dirname(path.rstrip("/"))
    return {"path": path, "parent": "/" if parent in ("", "/", "/Users", "/Volumes") else parent, "dirs": dirs[:500]}

def verb(verb, args, h):
    """Called by helper.py on a Mac before its own verbs. Answers the Mac-only ones (and exits), returns for the
    shared ones, and refuses the rest plainly."""
    h.notify, h.changelog = notify, changelog                         # the shared verbs alert and log the Mac way
    os.environ["PATH"] = TOOL_PATH                                    # docker, tailscale… where Homebrew and the apps put them
    sock = docker_sock()
    if sock and sock != "/var/run/docker.sock": os.environ.setdefault("DOCKER_HOST", "unix://" + sock)
    out, fail = h.out, h.fail
    if verb == "drives" and not args:
        out({"drives": drives(), "unmounted": [], "fans": {"supported": False, "note": "Fan control isn't available on a Mac."}})
    if verb == "proc-list" and not args: out({"processes": proc_list()})
    if verb == "proc-signal" and len(args) == 2 and args[0].isdigit() and args[1] in ("term", "kill"): out(proc_signal(int(args[0]), args[1], fail))
    if verb == "power" and len(args) == 1 and args[0] in ("reboot", "poweroff"):
        changelog(f"Server {args[0]} requested from the Nova app")
        notify("warning", f"Server {'restarting' if args[0] == 'reboot' else 'shutting down'} (from the Nova app)", "")
        if MONITOR: run([sys.executable, MONITOR], 40)               # send it now: after the restart is too late
        power_now(args[0]); out({"ok": True, "in_seconds": 6, "error": ""})
    if verb == "power" and args in (["nova-restart"], ["nova-stop"]):
        stop = args[0] == "nova-stop"
        changelog(f"Nova {'stopped' if stop else 'restarted'} from the Nova app")
        if stop: notify("warning", "Nova was stopped (from the Nova app)", "The Mac keeps running. Start Nova again with: sudo nova gui → Start (or restart the Mac).")
        job = f"system/{LABEL}.api"
        _detached(["/bin/sh", "-c", f"sleep 2; /bin/launchctl {'bootout ' + job if stop else 'kickstart -k ' + job}"])
        out({"ok": True, "in_seconds": 2, "error": ""})
    if verb == "power-schedule-list" and not args: out({"schedules": scheds()})
    if verb == "power-schedule" and len(args) == 2 and args[0] in ("reboot", "poweroff") and args[1].isdigit(): out(power_schedule(args[0], int(args[1]), fail))
    if verb == "power-unschedule" and len(args) == 1 and re.fullmatch(r"[0-9a-f]{8}", args[0]): out(power_unschedule(args[0], fail))
    if verb == "tools-status" and not args: out(tools_status())
    if verb == "keep-awake-status" and not args: out(keep_awake_status())
    if verb == "keep-awake" and len(args) == 1 and (args[0].isdigit() or args[0] == "forever"): out(keep_awake(args[0], fail))
    if verb == "list-dirs" and len(args) == 1: out(list_dirs(args[0], fail))
    if verb == "backup-status" and not args: out({"none": True})
    if verb == "task-list" and not args: out({"tasks": []})            # (background tasks — installs, updates — aren't on a Mac yet)
    if verb == "recommendations" and not args: out({"recommendations": [], "snoozed": 0, "t": time.time()})
    if verb == "update-status" and not args:
        try: v = open(os.path.join(LIB, "VERSION")).read().strip()
        except OSError: v = ""
        out({"installed": v, "source": "", "running": False})
    if verb == "update-check" and not args: out({"ok": False, "message": "Updating from the app isn't available on a Mac yet — on the Mac: sudo nova update"}, 1)
    if verb in ("power-schedule", "proc-signal", "power", "keep-awake"): fail("bad request", 2)
    if verb not in SHARED: fail("That isn't available on a Mac server yet.", 3)
