#!/usr/bin/env python3
"""
Nova code gate — a short-lived code (or a password you choose) in front of one website.

Cloudflare Access can only let people in by their email address. For "give Sam a code for tonight" Nova puts this small
service between Cloudflare and the app: the address's route on your tunnel points here instead of straight at the app, and
Cloudflare's own login is taken off it (the code is the lock). A visitor sees a page asking for the code; with a good one
they get a cookie and from then on everything they do is passed on to that one app, and nothing else on this server.

  * It listens on 127.0.0.1 only — the only thing that talks to it is cloudflared, on this same machine.
  * It runs as its own user (nova-gate), with no write access to anything but its own usage file, and may only open
    connections to this machine and the private network (see nova-gate.service).
  * It can only ever forward to the upstream recorded for the address in gate.json — never to anything a visitor names.
  * Codes are stored as hashes. Random codes are long enough that guessing is hopeless (79 bits); chosen passwords are
    hashed with scrypt, and guessing is slowed down hard (per visitor, and per address) because the address is public.
  * Revoking or expiring a code ends every session that came from it at the very next request.

The same file holds the management half (make / revoke codes, register an address) used by Nova's root helper.
"""
import base64, hashlib, hmac, http.client, ipaddress, json, os, re, secrets, select, socket, ssl, sys, threading, time
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

DIR = os.environ.get("NOVA_GATE_DIR", "/var/lib/nova-gate")
PORT = int(os.environ.get("NOVA_GATE_PORT", "8099"))
COOKIE = "nova_gate"
LOGIN = "/__nova_gate/login"
LOGOUT = "/__nova_gate/logout"
HEALTH = "/__nova_gate/health"
ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"          # no 0 1 I L O: nothing to misread when someone types it from a message
EXPIRES = {"1h": 3600, "1d": 86400, "7d": 7 * 86400, "30d": 30 * 86400, "never": 0}
MAX_CODES = 20
SESSION_MAX = 30 * 86400
HOST_RE = r"[a-z0-9]([a-z0-9.-]{0,250}[a-z0-9])?(:[0-9]{1,5})?"
COMMON = {"password", "password1", "password123", "12345678", "123456789", "1234567890", "qwertyui", "qwerty123", "iloveyou", "letmein1",
          "welcome1", "admin123", "abc12345", "11111111", "00000000", "football", "baseball", "monkey123", "dragon123", "sunshine"}


# ── state files ─────────────────────────────────────────────────────────────────────────
def _p(name): return os.path.join(DIR, name)

def _write(path, data, mode=0o640, group="nova-gate"):
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, mode)
    with os.fdopen(fd, "w") as f: f.write(data)
    os.chmod(tmp, mode)
    if os.geteuid() == 0:
        try: import grp; os.chown(tmp, 0, grp.getgrnam(group).gr_gid)
        except (KeyError, OSError): pass
    os.replace(tmp, path)

@contextmanager
def _locked():
    """One writer at a time (Nova's helper runs one request per process, so two can overlap)."""
    import fcntl
    os.makedirs(DIR, exist_ok=True)
    fd = os.open(_p(".lock"), os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX); yield
    finally:
        os.close(fd)

def load():
    try: d = json.load(open(_p("gate.json")))
    except (OSError, ValueError): d = {}
    d.setdefault("hosts", {}); return d

def save(d): _write(_p("gate.json"), json.dumps(d, indent=1))

def key():
    """The server's secret for cookies and code lookups (made once)."""
    try: k = open(_p("gate.key")).read().strip()
    except OSError: k = ""
    if not re.fullmatch(r"[0-9a-f]{64}", k):
        with _locked():
            try: k = open(_p("gate.key")).read().strip()
            except OSError: k = ""
            if not re.fullmatch(r"[0-9a-f]{64}", k): k = secrets.token_hex(32); _write(_p("gate.key"), k + "\n", 0o640)
    return bytes.fromhex(k)

def usage():
    try: return json.load(open(os.path.join(DIR, "run", "usage.json")))
    except (OSError, ValueError): return {}


# ── codes ───────────────────────────────────────────────────────────────────────────────
def norm(code): return re.sub(r"[^A-Z0-9]", "", str(code).upper())

def lookup(code): return hmac.new(key(), b"code:" + norm(code).encode(), hashlib.sha256).hexdigest()[:32]

