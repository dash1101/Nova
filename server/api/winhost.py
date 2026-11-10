"""
winhost — Nova on Windows: everything the Linux code reads from /proc, systemd, lsblk or apt, done the Windows way.

Three groups, all reached through plat.py or helper.py (nothing else imports this file):

  1. live numbers for the status page (the same function names server.py uses on Linux)
  2. what the machine is: os_name(), cpu_model(), board(), meminfo(), uptime_s(), loadavg()
  3. the privileged verbs (drives, processes, power, programs…): verb() is called by helper.py, which runs as SYSTEM

Needs psutil (installed by the Windows installer next to cryptography). PowerShell is used for what psutil doesn't know
(drive health, scheduled tasks); every call is a fixed script — nothing from the app is ever put into a command line
unchecked.
"""
import json, os, re, shutil, subprocess, sys, threading, time

import plat

try: import psutil
except ImportError: psutil = None              # only the installer's own checks run without it

NO_WINDOW = 0x08000000                         # CREATE_NO_WINDOW: no console flashing up for each command


# ── running things ────────────────────────────────────────────────────────────────
def run(cmd, timeout=120, input=None):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout, input=input,
                           creationflags=NO_WINDOW if plat.WINDOWS else 0)
        return r.returncode, r.stdout, r.stderr
    except FileNotFoundError: return 127, "", f"{cmd[0]} isn't installed"
    except subprocess.TimeoutExpired: return 124, "", "it took too long"

def ps(script, timeout=60):
    """Run a fixed PowerShell script → (rc, stdout, stderr). Output is UTF-8 whatever the console's code page."""
    pre = "$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue';[Console]::OutputEncoding=[Text.Encoding]::UTF8;"
    return run(["powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", pre + script], timeout)

def ps_json(script, timeout=60):
    """A PowerShell pipeline → a list of dicts (one object, many or none)."""
    rc, so, se = ps(f"@({script}) | ConvertTo-Json -Depth 4 -Compress", timeout)
    if rc != 0 or not so.strip(): return []
    try: v = json.loads(so)
    except ValueError: return []
    return v if isinstance(v, list) else [v]

def psq(s):
    """A value inside a PowerShell single-quoted string."""
    return "'" + str(s).replace("'", "''") + "'"


# ── 1. live numbers (same names and shapes as the Linux functions in server.py) ─────────
def _hwmon(name, label=None): return None       # Windows has no general way to read sensor chips without a vendor driver
def _cpu_temp(): return None

def _cpu_times():
    t = psutil.cpu_times()
    return int(sum(t) * 1000), int(t.idle * 1000)                      # total, idle (ms)

def _core_times():
    return [(int(sum(t) * 1000), int(t.idle * 1000)) for t in psutil.cpu_times(percpu=True)]

def _default_iface(): return ""

_SKIP_NIC = re.compile(r"loopback|vethernet|isatap|teredo|bluetooth|pseudo", re.I)
def _net(dev=None):
    rx = tx = 0
    for name, c in psutil.net_io_counters(pernic=True).items():
        if not _SKIP_NIC.search(name): rx += c.bytes_recv; tx += c.bytes_sent
    return rx, tx

