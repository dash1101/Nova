"""
Short guides for `nova help TOPIC` (and `?` during `sudo nova setup`), and the checks behind `sudo nova doctor`.
Plain words, the commands to type, and where to look next. Kept here so the CLI, the setup and the docs say the same.
"""
import json, os, re, shutil, socket, subprocess

GUIDES = {
    "start": ("Getting started", """
Nova has two parts: this server, and the Nova app on your Android phone (or any browser).

  1  Set up this server (about five minutes):
       sudo nova setup            questions in this terminal
       sudo nova setup --gui      the same in a browser
       "Set up Nova"              in your apps menu, if this computer has a desktop
  2  Pair your phone, on your home Wi-Fi:
       sudo nova add              shows a QR code — scan it in the Nova app
  3  Or use a browser:  open https://<this server>:8495/ , choose "Get a code",
     then approve it in the app (or: sudo nova approve CODE)

Everything else is in the app. More: nova help pair · nova help browser · nova help remote"""),

    "pair": ("Pairing a phone or tablet", """
On home Wi-Fi (the phone must be on the same network as this server):

  sudo nova add                  a QR code and a code to type, good for 10 minutes
  sudo nova add --viewer         view only: sees everything, changes nothing (family, a wall tablet)
  sudo nova add --user NAME      for a named person (shown in Users & devices)

In the app: "Scan the code", or type the server address and code — then check the certificate
code it shows matches the "Certificate" line here. That check is what keeps a stranger in the
middle out.

  sudo nova devices              who's paired          sudo nova remove NAME    take one away for good

Didn't work? nova help troubleshoot"""),

    "browser": ("Using Nova in a browser (and on iPhone)", """
Open https://<this server>:8495/ (on the server itself: https://localhost:8495/) — the browser warns once about Nova's own certificate; that's
expected. Choose "Get a code", then any one of:

  • the Nova app: Menu → Users & devices → Approve a browser (scan the QR, or type the code)
  • this server:  sudo nova approve CODE
  • an authenticator app or a passkey, if you've set one up (Security & privacy) — no phone needed

On iPhone or iPad: open it in Safari → Share → Add to Home Screen, and it works like an app.
From outside your home it needs Tailscale or Cloudflare: nova help remote"""),

    "servers": ("More than one server", """
Install Nova on each one. Then you can see them together:

  • In the app: Settings → Servers lists the Nova servers it finds at home — tap one to pair it.
  • In Nova web: Servers → pick one it found (or type its address) → Link. That server asks for
    approval: in the Nova app on your phone, or there with  sudo nova approve CODE
  • From the terminal on the server that shows the others:  sudo nova link OTHER-SERVER

Linked servers are read-only here (status, numbers, alerts). To control one, the browser pairs
with that server itself — Nova web walks you through it."""),

    "remote": ("Using Nova away from home", """
Nova works at home out of the box. For when you're out, pick either (or both):

  Tailscale (easiest)    a private network between your own devices — nothing opens to the internet.
                         sudo nova setup → Away from home → Tailscale, then install Tailscale on your phone.
  Cloudflare Tunnel      a web address on your own domain, behind Cloudflare's login.
                         Step by step: /usr/share/doc/nova-server/REMOTE.md

Either way every request is still signed by your phone, so the address alone lets nobody in."""),

    "backup": ("Backups", """
Set backups up in the app or Nova web: Storage → Backups → New backup — pick folders, a drive
or a NAS, and how often. Dated snapshots; unchanged files cost no extra space.

  sudo nova backup now           run your backups now
  sudo nova backup list          what's there

Restoring: in the app, open a backup → a date → the files you want back."""),

    "update": ("Updating", """
  sudo nova update               install the newest release (checked against Nova's signing key)
  sudo nova update --check       only look

Nova also checks once a day and tells you in the app when a new version is out. Your settings and
paired devices stay. The app updates itself from this server (Settings → Software update)."""),

    "troubleshoot": ("When something's wrong", """
  sudo nova doctor               checks everything and says what to do about each problem
  sudo nova status               is it running, and at which addresses
  sudo journalctl -u nova-api -n 50     Nova's own log (errors at the end)
  sudo nova log                  the last actions (who did what)

Common ones:
  The app can't reach the server     same Wi-Fi? the server's address changed? → sudo nova setup
  "Certificate doesn't match"        Nova was reinstalled → pair again (sudo nova add)
  Authenticator codes are refused    the server's clock is off → sudo timedatectl set-ntp true
  The firewall blocks phones         sudo nova setup opens Nova's ports (home network and Tailscale)
  Works at home but not away         sudo nova setup → Away from home → Tailscale, and Tailscale
                                     on the phone (same account); the app picks it up by itself"""),

    "uninstall": ("Removing Nova", """
  sudo nova cloudflare undo      first, if you used Cloudflare auto-setup (takes back what Nova added)
  sudo nova uninstall            remove Nova, keep its settings and paired devices
  sudo nova uninstall --purge    remove settings and paired devices too
  (On Debian and Ubuntu that's the same as sudo apt remove / apt purge nova-server.)

Your containers, files and backups are never touched."""),
}
ALIASES = {"phone": "pair", "add": "pair", "approve": "browser", "iphone": "browser", "ios": "browser", "web": "browser",
           "link": "servers", "server": "servers", "nodes": "servers", "tailscale": "remote", "cloudflare": "remote",
           "away": "remote", "backups": "backup", "upgrade": "update", "doctor": "troubleshoot", "problems": "troubleshoot",
           "fix": "troubleshoot", "remove": "uninstall", "setup": "start", "install": "start", "begin": "start"}