def pw_hash(password, salt): return hashlib.scrypt(password.encode(), salt=salt, n=2 ** 14, r=8, p=1, dklen=32).hex()

def new_random():
    s = "".join(secrets.choice(ALPHABET) for _ in range(16)); return "-".join(s[i:i + 4] for i in range(0, 16, 4))

def _clean_label(s): return "".join(c for c in str(s or "") if c.isprintable()).strip()[:40]

def _norm_host(h):
    h = str(h or "").strip().lower().rstrip(".")
    if not re.fullmatch(HOST_RE, h): raise ValueError("an address like site.example.com")
    return h

def register(host, name, upstream, no_tls_verify=False):
    """Put an address behind the gate (keeps any codes it already had). `upstream` is where the app really is."""
    host = _norm_host(host)
    u = urlsplit(str(upstream))
    if u.scheme not in ("http", "https") or not u.hostname or not u.port or u.username or u.path not in ("", "/") or u.query:
        raise ValueError("upstream: like http://localhost:8096")
    with _locked():
        d = load(); h = d["hosts"].setdefault(host, {"codes": []})
        h.update(name=_clean_label(name) or host, upstream=f"{u.scheme}://{u.hostname}:{u.port}", no_tls_verify=bool(no_tls_verify), active=True)
        save(d)
    return {"host": host}

def deactivate(host, forget=False):
    host = _norm_host(host)
    with _locked():
        d = load()
        if forget: d["hosts"].pop(host, None)
        elif host in d["hosts"]: d["hosts"][host]["active"] = False
        save(d)

def policy_error(pw):
    if len(pw) < 8: return "at least 8 characters"
    if len(pw) > 100: return "100 characters at most"
    if pw.lower() in COMMON or len(set(pw)) < 4: return "that one is too easy to guess"
    return ""

def create_code(host, kind="random", password=None, label="", expires="1d", by=""):
    """Make a code. A random one is returned once (only its hash is kept); a chosen password is never returned."""
    host = _norm_host(host)
    if kind not in ("random", "password"): raise ValueError("a random code or a password")
    if expires not in EXPIRES: raise ValueError("how long: 1h, 1d, 7d, 30d or never")
    now = int(time.time())
    rec = {"id": secrets.token_hex(4), "label": _clean_label(label), "kind": kind, "created": now, "by": _clean_label(by),
           "expires": now + EXPIRES[expires] if EXPIRES[expires] else 0, "revoked": False}
    secret = None
    if kind == "random":
        secret = new_random(); rec["lookup"] = lookup(secret)
    else:
        err = policy_error(str(password or ""))
        if err: raise ValueError("password: " + err)
        salt = os.urandom(16); rec["salt"] = salt.hex(); rec["hash"] = pw_hash(str(password), salt)
    with _locked():
        d = load(); h = d["hosts"].get(host)
        if not h or not h.get("active"): raise ValueError(f"{host} isn't behind a code")
        live = [c for c in h["codes"] if not c.get("revoked") and (not c.get("expires") or c["expires"] > now)]
        if len(live) >= MAX_CODES: raise ValueError(f"{MAX_CODES} codes at most — revoke one you don't need")
        if kind == "password" and any(c.get("kind") == "password" and c.get("salt") and hmac.compare_digest(pw_hash(str(password), bytes.fromhex(c["salt"])), c["hash"]) for c in live):
            raise ValueError("another code already uses that password")
        h["codes"] = [c for c in h["codes"] if not (c.get("revoked") or (c.get("expires") and c["expires"] < now - 7 * 86400))][-60:] + [rec]   # old, dead ones drop off
        save(d)
    return {"id": rec["id"], "code": secret, "expires": rec["expires"], "label": rec["label"], "kind": kind}

def revoke(host, cid):
    host = _norm_host(host)
    with _locked():
        d = load(); h = d["hosts"].get(host) or {"codes": []}; hit = False
        for c in h["codes"]:
            if c.get("id") == cid or cid == "all":
                if not c.get("revoked"): c["revoked"] = True; hit = True
        if hit: save(d)
    if not hit: raise ValueError("no such code")
    return {"ok": True}