def _disks():
    """{PhysicalDrive0: (sectors read + written, ms spent on I/O)} — the same shape as /proc/diskstats gives on Linux."""
    out = {}
    try:
        for name, c in (psutil.disk_io_counters(perdisk=True) or {}).items():
            out[name] = ((c.read_bytes + c.write_bytes) // 512, int(getattr(c, "read_time", 0) + getattr(c, "write_time", 0)))
    except Exception: pass
    return out

_GPU = {"v": None, "started": False}
def _gpu_loop():
    """Graphics busy %: nvidia-smi when there is one, else Windows' own counter (the number Task Manager shows)."""
    if shutil.which("nvidia-smi"):
        while True:
            rc, so, _ = run(["nvidia-smi", "--query-gpu=utilization.gpu", "--format=csv,noheader,nounits"], 8)
            vals = [float(x) for x in so.split() if x.replace(".", "", 1).isdigit()]
            _GPU["v"] = round(sum(vals) / len(vals), 1) if vals else None
            time.sleep(3)
    # (it stops by itself when Nova does, and doesn't sit in Nova's folder — so an update or uninstall is never blocked by it)
    script = ("$ErrorActionPreference='SilentlyContinue';while(Get-Process -Id " + str(os.getpid()) + "){$s=(Get-Counter '\\GPU Engine(*engtype_3D)\\Utilization Percentage').CounterSamples;"
              "if($s){[Console]::Out.WriteLine([math]::Round(($s|Measure-Object CookedValue -Sum).Sum,1))}else{[Console]::Out.WriteLine('none')};Start-Sleep -Seconds 4}")
    try:
        p = subprocess.Popen(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             stdin=subprocess.DEVNULL, text=True, creationflags=NO_WINDOW if plat.WINDOWS else 0, cwd=os.environ.get("SystemRoot") or None)
        misses = 0
        for line in p.stdout:
            try: _GPU["v"] = min(100.0, float(line.strip().replace(",", "."))); misses = 0
            except ValueError:
                _GPU["v"] = None; misses += 1
                if misses >= 3: p.kill(); return               # this machine has no graphics counter: stop asking
    except Exception: _GPU["v"] = None

def _gpu():
    if not _GPU["started"]:
        _GPU["started"] = True; threading.Thread(target=_gpu_loop, daemon=True).start()
    return _GPU["v"]


# ── 2. what the machine is ────────────────────────────────────────────────────────────
def _reg(path, name, default=""):
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path) as k: return str(winreg.QueryValueEx(k, name)[0]).strip()
    except Exception: return default

def os_name():
    name = _reg(r"SOFTWARE\Microsoft\Windows NT\CurrentVersion", "ProductName", "Windows")
    build = _reg(r"SOFTWARE\Microsoft\Windows NT\CurrentVersion", "CurrentBuildNumber", "0")
    if name.startswith("Windows 10") and build.isdigit() and int(build) >= 22000: name = name.replace("Windows 10", "Windows 11", 1)   # the registry still says 10
    return name

def cpu_model(): return _reg(r"HARDWARE\DESCRIPTION\System\CentralProcessor\0", "ProcessorNameString")

def board():
    v, n = _reg(r"HARDWARE\DESCRIPTION\System\BIOS", "BaseBoardManufacturer"), _reg(r"HARDWARE\DESCRIPTION\System\BIOS", "BaseBoardProduct")
    return f"{v.split()[0].title() if v else ''} {n}".strip()

