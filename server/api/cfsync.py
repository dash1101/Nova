"""
Labs · Cloudflare auto-setup: put one of your server's web apps on your own domain, through the
Cloudflare tunnel you already have, behind the same Cloudflare Access login as Nova.

Order matters, and is always the same:
  1. protect   — add the address to the Access application that already protects Nova
  2. name      — a DNS record pointing the address at your tunnel
  3. route     — a public hostname on the tunnel, sending it to the app
Removing goes the other way round (route, name, then protection), so an app is never reachable
without the login, not even for a moment.

Needs a Cloudflare API token (kept root-only in /etc/nova-api/cloudflare.json) with:
  Account · Cloudflare Tunnel · Edit,  Account · Access: Apps and Policies · Edit,  Zone · DNS · Edit
The account and tunnel come from the tunnel token cloudflared already uses.
"""
import base64, json, os, re, urllib.error, urllib.request

TOKEN_FILE = "/etc/nova-api/cloudflare.json"
TUNNEL_TOKEN = "/etc/cloudflared/tunnel-token"
API = "https://api.cloudflare.com/client/v4"
HOST_RE = r"(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}"


def _site():
    try: return json.load(open("/etc/nova-api/config.json"))
    except Exception: return {}

def token():
    try: return json.load(open(TOKEN_FILE)).get("token", "")
    except Exception: return ""

def save_token(t):
    t = str(t).strip()
    if t and not re.fullmatch(r"[A-Za-z0-9_-]{30,80}", t): raise ValueError("that doesn't look like a Cloudflare API token")
    fd = os.open(TOKEN_FILE + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f: json.dump({"token": t}, f)
    os.replace(TOKEN_FILE + ".tmp", TOKEN_FILE)

def tunnel():
    """(account id, tunnel id) from cloudflared's own tunnel token."""
    try:
        t = open(TUNNEL_TOKEN).read().strip()
        d = json.loads(base64.b64decode(t + "=" * (-len(t) % 4)))
        return d["a"], d["t"]
    except Exception:
        raise ValueError("couldn't read the tunnel token (/etc/cloudflared/tunnel-token) — is the tunnel set up with a token?")

def call(method, path, body=None):
    tk = token()
    if not tk: raise ValueError("add a Cloudflare API token first")
    req = urllib.request.Request(API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": f"Bearer {tk}", "Content-Type": "application/json", "User-Agent": "Nova"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r: j = json.loads(r.read())
    except urllib.error.HTTPError as e:
        try: j = json.loads(e.read())
        except Exception: j = {}
        msg = "; ".join(x.get("message", "") for x in j.get("errors", [])) or f"HTTP {e.code}"
        if e.code in (401, 403): msg = f"the API token isn't allowed to do this ({msg}) — check its permissions"
        raise ValueError(f"Cloudflare: {msg}")
    if not j.get("success", False): raise ValueError("Cloudflare: " + ("; ".join(x.get("message", "") for x in j.get("errors", [])) or "failed"))
    return j.get("result")

def _nova_host(): return (re.sub(r"^https?://", "", _site().get("remote_url", "")).split("/")[0]).lower()

def zone_for(host):
    parts = host.split(".")
    for i in range(len(parts) - 1):
        z = call("GET", f"/zones?name={'.'.join(parts[i:])}")
        if z: return z[0]["id"], z[0]["name"]
    raise ValueError(f"none of your Cloudflare zones covers {host}")

def access_app(acct):
    """The Access application that protects Nova (we add app addresses to it, so they share its login and rules)."""
    nova = _nova_host()
    if not nova: raise ValueError("Nova's remote address isn't set (remote_url in /etc/nova-api/config.json)")
    for a in call("GET", f"/accounts/{acct}/access/apps") or []:
        doms = [a.get("domain", "")] + list(a.get("self_hosted_domains") or []) + [d.get("uri", "") for d in a.get("destinations") or [] if isinstance(d, dict)]
        if any(d.split("/")[0].lower() in (nova, "*." + nova.split(".", 1)[1]) for d in doms if d):
            return a
    raise ValueError(f"couldn't find the Cloudflare Access application that protects {nova}")

def _domains(a):
    return [d.get("uri") for d in a.get("destinations") or [] if isinstance(d, dict) and d.get("uri")] or list(a.get("self_hosted_domains") or [a.get("domain")])

def status():
    out = {"token": bool(token()), "nova_host": _nova_host()}
    try: acct, tun = tunnel(); out["tunnel"] = True
    except ValueError as e: out["error"] = str(e); return out
    if not out["token"]: return out
    try:
        call("GET", "/user/tokens/verify")
        cfg = call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}
        out["hostnames"] = [r["hostname"] for r in (cfg.get("config") or {}).get("ingress", []) if r.get("hostname")]
        a = access_app(acct); out["access_app"] = a.get("name"); out["protected"] = _domains(a)
    except ValueError as e: out["error"] = str(e)
    return out

def plan(host, service, no_tls_verify=False):
    """What publishing would change, without changing anything."""
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host): raise ValueError("an address like photos.example.com")
    if host == _nova_host(): raise ValueError("that's Nova's own address")
    if not re.fullmatch(r"https?://(localhost|127\.0\.0\.1|[0-9.]+|\[[0-9a-f:]+\]):[0-9]{1,5}(/[^\s]*)?", str(service)): raise ValueError("service: like http://localhost:8096")
    acct, tun = tunnel(); zid, zname = zone_for(host)
    cfg = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
    ingress = cfg.get("ingress") or [{"service": "http_status:404"}]
    if any(r.get("hostname") == host for r in ingress): raise ValueError(f"{host} is already on your tunnel")
    if call("GET", f"/zones/{zid}/dns_records?name={host}"): raise ValueError(f"{host} already has a DNS record — pick another address, or remove that record in Cloudflare")
    a = access_app(acct)
    return {"host": host, "service": service, "zone": zname, "access_app": a.get("name"),
            "steps": [f"Protect {host} with “{a.get('name')}” — the same Cloudflare login as Nova",
                      f"Add a DNS record: {host} → your tunnel",
                      f"Send {host} to {service} on the server" + (" (without checking its certificate)" if no_tls_verify else "")]}

