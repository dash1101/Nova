"""nova_rgb — fan light: state, validation, apply, schedules, status light.

state.json fields:
  on, effect (static|pulse|blink|cycle|wave|random|gradient), color, color2 (gradient end),
  brightness 0-100, speed 1-100 (higher = faster; speed_v=2 marks the new scale), rainbow (cycle/wave use every colour vs. just `color`),
  led_count (LEDs on the fan ring, for gradients),
  status_light: when on, the light shows server health (warning = amber, critical = red pulse)
                instead of your colour, then goes back to normal when all is well,
  schedules: [{"id","time":"HH:MM","days":[0-6, Mon=0],"enabled",set:{...any of the fields above}}]
`python3 nova_rgb.py restore` at boot (a wave fades in, then your setting), `python3 nova_rgb.py off` at
shutdown (the ARGB header keeps standby power, so without it the fan stays lit on its last frame), `python3 nova_rgb.py tick` every minute (schedules + status),
`python3 nova_rgb.py animate` as a service: draws software effects (wave) frame by frame in direct mode,
because the controller's own wave doesn't render on this board's ARGB header.
"""
import colorsys, json, math, os, sys, time, uuid
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fusion2

STATE = "/var/lib/nova-rgb/state.json"
APPLIED = "/var/lib/nova-rgb/applied.json"     # what's physically on the fan right now
STATUS = "/var/lib/nova-alerts/www/status.json"
DEFAULT = {"on": True, "effect": "static", "color": "#005aff", "color2": "#bf5af2", "brightness": 50,
           "speed": 50, "speed_v": 2, "rainbow": True, "led_count": 12, "status_light": False, "schedules": [],
           "palette": [], "presets": [], "schedules_paused": False, "location": None}
EFFECT_NAMES = ["static", "pulse", "blink", "cycle", "wave", "random", "gradient",
                "comet", "scanner", "twinkle", "fire", "breathe"]
SETTABLE = ("on", "effect", "color", "color2", "brightness", "speed", "rainbow", "led_count", "palette")

# Drawn frame by frame by `animate` (direct mode) from the clock, so the app can show them in sync;
# the rest run on the controller.
SOFTWARE_EFFECTS = {"wave", "comet", "scanner", "twinkle", "fire", "breathe"}
FIRE = ["#200000", "#ff1800", "#ff6000", "#ffb000", "#fff0a0"]      # fire's default heat palette

def _old_speed(v):
    """0-9 where 9 was the *slowest* (controller periods) -> 1-100 where 100 is fastest."""
    return round((9 - max(0, min(9, int(v)))) / 9 * 99) + 1

def load():
    try:
        with open(STATE) as f: raw = json.load(f)
    except Exception: return json.loads(json.dumps(DEFAULT))
    st = {**DEFAULT, **raw}
    if raw.get("speed_v") != 2:                    # migrate the old, inverted scale (incl. schedules)
        st["speed"] = _old_speed(st.get("speed", 4))
        for sc in st.get("schedules", []):
            if "speed" in sc.get("set", {}): sc["set"]["speed"] = _old_speed(sc["set"]["speed"])
        st["speed_v"] = 2
    return st

def save(st):
    os.makedirs(os.path.dirname(STATE), exist_ok=True)
    tmp = STATE + ".tmp"
    with open(tmp, "w") as f: json.dump(st, f, indent=1)
    os.replace(tmp, STATE)

def hex_rgb(h): h = h.lstrip("#"); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4))

def _hex(c):
    c = str(c)
    if not (len(c) == 7 and c[0] == "#" and all(x in "0123456789abcdefABCDEF" for x in c[1:])):
        raise ValueError("colors must be #rrggbb")
    return c.lower()

