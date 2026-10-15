"""Recommended actions: what's worth doing on this server right now, worked out from what Nova already knows — a disk
filling up, space that can be freed, memory and swap, updates, a restart a kernel update is waiting on, drive health,
backups that skipped or didn't run, devices nobody has used in months, and so on. Each one says why and goes straight
to the fix. You can snooze one for a month. Runs as root (in the helper), and only reads."""
import glob, json, os, re, shutil, subprocess, time

DATA = "/var/lib/nova-api"
SNOOZE = f"{DATA}/recommendations-snoozed.json"
GiB = 1 << 30


def _json(p, d=None):
    try: return json.load(open(p))
    except Exception: return d

def _sh(cmd, timeout=20):
    try: r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout); return r.returncode, r.stdout
    except Exception: return 1, ""

def _human(n):
    for u in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1000 or u == "TB": return f"{n:.0f} {u}" if u in ("B", "KB") or n >= 100 else f"{n:.1f} {u}"
        n /= 1000

def _du(path):
    rc, so = _sh(["du", "-sb", path], 60)
    try: return int(so.split()[0]) if rc == 0 else 0
    except (ValueError, IndexError): return 0

def _docker_reclaimable():
    rc, so = _sh(["docker", "system", "df", "--format", "{{json .}}"], 60)
    total = 0
    for l in so.splitlines():
        try: d = json.loads(l)
        except ValueError: continue
        m = re.match(r"([\d.]+)\s*([kMGT]?B)", d.get("Reclaimable", ""))
        if m and d.get("Type") in ("Images", "Build Cache"):
            total += float(m.group(1)) * {"B": 1, "kB": 1e3, "MB": 1e6, "GB": 1e9, "TB": 1e12}.get(m.group(2), 1)
    return int(total)