def meminfo():
    vm, sw = psutil.virtual_memory(), psutil.swap_memory()
    return {"MemTotal": vm.total // 1024, "MemAvailable": vm.available // 1024, "SwapTotal": sw.total // 1024, "SwapFree": sw.free // 1024}

def uptime_s(): return int(time.time() - psutil.boot_time())

def loadavg():
    try: return tuple(round(x, 2) for x in psutil.getloadavg())       # psutil keeps a Linux-style average on Windows
    except Exception: return (0.0, 0.0, 0.0)

def docker_running(): return os.path.exists(r"\\.\pipe\docker_engine")

def task_state(name):
    """One of Nova's scheduled tasks (Api, Helper, Alerts): "active" while it runs, "inactive" when it's waiting or stopped, "missing"."""
    if not re.fullmatch(r"[A-Za-z]{1,20}", name): return "missing"
    rc, so, se = ps("$t=Get-ScheduledTask -TaskPath '\\Nova\\' -TaskName " + psq(name) + " -ErrorAction SilentlyContinue; if($t){$t.State.ToString()}else{'Missing'}", 30)
    st = so.strip().lower()
    return "active" if st == "running" else "missing" if st in ("missing", "") else "inactive"

def lan():
    """This machine's home-network address and subnet: (host, "a.b.c.0/24"), from the interface that leads to the internet."""
    import ipaddress, socket
    host = ""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.connect(("192.0.2.1", 9)); host = s.getsockname()[0]; s.close()   # (nothing is sent)
    except OSError: pass
    best = None
    for name, addrs in psutil.net_if_addrs().items():
        for a in addrs:
            if a.family != socket.AF_INET or not a.netmask or a.address.startswith(("127.", "169.254.")): continue
            net = ipaddress.ip_network(f"{a.address}/{a.netmask}", strict=False)
            if a.address == host: return host, str(net)
            if best is None and ipaddress.ip_address(a.address).is_private and not a.address.startswith("100."): best = (a.address, str(net))
    return best or (host or "127.0.0.1", f"{host or '127.0.0.1'}/32")

def tailnet():
    """(host, subnet) when Tailscale is up on this machine, else ("", "")."""
    import socket
    for name, addrs in psutil.net_if_addrs().items():
        for a in addrs:
            if a.family == socket.AF_INET and a.address.startswith("100.") and 64 <= int(a.address.split(".")[1]) <= 127: return a.address, "100.64.0.0/10"
    return "", ""

def doctor():
    """`nova doctor` on Windows: what's wrong, each with the fix in plain words — [{"level": ok|warn|stop, "title", "fix"}]."""
    import socket
    out = []
    add = lambda lv, t, fix="": out.append({"level": lv, "title": t, "fix": fix})
    try: cfg = json.load(open(plat.p("/etc/nova-api/config.json")))
    except (OSError, ValueError): cfg = None
    if cfg is None:
        add("stop", "Nova isn't set up yet", "Run the Nova installer again (install.ps1) — it writes the settings."); return out
    states = {t: task_state(t) for t in ("Api", "Helper", "Alerts")}
    if states["Api"] == "active": add("ok", "Nova is running")
    else: add("stop", "Nova isn't running" if states["Api"] != "missing" else "Nova's background task is missing",
              "Press Start in the Nova Server window (or restart the server)" if states["Api"] != "missing" else "Run the Nova installer again")
    if states["Helper"] != "active": add("stop", "Nova's helper (drives, power, processes) isn't running", "Press Restart in the Nova Server window")
    if states["Alerts"] != "active": add("warn", "The monitor behind the Inbox isn't running", "Press Restart in the Nova Server window")
    host, port = cfg.get("lan_host", ""), int(cfg.get("tls_port", 8495))
    if states["Api"] == "active" and host:
        try:
            socket.create_connection((host, port), timeout=3).close(); add("ok", f"Listening for phones and browsers on port {port}")
        except OSError: add("stop", f"Nothing is listening on port {port}", f"See {os.path.join(plat.p('/var/log/nova-api'), 'api.log')} for why")
    if host and psutil:
        mine = {a.address for addrs in psutil.net_if_addrs().values() for a in addrs}
        if host in mine: add("ok", f"Home address {host} is this machine's")
        else: add("stop", f"Phones look for this server at {host}, but that's no longer its address", "Nova Server window → Where to find it → Look again")
    rc, so, se = ps("(Get-NetFirewallRule -Group 'Nova' -ErrorAction SilentlyContinue | Where-Object Enabled -eq 'True' | Measure-Object).Count", 40)
    if so.strip().isdigit() and int(so.strip()) >= 1: add("ok", "Windows Firewall lets your home network in")
    else: add("warn", "Windows Firewall has no rule for Nova, so phones may not reach it", "Run the Nova installer again, or allow TCP ports 8095 and 8495 from your local subnet")
    try: n = len(json.load(open(plat.p("/var/lib/nova-api/devices.json"))))
    except (OSError, ValueError): n = 0
    add("ok", f"{n} paired device{'s' if n != 1 else ''}") if n else add("warn", "No devices are paired yet", "nova add   (or Pair a phone in the Nova Server window)")
    try:
        ends = [t for t in json.load(open(plat.p("/var/lib/nova-alerts/state.json"))).get("counters", {}).get("bad_ends", []) if time.time() - t < 7 * 86400]
        if ends: add("warn", f"The server went off without shutting down {len(ends)} time{'s' if len(ends) > 1 else ''} in the last 7 days", "the Inbox says why each time (a crash, a power cut…)")
    except (OSError, ValueError): pass
    if not docker_running(): add("ok", "Docker isn't running (Nova works without it; the Containers page stays empty)")
    return out

def is_admin():
    try:
        import ctypes
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception: return False


# ── 3. the privileged verbs ──────────────────────────────────────────────────────────
# Shared with Linux (helper.py's own code runs): everything Docker, notification settings, Discord, dismissing alerts,
# background tasks. Listed so a verb that hasn't been looked at on Windows says so instead of failing oddly.
SHARED = {"containers", "containers-stats", "container", "container-info", "container-logs", "container-diagnose", "container-policy",
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

def _clean_serial(s, fallback):
    s = re.sub(r"[^A-Za-z0-9_.-]", "", str(s or "").strip())[:64]
    return s or fallback

def drives():
    """Physical drives with their letters, space and health — the same fields the Linux `drives` verb returns."""
    disks = ps_json("Get-PhysicalDisk | Select-Object DeviceId,FriendlyName,SerialNumber,MediaType,BusType,Size,HealthStatus,OperationalStatus,SpindleSpeed")
    parts = ps_json("Get-Partition | Where-Object DriveLetter | Select-Object DiskNumber,DriveLetter,IsBoot,IsSystem,Size")
    rel = {}
    for r in ps_json("Get-PhysicalDisk | ForEach-Object { $d=$_; $c=$d | Get-StorageReliabilityCounter -ErrorAction SilentlyContinue; "
                     "if($c){[pscustomobject]@{DeviceId=$d.DeviceId;Temperature=$c.Temperature;Wear=$c.Wear;PowerOnHours=$c.PowerOnHours;"
                     "ReadErrorsUncorrected=$c.ReadErrorsUncorrected;WriteErrorsUncorrected=$c.WriteErrorsUncorrected}} }", 90):
        rel[str(r.get("DeviceId"))] = r
    sysdrive = plat.SYSTEM_DRIVE[:1].upper()
    res = []
    for d in disks:
        num = str(d.get("DeviceId")); name = f"PhysicalDrive{num}"
        mine = [p for p in parts if str(p.get("DiskNumber")) == num and p.get("DriveLetter")]
        mounts, usage = [], []
        for p in mine:
            letter = str(p["DriveLetter"]).strip()[:1].upper()
            if not letter.isalpha(): continue
            m = f"{letter}:\\"; mounts.append(m)
            try:
                du = shutil.disk_usage(m); usage.append({"mount": m, "total": du.total, "used": du.total - du.free, "free": du.free})
            except OSError: pass
        boot = any(str(p.get("DriveLetter", "")).upper().startswith(sysdrive) or p.get("IsBoot") or p.get("IsSystem") for p in mine)
        bus = str(d.get("BusType") or "").lower(); media = str(d.get("MediaType") or "")
        ssd = media == "SSD" or bus == "nvme" or (media != "HDD" and not d.get("SpindleSpeed"))
        r = rel.get(num, {}); healthy = str(d.get("HealthStatus") or "") == "Healthy"
        wear = r.get("Wear"); life = max(0, 100 - int(wear)) if isinstance(wear, (int, float)) else None
        bad = (r.get("ReadErrorsUncorrected") or 0) + (r.get("WriteErrorsUncorrected") or 0)
        temp = r.get("Temperature") if isinstance(r.get("Temperature"), (int, float)) and 0 < r["Temperature"] < 120 else None
        res.append({"name": name, "serial": _clean_serial(d.get("SerialNumber"), name), "model": str(d.get("FriendlyName") or "").strip(),
                    "size": int(d.get("Size") or 0), "bus": {"sata": "sata", "nvme": "nvme", "usb": "usb", "sas": "sas"}.get(bus, bus), "ssd": bool(ssd),
                    "role": "Boot drive" if boot else "Not in use" if not mounts else "Other", "mounts": mounts,
                    "usage": sorted(usage, key=lambda u: (not u["mount"].upper().startswith(sysdrive), -u["total"])),
                    "protected": bool(boot), "temp": temp, "smart_passed": healthy if d.get("HealthStatus") else None,
                    "realloc": None, "pending": None, "uncorrect": bad or None if r else None, "life": life, "hours": r.get("PowerOnHours"),
                    "health": (life if life is not None else 100) if healthy else (min(life if life is not None else 50, 50) if d.get("HealthStatus") else None),
                    "worn": life is not None and life <= 10, "crc": None, "sector": 512, "status": str(d.get("OperationalStatus") or "")})
    return res

SYSTEM_PROCS = {"system", "registry", "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe", "services.exe", "lsass.exe", "svchost.exe", "dwm.exe",
                "fontdrvhost.exe", "memory compression", "secure system", "lsaiso.exe", "sshd.exe", "dockerd.exe", "com.docker.service", "vmms.exe", "vmcompute.exe"}

def proc_list():
    me = {os.getpid(), os.getppid()}
    procs = [p for p in psutil.process_iter() if p.pid != 0]
    for p in procs:
        try: p.cpu_percent(None)
        except psutil.Error: pass
    time.sleep(.5); now = time.time(); out = []
    for p in procs:
        if p.pid in me: continue
        try:
            with p.oneshot():
                name = p.name(); cpu = p.cpu_percent(None)
                try: user = (p.username() or "").split("\\")[-1]
                except psutil.Error: user = "SYSTEM"
                try: cmd = " ".join(p.cmdline())
                except psutil.Error: cmd = ""
                try: mem = p.memory_info().rss
                except psutil.Error: mem = 0
                try: threads = p.num_threads()
                except psutil.Error: threads = 0
                kernel = p.pid == 4 or name.lower() in ("system", "registry", "memory compression", "secure system")
                out.append({"pid": p.pid, "ppid": p.ppid(), "user": user, "cpu": round(cpu, 1), "mem": mem, "threads": threads,
                            "up_s": int(max(0, now - p.create_time())), "state": {"running": "R", "stopped": "T"}.get(p.status(), "S"),
                            "name": name[:60], "cmd": (cmd or name)[:400], "kernel": kernel})
        except psutil.Error: continue
    return out

def proc_signal(pid, how, fail):
    try: p = psutil.Process(pid); name = p.name()
    except psutil.Error: fail("that process isn't running anymore")
    if pid <= 4 or pid in (os.getpid(), os.getppid()): fail("that one keeps the system running — it can't be stopped from here")
    if name.lower() in SYSTEM_PROCS: fail(f"{name} keeps Windows (or your way in) working — stop it on the server itself if you're sure")
    try:
        if any(LIB.lower() in (a or "").lower() for a in p.cmdline()): fail("that's part of Nova — use Power → Restart Nova instead")
    except psutil.Error: pass
    try:
        if how == "kill": p.kill()
        else:
            rc, so, se = run(["taskkill", "/PID", str(pid)], 20)          # asks it to close, like the X on its window
            if rc != 0: fail("it didn't close when asked — use Force stop")
    except psutil.NoSuchProcess: fail("that process isn't running anymore")
    except psutil.AccessDenied: fail("not allowed to stop that process")
    changelog(f"Process {pid} ({name}) {'force-stopped' if how == 'kill' else 'ended'} from the Nova app (Task manager)")
    return {"ok": True, "pid": pid, "name": name}

# scheduled restarts / shutdowns: one-time tasks in Task Scheduler's \Nova\ folder, named sched-<8 hex>; the list is kept beside them
SCHED_FILE = plat.p("/var/lib/nova-api/power-sched.json")
def _scheds():
    try: s = json.load(open(SCHED_FILE))
    except Exception: s = []
    return [x for x in s if isinstance(x, dict) and x.get("at", 0) > time.time() - 120]
def _save_scheds(s):
    os.makedirs(os.path.dirname(SCHED_FILE), exist_ok=True)
    with open(SCHED_FILE + ".tmp", "w") as f: json.dump(s, f)
    os.replace(SCHED_FILE + ".tmp", SCHED_FILE)

def power_schedule(action, at, fail):
    if not time.time() + 30 <= at <= time.time() + 367 * 86400: fail("pick a time from a minute from now to a year from now")
    s = _scheds()
    if len(s) >= 10: fail("there are already 10 scheduled — cancel one first")
    sid = os.urandom(4).hex(); when = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(at))
    arg = "/r /t 5 /c \"Nova: scheduled restart\"" if action == "reboot" else "/s /t 5 /c \"Nova: scheduled shutdown\""
    rc, so, se = ps("$a=New-ScheduledTaskAction -Execute 'shutdown.exe' -Argument " + psq(arg) + ";"
                    "$t=New-ScheduledTaskTrigger -Once -At ([datetime]" + psq(when) + ");"
                    "$p=New-ScheduledTaskPrincipal -UserId 'SYSTEM' -RunLevel Highest;"
                    "$s=New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable;"
                    "Register-ScheduledTask -TaskPath '\\Nova\\' -TaskName " + psq("sched-" + sid) + " -Action $a -Trigger $t -Principal $p -Settings $s -Force | Out-Null")
    if rc != 0: fail("couldn't set the timer: " + (se or so).strip()[-200:])
    _save_scheds(s + [{"id": sid, "at": at, "action": action}])
    changelog(f"Server {'restart' if action == 'reboot' else 'shutdown'} scheduled for {when.replace('T', ' ')} from the Nova app")
    return {"ok": True, "id": sid, "at": at, "action": action}

def power_unschedule(sid, fail):
    s = _scheds()
    if not any(x.get("id") == sid for x in s): fail("no such schedule")
    ps("Unregister-ScheduledTask -TaskPath '\\Nova\\' -TaskName " + psq("sched-" + sid) + " -Confirm:$false")
    _save_scheds([x for x in s if x.get("id") != sid]); changelog(f"Scheduled sched-{sid} cancelled from the Nova app")
    return {"ok": True}

def tools_status():
    have = {"docker": bool(shutil.which("docker")), "winget": bool(winget()), "tailscale": bool(shutil.which("tailscale")), "cloudflared": bool(shutil.which("cloudflared"))}
    return {"tools": have, "missing": []}

_WINGET = []
def winget():
    """winget.exe, also when running as SYSTEM (where it isn't on the PATH)."""
    if not _WINGET:
        import glob
        w = shutil.which("winget")
        if not w:
            c = sorted(glob.glob(os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "WindowsApps", "Microsoft.DesktopAppInstaller_*_*__8wekyb3d8bbwe", "winget.exe")))
            w = c[-1] if c else None
        _WINGET.append(w)
    return _WINGET[0]

