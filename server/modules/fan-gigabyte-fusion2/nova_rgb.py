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
           "speed": 50, "speed_v": 2, "rainbow": True, "led_count": 12, "status_light": False, "schedules": []}
EFFECT_NAMES = ["static", "pulse", "blink", "cycle", "wave", "random", "gradient"]
SETTABLE = ("on", "effect", "color", "color2", "brightness", "speed", "rainbow", "led_count")

SOFTWARE_EFFECTS = {"wave"}            # animated by `animate`, not the controller

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
        elif k in ("rainbow", "status_light"): out[k] = bool(v)
        elif k == "schedules" and allow_schedules:
            if not isinstance(v, list) or len(v) > 20: raise ValueError("up to 20 schedules")
            sch = []
            for s in v:
                t = str(s.get("time", ""))
                hh, mm = t.split(":") if ":" in t else ("x", "x")
                if not (hh.isdigit() and mm.isdigit() and int(hh) < 24 and int(mm) < 60): raise ValueError("time HH:MM")
                days = sorted({int(d) for d in s.get("days", list(range(7))) if 0 <= int(d) <= 6})
                sset = validate(s.get("set", {}), allow_schedules=False)
                if not sset: raise ValueError("a schedule must change something")
                sch.append({"id": str(s.get("id") or uuid.uuid4().hex[:8])[:12], "time": f"{int(hh):02d}:{int(mm):02d}",
                            "days": days or list(range(7)), "enabled": bool(s.get("enabled", True)),
                            "name": str(s.get("name", ""))[:30], "set": sset})
            out[k] = sch
        elif k not in ("effects", "speed_v", "status_override", "period_ms"):
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
            f.set_direct([_scaled(c, eff["brightness"]) for c in wave_frame(eff, time.time())])
        else:
            f.set_effect("argb", eff["effect"], hex_rgb(eff["color"]),
                         round(255 * eff["brightness"] / 100), eff["speed"], eff.get("rainbow", True))
        # Written under the lock, so the animator can never draw a frame over a newer setting.
        tmp = APPLIED + ".tmp"
        with open(tmp, "w") as fh:
            json.dump({"t": time.time(), "override": override or None,
                       "anim": {k: eff[k] for k in ("effect", "color", "color2", "brightness", "speed", "rainbow", "led_count")} if anim else None}, fh)
        os.replace(tmp, APPLIED)

def wave_frame(eff, t):
    """One frame of the wave: rainbow -> colours chase round the ring; otherwise a bright band
    of `color` travels round over a dim version of it."""
    n = max(1, int(eff["led_count"])); per = fusion2.period_ms(eff["speed"]) / 1000 * 1.5
    phase = (t / per) % 1.0
    if eff.get("rainbow", True):
        return [tuple(round(c * 255) for c in colorsys.hsv_to_rgb((j / n + phase) % 1.0, 1, 1)) for j in range(n)]
    base = hex_rgb(eff["color"]); out = []
    for j in range(n):
        x = (math.cos(2 * math.pi * (j / n - phase)) + 1) / 2
        k = 0.08 + 0.92 * x ** 3
        out.append(tuple(round(c * k) for c in base))
    return out

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
                dev.set_direct([_scaled(c, cur["brightness"]) for c in wave_frame(cur, time.time())], order)
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

def tick():
    st = load(); changed = False
    now = time.localtime(); hm = time.strftime("%H:%M", now)
    for s in st.get("schedules", []):
        if s.get("enabled", True) and s["time"] == hm and now.tm_wday in s["days"]:
            st.update(s["set"]); changed = True
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
