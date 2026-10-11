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

Nova only ever undoes what it did itself: every address it publishes goes into a record
(/etc/nova-api/cloudflare-added.json) with exactly which parts it added — the Access entry, the DNS
record (by its id), the tunnel route. Taking an app off, or `sudo nova cloudflare undo` (everything,
e.g. before uninstalling), removes those parts and nothing else. Your own routes, records and Access
settings are left alone, and a setup that fails halfway is rolled back.
"""
import base64, json, os, re, urllib.error, urllib.request

TOKEN_FILE = "/etc/nova-api/cloudflare.json"
TUNNEL_TOKEN = "/etc/cloudflared/tunnel-token"
LEDGER = "/etc/nova-api/cloudflare-added.json"

def ledger():
    try: return json.load(open(LEDGER))
    except Exception: return {}

def _save_ledger(d):
    fd = os.open(LEDGER + ".tmp", os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as f: json.dump(d, f, indent=1)
    os.replace(LEDGER + ".tmp", LEDGER)
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
    if t and not re.fullmatch(r"[A-Za-z0-9_-]{30,200}", t): raise ValueError("that doesn't look like a Cloudflare API token")
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
        try: call("GET", "/user/tokens/verify")                       # a token from My Profile…
        except ValueError: call("GET", f"/accounts/{acct}/tokens/verify")   # …or one made under Manage Account
        cfg = call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}
        out["hostnames"] = [r["hostname"] for r in (cfg.get("config") or {}).get("ingress", []) if r.get("hostname")]
        a = access_app(acct); out["access_app"] = a.get("name"); out["protected"] = _domains(a)
        # addresses an older Nova published (before the record): their DNS record says "Added by Nova" — note them once
        led = ledger(); new = False
        for h in out["hostnames"]:
            if h in led or h == out["nova_host"] or h not in out["protected"]: continue
            try: rec = _legacy(h, acct, tun)
            except ValueError: rec = None
            if rec: led[h] = {**rec, "service": next((r.get("service") for r in (cfg.get("config") or {}).get("ingress", []) if r.get("hostname") == h), None), "legacy": True}; new = True
        if new: _save_ledger(led)
        out["nova_added"] = {h: ("protected" if r.get("protected_existing") else "added") for h, r in led.items()}   # only these can be taken off from Nova
    except ValueError as e: out["error"] = str(e)
    return out

def _in_use(host, ingress, zid):
    return any(r.get("hostname") == host for r in ingress) or bool(call("GET", f"/zones/{zid}/dns_records?name={host}"))

def _free_name(host, ingress, zid):
    """A nearby address nothing uses yet: immich.example.com → immich-nova.example.com, immich-2…"""
    first, rest = host.split(".", 1)
    for cand in [f"{first}-nova.{rest}"] + [f"{first}-{n}.{rest}" for n in range(2, 6)]:
        if not _in_use(cand, ingress, zid): return cand
    return None

def plan(host, service, no_tls_verify=False):
    """What publishing would change, without changing anything. If the address is already in use,
    says how (and offers a free name nearby, or — for an address already on this tunnel but not behind
    the login — to protect that one instead)."""
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host): raise ValueError("an address like photos.example.com")
    if host == _nova_host(): raise ValueError("that's Nova's own address")
    if not re.fullmatch(r"https?://(localhost|127\.0\.0\.1|[0-9.]+|\[[0-9a-f:]+\]):[0-9]{1,5}(/[^\s]*)?", str(service)): raise ValueError("service: like http://localhost:8096")
    acct, tun = tunnel(); zid, zname = zone_for(host)
    cfg = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
    ingress = cfg.get("ingress") or []
    route = next((r for r in ingress if r.get("hostname") == host), None)
    dns = call("GET", f"/zones/{zid}/dns_records?name={host}") or []
    a = access_app(acct)
    if route or dns:
        to_tunnel = any(r.get("type") == "CNAME" and r.get("content", "") == f"{tun}.cfargotunnel.com" for r in dns)
        protected = host in _domains(a)
        c = {"route": bool(route), "route_service": (route or {}).get("service"), "dns": bool(dns), "dns_to_tunnel": to_tunnel,
             "protected": protected, "nova_added": host in ledger()}
        why = ("Nova already put it on your tunnel" if c["nova_added"] else
               f"it's already on your tunnel (→ {c['route_service']}){' and behind the login' if protected else ', open to anyone — no login'}" if route else
               "it already has a DNS record (something else uses that name)")
        return {"host": host, "service": service, "zone": zname, "access_app": a.get("name"), "conflict": c, "why": why,
                "can_protect": bool(route) and to_tunnel and not protected and not c["nova_added"],
                "suggest": _free_name(host, ingress, zid)}
    return {"host": host, "service": service, "zone": zname, "access_app": a.get("name"),
            "steps": [f"Protect {host} with “{a.get('name')}” — the same Cloudflare login as Nova",
                      f"Add a DNS record: {host} → your tunnel",
                      f"Send {host} to {service} on the server" + (" (without checking its certificate)" if no_tls_verify else "")]}

def protect(host):
    """An address you already have on this tunnel, open to anyone: put it behind Nova's Access login.
    Only the Access application changes — your route and DNS record stay exactly as they are."""
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host) or host == _nova_host(): raise ValueError("bad address")
    acct, tun = tunnel(); zid, _ = zone_for(host)
    cfg = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
    route = next((r for r in cfg.get("ingress") or [] if r.get("hostname") == host), None)
    if not route: raise ValueError(f"{host} isn't on your tunnel")
    if not any(r.get("type") == "CNAME" and r.get("content", "") == f"{tun}.cfargotunnel.com" for r in call("GET", f"/zones/{zid}/dns_records?name={host}") or []):
        raise ValueError(f"{host}'s DNS record doesn't point at this tunnel — Nova leaves it alone")
    a = access_app(acct); doms = _domains(a)
    if host in doms: raise ValueError(f"{host} is already behind the login")
    call("PUT", f"/accounts/{acct}/access/apps/{a['id']}", _access_body(a, doms + [host]))
    led = ledger(); led[host] = {"service": route.get("service"), "zone": zid, "added": int(__import__("time").time()),
                                 "access": True, "dns_id": None, "route": False, "protected_existing": True}
    _save_ledger(led)
    return {"ok": True, "host": host, "url": f"https://{host}"}

def _access_body(a, doms):
    body = {k: v for k, v in a.items() if k not in ("id", "aud", "created_at", "updated_at", "policies", "self_hosted_domains", "domain", "destinations", "uid")}
    body.update(domain=doms[0], destinations=[{"type": "public", "uri": d} for d in doms], type=a.get("type", "self_hosted"))
    body["policies"] = [{"id": x["id"], "precedence": x.get("precedence", i + 1)} for i, x in enumerate(a.get("policies") or []) if x.get("id")]
    return body

def _with_rule(ingress, rule):
    """The tunnel's rules with one more, just before the final catch-all — every other rule (and its order) kept."""
    ingress = list(ingress or [])
    if ingress and not ingress[-1].get("hostname") and not ingress[-1].get("path"): return ingress[:-1] + [rule, ingress[-1]]
    return ingress + [rule, {"service": "http_status:404"}]

