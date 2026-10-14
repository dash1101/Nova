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


def _me():
    """This server's name, as it signs what it adds to Cloudflare (so several Nova servers can share one token)."""
    try: n = json.load(open("/var/lib/nova-api/settings.json")).get("display_name") or ""
    except Exception: n = ""
    return re.sub(r"[^\w .-]", "", n or __import__("socket").gethostname())[:40] or "server"

def _iid():
    """A short id for this server, made once — what Nova matches its own records by (the name can change)."""
    f = "/etc/nova-api/instance-id"
    try: v = open(f).read().strip()
    except OSError: v = ""
    if not re.fullmatch(r"[0-9a-f]{8}", v):
        v = os.urandom(4).hex()
        try:
            with open(f, "w") as fh: fh.write(v + "\n")
            os.chmod(f, 0o600)
        except OSError: pass
    return v

def _tag(): return f"Added by Nova · {_me()[:60]} · {_iid()}"

def _ours(c): return c == "Added by Nova" or (c.startswith("Added by Nova · ") and c.endswith(f" · {_iid()}"))

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
        out["services"] = {r["hostname"]: r.get("service", "") for r in (cfg.get("config") or {}).get("ingress", []) if r.get("hostname")}
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
        # the same token on other Nova servers: what they added (signed with their name), so it's clear whose is whose
        others = []
        try:
            for z in (call("GET", "/zones?per_page=50") or [])[:20]:
                for r in call("GET", f"/zones/{z['id']}/dns_records?comment.startswith=Added%20by%20Nova&per_page=100") or []:
                    c = r.get("comment") or ""
                    if c.startswith("Added by Nova · ") and not _ours(c):
                        others.append({"host": r.get("name"), "server": c[len("Added by Nova · "):].rsplit(" · ", 1)[0]})
        except ValueError: pass
        out["me"], out["others"] = _me(), others
        out["nova"] = {h: {"kind": r.get("kind") or ("protected" if r.get("protected_existing") else "existing" if r.get("existing") else "added"),
                           "mode": r.get("mode") or ("me" if r.get("access") else "public"), "guests": r.get("guests", []), "session": r.get("session"),
                           "for_host": r.get("for_host"), "service": r.get("service"), "existing": bool(r.get("existing")),
                           "app_key": bool(r.get("app_token")), "app_key_made": (r.get("app_token") or {}).get("made")} for h, r in led.items()}
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

def protect(host, mode="me", guests=None, session=None):
    """An address you already have on this tunnel, open to anyone: put a login in front of it (yours, or
    yours + guests). Only Access changes — your route and DNS record stay exactly as they are."""
    if mode not in ("me", "guests"): raise ValueError("protect with: me or guests")
    r = set_access(host, mode, guests, session)
    return {**r, "url": f"https://{r['host']}"}

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

MODES = ("me", "guests", "public")       # who can open an address: your login · your login + guests · anyone
# (a record can also say "offline": a change failed halfway, so its route is parked off the tunnel until the next try)
SESSIONS = ("1h", "24h", "168h", "720h")
EMAIL_RE = r"[^@\s<>\"']{1,64}@[A-Za-z0-9.-]{1,190}\.[A-Za-z]{2,24}"

def _guests(gs):
    out = []
    for g in gs or []:
        g = str(g).strip().lower()
        if not re.fullmatch(EMAIL_RE, g): raise ValueError(f"“{g[:60]}” isn't an email address")
        if g not in out: out.append(g)
    if len(out) > 50: raise ValueError("50 guests at most")
    return out

def _owner_policies(acct):
    """The allow rules on Nova's own login (your email, …), to reuse on an app's own login."""
    a = access_app(acct)
    ps = [p for p in a.get("policies") or [] if p.get("decision") == "allow" and p.get("id")]
    if not ps: raise ValueError("Nova's Access application has no Allow policy to reuse — add one in Cloudflare (Access → Applications → your Nova app)")
    if any(not p.get("reusable", True) for p in ps):
        raise ValueError("Nova's Access login uses older, non-reusable policies — in Cloudflare, convert them to reusable policies first")
    return [p["id"] for p in ps]

