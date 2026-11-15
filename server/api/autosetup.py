"""
autosetup — first-time settings for a Nova server on Windows or macOS, without questions (the installer runs it; safe to
run again). Linux has the question-by-question `sudo nova setup` instead.

    python autosetup.py            write config.json if there isn't one, and print what it found
    python autosetup.py --refresh  look at the network again (after the server's address changed) and keep everything else

It finds this machine's home-network address the way nova-setup does on Linux, and Tailscale's when it's running.
"""
import json, os, sys

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)
import plat  # noqa: E402

CONFIG = plat.p("/etc/nova-api/config.json")
ALERTS = plat.p("/etc/nova-alerts/config.json")

def lan():
    """This machine's home-network address and subnet: (host, "a.b.c.0/24"), from the interface that leads to the internet."""
    import ipaddress, socket, psutil
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
    import socket, psutil
    for name, addrs in psutil.net_if_addrs().items():
        for a in addrs:
            if a.family == socket.AF_INET and a.address.startswith("100.") and 64 <= int(a.address.split(".")[1]) <= 127: return a.address, "100.64.0.0/10"
    return "", ""

def network():
    host, subnet = lan()
    th, ts = tailnet()
    return {"lan_host": host, "lan_subnet": subnet, "lan_url": f"http://{host}:8095", "bind": ["127.0.0.1", host],
            "tailnet_host": th, "tailnet_subnet": ts}

def first_config():
    cfg = {"port": 8095, "tls_port": 8495, **network(), "ssh_user": "", "ssh_hosts": [], "remote_url": ""}
    if plat.MAC:                                              # the terminal, file manager and SSH key use your own account
        import machost
        cfg["ssh_user"] = os.environ.get("SUDO_USER", "") if os.environ.get("SUDO_USER", "") not in ("", "root") else machost.console_user()
    return cfg

def _private(path, group):
    """root may write it; Nova's own account may read it (the alerts settings — the Discord webhook — root only)."""
    import grp
    os.chown(path, 0, grp.getgrnam(group).gr_gid if group else 0); os.chmod(path, 0o640 if group else 0o600)

def main():
    refresh = "--refresh" in sys.argv
    for d in ("/etc/nova-api", "/etc/nova-alerts", "/var/lib/nova-api", "/var/lib/nova-alerts", "/var/log/nova-api", "/run/nova", "/run/nova-tasks", "/opt"):
        os.makedirs(plat.p(d), exist_ok=True)
    try: cfg = json.load(open(CONFIG))
    except Exception: cfg = None
    if cfg is None:
        cfg = first_config()
    elif refresh:
        net = network()
        if cfg.get("bind"): net["bind"] = [net["lan_host"] if b == cfg.get("lan_host") else b for b in cfg["bind"]]
        if cfg.get("lan_url", "").startswith("http://"): net["lan_url"] = f"http://{net['lan_host']}:{cfg.get('port', 8095)}"
        cfg.update(net)
    else:
        print(json.dumps({"ok": True, "changed": False, "lan_host": cfg.get("lan_host", "")})); return
    tmp = CONFIG + ".tmp"
    with open(tmp, "w") as f: json.dump(cfg, f, indent=2)
    if plat.MAC: _private(tmp, "nova-api")                    # (on Windows the folder's permissions decide who reads it)
    os.replace(tmp, CONFIG)
    if not os.path.exists(ALERTS):
        with open(ALERTS, "w") as f: json.dump({}, f)
        if plat.MAC: _private(ALERTS, None)
    print(json.dumps({"ok": True, "changed": True, "lan_host": cfg["lan_host"], "lan_subnet": cfg["lan_subnet"], "tailnet_host": cfg.get("tailnet_host", "")}))

if __name__ == "__main__":
    main()