def apply(host, service, no_tls_verify=False):
    p = plan(host, service, no_tls_verify); host = p["host"]
    acct, tun = tunnel(); zid, _ = zone_for(host); done = []
    try:
        # 1. protect first
        a = access_app(acct); doms = _domains(a)
        if host not in doms:
            body = {k: v for k, v in a.items() if k not in ("id", "aud", "created_at", "updated_at", "policies", "self_hosted_domains", "domain", "destinations", "uid")}
            body.update(domain=doms[0], destinations=[{"type": "public", "uri": d} for d in doms + [host]], type=a.get("type", "self_hosted"))
            body["policies"] = [{"id": x["id"], "precedence": x.get("precedence", i + 1)} for i, x in enumerate(a.get("policies") or []) if x.get("id")]
            call("PUT", f"/accounts/{acct}/access/apps/{a['id']}", body); done.append("access")
        # 2. name
        call("POST", f"/zones/{zid}/dns_records", {"type": "CNAME", "name": host, "content": f"{tun}.cfargotunnel.com", "proxied": True,
                                                  "comment": "Added by Nova"}); done.append("dns")
        # 3. route (read-modify-write: keep every existing rule; the catch-all stays last)
        cur = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
        ingress = [r for r in (cur.get("ingress") or []) if r.get("hostname")]
        catch = [r for r in (cur.get("ingress") or []) if not r.get("hostname")] or [{"service": "http_status:404"}]
        rule = {"hostname": host, "service": service, **({"originRequest": {"noTLSVerify": True}} if no_tls_verify else {})}
        call("PUT", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations", {"config": {**cur, "ingress": ingress + [rule] + catch[-1:]}}); done.append("route")
    except ValueError as e:
        raise ValueError(f"{e} (done so far: {', '.join(done) or 'nothing'} — the app isn't reachable without the login)")
    return {"ok": True, "host": host, "url": f"https://{host}"}

def remove(host):
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host) or host == _nova_host(): raise ValueError("bad address")
    acct, tun = tunnel()
    cur = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
    if any(r.get("hostname") == host for r in cur.get("ingress") or []):
        call("PUT", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations", {"config": {**cur, "ingress": [r for r in cur["ingress"] if r.get("hostname") != host]}})
    zid, _ = zone_for(host)
    for r in call("GET", f"/zones/{zid}/dns_records?name={host}") or []:
        if r.get("type") == "CNAME" and r.get("content", "").endswith(".cfargotunnel.com"): call("DELETE", f"/zones/{zid}/dns_records/{r['id']}")
    a = access_app(acct); doms = _domains(a)
    if host in doms and len(doms) > 1:
        body = {k: v for k, v in a.items() if k not in ("id", "aud", "created_at", "updated_at", "policies", "self_hosted_domains", "domain", "destinations", "uid")}
        left = [d for d in doms if d != host]
        body.update(domain=left[0], destinations=[{"type": "public", "uri": d} for d in left], type=a.get("type", "self_hosted"))
        body["policies"] = [{"id": x["id"], "precedence": x.get("precedence", i + 1)} for i, x in enumerate(a.get("policies") or []) if x.get("id")]
        call("PUT", f"/accounts/{acct}/access/apps/{a['id']}", body)
    return {"ok": True}