def list_dirs(path, fail):
    """Folder picker: the drives at the top, then the folders inside one."""
    if path in ("", "/"):
        return {"path": "/", "parent": None, "dirs": [{"name": p.mountpoint, "path": p.mountpoint} for p in psutil.disk_partitions() if p.fstype]}
    path = os.path.abspath(path)
    if not os.path.isdir(path): fail("no such folder")
    dirs = []
    try:
        for e in sorted(os.scandir(path), key=lambda e: e.name.lower()):
            try:
                if e.is_dir(follow_symlinks=False) and not e.name.startswith(("$", ".")) and e.name not in ("System Volume Information",): dirs.append({"name": e.name, "path": e.path})
            except OSError: pass
    except PermissionError: fail("not allowed to look in that folder")
    parent = os.path.dirname(path.rstrip("\\/"))
    return {"path": path, "parent": "/" if parent == path or not parent or len(path) <= 3 else parent, "dirs": dirs[:500]}

def verb(verb, args, h):
    """Called by helper.py on Windows before its own verbs. Answers the Windows-only ones (and exits), returns for the
    shared ones, and refuses the rest plainly."""
    h.notify, h.changelog = notify, changelog                         # the shared verbs alert and log the Windows way
    out, fail = h.out, h.fail
    if verb == "drives" and not args:
        out({"drives": drives(), "unmounted": [], "fans": {"supported": False, "note": "Fan control isn't available on Windows."}})
    if verb == "proc-list" and not args: out({"processes": proc_list()})
    if verb == "proc-signal" and len(args) == 2 and args[0].isdigit() and args[1] in ("term", "kill"): out(proc_signal(int(args[0]), args[1], fail))
    if verb == "power" and len(args) == 1 and args[0] in ("reboot", "poweroff"):
        changelog(f"Server {args[0]} requested from the Nova app")
        notify("warning", f"Server {'restarting' if args[0] == 'reboot' else 'shutting down'} (from the Nova app)", "")
        if MONITOR: run([sys.executable, MONITOR], 40)               # send it now: after the restart is too late
        rc, so, se = run(["shutdown.exe", "/r" if args[0] == "reboot" else "/s", "/t", "8", "/c", "Nova: requested from the Nova app"], 20)
        out({"ok": rc == 0, "in_seconds": 8, "error": (se or so).strip()[:200] if rc else ""}, 0 if rc == 0 else 1)
    if verb == "power" and args in (["nova-restart"], ["nova-stop"]):
        stop = args[0] == "nova-stop"
        changelog(f"Nova {'stopped' if stop else 'restarted'} from the Nova app")
        if stop: notify("warning", "Nova was stopped (from the Nova app)", "The server keeps running. Start Nova again from the Nova Server window, or restart the server.")
        cmd = "Start-Sleep 2; Stop-ScheduledTask -TaskPath '\\Nova\\' -TaskName 'Api'" + ("" if stop else "; Start-Sleep 1; Start-ScheduledTask -TaskPath '\\Nova\\' -TaskName 'Api'")
        subprocess.Popen(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", cmd], creationflags=NO_WINDOW | 0x00000008 | 0x00000200,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True)
        out({"ok": True, "in_seconds": 2, "error": ""})
    if verb == "power-schedule-list" and not args: out({"schedules": _scheds()})
    if verb == "power-schedule" and len(args) == 2 and args[0] in ("reboot", "poweroff") and args[1].isdigit(): out(power_schedule(args[0], int(args[1]), fail))
    if verb == "power-unschedule" and len(args) == 1 and re.fullmatch(r"[0-9a-f]{8}", args[0]): out(power_unschedule(args[0], fail))
    if verb == "tools-status" and not args: out(tools_status())
    if verb == "list-dirs" and len(args) == 1: out(list_dirs(args[0], fail))
    if verb == "backup-status" and not args: out({"none": True})
    if verb == "recommendations" and not args: out({"recommendations": [], "snoozed": 0, "t": time.time()})
    if verb == "task-list" and not args: out({"tasks": []})
    if verb in ("programs", "program-search"): out({"items": []})
    if verb == "power-schedule-list" and not args: out({"schedules": _scheds()})
    if verb == "update-status" and not args:
        try: v = open(os.path.join(LIB, "VERSION")).read().strip()
        except OSError: v = ""
        out({"installed": v, "source": "", "running": False})
    if verb == "update-check" and not args: out({"ok": False, "message": "Updating from the app isn't available on Windows yet — run the installer again to update."}, 1)
    if verb == "power-schedule" or verb == "proc-signal" or verb == "power": fail("bad request", 2)
    if verb not in SHARED: fail("That isn't available on a Windows server yet.", 3)
