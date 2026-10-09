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
Pairing is only possible from the home LAN, with a one-time code shown by `sudo nova-api pair`.
"""
import base64, hashlib, hmac, ipaddress, json, os, secrets, subprocess, sys, threading, time, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec, padding, rsa

HERE = os.path.dirname(os.path.realpath(__file__))
for _d in ("/usr/lib/nova-rgb", "/usr/local/lib/nova-rgb"):      # packaged / source install
    if os.path.isdir(_d): sys.path.insert(0, _d)
try:                       # optional module: fan/case lighting (modules/fan-gigabyte-fusion2)
    import nova_rgb  # noqa: E402
except ImportError:
    nova_rgb = None

API_VERSION = "0.4.4-alpha.1"
CONFIG = "/etc/nova-api/config.json"
DATA = "/var/lib/nova-api"
DEVICES = f"{DATA}/devices.json"
NOTIFY_FILE = f"{DATA}/notify.json"      # notification settings chosen in the app
PAIRING = f"{DATA}/pairing.json"
AUDIT = "/var/log/nova-api/audit.log"
STATUS = "/var/lib/nova-alerts/www/status.json"
MAX_BODY = 64 * 1024
NOTIFY_KEYS = {"push_min_level": ("info", "warning", "critical"), "push_logins": bool, "push_usb": bool,
               "discord_paused": bool}
EVENTS = "/var/lib/nova-alerts/www/events.json"
APK_DIR = f"{DATA}/apk"
CRASH_DIR = "/var/log/nova-api/crash"
CLOCK_SKEW_MS = 60_000
SETTINGS = f"{DATA}/settings.json"       # changeable from the app: display name, accent colour

lock = threading.Lock()          # devices.json / pairing.json writes
rgb_lock = threading.Lock()      # one HID conversation at a time
nonces = {}                      # nonce -> expiry (seconds)
fails = {}                       # ip -> [timestamps]


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

def helper(*args, timeout=120):
    if os.path.exists(HELPER_SOCK):
        c = helper_connect(args, timeout); c.shutdown(1)
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

def stats_sampler():
    iface = _default_iface()
    prev_cpu, prev_net, prev_t = _cpu_times(), _net(iface), time.time()
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
            STATS["now"] = now
            point = {k: now[k] for k in ("t", "cpu", "mem", "temp", "rx", "tx")}
            STATS["recent"] = (STATS["recent"] + [point])[-RECENT_N:]
            if n % HISTORY_EVERY == 1:                    # one averaged point per 15 s for the hour view
                last = STATS["recent"][-HISTORY_EVERY:]
                avg = {k: round(sum((p[k] or 0) for p in last) / len(last), 1) for k in ("cpu", "mem", "rx", "tx")}
                avg["temp"] = point["temp"]; avg["t"] = int(t)
                STATS["history"] = (STATS["history"] + [avg])[-HISTORY_N:]
            prev_cpu, prev_net, prev_t = cpu, net, t
        except Exception:
            pass
threading.Thread(target=stats_sampler, daemon=True).start()

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
                      "method": method, "path": path, "data": data, "created": time.time(), "state": "pending"}
    for k in [k for k, v in approvals.items() if time.time() - v["created"] > 3600]: approvals.pop(k, None)
    return approvals[aid]

def describe_action(a):
    p = [x for x in a["path"].split("/") if x][2:]
    if p[:1] == ["containers"] and len(p) == 3: return f"{p[2].title()} the container {p[1]}"
    if p[:1] == ["store"]: return f"{p[2].title()} {p[1]} from the app store"
    if p[:1] == ["programs"]: return f"{p[2].title()} the program {p[1]}"
    if p[:1] == ["drives"]: return f"{p[2].title()} a drive"
    if p[:1] == ["power"]: return "Restart the server" if p[1:] == ["reboot"] else "Shut down the server"
    if p[:1] == ["devices"]: return "Change paired devices"
    if p[:1] == ["ssh"]: return "Let this phone log in over SSH"
    if p == ["alerts", "dismiss"]: return f"Ignore the alert “{a.get('data', {}).get('key', '')}”"
    if p == ["events", "delete"]: return "Clear the whole inbox" if a.get("data", {}).get("all") else "Delete from the inbox"
    return f"{a['method']} {a['path']}"

def browser_forbidden(method, parts, dev):
    """Device management and approvals are for phones only (their keys live in hardware)."""
    if parts[:1] == ["approvals"] and method == "POST": return True
    if parts[:1] == ["browser"]: return True
    if parts == ["server", "update"] and method == "POST": return True          # updates: phones only
    if parts[:1] == ["ssh"] and method != "GET": return True                      # SSH keys: phones only
    if parts[:1] == ["devices"] and method != "GET" and parts != ["devices", dev.get("id")]: return True
    return False

ROLES = ("admin", "viewer")
def role_of(d): return d.get("role") if d.get("role") in ROLES else "admin"     # devices from before roles: admin

def viewer_may(method, parts, dev):
    """View-only devices: read anything, change nothing — except their own housekeeping."""
    if method == "GET": return True
    if method == "DELETE" and parts == ["devices", dev.get("id")]: return True     # unpair itself
    if method == "POST" and parts in (["crash"], ["device", "stepup-key"]): return True
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
    happened — silence an alert, erase the login history — so a browser asks a phone first."""
    return method == "POST" and parts in (["alerts", "dismiss"], ["events", "delete"])