def validate(patch, allow_schedules=True):
    """Allowlist + range checks on everything the app may set."""
    out = {}
    for k, v in patch.items():
        if k == "on": out[k] = bool(v)
        elif k == "effect":
            if v not in EFFECT_NAMES: raise ValueError("unknown effect")
            out[k] = v
        elif k in ("color", "color2"): out[k] = _hex(v)
        elif k == "brightness":
            v = int(v)
            if not 0 <= v <= 100: raise ValueError("brightness 0-100")
            out[k] = v
        elif k == "speed":
            v = int(v)
            if not 1 <= v <= 100: raise ValueError("speed 1-100")
            out[k] = v
        elif k == "led_count":
            v = int(v)
            if not 1 <= v <= 120: raise ValueError("led_count 1-120")
            out[k] = v
        elif k in ("rainbow", "status_light", "schedules_paused"): out[k] = bool(v)
        elif k == "palette":
            if not isinstance(v, list) or len(v) > 8: raise ValueError("palette: up to 8 colours")
            out[k] = [_hex(c) for c in v]
        elif k == "location":
            if v is None: out[k] = None
            else:
                lat, lon = float(v.get("lat")), float(v.get("lon"))
                if not (-90 <= lat <= 90 and -180 <= lon <= 180): raise ValueError("location out of range")
                out[k] = {"lat": round(lat, 4), "lon": round(lon, 4)}
        elif k == "presets" and allow_schedules:
            if not isinstance(v, list) or len(v) > 24: raise ValueError("up to 24 presets")
            out[k] = [{"id": str(p.get("id") or uuid.uuid4().hex[:8])[:12], "name": "".join(c for c in str(p.get("name", "Preset")) if c.isprintable())[:30] or "Preset",
                       "set": validate(p.get("set", {}), allow_schedules=False)} for p in v]
        elif k == "schedules" and allow_schedules:
            if not isinstance(v, list) or len(v) > 20: raise ValueError("up to 20 schedules")
            sch = []
            for s in v:
                t = str(s.get("time", ""))
                hh, mm = t.split(":") if ":" in t else ("x", "x")
                if not (hh.isdigit() and mm.isdigit() and int(hh) < 24 and int(mm) < 60): raise ValueError("time HH:MM")
                days = sorted({int(d) for d in s.get("days", list(range(7))) if 0 <= int(d) <= 6})
                sset = validate(s.get("set", {}), allow_schedules=False)
                preset = str(s.get("preset") or "")[:12]
                if not sset and not preset: raise ValueError("a schedule must change something")
                trig = s.get("trigger", "time")
                if trig not in ("time", "sunrise", "sunset"): raise ValueError("trigger: time, sunrise or sunset")
                off = int(s.get("offset", 0))
                if not -120 <= off <= 120: raise ValueError("offset: up to 2 hours either way")
                fade = int(s.get("fade", 0))
                if not 0 <= fade <= 120: raise ValueError("fade: 0-120 minutes")
                until = s.get("until")
                if until:
                    ut = until.get("trigger", "time"); ui = str(until.get("time", "07:00")); uo = int(until.get("offset", 0))
                    uh, um = ui.split(":") if ":" in ui else ("x", "x")
                    if ut not in ("time", "sunrise", "sunset") or not (uh.isdigit() and um.isdigit() and int(uh) < 24 and int(um) < 60) or not -120 <= uo <= 120:
                        raise ValueError("bad end time")
                    until = {"trigger": ut, "time": f"{int(uh):02d}:{int(um):02d}", "offset": uo}
                sch.append({"id": str(s.get("id") or uuid.uuid4().hex[:8])[:12], "time": f"{int(hh):02d}:{int(mm):02d}",
                            "days": days or list(range(7)), "enabled": bool(s.get("enabled", True)),
                            "name": "".join(c for c in str(s.get("name", "")) if c.isprintable())[:30], "set": sset,
                            "preset": preset, "trigger": trig, "offset": off, "fade": fade, "until": until or None,
                            "skip_next": bool(s.get("skip_next", False)), "if_on": bool(s.get("if_on", False))})
            out[k] = sch
        elif k not in ("effects", "speed_v", "status_override", "period_ms", "sun", "running"):
            raise ValueError(f"unknown setting {k}")
    return out

def _scaled(rgb, pct):
    return tuple(round(c * pct / 100) for c in rgb)

