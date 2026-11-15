"""
macmon — what Nova's monitor watches on a Mac.

nova_alerts.py does the remembering, de-duplicating, Inbox and Discord on every system; on a Mac its checks come from
here instead of from the journal, systemd and lsblk. Each check gets the Monitor (m) and uses m.condition() for things
that are true for a while and m.event() for things that happened. (The Windows counterpart is winmon.py.)
"""
import json, os, re, shutil, sys, time

import machost

try: import psutil
except ImportError: psutil = None

# macOS writes down why it last went off ("Previous shutdown cause: N"): 5 is a normal shutdown or restart, 3 the power
# button held down, 0 power lost, negative numbers hardware (heat, power supply, battery).
def shutdown_cause(code):
    """A shutdown-cause number → None (clean) or {"kind", "why", "tip"}."""
    if code in (5, 7): return None
    if code == 3: return {"kind": "unknown", "why": "it was switched off by holding the power button (or it froze and was forced off)", "tip": "If nobody held the button, it may have frozen — note what was running."}
    if code == 0: return {"kind": "unknown", "why": "it lost power", "tip": "Check the power cable; a small battery backup (UPS) rides out short cuts."}
    if code in (-3, -5, -71, -74, -75, -78, -79, -84, -86, -95, -100, -101, -102, -103):
        return {"kind": "hardware", "why": f"it shut itself down to protect against heat (shutdown cause {code})", "tip": "Check that its vents are clear and the room isn't hot."}
    if code < 0: return {"kind": "hardware", "why": f"macOS recorded a hardware reason (shutdown cause {code})", "tip": "If it keeps happening, run Apple Diagnostics (hold D while starting)."}
    return {"kind": "unknown", "why": f"macOS recorded shutdown cause {code}", "tip": ""}

def check_last_boot(m):
    boot = str(int(psutil.boot_time()))
    c = m.st["counters"]
    if c.get("boot_seen") == boot: return
    fresh = m.first_run; c["boot_seen"] = boot
    if fresh: return
    rc, so, _ = machost.run(["/usr/bin/log", "show", "--last", "boot", "--style", "compact", "--predicate", 'eventMessage CONTAINS "Previous shutdown cause"'], 90)
    hit = re.findall(r"Previous shutdown cause: (-?\d+)", so) if rc == 0 else []
    end = shutdown_cause(int(hit[-1])) if hit else None
    if end is None: return
    A = sys.modules[type(m).__module__]                       # nova_alerts: its small helpers
    up = psutil.boot_time()
    crashes = [t for t in c.get("bad_ends", []) if time.time() - t < 7 * 86400] + [up]
    c["bad_ends"] = crashes[-20:]
    again = f" That's {len(crashes)} times in the last 7 days." if len(crashes) > 1 else ""
    title = "The server stopped on a hardware error" if end["kind"] == "hardware" else "The server went off without shutting down"
    m.event("critical" if end["kind"] == "hardware" or len(crashes) > 1 else "warning", title,
            f"It came back {A.local_hm(up)}. Why: {end['why']}.{again} {end['tip']}".strip(), "system")

def check_load_mem(m):
    t = m.cfg["thresholds"]; cores = os.cpu_count() or 1
    l1, l5, l15 = os.getloadavg()
    m.metrics["load"] = f"{l1:.2f} / {cores} cores"
    m.condition("load:cpu", l15 / cores >= t["load_per_core_warn"], "warning", f"Sustained high CPU load: {l15:.1f}", f"15-minute average on {cores} cores.")
    vm = psutil.virtual_memory(); avail = 100 * vm.available / vm.total
    m.metrics["memory"] = f"{100 - avail:.0f}% used"
    m.condition("mem:low", avail < t["mem_available_warn_pct"], "warning", f"Memory nearly full: {avail:.0f}% available", "")
    up = time.time() - psutil.boot_time()
    m.metrics["uptime"] = f"{int(up // 86400)}d {int(up % 86400 // 3600)}h"

