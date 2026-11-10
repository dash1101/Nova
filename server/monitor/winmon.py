"""
winmon — what Nova's monitor watches on a Windows server.

nova_alerts.py does the remembering, de-duplicating, Inbox and Discord on every system; on Windows its checks come
from here instead of from the journal, systemd and lsblk. Each check gets the Monitor (m) and uses m.condition() for
things that are true for a while and m.event() for things that happened.
"""
import json, os, shutil, sys, time

import plat, winhost

try: import psutil
except ImportError: psutil = None

# Stop codes worth explaining (the number Windows shows on a blue screen) → (kind, what it means, what to do)
STOP_CODES = {
    0x116: ("crash", "the graphics driver stopped answering (VIDEO_TDR_FAILURE)", "Update or reinstall the graphics driver; if it happens during video work, turn off hardware acceleration in that app."),
    0x117: ("crash", "the graphics driver stopped answering (VIDEO_TDR_TIMEOUT)", "Update or reinstall the graphics driver."),
    0x119: ("crash", "the graphics driver hit an error (VIDEO_SCHEDULER_INTERNAL_ERROR)", "Update or reinstall the graphics driver."),
    0x124: ("hardware", "the processor reported a hardware error (WHEA_UNCORRECTABLE_ERROR)", "Check cooling and power, undo any overclock, and test the memory."),
    0x1A: ("memory", "a memory error (MEMORY_MANAGEMENT)", "Test the memory with Windows Memory Diagnostic."),
    0x50: ("crash", "a driver or faulty memory touched memory that wasn't there (PAGE_FAULT_IN_NONPAGED_AREA)", "Update drivers; if it keeps happening, test the memory."),
    0xD1: ("crash", "a driver misbehaved (DRIVER_IRQL_NOT_LESS_OR_EQUAL)", "Update the driver named in Windows' Reliability Monitor."),
    0x9F: ("crash", "a driver got stuck changing power state (DRIVER_POWER_STATE_FAILURE)", "Update drivers, especially network and graphics."),
    0xEF: ("crash", "a process Windows can't run without stopped (CRITICAL_PROCESS_DIED)", "Run: sfc /scannow — and check the system drive's health."),
    0x7A: ("storage", "Windows couldn't read its own files back from the drive (KERNEL_DATA_INPAGE_ERROR)", "Check the system drive's health and cable now, and back up."),
    0x77: ("storage", "Windows couldn't read its own files back from the drive (KERNEL_STACK_INPAGE_ERROR)", "Check the system drive's health and cable now, and back up."),
    0x133: ("freeze", "a driver held the processor for too long (DPC_WATCHDOG_VIOLATION)", "Update storage and chipset drivers."),
    0x101: ("freeze", "a processor core stopped responding (CLOCK_WATCHDOG_TIMEOUT)", "Check cooling and undo any overclock."),
}

def boot_end(events):
    """Windows' own record of how the last run ended → None (clean) or {"kind", "why", "tip", "t"}.
    events: [{"Id": 41|6008|1001, "Time": epoch seconds, "Code": bugcheck number or 0, "Message": str}] since this boot began."""
    bug = next((e for e in events if e.get("Id") == 1001 and e.get("Code")), None) or next((e for e in events if e.get("Id") == 41 and e.get("Code")), None)
    if bug:
        code = int(bug["Code"])
        kind, why, tip = STOP_CODES.get(code, ("crash", f"Windows stopped with a blue screen (stop code 0x{code:X})", "Windows' Reliability Monitor shows what was involved (search “View reliability history”)."))
        if code in STOP_CODES: why = f"Windows stopped with a blue screen: {why}"
        return {"kind": kind, "why": why, "tip": tip, "t": bug.get("Time")}
    dirty = next((e for e in events if e.get("Id") in (41, 6008)), None)
    if dirty:
        return {"kind": "unknown", "why": "it lost power or was switched off (or froze and was reset) — Windows recorded nothing before it went off",
                "tip": "If nobody held the power button: check the power cable, the power supply, and whether it runs hot.", "t": dirty.get("Time")}
    return None