# On Windows a few guides are different: no apt, no journalctl, no sudo (an administrator terminal instead).
WINDOWS = {
    "update": ("Updating", """
Download the newest nova-windows.zip and double-click "Install Nova.cmd" again — or, in an administrator
PowerShell:  irm https://raw.githubusercontent.com/dash1101/Nova/main/get.ps1 | iex
Your settings, paired devices and the server's certificate stay. The app updates itself from this server."""),
    "uninstall": ("Removing Nova", """
  nova cloudflare undo                                  first, if you used Cloudflare auto-setup
  powershell -File "C:\\Program Files\\Nova\\uninstall.ps1"         remove Nova, keep settings and paired devices
  powershell -File "C:\\Program Files\\Nova\\uninstall.ps1" -Purge  remove settings and paired devices too

Your containers, files and backups are never touched."""),
    "troubleshoot": ("When something's wrong", """
  nova doctor                    checks everything and says what to do about each problem
  nova status                    is it running, and at which addresses
  nova log                       the last actions (who did what)
  C:\\ProgramData\\Nova\\log        Nova's own logs (api.log, helper.log, alerts.log)

Common ones:
  The app can't reach the server     same Wi-Fi? the address changed? → Nova Server window → Look again
  "Certificate doesn't match"        Nova was reinstalled with -Purge → pair again (nova add)
  Authenticator codes are refused    the server's clock is off → Settings → Time → Set time automatically
  The firewall blocks phones         run the installer again (it adds the rule for your home network)"""),
}

def guide(topic):
    t = ALIASES.get(topic, topic)
    if t not in GUIDES: return None
    title, body = WINDOWS[t] if os.name == "nt" and t in WINDOWS else GUIDES[t]
    return title, body.strip("\n")

def topics(): return list(GUIDES)


# ── sudo nova doctor ────────────────────────────────────────────────────────────────────
def _sh(*cmd, timeout=10):
    try: r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout); return r.returncode, r.stdout.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired): return 127, ""

def _in_tailnet(ip):
    try: return __import__("ipaddress").ip_address(ip) in __import__("ipaddress").ip_network("100.64.0.0/10")
    except ValueError: return False