def listing(host):
    host = _norm_host(host); now = time.time(); u = usage().get(host, {}); d = load(); h = d["hosts"].get(host) or {}
    out = []
    for c in h.get("codes", []):
        if c.get("revoked"): continue
        s = u.get(c["id"], {})
        out.append({"id": c["id"], "label": c.get("label", ""), "kind": c.get("kind"), "created": c.get("created"), "expires": c.get("expires", 0),
                    "expired": bool(c.get("expires") and c["expires"] <= now), "logins": s.get("logins", 0), "last_used": s.get("last_used", 0)})
    return {"host": host, "active": bool(h.get("active")), "name": h.get("name", ""), "codes": out}


# ── the visitor's cookie ────────────────────────────────────────────────────────────────
def _b64(b): return base64.urlsafe_b64encode(b).decode().rstrip("=")
def _unb64(s): return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))

def make_cookie(host, cid, exp):
    body = _b64(json.dumps({"h": host, "c": cid, "e": int(exp)}, separators=(",", ":")).encode())
    return f"v1.{body}.{_b64(hmac.new(key(), (host + '|' + body).encode(), hashlib.sha256).digest())}"

def read_cookie(value, host):
    """-> (code id, expiry) or None. Checks the signature, the address it was made for, and its own expiry."""
    try:
        v, body, mac = value.split(".")
        if v != "v1" or not hmac.compare_digest(_b64(hmac.new(key(), (host + "|" + body).encode(), hashlib.sha256).digest()), mac): return None
        p = json.loads(_unb64(body))
        if p.get("h") != host or int(p.get("e", 0)) < time.time(): return None
        return str(p["c"]), int(p["e"])
    except Exception: return None


# ── the server ──────────────────────────────────────────────────────────────────────────
HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade"}

_cfg = {"mtime": 0, "d": {"hosts": {}}}
def config():
    try: st = os.stat(_p("gate.json")); m = (st.st_mtime_ns, st.st_ctime_ns, st.st_ino, st.st_size)     # (two quick edits can share a timestamp)
    except OSError: return _cfg["d"]
    if m != _cfg["mtime"]:
        try: _cfg["d"] = json.load(open(_p("gate.json"))); _cfg["d"].setdefault("hosts", {}); _cfg["mtime"] = m
        except (OSError, ValueError): pass                 # keep the last good copy
    return _cfg["d"]

class Limiter:
    """Guessing a code is slowed down hard: per visitor (5 wrong in 10 min → 15 min wait, doubling each time) and per address
    (30 wrong in 10 min from anyone → a 5 min pause for new sign-ins; people already in are not affected)."""
    def __init__(self): self.lock = threading.Lock(); self.ip = {}; self.host = {}
    def wait(self, host, ip):
        now = time.time()
        with self.lock:
            a = self.ip.get((host, ip)); h = self.host.get(host)
            return max(int(max((a or {}).get("until", 0), (h or {}).get("until", 0)) - now), 0)
    def fail(self, host, ip):
        now = time.time()
        with self.lock:
            if len(self.ip) > 20000: self.ip = {k: v for k, v in self.ip.items() if v.get("until", 0) > now or now - v["t"] < 600}
            a = self.ip.setdefault((host, ip), {"n": 0, "t": now, "strikes": 0, "until": 0})
            if now - a["t"] > 600 and a["until"] < now: a.update(n=0, t=now)
            a["n"] += 1
            if a["n"] >= 5: a.update(n=0, t=now, until=now + min(900 * 2 ** a["strikes"], 21600)); a["strikes"] += 1
            h = self.host.setdefault(host, {"n": 0, "t": now, "until": 0})
            if now - h["t"] > 600: h.update(n=0, t=now)
            h["n"] += 1
            if h["n"] >= 30: h.update(n=0, t=now, until=now + 300)
    def ok(self, host, ip):
        with self.lock: self.ip.pop((host, ip), None)

LIMITS = Limiter()
_usage = {"d": None, "dirty": False, "lock": threading.Lock()}

def _note_login(host, cid):
    now = int(time.time())
    with _usage["lock"]:
        if _usage["d"] is None: _usage["d"] = usage()
        u = _usage["d"].setdefault(host, {}).setdefault(cid, {"logins": 0, "last_used": 0}); u["logins"] += 1; u["last_used"] = now
        if not _usage["dirty"]: _usage["dirty"] = True; threading.Timer(3, _flush_usage).start()