def apply(host, service, no_tls_verify=False):
    p = plan(host, service, no_tls_verify); host = p["host"]
    if p.get("conflict"): raise ValueError(f"{host} is in use: {p['why']}")
    acct, tun = tunnel(); zid, _ = zone_for(host)
    rec = {"service": service, "zone": zid, "added": int(__import__("time").time()), "access": False, "dns_id": None, "route": False}
    try:
        # 1. protect first
        a = access_app(acct); doms = _domains(a)
        if host not in doms:
            call("PUT", f"/accounts/{acct}/access/apps/{a['id']}", _access_body(a, doms + [host])); rec["access"] = True
        # 2. name
        r = call("POST", f"/zones/{zid}/dns_records", {"type": "CNAME", "name": host, "content": f"{tun}.cfargotunnel.com", "proxied": True,
                                                       "comment": "Added by Nova"})
        rec["dns_id"] = (r or {}).get("id")
        # 3. route (read-modify-write: every existing rule stays, in order; the catch-all stays last)
        cur = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
        rule = {"hostname": host, "service": service, **({"originRequest": {"noTLSVerify": True}} if no_tls_verify else {})}
        call("PUT", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations", {"config": {**cur, "ingress": _with_rule(cur.get("ingress"), rule)}}); rec["route"] = True
    except ValueError as e:
        # halfway: take back what this attempt added (route, name, then protection), so nothing is left behind
        try: _undo(host, rec, acct, tun)
        except ValueError as e2: raise ValueError(f"{e} — and undoing the partial setup failed too ({e2}); check {host} in Cloudflare")
        raise ValueError(f"{e} (nothing was left changed)")
    led = ledger(); led[host] = rec; _save_ledger(led)
    return {"ok": True, "host": host, "url": f"https://{host}"}

def _undo(host, rec, acct, tun):
    """Remove exactly the parts recorded in `rec`, in the safe order (route, name, then protection)."""
    if rec.get("route"):
        cur = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
        if any(r.get("hostname") == host for r in cur.get("ingress") or []):
            call("PUT", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations", {"config": {**cur, "ingress": [r for r in cur["ingress"] if r.get("hostname") != host]}})
    zid = rec.get("zone")
    if rec.get("dns_id") and zid:
        try: r = call("GET", f"/zones/{zid}/dns_records/{rec['dns_id']}")
        except ValueError: r = None                                # already gone
        if r and r.get("type") == "CNAME" and r.get("content", "").endswith(".cfargotunnel.com"): call("DELETE", f"/zones/{zid}/dns_records/{rec['dns_id']}")
    if rec.get("access"):
        a = access_app(acct); doms = _domains(a)
        if host in doms and len(doms) > 1: call("PUT", f"/accounts/{acct}/access/apps/{a['id']}", _access_body(a, [d for d in doms if d != host]))

def _legacy(host, acct, tun):
    """An address published by an older Nova (before the record): its DNS record carries "Added by Nova"."""
    zid, _ = zone_for(host)
    mine = [r for r in call("GET", f"/zones/{zid}/dns_records?name={host}") or [] if r.get("comment") == "Added by Nova" and r.get("type") == "CNAME"]
    if not mine: return None
    return {"zone": zid, "dns_id": mine[0]["id"], "route": True, "access": True}

def remove(host, allow_public=False):
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host) or host == _nova_host(): raise ValueError("bad address")
    acct, tun = tunnel(); led = ledger()
    rec = led.get(host) or _legacy(host, acct, tun)
    if not rec: raise ValueError(f"Nova didn't put {host} on your tunnel, so it leaves it alone — change it in the Cloudflare dashboard")
    if rec.get("protected_existing") and not allow_public:
        raise ValueError(f"{host} was yours before Nova protected it: taking Nova off only removes the login, so it would be open to anyone again. Confirm to do that")
    _undo(host, rec, acct, tun)
    led.pop(host, None); _save_ledger(led)
    return {"ok": True}

def undo_all(allow_public=False):
    """Everything Nova added, taken off (e.g. before uninstalling). Addresses that were yours before Nova
    protected them keep their login unless allow_public (taking it off would make them public again)."""
    out = {}
    for host, rec in list(ledger().items()):
        if rec.get("protected_existing") and not allow_public: out[host] = "kept behind the login (it was yours before Nova)"; continue
        try: remove(host, allow_public); out[host] = "removed"
        except ValueError as e: out[host] = f"failed: {e}"
    return out
