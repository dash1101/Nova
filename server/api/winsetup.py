"""
winsetup — first-time settings for a Nova server on Windows (the installer runs it; safe to run again).

    python winsetup.py            write config.json if there isn't one, and print what it found
    python winsetup.py --refresh  look at the network again (after the server's address changed) and keep everything else

It finds this machine's home-network address the way nova-setup does on Linux, and Tailscale's when it's running.
"""
import json, os, sys

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)
import plat, winhost  # noqa: E402

CONFIG = plat.p("/etc/nova-api/config.json")
ALERTS = plat.p("/etc/nova-alerts/config.json")

def network():
    host, subnet = winhost.lan()
    th, ts = winhost.tailnet()
    return {"lan_host": host, "lan_subnet": subnet, "lan_url": f"http://{host}:8095", "bind": ["127.0.0.1", host],
            "tailnet_host": th, "tailnet_subnet": ts}

def main():
    refresh = "--refresh" in sys.argv
    for d in ("/etc/nova-api", "/etc/nova-alerts", "/var/lib/nova-api", "/var/lib/nova-alerts", "/var/log/nova-api", "/run/nova", "/run/nova-tasks", "/opt"):
        os.makedirs(plat.p(d), exist_ok=True)
    try: cfg = json.load(open(CONFIG))
    except Exception: cfg = None
    if cfg is None:
        cfg = {"port": 8095, "tls_port": 8495, **network(), "ssh_user": "", "ssh_hosts": [], "remote_url": ""}
    elif refresh:
        net = network()
        if cfg.get("bind"): net["bind"] = [net["lan_host"] if b == cfg.get("lan_host") else b for b in cfg["bind"]]
        if cfg.get("lan_url", "").startswith("http://"): net["lan_url"] = f"http://{net['lan_host']}:{cfg.get('port', 8095)}"
        cfg.update(net)
    else:
        print(json.dumps({"ok": True, "changed": False, "lan_host": cfg.get("lan_host", "")})); return
    tmp = CONFIG + ".tmp"
    with open(tmp, "w") as f: json.dump(cfg, f, indent=2)
    os.replace(tmp, CONFIG)
    if not os.path.exists(ALERTS):
        with open(ALERTS, "w") as f: json.dump({}, f)
    print(json.dumps({"ok": True, "changed": True, "lan_host": cfg["lan_host"], "lan_subnet": cfg["lan_subnet"], "tailnet_host": cfg.get("tailnet_host", "")}))

if __name__ == "__main__":
    main()