def apply(st, override=None):
    """Push a state to the hardware. `override` (status light) replaces effect/colour."""
    eff = dict(st)
    if override: eff.update(override)
    anim = bool(eff["on"]) and eff["effect"] in SOFTWARE_EFFECTS
    with fusion2.Fusion2() as f:          # holds the hardware lock until the end of this block
        if not eff["on"]:
            f.set_effect("argb", "off", (0, 0, 0), 0)
        elif eff["effect"] == "gradient":
            n = eff["led_count"]; a, b = hex_rgb(eff["color"]), hex_rgb(eff["color2"])
            cols = [tuple(round(a[i] + (b[i] - a[i]) * (j / max(1, n - 1))) for i in range(3)) for j in range(n)]
            f.set_direct([_scaled(c, eff["brightness"]) for c in cols])
        elif anim:
            f.set_direct([_scaled(c, eff["brightness"]) for c in frame(eff, time.time())])
        else:
            f.set_effect("argb", eff["effect"], hex_rgb(eff["color"]),
                         round(255 * eff["brightness"] / 100), eff["speed"], eff.get("rainbow", True))
        # Written under the lock, so the animator can never draw a frame over a newer setting.
        tmp = APPLIED + ".tmp"
        with open(tmp, "w") as fh:
            json.dump({"t": time.time(), "override": override or None,
                       "anim": {k: eff.get(k) for k in ("effect", "color", "color2", "brightness", "speed", "rainbow", "led_count", "palette")} if anim else None}, fh)
        os.replace(tmp, APPLIED)

# ── software effects: one frame from the clock (the Nova app draws the same maths, in sync) ──────
def _h(a, b):
    """32-bit integer hash, written so Python, Kotlin and JavaScript give identical results."""
    h = ((a * 73856093) ^ (b * 19349663)) & 0x7fffffff
    h ^= h >> 13
    h = (h * 1274126177) & 0x7fffffff
    return h ^ (h >> 16)

def _pal(eff):
    if eff.get("rainbow", True) and eff["effect"] != "fire": return None          # None = rainbow
    p = [c for c in (eff.get("palette") or []) if c]
    if len(p) >= 2: return [hex_rgb(c) for c in p]
    if eff["effect"] == "fire": return [hex_rgb(c) for c in FIRE]
    return [hex_rgb(eff.get("color") or "#3e91ff")]

def _at(pal, x):
    """Colour at position x (0..1, wrapping) along the palette — rainbow when pal is None."""
    x %= 1.0
    if pal is None: return tuple(round(c * 255) for c in colorsys.hsv_to_rgb(x, 1, 1))
    if len(pal) == 1: return pal[0]
    f = x * len(pal); i = int(f) % len(pal); k = f - int(f); a, b = pal[i], pal[(i + 1) % len(pal)]
    return tuple(round(a[c] + (b[c] - a[c]) * k) for c in range(3))

def _heat(pal, x):
    """Fire: a non-wrapping ramp through the palette, 0 = coolest."""
    x = max(0.0, min(0.999, x)); f = x * (len(pal) - 1); i = int(f); k = f - i; a, b = pal[i], pal[min(i + 1, len(pal) - 1)]
    return tuple(round(a[c] + (b[c] - a[c]) * k) for c in range(3))

