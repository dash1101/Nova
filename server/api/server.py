#!/usr/bin/python3
"""
nova-api — backend for the Nova Android app.

Runs as the unprivileged `nova-api` user. Listens on the LAN address (home Wi-Fi, trusted)
and on 127.0.0.1 (where cloudflared delivers remote requests). Anything that needs root
goes through /usr/local/lib/nova-api/helper, which only knows a fixed list of verbs.

Every request (except pairing) must be signed by a paired device:
  X-Nova-Device:    device id (from pairing)
  X-Nova-Time:      unix time in ms        (must be within ±60 s)
  X-Nova-Nonce:     random, single-use     (replays are rejected)
  X-Nova-Signature: base64 ECDSA-P256/SHA-256 over
                    METHOD \n PATH?QUERY \n TIME \n NONCE \n sha256hex(BODY)
The private key is generated inside the phone's hardware keystore and never leaves it.

Remote requests (via Cloudflare) must also carry a valid Cloudflare Access JWT, verified
against your team's public keys — so the edge rejects strangers before they reach us,
and if Access is ever switched off by mistake, remote access fails closed.
Pairing is only possible from the home LAN, with a one-time code shown by `sudo nova add`.
"""
import base64, hashlib, hmac, ipaddress, json, os, re, secrets, socket, subprocess, sys, threading, time, urllib.error, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
sys.path.insert(0, os.path.dirname(os.path.realpath(__file__)))
import nodes

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

HERE = os.path.dirname(os.path.realpath(__file__))
for _d in ("/usr/lib/nova-rgb", "/usr/local/lib/nova-rgb"):      # packaged / source install
    if os.path.isdir(_d): sys.path.insert(0, _d)
sys.path.insert(0, HERE)
from nova_tz import tz_location  # noqa: E402
try:                       # optional module: fan/case lighting (modules/fan-gigabyte-fusion2)
    import nova_rgb  # noqa: E402
except ImportError:
    nova_rgb = None

API_VERSION = "0.5.11-alpha"
CONFIG = "/etc/nova-api/config.json"
DATA = "/var/lib/nova-api"
DEVICES = f"{DATA}/devices.json"
NOTIFY_FILE = f"{DATA}/notify.json"      # notification settings chosen in the app
PAIRING = f"{DATA}/pairing.json"
AUDIT = "/var/log/nova-api/audit.log"
STATUS = "/var/lib/nova-alerts/www/status.json"
MAX_BODY = 64 * 1024
UPLOAD_CHUNK = 4 * 1024 * 1024 + 1024
LABS = {"cloudflare_sync": {"name": "Cloudflare auto-setup", "about": "Put an app on your own domain in one step: Nova adds the Cloudflare tunnel route, the DNS record and the Access login for you. Needs a Cloudflare API token."},
        "crash_guard": {"name": "Crash-loop guard", "about": "If a container restarts 5 times in 10 minutes, Nova stops it and tells you, instead of letting it restart forever. Starting it again turns its automatic restarts back on."},
        "auto_updates": {"name": "Weekly container updates", "about": "Once a week, Nova updates the containers that have a newer version. One that won't start on its new version is put back on the old one."},
        "image_cleanup": {"name": "Clean up old images", "about": "See the container images nothing uses anymore (old versions left behind by updates) and remove them to free up space. The versions Nova keeps to roll back to stay."},
        "wake_on_lan": {"name": "Wake-on-LAN", "about": "Turn on other computers on your network from Nova: the server sends them a wake-up packet."}}
WOL = f"{DATA}/wol.json"                         # Labs → Wake-on-LAN: [{id, name, mac}]
LABS_STATE = f"{DATA}/labs-state.json"           # written by labs.py (root): last weekly update, …
RAW_BODY_PATHS = ("/api/v1/files/upload", "/api/v1/files/save")
FILE_UNLOCK = {}          # browser device id → until when it may change files (after a phone approved it)
NOTIFY_KEYS = {"push_min_level": ("info", "warning", "critical"), "push_logins": bool, "push_usb": bool,
               "discord_paused": bool}
EVENTS = "/var/lib/nova-alerts/www/events.json"
APK_DIR = f"{DATA}/apk"
CRASH_DIR = "/var/log/nova-api/crash"
CLOCK_SKEW_MS = 60_000
SHELL_APPROVE = f"{DATA}/shell-approve.json"   # written by `sudo nova approve CODE` (root only)
PENDING = f"{DATA}/pending.json"               # what's waiting for approval, for `sudo nova approve` (root/nova-api only)
CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
APPS = f"{DATA}/apps.json"                      # your changes to the Apps grid (names, hidden, custom links)
ICONS = f"{DATA}/icons"                         # app icons, fetched once from dashboard-icons and kept here
SETTINGS = f"{DATA}/settings.json"       # changeable from the app: display name, accent color

lock = threading.Lock()          # devices.json / pairing.json writes
rgb_lock = threading.Lock()      # one HID conversation at a time
nonces = {}                      # nonce -> expiry (seconds)
fails = {}                       # ip -> [timestamps]


def server_location():
    """The location used for sunrise/sunset: the one set in setup or the app, else the time zone's."""
    loc = load_json(SETTINGS, {}).get("location")
    if loc: return {**loc, "source": "set"}
    return tz_location()

def valid_location(v):
    if v is None: return None                                  # back to the time zone's
    lat, lon = float(v.get("lat")), float(v.get("lon"))
    if not (-90 <= lat <= 90 and -180 <= lon <= 180): raise ValueError("location out of range")
    name = "".join(c for c in str(v.get("name", "")) if c.isprintable()).strip()[:40]
    return {"lat": round(lat, 2), "lon": round(lon, 2), "name": name}     # ~1 km: plenty for the sun

_apps_cache = {"t": 0, "apps": []}
def app_list():
    """Detected web apps (cached 30 s) + your custom links, with your changes applied."""
    if time.time() - _apps_cache["t"] > 30:
        rc, res = helper("apps-detect", timeout=60)
        if rc == 0: _apps_cache.update(t=time.time(), apps=res.get("apps", []))
    cfg = load_json(APPS, {"overrides": {}, "custom": []}); ov = cfg.get("overrides", {})
    out = []
    for a in _apps_cache["apps"]:
        o = ov.get(a["id"], {})
        out.append({**a, "name": o.get("name") or a["name"], "slug": o.get("icon") or a["slug"], "hidden": bool(o.get("hidden")),
                    "url": o.get("url", ""), "remote_url": o.get("remote_url", "")})
    for c in cfg.get("custom", []):
        out.append({"id": c["id"], "name": c["name"], "slug": c.get("icon") or "", "url": c.get("url", ""), "remote_url": c.get("remote_url", ""),
                    **({"kind": "command", "command": c["command"], "timeout": c.get("timeout", 600), "confirm": bool(c.get("confirm"))} if c.get("command") else {}),
                    "hidden": bool(c.get("hidden")), "source": "custom", "port": None, "scheme": "", "path": "", "host_ip": ""})
    return out

def live_tasks():
    """Running background tasks (and ones that just ended), read straight from /run/nova-tasks."""
    out = []
    try: names = os.listdir("/run/nova-tasks")
    except OSError: return out
    for n in names:
        if not n.endswith(".json"): continue
        t = load_json(f"/run/nova-tasks/{n}", None)
        if t and (t.get("state") == "running" or time.time() - (t.get("finished") or 0) < 60):
            out.append({k: t.get(k) for k in ("id", "kind", "title", "state", "pct", "step", "note", "error", "key", "started", "finished")})
    return sorted(out, key=lambda t: -(t.get("started") or 0))

def app_remote(aid):
    """Help putting an app on the internet safely through your Cloudflare tunnel: what to type in the
    Cloudflare dashboard, and (if a remote link is set) whether that link really asks for a login
    (Cloudflare Access) before anything reaches the app."""
    a = next((x for x in app_list() if x["id"] == aid), None)
    if not a: return 404, {"error": "no such app"}
    nova = urllib.parse.urlparse(CFG.get("remote_url", "")).hostname or ""
    domain = ".".join(nova.split(".")[-2:]) if nova.count(".") >= 1 else ""
    service = f"{a.get('scheme') or 'http'}://localhost:{a['port']}" if a.get("port") else (a.get("url") or "")
    out = {"service": service, "suggested": f"{(a.get('slug') or re.sub(r'[^a-z0-9]+', '-', a['name'].lower())).strip('-')[:30]}.{domain}" if domain else "",
           "domain": domain, "nova_host": nova, "remote_url": a.get("remote_url", "")}
    u = urllib.parse.urlparse(a.get("remote_url", ""))
    if not a.get("remote_url"): return 200, out
    host = u.hostname or ""
    if u.scheme != "https" or not host or re.fullmatch(r"[\d.]+|\[?[0-9a-f:]+\]?", host) or host in ("localhost",) or host.endswith((".local", ".lan", ".internal")):
        out["check"] = {"state": "bad", "message": "The remote link should be an https:// address on your own domain"}; return 200, out
    class NoFollow(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k): return None
    try:
        req = urllib.request.Request(urllib.parse.urlunparse(u._replace(path=u.path or "/")), headers={"User-Agent": "Nova remote check"})
        try: r = urllib.request.build_opener(NoFollow).open(req, timeout=8); code, loc = r.status, ""
        except urllib.error.HTTPError as e: code, loc = e.code, e.headers.get("Location", "")
        lh = urllib.parse.urlparse(loc).hostname or ""
        if code in (301, 302, 303, 307, 308) and lh.endswith(".cloudflareaccess.com"):
            out["check"] = {"state": "protected", "message": "Protected: Cloudflare asks for your login before anything reaches the app"}
        elif 200 <= code < 300:
            out["check"] = {"state": "open", "message": "Open to the internet: the app answered without Cloudflare asking anyone to log in. Add this address to your Cloudflare Access application (or a wildcard for your domain) before using it."}
        elif code in (301, 302, 303, 307, 308):
            out["check"] = {"state": "open", "message": f"It redirects to {lh or 'another page'} instead of a Cloudflare login — check that the address is covered by Cloudflare Access."}
        elif code in (502, 503, 530):
            out["check"] = {"state": "down", "message": "Cloudflare answered, but couldn't reach the app — check the service in the tunnel's public hostname"}
        else: out["check"] = {"state": "unknown", "message": f"Answered with HTTP {code}"}
    except Exception as e:
        out["check"] = {"state": "down", "message": "Can't reach that address yet (the DNS record may still be on its way)"}
    return 200, out

def app_icon(aid):
    a = next((x for x in app_list() if x["id"] == aid), None)
    slug = (a or {}).get("slug", "")
    if not slug or not re.fullmatch(r"[a-z0-9-]{1,60}", slug): return None
    f = f"{ICONS}/{slug}.png"
    if os.path.exists(f): return open(f, "rb").read() or None
    os.makedirs(ICONS, exist_ok=True)
    try:
        req = urllib.request.Request(f"https://cdn.jsdelivr.net/gh/homarr-labs/dashboard-icons/png/{slug}.png", headers={"User-Agent": "nova"})
        with urllib.request.urlopen(req, timeout=6) as r:
            data = r.read(512 * 1024)
        if not data.startswith(b"\x89PNG"): data = b""
    except Exception: data = b""
    open(f, "wb").write(data)              # empty file = no icon (don't ask again)
    return data or None

def load_json(p, default):
    try:
        with open(p) as f: return json.load(f)
    except Exception: return default

def save_json(p, obj, mode=0o640):
    tmp = f"{p}.tmp{os.getpid()}"
    with open(tmp, "w") as f: json.dump(obj, f, indent=1)
    os.chmod(tmp, mode); os.replace(tmp, p)

def audit(**kw):
    kw["t"] = time.strftime("%Y-%m-%d %H:%M:%S")
    try:
        fd = os.open(AUDIT, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o640)
        with os.fdopen(fd, "a") as f: f.write(json.dumps(kw) + "\n")
    except Exception: pass

HELPER_SOCK = "/run/nova-helper.sock"     # socket-activated helper outside this sandbox (if installed)

def helper_connect(args, timeout):
    import socket
    c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM); c.settimeout(timeout)
    c.connect(HELPER_SOCK); c.sendall((json.dumps(list(args)) + "\n").encode())
    return c

def helper(*args, timeout=120, data=b""):
    if os.path.exists(HELPER_SOCK):
        c = helper_connect(args, timeout)
        if data: c.sendall(data)
        c.shutdown(1)
        buf = b""
        while True:
            b = c.recv(65536)
            if not b: break
            buf += b
        c.close()
        try: r = json.loads(buf); rc, so, se = r["rc"], r["stdout"], r["stderr"]
        except Exception: return 1, {"error": "helper failed"}
    else:
        return 1, {"error": "the Nova helper service isn't running (systemctl status nova-helper.socket)"}
    try: return rc, json.loads(so or "{}")
    except Exception: return rc, {"error": (se or so)[:200]}

if os.path.exists(CONFIG) and not os.access(CONFIG, os.R_OK):
    # Running with an empty config would silently break remote access and auth checks.
    sys.exit(f"cannot read {CONFIG} — it must be root:nova-api, mode 640 (sudo chown root:nova-api {CONFIG}; sudo chmod 640 {CONFIG})")
CFG = load_json(CONFIG, {})
# Everything site-specific comes from config.json (defaults match the original install).
# No config yet (fresh install before `nova-setup`): only localhost, nothing on the network.
LAN = ipaddress.ip_network(CFG.get("lan_subnet") or "127.0.0.1/32")
LAN_HOST = CFG.get("lan_host") or "127.0.0.1"
# Optional: a Tailscale/VPN network whose devices may use Nova (signed requests only — never pairing).
TAILNET = ipaddress.ip_network(CFG["tailnet_subnet"]) if CFG.get("tailnet_subnet") else None
TAILNET_HOST = CFG.get("tailnet_host", "")
SSH_USER = CFG.get("ssh_user", "")
SSH_HOSTS = CFG.get("ssh_hosts", [{"name": "Home network", "host": LAN_HOST, "port": 22}])
def board_name():
    try:
        v = open("/sys/class/dmi/id/board_vendor").read().strip(); n = open("/sys/class/dmi/id/board_name").read().strip()
        return f"{v.split()[0].title() if v else ''} {n}".strip()
    except OSError: return ""