def _guest_policy(acct, host, guests, pid=None):
    body = {"name": f"Nova guests · {host}", "decision": "allow", "include": [{"email": {"email": g}} for g in guests]}
    if pid: call("PUT", f"/accounts/{acct}/access/policies/{pid}", body); return pid
    return call("POST", f"/accounts/{acct}/access/policies", body)["id"]

def _own_app(acct, host, guests, session, app_id=None, pid=None, extra=()):
    """An Access application just for this address: your own login rules, plus a guest list Nova keeps
    (and `extra` policies, like the key for the app's own phone app)."""
    pid = _guest_policy(acct, host, guests or ["nobody@invalid.example"], pid)   # (an empty include would let no one in anyway)
    owner = _owner_policies(acct)
    keep = [x for x in extra if x]
    if app_id:   # an edit: keep every policy you added yourself (a service token for the app's phone app, say)
        cur = next((a for a in call("GET", f"/accounts/{acct}/access/apps") or [] if a.get("id") == app_id), None) or {}
        keep = list(dict.fromkeys(keep + [p["id"] for p in cur.get("policies") or [] if p.get("id") and p["id"] != pid and p["id"] not in owner]))
    pols = [{"id": x, "precedence": i + 1} for i, x in enumerate(owner + keep)] + [{"id": pid, "precedence": len(owner) + len(keep) + 1}]
    body = {"name": f"{host} (Nova)", "type": "self_hosted", "domain": host, "destinations": [{"type": "public", "uri": host}],
            "session_duration": session if session in SESSIONS else "24h", "app_launcher_visible": False, "policies": pols}
    if app_id: call("PUT", f"/accounts/{acct}/access/apps/{app_id}", body); return app_id, pid
    return call("POST", f"/accounts/{acct}/access/apps", body)["id"], pid

def _protect(acct, host, rec, mode, guests, session):
    """Put the protection for `mode` in place (nothing for public), recording what was made."""
    tok = [(rec.get("app_token") or {}).get("policy_id")]
    if mode == "me" and rec.get("app_token"):          # only you, plus the app's own key: its own login, with no guests
        rec["app_id"], rec["policy_id"] = _own_app(acct, host, [], session or rec.get("session"), extra=tok)
    elif mode == "me":
        a = access_app(acct); doms = _domains(a)
        if host not in doms: call("PUT", f"/accounts/{acct}/access/apps/{a['id']}", _access_body(a, doms + [host])); rec["access"] = True
    elif mode == "guests":
        rec["app_id"], rec["policy_id"] = _own_app(acct, host, guests, session, extra=tok)
        rec["guests"], rec["session"] = guests, session
    rec["mode"] = mode

def _unprotect(acct, host, rec):
    """Take away exactly the protection Nova put on (its own app and guest list, or its entry on Nova's login)."""
    if rec.get("app_id"):
        try: call("DELETE", f"/accounts/{acct}/access/apps/{rec['app_id']}")
        except ValueError as e:
            if "not found" not in str(e).lower(): raise
        rec.pop("app_id", None)
    if rec.get("policy_id"):
        try: call("DELETE", f"/accounts/{acct}/access/policies/{rec['policy_id']}")
        except ValueError: pass                                        # an orphaned, empty guest list is harmless
        rec.pop("policy_id", None)
    if rec.get("access"):
        a = access_app(acct); doms = _domains(a)
        if host in doms and len(doms) > 1: call("PUT", f"/accounts/{acct}/access/apps/{a['id']}", _access_body(a, [d for d in doms if d != host]))
        rec["access"] = False

