"""
Several servers under one Nova web.

This server (the "head") keeps a read-only window on other Nova servers ("nodes") on the home
network or your tailnet. It pairs with each node as a `head` device: a key, made here, that the
node only lets read its overview, live numbers and events — it can't change anything, see your
devices or approve requests. The head shows all of them together.

Controlling a node from here ("relay"): the browser pairs its own key with the node (approved on the
node's phone or with `sudo nova approve` there) and signs every request for the node itself. This
server only carries those bytes to the node's saved address (certificate pinned) and adds a short
statement, signed with its head key, saying where the browser really is (home network, tailnet or
remote) — so the node applies its normal rules and limits to the browser, not to this server. The
head key never authorizes anything on its own; the node checks the browser's signature as always.

  probe(host)               the node's name, Nova version and certificate fingerprint (to compare
                            with the "Certificate" line that `sudo nova add` shows on that node)
  pair(host, code, pin)     pair with a one-time code from `sudo nova add` on the node
  link_start / link_poll    or ask the node, and its owner approves (Nova app, or sudo nova approve CODE there)
  status()                  every node with its latest overview (polled in the background)
  remove(id)                unpair (the node forgets this head too, if it can be reached)
  relay(id, …)              carry one browser-signed request to a node (see above)

Node records (with their private keys) live in /var/lib/nova-api/nodes.json, readable by nova-api only.
"""
import base64, hashlib, http.client, ipaddress, json, os, re, secrets, socket, ssl, threading, time
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

DATA = "/var/lib/nova-api"
FILE = f"{DATA}/nodes.json"
PORT = 8495
POLL_S = 20
MAX_NODES = 16
_lock = threading.Lock()
_live = {}             # id -> {"t", "ok", "error", "overview", "now"}


# ── where a node may be ──────────────────────────────────────────────────────────────────
def _home_ip(ip):
    a = ipaddress.ip_address(ip)
    if a.is_loopback or a.is_multicast or a.is_unspecified: return False
    return a.is_private or a.is_link_local or (a.version == 4 and a in ipaddress.ip_network("100.64.0.0/10"))

def check_address(host):
    """Only addresses on a home network or a tailnet (no public internet, not this machine)."""
    host = str(host).strip().lower()
    if not host or len(host) > 253 or any(c in host for c in "/@?#\\ "): raise ValueError("give an address like 192.168.1.20")
    try: ips = [host] if _is_ip(host) else sorted({r[4][0] for r in socket.getaddrinfo(host, PORT, proto=socket.IPPROTO_TCP)})
    except socket.gaierror: raise ValueError("can't find that address")
    if not ips or not all(_home_ip(i) for i in ips): raise ValueError("only servers on your home network or tailnet can be added")
    return host

def _is_ip(h):
    try: ipaddress.ip_address(h.strip("[]")); return True
    except ValueError: return False


# ── talking to a node: pinned TLS, signed requests ───────────────────────────────────────
def _conn(host, pin=None, timeout=6):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); ctx.check_hostname = False; ctx.verify_mode = ssl.CERT_NONE
    c = http.client.HTTPSConnection(host, PORT, timeout=timeout, context=ctx); c.connect()
    got = hashlib.sha256(c.sock.getpeercert(binary_form=True)).hexdigest()
    if pin is not None and got != pin:
        c.close(); raise ValueError("that server's certificate has changed — remove it here and add it again if this is expected")
    return c, got

def _read(c):
    r = c.getresponse(); body = r.read(512 * 1024); c.close()
    try: return r.status, json.loads(body or b"{}")
    except ValueError: return r.status, {}

def probe(host):
    host = check_address(host)
    c, pin = _conn(host)
    c.request("GET", "/api/v1/ping"); st, j = _read(c)
    if st != 200 or j.get("name") != "Nova": raise ValueError("that isn't a Nova server")
    return {"host": host, "pin": pin, "pin_short": " ".join(pin[i:i + 4].upper() for i in (0, 4, 8)), "api": j.get("api", "")}

def _call(n, method, path, body=b""):
    key = serialization.load_pem_private_key(n["key"].encode(), None)
    ts, nonce = str(int(time.time() * 1000)), secrets.token_urlsafe(18)
    msg = "\n".join([method, path, ts, nonce, hashlib.sha256(body).hexdigest()]).encode()
    sig = base64.b64encode(key.sign(msg, ec.ECDSA(hashes.SHA256()))).decode()
    c, _ = _conn(n["host"], n["pin"])
    c.request(method, path, body=body or None, headers={"Content-Type": "application/json", "X-Nova-Device": n["device_id"],
              "X-Nova-Time": ts, "X-Nova-Nonce": nonce, "X-Nova-Signature": sig})
    return _read(c)


