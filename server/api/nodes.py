"""
Several servers under one Nova web.

This server (the "head") keeps a read-only window on other Nova servers ("nodes") on the home
network or your tailnet. It pairs with each node as a `head` device: a key, made here, that the
node only lets read its overview, live numbers and events — it can't change anything, see your
devices or approve requests. The head shows all of them together; to manage a node you open that
node's own Nova web (its own keys, its own approvals), so nothing is ever relayed.

  probe(host)               the node's name, Nova version and certificate fingerprint (to compare
                            with the "Certificate" line that `sudo nova add` shows on that node)
  pair(host, code, pin)     pair with a one-time code from `sudo nova add` on the node
  status()                  every node with its latest overview (polled in the background)
  remove(id)                unpair (the node forgets this head too, if it can be reached)

Node records (with their private keys) live in /var/lib/nova-api/nodes.json, readable by nova-api only.
"""
import base64, hashlib, http.client, ipaddress, json, os, secrets, socket, ssl, threading, time
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
            "api": n.get("api", "")}

def status():
    return [_public(n) for n in _load()["nodes"]]


# ── background polling ───────────────────────────────────────────────────────────────────
def _poll_one(n):
    try:
        s1, o = _call(n, "GET", "/api/v1/overview")
        if s1 == 401: raise ValueError("this server forgot Nova web here — remove it and add it again")
        if s1 != 200: raise ValueError(f"answered {s1}")
        s2, st = _call(n, "GET", "/api/v1/stats?since=9e12")
        _live[n["id"]] = {"t": time.time(), "ok": True, "overview": o, "now": (st or {}).get("now") if s2 == 200 else None}
    except Exception as e:
        prev = _live.get(n["id"], {})
        _live[n["id"]] = {**prev, "ok": False, "error": str(e) if isinstance(e, ValueError) else "can't reach it"}

def poller():
    while True:
        for n in _load()["nodes"]: _poll_one(n)
        time.sleep(POLL_S)

def start():
    threading.Thread(target=poller, daemon=True).start()