def frame(eff, t):
    """LED colours (full brightness; brightness is applied on top) for software effects at time t."""
    n = max(1, int(eff["led_count"])); per = fusion2.period_ms(eff["speed"]) / 1000; e = eff["effect"]; pal = _pal(eff)
    sc = lambda c, k: tuple(round(v * max(0.0, min(1.0, k))) for v in c)
    if e == "wave":
        ph = (t / (per * 1.5)) % 1.0
        if pal is None or len(pal) >= 2: return [_at(pal, j / n + ph) for j in range(n)]
        out = []
        for j in range(n):
            x = (math.cos(2 * math.pi * (j / n - ph)) + 1) / 2
            out.append(sc(pal[0], 0.08 + 0.92 * x ** 3))
        return out
    if e == "comet":                       # a bright head with a fading tail running round the ring
        head = (t / (per * 1.5)) % 1.0 * n; out = []
        for j in range(n):
            d = (head - j) % n; k = max(0.0, 1 - d / (n * 0.6)) ** 2
            out.append(sc(_at(pal, head / n), max(k, 0.03)))
        return out
    if e == "scanner":                     # Larson scanner: a light sweeping back and forth
        p = (t / (per * 1.5)) % 1.0; pos = (p * 2 if p < 0.5 else 2 - p * 2) * (n - 1)
        return [sc(_at(pal, j / n), max(0.03, max(0.0, 1 - abs(j - pos) / 2.2) ** 1.5)) for j in range(n)]
    if e == "twinkle":                     # LEDs fade in and out at random
        out = []
        for j in range(n):
            L = per * 1.5; loc = t / L + (_h(j, 7) % 1000) / 1000; c = int(loc); f = loc - c; r = _h(j, c)
            k = math.sin(math.pi * f) ** 2 if r % 100 < 55 else 0.0
            col = _at(pal, (r % 997) / 997) if (pal is None or len(pal) > 1) else pal[0]
            out.append(sc(col, max(0.05, k)))
        return out
    if e == "fire":                        # flickering flame, smooth noise per LED
        out = []
        for j in range(n):
            q = t / 0.12; c = int(q); f = q - c; f = f * f * (3 - 2 * f)
            a, b = (_h(j, c) % 1000) / 1000, (_h(j, c + 1) % 1000) / 1000
            v = 0.45 + 0.55 * (a + (b - a) * f)
            out.append(sc(_heat(pal, v), 0.35 + 0.65 * v))
        return out
    if e == "breathe":                     # a smooth breath; each breath takes the next palette colour
        T = per * 2; c = int(t / T); k = 0.06 + 0.94 * (0.5 - 0.5 * math.cos(2 * math.pi * (t / T % 1.0)))
        col = _at(pal, (c % 12) / 12) if pal is None else pal[c % len(pal)]
        return [sc(col, k)] * n
    return [hex_rgb(eff.get("color") or "#ffffff")] * n

def wave_frame(eff, t):                    # (older name, still used by the boot intro)
    return frame({**eff, "effect": "wave"}, t)

def animate(fps=30):
    """Service loop: draw software effects while applied.json says one is active."""
    order, dev, seen, cur = None, None, None, None
    while True:
        try:
            m = os.stat(APPLIED).st_mtime_ns
            if m != seen:
                seen = m; cur = json.load(open(APPLIED)).get("anim"); order = None
            if not cur:
                if dev: dev.close(); dev = None
                time.sleep(0.25); continue
            if dev is None: dev = fusion2.Fusion2(init=False, lock=False)
            with fusion2.hwlock():
                if os.stat(APPLIED).st_mtime_ns != seen: continue      # changed while we waited
                if order is None: order = dev.direct_setup()
                dev.set_direct([_scaled(c, cur["brightness"]) for c in frame(cur, time.time())], order)
            time.sleep(1 / fps)
        except Exception as e:                      # unplugged / re-enumerated: retry quietly
            print("animate:", e, flush=True)
            try: dev and dev.close()
            except Exception: pass
            dev, order = None, None; time.sleep(2)

def intro(st, seconds=2.6, fps=30):
    """Boot: a wave in your colour (or rainbow) fades in and comes up to your brightness; the
    caller then applies your real setting."""
    look = {**st, "effect": "wave", "speed": max(55, st.get("speed", 50))}
    target = max(20, st.get("brightness", 50)) if st.get("on", True) else 35
    with fusion2.Fusion2() as f:
        order = f.direct_setup(); t0 = time.time()
        while (el := time.time() - t0) < seconds:
            k = min(1.0, el / (seconds * 0.6))                       # fade in over the first 60%
            f.set_direct([_scaled(c, target * k * k) for c in wave_frame(look, time.time())], order)
            time.sleep(1 / fps)

def off():
    """Shutdown: every LED dark (the setting itself is kept for the next boot)."""
    with fusion2.Fusion2() as f:
        try: f.set_direct([(0, 0, 0)] * 32)
        except Exception: pass
        f.init()                                                      # back to controller effects…
        f.set_effect("argb", "off", (0, 0, 0), 0)                     # …and switch the header off
        tmp = APPLIED + ".tmp"
        with open(tmp, "w") as fh: json.dump({"t": time.time(), "override": None, "anim": None, "off": True}, fh)
        os.replace(tmp, APPLIED)                                      # the animator stops drawing