# ── background jobs (installs, updates — anything slower than a request) ──────────
jobs = {}
def start_job(title, args, timeout=3600):
    jid = secrets.token_urlsafe(8)
    jobs[jid] = {"id": jid, "title": title, "state": "running", "started": time.time()}
    def work():
        try:
            rc, res = helper(*args, timeout=timeout)
            jobs[jid].update(state="done" if rc == 0 and res.get("ok", True) else "failed", result=res)
        except Exception as e:
            jobs[jid].update(state="failed", result={"error": repr(e)[:200]})
        jobs[jid]["finished"] = time.time()
    threading.Thread(target=work, daemon=True).start()
    for k in sorted(jobs, key=lambda k: jobs[k]["started"])[:-30]: jobs.pop(k, None)
    return jobs[jid]

# ── interactive container shells ──────────────────────────────────────────────────
shells = {}
SHELL_IDLE, SHELL_MAX, SHELL_BUF = 600, 7200, 512 * 1024
def open_shell(dev_id, name, verb=("shell",)):
    if sum(1 for x in shells.values() if x["alive"]) >= 4: raise ValueError("too many open shells")
    args = list(verb) + ([name] if name else [])
    if os.path.exists(HELPER_SOCK):
        c = helper_connect(args, None)
        read, write = (lambda: c.recv(4096)), c.sendall
        def kill():
            try: c.shutdown(2)
            except OSError: pass
            c.close()
    else:
        raise ValueError("the Nova helper service isn't running")
    sid = secrets.token_urlsafe(12)
    sh = {"id": sid, "device": dev_id, "container": name, "write": write, "kill": kill, "buf": bytearray(), "base": 0,
          "created": time.time(), "last": time.time(), "alive": True}
    def reader():
        while True:
            try: chunk = read()
            except OSError: chunk = b""
            if not chunk: break
            sh["buf"] += chunk
            if len(sh["buf"]) > SHELL_BUF:                 # keep the tail; remember how much we dropped
                drop = len(sh["buf"]) - SHELL_BUF; del sh["buf"][:drop]; sh["base"] += drop
        sh["alive"] = False
    threading.Thread(target=reader, daemon=True).start()
    shells[sid] = sh
    return sh

def reap_shells():
    while True:
        time.sleep(30); now = time.time()
        for sid, sh in list(shells.items()):
            if sh["alive"] and (now - sh["last"] > SHELL_IDLE or now - sh["created"] > SHELL_MAX):
                try: sh["kill"]()
                except Exception: pass
            if not sh["alive"] and now - sh["last"] > 300: shells.pop(sid, None)
threading.Thread(target=reap_shells, daemon=True).start()

# ── live stats for the status page: sampled every second; the last 3 minutes at 1 s and the
#    last hour at 15 s (averaged) are kept in memory ──────
STATS = {"history": [], "recent": [], "now": {}}
RECENT_N, HISTORY_N, HISTORY_EVERY = 180, 240, 15
def _hwmon(name, label=None):
    import glob as g
    for h in g.glob("/sys/class/hwmon/hwmon*"):
        try:
            if open(f"{h}/name").read().strip() != name: continue
            return int(open(f"{h}/temp1_input").read()) / 1000
        except Exception: pass
    return None

def _cpu_times():
    f = [int(x) for x in open("/proc/stat").readline().split()[1:]]
    return sum(f), f[3] + f[4]                        # total, idle+iowait

def _default_iface():
    try:
        for l in open("/proc/net/route").readlines()[1:]:
            f = l.split()
            if f[1] == "00000000": return f[0]
    except Exception: pass
    return "eth0"

def _net(dev=None):
    dev = dev or _default_iface()
    for l in open("/proc/net/dev"):
        if l.strip().startswith(dev + ":"):
            v = l.split(":", 1)[1].split(); return int(v[0]), int(v[8])
    return 0, 0

WHOLE_DISK = re.compile(r"(sd[a-z]+|nvme\d+n\d+|vd[a-z]+|xvd[a-z]+|hd[a-z]+|mmcblk\d+)")
def _disks():
    """Whole disks from /proc/diskstats: {name: (sectors read + written, ms spent doing I/O)}."""
    out = {}
    try:
        for l in open("/proc/diskstats"):
            f = l.split()
            if len(f) >= 13 and WHOLE_DISK.fullmatch(f[2]): out[f[2]] = (int(f[5]) + int(f[9]), int(f[12]))
    except (OSError, ValueError): pass
    return out

def stats_sampler():
    iface = _default_iface()
    prev_cpu, prev_net, prev_t = _cpu_times(), _net(iface), time.time()
    prev_disk = _disks()
    n = 0
    while True:
        time.sleep(1 - (time.time() % 1) + 0.02)         # on the second, so phones see an even beat
        n += 1
        if n % 300 == 0: iface = _default_iface()         # follow a changed network setup
        try:
            cpu, net, t = _cpu_times(), _net(iface), time.time()
            dt_all, dt_idle = cpu[0] - prev_cpu[0], cpu[1] - prev_cpu[1]
            m = {l.split(":")[0]: int(l.split()[1]) for l in open("/proc/meminfo")}
            now = {"t": round(t, 2), "cpu": round(100 * (1 - dt_idle / max(1, dt_all)), 1),
                   "mem": round(100 * (1 - m["MemAvailable"] / m["MemTotal"]), 1),
                   "mem_used_gb": round((m["MemTotal"] - m["MemAvailable"]) / 1048576, 1), "mem_total_gb": round(m["MemTotal"] / 1048576, 1),
                   "swap": round(100 * (1 - m["SwapFree"] / max(1, m["SwapTotal"])), 1) if m.get("SwapTotal") else 0,
                   "temp": _hwmon("k10temp"), "nvme_temp": _hwmon("nvme"),
                   "rx": round((net[0] - prev_net[0]) / (t - prev_t)), "tx": round((net[1] - prev_net[1]) / (t - prev_t)),
                   "load": float(open("/proc/loadavg").read().split()[0]), "cores": os.cpu_count(),
                   "uptime_s": int(float(open("/proc/uptime").read().split()[0]))}
            # per-drive activity for the drive lights: share of the last second spent on I/O, and bytes/s
            disk = _disks(); dt = max(0.2, t - prev_t)
            now["disks"] = {d: [round(min(1.0, max(0.0, (v[1] - prev_disk[d][1]) / (dt * 1000))), 2), round((v[0] - prev_disk[d][0]) * 512 / dt)]
                            for d, v in disk.items() if d in prev_disk}
            STATS["now"] = now
            point = {k: now[k] for k in ("t", "cpu", "mem", "temp", "rx", "tx")}
            STATS["recent"] = (STATS["recent"] + [point])[-RECENT_N:]
            if n % HISTORY_EVERY == 1:                    # one averaged point per 15 s for the hour view
                last = STATS["recent"][-HISTORY_EVERY:]
                avg = {k: round(sum((p[k] or 0) for p in last) / len(last), 1) for k in ("cpu", "mem", "rx", "tx")}
                avg["temp"] = point["temp"]; avg["t"] = int(t)
                STATS["history"] = (STATS["history"] + [avg])[-HISTORY_N:]
            prev_cpu, prev_net, prev_t, prev_disk = cpu, net, t, disk
        except Exception:
            pass
threading.Thread(target=stats_sampler, daemon=True).start()
nodes.start()                          # other Nova servers shown in this web (polled read-only)

