#!/usr/bin/python3 -I
"""
Nova's CPU power settings: a power plan, a power limit in watts, and a temperature limit.

  Plan          Full speed  — boost on, the processor picks the fastest it can
                Balanced    — boost on, but it leans toward saving power
                Cool and quiet — no boost, saving power first (the coolest, and slower)
  Power limit   e.g. 45 W: Nova measures what the processor package draws (its own energy counter, every two
                seconds) and lowers the highest clock it may use until it stays under. 0: no limit.
  Temperature   e.g. 85 °C: the same, held under that temperature. 0: no limit.

Most processors (AMD desktop ones included) don't let Linux set a power limit directly, so the limits are held by
the clock: when it's over, the top clock comes down in steps; when it's well under again, it goes back up. That
works on any processor with cpufreq. The plan uses whatever the driver offers (energy preference, boost).

    cpupower.py run        the service (nova-cpupower.service, as root): applies the settings and holds the limits
    cpupower.py status     what it's doing now, as JSON

Settings: /etc/nova-api/cpu-power.json (written by Nova's helper); live state: /run/nova-cpupower.json.
Until something is set here, it only measures: the processor keeps whatever settings it had. When the service stops,
the processor gets its full clock range back (the plan stays as it was set). In a container it does nothing: /sys there
may be the host's.
"""
import glob, json, os, signal, sys, time

SETTINGS = "/etc/nova-api/cpu-power.json"
STATE = "/run/nova-cpupower.json"
CPU = "/sys/devices/system/cpu"
PLANS = ("full", "balanced", "cool")
EPP = {"full": "performance", "balanced": "balance_power", "cool": "power"}
EVERY = 2.0


def read(p, default=None):
    try:
        with open(p) as f: return f.read().strip()
    except OSError: return default

def write(p, v):
    try:
        with open(p, "w") as f: f.write(str(v))
        return True
    except OSError: return False

def settings():
    try: s = json.load(open(SETTINGS))
    except (OSError, ValueError): s = {}
    plan = s.get("plan") if s.get("plan") in PLANS else "full"
    watts = s.get("watts") if isinstance(s.get("watts"), int) and not isinstance(s.get("watts"), bool) and 0 <= s["watts"] <= 400 else 0
    temp = s.get("temp") if isinstance(s.get("temp"), int) and not isinstance(s.get("temp"), bool) and 0 <= s["temp"] <= 105 else 0
    return {"plan": plan, "watts": watts, "temp": temp}

def policies(): return sorted(glob.glob(f"{CPU}/cpufreq/policy[0-9]*"))

def in_container():
    return os.path.exists("/.dockerenv") or os.path.exists("/run/.containerenv") or bool(read("/run/systemd/container"))

def mtime():
    try: return os.path.getmtime(SETTINGS)
    except OSError: return 0

def hw():
    """What this machine offers: clock range, the energy preference, boost, the energy counter, a CPU temperature."""
    pol = [] if in_container() else policies()
    out = {"cpufreq": bool(pol)}
    if pol:
        out["min_khz"] = int(read(f"{pol[0]}/cpuinfo_min_freq", "0") or 0)
        out["max_khz"] = max(int(read(f"{p}/cpuinfo_max_freq", "0") or 0) for p in pol)
        out["driver"] = read(f"{pol[0]}/scaling_driver", "")
        out["epp"] = os.path.exists(f"{pol[0]}/energy_performance_preference")
    out["boost"] = next((p for p in (f"{CPU}/cpufreq/boost",) if os.path.exists(p)), "") or \
                   (f"{CPU}/intel_pstate/no_turbo" if os.path.exists(f"{CPU}/intel_pstate/no_turbo") else "")
    out["energy"] = next((z for z in sorted(glob.glob("/sys/class/powercap/*rapl*:0")) if os.path.exists(f"{z}/energy_uj")), "")
    out["temp"] = ""
    for h in sorted(glob.glob("/sys/class/hwmon/hwmon*")):
        if read(f"{h}/name") in ("k10temp", "zenpower", "coretemp", "cpu_thermal") and os.path.exists(f"{h}/temp1_input"):
            out["temp"] = f"{h}/temp1_input"; break
    return out

def apply_plan(plan, h):
    """The plan's energy preference and boost (what the driver offers)."""
    if h.get("epp"):
        for p in policies():
            if read(f"{p}/scaling_governor") == "performance" and EPP[plan] != "performance":
                write(f"{p}/scaling_governor", "powersave")            # (amd-pstate-epp/intel_pstate: EPP needs powersave)
            write(f"{p}/energy_performance_preference", EPP[plan])
    if h.get("boost"):
        on = plan != "cool"
        if h["boost"].endswith("no_turbo"): write(h["boost"], "0" if on else "1")
        else: write(h["boost"], "1" if on else "0")

def set_cap(khz):
    for p in policies(): write(f"{p}/scaling_max_freq", khz)