# Actions that need the fingerprint-bound step-up key (second signature).
def needs_stepup(method, parts):
    if method == "DELETE" and parts[:1] == ["devices"]: return True
    if method == "POST" and parts == ["devices", "remove-all"]: return True
    if method == "POST" and parts == ["browser", "approve"]: return True
    if method == "POST" and parts == ["server", "update"]: return True
    if method == "POST" and parts[:1] == ["approvals"] and parts[-1:] == ["approve"]: return True
    if method == "POST" and parts[:1] == ["devices"] and parts[-1:] in (["access"], ["invite"]): return True
    if method != "POST": return False
    if parts[:1] == ["containers"] and len(parts) == 3 and parts[2] in ("stop", "restart", "shell", "policy"): return True
    if parts[:1] in (["store"], ["programs"]) and len(parts) == 3: return True
    if parts[:1] == ["drives"] and parts[-1:] == ["unmount"]: return True
    if parts[:1] == ["power"]: return True
    if parts == ["ssh", "authorize"]: return True
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
        if n > MAX_BODY: return None
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
        try: data = json.loads(body) if body else {}
        except ValueError: return self.send(400, {"error": "invalid JSON"})
        if not isinstance(data, dict): return self.send(400, {"error": "expected an object"})

        parts = [p for p in path.split("/") if p][2:]
        if dev.get("type") == "browser" and browser_forbidden(method, parts, dev):
            audit(device=dev["name"], path=path, result=403, why="phones only")
            return self.send(403, {"error": "phones_only", "message": "Do this from the Nova app on an admin phone."})
        if dev.get("type") == "browser" and method == "DELETE" and parts == ["devices", dev["id"]]:
            with lock:
                devs = load_json(DEVICES, {}); devs.pop(dev["id"], None); save_json(DEVICES, devs)
            audit(device=dev["name"], path=path, result=200, why="browser removed itself")
            return self.send(200, {"ok": True})
        if (needs_stepup(method, parts) or browser_needs_phone(method, parts)) and not dev.get("stepup_ok") and dev.get("type") == "browser" and role_of(dev) == "admin":
            try: data0 = json.loads(body) if body else {}
            except ValueError: data0 = {}
            a = new_approval(dev, method, path, data0)
            audit(device=dev["name"], path=path, result=202, why="sent to a phone for approval")
            return self.send(202, {"approval": a["id"], "message": "Approve this on your phone (Nova app)."})
        if needs_stepup(method, parts) and not dev.get("stepup_ok"):
            audit(device=dev["name"], path=path, result=403, why="step-up required")
            return self.send(403, {"error": "stepup_required",
                                   "message": "Confirm with your fingerprint to do this."
                                   if dev.get("stepup_key") else "Set up fingerprint confirmation first (home Wi-Fi)."})
        if role_of(dev) != "admin" and not viewer_may(method, parts, dev):
            audit(device=dev["name"], path=path, result=403, why="view-only")
            return self.send(403, {"error": "view_only", "message": "This phone has view-only access."})
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
        self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()")
        self.send_header("Cross-Origin-Opener-Policy", "same-origin")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self'; style-src-attr 'unsafe-inline'; script-src 'self'; "
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
            return self.send(200, {"id": rid, "code": code, "expires_in": 600})
        if method == "GET" and path.startswith("/api/v1/browser/request/"):
            rq = browser_requests.get(path.rsplit("/", 1)[1])
            if not rq: return self.send(404, {"state": "expired"})
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

    def dispatch(self, method, path, data, dev):
        parts = [p for p in path.split("/") if p][2:]       # after /api/v1
        q = dict(x.split("=", 1) for x in self.path.split("?", 1)[1].split("&") if "=" in x) if "?" in self.path else {}

        # ── identity / device ──
        if method == "GET" and parts == ["whoami"]:
            return 200, {"device": dev["name"], "via": dev["via"], "paired": dev.get("created"), "id": dev["id"],
                         "stepup": bool(dev.get("stepup_key")), "api": API_VERSION,
                         "role": role_of(dev), "user": dev.get("user", "")}
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
                                      "role": role_of(v), "user": v.get("user", ""), "type": v.get("type", "phone")}
                                     for k, v in load_json(DEVICES, {}).items()]}
        if method == "POST" and parts == ["devices", "remove-all"]:
            # Fingerprint-confirmed. keep_self=true removes every *other* phone; false removes all, this one included.
            keep = bool(data.get("keep_self", True))
            with lock:
                devs = load_json(DEVICES, {})
                gone = [v["name"] for k, v in devs.items() if not (keep and k == dev["id"])]
                gone_ids = [k for k in devs if not (keep and k == dev["id"])]
                devs = {k: v for k, v in devs.items() if keep and k == dev["id"]}
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
            # Fingerprint-confirmed: a one-time pairing code (like `nova-api pair`) for a new phone.
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
                with lock: save_json(SETTINGS, cfg)
            return 200, {"display_name": cfg.get("display_name", ""), "accent": cfg.get("accent", ""), "hostname": os.uname().nodename}
        if method == "DELETE" and len(parts) == 2 and parts[0] == "devices":
            with lock:
                devs = load_json(DEVICES, {}); gone = devs.pop(parts[1], None); save_json(DEVICES, devs)
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
            return 200, {k: v for k, v in a.items() if k != "data"} | {"what": describe_action(a)}
        if len(parts) == 3 and parts[0] == "approvals" and method == "POST" and parts[2] in ("approve", "deny"):
            # Only an admin *phone*, with its fingerprint key (step-up), can approve.
            a = approvals.get(parts[1])
            if not a or a["state"] != "pending": return 404, {"error": "nothing waiting with that id"}
            if time.time() - a["created"] > 600: a["state"] = "expired"; return 410, {"error": "that request expired"}
            if parts[2] == "deny": a["state"] = "denied"; return 200, {"ok": True}
            bdev = load_json(DEVICES, {}).get(a["device"])
            if not bdev: a["state"] = "denied"; return 404, {"error": "that browser was removed"}
            if role_of(bdev) != "admin": a["state"] = "denied"; return 403, {"error": "that browser is view-only now"}
            bdev = dict(bdev, id=a["device"], via=dev["via"], ip=dev["ip"], stepup_ok=True)
            a["state"] = "running"
            try:
                code, res = self.dispatch(a["method"], a["path"], a["data"], bdev)
            except ValueError as e: code, res = 400, {"error": str(e)}
            except Exception as e: code, res = 500, {"error": "server error"}; audit(path=a["path"], result=500, error=repr(e)[:200])
            a.update(state="done" if code < 400 else "failed", result=res, code=code, approved_by=dev["name"])
            audit(device=dev["name"], path=a["path"], result=code, approved_for=a["device_name"], stepup=True)
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
            return 200, {"server": {"name": os.uname().nodename, "display_name": cfg.get("display_name", ""),
                                    "accent": cfg.get("accent", ""), "board": board_name(), "cpu": cpu,
                                    "ram_gb": round(mem.get("MemTotal", 0) / 1048576), "kernel": os.uname().release,
                                    "uptime_s": int(float(open("/proc/uptime").read().split()[0]))},
                         "status": {k: st.get(k) for k in ("level", "headline", "status", "active_count", "active", "metrics", "updated_local")},
                         "fan": fan, "containers": {"running": sum(1 for c in cl if c["state"] == "running"), "total": len(cl)},
                         # What this server has, so the app only shows what works here.
                         "features": {"lighting": nova_rgb is not None, "monitor": os.path.exists(STATUS),
                                      "backup": os.path.exists("/usr/local/bin/nova-backup"), "store": os.path.isdir(f"{os.path.dirname(os.path.abspath(__file__))}/store"),
                                      "ssh": bool(SSH_HOSTS), "lan_tls": True}}
        if method == "GET" and parts == ["events", "wait"]:
            # Long poll for the phone's instant alerts: return as soon as something newer than
            # `since` is written (nova-alerts runs every minute), or empty after `timeout` s.
            since = float(q.get("since", "0") or 0); end = time.time() + min(55, max(5, int(q.get("timeout", "50") or 50)))
            seen = set(str(q.get("seen", "")).split(","))
            while True:
                ev = [e for e in load_json(EVENTS, {}).get("events", []) if e.get("t", 0) > since]
                ap = [{"id": a["id"], "what": describe_action(a), "device_name": a["device_name"], "user": a.get("user", "")}
                      for a in approvals.values() if a["state"] == "pending" and role_of(dev) == "admin"
                      and dev.get("type") != "browser" and time.time() - a["created"] < 600]
                if ev or ("seen" in q and [a for a in ap if a["id"] not in seen]) or time.time() >= end:
                    return 200, {"events": ev[:50], "approvals": ap}
                time.sleep(2)
        if method == "GET" and parts == ["events"]:
            since = float(q.get("since", "0") or 0)
            ev = [e for e in load_json(EVENTS, {}).get("events", []) if e.get("t", 0) > since]
            return 200, {"events": ev[:200]}
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
                    st.update(nova_rgb.validate(data))
                    nova_rgb.apply(st, nova_rgb.status_override(st)); nova_rgb.save(st)
                return 200, {**st, "effects": nova_rgb.EFFECT_NAMES, "status_override": nova_rgb.status_override(st)}

        # ── containers ──
        if method == "GET" and parts == ["containers"]:
            rc, res = helper("containers"); return (200 if rc == 0 else 502), res
        if len(parts) >= 2 and parts[0] == "containers":
            name = parts[1]
            if method == "GET" and len(parts) == 2:
                rc, res = helper("container-info", name, timeout=40); return (200 if rc == 0 else 400), res
            if method == "GET" and parts[2:] == ["logs"]:
                rc, res = helper("container-logs", name, str(q.get("lines", "300")), timeout=40)
                return (200 if rc == 0 else 400), res
            if method == "POST" and len(parts) == 3 and parts[2] in ("start", "stop", "restart"):
                rc, res = helper("container", parts[2], name, timeout=180); return (200 if rc == 0 else 400), res
            if method == "POST" and parts[2:] == ["update"]:
                return 202, {"job": start_job(f"Update {name}", ["container-update", name])}
            if method == "POST" and parts[2:] == ["policy"]:
                rc, res = helper("container-policy", name, str(data.get("policy", ""))); return (200 if rc == 0 else 400), res
            if method == "POST" and parts[2:] == ["shell"]:
                sh = open_shell(dev["id"], name)
                return 200, {"session": sh["id"], "container": name}
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
                chunk = bytes(sh["buf"][off - sh["base"]:])
                return 200, {"data": chunk.decode(errors="replace"), "offset": sh["base"] + len(sh["buf"]), "alive": sh["alive"]}
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
            return 202, {"job": start_job(f"{parts[2].title()} {parts[1]}", [f"store-{parts[2]}", parts[1]])}
        if method == "GET" and parts == ["programs"]:
            rc, res = helper("programs"); return 200, res
        if method == "POST" and len(parts) == 3 and parts[0] == "programs" and parts[2] in ("install", "remove"):
            return 202, {"job": start_job(f"{parts[2].title()} {parts[1]}", [f"program-{parts[2]}", parts[1]])}

        # ── hardware ──
        if method == "GET" and parts == ["hardware"]:
            rc, res = helper("drives", timeout=60)
            res["temps"] = {k: v for k, v in (load_json(STATUS, {}).get("metrics") or {}).items() if "temp" in k}
            return 200, res
        if method == "POST" and parts == ["alerts", "dismiss"]:
            rc, res = helper("alerts-dismiss", str(data.get("key", ""))[:220], timeout=160); return (200 if rc == 0 else 400), res
        if method == "POST" and parts == ["events", "delete"]:
            ts = data.get("t"); arg = "all" if data.get("all") is True else ",".join(f"{float(x):.4f}" for x in (ts if isinstance(ts, list) else [ts])[:200])
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

    # ── pairing (home LAN only, one-time code from `sudo nova-api pair`) ──
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
            devs[dev_id] = {"name": name, "public_key": pem, "created": time.strftime("%Y-%m-%d %H:%M"),
                            "paired_from": ip, "role": role, "user": str(p.get("user", ""))[:40]}
            save_json(DEVICES, devs)
        audit(ip=ip, path="/api/v1/pair", result=200, device=name)
        helper("notify-paired", "".join(c for c in name if c.isalnum() or c in " -_")[:40] or "device")
        tls = lan_tls()
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