def apply(host, service, no_tls_verify=False, mode="me", guests=None, session="24h", allow_public=False, kind=None, for_host=None):
    if mode not in MODES: raise ValueError("who can open it: me, guests or public")
    if mode == "public" and not allow_public: raise ValueError("making an address public needs your confirmation")
    guests = _guests(guests)
    p = plan(host, service, no_tls_verify); host = p["host"]
    if p.get("conflict"): raise ValueError(f"{host} is in use: {p['why']}")
    acct, tun = tunnel(); zid, _ = zone_for(host)
    rec = {"service": service, "zone": zid, "added": int(__import__("time").time()), "access": False, "dns_id": None, "route": False, "mode": mode}
    if kind: rec.update(kind=kind, for_host=for_host)
    try:
        # 1. protect first (a public address has nothing to protect)
        _protect(acct, host, rec, mode, guests, session)
        # 2. name
        r = call("POST", f"/zones/{zid}/dns_records", {"type": "CNAME", "name": host, "content": f"{tun}.cfargotunnel.com", "proxied": True,
                                                       "comment": _tag()})
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

def _route_off(acct, tun, host):
    cur = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
    rule = next((r for r in cur.get("ingress") or [] if r.get("hostname") == host), None)
    if rule: call("PUT", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations", {"config": {**cur, "ingress": [r for r in cur["ingress"] if r.get("hostname") != host]}})
    return rule

def _route_on(acct, tun, rule):
    if not rule: return
    cur = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
    if any(r.get("hostname") == rule["hostname"] for r in cur.get("ingress") or []): return
    call("PUT", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations", {"config": {**cur, "ingress": _with_rule(cur.get("ingress"), rule)}})

def _existing_open(host, acct, tun):
    """Your own address on this tunnel, open to anyone (not Nova's): a record Nova can start from."""
    zid, _ = zone_for(host)
    cfg = (call("GET", f"/accounts/{acct}/cfd_tunnel/{tun}/configurations") or {}).get("config") or {}
    route = next((r for r in cfg.get("ingress") or [] if r.get("hostname") == host), None)
    if not route: raise ValueError(f"{host} isn't on your tunnel")
    if not any(r.get("type") == "CNAME" and r.get("content", "") == f"{tun}.cfargotunnel.com" for r in call("GET", f"/zones/{zid}/dns_records?name={host}") or []):
        raise ValueError(f"{host}'s DNS record doesn't point at this tunnel — Nova leaves it alone")
    if host in _domains(access_app(acct)): raise ValueError(f"{host} is already behind your login (set up outside Nova) — change it in Cloudflare")
    return {"service": route.get("service"), "zone": zid, "added": int(__import__("time").time()), "access": False, "dns_id": None,
            "route": False, "protected_existing": True, "mode": "public"}

def set_access(host, mode, guests=None, session=None, allow_public=False):
    """Change who can open an address Nova manages (or your own open one): only you, you + guests, or anyone.
    Moving between two kinds of login takes the address off the tunnel for the few seconds it takes,
    so it's never reachable without a login in between; if anything fails it stays off (safe) and says so."""
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host) or host == _nova_host(): raise ValueError("bad address")
    if mode not in MODES: raise ValueError("who can open it: me, guests or public")
    acct, tun = tunnel(); led = ledger()
    rec = led.get(host) or _legacy(host, acct, tun) or _existing_open(host, acct, tun)
    old = rec.get("mode") or ("me" if rec.get("access") else "public")
    if old == "offline" and mode == "public" and not allow_public: raise ValueError(f"anyone could open {host} — confirm to make it public")
    guests = _guests(guests if guests is not None else rec.get("guests"))
    session = session if session in SESSIONS else rec.get("session") or "24h"
    if mode == "public" and old != "public" and not allow_public: raise ValueError(f"anyone could open {host} — confirm to make it public")
    if mode == old == "guests":                                          # just the list or the session length
        rec["app_id"], rec["policy_id"] = _own_app(acct, host, guests, session, rec.get("app_id"), rec.get("policy_id"))
        rec["guests"], rec["session"] = guests, session
    elif mode != old:
        if mode == "guests": _owner_policies(acct)                      # anything that can fail is checked before the address goes offline
        # moving from one login to another (or out of "offline" after a failed try): off the tunnel meanwhile
        if old not in ("public", "offline") and mode != "public" and not rec.get("parked_route"):     # (already parked: keep the rule we have)
            rec["parked_route"] = _route_off(acct, tun, host)
        try:
            _unprotect(acct, host, rec)
            _protect(acct, host, rec, mode, guests, session)
        except ValueError as e:
            if rec.get("parked_route"): rec["mode"] = "offline"
            led[host] = rec; _save_ledger(led)                           # remember what's there now, whatever happened
            if rec.get("parked_route"): raise ValueError(f"{e} — {host} is off the internet until this works (nothing was left open); try again")
            raise
        _route_on(acct, tun, rec.pop("parked_route", None))
    rec["mode"] = mode
    if mode != "guests": rec.pop("guests", None)
    led[host] = rec; _save_ledger(led)
    return {"ok": True, "host": host, "mode": mode, "guests": rec.get("guests", []), "session": rec.get("session")}

def share(share_host, service, for_host):
    """The public address for an app's share links (e.g. Immich's share proxy). Uses your own existing route
    to the same place if there is one (and records it, changing nothing); otherwise adds it, open to anyone."""
    share_host = str(share_host).strip().lower()
    p = plan(share_host, service)
    led = ledger()
    if p.get("conflict"):
        c = p["conflict"]
        if c["route"] and c["route_service"] == service and c["dns_to_tunnel"] and not c["protected"]:
            led[share_host] = {"service": service, "existing": True, "kind": "share", "for_host": for_host, "mode": "public",
                               "access": False, "route": False, "dns_id": None, "added": int(__import__("time").time())}
            _save_ledger(led); return {"ok": True, "host": share_host, "url": f"https://{share_host}", "existing": True}
        raise ValueError(f"{share_host} is in use: {p['why']}" + (f" — try {p['suggest']}" if p.get("suggest") else ""))
    return apply(share_host, service, mode="public", allow_public=True, kind="share", for_host=for_host)

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
    _unprotect(acct, host, rec)

def app_key(host, rotate=False):
    """A key for the app's own phone app (Immich, Home Assistant…): those apps can't do the browser login, but they
    can send two extra headers. Nova makes a Cloudflare service token that opens only this address, and gives you
    the two headers once. A new key replaces the old one. Only for an address behind your login."""
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host) or host == _nova_host(): raise ValueError("bad address")
    acct, tun = tunnel(); led = ledger(); rec = led.get(host)
    if not rec or rec.get("mode", "me" if rec.get("access") else "public") not in ("me", "guests"):
        raise ValueError(f"{host} has to be behind your login (Only you, or You and guests) for an app key")
    old = rec.get("app_token")
    try: tk = call("POST", f"/accounts/{acct}/access/service_tokens", {"name": f"Nova app key · {host}", "duration": "8760h"})
    except ValueError as e:
        if re.search(r"auth|permission|forbidden|10000|9109", str(e), re.I):
            raise ValueError("your Cloudflare API token can't make app keys yet — edit the token in Cloudflare and add the permission "
                             "Account · Access: Service Tokens · Edit, then try again")
        raise
    try: pol = call("POST", f"/accounts/{acct}/access/policies", {"name": f"Nova app key · {host}", "decision": "non_identity",
                                                                  "include": [{"service_token": {"token_id": tk["id"]}}]})
    except ValueError: _drop_key(acct, {"id": tk["id"]}); raise
    rec["app_token"] = {"id": tk["id"], "policy_id": pol["id"], "client_id": tk["client_id"], "made": int(__import__("time").time())}
    mode = rec.get("mode") or "me"
    try:
        if rec.get("app_id"):                            # it has its own login already: add the key to it (and drop an older key)
            app_now = next((a for a in call("GET", f"/accounts/{acct}/access/apps") or [] if a.get("id") == rec["app_id"]), None) or {}
            pols = [p["id"] for p in app_now.get("policies") or [] if p.get("id") and p["id"] != (old or {}).get("policy_id")] + [pol["id"]]
            body = {k: v for k, v in app_now.items() if k in ("name", "type", "domain", "destinations", "session_duration", "app_launcher_visible")}
            call("PUT", f"/accounts/{acct}/access/apps/{rec['app_id']}", {**body, "policies": [{"id": x, "precedence": i + 1} for i, x in enumerate(pols)]})
        else:                                            # on Nova's shared login: move it to its own (off the tunnel in between, never open)
            if not rec.get("parked_route"): rec["parked_route"] = _route_off(acct, tun, host)
            _unprotect(acct, host, rec); _protect(acct, host, rec, mode, rec.get("guests") or [], rec.get("session") or "24h")
            _route_on(acct, tun, rec.pop("parked_route", None))
    except ValueError as e:
        new_tok = rec.pop("app_token", None)
        if old: rec["app_token"] = old                   # the key that was there keeps working
        if rec.get("parked_route"): rec["mode"] = "offline"   # off the internet (never open) until a retry or a mode change works
        led[host] = rec; _save_ledger(led)
        if new_tok: _drop_key(acct, new_tok)
        if rec.get("parked_route"): raise ValueError(f"{e} — {host} is off the internet until this works (nothing was left open); try again")
        raise
    if old: _drop_key(acct, old)
    led[host] = rec; _save_ledger(led)
    return {"ok": True, "host": host, "client_id": tk["client_id"], "client_secret": tk["client_secret"],
            "headers": {"CF-Access-Client-Id": tk["client_id"], "CF-Access-Client-Secret": tk["client_secret"]}}