def status_override(st):
    """What the status light wants right now (None = show the user's own setting)."""
    if not st.get("status_light"): return None
    try:
        s = json.load(open(STATUS))
        if time.time() - os.path.getmtime(STATUS) > 600: return None   # monitor silent: don't lie
        lvl = s.get("level")
    except Exception:
        return None
    bright = max(st["brightness"], 25)
    if lvl == "critical": return {"on": True, "effect": "pulse", "color": "#ff2020", "brightness": bright, "speed": 70}
    if lvl == "warning":  return {"on": True, "effect": "static", "color": "#ffa000", "brightness": bright}
    return None

# ── schedules: time / sunrise / sunset (± up to 2 h), fade in, optional end time that puts things back ──
NOVA_SETTINGS = "/var/lib/nova-api/settings.json"     # the server's location (set in nova-setup or the app)

def tz_location():
    """The time zone's reference city (nova_tz, shipped with nova-api); None without it."""
    for d in ("/usr/lib/nova-api", "/usr/local/lib/nova-api", os.path.join(os.path.dirname(os.path.realpath(__file__)), "../../api")):
        if os.path.isfile(os.path.join(d, "nova_tz.py")) and d not in sys.path: sys.path.append(d)
    try: from nova_tz import tz_location as t
    except ImportError: return None
    return t()

def location(st):
    """Set in setup/the app > an old per-lighting location > the time zone's."""
    try: loc = json.load(open(NOVA_SETTINGS)).get("location")
    except Exception: loc = None
    return loc or st.get("location") or tz_location()