# ── the list ─────────────────────────────────────────────────────────────────────────────
def _load():
    try: return json.load(open(FILE))
    except (OSError, ValueError): return {"nodes": []}

def _save(d):
    tmp = FILE + ".tmp"
    with open(os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f: json.dump(d, f)
    os.replace(tmp, FILE)

def pair(host, code, pin, head_name):
    host = check_address(host)
    code = "".join(ch for ch in str(code).upper() if ch.isalnum())[:16]
    if not code: raise ValueError("enter the code that sudo nova add shows on that server")
    if not (isinstance(pin, str) and len(pin) == 64 and all(ch in "0123456789abcdef" for ch in pin)): raise ValueError("check the server again first")
    with _lock:
        d = _load()
        if len(d["nodes"]) >= MAX_NODES: raise ValueError(f"up to {MAX_NODES} servers")
        if any(n["host"] == host for n in d["nodes"]): raise ValueError("that server is already here")
    key = ec.generate_private_key(ec.SECP256R1())
    pub = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    c, _ = _conn(host, pin)
    c.request("POST", "/api/v1/pair", body=json.dumps({"code": code, "name": f"{head_name} (Nova web)"[:40], "public_key": pub, "kind": "head"}),
              headers={"Content-Type": "application/json"})
    st, j = _read(c)
    if st != 200 or not j.get("device_id"):
        raise ValueError({401: "that code didn't work — check it, or run sudo nova add again", 429: "too many tries — wait 10 minutes"}.get(st, j.get("error") or f"pairing failed ({st})"))
    if j.get("kind") != "head":
        raise ValueError("that server's Nova is too old to be added here — run sudo nova update on it first")
    n = {"id": secrets.token_hex(6), "host": host, "pin": pin, "device_id": j["device_id"],
         "key": key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode(),
         "name": str(j.get("name") or host)[:60], "lan_url": str(j.get("lan_url") or f"https://{host}:{PORT}")[:200],
         "remote_url": str(j.get("remote_url") or "")[:200], "added": time.strftime("%Y-%m-%d %H:%M")}
    with _lock:
        d = _load(); d["nodes"].append(n); _save(d)
    threading.Thread(target=_poll_one, args=(n,), daemon=True).start()
    return _public(n)

# ── finding them: the same "NOVA?" call the app makes on the home network ──────────────────
def discover(own_hosts=(), wait=1.5):
    """Nova servers that answer on the home network (not this one, not ones already here): [{"name", "host", "api"}]."""
    linked = {n["host"] for n in _load()["nodes"]} | set(own_hosts)
    found = {}
    sk = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); sk.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1); sk.settimeout(.25)
    try:
        sk.sendto(b"NOVA?", ("255.255.255.255", PORT + 1))
        end = time.time() + wait
        while time.time() < end:
            try: msg, (ip, _) = sk.recvfrom(2048)
            except socket.timeout: continue
            try: j = json.loads(msg)
            except ValueError: continue
            if j.get("nova") != 1 or ip in linked or ip in found: continue
            try: check_address(ip)
            except ValueError: continue
            found[ip] = {"name": str(j.get("name") or ip)[:60], "host": ip, "api": str(j.get("api", ""))[:20]}
    except OSError: pass
    finally: sk.close()
    return list(found.values())


# ── linking by approval: no code to fetch from the other server first ─────────────────────
# This server asks the other one to be added; the other server's owner approves it in the Nova app (or with
# sudo nova approve CODE there). The certificate seen when checking the server (probe) is pinned for every
# step, so nobody in the middle can swap in their own key; whoever approves, it's only ever the read-only window.
_links = {}            # link id -> {"host", "pin", "key", "rid", "code", "expires"}

def link_start(host, pin, head_name):
    host = check_address(host)
    if not (isinstance(pin, str) and len(pin) == 64 and all(ch in "0123456789abcdef" for ch in pin)): raise ValueError("check the server again first")
    with _lock:
        d = _load()
        if len(d["nodes"]) >= MAX_NODES: raise ValueError(f"up to {MAX_NODES} servers")
        if any(n["host"] == host for n in d["nodes"]): raise ValueError("that server is already here")
        now = time.time()
        for k in [k for k, v in _links.items() if v["expires"] < now]: _links.pop(k, None)
        if len(_links) >= 8: raise ValueError("too many links waiting — finish or wait for one first")
    key = ec.generate_private_key(ec.SECP256R1())
    pub = key.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    c, _ = _conn(host, pin)
    c.request("POST", "/api/v1/browser/request", body=json.dumps({"kind": "head", "name": f"{head_name} (Nova server)"[:40], "public_key": pub}),
              headers={"Content-Type": "application/json"})
    st, j = _read(c)
    if st != 200 or not j.get("id"):
        raise ValueError({403: j.get("error") or "that server won't take links from here", 429: "too many tries — wait a few minutes"}.get(st, j.get("error") or f"linking failed ({st})"))
    lid = secrets.token_hex(6)
    with _lock:
        _links[lid] = {"host": host, "pin": pin, "rid": j["id"], "code": j.get("code", ""), "expires": time.time() + int(j.get("expires_in", 600)),
                       "key": key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()).decode()}
    return {"id": lid, "code": j.get("code", ""), "expires_in": int(j.get("expires_in", 600))}