def _drop_key(acct, tok):
    for path in ([f"/accounts/{acct}/access/policies/{tok['policy_id']}"] if tok.get("policy_id") else []) + [f"/accounts/{acct}/access/service_tokens/{tok.get('id')}"]:
        try: call("DELETE", path)
        except ValueError: pass                          # already gone

def app_key_remove(host):
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host): raise ValueError("bad address")
    acct, _ = tunnel(); led = ledger(); rec = led.get(host) or {}
    tok = rec.pop("app_token", None)
    if not tok: return {"ok": True}
    if rec.get("app_id"):                                # take it off the address's login first
        a = next((x for x in call("GET", f"/accounts/{acct}/access/apps") or [] if x.get("id") == rec["app_id"]), None)
        if a:
            pols = [p["id"] for p in a.get("policies") or [] if p.get("id") and p["id"] != tok.get("policy_id")]
            body = {k: v for k, v in a.items() if k in ("name", "type", "domain", "destinations", "session_duration", "app_launcher_visible")}
            call("PUT", f"/accounts/{acct}/access/apps/{rec['app_id']}", {**body, "policies": [{"id": x, "precedence": i + 1} for i, x in enumerate(pols)]})
    _drop_key(acct, tok)
    led[host] = rec; _save_ledger(led)
    return {"ok": True}