def check_last_boot(m):
    boot = str(int(psutil.boot_time()))
    c = m.st["counters"]
    if c.get("boot_seen") == boot: return
    fresh = m.first_run; c["boot_seen"] = boot
    if fresh: return
    ev = winhost.ps_json(
        "Get-WinEvent -FilterHashtable @{LogName='System';Id=41,6008,1001;StartTime=(Get-Date).AddSeconds(-" + str(int(time.time() - psutil.boot_time()) + 600) + ")} -ErrorAction SilentlyContinue | "
        "ForEach-Object { $c=0; if($_.Id -eq 41){$c=[int64]$_.Properties[0].Value} "
        "elseif($_.Id -eq 1001 -and $_.ProviderName -like '*WER-SystemErrorReporting*' -and $_.Properties[0].Value -match '0x([0-9a-fA-F]+)'){$c=[Convert]::ToInt64($Matches[1],16)} "
        "[pscustomobject]@{Id=$_.Id;Provider=$_.ProviderName;Time=[int64](($_.TimeCreated.ToUniversalTime()-[datetime]'1970-01-01').TotalSeconds);Code=$c} }", 90)
    ev = [e for e in ev if e.get("Id") != 1001 or "WER-SystemErrorReporting" in str(e.get("Provider", "")) or "BugCheck" in str(e.get("Provider", ""))]
    end = boot_end(ev)
    if end is None: return
    A = sys.modules[type(m).__module__]                       # nova_alerts: its small helpers
    up = psutil.boot_time()
    crashes = [t for t in c.get("bad_ends", []) if time.time() - t < 7 * 86400] + [up]
    c["bad_ends"] = crashes[-20:]
    again = f" That's {len(crashes)} times in the last 7 days." if len(crashes) > 1 else ""
    title = {"crash": "The server crashed and restarted", "hardware": "The server stopped on a hardware error", "freeze": "The server froze and was restarted",
             "storage": "The server went down with drive errors", "memory": "The server went down on a memory error"}.get(end["kind"], "The server went off without shutting down")
    m.event("critical" if end["kind"] in ("hardware", "storage") or len(crashes) > 1 else "warning", title,
            f"It came back {A.local_hm(up)}. Why: {end['why']}.{again} {end['tip']}".strip(), "system")

def check_load_mem(m):
    t = m.cfg["thresholds"]; cores = os.cpu_count() or 1
    l1, l5, l15 = winhost.loadavg()
    m.metrics["load"] = f"{l1:.2f} / {cores} cores"
    m.condition("load:cpu", l15 / cores >= t["load_per_core_warn"], "warning", f"Sustained high CPU load: {l15:.1f}", f"15-minute average on {cores} cores.")
    vm = psutil.virtual_memory(); avail = 100 * vm.available / vm.total
    m.metrics["memory"] = f"{100 - avail:.0f}% used"
    m.condition("mem:low", avail < t["mem_available_warn_pct"], "warning", f"Memory nearly full: {avail:.0f}% available", "")
    up = time.time() - psutil.boot_time()
    m.metrics["uptime"] = f"{int(up // 86400)}d {int(up % 86400 // 3600)}h"

def check_disks(m):
    t = m.cfg["thresholds"]; names = m.cfg.get("mount_names") or {}; storage = []
    sysdrive = plat.SYSTEM_DRIVE.upper()
    for p in psutil.disk_partitions():
        if not p.fstype or "cdrom" in p.opts or "removable" in p.opts: continue
        mp = p.mountpoint
        try: du = shutil.disk_usage(mp)
        except OSError: continue
        if du.total < 1e9: continue                                   # recovery and EFI partitions with a letter
        pct = 100 * (du.total - du.free) / du.total; free_gb = du.free / 1e9
        storage.append({"name": names.get(mp) or ("System drive" if mp.upper() == sysdrive else mp.rstrip("\\")), "mount": mp, "pct": round(pct, 1), "free": du.free, "total": du.total})
        if mp.upper() == sysdrive: m.metrics["root_used"] = f"{pct:.0f}% ({free_gb:.0f} GB free)"
        m.condition(f"space:{mp[:1].lower()}", pct >= t["disk_warn_pct"], "critical" if pct >= t["disk_crit_pct"] else "warning",
                    f"{mp.rstrip(chr(92))} is {pct:.0f}% full", f"{free_gb:.0f} GB free.")
    m.metrics["storage"] = storage