def link_poll(lid):
    """"pending" | "expired" | "approved" (then the server is in the list) — or raises if the server answered strangely."""
    with _lock: lk = _links.get(lid)
    if not lk or lk["expires"] < time.time(): _links.pop(lid, None); return {"state": "expired"}
    c, _ = _conn(lk["host"], lk["pin"])
    c.request("GET", f"/api/v1/browser/request/{lk['rid']}"); st, j = _read(c)
    if st == 404: _links.pop(lid, None); return {"state": "expired"}
    if j.get("state") != "approved": return {"state": "pending", "code": lk["code"]}
    if j.get("kind") != "head" or not j.get("device_id"):
        _links.pop(lid, None); raise ValueError("that server's Nova is too old to link this way — run sudo nova update on it first")
    n = {"id": secrets.token_hex(6), "host": lk["host"], "pin": lk["pin"], "device_id": str(j["device_id"])[:40], "key": lk["key"],
         "name": str(j.get("name") or lk["host"])[:60], "lan_url": str(j.get("lan_url") or f"https://{lk['host']}:{PORT}")[:200],
         "remote_url": str(j.get("remote_url") or "")[:200], "added": time.strftime("%Y-%m-%d %H:%M")}
    with _lock:
        _links.pop(lid, None)
        d = _load()
        if any(x["host"] == n["host"] for x in d["nodes"]): return {"state": "approved", "node": next(_public(x) for x in d["nodes"] if x["host"] == n["host"])}
        d["nodes"].append(n); _save(d)
    threading.Thread(target=_poll_one, args=(n,), daemon=True).start()
    return {"state": "approved", "node": _public(n)}


def remove(nid):
    with _lock:
        d = _load(); n = next((x for x in d["nodes"] if x["id"] == nid), None)
        if not n: return False
        d["nodes"] = [x for x in d["nodes"] if x["id"] != nid]; _save(d)
    _live.pop(nid, None)
    try: _call(n, "DELETE", f"/api/v1/devices/{n['device_id']}")      # the node forgets this head too
    except Exception: pass
    return True

def _public(n):
    """What the web may see: never the key or the device id."""
    live = _live.get(n["id"], {})
    o = live.get("overview") or {}; srv = o.get("server") or {}; st = o.get("status") or {}; now = live.get("now") or {}
    return {"id": n["id"], "host": n["host"], "name": srv.get("display_name") or srv.get("name") or n["name"],
            "lan_url": n["lan_url"], "remote_url": n["remote_url"], "added": n["added"],
            "ok": bool(live.get("ok")), "error": live.get("error", ""), "seen": live.get("t", 0),
            "level": st.get("level", ""), "headline": st.get("headline", ""), "containers": o.get("containers") or {},
            "cpu": now.get("cpu"), "mem": now.get("mem"), "temp": now.get("temp"), "uptime_s": now.get("uptime_s") or srv.get("uptime_s"),
            "api": (live.get("who") or {}).get("api") or n.get("api", ""), "relay": bool((live.get("who") or {}).get("relay"))}

def statement(method, path, rnonce, inner_nonce, via, ip, ts):
    """What the head signs about a relayed request (the node checks it with the head device's key)."""
    return "\n".join(["nova-relay", method, path, rnonce, inner_nonce, via, ip, ts]).encode()

RELAY_MAX = 4 * 1024 * 1024
RELAY_PATH = re.compile(r"/api/v1/[A-Za-z0-9._~\-/]*(\?[A-Za-z0-9._~\-=&%+,:]*)?")
_INNER = {"X-Nova-Device": r"[A-Za-z0-9_-]{1,64}", "X-Nova-Time": r"\d{10,16}", "X-Nova-Nonce": r"[A-Za-z0-9_-]{16,64}",
          "X-Nova-Signature": r"[A-Za-z0-9+/]{20,200}={0,2}"}
