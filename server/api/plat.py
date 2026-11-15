"""
plat — the one place that knows which operating system Nova is running on.

Nova's server is written for Linux, and the same files run on Windows and macOS. Everything that differs
goes through here, so the rest of the code stays one codebase:

  p("/var/lib/nova-api/x")   a Linux path → where that lives on this system (unchanged on Linux)
  node(), kernel(), os_name(), cpu_model(), board()      what the machine is
  meminfo(), uptime_s(), loadavg()                        a few live numbers
  LINUX, WINDOWS, MAC                                     which one this is (the Windows code is winhost.py, the Mac's machost.py)
  host()                                                  that module — winhost or machost — or None on Linux

Rule of thumb when adding code: write the Linux path literally and wrap it in plat.p(); read system
facts through a function here instead of opening /proc yourself.
"""
import os, platform, re, sys

WINDOWS = os.name == "nt"
MAC = sys.platform == "darwin"
LINUX = not (WINDOWS or MAC)

# Where Nova's Linux folders live on Windows (under %ProgramData%\Nova) and on a Mac (under /Library/Application Support/Nova):
# one folder with the same layout on both, readable only by Nova's accounts.
if LINUX:
    ROOT, _MAP = "/", ()
else:
    ROOT = os.path.join(os.environ.get("ProgramData") or r"C:\ProgramData", "Nova") if WINDOWS else "/Library/Application Support/Nova"
    _MAP = (("/etc/nova-api", "config"), ("/etc/nova-alerts", os.path.join("config", "alerts")),
            ("/var/lib/nova-api", "data"), ("/var/lib/nova-alerts", "alerts"), ("/var/log/nova-api", "log"),
            ("/run/nova-tasks", os.path.join("run", "tasks")), ("/run/nova", "run"), ("/opt", "apps"),
            ("/etc/nova", os.path.join("config", "nova")))

def host():
    """The module that does things this system's way: winhost on Windows, machost on a Mac, None on Linux (its code is inline)."""
    if WINDOWS:
        import winhost
        return winhost
    if MAC:
        import machost
        return machost
    return None

def p(path):
    """A Linux path as written in the code → the real path here."""
    if LINUX: return path
    for src, dst in _MAP:
        if path == src or path.startswith(src + "/"):
            return os.path.join(ROOT, dst, *[s for s in path[len(src):].split("/") if s])
    return path

def node(): return platform.node() or "server"

def kernel():
    if WINDOWS:
        v = platform.version()                           # 10.0.26100
        return f"Windows {v}"
    return ("Darwin " if MAC else "") + platform.release()

def os_name():
    if not LINUX: return host().os_name()
    try: return re.search(r'^PRETTY_NAME="?([^"\n]+)', open("/etc/os-release").read(), re.M).group(1)
    except Exception: return "Linux"

def cpu_model():
    if not LINUX: return host().cpu_model()
    try: return next((l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name")), "")
    except OSError: return ""

def board():
    if not LINUX: return host().board()
    try:
        v = open("/sys/class/dmi/id/board_vendor").read().strip(); n = open("/sys/class/dmi/id/board_name").read().strip()
        return f"{v.split()[0].title() if v else ''} {n}".strip()
    except OSError: return ""

def meminfo():
    """Memory in kB, with /proc/meminfo's names: MemTotal, MemAvailable, SwapTotal, SwapFree (+ whatever else Linux has)."""
    if not LINUX: return host().meminfo()
    return {l.split(":")[0]: int(l.split()[1]) for l in open("/proc/meminfo")}

def uptime_s():
    if not LINUX: return host().uptime_s()
    return int(float(open("/proc/uptime").read().split()[0]))

def loadavg():
    """(1, 5, 15 minute) load. Windows has no such number: there it's the share of busy cores, so 'load / cores' still reads right."""
    if WINDOWS:
        import winhost
        return winhost.loadavg()
    return os.getloadavg()

def docker_running():
    if not LINUX: return host().docker_running()
    return os.path.exists("/var/run/docker.sock") or os.path.exists("/run/docker.sock")

OS = "windows" if WINDOWS else "macos" if MAC else "linux"   # sent to the apps (overview.server.os)
SUDO = "" if WINDOWS else "sudo "                            # what goes before "nova …" in a hint (Windows: an administrator terminal)
SYSTEM_DRIVE = (os.environ.get("SystemDrive") or "C:") + "\\" if WINDOWS else "/"

# Parts of Nova a Windows or Mac server doesn't have yet. They're reported in overview.features, so the apps hide those
# pages instead of showing errors. Remove a line here when the part works there (docs/WINDOWS.md and docs/MACOS.md have the lists).
WITHOUT = ({"terminal": False, "files": False, "store": False, "backup": False, "storage": False, "diagnostics": False, "diag": False,
            "lighting": False, "lighting_installed": False, "ssh": False, "updates": False, "programs": False, "freeup": False} if WINDOWS else
           {"store": False, "backup": False, "storage": False, "diagnostics": False, "diag": False,
            "lighting": False, "lighting_installed": False, "updates": False, "programs": False, "freeup": False} if MAC else {})
# (Windows: updates, programs and the store come back on where winget is, and SSH where OpenSSH Server is: server.py's win_extras())

def strftime(fmt, t=None):
    """time.strftime with Linux's %-d / %-I (no leading zero), which Windows spells %#d / %#I."""
    import time
    return time.strftime(fmt.replace("%-", "%#") if WINDOWS else fmt, t if t is not None else time.localtime())