def check_drives(m, due):
    """Drive health as Windows sees it (the same source as Settings → Storage → Drive health). Every 30 minutes."""
    c = m.st["counters"]
    if due: c["win_drives"] = winhost.drives()
    states = []
    for d in c.get("win_drives") or []:
        key = "".join(ch for ch in d["serial"] if ch.isalnum() or ch in "._-")[:60] or d["name"]
        bad = d.get("smart_passed") is False
        m.condition(f"smart:{key}", bad, "critical", f"Drive is failing: {d['model'] or d['name']}",
                    f"Windows reports this drive as {(d.get('status') or 'unhealthy').lower()}. Back up what's on it and replace it.")
        worn = d.get("life") is not None and d["life"] <= 10
        m.condition(f"drive:{key}", worn and not bad, "warning", f"Drive is worn out: {d['model'] or d['name']}", f"{d['life']}% of its rated life is left. Plan to replace it.")
        hot = d.get("temp") is not None and d["temp"] >= (m.cfg["thresholds"]["nvme_temp_warn"] if d.get("bus") == "nvme" else m.cfg["thresholds"]["sata_temp_warn"])
        m.condition(f"temp:{key}", hot, "warning", f"Drive is hot: {d['model'] or d['name']} at {d.get('temp')}°C", "Check airflow around the drives.")
        states.append({"serial": d["serial"], "name": d["name"], "state": "failing" if bad else "ok", "temp": d.get("temp")})
    m.metrics["drive_states"] = states

AUTO_SERVICES = ("sshd", "docker", "com.docker.service", "Tailscale", "cloudflared")
def check_services(m):
    """Services that are set to start by themselves but aren't running, and Nova's own parts."""
    want = m.cfg.get("required_services")
    names = list(AUTO_SERVICES) if want in (None, "auto") else [str(x) for x in want]
    seen = {}
    for name in names:
        try: s = psutil.win_service_get(name).as_dict()
        except Exception: continue                                      # not installed here
        seen[name] = s
        down = s.get("start_type") == "automatic" and s.get("status") != "running"
        m.condition(f"svc:{name.lower()}", down, "critical" if name.lower() in ("sshd", "docker", "com.docker.service") else "warning",
                    f"Service stopped: {s.get('display_name') or name}", "It's set to start with Windows but isn't running. Start it in Services, or restart the server.")
    parts = set()                                                       # Nova's own parts, by looking for their processes (cheap, every pass)
    for p in psutil.process_iter(["cmdline"]):
        cl = p.info.get("cmdline") or []
        if len(cl) >= 2 and cl[-2].lower().endswith("winrun.py"): parts.add(cl[-1])
    for part, label in (("api", "the part your phone talks to"), ("helper", "the helper")):
        m.condition(f"svc:nova-{part}", part not in parts, "critical", f"Nova isn't fully running: {label} has stopped",
                    "Open the Nova Server window and press Start, or restart the server.")
    m.metrics["services"] = f"{sum(1 for s in seen.values() if s.get('status') == 'running')}/{len(seen)} running" if seen else None
    if m.metrics["services"] is None: m.metrics.pop("services")

def check_containers(m):
    if not winhost.docker_running(): return
    rc, so, se = winhost.run(["docker", "ps", "-a", "--format", "{{json .}}"], 30)
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

def check_updates(m):
    """Windows wants a restart to finish installing updates."""
    need = False
    try:
        import winreg
        for path in (r"SOFTWARE\Microsoft\Windows\CurrentVersion\WindowsUpdate\Auto Update\RebootRequired", r"SOFTWARE\Microsoft\Windows\CurrentVersion\Component Based Servicing\RebootPending"):
            try: winreg.CloseKey(winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, path)); need = True
            except OSError: pass
    except ImportError: pass
    m.condition("sys:reboot", need, "info", "Windows needs a restart to finish updates", "Restart when it suits you: Power → Restart in the Nova app.")

def steps(m, smart_due, web_due):
    return [(lambda: check_last_boot(m), "check_last_boot"), (m.drain_spool, "drain_spool"), (lambda: check_load_mem(m), "check_load_mem"),
            (lambda: check_disks(m), "check_disks"), (lambda: check_drives(m, smart_due or not m.st["counters"].get("win_drives")), "check_drives"),
            (lambda: check_services(m), "check_services"), (lambda: check_containers(m), "check_containers"), (lambda: check_updates(m), "check_updates"),
            (lambda: m.check_public(web_due), "check_public")]