def _flush_usage():
    with _usage["lock"]:
        _usage["dirty"] = False
        try:
            os.makedirs(os.path.join(DIR, "run"), exist_ok=True)
            tmp = os.path.join(DIR, "run", "usage.json.tmp")
            with open(tmp, "w") as f: json.dump(_usage["d"], f)
            os.replace(tmp, os.path.join(DIR, "run", "usage.json"))
        except OSError as e: print("couldn't save usage:", e, file=sys.stderr)

def find_code(h, typed):
    """The live code that `typed` matches, or None. Random codes are looked up by keyed hash; passwords are checked with scrypt."""
    now = time.time(); live = [c for c in h.get("codes", []) if not c.get("revoked") and (not c.get("expires") or c["expires"] > now)]
    n = norm(typed)
    if 12 <= len(n) <= 24:
        lk = lookup(n)
        for c in live:
            if c.get("kind") == "random" and hmac.compare_digest(c.get("lookup", ""), lk): return c
    typed = typed[:100]
    for c in live:
        if c.get("kind") == "password" and c.get("salt") and hmac.compare_digest(pw_hash(typed, bytes.fromhex(c["salt"])), c.get("hash", "")): return c
    return None

def code_live(h, cid):
    now = time.time()
    return next((c for c in h.get("codes", []) if c.get("id") == cid and not c.get("revoked") and (not c.get("expires") or c["expires"] > now)), None)

PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><meta name="color-scheme" content="light dark"><title>{title}</title><style>
:root{{--bg:#f4f4f8;--card:#fff;--text:#16161d;--sub:#63636f;--line:#dcdce4;--accent:#3e7bff;--bad:#c8372d}}
@media(prefers-color-scheme:dark){{:root{{--bg:#0f1014;--card:#1b1c22;--text:#f0f0f6;--sub:#9a9aa8;--line:#2d2e37;--bad:#ff7b72}}}}
*{{box-sizing:border-box}}body{{margin:0;min-height:100vh;display:grid;place-items:center;padding:20px;background:var(--bg);color:var(--text);font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}}
main{{width:100%;max-width:400px;background:var(--card);border:1px solid var(--line);border-radius:22px;padding:30px 26px;box-shadow:0 10px 40px rgba(0,0,0,.12)}}
h1{{font-size:22px;margin:0 0 6px}}p{{margin:0 0 18px;color:var(--sub)}}label{{display:block;font-size:13px;color:var(--sub);margin-bottom:6px}}
.f{{display:flex;gap:8px}}input[type=password],input[type=text]{{flex:1;min-width:0;font:inherit;font-size:17px;letter-spacing:.04em;padding:13px 14px;border-radius:14px;border:1px solid var(--line);background:transparent;color:var(--text)}}
input:focus{{outline:2px solid var(--accent);outline-offset:1px}}button{{font:inherit;font-weight:600;border:0;border-radius:14px;cursor:pointer}}
.go{{width:100%;margin-top:14px;padding:14px;background:var(--accent);color:#fff}}.eye{{padding:0 14px;background:transparent;border:1px solid var(--line);color:var(--sub)}}
.err{{color:var(--bad);margin:12px 0 0;font-size:14px}}small{{display:block;margin-top:18px;color:var(--sub);font-size:12px;text-align:center}}</style></head><body><main>
<h1>{head}</h1><p>{lead}</p>
<form method="post" action="{login}" autocomplete="off"><input type="hidden" name="next" value="{next}">
<label for="c">Access code</label><div class="f"><input id="c" name="code" type="password" autocapitalize="none" autocorrect="off" spellcheck="false" required autofocus maxlength="100">
<button type="button" class="eye" id="e" aria-label="Show the code">Show</button></div>{err}<button class="go" type="submit">Continue</button></form>
<small>Protected by Nova</small></main><script>var i=document.getElementById("c"),b=document.getElementById("e");b.onclick=function(){{var s=i.type=="password";i.type=s?"text":"password";b.textContent=s?"Hide":"Show"}}</script></body></html>"""

def _esc(s): return str(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;")

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "nova-gate"; sys_version = ""
    timeout = 120                                   # an idle or half-sent request is dropped after this
    def log_message(self, *a): pass                 # (nothing a visitor typed ever goes in a log)

    # ── small helpers ──
    def ip(self):
        v = (self.headers.get("CF-Connecting-IP") or "").strip()
        try: return str(ipaddress.ip_address(v))
        except ValueError: return self.client_address[0]

    def reply(self, code, body=b"", ctype="text/plain; charset=utf-8", extra=(), close=False):
        if isinstance(body, str): body = body.encode()
        self.send_response_only(code)
        for k, v in (("Content-Type", ctype), ("Content-Length", str(len(body))), ("Cache-Control", "no-store"), ("X-Content-Type-Options", "nosniff"),
                     ("Referrer-Policy", "no-referrer"), ("X-Robots-Tag", "noindex")) + tuple(extra): self.send_header(k, v)
        if close: self.send_header("Connection", "close"); self.close_connection = True
        self.end_headers()
        if self.command != "HEAD": self.wfile.write(body)

    def page(self, h, host, err="", nxt="/", code=401):
        name = _esc(h.get("name") or host)
        body = PAGE.format(title=name, head=f"Open {name}", lead="This site needs an access code. Whoever shared it with you can give you one.",
                           login=LOGIN, next=_esc(nxt), err=f'<div class="err" role="alert">{_esc(err)}</div>' if err else "")
        self.reply(code, body, "text/html; charset=utf-8", (("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'"),))

    def cookie_value(self):
        for line in self.headers.get_all("Cookie") or []:
            for part in line.split(";"):
                k, _, v = part.strip().partition("=")
                if k == COOKIE: return v
        return ""

    def host(self): return (self.headers.get("Host") or "").strip().lower().rstrip(".")

    # ── routing ──
    def handle_one_request(self):
        try: super().handle_one_request()
        except (BrokenPipeError, ConnectionResetError, TimeoutError, socket.timeout): self.close_connection = True

    def do_request(self):
        path = self.path
        if path.startswith("http://") or path.startswith("https://"):       # absolute-form: only the path counts, never the host in it
            u = urlsplit(path); path = (u.path or "/") + ("?" + u.query if u.query else "")
        if not path.startswith("/") or any(c in path for c in " \r\n\t\x00"): return self.reply(400, "bad request", close=True)
        self.path = path
        if path == HEALTH: return self.reply(200, '{"ok":true}', "application/json")
        host = self.host(); h = config()["hosts"].get(host)
        if not h or not h.get("active"): return self.reply(404, "not found")
        route = path.split("?", 1)[0]
        if route == LOGIN: return self.login(h, host)
        if route == LOGOUT:
            return self.reply(303, b"", extra=(("Location", "/"), ("Set-Cookie", f"{COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax")))
        if route.startswith("/__nova_gate/"): return self.reply(404, "not found")
        s = read_cookie(self.cookie_value(), host)
        if not s or not code_live(h, s[0]):
            # no valid session: a browser gets the code page, anything else a plain refusal
            if self.command == "GET" and "text/html" in (self.headers.get("Accept") or ""):
                return self.page(h, host, nxt=path[:500])
            self.drain(); return self.reply(401, "an access code is needed", extra=(("WWW-Authenticate", 'Nova-Code realm="access code"'),))
        return self.forward(h, host)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = do_OPTIONS = do_request

    def drain(self):
        """Read (and drop) a small request body so the connection stays usable; a big one just closes it."""
        try: n = int(self.headers.get("Content-Length") or 0)
        except ValueError: n = 0
        if n and n <= 65536: self.rfile.read(n)
        elif n or self.headers.get("Transfer-Encoding"): self.close_connection = True

    # ── the code page ──
    def login(self, h, host):
        if self.command != "POST": return self.page(h, host, nxt=(parse_qs(urlsplit(self.path).query).get("next") or ["/"])[0][:500], code=200)
        try: n = int(self.headers.get("Content-Length") or 0)
        except ValueError: n = -1
        if not 0 <= n <= 4096: return self.reply(413, "too big", close=True)
        form = parse_qs(self.rfile.read(n).decode("utf-8", "replace")); typed = (form.get("code") or [""])[0].strip()
        nxt = (form.get("next") or ["/"])[0]
        if not nxt.startswith("/") or nxt.startswith("//") or "\\" in nxt or any(ord(c) < 32 for c in nxt): nxt = "/"
        site = self.headers.get("Sec-Fetch-Site")
        if site and site not in ("same-origin", "none"): return self.reply(403, "not allowed")
        ip = self.ip(); wait = LIMITS.wait(host, ip)
        if wait: return self.page(h, host, f"Too many tries. Wait {max(wait // 60, 1)} minute{'s' if wait > 90 else ''} and try again.", nxt, 429)
        c = find_code(h, typed) if typed else None
        if not c:
            LIMITS.fail(host, ip); time.sleep(0.5)
            print(f"gate: wrong code for {host} from {ip}", file=sys.stderr)
            return self.page(h, host, "That code didn't work.", nxt)
        LIMITS.ok(host, ip); _note_login(host, c["id"])
        exp = min(c["expires"], time.time() + SESSION_MAX) if c.get("expires") else time.time() + SESSION_MAX
        print(f"gate: {host} opened with code {c['id']} ({c.get('label') or 'no label'}) from {ip}", file=sys.stderr)
        self.reply(303, b"", extra=(("Location", nxt), ("Set-Cookie", f"{COOKIE}={make_cookie(host, c['id'], exp)}; Path=/; Max-Age={int(exp - time.time())}; HttpOnly; Secure; SameSite=Lax")))

    # ── passing it on ──
    def request_headers(self, host):
        out = []; drop = set(HOP)
        for v in self.headers.get_all("Connection") or []: drop |= {t.strip().lower() for t in v.split(",")}
        cookies = []
        for k, v in self.headers.items():
            lk = k.lower()
            if lk in drop or lk == "expect": continue
            if lk == "cookie":
                cookies += [p.strip() for p in v.split(";") if p.strip() and not p.strip().startswith(COOKIE + "=")]; continue
            out.append((k, v))
        if cookies: out.append(("Cookie", "; ".join(cookies)))
        if not self.headers.get("X-Forwarded-For"): out.append(("X-Forwarded-For", self.ip()))
        if not self.headers.get("X-Forwarded-Proto"): out.append(("X-Forwarded-Proto", "https"))
        return out

    def forward(self, h, host):
        u = urlsplit(h["upstream"]); tls = u.scheme == "https"
        te = (self.headers.get("Transfer-Encoding") or "").lower(); cl = self.headers.get_all("Content-Length")
        if (te and cl) or (cl and len(set(x.strip() for x in cl)) > 1) or (te and te != "chunked"): return self.reply(400, "bad request", close=True)
        if cl and not cl[0].strip().isdigit(): return self.reply(400, "bad request", close=True)
        upgrade = "upgrade" in (self.headers.get("Connection") or "").lower() and (self.headers.get("Upgrade") or "").lower() == "websocket"
        hdrs = self.request_headers(host)
        try:
            if upgrade: return self.tunnel(u, tls, h, hdrs)
            ctx = (ssl._create_unverified_context() if h.get("no_tls_verify") else ssl.create_default_context()) if tls else None
            conn = (http.client.HTTPSConnection(u.hostname, u.port, timeout=10, context=ctx) if tls else http.client.HTTPConnection(u.hostname, u.port, timeout=10))
            conn.connect(); conn.sock.settimeout(900)
            conn.putrequest(self.command, self.path, skip_host=True, skip_accept_encoding=True)
            for k, v in hdrs: conn.putheader(k, v)
            if te == "chunked": conn.putheader("Transfer-Encoding", "chunked")
            conn.endheaders()
        except (ValueError, http.client.InvalidURL): return self.reply(400, "bad request", close=True)
        except (OSError, ssl.SSLError): return self.reply(502, "That site isn't answering right now.", close=True)
        try:
            if te == "chunked":
                for d in self.read_chunked(): conn.send(f"{len(d):x}\r\n".encode() + d + b"\r\n")
                conn.send(b"0\r\n\r\n")
            elif cl and int(cl[0]):
                left = int(cl[0])
                while left:
                    d = self.rfile.read(min(left, 65536))
                    if not d: raise EOFError
                    conn.send(d); left -= len(d)
            resp = conn.getresponse()
        except (OSError, EOFError, ValueError, http.client.HTTPException):
            conn.close(); return self.reply(502, "That site isn't answering right now.", close=True)
        try: self.relay(resp)
        finally: conn.close()

    def read_chunked(self):
        while True:
            size = int(self.rfile.readline(1024).split(b";")[0].strip(), 16)
            if size == 0:
                while self.rfile.readline(8192) not in (b"\r\n", b"\n", b""): pass
                return
            left = size
            while left:
                d = self.rfile.read(min(left, 65536))
                if not d: raise EOFError
                yield d; left -= len(d)
            self.rfile.readline(4)

    def relay(self, resp):
        self.send_response_only(resp.status, resp.reason)
        drop = set(HOP) | {"content-length"}
        for v in resp.msg.get_all("Connection") or []: drop |= {t.strip().lower() for t in v.split(",")}
        cc = None
        for k, v in resp.msg.items():
            lk = k.lower()
            if lk in drop: continue
            if lk == "cache-control": cc = v; continue
            self.send_header(k, v)
        # Cloudflare's own cache must never hand a signed-in visitor's pages or pictures to someone without a code
        if cc is None: cc = "private"
        elif "private" not in cc.lower() and "no-store" not in cc.lower(): cc = "private, " + cc
        self.send_header("Cache-Control", cc)
        nobody = self.command == "HEAD" or resp.status in (204, 304) or 100 <= resp.status < 200
        length = resp.getheader("Content-Length")
        if nobody:
            if length is not None: self.send_header("Content-Length", length)
            self.end_headers(); return
        if resp.chunked: self.send_header("Transfer-Encoding", "chunked")
        elif length is not None: self.send_header("Content-Length", length)
        else: self.send_header("Connection", "close"); self.close_connection = True
        self.end_headers()
        try:
            while True:
                d = resp.read1(65536)
                if not d: break
                self.wfile.write(f"{len(d):x}\r\n".encode() + d + b"\r\n" if resp.chunked else d)
            if resp.chunked: self.wfile.write(b"0\r\n\r\n")
        except (OSError, http.client.HTTPException): self.close_connection = True

    def tunnel(self, u, tls, h, hdrs):
        """A WebSocket: the handshake goes through, then bytes are passed both ways until either side closes."""
        try:
            s = socket.create_connection((u.hostname, u.port), timeout=10)
            if tls: s = (ssl._create_unverified_context() if h.get("no_tls_verify") else ssl.create_default_context()).wrap_socket(s, server_hostname=u.hostname)
        except (OSError, ssl.SSLError): return self.reply(502, "That site isn't answering right now.", close=True)
        head = f"{self.command} {self.path} HTTP/1.1\r\n" + "".join(f"{k}: {v}\r\n" for k, v in hdrs) + "Connection: Upgrade\r\nUpgrade: websocket\r\n\r\n"
        if any(c in head.replace("\r\n", "") for c in "\r\n"): s.close(); return self.reply(400, "bad request", close=True)
        s.sendall(head.encode("latin-1", "replace")); s.settimeout(None); c = self.connection; c.settimeout(None)
        self.close_connection = True
        try:
            while True:
                r, _, _ = select.select([s, c], [], [], 3600)
                if not r: break
                for x in r:
                    d = x.recv(65536)
                    if not d: return
                    (c if x is s else s).sendall(d)
        except OSError: pass
        finally: s.close()


class Server(ThreadingHTTPServer):
    daemon_threads = True; request_queue_size = 128; allow_reuse_address = True
    def __init__(self, *a, **k): super().__init__(*a, **k); self.slots = threading.BoundedSemaphore(300)
    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False): request.close(); return          # far more at once than this home server could serve anyway
        super().process_request(request, client_address)
    def process_request_thread(self, request, client_address):
        try: super().process_request_thread(request, client_address)
        finally: self.slots.release()


def main():
    global DIR
    import argparse
    ap = argparse.ArgumentParser(); ap.add_argument("--port", type=int, default=PORT); ap.add_argument("--dir", default=DIR); ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--init", action="store_true", help="(as root, before the service starts) make the folder and the secret key, then exit")
    a = ap.parse_args(); DIR = a.dir
    if a.init:
        os.makedirs(DIR, exist_ok=True); key(); return
    if a.bind not in ("127.0.0.1", "::1", "localhost"): sys.exit("the gate only listens on this machine")
    try: key()
    except OSError as e: sys.exit(f"no secret key yet ({e}) — it is made by: gate.py --init, run as root before the service starts")
    srv = Server((a.bind, a.port), Handler); print(f"nova-gate on {a.bind}:{a.port}", file=sys.stderr, flush=True)
    try: srv.serve_forever()
    except KeyboardInterrupt: pass


if __name__ == "__main__": main()