_UNSIGNED = re.compile(r"/api/v1/browser/request(/[A-Za-z0-9_-]{8,64})?")      # asking to be added, and waiting for the answer

def relay(nid, method, path, hdrs, body, via, ip):
    """One request a browser signed for node `nid`, carried there. Returns (status, json) from the node."""
    n = next((x for x in _load()["nodes"] if x["id"] == nid), None)
    if not n: raise LookupError("no such server")
    if method not in ("GET", "POST", "DELETE"): raise ValueError("method: GET, POST or DELETE")
    if not isinstance(path, str) or len(path) > 2048 or not RELAY_PATH.fullmatch(path) or ".." in path or "//" in path:
        raise ValueError("that isn't a Nova API path")
    p0 = path.split("?", 1)[0]
    if p0 in ("/api/v1/pair", "/api/v1/crash"): raise ValueError("that can't be done through another server")
    if not isinstance(hdrs, dict) or set(hdrs) - set(_INNER): raise ValueError("only the four X-Nova signature headers")
    inner = {}
    for k, pat in _INNER.items():
        v = hdrs.get(k)
        if v is None: continue
        if not isinstance(v, str) or not re.fullmatch(pat, v): raise ValueError(f"bad {k}")
        inner[k] = v
    unsigned = (method == "POST" and p0 == "/api/v1/browser/request") or (method == "GET" and _UNSIGNED.fullmatch(p0) and p0 != "/api/v1/browser/request")
    if len(inner) != 4 and not (unsigned and not inner): raise ValueError("sign the request for that server")
    if not isinstance(body, str): raise ValueError("body: a string")
    raw = body.encode("utf-8")
    if len(raw) > 64 * 1024: raise ValueError("body too large")
    if method == "GET" and raw: raise ValueError("a GET has no body")
    if via not in ("lan", "tailnet", "remote"): raise ValueError("bad origin")
    key = serialization.load_pem_private_key(n["key"].encode(), None)
    ts, rn = str(int(time.time() * 1000)), secrets.token_urlsafe(18)
    sig = base64.b64encode(key.sign(statement(method, path, rn, inner.get("X-Nova-Nonce", ""), via, ip, ts), ec.ECDSA(hashes.SHA256()))).decode()
    h = {"Content-Type": "application/json", **inner, "X-Nova-Relay-Head": n["device_id"], "X-Nova-Relay-Via": via, "X-Nova-Relay-IP": ip,
         "X-Nova-Relay-Time": ts, "X-Nova-Relay-Nonce": rn, "X-Nova-Relay-Sig": sig}
    try:
        c, _ = _conn(n["host"], n["pin"], timeout=200)
        c.request(method, path, body=raw if method != "GET" else None, headers=h)
        r = c.getresponse(); data = r.read(RELAY_MAX + 1); ctype = r.getheader("Content-Type", ""); c.close()
    except ValueError: raise
    except (OSError, http.client.HTTPException):
        raise ConnectionError(f"can't reach {n['name']} right now")
    if len(data) > RELAY_MAX: raise ValueError("that server's answer is too big to pass along")
    if not ctype.startswith("application/json"): return r.status, {"error": "not_relayable", "message": "Open that server's own Nova web for this."}
    try: j = json.loads(data or b"{}")
    except ValueError: j = {}
    return r.status, j

def status():
    return [_public(n) for n in _load()["nodes"]]


# ── background polling ───────────────────────────────────────────────────────────────────
def _poll_one(n):
    try:
        s1, o = _call(n, "GET", "/api/v1/overview")
        if s1 == 401: raise ValueError("this server forgot Nova web here — remove it and add it again")
        if s1 != 200: raise ValueError(f"answered {s1}")
        s2, st = _call(n, "GET", "/api/v1/stats?since=9e12")
        prev = _live.get(n["id"], {}); who = prev.get("who")
        if not who or time.time() - prev.get("who_t", 0) > 600:       # its Nova version (can it be controlled from here?)
            s3, w = _call(n, "GET", "/api/v1/whoami"); who = w if s3 == 200 else (who or {})
            prev = {**prev, "who_t": time.time()}
        _live[n["id"]] = {"t": time.time(), "ok": True, "overview": o, "now": (st or {}).get("now") if s2 == 200 else None, "who": who, "who_t": prev.get("who_t", 0)}
    except Exception as e:
        prev = _live.get(n["id"], {})
        _live[n["id"]] = {**prev, "ok": False, "error": str(e) if isinstance(e, ValueError) else "can't reach it"}

def poller():
    while True:
        for n in _load()["nodes"]: _poll_one(n)
        time.sleep(POLL_S)

def start():
    threading.Thread(target=poller, daemon=True).start()
