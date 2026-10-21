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
Open https://<this server>:8495/ — the browser warns once about Nova's own certificate; that's
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
  Requests fail with a time error    the clock is off → sudo timedatectl set-ntp true
  The firewall blocks phones         sudo nova setup opens Nova's ports for your home network"""),

    "uninstall": ("Removing Nova", """
  sudo nova cloudflare undo      first, if you used Cloudflare auto-setup (takes back what Nova added)
  sudo apt remove nova-server    remove Nova, keep its settings and paired devices
  sudo apt purge nova-server     remove settings and paired devices too

Your containers, files and backups are never touched."""),
}
ALIASES = {"phone": "pair", "add": "pair", "approve": "browser", "iphone": "browser", "ios": "browser", "web": "browser",
           "link": "servers", "server": "servers", "nodes": "servers", "tailscale": "remote", "cloudflare": "remote",
           "away": "remote", "backups": "backup", "upgrade": "update", "doctor": "troubleshoot", "problems": "troubleshoot",
           "fix": "troubleshoot", "remove": "uninstall", "setup": "start", "install": "start", "begin": "start"}

def guide(topic):
    t = ALIASES.get(topic, topic)
    if t not in GUIDES: return None
    title, body = GUIDES[t]
    return title, body.strip("\n")

def topics(): return list(GUIDES)


# ── sudo nova doctor ────────────────────────────────────────────────────────────────────
def _sh(*cmd, timeout=10):
    try: r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout); return r.returncode, r.stdout.strip()
    except (FileNotFoundError, subprocess.TimeoutExpired): return 127, ""

def doctor():
    """What's wrong with this Nova, each with the fix in plain words: [{"level": ok|warn|stop, "title", "fix"}]."""
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
        if host in addrs: add("ok", f"Home address {host} is this machine's")
        else: add("stop", f"Phones look for this server at {host}, but that's no longer its address",
                  "sudo nova setup → Network (then the app finds it again; pairing stays)")
    # firewall
    rc, ufw = _sh("ufw", "status")
    if rc == 0 and ufw.startswith("Status: active"):
        if str(port) in ufw: add("ok", "The firewall lets your home network in")
        else: add("warn", f"The firewall (ufw) is on and doesn't allow port {port}", "sudo nova setup → Start Nova opens it for your home network only")
    elif shutil.which("firewall-cmd") and _sh("firewall-cmd", "--state")[0] == 0:
        _, fw = _sh("firewall-cmd", "--list-all")
        if str(port) not in fw: add("warn", f"The firewall (firewalld) doesn't allow port {port}", "sudo nova setup → Start Nova opens it for your home network only")
    # time: every request is signed with a timestamp
    _, sync = _sh("timedatectl", "show", "-p", "NTPSynchronized", "--value")
    if sync == "no": add("warn", "The clock isn't synced — signed requests can be refused", "sudo timedatectl set-ntp true")
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
    # Tailscale chosen but not signed in
    if cfg.get("tailnet_host"):
        rc, ip = _sh("tailscale", "ip", "-4")
        if rc != 0 or cfg["tailnet_host"] not in ip: add("warn", "Tailscale is set up for Nova but isn't connected", "sudo tailscale up")
    return out
