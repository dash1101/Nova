#!/usr/bin/env python3
"""Tests for the monitor's "why did the last boot end" check (nova_alerts.boot_end and check_last_boot).
   python3 packaging/monitor_test.py        (no network, nothing real is touched)"""
import os, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
TMP = tempfile.mkdtemp(prefix="nova-monitor-test-")
os.environ["NOVA_ALERTS_DIR"] = TMP; os.environ["NOVA_ALERTS_CONFIG"] = os.path.join(TMP, "config.json")
sys.path.insert(0, os.path.join(HERE, "..", "server", "monitor"))
import nova_alerts as na                                                   # noqa: E402

passed = failed = 0
def check(name, ok, info=""):
    global passed, failed
    if ok: passed += 1
    else: failed += 1; print(f"FAIL {name}  {info}")

def k(*msgs): return [{"MESSAGE": m, "_TRANSPORT": "kernel", "__REALTIME_TIMESTAMP": "1794000000000000"} for m in msgs]
def u(*msgs): return [{"MESSAGE": m, "_TRANSPORT": "journal", "__REALTIME_TIMESTAMP": "1794000000000000"} for m in msgs]
BUSY = u("Starting nova-alerts.service", "Finished nova-alerts.service", "tailscaled: magicsock: new contact")

# normal endings are not news
for end in ("Journal stopped", "Reached target reboot.target - System Reboot.", "Reached target poweroff.target - System Power Off.",
            "System is rebooting.", "Shutting down."):
    check(f"clean end: {end}", na.boot_end(BUSY + u(end)) is None)
check("no journal at all", na.boot_end([]) is None)
check("odd entries don't break it", na.boot_end([{"MESSAGE": [1, 2]}, {}])["kind"] == "unknown")

# a graphics driver crash under a video program (a real trace, shortened)
gpu = k("list_del corruption. prev->next should be ffff8bad9a8e5518, but was ffff8b639a8e55d6.", "------------[ cut here ]------------",
        "kernel BUG at lib/list_debug.c:62!", "Oops: invalid opcode: 0000 [#1] PREEMPT SMP NOPTI",
        "CPU: 9 UID: 0 PID: 101693 Comm: ffmpeg:cs0 Not tainted 6.12.115+deb13.1-amd64 #1  Debian 6.12.115-2",
        "RIP: 0010:__list_del_entry_valid_or_report.cold+0x37/0x6f", "Call Trace:", " <TASK>",
        " amdgpu_vm_bo_update+0x573/0x820 [amdgpu]", " ? srso_alias_return_thunk+0x5/0xfbef5", " amdgpu_cs_ioctl+0x1254/0x1ac0 [amdgpu]",
        " drm_ioctl_kernel+0xad/0x100 [drm]")
e = na.boot_end(BUSY + gpu)
check("graphics crash: kind", e["kind"] == "crash", e)
check("graphics crash: names the driver and the program", "AMD graphics driver (amdgpu)" in e["why"] and "while ffmpeg was using it" in e["why"], e)
check("graphics crash: says what to change", "hardware" in e["tip"].lower() and "software" in e["tip"], e)
e = na.boot_end(k("BUG: kernel NULL pointer dereference, address: 0000000000000008", "CPU: 2 UID: 0 PID: 77 Comm: kworker/2:1 Tainted: G",
                  "Call Trace:", " i915_gem_object_pin+0x12/0x90 [i915]"))
check("graphics crash without a video program: a general tip", e["kind"] == "crash" and "Intel" in e["why"] and "update the system" in e["tip"], e)
e = na.boot_end(k("general protection fault, probably for non-canonical address 0xdead: 0000 [#1]", "CPU: 0 PID: 1 Comm: dockerd",
                  "Call Trace:", " btrfs_search_slot+0x1/0x2 [btrfs]"))
check("another driver: named", e["kind"] == "crash" and "in the btrfs driver" in e["why"] and "while dockerd was running" in e["why"], e)
e = na.boot_end(k("Kernel panic - not syncing: Fatal exception"))
check("panic with nothing else", e["kind"] == "crash" and "kernel" in e["why"], e)

for msgs, kind in ((["mce: [Hardware Error]: CPU 3: Machine Check Exception: 5 Bank 4"], "hardware"),
                   (["thermal thermal_zone0: critical temperature reached (105 C), shutting down"], "heat"),
                   (["watchdog: BUG: soft lockup - CPU#3 stuck for 26s! [kswapd0:89]"], "freeze"),
                   (["rcu: INFO: rcu_preempt self-detected stall on CPU"], "freeze"),
                   (["nvme nvme0: controller is down; will reset: CSTS=0xffffffff"], "storage"),
                   (["EXT4-fs error (device nvme0n1p2): ext4_journal_check_start:83: Detected aborted journal"], "storage"),
                   (["Out of memory: Killed process 4242 (python3) total-vm:9000000kB"], "memory")):
    e = na.boot_end(BUSY + k(*msgs))
    check(f"{kind}: {msgs[0][:40]}", e["kind"] == kind and e["why"] and e["tip"], e)
check("a crash wins over an earlier out-of-memory", na.boot_end(k("Out of memory: Killed process 1 (x)", "Kernel panic - not syncing"))["kind"] == "crash")
check("user-space text that looks like a kernel line is ignored", na.boot_end(u("my app says: Kernel panic - not syncing"))["kind"] == "unknown")
e = na.boot_end(BUSY)
check("nothing logged: says so, with what that usually means", e["kind"] == "unknown" and "power cut" in e["tip"], e)

# check_last_boot: once per boot, never on a fresh install, counts repeats
import json
def monitor(journal, first_run=False, counters=None):
    m = na.Monitor(); m.first_run = first_run; m.st["counters"].update(counters or {})
    na.sh = lambda cmd, timeout=30: (0, "\n".join(json.dumps(x) for x in journal)) if cmd[:1] == ["journalctl"] else (1, "")
    return m
m = monitor(BUSY + gpu); m.check_last_boot()
ev = m.st["recent"][0] if m.st["recent"] else {}
check("an event is recorded", ev.get("title") == "The server crashed and restarted" and ev.get("category") == "system", ev)
check("…that explains it", "Why: the AMD graphics driver" in ev.get("detail", "") and "It stopped around" in ev.get("detail", ""), ev)
check("…and is pushed", len(m.pushes) == 1 and m.pushes[0]["level"] == "warning", m.pushes)
n0 = len(m.st["recent"]); m.check_last_boot()
check("only once per boot", len(m.st["recent"]) == n0)
m = monitor(BUSY + gpu, first_run=True); m.check_last_boot()
check("a fresh install doesn't report old history", not m.st["recent"] and m.st["counters"].get("boot_seen"))
m = monitor(BUSY + u("Journal stopped")); m.check_last_boot()
check("a normal restart is silent", not m.st["recent"])
m = monitor(BUSY, counters={"bad_ends": [na.now() - 3600]}); m.check_last_boot()
ev = m.st["recent"][0]
check("a second time in a week is critical and says so", m.pushes[0]["level"] == "critical" and "2 times in the last 7 days" in ev["detail"]
      and ev["title"] == "The server went off without shutting down", ev)
m = monitor([]); na.sh = lambda cmd, timeout=30: (1, ""); m.check_last_boot()
check("no earlier boot in the journal: silent", not m.st["recent"])

print(f"{passed}/{passed + failed} passed"); sys.exit(1 if failed else 0)
