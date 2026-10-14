"""
The status light on a Home Assistant light (a Govee strip, Hue bulb… anything HA calls light.*).

When the server needs attention, the light turns red (critical) or amber (warning); when it's fine again,
the light goes back exactly to how it was (on/off, color, brightness). Nova only acts when the status
changes, so it never fights you or your automations in between.

Settings in /var/lib/nova-rgb/ha.json (owner-only): url, token (a long-lived access token from your HA
profile), entity, enabled. The token is never sent back to the app.
"""
import ipaddress, json, os, re, socket, time, urllib.error, urllib.parse, urllib.request

CONF = "/var/lib/nova-rgb/ha.json"
STATUS = "/var/lib/nova-alerts/status.json"
COLORS = {"critical": ([255, 0, 0], 100), "warning": ([255, 150, 0], 80)}

def load():
    try: return json.load(open(CONF))
    except Exception: return {}

def save(c):
    os.makedirs(os.path.dirname(CONF), exist_ok=True)
    tmp = CONF + ".tmp"; fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f: json.dump(c, f)
    os.replace(tmp, CONF)

def check_url(u):
    """Home Assistant on this server or at home only (it's sent a token): http(s), a private/loopback address or a .local/.lan name."""
    u = str(u or "").strip().rstrip("/")
    p = urllib.parse.urlparse(u)
    if p.scheme not in ("http", "https") or not p.hostname or p.path not in ("", "/") or p.query or p.username:
        raise ValueError("Home Assistant's address, like http://homeassistant.local:8123")
    h = p.hostname
    try: ips = {ai[4][0] for ai in socket.getaddrinfo(h, p.port or 8123)}
    except OSError: raise ValueError(f"can't find {h} on your network")
    if not ips or not all(ipaddress.ip_address(i.split("%")[0]).is_private or ipaddress.ip_address(i.split("%")[0]).is_loopback for i in ips):
        raise ValueError("Home Assistant has to be on this server or your home network")
    return f"{p.scheme}://{p.netloc}"

def call(c, method, path, body=None, timeout=8):
    req = urllib.request.Request(c["url"] + path, json.dumps(body).encode() if body is not None else None, method=method,
                                 headers={"Authorization": f"Bearer {c['token']}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r: return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        if e.code == 401: raise ValueError("Home Assistant refused the token — make a new long-lived access token in your HA profile")
        if e.code == 404: raise ValueError("Home Assistant doesn't know that light")
        raise ValueError(f"Home Assistant said {e.code}")
    except (urllib.error.URLError, OSError) as e: raise ValueError(f"can't reach Home Assistant ({getattr(e, 'reason', e)})")

def lights(c):
    """The lights Home Assistant has, for picking one."""
    return sorted(({"entity": s["entity_id"], "name": s.get("attributes", {}).get("friendly_name") or s["entity_id"], "state": s.get("state")}
                   for s in call(c, "GET", "/api/states") or [] if s.get("entity_id", "").startswith("light.")), key=lambda x: x["name"].lower())

def level():
    try:
        if time.time() - os.path.getmtime(STATUS) > 600: return None            # the monitor is quiet: don't guess
        return json.load(open(STATUS)).get("level") or "ok"
    except Exception: return None

def sync(force=False, want=None):
    """Called every minute: change the light only when the status level changes. (want="ok": put it back now.)"""
    c = load()
    if not c.get("enabled") or not c.get("token") or not c.get("entity"): return
    if want is None:
        lv = level()
        if lv is None: return
        want = lv if lv in COLORS else "ok"
    if want == c.get("shown", "ok") and not force: return
    ent = c["entity"]
    if want != "ok":
        if c.get("shown", "ok") == "ok":                                         # remember how it was, to put it back after
            s = call(c, "GET", f"/api/states/{urllib.parse.quote(ent)}") or {}
            a = s.get("attributes", {})
            c["saved"] = {"state": s.get("state"), "rgb_color": a.get("rgb_color"), "brightness": a.get("brightness"),
                          "color_temp_kelvin": a.get("color_temp_kelvin"), "effect": a.get("effect")}
        rgb, pct = COLORS[want]
        call(c, "POST", "/api/services/light/turn_on", {"entity_id": ent, "rgb_color": rgb, "brightness_pct": pct, **({"flash": "long"} if want == "critical" else {})})
        if want == "critical":                                                   # flash is brief on most lights: settle on solid red after it
            time.sleep(2); call(c, "POST", "/api/services/light/turn_on", {"entity_id": ent, "rgb_color": rgb, "brightness_pct": pct})
    else:
        sv = c.get("saved") or {}
        if sv.get("state") == "off": call(c, "POST", "/api/services/light/turn_off", {"entity_id": ent})
        elif sv:
            body = {"entity_id": ent}
            if sv.get("effect"): body["effect"] = sv["effect"]
            elif sv.get("rgb_color"): body["rgb_color"] = sv["rgb_color"]
            elif sv.get("color_temp_kelvin"): body["color_temp_kelvin"] = sv["color_temp_kelvin"]
            if sv.get("brightness"): body["brightness"] = sv["brightness"]
            call(c, "POST", "/api/services/light/turn_on", body)
        c.pop("saved", None)
    c["shown"] = want; c["changed"] = int(time.time()); c.pop("error", None); save(c)

def view():
    c = load()
    return {"url": c.get("url", ""), "entity": c.get("entity", ""), "name": c.get("name", ""), "enabled": bool(c.get("enabled")),
            "has_token": bool(c.get("token")), "shown": c.get("shown", "ok"), "error": c.get("error")}

def configure(d):
    c = load()
    if "url" in d: c["url"] = check_url(d["url"])
    if d.get("token"):
        t = str(d["token"]).strip()
        if not re.fullmatch(r"[A-Za-z0-9._-]{20,600}", t): raise ValueError("that doesn't look like a Home Assistant access token")
        c["token"] = t
    if "entity" in d:
        e = str(d["entity"]).strip()
        if e and not re.fullmatch(r"light\.[a-z0-9_]{1,120}", e): raise ValueError("pick one of Home Assistant's lights (light.…)")
        c["entity"] = e
    if "name" in d: c["name"] = str(d["name"])[:80]
    if "enabled" in d:
        if not d["enabled"] and c.get("enabled") and c.get("shown", "ok") != "ok":   # switched off mid-alert: put the light back first
            save(c)
            try: sync(want="ok")
            except ValueError: pass
            c = load()
        c["enabled"] = bool(d["enabled"])
    if not c.get("url") or not c.get("token"): save(c); return view()
    if c.get("enabled") and c.get("entity"): call(c, "GET", f"/api/states/{urllib.parse.quote(c['entity'])}")      # check it works before saving it as on
    c.pop("error", None); save(c)
    try: sync(force=False)
    except ValueError: pass
    return view()

def remove():
    c = load()
    if c.get("enabled") and c.get("shown", "ok") != "ok":
        try: sync(want="ok")
        except ValueError: pass
    try: os.remove(CONF)
    except OSError: pass
    return view()