def sun_times(lat, lon, day=None):
    """Local sunrise and sunset (minutes after midnight) for `day` — the standard almanac method
    (zenith 90.833°), good to a minute or two. None in polar day/night."""
    import datetime as dt
    day = day or dt.date.today(); N = day.timetuple().tm_yday; lng = lon / 15
    tz = -(time.altzone if time.localtime().tm_isdst > 0 else time.timezone) / 3600
    out = []
    for rising in (True, False):
        t = N + ((6 if rising else 18) - lng) / 24
        M = 0.9856 * t - 3.289
        L = (M + 1.916 * math.sin(math.radians(M)) + 0.020 * math.sin(math.radians(2 * M)) + 282.634) % 360
        RA = math.degrees(math.atan(0.91764 * math.tan(math.radians(L)))) % 360
        RA = (RA + (L // 90) * 90 - (RA // 90) * 90) / 15
        sd = 0.39782 * math.sin(math.radians(L)); cd = math.cos(math.asin(sd))
        cH = (math.cos(math.radians(90.833)) - sd * math.sin(math.radians(lat))) / (cd * math.cos(math.radians(lat)))
        if not -1 <= cH <= 1: return None
        H = (360 - math.degrees(math.acos(cH)) if rising else math.degrees(math.acos(cH))) / 15
        UT = (H + RA - 0.06571 * t - 6.622 - lng) % 24
        out.append(round(((UT + tz) % 24) * 60))
    return tuple(out)

def _minute(trig, hhmm, offset, st):
    """When a trigger fires today, in minutes after midnight (None if it can't be worked out)."""
    if trig == "time":
        h, m = hhmm.split(":"); base = int(h) * 60 + int(m)
    else:
        loc = location(st); sun = sun_times(loc["lat"], loc["lon"]) if loc else None
        if not sun: return None
        base = sun[0] if trig == "sunrise" else sun[1]
    return (base + int(offset or 0)) % 1440

def extras(st):
    """Read-only extras for the app: today's sun times, and what each schedule does next."""
    out = {"effects": EFFECT_NAMES, "software_effects": sorted(SOFTWARE_EFFECTS)}
    loc = location(st); sun = sun_times(loc["lat"], loc["lon"]) if loc else None
    if sun: out["sun"] = {"sunrise": f"{sun[0] // 60:02d}:{sun[0] % 60:02d}", "sunset": f"{sun[1] // 60:02d}:{sun[1] % 60:02d}"}
    nxt = {}
    for s in st.get("schedules", []):
        m = _minute(s.get("trigger", "time"), s.get("time", "00:00"), s.get("offset", 0), st)
        if m is not None: nxt[s["id"]] = f"{m // 60:02d}:{m % 60:02d}"
    out["starts_today"] = nxt
    out["running"] = list((st.get("running") or {}).keys())
    return out

LOOK = ("on", "effect", "color", "color2", "brightness", "speed", "rainbow", "palette")

def _lerp_hex(a, b, k):
    x, y = hex_rgb(a), hex_rgb(b); return "#%02x%02x%02x" % tuple(round(x[i] + (y[i] - x[i]) * k) for i in range(3))

def tick():
    st = load(); changed = False
    now_t = time.time(); now = time.localtime(now_t); minute = now.tm_hour * 60 + now.tm_min
    running = st.setdefault("running", {})        # schedule id -> {"restore": look, "fade": {...}}
    presets = {p["id"]: p["set"] for p in st.get("presets", [])}
    if not st.get("schedules_paused"):
        for s in st.get("schedules", []):
            if not s.get("enabled", True) or now.tm_wday not in s.get("days", range(7)): continue
            target = dict(presets.get(s.get("preset"), {})) or dict(s.get("set", {}))
            start = _minute(s.get("trigger", "time"), s.get("time", "00:00"), s.get("offset", 0), st)
            if start == minute and not target == {}:
                if s.get("skip_next"): s["skip_next"] = False; changed = True; continue
                if s.get("if_on") and not st.get("on", True): continue      # "only if the light is on"
                r = running.setdefault(s["id"], {})
                if s.get("until"): r["restore"] = {k: st.get(k) for k in LOOK}
                fade = int(s.get("fade", 0))
                if fade > 0 and st.get("on", True):
                    # Fade: effect/colour switch now if they change, brightness glides; turning off fades to 0 first.
                    r["fade"] = {"t0": now_t, "dur": fade * 60, "b0": st.get("brightness", 50), "c0": st.get("color"),
                                 "b1": 0 if target.get("on") is False else target.get("brightness", st.get("brightness", 50)),
                                 "c1": target.get("color", st.get("color")), "off": target.get("on") is False}
                    st.update({k: v for k, v in target.items() if k not in ("brightness", "color", "on")}); st["on"] = True
                else:
                    if fade > 0 and target.get("on", True):          # fading in from off: start dark, glide up
                        r["fade"] = {"t0": now_t, "dur": fade * 60, "b0": 1, "c0": target.get("color", st.get("color")),
                                     "b1": target.get("brightness", st.get("brightness", 50)), "c1": target.get("color", st.get("color")), "off": False}
                        st.update(target); st["brightness"] = 1; st["on"] = True
                    else: st.update(target)
                changed = True
            u = s.get("until")
            if u and s["id"] in running:
                end = _minute(u.get("trigger", "time"), u.get("time", "07:00"), u.get("offset", 0), st)
                if end == minute and "restore" in running[s["id"]]:
                    st.update({k: v for k, v in running[s["id"]]["restore"].items() if v is not None})
                    running.pop(s["id"], None); changed = True
    for sid, r in list(running.items()):           # fades in progress (stepped once a minute)
        f = r.get("fade")
        if not f: continue
        k = min(1.0, (now_t - f["t0"]) / max(1, f["dur"]))
        st["brightness"] = round(f["b0"] + (f["b1"] - f["b0"]) * k)
        if f.get("c0") and f.get("c1"): st["color"] = _lerp_hex(f["c0"], f["c1"], k)
        if k >= 1:
            r.pop("fade", None)
            if f.get("off"): st["on"] = False; st["brightness"] = f["b0"]      # off at the end, brightness kept for next time
            if not r: running.pop(sid, None)
        changed = True
    if changed: save(st)
    ov = status_override(st)
    try: last = json.load(open(APPLIED))
    except Exception: last = {}
    if changed or ov != last.get("override"):
        apply(st, ov)

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "restore"
    if cmd == "tick": tick()
    elif cmd == "animate": animate()
    elif cmd == "off": off(); print("lights off")
    else:
        st = load()
        try: intro(st)
        except Exception as e: print("intro skipped:", e)
        for attempt in range(5):
            try: apply(st, status_override(st)); print("restored", st); break
            except Exception as e: print("retry:", e); time.sleep(2)
