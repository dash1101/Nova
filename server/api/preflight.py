"""
Checks before (and after) setting Nova up: things that would make it fail later in confusing ways.
Each check returns (level, title, detail, fix) with level "ok" | "warn" | "stop".
Used by nova-setup (terminal), nova-setup-gui (browser) and `nova-setup --check`.
"""
import json, os, re, shutil, socket, subprocess

def _sh(*cmd, timeout=10):
    try: r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout); return r.returncode, r.stdout.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired): return 127, ""

def _port_owner(port, proto="tcp"):
    """Who listens on this port: None if it's free, "nova" if it's Nova itself, else the program's name."""
    rc, out = _sh("ss", "-H", "-lnp" + ("t" if proto == "tcp" else "u"), f"sport = :{port}")
    if rc != 0 or not out.strip(): return None
    pid = re.search(r"pid=(\d+)", out)
    if not pid: return "nova" if _sh("systemctl", "is-active", "nova-api.service")[0] == 0 else "another program"   # not root: can't see whose
    try:
        if "nova-api.service" in open(f"/proc/{pid.group(1)}/cgroup").read(): return "nova"
    except OSError: pass
    m = re.search(r'users:\(\("([^"]+)"', out)
    return m.group(1) if m else "another program"

def checks():
    out = []
    add = lambda lv, t, d="", fix="": out.append({"level": lv, "title": t, "detail": d, "fix": fix})
    # systemd runs Nova and its root helper
    if not os.path.isdir("/run/systemd/system"): add("stop", "This system doesn't use systemd", "Nova runs as a systemd service. Linux without systemd (or inside a simple container) isn't supported yet.")
    rc, virt = _sh("systemd-detect-virt", "-c")
    if rc == 0 and virt not in ("", "none"): add("warn", f"Running inside a container ({virt})", "Drives, lighting and power controls need the real machine; some parts of Nova won't work here.")
    # the one library it needs
    try: import cryptography  # noqa: F401
    except ImportError: add("stop", "Python's cryptography library is missing", "Nova checks every request's signature with it.", "Install python3-cryptography with your package manager")
    # ports
    for port, proto, what in ((8495, "tcp", "the app and browsers"), (8496, "udp", "finding the server on your Wi-Fi"), (8095, "tcp", "Nova's local port")):
        who = _port_owner(port, proto)
        if who and who != "nova": add("stop" if port != 8496 else "warn", f"Port {port} is already taken by {who}", f"Nova uses {proto} {port} for {what}.", f"Stop or move {who}, then run setup again")
    # space
    for p in ("/var/lib", "/etc"):
        try:
            st = os.statvfs(p); free = st.f_bavail * st.f_frsize
            if free < 100 * 2**20: add("warn", f"Almost no space left on {p} ({free // 2**20} MB)", "Nova keeps its settings, paired devices and alerts there.", "Free up some space first")
        except OSError: pass
    # the clock: every request carries a time stamp, so a wrong clock makes them all fail
    rc, sync = _sh("timedatectl", "show", "-p", "NTPSynchronized", "--value")
    if rc == 0 and sync.strip() == "no":
        rc2, ntp = _sh("timedatectl", "show", "-p", "NTP", "--value")
        add("warn", "The clock isn't synced with the internet", "Phones sign each request with the time; if the server's clock drifts more than a few minutes, they're refused.",
            "sudo timedatectl set-ntp true" if ntp.strip() == "no" else "Check that the server can reach a time server (NTP, udp 123)")
    # the home network
    rc, route = _sh("ip", "-4", "route", "get", "1.1.1.1")
    ip = re.search(r"src (\S+)", route or "")
    if not ip: add("warn", "No network address found", "Nova needs the server's address on your home network so the phone can reach it.", "Connect the server to your network, then run setup again")
    else:
        dev = re.search(r"dev (\S+)", route).group(1)
        rc, a = _sh("ip", "-4", "-o", "addr", "show", "dev", dev)
        if "dynamic" in a: add("ok", f"Home address {ip.group(1)} comes from your router (DHCP)", "", "Reserve this address for the server in your router so the app always finds it — Nova also finds it again by itself on Wi-Fi")
        else: add("ok", f"Home address {ip.group(1)}")
    # Docker is optional, but most of Nova's app features use it
    if not shutil.which("docker"): add("ok", "Docker isn't installed", "", "Containers, the app store and apps need it — setup can install it in the Tools step")
    elif _sh("docker", "info", timeout=15)[0] != 0: add("warn", "Docker is installed but not running", "", "sudo systemctl enable --now docker")
    return out

def reachable(host, port=8495, timeout=3):
    """After starting: can the app's port be reached on the home address (not only on localhost)?"""
    try:
        with socket.create_connection((host, port), timeout=timeout): return True
    except OSError: return False

if __name__ == "__main__":
    print(json.dumps(checks(), indent=1))