WEB_DIR = next((d for d in (os.path.join(os.path.dirname(os.path.abspath(__file__)), "web"),
                             os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web")) if os.path.isdir(d)),
               os.path.join(HERE, "web"))
MIME = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
        ".svg": "image/svg+xml", ".png": "image/png", ".json": "application/json", ".webmanifest": "application/manifest+json"}

def der_sig(raw):
    """Web Crypto ECDSA signatures are r||s (64 bytes); convert to DER for `cryptography`."""
    if len(raw) == 64:
        from cryptography.hazmat.primitives.asymmetric.utils import encode_dss_signature
        return encode_dss_signature(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"))
    return raw

# ── browsers: pairing requests (approved from a phone) and approvals for risky actions ──
browser_requests = {}      # id -> {pem, name, code, expires, state, device_id}
approvals = {}             # id -> {id, device, device_name, method, path, data, created, state, result}

def new_approval(dev, method, path, data):
    aid = secrets.token_urlsafe(9)
    approvals[aid] = {"id": aid, "device": dev["id"], "device_name": dev["name"], "user": dev.get("user", ""),
                      "method": method, "path": path, "data": data, "created": time.time(), "state": "pending",
                      "code": "".join(secrets.choice(CODE_ALPHABET) for _ in range(6))}
    for k in [k for k, v in approvals.items() if time.time() - v["created"] > 3600]: approvals.pop(k, None)
    write_pending()
    return approvals[aid]

def write_pending():
    """What's waiting (browser pairing codes, browser actions held for approval) — so `sudo nova approve`
    can list it. In the API's own 0700 data folder, mode 0600: only root and nova-api can read it."""
    now = time.time()
    try:
        save_json(PENDING, {"t": now,
            "browsers": [{"code": v["code"], "name": v["name"], "from": v["from"], "expires": v["expires"]}
                         for v in browser_requests.values() if v["state"] == "pending" and v["expires"] > now],
            "approvals": [{"code": a["code"], "what": describe_action(a), "device": a["device_name"], "expires": a["created"] + 600}
                          for a in approvals.values() if a["state"] == "pending" and now - a["created"] < 600]})
        os.chmod(PENDING, 0o600)
    except Exception: pass

def shell_approval(code):
    """The root-written one-time approval for this code, if there is one (and use it up)."""
    try:
        st = os.stat(SHELL_APPROVE)
        if st.st_uid != 0 or st.st_mode & 0o022: os.remove(SHELL_APPROVE); return None      # only root may write it
        ap = json.load(open(SHELL_APPROVE))
    except (OSError, ValueError): return None
    if time.time() - ap.get("t", 0) > 120:
        try: os.remove(SHELL_APPROVE)
        except OSError: pass
        return None
    if not hmac.compare_digest(str(ap.get("code", "")).strip().upper(), code): return None
    try: os.remove(SHELL_APPROVE)
    except OSError: return None                      # someone else used it first
    return ap

def shell_account():
    """Name of the normal account the terminal and file manager use (for approval texts)."""
    if CFG.get("shell_user"): return CFG["shell_user"]
    try:
        import pwd
        ok = set(l.strip() for l in open("/etc/shells") if l.startswith("/"))
        people = [u.pw_name for u in pwd.getpwall() if 1000 <= u.pw_uid < 60000 and u.pw_shell in ok]
        return people[0] if len(people) == 1 else "your account"
    except Exception: return "your account"

def describe_action(a):
    p = [x for x in a["path"].split("/") if x][2:]
    if p == ["labs", "images", "clean"]: return "Remove the container images nothing uses anymore (the versions kept for rolling back stay)"
    if p == ["terminal"]: return f"Open a terminal on the server as {shell_account()} — that's full control if the account can use sudo or Docker"
    if p == ["files", "unlock"]: return f"Let this browser change files on the server as {shell_account()} for 15 minutes"
    if p == ["cloudflare", "publish"]: return f"Put {a.get('data', {}).get('host', '?')} on the internet through your Cloudflare tunnel (behind your Access login)"
    if p == ["cloudflare", "remove"]: return f"Take {a.get('data', {}).get('host', '?')} off your Cloudflare tunnel"
    if p == ["cloudflare", "token"]: return "Save a Cloudflare API token on the server"
    if p == ["apps", "command"]: return f"Save the command app “{a.get('data', {}).get('name', '?')}”: {str(a.get('data', {}).get('command', ''))[:80]}"
    if p[:1] == ["apps"] and p[-1:] == ["run"]: return "Run a command app"
    if p == ["containers", "custom"]: return f"Add your own container “{a.get('data', {}).get('spec', {}).get('name', '?')}” ({a.get('data', {}).get('spec', {}).get('image', '?')})"
    if p[:1] == ["containers"] and p[-1:] == ["remove-custom"]: return f"Remove the container {p[1]} (its data is kept)"
    if p[:1] == ["containers"] and len(p) == 4 and p[2] == "fix": return {"kill": f"Force-stop {p[1]}", "recreate": f"Recreate {p[1]}", "rollback": f"Put {p[1]} back on its previous version"}.get(p[3], "Fix a container")
    if p[:1] == ["containers"] and len(p) == 3: return f"{p[2].title()} the container {p[1]}"
    if p[:1] == ["store"]: return f"{p[2].title()} {p[1]} from the app store"
    if p[:1] == ["programs"]: return f"{p[2].title()} the program {p[1]}"
    if p[:1] == ["drives"]: return f"{p[2].title()} a drive"
    if p[:1] == ["power"]: return "Restart the server" if p[1:] == ["reboot"] else "Shut down the server"
    if p[:1] == ["devices"] and a.get("method") == "DELETE" and len(p) == 2:
        d = load_json(DEVICES, {}).get(p[1], {})
        return f"Remove “{d.get('name', 'a device')}” from this server" if d else "Remove a device"
    if p[:1] == ["devices"]: return "Change paired devices"
    if p == ["nodes"]: return f"Show the server at {a.get('data', {}).get('host', '?')} in this Nova web"
    if p[:1] == ["nodes"]: return "Remove a server from this Nova web"
    if p[:1] == ["ssh"]: return "Let this phone log in over SSH"
    if p == ["alerts", "dismiss"]: return f"Ignore the alert “{a.get('data', {}).get('key', '')}”"
    if p == ["events", "delete"]: return "Clear the whole inbox" if a.get("data", {}).get("all") else "Delete from the inbox"
    if p[:2] == ["storage", "task"] and len(p) == 3:
        d = a.get("data", {}).get("spec", {}); n = len(d.get("drives") or [])
        return {"format": f"Erase and set up {n} drive", "combine": f"Combine drives into “{d.get('name', '')}”" + (f" (erases {n})" if n else ""),
                "raid": f"Create {str(d.get('level', 'RAID')).upper()} “{d.get('name', '')}” (erases {n} drives)", "pool-remove": "Remove a storage pool" + (" and erase its drives" if d.get("erase") else ""),
                "pool-add": "Add a drive to a pool (erases it)"}.get(p[2], "Change storage")
    if p == ["updates", "packages"]: return "Update system packages"
    if p == ["notify", "discord"]: return "Change where Discord alerts go"
    if p == ["updates", "containers"]: return "Update containers"
    if p[:1] == ["backups"] and p[-1:] == ["restore"]: return f"Restore {a.get('data', {}).get('path', 'files')} from a backup"
    return f"{a['method']} {a['path']}"

def browser_forbidden(method, parts, dev):
    """Device management and approvals are for phones only (their keys live in hardware)."""
    if parts[:1] == ["approvals"] and method == "POST": return True
    if parts[:1] == ["browser"]: return True
    if parts == ["server", "update"] and method == "POST": return True          # updates: phones only
    if parts[:1] == ["ssh"] and method != "GET": return True                      # SSH keys: phones only
    # Removing a device is allowed from a browser, but it's held for an admin phone's fingerprint (needs_stepup).
    if parts[:1] == ["devices"] and method == "DELETE" and len(parts) == 2: return False
    if parts[:1] == ["devices"] and method != "GET" and parts != ["devices", dev.get("id")]: return True
    return False

STORAGE_DESTRUCTIVE = ("format", "combine", "raid", "pool-remove", "pool-add")
ROLES = ("admin", "viewer")
FORMS = ("phone", "tablet", "desktop", "watch")
def role_of(d): return d.get("role") if d.get("role") in ROLES else "admin"     # devices from before roles: admin

def viewer_may(method, parts, dev):
    """View-only devices: read anything, change nothing — except their own housekeeping."""
    if method == "GET": return True
    if method == "DELETE" and parts == ["devices", dev.get("id")]: return True     # unpair itself
    if method == "POST" and parts in (["crash"], ["device", "stepup-key"], ["device", "form"]): return True
    return False

def basic_status():
    """Status for servers without the optional monitor module: just the essentials, always 'ok'
    unless something is obviously wrong (disk nearly full, CPU very hot)."""
    import shutil as sh
    n = STATS.get("now") or {}
    du = sh.disk_usage("/"); used = round(100 * du.used / du.total)
    hot = (n.get("temp") or 0) >= 90
    level = "warning" if used >= 95 or hot else "ok"
    head = "System drive almost full" if used >= 95 else "CPU is very hot" if hot else "All systems normal"
    up = n.get("uptime_s") or int(float(open("/proc/uptime").read().split()[0]))
    return {"level": level, "headline": head, "active_count": 0 if level == "ok" else 1,
            "active": [] if level == "ok" else [{"level": level, "title": head, "detail": "", "since": ""}],
            "updated_local": time.strftime("%Y-%m-%d %H:%M:%S"),
            "metrics": {k: v for k, v in {
                "cpu_temp": f"{n['temp']:.0f}°C" if n.get("temp") else None,
                "memory": f"{n['mem']:.0f}% used" if n.get("mem") is not None else None,
                "load": f"{n.get('load')} / {n.get('cores')} cores" if n.get("load") is not None else None,
                "uptime": f"{up // 86400}d {up % 86400 // 3600}h",
                "root_used": f"{used}% ({du.free / 1e9:.0f} GB free)"}.items() if v is not None}}

def browser_needs_phone(method, parts):
    """Harmless from a phone (hardware key, one swipe), but from a browser they could hide what
    happened — silence an alert, erase the login history — or (files) act as your account, so a browser asks a phone first."""
    return method == "POST" and parts in (["alerts", "dismiss"], ["files", "unlock"])

# Actions that need the fingerprint-bound step-up key (second signature).
def needs_stepup(method, parts):
    if method == "DELETE" and parts[:1] == ["devices"]: return True
    if method == "POST" and parts == ["devices", "remove-all"]: return True
    if method == "POST" and parts == ["devices", "watch"]: return True
    if method == "POST" and parts == ["nodes"]: return True                        # add a server here
    if method == "DELETE" and parts[:1] == ["nodes"]: return True
    if method == "POST" and parts == ["browser", "approve"]: return True
    if method == "POST" and parts == ["server", "update"]: return True
    if method == "POST" and parts[:1] == ["approvals"] and parts[-1:] == ["approve"]: return True
    if method == "POST" and parts[:1] == ["devices"] and parts[-1:] in (["access"], ["invite"]): return True
    if method != "POST": return False
    if parts[:1] == ["containers"] and len(parts) == 3 and parts[2] in ("stop", "restart", "shell", "policy"): return True
    if parts[:1] == ["containers"] and len(parts) == 4 and parts[2] == "fix": return True
    if parts[:1] in (["store"], ["programs"]) and len(parts) == 3: return True
    if parts[:1] == ["drives"] and parts[-1:] == ["unmount"]: return True
    if parts[:1] == ["power"]: return True
    if parts == ["ssh", "authorize"]: return True
    if parts[:2] == ["storage", "task"] and len(parts) == 3 and parts[2] in STORAGE_DESTRUCTIVE: return True
    if parts in (["updates", "packages"], ["updates", "containers"]): return True
    if parts == ["containers", "custom"]: return True                      # new container: fingerprint
    if parts == ["terminal"]: return True                                   # server terminal: fingerprint
    if parts == ["apps", "command"]: return True                           # what a command app runs: fingerprint
    if parts in (["cloudflare", "token"], ["cloudflare", "publish"], ["cloudflare", "remove"]): return True     # changes your Cloudflare account
    if parts[:1] == ["containers"] and parts[-1:] == ["remove-custom"]: return True
    if parts == ["labs", "images", "clean"]: return True                    # deletes images: fingerprint
    if parts == ["notify", "discord"]: return True                         # where alerts get sent: fingerprint
    if parts[:1] == ["backups"] and len(parts) == 3 and parts[2] == "restore": return True
    return False

# ── Cloudflare Access JWT (remote requests) ─────────────────────────────────────
_jwks = {"t": 0, "keys": {}}
def _b64u(s): return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

def access_jwt_ok(token):
    team, aud = CFG.get("cf_access_team", "").removeprefix("https://").removesuffix("/").removesuffix(".cloudflareaccess.com"), CFG.get("cf_access_aud")
    if not (team and aud and token): return False
    try:
        h64, p64, s64 = token.split(".")
        head, claims = json.loads(_b64u(h64)), json.loads(_b64u(p64))
        if head.get("alg") != "RS256": return False
        stale = time.time() - _jwks["t"] > 3600
        if stale or (head.get("kid") not in _jwks["keys"] and time.time() - _jwks["t"] > 60):   # unknown kid: refetch at most once a minute
            with urllib.request.urlopen(f"https://{team}.cloudflareaccess.com/cdn-cgi/access/certs", timeout=10) as r:
                ks = json.load(r)["keys"]
            _jwks["keys"] = {k["kid"]: rsa.RSAPublicNumbers(int.from_bytes(_b64u(k["e"]), "big"),
                                                            int.from_bytes(_b64u(k["n"]), "big")).public_key()
                             for k in ks if k.get("kty") == "RSA"}
            _jwks["t"] = time.time()
        key = _jwks["keys"].get(head.get("kid"))
        if not key: return False
        key.verify(_b64u(s64), f"{h64}.{p64}".encode(), padding.PKCS1v15(), hashes.SHA256())
        auds = claims.get("aud", []); auds = [auds] if isinstance(auds, str) else auds
        now = time.time()
        return aud in auds and claims.get("exp", 0) > now and claims.get("nbf", 0) <= now + 60 \
            and claims.get("iss") == f"https://{team}.cloudflareaccess.com"
    except Exception:
        return False


class Handler(BaseHTTPRequestHandler):
    server_version = "nova"
    sys_version = ""
    timeout = 30                     # a client that stops sending mid-request is dropped (slowloris)

    def log_message(self, *a): pass

    # ── plumbing ──
    def send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, code, data, ctype):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def origin(self):
        """('lan'|'local'|'remote'|None, client_ip)"""
        peer = ipaddress.ip_address(self.client_address[0])
        if peer in LAN: return "lan", str(peer)
        if TAILNET and peer in TAILNET: return "tailnet", str(peer)
        if peer.is_loopback:
            if self.headers.get("Cf-Ray"):            # came through the Cloudflare tunnel
                return "remote", self.headers.get("Cf-Connecting-IP", "?")
            return "local", str(peer)
        return None, str(peer)

    def throttled(self, ip):
        now = time.time()
        if len(fails) > 5000:                                   # keep the table bounded
            for k in [k for k, v in fails.items() if not v or now - v[-1] > 600]: fails.pop(k, None)
        fails[ip] = [t for t in fails.get(ip, []) if now - t < 600]
        return len(fails[ip]) >= 20

    def fail(self, ip, code, why):
        fails.setdefault(ip, []).append(time.time())
        audit(ip=ip, path=self.path, result=code, why=why)
        self.send(code, {"error": "unauthorized" if code == 401 else why})

    def authenticate(self, body):
        """Returns device dict or None (and has already responded)."""
        where, ip = self.origin()
        if where is None: self.fail(ip, 403, "not from LAN or Cloudflare"); return None
        if self.throttled(ip): self.send(429, {"error": "too many failed attempts"}); return None
        if where == "remote" and not access_jwt_ok(self.headers.get("Cf-Access-Jwt-Assertion")):
            self.fail(ip, 401, "missing/invalid Cloudflare Access token"); return None
        dev_id = self.headers.get("X-Nova-Device", "")
        ts, nonce = self.headers.get("X-Nova-Time", ""), self.headers.get("X-Nova-Nonce", "")
        sig = self.headers.get("X-Nova-Signature", "")
        dev = load_json(DEVICES, {}).get(dev_id)
        if not dev or dev.get("revoked"): self.fail(ip, 401, "unknown device"); return None
        try: ts_i = int(ts)
        except ValueError: self.fail(ip, 401, "bad time"); return None
        if abs(time.time() * 1000 - ts_i) > CLOCK_SKEW_MS: self.fail(ip, 401, "clock skew"); return None
        if not (16 <= len(nonce) <= 64) or not nonce.replace("-", "").replace("_", "").isalnum():
            self.fail(ip, 401, "bad nonce"); return None
        now = time.time()
        for k in [k for k, exp in nonces.items() if exp < now]: nonces.pop(k, None)
        if nonce in nonces: self.fail(ip, 401, "replayed request"); return None
        msg = "\n".join([self.command, self.path, ts, nonce, hashlib.sha256(body).hexdigest()]).encode()
        try:
            pub = serialization.load_pem_public_key(dev["public_key"].encode())
            pub.verify(der_sig(base64.b64decode(sig)), msg, ec.ECDSA(hashes.SHA256()))
        except (InvalidSignature, ValueError, TypeError):
            self.fail(ip, 401, "bad signature"); return None
        dev["stepup_ok"] = False
        su = self.headers.get("X-Nova-StepUp", "")
        if su and dev.get("stepup_key"):
            try:
                serialization.load_pem_public_key(dev["stepup_key"].encode()).verify(
                    base64.b64decode(su), msg, ec.ECDSA(hashes.SHA256()))
                dev["stepup_ok"] = True
            except (InvalidSignature, ValueError, TypeError):
                self.fail(ip, 401, "bad step-up signature"); return None
        nonces[nonce] = now + 2 * CLOCK_SKEW_MS / 1000
        with lock:
            devs = load_json(DEVICES, {})
            seen = time.strftime("%Y-%m-%d %H:%M")
            if dev_id in devs and (devs[dev_id].get("last_seen"), devs[dev_id].get("last_via")) != (seen, where):
                devs[dev_id].update(last_seen=seen, last_via=where)       # at most one write a minute
                save_json(DEVICES, devs)
        dev["id"], dev["via"], dev["ip"] = dev_id, where, ip
        return dev

    def read_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw_ok = self.path.split("?", 1)[0] in RAW_BODY_PATHS          # file uploads: raw bytes, bigger chunks
        if n > (UPLOAD_CHUNK if raw_ok else MAX_BODY): return None
        return self.rfile.read(n) if n else b""

    # ── routing ──
    def do_GET(self): self.route("GET")
    def do_POST(self): self.route("POST")
    def do_DELETE(self): self.route("DELETE")

    def route(self, method):
        body = self.read_body()
        if body is None: return self.send(413, {"error": "body too large"})
        path = self.path.split("?", 1)[0]

        if method == "GET" and (path == "/" or path.startswith("/web/")):
            return self.static(path)
        if path.startswith("/api/v1/browser/request"):
            return self.browser_request(method, path, body)
        if method == "GET" and path == "/api/v1/ping":
            return self.send(200, {"ok": True, "name": "Nova", "api": API_VERSION})
        if method == "POST" and path == "/api/v1/pair":
            return self.pair(body)
        if method == "POST" and path == "/api/v1/crash" and not self.headers.get("X-Nova-Device"):
            where, ip = self.origin()
            if where not in ("lan", "local") or self.throttled(ip): return self.send(403, {"error": "no"})
            fails.setdefault(ip, []).append(time.time())      # rate-limit anonymous reports
            return self.send(200, self.save_crash("unpaired", body))

        dev = self.authenticate(body)
        if not dev: return
        if path in RAW_BODY_PATHS: data = {"_raw": True}
        else:
            try: data = json.loads(body) if body else {}
            except ValueError: return self.send(400, {"error": "invalid JSON"})
        if not isinstance(data, dict): return self.send(400, {"error": "expected an object"})

        parts = [p for p in path.split("/") if p][2:]
        if dev.get("type") == "head":
            # Another Nova server's web showing this one: it reads the overview, live numbers and events,
            # and can unpair itself. Nothing else — no changes, no devices, no logs, no approvals.
            ok = (method == "GET" and parts in (["whoami"], ["overview"], ["stats"], ["events"])) \
                or (method == "DELETE" and parts == ["devices", dev.get("id")])
            if not ok:
                audit(device=dev["name"], path=path, result=403, why="head is read-only")
                return self.send(403, {"error": "read_only_head"})
        if dev.get("type") == "watch":
            # A watch is an approver and nothing else: it lists what's waiting and approves or denies it.
            # Its key lives in the watch's hardware and only works while the watch is unlocked (on the wrist),
            # so its signature counts as the fingerprint step for approving.
            ok = (method == "GET" and (parts in (["whoami"], ["approvals"]) or (len(parts) == 2 and parts[0] == "approvals"))) \
                or (method == "POST" and len(parts) == 3 and parts[0] == "approvals" and parts[2] in ("approve", "deny"))
            owner = load_json(DEVICES, {}).get(dev.get("owner", ""))
            if not owner or role_of(owner) != "admin":           # a watch can only do what its (admin) phone can
                return self.send(403, {"error": "this watch's phone isn't an admin any more"})
            if not ok:
                audit(device=dev["name"], path=path, result=403, why="watches only approve")
                return self.send(403, {"error": "watch_only_approves"})
            dev["stepup_ok"] = True
        if dev.get("type") == "browser" and browser_forbidden(method, parts, dev):
            audit(device=dev["name"], path=path, result=403, why="phones only")
            return self.send(403, {"error": "phones_only", "message": "Do this from the Nova app on an admin phone."})
        if dev.get("type") in ("browser", "head") and method == "DELETE" and parts == ["devices", dev["id"]]:
            with lock:
                devs = load_json(DEVICES, {}); devs.pop(dev["id"], None); save_json(DEVICES, devs)
            audit(device=dev["name"], path=path, result=200, why=f"{dev.get('type')} removed itself")
            return self.send(200, {"ok": True})
        if (needs_stepup(method, parts) or browser_needs_phone(method, parts)) and not dev.get("stepup_ok") and dev.get("type") == "browser" and role_of(dev) == "admin":
            try: data0 = json.loads(body) if body else {}
            except ValueError: data0 = {}
            a = new_approval(dev, method, path, data0)
            audit(device=dev["name"], path=path, result=202, why="sent to a phone for approval")
            return self.send(202, {"approval": a["id"], "code": a["code"], "message": "Approve this on your phone (Nova app), or on the server: sudo nova approve " + a["code"]})
        if needs_stepup(method, parts) and not dev.get("stepup_ok"):
            audit(device=dev["name"], path=path, result=403, why="step-up required")
            return self.send(403, {"error": "stepup_required",
                                   "message": "Confirm with your fingerprint to do this."
                                   if dev.get("stepup_key") else "Set up fingerprint confirmation first (home Wi-Fi)."})
        if role_of(dev) != "admin" and not viewer_may(method, parts, dev):
            audit(device=dev["name"], path=path, result=403, why="view-only")
            return self.send(403, {"error": "view_only", "message": "This phone has view-only access."})
        if method == "GET" and parts == ["diag", "blob"]:
            # This device ↔ server speed test: N MB of incompressible bytes, streamed.
            try: mb = max(1, min(200, int(urllib.parse.parse_qs(self.path.split("?", 1)[-1]).get("mb", ["20"])[0])))
            except ValueError: mb = 20
            chunk = os.urandom(1024 * 1024)
            self.send_response(200); self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(mb * len(chunk))); self.send_header("Cache-Control", "no-store"); self.end_headers()
            try:
                for _ in range(mb): self.wfile.write(chunk)
            except (BrokenPipeError, ConnectionResetError): pass
            return
        if parts[:1] == ["files"] and parts != ["files", "unlock"]:
            # the file manager — as your normal account on the server (files.py), admins only
            if role_of(dev) != "admin" or dev.get("type") in ("watch", "head"): return self.send(403, {"error": "admins only"})
            # A browser changes files only after a phone approved it (then for 15 minutes): files written as your
            # account can be as powerful as that account (~/.ssh, ~/.bashrc, ~/bin, a Docker-capable user…).
            try: op0 = json.loads(body or b"{}").get("op") if parts == ["files"] else None
            except (ValueError, AttributeError): op0 = None
            writing = method == "POST" and (parts != ["files"] or op0 != "read")
            if writing and dev.get("type") == "browser" and FILE_UNLOCK.get(dev["id"], 0) < time.time():
                return self.send(403, {"error": "files_locked", "message": "Approve file changes on your phone first (they're then allowed for 15 minutes)."})
            fq = {k: v[0] for k, v in urllib.parse.parse_qs(self.path.split("?", 1)[1] if "?" in self.path else "").items()}
            def reply(rc, res): return self.send(200 if rc == 0 else 400, res)
            if method == "GET" and parts == ["files"]:
                return reply(*helper("files", json.dumps({"op": "list", "path": fq.get("path", "")}), timeout=60))
            if method == "POST" and parts == ["files"]:
                try: a = json.loads(body or b"{}")
                except ValueError: return self.send(400, {"error": "invalid JSON"})
                if not isinstance(a, dict) or a.get("op") not in ("mkdir", "new", "rename", "copy", "trash", "read"): return self.send(400, {"error": "unknown operation"})
                rc, res = helper("files", json.dumps(a)[:7000], timeout=600)
                if rc == 0 and a["op"] != "read": audit(device=dev["name"], path=path, result=200, files=a["op"], target=str(a.get("path") or a.get("paths"))[:200])
                return reply(rc, res)
            if method == "POST" and parts in (["files", "upload"], ["files", "save"]):
                spec = {"op": "put" if parts[1] == "upload" else "write", "path": fq.get("path", ""), "name": fq.get("name", ""),
                        "offset": int(fq.get("offset", "0") or 0), "last": fq.get("last") == "1", "replace": fq.get("replace") == "1", "mtime": int(fq.get("mtime", "0") or 0)}
                rc, res = helper("files-data", json.dumps(spec), timeout=300, data=body or b"")
                if rc == 0 and (spec["op"] == "write" or res.get("done")): audit(device=dev["name"], path=path, result=200, files=spec["op"], target=(spec["path"] + "/" + spec["name"])[:200])
                return reply(rc, res)
            if method == "GET" and parts == ["files", "download"]:
                c = helper_connect(["files-get", fq.get("path", "")], 120); c.shutdown(1)
                head = b""
                while not head.endswith(b"\n") and len(head) < 4096:
                    b = c.recv(1)
                    if not b: break
                    head += b
                try: h = json.loads(head)
                except ValueError: c.close(); return self.send(502, {"error": "the helper didn't answer"})
                if "error" in h: c.close(); return self.send(400, h)
                fn = urllib.parse.quote(h["name"])
                self.send_response(200); self.send_header("Content-Type", h["mime"] if fq.get("inline") == "1" and h["mime"].startswith(("image/", "text/plain", "video/", "audio/", "application/pdf")) else "application/octet-stream")
                self.send_header("Content-Length", str(h["size"])); self.send_header("Cache-Control", "no-store")
                self.send_header("Content-Disposition", f"{'inline' if fq.get('inline') == '1' else 'attachment'}; filename*=UTF-8''{fn}"); self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                try:
                    while True:
                        b = c.recv(1 << 16)
                        if not b: break
                        self.wfile.write(b)
                except (BrokenPipeError, ConnectionResetError): pass
                c.close(); return
            return self.send(404, {"error": "no such endpoint"})
        if method == "GET" and len(parts) == 3 and parts[0] == "apps" and parts[2] == "icon":
            png = app_icon(parts[1])
            if not png: return self.send(404, {"error": "no icon"})
            self.send_response(200); self.send_header("Content-Type", "image/png"); self.send_header("Content-Length", str(len(png)))
            self.send_header("Cache-Control", "private, max-age=86400"); self.end_headers(); self.wfile.write(png)
            return
        if method == "GET" and parts == ["app", "apk"]:
            meta = load_json(f"{APK_DIR}/latest.json", {})
            f = os.path.join(APK_DIR, os.path.basename(meta.get("file", "")))
            if not meta or not os.path.isfile(f): return self.send(404, {"error": "no update published"})
            audit(device=dev["name"], via=dev["via"], path=path, result=200)
            return self.send_bytes(200, open(f, "rb").read(), "application/vnd.android.package-archive")
        try:
            code, res = self.dispatch(method, path, data, dev)
        except ValueError as e:
            code, res = 400, {"error": str(e)}
        except Exception as e:
            code, res = 500, {"error": "server error"}
            audit(device=dev["name"], path=path, result=500, error=repr(e)[:200])
        if (method != "GET" or code >= 400) and not path.startswith("/api/v1/shell/"):
            logged = {k: (str(v)[:200] if k != "report" else f"<{len(str(v))} bytes>") for k, v in data.items()}
            audit(device=dev["name"], via=dev["via"], ip=dev["ip"], method=method, path=path,
                  data=logged, result=code, stepup=dev.get("stepup_ok", False))
        elif path.startswith("/api/v1/shell/") and method == "POST":
            audit(device=dev["name"], via=dev["via"], path=path, input=str(data.get("input", ""))[:200], result=code)
        self.send(code, res)

    def static(self, path):
        name = "index.html" if path == "/" else os.path.basename(path)
        f = os.path.join(WEB_DIR, name)
        if not os.path.isfile(f): return self.send(404, {"error": "not found"})
        where, ip = self.origin()
        if where is None: return self.send(403, {"error": "not from LAN or Cloudflare"})
        data = open(f, "rb").read()
        self.send_response(200)
        self.send_header("Content-Type", MIME.get(os.path.splitext(f)[1], "application/octet-stream"))
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=(self), payment=(), usb=(), interest-cohort=()")
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; style-src 'self'; style-src-attr 'unsafe-inline'; script-src 'self'; "
                         "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        self.end_headers(); self.wfile.write(data)

    def browser_request(self, method, path, body):
        """A browser asks to be added. Unauthenticated, so: rate-limited, short-lived, and it only
        becomes a device when an admin phone approves the code (with fingerprint)."""
        where, ip = self.origin()
        if where is None: return self.send(403, {"error": "not from LAN or Cloudflare"})
        if where == "remote" and not access_jwt_ok(self.headers.get("Cf-Access-Jwt-Assertion")):
            return self.fail(ip, 401, "missing/invalid Cloudflare Access token")
        if self.throttled(ip): return self.send(429, {"error": "too many attempts"})
        now = time.time()
        for k in [k for k, v in browser_requests.items() if v["expires"] < now]: browser_requests.pop(k, None)
        if method == "POST" and path == "/api/v1/browser/request":
            fails.setdefault(ip, []).append(now)                      # counts toward the rate limit
            if sum(1 for v in browser_requests.values() if v["state"] == "pending") >= 10: return self.send(429, {"error": "too many pending requests"})
            try:
                d = json.loads(body); pem = d["public_key"]; name = "".join(c for c in str(d.get("name", "Browser")) if c.isprintable())[:40]
                pub = serialization.load_pem_public_key(pem.encode())
                if not isinstance(pub, ec.EllipticCurvePublicKey) or pub.curve.name != "secp256r1": raise ValueError
            except Exception: return self.send(400, {"error": "need a P-256 public_key"})
            rid = secrets.token_urlsafe(18)
            code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(6))
            browser_requests[rid] = {"pem": pem, "name": name or "Browser", "code": code, "expires": now + 600, "state": "pending",
                                     "from": ip, "via": where}
            write_pending()
            return self.send(200, {"id": rid, "code": code, "expires_in": 600})
        if method == "GET" and path.startswith("/api/v1/browser/request/"):
            rq = browser_requests.get(path.rsplit("/", 1)[1])
            if not rq: return self.send(404, {"state": "expired"})
            # Approved from the server's shell (`sudo nova approve CODE`): a one-time file in
            # Nova's private data folder, which only root or the service itself can write.
            ap = shell_approval(rq["code"]) if rq["state"] == "pending" and rq["expires"] > time.time() else None
            if ap:
                role = ap.get("role") if ap.get("role") in ROLES else "admin"
                dev_id = secrets.token_urlsafe(12)
                with lock:
                    devs = load_json(DEVICES, {})
                    devs[dev_id] = {"name": rq["name"][:40], "public_key": rq["pem"], "type": "browser", "created": time.strftime("%Y-%m-%d %H:%M"),
                                    "paired_from": rq["from"], "role": role, "approved_by": "shell",
                                    "user": "".join(c for c in str(ap.get("user", "")) if c.isprintable()).strip()[:40]}
                    save_json(DEVICES, devs)
                rq.update(state="approved", device_id=dev_id, role=role)
                audit(device=rq["name"], path="/api/v1/browser/approve", result=200, why="approved from the server shell")
                helper("notify-paired", "".join(c for c in rq["name"] if c.isalnum() or c in " -_")[:40] or "browser")
                write_pending()
            return self.send(200, {"state": rq["state"], "device_id": rq.get("device_id"), "role": rq.get("role"),
                                   "server": load_json(SETTINGS, {}).get("display_name") or os.uname().nodename})
        return self.send(404, {"error": "not found"})

    def save_crash(self, who, body):
        os.makedirs(CRASH_DIR, exist_ok=True)
        old = sorted(os.listdir(CRASH_DIR))
        for f in old[:-49]:                                     # keep the newest 50
            try: os.remove(os.path.join(CRASH_DIR, f))
            except OSError: pass
        try: d = json.loads(body); text = str(d.get("report", ""))[:60000]; ver = str(d.get("version", ""))[:20]
        except Exception: text, ver = body[:60000].decode(errors="replace"), ""
        fn = f"{CRASH_DIR}/{time.strftime('%Y%m%d-%H%M%S')}-{''.join(c for c in who if c.isalnum())[:20]}.txt"
        with open(fn, "w") as f: f.write(f"device: {who}\nversion: {ver}\n\n{text}")
        return {"ok": True}

    def run_approval(self, a, approver, dev):
        """Do what a browser asked for, now that a phone (fingerprint) or root (shell) approved it."""
        bdev = load_json(DEVICES, {}).get(a["device"])
        if not bdev: a["state"] = "denied"; write_pending(); return 404, {"error": "that browser was removed"}
        if role_of(bdev) != "admin": a["state"] = "denied"; write_pending(); return 403, {"error": "that browser is view-only now"}
        bdev = dict(bdev, id=a["device"], via=dev["via"], ip=dev["ip"], stepup_ok=True)
        a["state"] = "running"
        try:
            code, res = self.dispatch(a["method"], a["path"], a["data"], bdev)
        except ValueError as e: code, res = 400, {"error": str(e)}
        except Exception as e: code, res = 500, {"error": "server error"}; audit(path=a["path"], result=500, error=repr(e)[:200])
        a.update(state="done" if code < 400 else "failed", result=res, status=code, approved_by=approver)
        audit(device=approver, path=a["path"], result=code, approved_for=a["device_name"], stepup=True)
        write_pending()
        return code, res

    def dispatch(self, method, path, data, dev):
        parts = [p for p in path.split("/") if p][2:]       # after /api/v1
        q = dict(x.split("=", 1) for x in self.path.split("?", 1)[1].split("&") if "=" in x) if "?" in self.path else {}

        # ── identity / device ──
        if method == "GET" and parts == ["whoami"]:
            return 200, {"device": dev["name"], "via": dev["via"], "paired": dev.get("created"), "id": dev["id"],
                         "stepup": bool(dev.get("stepup_key")), "api": API_VERSION,
                         "role": role_of(dev), "user": dev.get("user", ""), "form": dev.get("form", "")}
        if method == "POST" and parts == ["device", "form"]:
            # What kind of device this is (phone, tablet or desktop) — only for showing the right icon and words.
            form = str(data.get("form", ""))
            if form not in FORMS: raise ValueError("form: phone, tablet or desktop")
            with lock:
                devs = load_json(DEVICES, {})
                if devs.get(dev["id"], {}).get("form") != form: devs[dev["id"]]["form"] = form; save_json(DEVICES, devs)
            return 200, {"ok": True, "form": form}
        if method == "POST" and parts == ["device", "stepup-key"]:
            if dev["via"] not in ("lan", "local"): return 403, {"error": "set this up on home Wi-Fi"}
            if dev.get("stepup_key"): return 409, {"error": "already set up (re-pair to replace it)"}
            pem = str(data.get("public_key", ""))
            pub = serialization.load_pem_public_key(pem.encode())
            if not isinstance(pub, ec.EllipticCurvePublicKey) or pub.curve.name != "secp256r1":
                raise ValueError("need a P-256 public key")
            with lock:
                devs = load_json(DEVICES, {}); devs[dev["id"]]["stepup_key"] = pem; save_json(DEVICES, devs)
            return 200, {"ok": True}
        if method == "GET" and parts == ["devices"]:
            return 200, {"devices": [{"id": k, "name": v["name"], "created": v.get("created"), "last_seen": v.get("last_seen"),
                                      "via": v.get("last_via"), "stepup": bool(v.get("stepup_key")), "current": k == dev["id"],
                                      "role": role_of(v), "user": v.get("user", ""), "type": v.get("type", "phone"),
                                      "form": v.get("form") or ("desktop" if v.get("type") == "browser" else "phone")}
                                     for k, v in load_json(DEVICES, {}).items()]}
        if method == "POST" and parts == ["devices", "watch"]:
            # Fingerprint-confirmed, from an admin phone: register the watch it's paired with as an approver.
            if dev.get("type") in ("browser", "watch") or role_of(dev) != "admin": return 403, {"error": "set up a watch from an admin phone"}
            pem = str(data.get("public_key", ""))
            pub = serialization.load_pem_public_key(pem.encode())
            if not isinstance(pub, ec.EllipticCurvePublicKey) or pub.curve.name != "secp256r1": raise ValueError("need a P-256 public key")
            name = "".join(c for c in str(data.get("name", "Watch")) if c.isprintable()).strip()[:40] or "Watch"
            wid = secrets.token_urlsafe(12)
            with lock:
                devs = load_json(DEVICES, {})
                for k in [k for k, v in devs.items() if v.get("owner") == dev["id"]]: devs.pop(k)        # one watch per phone
                devs[wid] = {"name": name, "public_key": pem, "type": "watch", "form": "watch", "role": "admin", "owner": dev["id"],
                             "user": dev.get("user", ""), "created": time.strftime("%Y-%m-%d %H:%M")}
                save_json(DEVICES, devs)
            helper("notify-paired", "".join(c for c in name if c.isalnum() or c in " -_")[:40] or "watch")
            return 200, {"ok": True, "device_id": wid}
        if method == "POST" and parts == ["devices", "remove-all"]:
            # Fingerprint-confirmed. keep_self=true removes every *other* phone; false removes all, this one included.
            keep = bool(data.get("keep_self", True))
            with lock:
                devs = load_json(DEVICES, {})
                gone = [v["name"] for k, v in devs.items() if not (keep and k == dev["id"])]
                gone_ids = [k for k in devs if not (keep and k == dev["id"])]
                devs = {k: v for k, v in devs.items() if keep and (k == dev["id"] or v.get("owner") == dev["id"])}
                save_json(DEVICES, devs)
            for k in gone_ids: helper("ssh-unauthorize", k)
            helper("notify-revoked", str(len(gone)))
            return 200, {"ok": True, "revoked": gone, "kept_self": keep}
        if method == "POST" and len(parts) == 3 and parts[0] == "devices" and parts[2] == "access":
            # Fingerprint-confirmed: change who a device belongs to and what it may do.
            with lock:
                devs = load_json(DEVICES, {})
                d = devs.get(parts[1])
                if not d: return 404, {"error": "no such device"}
                if "role" in data:
                    if data["role"] not in ROLES: raise ValueError("role must be admin or viewer")
                    if data["role"] != "admin" and role_of(d) == "admin" and \
                            sum(1 for v in devs.values() if role_of(v) == "admin") <= 1:
                        raise ValueError("that's the last admin — make another device admin first")
                    d["role"] = data["role"]
                if "user" in data: d["user"] = "".join(c for c in str(data["user"]) if c.isprintable()).strip()[:40]
                save_json(DEVICES, devs)
            return 200, {"ok": True, "role": role_of(d), "user": d.get("user", "")}
        if method == "POST" and parts == ["devices", "invite"]:
            # Fingerprint-confirmed: a one-time pairing code (like `nova add`) for a new phone.
            role = data.get("role", "viewer")
            if role not in ROLES: raise ValueError("role must be admin or viewer")
            user = "".join(c for c in str(data.get("user", "")) if c.isprintable()).strip()[:40]
            code = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(10))
            with lock: save_json(PAIRING, {"code_sha256": hashlib.sha256(code.encode()).hexdigest(),
                                           "expires": time.time() + 600, "role": role, "user": user})
            tls = lan_tls()
            qr = json.dumps({"nova": 1, "lan": tls.get("url") or CFG.get("lan_url", ""), "pin": tls.get("pin", ""),
                             "remote": CFG.get("remote_url", ""), "code": code}, separators=(",", ":"))
            return 200, {"code": code, "qr": qr, "expires_in": 600, "role": role, "user": user,
                         "address": (tls.get("url") or "").split("//")[-1], "pin_short": tls.get("pin", "")[:12].upper()}
        if method == "POST" and len(parts) == 3 and parts[0] == "devices" and parts[2] == "rename":
            name = "".join(c for c in str(data.get("name", "")) if c.isprintable()).strip()[:40]
            if not name: raise ValueError("give it a name")
            with lock:
                devs = load_json(DEVICES, {})
                if parts[1] not in devs: return 404, {"error": "no such device"}
                devs[parts[1]]["name"] = name; save_json(DEVICES, devs)
            return 200, {"ok": True, "name": name}
        if parts == ["settings"]:
            cfg = load_json(SETTINGS, {})
            if method == "POST":
                if "display_name" in data:
                    v = "".join(c for c in str(data["display_name"]) if c.isprintable()).strip()[:40]
                    cfg["display_name"] = v
                if "accent" in data:
                    v = str(data["accent"])
                    if v and not (len(v) == 7 and v[0] == "#" and all(x in "0123456789abcdefABCDEF" for x in v[1:])): raise ValueError("accent must be #rrggbb")
                    cfg["accent"] = v.lower()
                if "location" in data: cfg["location"] = valid_location(data["location"])
                with lock: save_json(SETTINGS, cfg)
            return 200, {"display_name": cfg.get("display_name", ""), "accent": cfg.get("accent", ""), "hostname": os.uname().nodename,
                         "location": server_location()}
        if method == "DELETE" and len(parts) == 2 and parts[0] == "devices":
            with lock:
                devs = load_json(DEVICES, {}); gone = devs.pop(parts[1], None)
                for k in [k for k, v in devs.items() if v.get("owner") == parts[1]]: devs.pop(k)     # its watch goes with it
                save_json(DEVICES, devs)
            if not gone: return 404, {"error": "no such device"}
            helper("ssh-unauthorize", parts[1])
            return 200, {"ok": True, "revoked": gone["name"]}
        if method == "POST" and parts == ["browser", "approve"]:
            code = str(data.get("code", "")).strip().upper()
            rq = next((v for v in browser_requests.values() if v["state"] == "pending" and hmac.compare_digest(v["code"], code)), None)
            if not rq: raise ValueError("no browser is waiting with that code (they expire after 10 minutes)")
            role = data.get("role", "viewer")
            if role not in ROLES: raise ValueError("role must be admin or viewer")
            dev_id = secrets.token_urlsafe(12)
            with lock:
                devs = load_json(DEVICES, {})
                devs[dev_id] = {"name": str(data.get("name") or rq["name"])[:40], "public_key": rq["pem"], "type": "browser",
                                "created": time.strftime("%Y-%m-%d %H:%M"), "paired_from": rq["from"], "role": role,
                                "user": "".join(c for c in str(data.get("user", "")) if c.isprintable()).strip()[:40]}
                save_json(DEVICES, devs)
            rq.update(state="approved", device_id=dev_id, role=role)
            helper("notify-paired", "".join(c for c in rq["name"] if c.isalnum() or c in " -_")[:40] or "browser")
            return 200, {"ok": True, "device_id": dev_id}
        if method == "GET" and parts == ["approvals"]:
            mine = dev.get("type") == "browser"
            return 200, {"approvals": [{k: v for k, v in a.items() if k != "data"} | {"what": describe_action(a)}
                                       for a in sorted(approvals.values(), key=lambda a: -a["created"])
                                       if (a["device"] == dev["id"] if mine else role_of(dev) == "admin")
                                       and (a["state"] == "pending" or time.time() - a["created"] < 600)]}
        if len(parts) == 2 and parts[0] == "approvals" and method == "GET":
            a = approvals.get(parts[1])
            if not a or (a["device"] != dev["id"] and role_of(dev) != "admin"): return 404, {"error": "no such approval"}
            if a["state"] == "pending" and time.time() - a["created"] < 600 and shell_approval(a["code"]):
                self.run_approval(a, "the server shell (sudo nova approve)", dev)
            return 200, {k: v for k, v in a.items() if k != "data"} | {"what": describe_action(a)}
        if len(parts) == 3 and parts[0] == "approvals" and method == "POST" and parts[2] in ("approve", "deny"):
            # Only an admin *phone*, with its fingerprint key (step-up), can approve.
            a = approvals.get(parts[1])
            if not a or a["state"] != "pending": return 404, {"error": "nothing waiting with that id"}
            if time.time() - a["created"] > 600: a["state"] = "expired"; return 410, {"error": "that request expired"}
            if parts[2] == "deny": a["state"] = "denied"; write_pending(); return 200, {"ok": True}
            code, res = self.run_approval(a, dev["name"], dev)
            return 200, {"ok": code < 400, "result": res}
        if parts[:2] == ["server", "update"]:
            if method == "GET" and len(parts) == 2:
                rc, res = helper("update-status"); return 200, res
            if method == "POST" and parts[2:] == ["check"]:
                rc, res = helper("update-check", timeout=200); return (200 if rc == 0 else 400), res
            if method == "POST" and len(parts) == 2:
                rc, res = helper("update-start"); return (200 if rc == 0 else 500), res
        if method == "GET" and parts == ["lan-tls"]:
            return 200, lan_tls()
        if method == "GET" and parts == ["remote-config"]:
            # Home network only: the Cloudflare secret is never handed out over the internet.
            if dev["via"] not in ("lan", "local"): return 403, {"error": "only on the home network"}
            return 200, {"remote_url": CFG.get("remote_url", ""), "cf_client_id": CFG.get("cf_client_id", ""),
                         "cf_client_secret": CFG.get("cf_client_secret", "")}
        if method == "POST" and parts == ["crash"]:
            return 200, self.save_crash(dev["name"], json.dumps(data).encode())

        # ── overview / status / inbox ──
        if method == "GET" and parts == ["status"]:
            return 200, load_json(STATUS, {}) or basic_status()
        if method == "GET" and parts == ["overview"]:
            st = load_json(STATUS, {}) or basic_status()
            rc, cs = helper("containers"); cl = cs.get("containers", [])
            mem = {l.split(":")[0]: int(l.split()[1]) for l in open("/proc/meminfo") if l.split()[0][:-1] in ("MemTotal",)}
            cpu = next((l.split(":", 1)[1].strip() for l in open("/proc/cpuinfo") if l.startswith("model name")), "")
            fan = None
            if nova_rgb:
                with rgb_lock: fan = nova_rgb.load()
            cfg = load_json(SETTINGS, {})
            return 200, {"time": time.time(),             # lets apps line their fan animation up with the real fan
                         "server": {"name": os.uname().nodename, "display_name": cfg.get("display_name", ""),
                                    "accent": cfg.get("accent", ""), "board": board_name(), "cpu": cpu,
                                    "ram_gb": round(mem.get("MemTotal", 0) / 1048576), "kernel": os.uname().release,
                                    "uptime_s": int(float(open("/proc/uptime").read().split()[0]))},
                         "status": {k: st.get(k) for k in ("level", "headline", "status", "active_count", "active", "metrics", "updated_local")},
                         "fan": fan, "containers": {"running": sum(1 for c in cl if c["state"] == "running"), "total": len(cl)},
                         "tasks": [{"title": t.get("title", ""), "pct": t.get("pct", 0)} for t in live_tasks() if t.get("state") == "running"],
                         # What this server has, so the app only shows what works here.
                         "features": {"lighting": nova_rgb is not None, "monitor": os.path.exists(STATUS),
                                      "backup": True, "legacy_backup": os.path.exists("/usr/local/bin/nova-backup"), "storage": True, "diagnostics": True, "store": os.path.isdir(f"{os.path.dirname(os.path.abspath(__file__))}/store"),
                                      "ssh": bool(SSH_HOSTS), "lan_tls": True}}
        if method == "GET" and parts == ["events", "wait"]:
            # Long poll for the phone's instant alerts: return as soon as something newer than
            # `since` is written (nova-alerts runs every minute), or empty after `timeout` s.
            since = float(q.get("since", "0") or 0); end = time.time() + min(55, max(5, int(q.get("timeout", "50") or 50)))
            seen = set(str(q.get("seen", "")).split(",")); wait_started = time.time()
            while True:
                ev = [e for e in load_json(EVENTS, {}).get("events", []) if e.get("t", 0) > since]
                ap = [{"id": a["id"], "what": describe_action(a), "device_name": a["device_name"], "user": a.get("user", "")}
                      for a in approvals.values() if a["state"] == "pending" and role_of(dev) == "admin"
                      and dev.get("type") != "browser" and time.time() - a["created"] < 600]
                tk = live_tasks()
                if any(t["state"] == "running" for t in tk): end = min(end, wait_started + 20)  # progress for the notification, without waking the phone too often
                if ev or ("seen" in q and [a for a in ap if a["id"] not in seen]) or time.time() >= end:
                    return 200, {"events": ev[:50], "approvals": ap, "tasks": tk}
                time.sleep(2)
        if method == "GET" and parts == ["archive"]:
            # Permanent history (archived from the Inbox, or aged out of it). Readable by every device.
            try: before = float(q.get("before", "") or "inf")
            except ValueError: before = float("inf")
            try: limit = max(1, min(500, int(q.get("limit", "200") or 200)))
            except ValueError: limit = 200
            rows = []
            try:
                with open(os.path.join(os.path.dirname(EVENTS), "archive.jsonl")) as f:
                    for line in f:
                        try: e = json.loads(line)
                        except ValueError: continue
                        if e.get("t", 0) < before: rows.append(e)
            except FileNotFoundError: pass
            seen, out = set(), []
            for e in sorted(rows, key=lambda e: (-e["t"], not e.get("archived"))):    # an archived copy wins
                k = round(e["t"], 4)
                if k in seen: continue
                seen.add(k); out.append(e)
                if len(out) >= limit: break
            return 200, {"events": out, "more": len(out) == limit}
        if method == "GET" and parts == ["events"]:
            since = float(q.get("since", "0") or 0)
            ev = [e for e in load_json(EVENTS, {}).get("events", []) if e.get("t", 0) > since]
            return 200, {"events": ev[:200]}
        if method == "GET" and parts == ["notify", "discord"]:
            if role_of(dev) != "admin" or dev.get("type") == "watch": return 403, {"error": "admins only"}
            rc, res = helper("discord-status"); return (200 if rc == 0 else 502), res
        if method == "POST" and parts == ["notify", "discord"]:
            rc, res = helper("discord-set", str(data.get("webhook", ""))[:300]); return (200 if rc == 0 else 400), res
        if method == "POST" and parts == ["notify", "discord", "test"]:
            rc, res = helper("discord-test", timeout=30); return (200 if rc == 0 else 400), res
        if parts == ["notify"]:
            # Saved in our own data dir (nova-alerts reads it, allowlisted keys only) — the API
            # can't and shouldn't write /etc. Base values come from the alerts config.
            rc, base = helper("get-notify")
            app = load_json(NOTIFY_FILE, {})
            if method == "POST":
                for k, v in data.items():
                    allowed = NOTIFY_KEYS.get(k)
                    if allowed is None: raise ValueError(f"unknown setting {k}")
                    if allowed is bool and not isinstance(v, bool): raise ValueError(f"{k} must be true/false")
                    if isinstance(allowed, tuple) and v not in allowed: raise ValueError(f"{k} must be one of {', '.join(allowed)}")
                    app[k] = v
                with lock: save_json(NOTIFY_FILE, app)
            return 200, {**(base if rc == 0 else {}), **app}

        # ── fan light ──
        if parts[:1] == ["fan"] and not nova_rgb:
            return 404, {"error": "this server has no lighting module"}
        if parts == ["fan"]:
            with rgb_lock:
                st = nova_rgb.load()
                if method == "POST":
                    pid = data.pop("apply_preset", None) if isinstance(data, dict) else None
                    if isinstance(data, dict) and "location" in data:   # the server's location lives in its settings now
                        loc = valid_location(data.pop("location"))
                        with lock: cfg = load_json(SETTINGS, {}); cfg["location"] = loc; save_json(SETTINGS, cfg)
                        st["location"] = None
                    patch = nova_rgb.validate(data)
                    if pid is not None:                               # one tap: a saved look
                        pr = next((p for p in st.get("presets", []) if p["id"] == str(pid)), None)
                        if not pr: raise ValueError("no such preset")
                        patch = {**pr["set"], **patch}
                    st.update(patch)
                    look = getattr(nova_rgb, "LOOK", nova_rgb.SETTABLE)
                    if any(k in patch for k in look):                # a manual change wins over a schedule's fade
                        for r in (st.get("running") or {}).values(): r.pop("fade", None)
                        st["running"] = {k: v for k, v in (st.get("running") or {}).items() if v}
                    if any(k in patch for k in nova_rgb.SETTABLE):   # only touch the hardware when the look changes
                        nova_rgb.apply(st, nova_rgb.status_override(st))
                    nova_rgb.save(st)
                ex = nova_rgb.extras(st) if hasattr(nova_rgb, "extras") else {"effects": nova_rgb.EFFECT_NAMES}
                return 200, {**st, **ex, "location": server_location(), "status_override": nova_rgb.status_override(st)}

        # ── start page: web search suggestions (through the server, so the browser only talks to Nova) ──
        if method == "GET" and parts == ["suggest"]:
            text = urllib.parse.unquote_plus(q.get("q", ""))[:120].strip()
            if not text: return 200, {"suggestions": []}
            try:
                req = urllib.request.Request("https://duckduckgo.com/ac/?type=list&q=" + urllib.parse.quote(text), headers={"User-Agent": "Mozilla/5.0 (Nova)"})
                with urllib.request.urlopen(req, timeout=4) as r:
                    j = json.loads(r.read(64 * 1024))
                return 200, {"suggestions": [str(x)[:120] for x in (j[1] if isinstance(j, list) and len(j) > 1 else [])][:8]}
            except Exception:
                return 200, {"suggestions": []}

        # ── update center ──
        if method == "GET" and parts == ["updates"]:
            return 200, load_json(f"{DATA}/updates.json", {})
        if method == "POST" and parts == ["updates", "check"]:
            rc, res = helper("task-start", "updates-check", "{}"); return (200 if rc == 0 else 400), res
        if method == "POST" and parts == ["updates", "packages"]:
            pk = data.get("packages", "all")
            if pk != "all" and not (isinstance(pk, list) and 0 < len(pk) <= 500 and all(re.fullmatch(r"[a-z0-9][a-z0-9+.-]{0,100}", str(p)) for p in pk)):
                raise ValueError("packages: a list of package names, or \"all\"")
            rc, res = helper("task-start", "apt-upgrade", json.dumps({"packages": pk})); return (200 if rc == 0 else 400), res
        if method == "POST" and parts == ["updates", "containers"]:
            cs = data.get("containers", "all")
            if cs != "all" and not (isinstance(cs, list) and 0 < len(cs) <= 100 and all(re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", str(c)) for c in cs)): raise ValueError("containers: a list of names, or \"all\"")
            rc, res = helper("task-start", "containers-update", json.dumps({"containers": cs})); return (200 if rc == 0 else 400), res

        # ── apps (web apps on this server, for the Apps grid) ──
        # ── other Nova servers shown here (read-only, see nodes.py) ──
        if method == "GET" and parts == ["nodes"]:
            return 200, {"nodes": nodes.status()}
        if method == "POST" and parts == ["nodes", "probe"]:
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            return 200, nodes.probe(str(data.get("host", "")))
        if method == "POST" and parts == ["nodes"]:
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            head = load_json(SETTINGS, {}).get("display_name") or os.uname().nodename
            return 200, nodes.pair(str(data.get("host", "")), str(data.get("code", "")), str(data.get("pin", "")), head)
        if method == "DELETE" and len(parts) == 2 and parts[0] == "nodes" and re.fullmatch(r"[0-9a-f]{12}", parts[1]):
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            return (200, {"ok": True}) if nodes.remove(parts[1]) else (404, {"error": "no such server"})
        # ── Labs: experimental features, off until you turn them on ──
        if method == "GET" and parts == ["labs"]:
            return 200, {"labs": {k: bool(load_json(SETTINGS, {}).get("labs", {}).get(k)) for k in LABS}, "about": LABS}
        if method == "POST" and parts == ["labs"]:
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            with lock:
                st = load_json(SETTINGS, {}); lb = st.get("labs", {})
                for k, v in data.items():
                    if k in LABS: lb[k] = bool(v)
                st["labs"] = lb; save_json(SETTINGS, st)
            return 200, {"labs": {k: bool(lb.get(k)) for k in LABS}, "about": LABS}
        if parts[:1] == ["labs"] and len(parts) > 1:
            lb = load_json(SETTINGS, {}).get("labs", {})
            if role_of(dev) != "admin" and not (parts[1] == "wol" and (method == "GET" or parts[-1:] == ["wake"])): return 403, {"error": "admins only"}
            if parts == ["labs", "schedule"] and method in ("GET", "POST"):
                if method == "POST":
                    day, hour = data.get("day"), data.get("hour")
                    if not (isinstance(day, int) and 0 <= day <= 6 and isinstance(hour, int) and 0 <= hour <= 23): raise ValueError("a day (0 = Monday … 6 = Sunday) and an hour (0–23)")
                    with lock:
                        st = load_json(SETTINGS, {}); st.setdefault("labs_cfg", {})["auto_updates"] = {"day": day, "hour": hour}; save_json(SETTINGS, st)
                cfg = load_json(SETTINGS, {}).get("labs_cfg", {}).get("auto_updates", {}); ls = load_json(LABS_STATE, {})
                return 200, {"day": cfg.get("day", 6), "hour": cfg.get("hour", 4), "last": ls.get("auto_updates_last"), "error": ls.get("auto_updates_error")}
            if parts[1] == "images":
                if not lb.get("image_cleanup"): return 403, {"error": "turn on “Clean up old images” in Settings → Labs first"}
                if method == "GET" and len(parts) == 2: rc, res = helper("labs-images", timeout=120); return (200 if rc == 0 else 400), res
                if method == "POST" and parts == ["labs", "images", "clean"]:
                    rc, res = helper("task-start", "images-prune", "{}"); return (202 if rc == 0 else 400), ({"task": res.get("id"), **res} if rc == 0 else res)
            if parts[1] == "wol":
                if not lb.get("wake_on_lan"): return 403, {"error": "turn on Wake-on-LAN in Settings → Labs first"}
                lst = load_json(WOL, [])
                if method == "GET" and len(parts) == 2: return 200, {"devices": lst}
                if method == "POST" and len(parts) == 2:
                    name = "".join(c for c in str(data.get("name", "")) if c.isprintable()).strip()[:40]
                    mac = re.sub(r"[^0-9a-f]", "", str(data.get("mac", "")).lower())
                    if not name or len(mac) != 12: raise ValueError("a name and a MAC address like 3c:7c:3f:12:34:56")
                    if len(lst) >= 50: raise ValueError("that's a lot of computers — remove one first")
                    e = {"id": secrets.token_hex(4), "name": name, "mac": ":".join(mac[i:i + 2] for i in range(0, 12, 2))}
                    with lock: lst = load_json(WOL, []) + [e]; save_json(WOL, lst)
                    return 200, {"devices": lst}
                if len(parts) == 4 and re.fullmatch(r"[0-9a-f]{8}", parts[2]) and method == "POST":
                    e = next((x for x in lst if x["id"] == parts[2]), None)
                    if not e: return 404, {"error": "no such computer"}
                    if parts[3] == "remove":
                        with lock: lst = [x for x in load_json(WOL, []) if x["id"] != parts[2]]; save_json(WOL, lst)
                        return 200, {"devices": lst}
                    if parts[3] == "wake":
                        mac = bytes.fromhex(e["mac"].replace(":", "")); pkt = b"\xff" * 6 + mac * 16
                        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as so:
                            so.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                            for port in (9, 7): so.sendto(pkt, ("255.255.255.255", port))
                        return 200, {"ok": True, "note": f"Sent the wake-up to {e['name']}. It can take a minute to come on."}
            return 404, {"error": "no such endpoint"}
        if parts[:1] == ["cloudflare"]:
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            if not load_json(SETTINGS, {}).get("labs", {}).get("cloudflare_sync"): return 403, {"error": "turn on Cloudflare auto-setup in Settings → Labs first"}
            if method == "GET" and parts == ["cloudflare"]: rc, res = helper("cf-status", timeout=60); return (200 if rc == 0 else 400), res
            if method == "POST" and parts == ["cloudflare", "token"]: rc, res = helper("cf-token", str(data.get("token", ""))[:200], timeout=60); return (200 if rc == 0 else 400), res
            if method == "POST" and parts[1:] in (["plan"], ["publish"]):
                spec = json.dumps({"host": str(data.get("host", ""))[:253], "service": str(data.get("service", ""))[:200], "no_tls_verify": bool(data.get("no_tls_verify"))})
                rc, res = helper("cf-plan" if parts[1] == "plan" else "cf-apply", spec, timeout=120)
                if rc == 0 and parts[1] == "publish" and data.get("app"):
                    cfg = load_json(APPS, {"overrides": {}, "custom": []}); aid = str(data["app"])
                    if aid.startswith("custom-"): cfg["custom"] = [dict(c, remote_url=res["url"]) if c["id"] == aid else c for c in cfg.get("custom", [])]
                    else: cfg.setdefault("overrides", {}).setdefault(aid, {})["remote_url"] = res["url"]
                    save_json(APPS, cfg)
                return (200 if rc == 0 else 400), res
            if method == "POST" and parts == ["cloudflare", "remove"]: rc, res = helper("cf-remove", str(data.get("host", ""))[:253], timeout=120); return (200 if rc == 0 else 400), res
            return 404, {"error": "no such endpoint"}
        if method == "GET" and parts == ["apps"]:
            return 200, {"apps": app_list()}
        if method == "POST" and parts == ["apps", "command"]:
            # a command or script you run with one tap (as your normal account) — creating or changing one needs your fingerprint
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            cmd = str(data.get("command", "")).replace("\r", "")
            name = "".join(c for c in str(data.get("name", "")) if c.isprintable()).strip()[:40]
            if not name or not cmd.strip() or len(cmd) > 8000 or "\0" in cmd: raise ValueError("give it a name and a command")
            icon = str(data.get("icon", "")).strip().lower()[:60]
            if icon and not re.fullmatch(r"[a-z0-9-]{1,60}", icon): raise ValueError("icon: a dashboard-icons name like 'bash'")
            try: tmo = max(10, min(3600, int(data.get("timeout") or 600)))
            except (TypeError, ValueError): tmo = 600
            cfg = load_json(APPS, {"overrides": {}, "custom": []}); aid = str(data.get("id") or "")
            if aid:
                if not any(c["id"] == aid and c.get("command") for c in cfg.get("custom", [])): return 404, {"error": "no such command app"}
                cfg["custom"] = [dict(c, name=name, command=cmd, icon=icon, timeout=tmo, confirm=bool(data.get("confirm"))) if c["id"] == aid else c for c in cfg["custom"]]
            else:
                aid = "custom-" + secrets.token_hex(4)
                cfg["custom"] = (cfg.get("custom", []) + [{"id": aid, "name": name, "command": cmd, "icon": icon or "bash", "timeout": tmo, "confirm": bool(data.get("confirm")), "url": ""}])[:60]
            save_json(APPS, cfg); audit(device=dev["name"], path=path, result=200, command_app=name)
            return 200, {"ok": True, "id": aid, "apps": app_list()}
        if method == "POST" and len(parts) == 3 and parts[0] == "apps" and parts[2] == "run":
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            if not re.fullmatch(r"custom-[0-9a-f]{8}", parts[1]): return 404, {"error": "no such command app"}
            rc, res = helper("task-start", "run-command", json.dumps({"id": parts[1]})); return (202 if rc == 0 else 400), ({"task": res.get("id"), **res} if rc == 0 else res)
        if method == "GET" and len(parts) == 3 and parts[0] == "apps" and parts[2] == "remote" and re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", parts[1]):
            if role_of(dev) != "admin" or dev.get("type") == "watch": return 403, {"error": "admins only"}
            return app_remote(parts[1])
        if method == "POST" and parts == ["apps"]:
            # change how an app shows (name, icon, hidden, links), or add your own (no id yet)
            aid = str(data.get("id") or "")
            if aid and not re.fullmatch(r"[A-Za-z0-9_.-]{1,64}", aid): raise ValueError("bad app")
            cfg = load_json(APPS, {"overrides": {}, "custom": []})
            o = {}
            if "name" in data: o["name"] = "".join(c for c in str(data["name"]) if c.isprintable()).strip()[:40]
            if "icon" in data:
                o["icon"] = str(data["icon"]).strip().lower()[:60]
                if o["icon"] and not re.fullmatch(r"[a-z0-9-]{1,60}", o["icon"]): raise ValueError("icon: a dashboard-icons name like 'jellyfin'")
            if "hidden" in data: o["hidden"] = bool(data["hidden"])
            for k in ("url", "remote_url"):
                if k in data:
                    v = str(data[k]).strip()[:300]
                    if v and not re.fullmatch(r"https?://[A-Za-z0-9.\-\[\]:]+(/[^\s]*)?", v): raise ValueError(f"{k}: a link like http://192.168.1.10:8096")
                    o[k] = v
            if not aid:
                if not o.get("url"): raise ValueError("give the app a link")
                aid = "custom-" + secrets.token_hex(4)
                cfg["custom"] = (cfg.get("custom", []) + [{"id": aid, "name": o.get("name") or "App", "url": o["url"], "icon": o.get("icon", ""), "remote_url": o.get("remote_url", "")}])[:60]
            elif aid.startswith("custom-"):
                cfg["custom"] = [dict(c, **o) if c["id"] == aid else c for c in cfg.get("custom", [])]
            else:
                cfg.setdefault("overrides", {})[aid] = {**cfg.get("overrides", {}).get(aid, {}), **o}
            with lock: save_json(APPS, cfg)
            return 200, {"ok": True, "id": aid, "apps": app_list()}
        if method == "DELETE" and len(parts) == 2 and parts[0] == "apps" and parts[1].startswith("custom-"):
            cfg = load_json(APPS, {"overrides": {}, "custom": []})
            cfg["custom"] = [c for c in cfg.get("custom", []) if c["id"] != parts[1]]
            with lock: save_json(APPS, cfg)
            return 200, {"ok": True}

        # ── storage map, pools, background tasks ──
        if method == "GET" and parts == ["storage"]:
            rc, res = helper("storage-map", timeout=60); return (200 if rc == 0 else 502), res
        if method == "POST" and parts[:2] == ["storage", "task"] and len(parts) == 3:
            spec = data.get("spec", {})
            if not isinstance(spec, dict): raise ValueError("spec must be an object")
            rc, res = helper("task-start", parts[2], json.dumps(spec), timeout=60)
            return (200 if rc == 0 else 400), res
        if method == "GET" and parts == ["tasks"]:
            rc, res = helper("task-list"); return (200 if rc == 0 else 502), res
        if len(parts) >= 2 and parts[0] == "tasks" and re.fullmatch(r"[0-9a-f]{12}", parts[1]):
            if method == "GET" and len(parts) == 2:
                rc, res = helper("task-status", parts[1]); return (200 if rc == 0 else 404), res
            if method == "POST" and parts[2:] == ["stop"]:
                rc, res = helper("task-stop", parts[1]); return (200 if rc == 0 else 400), res
        # ── backups ──
        if parts == ["backups"]:
            if method == "GET": rc, res = helper("backups-list", timeout=60); return (200 if rc == 0 else 502), res
            if method == "POST":
                rc, res = helper("backup-put", json.dumps(data)[:8000]); return (200 if rc == 0 else 400), res
        if method == "GET" and parts == ["fs", "dirs"]:
            if role_of(dev) != "admin" or dev.get("type") == "watch": return 403, {"error": "admins only"}
            rc, res = helper("list-dirs", urllib.parse.unquote(q.get("path", "/"))[:1000] or "/"); return (200 if rc == 0 else 400), res
        if method == "GET" and parts == ["backups", "suggest"]:
            rc, res = helper("backup-suggest", timeout=90); return (200 if rc == 0 else 502), res
        if method == "POST" and parts == ["backups", "test"]:
            rc, res = helper("backup-test", json.dumps(data)[:8000], timeout=120); return (200 if rc == 0 else 400), res
        if len(parts) >= 2 and parts[0] == "backups" and re.fullmatch(r"[a-z0-9]{1,12}", parts[1]):
            jid = parts[1]
            if method == "DELETE" and len(parts) == 2:
                rc, res = helper("backup-delete", jid); return (200 if rc == 0 else 400), res
            if method == "POST" and parts[2:] == ["run"]:
                rc, res = helper("task-start", "backup-run", json.dumps({"job": jid, "force": bool(data.get("force"))})); return (200 if rc == 0 else 400), res
            if method == "GET" and parts[2:] == ["snapshots"]:
                rc, res = helper("backup-snapshots", jid, timeout=120); return (200 if rc == 0 else 400), res
            if method == "GET" and parts[2:] == ["browse"]:
                snap, pth = q.get("snap", ""), urllib.parse.unquote(q.get("path", "/"))
                if not re.fullmatch(r"current|\d{4}-\d\d-\d\d_\d{4}(\d\d)?", snap): raise ValueError("bad snapshot")
                rc, res = helper("backup-browse", jid, snap, pth[:1000], timeout=120); return (200 if rc == 0 else 400), res
            if method == "POST" and parts[2:] == ["restore"]:
                spec = {"job": jid, "snapshot": str(data.get("snapshot", "")), "path": str(data.get("path", ""))[:1000], "to": str(data.get("to", "beside"))}
                rc, res = helper("task-start", "restore", json.dumps(spec)); return (200 if rc == 0 else 400), res
        # ── diagnostics ──
        if method == "POST" and len(parts) == 2 and parts[0] == "diag" and parts[1] in ("net-internet", "disk-speed", "cpu-stress", "mem-test"):
            rc, res = helper("task-start", parts[1], json.dumps({k: data[k] for k in ("path", "seconds", "percent") if k in data})); return (200 if rc == 0 else 400), res
        if method == "GET" and len(parts) == 2 and parts[0] == "diag" and parts[1] in ("ping", "trace", "dns", "port"):
            args = [urllib.parse.unquote(q.get("host", ""))[:253]] + ([q.get("port", "")] if parts[1] == "port" else [])
            rc, res = helper("diag-quick", parts[1], *args, timeout=120); return (200 if rc == 0 else 400), res
        if method == "GET" and parts == ["diag", "top"]:
            rc, res = helper("diag-top"); return (200 if rc == 0 else 502), res
        if method == "GET" and parts == ["diag", "tools"]:
            rc, res = helper("tools-status"); return (200 if rc == 0 else 502), res

        # ── containers ──
        if method == "GET" and parts == ["containers"]:
            rc, res = helper("containers"); return (200 if rc == 0 else 502), res
        # your own container, from a form (helper.py checks every field; risky options can't be expressed)
        if method == "POST" and parts == ["containers", "custom", "check"]:
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            raw = json.dumps(data.get("spec") or {})
            if len(raw) > 7000: raise ValueError("that's too much for one container")
            rc, res = helper("custom-check", raw, timeout=40); return (200 if rc == 0 else 400), res
        if method == "POST" and parts == ["containers", "custom"]:
            raw = json.dumps(data.get("spec") or {})
            if len(raw) > 7000: raise ValueError("that's too much for one container")
            rc, res = helper("custom-check", raw, timeout=40)
            if rc != 0: return 400, res
            rc, res = helper("task-start", "custom-install", raw); return (202 if rc == 0 else 400), ({"task": res.get("id"), **res} if rc == 0 else res)
        if len(parts) >= 2 and parts[0] == "containers":
            name = parts[1]
            if method == "GET" and len(parts) == 2:
                rc, res = helper("container-info", name, timeout=40); return (200 if rc == 0 else 400), res
            if method == "GET" and parts[2:] == ["logs"]:
                rc, res = helper("container-logs", name, str(q.get("lines", "300")), timeout=40)
                return (200 if rc == 0 else 400), res
            if method == "POST" and len(parts) == 3 and parts[2] in ("start", "stop", "restart"):
                rc, res = helper("container", parts[2], name, timeout=180); return (200 if rc == 0 else 400), res
            if method == "GET" and parts[2:] == ["diagnose"]:
                rc, res = helper("container-diagnose", name, timeout=60); return (200 if rc == 0 else 400), res
            if method == "POST" and len(parts) == 4 and parts[2] == "fix" and parts[3] in ("kill", "recreate", "rollback"):
                rc, res = helper("container-fix", parts[3], name, timeout=700); return (200 if rc == 0 else 400), res
            if method == "POST" and parts[2:] == ["remove-custom"]:
                rc, res = helper("task-start", "custom-uninstall", json.dumps({"name": name})); return (202 if rc == 0 else 400), ({"task": res.get("id"), **res} if rc == 0 else res)
            if method == "POST" and parts[2:] == ["update"]:
                rc, res = helper("task-start", "containers-update", json.dumps({"containers": [name]})); return (202 if rc == 0 else 400), ({"task": res.get("id"), **res} if rc == 0 else res)
            if method == "POST" and parts[2:] == ["policy"]:
                rc, res = helper("container-policy", name, str(data.get("policy", ""))); return (200 if rc == 0 else 400), res
            if method == "POST" and parts[2:] == ["shell"]:
                sh = open_shell(dev["id"], name)
                return 200, {"session": sh["id"], "container": name}
        if method == "POST" and parts == ["files", "unlock"]:
            if role_of(dev) != "admin" or dev.get("type") != "browser": return 400, {"error": "only browsers need this"}
            FILE_UNLOCK[dev["id"]] = time.time() + 900; return 200, {"ok": True, "until": FILE_UNLOCK[dev["id"]]}
        if method == "POST" and parts == ["terminal"]:
            # a login shell on the server as your normal user (approved with your fingerprint; sudo asks its password)
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            sh = open_shell(dev["id"], dev["name"], verb=("host-shell",))
            sh["host"] = True
            return 200, {"session": sh["id"]}
        if len(parts) == 2 and parts[0] == "shell":
            sh = shells.get(parts[1])
            if not sh or sh["device"] != dev["id"]: return 404, {"error": "no such shell"}
            sh["last"] = time.time()
            if method == "POST":
                if not sh["alive"]: return 410, {"error": "shell has exited"}
                text = str(data.get("input", ""))[:8000]
                try: sh["write"](text.encode())
                except Exception: return 410, {"error": "shell has exited"}
                return 200, {"ok": True}
            if method == "GET":
                off = max(int(q.get("offset", "0") or 0), sh["base"])
                if q.get("wait") == "1":                         # long poll: answer as soon as there's output (≤ 8 s)
                    end = time.time() + 8
                    while sh["alive"] and sh["base"] + len(sh["buf"]) <= off and time.time() < end: time.sleep(0.03)
                chunk = bytes(sh["buf"][off - sh["base"]:])
                for cut in range(min(3, len(chunk)) + 1):             # never split a UTF-8 character between replies
                    try: chunk[:len(chunk) - cut].decode(); chunk = chunk[:len(chunk) - cut]; break
                    except UnicodeDecodeError: continue
                return 200, {"data": chunk.decode(errors="replace"), "offset": off + len(chunk), "alive": sh["alive"]}
            if method == "DELETE":
                try: sh["kill"]()
                except Exception: pass
                return 200, {"ok": True}

        # ── status page / SSH ──
        if method == "GET" and parts == ["stats"]:
            # ?since=T (the phone polling once a second): only the new 1-second points
            try: since = float(q.get("since", "") or "nan")
            except ValueError: since = float("nan")
            now = {"now": STATS["now"]} if STATS["now"] else {}
            if since == since:                                  # not NaN
                return 200, {"recent": [p for p in STATS["recent"] if p["t"] > since], **now}
            return 200, {"history": STATS["history"], "recent": STATS["recent"], **now}
        if parts == ["ssh", "authorize"] and method in ("GET", "POST"):
            # GET: is this phone's key installed?  POST {key}: install it (fingerprint-confirmed).
            if method == "GET":
                rc, res = helper("ssh-key-status", dev["id"]); return (200 if rc == 0 else 500), res
            key = str(data.get("key", ""))[:400]
            rc, res = helper("ssh-authorize", dev["id"], key)
            if rc == 0: helper("notify-ssh-key", dev["name"][:60])
            return (200 if rc == 0 else 400), res
        if method == "GET" and parts == ["ssh-hostkeys"]:
            # Lets the app pin the server's SSH host keys (no trust-on-first-use).
            keys = []
            for f in sorted(os.listdir("/etc/ssh")):
                if f.startswith("ssh_host_") and f.endswith("_key.pub"):
                    try: keys.append(" ".join(open(f"/etc/ssh/{f}").read().split()[:2]))
                    except OSError: pass
            return 200, {"keys": keys, "user": SSH_USER, "hosts": SSH_HOSTS}

        # ── jobs ──
        if method == "GET" and parts == ["jobs"]:
            return 200, {"jobs": sorted(jobs.values(), key=lambda j: -j["started"])}
        if method == "GET" and len(parts) == 2 and parts[0] == "jobs":
            j = jobs.get(parts[1]); return (200, j) if j else (404, {"error": "no such job"})

        # ── app stores ──
        if method == "GET" and parts == ["store"]:
            rc, res = helper("store-list", timeout=60); return 200, res
        if method == "POST" and len(parts) == 3 and parts[0] == "store" and parts[2] in ("install", "uninstall"):
            rc, res = helper("task-start", f"store-{parts[2]}", json.dumps({"id": parts[1]})); return (202 if rc == 0 else 400), ({"task": res.get("id"), **res} if rc == 0 else res)
        if method == "GET" and parts == ["programs"]:
            rc, res = helper("programs"); return 200, res
        if method == "GET" and parts == ["programs", "search"]:
            if role_of(dev) != "admin": return 403, {"error": "admins only"}
            rc, res = helper("program-search", urllib.parse.unquote_plus(q.get("q", ""))[:60], timeout=60); return (200 if rc == 0 else 400), res
        if method == "POST" and len(parts) == 3 and parts[0] == "programs" and parts[2] in ("install", "remove"):
            rc, res = helper("task-start", f"program-{parts[2]}", json.dumps({"pkg": parts[1]})); return (202 if rc == 0 else 400), ({"task": res.get("id"), **res} if rc == 0 else res)

        # ── hardware ──
        if method == "GET" and parts == ["hardware"]:
            rc, res = helper("drives", timeout=60)
            res["temps"] = {k: v for k, v in (load_json(STATUS, {}).get("metrics") or {}).items() if "temp" in k}
            return 200, res
        if method == "POST" and parts == ["alerts", "dismiss"]:
            rc, res = helper("alerts-dismiss", str(data.get("key", ""))[:220], timeout=160); return (200 if rc == 0 else 400), res
        if method == "POST" and parts == ["events", "restore"]:
            ts = data.get("t"); arg = ",".join(f"{float(x):.6f}" for x in (ts if isinstance(ts, list) else [ts])[:200])
            rc, res = helper("events-restore", arg, timeout=160); return (200 if rc == 0 else 400), res
        if method == "POST" and parts == ["events", "delete"]:
            ts = data.get("t"); arg = "all" if data.get("all") is True else ",".join(f"{float(x):.6f}" for x in (ts if isinstance(ts, list) else [ts])[:200])
            rc, res = helper("events-delete", arg, timeout=160); return (200 if rc == 0 else 400), res
        if method == "POST" and parts == ["mounts", "mount"]:
            rc, res = helper("mount-fstab", str(data.get("mount", ""))[:200], timeout=90); return (200 if rc == 0 else 400), res
        if len(parts) == 3 and parts[0] == "drives" and parts[2] == "test":
            if method == "GET": rc, res = helper("smart-tests", parts[1], timeout=70); return (200 if rc == 0 else 400), res
            if method == "POST":
                kind = str(data.get("type", "short"))
                if kind not in ("short", "long", "abort"): raise ValueError("type must be short, long or abort")
                rc, res = helper("smart-test", parts[1], kind, timeout=70); return (200 if rc == 0 else 400), res
        if method == "POST" and len(parts) == 3 and parts[0] == "drives" and parts[2] in ("mount", "unmount"):
            rc, res = helper(f"drive-{parts[2]}", parts[1], timeout=120); return (200 if rc == 0 else 400), res
        if method == "POST" and len(parts) == 2 and parts[0] == "power" and parts[1] in ("reboot", "poweroff"):
            rc, res = helper("power", parts[1]); return (200 if rc == 0 else 500), res

        # ── quick actions ──
        if method == "GET" and parts == ["backup"]:
            rc, res = helper("backup-status"); return 200, res
        if method == "POST" and parts == ["actions", "backup"]:
            rc, res = helper("backup-now"); return (200 if rc == 0 else 500), res
        if method == "POST" and parts == ["actions", "free-ram"]:
            rc, res = helper("free-ram"); return (200 if rc == 0 else 500), res

        # ── app updates ──
        if method == "GET" and parts == ["app", "latest"]:
            m = load_json(f"{APK_DIR}/latest.json", {})
            return 200, {k: m.get(k) for k in ("version_code", "version_name", "sha256", "size", "notes", "published")}

        return 404, {"error": "no such endpoint"}

    # ── pairing (home LAN only, one-time code from `sudo nova add`) ──
    def pair(self, body):
        where, ip = self.origin()
        if where not in ("lan", "local"): return self.fail(ip, 403, "pairing only works on the home network")
        if self.throttled(ip): return self.send(429, {"error": "too many failed attempts"})
        try:
            d = json.loads(body); code, name, pem = d["code"], str(d["name"])[:40], d["public_key"]
            pub = serialization.load_pem_public_key(pem.encode())
            if not isinstance(pub, ec.EllipticCurvePublicKey) or pub.curve.name != "secp256r1":
                raise ValueError
        except Exception:
            return self.send(400, {"error": "need code, name, P-256 public_key"})
        with lock:
            p = load_json(PAIRING, {})
            ok = p and p.get("expires", 0) > time.time() and \
                hmac.compare_digest(p.get("code_sha256", ""), hashlib.sha256(code.encode()).hexdigest())
            if not ok:
                return self.fail(ip, 401, "bad or expired pairing code")
            os.remove(PAIRING)                                # one-time
            dev_id = secrets.token_urlsafe(12)
            devs = load_json(DEVICES, {})
            role = p.get("role") if p.get("role") in ROLES else "admin"
            head = d.get("kind") == "head"           # another Nova server's web, showing this one read-only
            devs[dev_id] = {"name": name, "public_key": pem, "created": time.strftime("%Y-%m-%d %H:%M"),
                            "paired_from": ip, "role": "viewer" if head else role, "user": str(p.get("user", ""))[:40],
                            **({"type": "head"} if head else {}),
                            **({"form": d["form"]} if d.get("form") in FORMS and not head else {})}
            save_json(DEVICES, devs)
        audit(ip=ip, path="/api/v1/pair", result=200, device=name)
        helper("notify-paired", "".join(c for c in name if c.isalnum() or c in " -_")[:40] or "device")
        tls = lan_tls()
        if head:                                      # read-only window: no Cloudflare service token for it
            return self.send(200, {"device_id": dev_id, "server": "Nova", "api": API_VERSION, "role": "viewer", "kind": "head",
                                   "name": load_json(SETTINGS, {}).get("display_name") or os.uname().nodename,
                                   "lan_url": tls.get("url") or CFG.get("lan_url", ""), "remote_url": CFG.get("remote_url", "")})
        return self.send(200, {"device_id": dev_id, "server": "Nova", "api": API_VERSION, "role": role,
                               "name": load_json(SETTINGS, {}).get("display_name") or os.uname().nodename,
                               "lan_url": tls.get("url") or CFG.get("lan_url", ""), "lan_pin": tls.get("pin", ""),
                               "remote_url": CFG.get("remote_url", ""),
                               "cf_client_id": CFG.get("cf_client_id", ""),
                               "cf_client_secret": CFG.get("cf_client_secret", "")})


TLS_DIR = f"{DATA}/tls"
TLS_PORT = int(CFG.get("tls_port", 8495))

def tls_material():
    """Self-signed certificate for the home-network listener, made on first start. The app pins
    its SHA-256 (delivered in the pairing QR / over the signed API), so no CA is involved."""
    cert, key = f"{TLS_DIR}/cert.pem", f"{TLS_DIR}/key.pem"
    if not os.path.exists(cert):
        import datetime
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        os.makedirs(TLS_DIR, mode=0o700, exist_ok=True)
        k = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, f"nova-api {os.uname().nodename}")])
        now = datetime.datetime.now(datetime.timezone.utc)
        c = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(k.public_key())
             .serial_number(x509.random_serial_number()).not_valid_before(now - datetime.timedelta(days=1))
             .not_valid_after(now + datetime.timedelta(days=3650))
             .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(LAN_HOST))]), critical=False)
             .sign(k, hashes.SHA256()))
        with open(key, "wb") as f:
            os.fchmod(f.fileno(), 0o600)
            f.write(k.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        with open(cert, "wb") as f: f.write(c.public_bytes(serialization.Encoding.PEM))
    from cryptography import x509
    der = x509.load_pem_x509_certificate(open(cert, "rb").read()).public_bytes(serialization.Encoding.DER)
    return cert, key, hashlib.sha256(der).hexdigest()

def lan_tls():
    try: _, _, pin = tls_material(); return {"url": f"https://{LAN_HOST}:{TLS_PORT}", "pin": pin}
    except Exception: return {}

MAX_CONN, MAX_PER_IP, MAX_LOCAL = 512, 32, 256
_conn = {"total": 0}; _per_ip = {}; _conn_lock = threading.Lock()

class NovaServer(ThreadingHTTPServer):
    """Threaded HTTP server with connection limits: at most 32 at once per address (256 for the
    local Cloudflare tunnel) and 512 in total, plus a deep accept queue. One flooding or slowloris
    client can't starve anyone else; its extra connections are closed at once."""
    daemon_threads = True
    request_queue_size = 128
    def process_request(self, request, client_address):
        ip = client_address[0]
        cap = MAX_LOCAL if ip.startswith("127.") else MAX_PER_IP
        with _conn_lock:
            ok = _conn["total"] < MAX_CONN and _per_ip.get(ip, 0) < cap
            if ok: _conn["total"] += 1; _per_ip[ip] = _per_ip.get(ip, 0) + 1
        if not ok:
            try: request.close()
            except OSError: pass
            return
        def run():
            try: self.finish_request(request, client_address)
            except Exception: pass
            finally:
                self.shutdown_request(request)
                with _conn_lock:
                    _conn["total"] -= 1; _per_ip[ip] -= 1
                    if not _per_ip[ip]: del _per_ip[ip]
        threading.Thread(target=run, daemon=True).start()
    def handle_error(self, request, client_address): pass

DISCOVERY_PORT = int(CFG.get("discovery_port", 8496))

def discovery_responder():
    """LAN discovery: the app broadcasts "NOVA?" on UDP 8496 and every Nova server on the home
    network answers with its name and address, so adding a server doesn't start with typing an IP.
    Only devices inside the home subnet get an answer, at most a few a second; it reveals nothing
    a port scan wouldn't (pairing still needs the one-time code shown on the server itself)."""
    import socket as so
    if not CFG.get("discovery", True) or str(LAN) == "127.0.0.1/32": return
    try:
        sk = so.socket(so.AF_INET, so.SOCK_DGRAM); sk.setsockopt(so.SOL_SOCKET, so.SO_REUSEADDR, 1); sk.bind(("", DISCOVERY_PORT))
    except OSError as e:
        print(f"no LAN discovery: {e}", flush=True); return
    print(f"nova-api discovery on udp/{DISCOVERY_PORT} (home network only)", flush=True)
    recent = {}
    while True:
        try:
            msg, (ip, port) = sk.recvfrom(256)
            if not msg.startswith(b"NOVA?") or ipaddress.ip_address(ip) not in LAN: continue
            now = time.time()
            if now - recent.get(ip, 0) < 0.3: continue
            recent[ip] = now
            if len(recent) > 500: recent.clear()
            tls = lan_tls()
            reply = {"nova": 1, "name": load_json(SETTINGS, {}).get("display_name") or os.uname().nodename,
                     "host": os.uname().nodename, "lan_url": tls.get("url") or CFG.get("lan_url", ""), "pin": tls.get("pin", ""),
                     "api": API_VERSION, "accent": load_json(SETTINGS, {}).get("accent", "")}
            sk.sendto(json.dumps(reply).encode(), (ip, port))
        except Exception:
            time.sleep(0.2)

def main():
    threading.Thread(target=discovery_responder, daemon=True).start()
    port = int(CFG.get("port", 8095))
    binds = list(dict.fromkeys(CFG.get("bind", ["127.0.0.1", LAN_HOST])))
    servers = []
    for addr in binds:
        try:
            s = NovaServer((addr, port), Handler)
            servers.append(s); threading.Thread(target=s.serve_forever, daemon=True).start()
            print(f"nova-api {API_VERSION} listening on {addr}:{port}", flush=True)
        except OSError as e:
            print(f"cannot bind {addr}:{port}: {e}", flush=True)
    # Encrypted listener on the home network (the app pins its certificate).
    try:
        import ssl
        cert, key, pin = tls_material()
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        ctx.load_cert_chain(cert, key)
        class TLSServer(NovaServer):
            def finish_request(self, request, client_address):     # handshake in the worker thread, not the accept loop
                request.settimeout(15); request.do_handshake()
                super().finish_request(request, client_address)
            def handle_error(self, request, client_address): pass    # failed handshakes are noise
        for host in [LAN_HOST] + ([TAILNET_HOST] if TAILNET_HOST else []):
            try:
                s = TLSServer((host, TLS_PORT), Handler)
                s.socket = ctx.wrap_socket(s.socket, server_side=True, do_handshake_on_connect=False)
                servers.append(s); threading.Thread(target=s.serve_forever, daemon=True).start()
                print(f"nova-api TLS on {host}:{TLS_PORT} (pin {pin[:16]}…)", flush=True)
            except OSError as e:
                print(f"cannot bind TLS {host}:{TLS_PORT}: {e}", flush=True)
    except Exception as e:
        print(f"no TLS listener: {e}", flush=True)
    if not servers: sys.exit(1)
    threading.Event().wait()


if __name__ == "__main__":
    main()