def _legacy(host, acct, tun):
    """An address published by an older Nova (before the record): its DNS record carries "Added by Nova"."""
    zid, _ = zone_for(host)
    mine = [r for r in call("GET", f"/zones/{zid}/dns_records?name={host}") or [] if _ours(r.get("comment") or "") and r.get("type") == "CNAME"]
    if not mine: return None
    return {"zone": zid, "dns_id": mine[0]["id"], "route": True, "access": True}

def remove(host, allow_public=False):
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host) or host == _nova_host(): raise ValueError("bad address")
    acct, tun = tunnel(); led = ledger()
    rec = led.get(host) or _legacy(host, acct, tun)
    if not rec: raise ValueError(f"Nova didn't put {host} on your tunnel, so it leaves it alone — change it in the Cloudflare dashboard")
    if rec.get("protected_existing") and rec.get("mode", "me") not in ("public",) and not allow_public:
        raise ValueError(f"{host} was yours before Nova protected it: taking Nova off only removes the login, so it would be open to anyone again. Confirm to do that")
    if rec.get("app_token"): _drop_key(acct, rec.pop("app_token"))      # the app key goes with the address
    if rec.get("existing"): _unprotect(acct, host, rec)               # a share address that was already yours: only Nova's login comes off
    else: _undo(host, rec, acct, tun)
    if rec.get("protected_existing") and rec.get("parked_route"): _route_on(acct, tun, rec["parked_route"])   # yours, as it was
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