def doctor():
    """What's wrong with this Nova, each with the fix in plain words: [{"level": ok|warn|stop, "title", "fix"}]."""
    if os.name == "nt":                                    # Windows has its own checks (tasks instead of systemd units, its own firewall)
        import winhost
        return winhost.doctor()
    out = []
    add = lambda lv, t, fix="": out.append({"level": lv, "title": t, "fix": fix})
    try: cfg = json.load(open("/etc/nova-api/config.json"))
    except (OSError, ValueError): cfg = None
    if cfg is None:
        add("stop", "Nova isn't set up yet", "sudo nova setup   (or sudo nova setup --gui)")
        return out
    active = _sh("systemctl", "is-active", "nova-api.service")[0] == 0
    if active: add("ok", "Nova is running")
    else: add("stop", "Nova isn't running", "sudo systemctl restart nova-api — and if it stops again: sudo journalctl -u nova-api -n 50")
    if _sh("systemctl", "is-active", "nova-helper.socket")[0] != 0:
        add("stop", "Nova's helper (drives, power, updates) isn't listening", "sudo systemctl enable --now nova-helper.socket")
    port = int(cfg.get("tls_port", 8495))
    if active:
        rc, ss = _sh("ss", "-H", "-ltn", f"sport = :{port}")
        add("ok", f"Listening for phones and browsers on port {port}") if ss else add("stop", f"Nothing is listening on port {port}", "sudo journalctl -u nova-api -n 50 shows why")
    # the address phones use still belongs to this machine (a new router or DHCP lease moves it)
    host = cfg.get("lan_host", "")
    if host and host not in ("0.0.0.0", "::"):
        _, addrs = _sh("ip", "-o", "addr", "show")
        try:
            a_ = __import__("ipaddress").ip_address(host); ipn = __import__("ipaddress").ip_network
            if a_ in ipn("100.64.0.0/10"):
                add("warn", f"The home address is set to {host}, a Tailscale address — phones and browsers at home that aren't on Tailscale can't reach Nova",
                    "sudo nova setup → Network, and pick this machine's address on your home Wi-Fi or Ethernet (Tailscale keeps working)")
        except ValueError: pass
        mine = lambda ip: f"inet {ip}/" in addrs or f"inet6 {ip}/" in addrs
        if mine(host): add("ok", f"Home address {host} is this machine's")
        elif _in_tailnet(host): add("stop", f"Nova's home address is its Tailscale one ({host}), and Tailscale isn't connected on this computer",
                                    "sudo tailscale up — or sudo nova setup → Network to use this machine's home Wi-Fi or Ethernet address")
        else: add("stop", f"Phones look for this server at {host}, but that's no longer its address",
                  "sudo nova setup → Network (then the app finds it again; pairing stays)")
        # Tailscale: this computer has its address, and Nova answers there (it waits for an address that comes up after it starts)
        th = cfg.get("tailnet_host", "")
        if th and th != host:
            now = [w.split("/")[0] for w in addrs.split() if "/" in w and w.count(".") == 3 and _in_tailnet(w.split("/")[0])]
            if not mine(th) and now:
                add("warn", f"Tailscale's address changed: Nova uses {th}, this computer now has {now[0]} — phones away from home can't reach Nova",
                    "sudo nova setup → Away from home → Tailscale (phones learn the new address next time they're home)")
            elif not mine(th):
                add("warn", f"Tailscale isn't connected on this computer (its address {th} isn't here), so phones away from home can't reach Nova",
                    "sudo tailscale up   (or check: tailscale status)")
            elif active and f"{th}:{port}" not in _sh("ss", "-H", "-ltn", f"sport = :{port}")[1]:
                add("warn", f"Nova isn't answering on its Tailscale address {th} yet", "it picks it up within half a minute; if not: sudo systemctl restart nova-api")
            else: add("ok", f"Tailscale address {th} is this machine's, and Nova answers there")
        if th or _in_tailnet(host):
            # "shields up" (Tailscale's "Allow incoming connections" off): this computer reaches its own address, nothing else does
            rc, prefs = _sh("tailscale", "debug", "prefs")
            if rc == 0 and re.search(r'"ShieldsUp"\s*:\s*true', prefs):
                add("stop", "Tailscale is blocking incoming connections (shields up), so your phone can't reach Nova over Tailscale — even though this computer can",
                    "sudo tailscale set --shields-up=false")
    # firewall
    rc, ufw = _sh("ufw", "status")
    if rc == 0 and ufw.startswith("Status: active"):
        if str(port) in ufw: add("ok", "The firewall lets your home network in")
        else: add("warn", f"The firewall (ufw) is on and doesn't allow port {port}", "sudo nova setup → Start Nova opens it for your home network only")
        if cfg.get("tailnet_host") and "tailscale0" not in ufw and "100.64.0.0/10" not in ufw:
            add("warn", "The firewall (ufw) doesn't let your Tailscale devices reach Nova", f"sudo ufw allow in on tailscale0 to any port {port} proto tcp")
    elif shutil.which("firewall-cmd") and _sh("firewall-cmd", "--state")[0] == 0:
        _, fw = _sh("firewall-cmd", "--list-all")
        if str(port) not in fw: add("warn", f"The firewall (firewalld) doesn't allow port {port}", "sudo nova setup → Start Nova opens it for your home network only")
        if cfg.get("tailnet_host") and "100.64.0.0/10" not in fw and "tailscale0" not in fw \
                and _sh("firewall-cmd", "--get-zone-of-interface=tailscale0")[1].strip() != "trusted":
            add("warn", "The firewall (firewalld) doesn't let your Tailscale devices reach Nova",
                f"sudo firewall-cmd --permanent --add-rich-rule='rule family=ipv4 source address=100.64.0.0/10 port port={port} protocol=tcp accept' && sudo firewall-cmd --reload")
    # time: every request is signed with a timestamp
    _, sync = _sh("timedatectl", "show", "-p", "NTPSynchronized", "--value")
    if sync == "no": add("warn", "The clock isn't synced — authenticator codes, schedules and alert times can be off", "sudo timedatectl set-ntp true")
    # room to work
    for path in ("/", "/var"):
        try:
            st = os.statvfs(path); free = st.f_bavail / max(1, st.f_blocks)
            if free < .05: add("warn", f"{path} is almost full ({free:.0%} free)", "free some space: the app's Free up (Menu → Free up) or sudo apt clean")
        except OSError: pass
    # devices
    try: n = len(json.load(open("/var/lib/nova-api/devices.json")))
    except (OSError, ValueError): n = 0
    add("ok", f"{n} paired device{'s' if n != 1 else ''}") if n else add("warn", "No phone or browser is paired yet", "sudo nova add   (nova help pair)")
    # (Tailscale: checked with the addresses above)
    # went off without shutting down lately? (the monitor notes each time, and why, in the Inbox)
    try:
        import time
        ends = [t for t in json.load(open("/var/lib/nova-alerts/state.json")).get("counters", {}).get("bad_ends", []) if time.time() - t < 7 * 86400]
        if ends: add("warn", f"The server went off without shutting down {len(ends)} time{'s' if len(ends) != 1 else ''} in the last 7 days",
                     "the Inbox says why each time (a crash, heat, a power cut…) and what to change")
    except (OSError, ValueError, TypeError, AttributeError): pass
    return out