def recommendations():
    out = []
    def add(id, level, title, detail, label=None, go=None, task=None):
        out.append({"id": id, "level": level, "title": title, "detail": detail,
                    "action": ({"label": label, "go": go} if go else {"label": label, "task": task} if task else None)})

    # ── space ──
    mounts, branches = {}, set()
    for l in open("/proc/mounts"):
        f = l.split()
        if len(f) > 2 and f[2] == "fuse.mergerfs":          # a pool: judged as a whole (its drives filling one by one is normal)
            mounts[f[1]] = f[0]
            for e in open("/etc/fstab"):
                g = e.split()
                if len(g) > 2 and g[1] == f[1] and ":" in g[0]: branches |= set(g[0].split(":"))
        elif len(f) > 2 and f[0].startswith("/dev/") and f[2] not in ("squashfs", "iso9660") and f[1] not in mounts and not f[1].startswith(("/boot", "/run", "/snap")):
            mounts[f[1]] = f[0]
    for b in branches: mounts.pop(b, None)
    for m in sorted(mounts):
        try: st = os.statvfs(m)
        except OSError: continue
        tot = st.f_blocks * st.f_frsize; free = st.f_bavail * st.f_frsize
        if not tot: continue
        pct = 100 - free * 100 // tot
        if pct >= 90 or (pct >= 80 and free < 20 * GiB):
            add(f"full:{m}", "urgent" if pct >= 95 else "warn", f"{'The system drive' if m == '/' else m} is {pct}% full",
                f"{_human(free)} left. " + ("When it fills up, containers and the server itself start failing." if m == "/" else "Move or delete what you don't need, or add space."),
                "Free up space" if m == "/" else "Open Files", go="freeup" if m == "/" else "files")
    reclaim = {"Old container images and build cache": _docker_reclaimable(), "Downloaded update packages": _du("/var/cache/apt/archives"),
               "Old logs": max(0, _du("/var/log/journal") - 300_000_000)}
    tot = sum(reclaim.values())
    if tot > 3 * GiB:
        big = ", ".join(f"{k.lower()} ({_human(v)})" for k, v in sorted(reclaim.items(), key=lambda x: -x[1]) if v > 200_000_000)
        add("freeup", "tip", f"About {_human(tot)} can be freed", f"{big[:1].upper() + big[1:]}. Nothing you use is touched — you pick what goes.", "Free up space", go="freeup")

    # ── memory ──
    mi = {l.split(":")[0]: int(l.split()[1]) * 1024 for l in open("/proc/meminfo")}
    if mi.get("SwapTotal", 0) == 0:
        add("noswap", "tip", "This server has no swap", "A little swap stops a busy moment from running memory out (and the system killing a program).", "Set up swap", go="status")
    elif mi.get("SwapTotal") and (mi["SwapTotal"] - mi.get("SwapFree", 0)) / mi["SwapTotal"] > .8:
        add("swap", "warn", f"Swap is {round(100 * (mi['SwapTotal'] - mi['SwapFree']) / mi['SwapTotal'])}% full",
            "Memory has been tight for a while. See what's using it, or stop something you don't need.", "See memory use", go="status")
    if mi.get("MemTotal") and mi.get("MemAvailable", 0) / mi["MemTotal"] < .08:
        add("mem", "warn", "Memory is nearly all used", f"Only {_human(mi['MemAvailable'])} available. Containers using the most are in Containers → Heaviest first.", "Open Containers", go="containers")

    # ── updates and restarts ──
    up = _json(f"{DATA}/updates.json", {}) or {}
    cnt = lambda v: len([x for x in v if not isinstance(x, dict) or x.get("status") not in ("current", "unknown", "", None)]) if isinstance(v, list) else int(v or 0)
    n_apt, n_ct = cnt(up.get("apt")), cnt(up.get("containers"))
    if n_apt or n_ct:
        add("updates", "warn" if n_apt > 20 else "tip", "Updates are waiting",
            ", ".join(x for x in (f"{n_apt} system package{'s' if n_apt != 1 else ''}" if n_apt else "", f"{n_ct} container{'s' if n_ct != 1 else ''}" if n_ct else "") if x) + ".",
            "Open Updates", go="updates")
    if os.path.exists("/var/run/reboot-required"):
        pk = [l.strip() for l in open("/var/run/reboot-required.pkgs")][:3] if os.path.exists("/var/run/reboot-required.pkgs") else []
        add("reboot", "warn", "A restart finishes an update", (f"Waiting on: {', '.join(pk)}. " if pk else "") + "Until then the old version (often the kernel) keeps running.", "Restart options", go="quick")
    nu = _json(f"{DATA}/update.json", {}) or {}
    if nu.get("state") == "available":
        add("nova", "tip", f"Nova {nu.get('available')} is ready", "Install it from the Nova app on an admin phone (Settings → Software update).", "Software update", go="settings")

    # ── drives ──
    stt = _json("/var/lib/nova-alerts/state.json", {}) or {}
    smart = (stt.get("counters") or {}).get("smart", {})
    known = (stt.get("counters") or {}).get("drives", {})
    for serial, sm in smart.items():
        name = (known.get(serial) or {}).get("model") or serial
        if sm.get("passed") is False:
            add(f"smart:{serial}", "urgent", f"{name} says it's failing", "Copy what's on it to another drive now, then replace it.", "Open the drive", go=f"drive/{serial}")
        elif (sm.get("pending") or 0) > 0 or (sm.get("uncorrect") or 0) > 0:
            add(f"pending:{serial}", "warn", f"{name} has {sm.get('pending') or sm.get('uncorrect')} unreadable sector{'s' if (sm.get('pending') or 0) != 1 else ''}",
                "Usually from a power cut mid-write, not a dying drive. Run a full surface scan to find them; the file they're in gets fixed when it's written again.",
                "Open the drive", go=f"drive/{serial}")
        elif (sm.get("temp") or 0) >= 55:
            add(f"hot:{serial}", "warn", f"{name} is running hot ({sm['temp']}°C)", "Check the airflow over the drives (and that the fans are spinning).", "Open the drive", go=f"drive/{serial}")

    # ── backups ──
    bs = _json("/var/lib/nova-backup/backup_status.json", {}) or {}
    skipped = [k for k, v in (bs.get("sets") or {}).items() if str(v).startswith("CARRIED FORWARD")]
    if skipped:
        why = next((str(bs["sets"][k]).split(":", 1)[-1].strip() for k in skipped), "")
        mounted_now = all(not why.endswith("not mounted") or os.path.ismount(why.split(" not mounted")[0]) for k in skipped)
        add("backup-skipped", "warn", f"The last backup skipped {', '.join(skipped)}",
            f"{why[:1].upper() + why[1:]} at the time." + (" It's there now — run the backup again so those are up to date." if mounted_now else " Mount it, then run the backup."),
            "Open Backups", go="backups")
    jobs = []
    for f in glob.glob(f"{DATA}/backups/*/state.json"):
        st = _json(f, {}) or {}
        jobs.append(st)
    if not bs and not jobs:
        add("nobackup", "warn", "Nothing is backed up", "If a drive dies, what's on it is gone. Set up a backup to another drive or another computer.", "Set up a backup", go="backups")
    for st in jobs:
        lo = st.get("last_ok")
        if lo and time.time() - lo > 8 * 86400:
            add("backup-old", "warn", "A backup hasn't worked in over a week", f"Last good one: {time.strftime('%b %-d', time.localtime(lo))}.", "Open Backups", go="backups"); break

    # ── devices ──
    devs = _json(f"{DATA}/devices.json", {}) or {}
    old = []
    for k, d in devs.items():
        ls = d.get("last_seen") or d.get("created") or ""
        try: t = time.mktime(time.strptime(ls[:16], "%Y-%m-%d %H:%M"))
        except ValueError: continue
        if time.time() - t > 90 * 86400: old.append(d.get("name") or k)
    if old:
        add("old-devices", "tip", f"{len(old)} device{'s' if len(old) > 1 else ''} not used in 3 months", f"{', '.join(old[:3])}{'…' if len(old) > 3 else ''}. Remove what you don't use any more — fewer keys that can reach the server.",
            "Users & devices", go="devices")
    if not os.path.exists(f"{DATA}/totp.json") and any(d.get("type") == "browser" for d in devs.values()):
        add("totp", "tip", "Approve browsers without your phone, if you need to", "Authenticator codes let a computer be approved with a 6-digit code when no phone or SSH is at hand. Off until you turn it on.",
            "Security & privacy", go="security")

    # ── containers ──
    rc, so = _sh(["docker", "ps", "-a", "--filter", "status=exited", "--format", "{{.Names}}\t{{.Status}}"], 20)
    long_off = [l.split("\t")[0] for l in so.splitlines() if re.search(r"Exited \(\d+\) (\d+ weeks|[2-9]\d* days|\d+ months)", l)]
    if long_off:
        add("stopped", "tip", f"{len(long_off)} container{'s' if len(long_off) > 1 else ''} stopped for a while", f"{', '.join(long_off[:4])}. Start {'them' if len(long_off) > 1 else 'it'} again, or remove {'them' if len(long_off) > 1 else 'it'} to free the space.",
            "Open Containers", go="containers")

    # ── mounts (from Storage) ──
    try:
        import storage
        for s in storage.mount_issues(storage.storage_map()):
            add(s["id"], "warn", s["title"], s["detail"], "Partition manager", go="disks")
    except Exception: pass

    snoozed = _json(SNOOZE, {}) or {}
    now = time.time()
    order = {"urgent": 0, "warn": 1, "tip": 2}
    res = [r for r in out if snoozed.get(r["id"], 0) < now]
    res.sort(key=lambda r: order.get(r["level"], 3))
    return {"recommendations": res, "snoozed": len(out) - len(res), "t": now}


def snooze(rid, days=30):
    if not re.fullmatch(r"[\w:./@+-]{1,200}", rid or ""): raise ValueError("bad id")
    s = _json(SNOOZE, {}) or {}
    s = {k: v for k, v in s.items() if v > time.time()}
    s[rid] = time.time() + days * 86400
    tmp = SNOOZE + ".tmp"; json.dump(s, open(tmp, "w")); os.chmod(tmp, 0o600); os.replace(tmp, SNOOZE)
    return {"ok": True}