def check_disks(m):
    """Space on the Mac's own volume and on every drive under /Volumes (macOS's hidden system volumes aren't anyone's to fill)."""
    t = m.cfg["thresholds"]; names = m.cfg.get("mount_names") or {}; storage = []
    mounts = ["/"] + sorted({p.mountpoint for p in psutil.disk_partitions() if p.mountpoint.startswith("/Volumes/") and "nobrowse" not in p.opts})
    for mp in mounts:
        try: du = shutil.disk_usage(mp)
        except OSError: continue
        if du.total < 1e9: continue
        pct = 100 * (du.total - du.free) / du.total; free_gb = du.free / 1e9
        label = names.get(mp) or ("Macintosh HD" if mp == "/" else os.path.basename(mp))
        storage.append({"name": label, "mount": mp, "pct": round(pct, 1), "free": du.free, "total": du.total})
        if mp == "/": m.metrics["root_used"] = f"{pct:.0f}% ({free_gb:.0f} GB free)"
        key = "root" if mp == "/" else re.sub(r"[^a-z0-9._-]", "-", os.path.basename(mp).lower())[:40]
        m.condition(f"space:{key}", pct >= t["disk_warn_pct"], "critical" if pct >= t["disk_crit_pct"] else "warning", f"{label} is {pct:.0f}% full", f"{free_gb:.0f} GB free.")
    m.metrics["storage"] = storage

def check_drives(m, due):
    """Drive health as macOS reports it (S.M.A.R.T. status in Disk Utility). Every 30 minutes."""
    c = m.st["counters"]
    if due: c["mac_drives"] = machost.drives()
    states = []
    for d in c.get("mac_drives") or []:
        key = "".join(ch for ch in d["serial"] if ch.isalnum() or ch in "._-")[:60] or d["name"]
        bad = d.get("smart_passed") is False
        m.condition(f"smart:{key}", bad, "critical", f"Drive is failing: {d['model'] or d['name']}", "macOS reports this drive as failing. Back up what's on it and replace it.")
        states.append({"serial": d["serial"], "name": d["name"], "state": "failing" if bad else "ok", "temp": None})
    m.metrics["drive_states"] = states

def check_services(m):
    """Nova's own parts (launchd keeps them running; if one stays down, say so)."""
    for part, label in (("api", "the part your phone talks to"), ("helper", "the helper")):
        m.condition(f"svc:nova-{part}", machost.service_state(part) != "active", "critical", f"Nova isn't fully running: {label} has stopped",
                    "On the Mac: sudo nova gui → Start, or restart the Mac.")

def check_containers(m):
    if not machost.docker_running() or not machost.which("docker"): return
    sock = machost.docker_sock()
    rc, so, se = machost.run([machost.which("docker"), "-H", "unix://" + sock, "ps", "-a", "--format", "{{json .}}"], 30)
    if rc != 0: return
    total = running = 0
    for line in so.splitlines():
        try: c = json.loads(line)
        except ValueError: continue
        total += 1; name = str(c.get("Names", ""))[:64]; state = str(c.get("State", "")); status = str(c.get("Status", ""))
        if state == "running": running += 1
        m.condition(f"ctr:{name}", state == "restarting" or "unhealthy" in status, "warning",
                    f"Container {'keeps restarting' if state == 'restarting' else 'is unhealthy'}: {name}", status)
    m.metrics["containers"] = f"{running}/{total} running"

def steps(m, smart_due, web_due):
    return [(lambda: check_last_boot(m), "check_last_boot"), (m.drain_spool, "drain_spool"), (lambda: check_load_mem(m), "check_load_mem"),
            (lambda: check_disks(m), "check_disks"), (lambda: check_drives(m, smart_due or not m.st["counters"].get("mac_drives")), "check_drives"),
            (lambda: check_services(m), "check_services"), (lambda: check_containers(m), "check_containers"),
            (lambda: m.check_public(web_due), "check_public")]