def top_khz(h):
    """The highest clock the plan allows: with boost off, what the processor reports as its top after the change."""
    pol = policies()
    return max((int(read(f"{p}/cpuinfo_max_freq", "0") or 0) for p in pol), default=h.get("max_khz", 0))

def cur_mhz():
    vals = [int(v) for v in (read(f"{p}/scaling_cur_freq") for p in policies()) if v and v.isdigit()]
    return round(sum(vals) / len(vals) / 1000) if vals else None

def status():
    """Settings, what the machine supports, and the live state (when the service runs)."""
    h = hw()
    try: live = json.load(open(STATE))
    except (OSError, ValueError): live = {}
    fresh = time.time() - live.get("at", 0) < 15
    return {**settings(), "supported": h["cpufreq"], "can_watts": bool(h["energy"]), "can_temp": bool(h["temp"]),
            "plans": [p for p in PLANS if p == "full" or h.get("epp") or h.get("boost")],
            "running": fresh, **({k: live.get(k) for k in ("now_watts", "now_temp", "now_mhz", "cap_mhz", "top_mhz", "limiting")} if fresh else {})}

def run():
    h = hw()
    if not h["cpufreq"]:
        print("cpupower: this machine has no cpufreq: nothing to do", flush=True)
        while True: time.sleep(3600)
    stop = {"now": False}
    def bye(*_): stop["now"] = True
    signal.signal(signal.SIGTERM, bye); signal.signal(signal.SIGINT, bye)

    seen = None; s = settings(); top = h["max_khz"]; cap = top; owned = False
    floor = max(h["min_khz"], 1_000_000) if h["min_khz"] else 1_000_000
    e_last = t_last = None; temps = []
    while not stop["now"]:
        m = mtime()
        if m != seen:                                              # settings changed (or the first look)
            seen = m; s = settings()
            if m:                                                  # (never set: leave the processor as it is, only measure)
                owned = True; apply_plan(s["plan"], h); time.sleep(0.3)
                top = top_khz(h); cap = top; set_cap(cap)
                print(f"cpupower: plan {s['plan']}, power limit {s['watts'] or 'none'}, temperature limit {s['temp'] or 'none'}", flush=True)
            else: print("cpupower: nothing set yet: only measuring", flush=True)
        # what the package draws (energy counter, µJ, wraps around) and the CPU temperature (smoothed: it jumps about)
        watts = None
        if h["energy"]:
            e = read(f"{h['energy']}/energy_uj"); now = time.monotonic()
            if e and e.isdigit():
                e = int(e)
                if e_last is not None and now > t_last:
                    d = e - e_last
                    if d < 0: d += int(read(f"{h['energy']}/max_energy_range_uj", "0") or 0)
                    watts = d / 1e6 / (now - t_last)
                e_last, t_last = e, now
        temp = None
        if h["temp"]:
            v = read(h["temp"])
            if v and v.lstrip("-").isdigit():
                temps = (temps + [int(v) / 1000])[-3:]; temp = sum(temps) / len(temps)
        # hold the limits: over → the top clock down (more when further over); well under → back up slowly
        over_w = s["watts"] and watts is not None and watts > s["watts"]
        over_t = s["temp"] and temp is not None and temp > s["temp"]
        under = (not s["watts"] or (watts is not None and watts < s["watts"] - 3)) and (not s["temp"] or (temp is not None and temp < s["temp"] - 3))
        new = cap
        if over_w or over_t:
            excess = max((watts - s["watts"]) / max(s["watts"], 1) if over_w else 0, (temp - s["temp"]) / 10 if over_t else 0)
            new = max(floor, cap - int(100_000 + 400_000 * min(excess, 1)))
        elif under and cap < top:
            new = min(top, cap + 100_000)
        if new != cap and owned: cap = new; set_cap(cap)
        limiting = ("temperature" if over_t else "power" if over_w else "") if (over_t or over_w) else ("held" if cap < top else "")
        st = {"at": time.time(), "now_watts": round(watts, 1) if watts is not None else None, "now_temp": round(temp) if temp is not None else None,
              "now_mhz": cur_mhz(), "cap_mhz": round(cap / 1000), "top_mhz": round(top / 1000), "limiting": limiting}
        tmp = STATE + ".tmp"
        try:
            with open(tmp, "w") as f: json.dump(st, f)
            os.chmod(tmp, 0o644); os.replace(tmp, STATE)
        except OSError: pass
        time.sleep(EVERY)
    if owned: set_cap(top_khz(h))                                  # stopping: the full range back
    print("cpupower: stopped" + (", full clock range back" if owned else ""), flush=True)

if __name__ == "__main__":
    if sys.argv[1:] == ["run"]: run()
    elif sys.argv[1:] == ["status"]: print(json.dumps(status()))
    else: sys.exit("usage: cpupower.py run|status")
