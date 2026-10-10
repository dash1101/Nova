#!/usr/bin/env python3
"""
nova_alerts.py — Nova monitoring + alerts (part of the nova-server package).

Runs once a minute (nova-alerts.timer). Each run:
  1. reads NEW journal entries and Docker events since the last run (cursor-based)
     -> one-shot EVENTS: logins, failed logins, disk errors, USB/disk plug/unplug,
        OOM kills, failed services, crashed/unhealthy containers
  2. checks CURRENT STATE
     -> CONDITIONS that raise and later resolve: temps, CPU/RAM/swap, disk space,
        missing mounts, SMART health + counter increases, drives appearing/vanishing,
        services/containers down, public sites down, stale backups, reboot needed
  3. pushes changes to Discord (batched, retried if Discord is unreachable)
  4. writes /var/lib/nova-alerts/www/status.json for the Homepage dashboard

Two tiers on purpose: the PHONE only hears about changes (something broke, recovered,
or someone logged in); HOMEPAGE always shows the full current picture.

Other scripts send alerts with:   nova-alert <info|warning|critical> "<title>" ["<details>"]

Config: /etc/nova-alerts/config.json (root-only: holds the Discord webhook URL). Everything has a
sensible default for any Debian machine: mounts come from /etc/fstab, services from what's enabled.

Commands:  nova_alerts.py                  one check pass (the timer runs this every minute)
           nova_alerts.py dismiss <key>    hide an alert you've dealt with (drive:<serial> = removed on purpose)
           nova_alerts.py forget-drive <serial|name>
           nova_alerts.py test             send a test alert
"""
import json, os, re, subprocess, sys, time, socket, glob, urllib.request, urllib.error, fcntl
from datetime import datetime, timezone

CONFIG_FILE = os.environ.get("NOVA_ALERTS_CONFIG", "/etc/nova-alerts/config.json")
APP_NOTIFY_FILE = "/var/lib/nova-api/notify.json"   # notification choices made in the Nova app (allowlisted keys only)
APP_NOTIFY_KEYS = {"push_min_level": ("info", "warning", "critical"), "push_logins": bool, "push_usb": bool,
                   "discord_paused": bool}
_DIR = os.environ.get("NOVA_ALERTS_DIR", "/var/lib/nova-alerts")      # (overridable for testing)
STATE_FILE  = f"{_DIR}/state.json"
STATUS_FILE = f"{_DIR}/www/status.json"
SPOOL_DIR   = f"{_DIR}/spool"
ARCHIVE     = f"{_DIR}/www/archive.jsonl"        # permanent history: archived + aged-out events, one JSON per line          # nova-alert drops messages here
LOCK_FILE   = os.environ.get("NOVA_ALERTS_LOCK", "/run/nova-alerts.lock")
LEVELS = {"info": 0, "warning": 1, "critical": 2}
COLORS = {"info": 0x3B82F6, "warning": 0xF5A524, "critical": 0xE5484D, "resolved": 0x30A46C}
ICONS  = {"info": "ℹ️", "warning": "⚠️", "critical": "🚨", "resolved": "✅"}
HOST = socket.gethostname()

DEFAULTS = {
    "discord_webhook": "",
    "discord_username": "Nova",
    "discord_mention_on_critical": "",       # e.g. "<@123456789012345678>"
    "push_min_level": "info",               # lowest level that reaches the phone
    "push_logins": True,
    "push_usb": True,
    "critical_reminder_hours": 12,
    "thresholds": {
        "cpu_temp_warn": 85, "cpu_temp_crit": 95,
        "nvme_temp_warn": 70, "nvme_temp_crit": 80,
        "sata_temp_warn": 55, "sata_temp_crit": 65,
        "load_per_core_warn": 1.5,           # 15-min load average / cores
        "mem_available_warn_pct": 8,
        "swap_used_warn_pct": 85,
        "disk_warn_pct": 90, "disk_crit_pct": 97,
        "backup_stale_hours": 36,
    },
    # "auto": every real filesystem in /etc/fstab must be mounted, and gets a space check — except
    # the branches of a mergerfs pool (filled to 100% by design; the pool's free space is what
    # matters). A list of mount points overrides it.
    "disk_space_mounts": "auto",
    "required_mounts": "auto",
    # "auto": whichever of these are enabled on this machine.
    "required_services": "auto",
    "auto_services": ["ssh", "docker", "cloudflared", "tailscaled", "fail2ban", "cron", "nova-api"],
    "public_urls": [],                       # websites to check every 5 minutes
    "mount_names": {},                       # "/mnt/media": "Media" — names shown in the app
    "db_dumps": [],                          # [{"name": "Photo database", "glob": "/srv/x/*.sql.gz", "metric": "x_db_backup"}]
    "backup_mount": "",                      # where nova-backup writes (only if you use it)
    "maintenance_unit": "",                  # a unit that restarts containers on purpose (alerts are quiet while it runs)
    "known_login_identities": [],            # informational only; every login is reported
    "retired_drives": [],                    # serials removed on purpose: never reported missing
}

# ── helpers ───────────────────────────────────────────────────────────────────
def now():   return time.time()
def iso(t=None): return datetime.fromtimestamp(t or now(), timezone.utc).isoformat(timespec="seconds")
def local_hm(t=None): return datetime.fromtimestamp(t or now()).strftime("%b %d %H:%M")

def sh(cmd, timeout=30):
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return r.returncode, r.stdout
    except Exception as e:
        return 1, ""

def load_json(path, default):
    try:
        with open(path) as f: return json.load(f)
    except Exception: return default

def write_json_atomic(path, data, mode=0o644):
    tmp = f"{path}.tmp{os.getpid()}"
    with open(tmp, "w") as f: json.dump(data, f, indent=1, default=str)
    os.chmod(tmp, mode); os.replace(tmp, path)

def archive_append(events, archived=False):
    """Keep events forever (well — the newest 50 000): ones archived from the Inbox and ones that age
    out of its 200-entry window. Any device can read them (Inbox → Archive)."""
    if not events: return
    try:
        os.makedirs(os.path.dirname(ARCHIVE), exist_ok=True)
        with open(ARCHIVE, "a") as f:
            for e in events:
                f.write(json.dumps({"t": e["t"], "time": e.get("time", ""), "level": e.get("level"), "title": e.get("title", ""),
                                    "detail": e.get("detail", ""), "category": e.get("category", ""), **({"archived": True} if archived else {})}) + "\n")
        os.chmod(ARCHIVE, 0o644)
        if os.path.getsize(ARCHIVE) > 25_000_000:                  # trim the oldest
            lines = open(ARCHIVE).readlines()[-50_000:]
            tmp = ARCHIVE + ".tmp"; open(tmp, "w").writelines(lines); os.chmod(tmp, 0o644); os.replace(tmp, ARCHIVE)
    except Exception: pass

def human_age(seconds):
    s = int(max(0, seconds))
    for unit, n in (("d", 86400), ("h", 3600), ("m", 60)):
        if s >= n: return f"{s // n}{unit} ago" if unit != "d" else f"{s // 86400}d {s % 86400 // 3600}h ago"
    return "just now"

def read_int(p):
    try: return int(open(p).read().strip())
    except Exception: return None

REAL_FS = {"ext2", "ext3", "ext4", "xfs", "btrfs", "f2fs", "vfat", "exfat", "ntfs", "ntfs3", "zfs", "fuse.mergerfs", "jfs", "reiserfs"}

def fstab_mounts():
    """(mount point, fstype, source, options) of every real filesystem in /etc/fstab."""
    out = []
    try:
        for line in open("/etc/fstab"):
            f = line.split()
            if len(f) < 3 or f[0].startswith("#"): continue
            opts = f[3].split(",") if len(f) > 3 else []
            if f[2] in REAL_FS and f[1].startswith("/") and "noauto" not in opts and not f[1].startswith("/boot"):
                out.append((f[1], f[2], f[0], opts))
    except Exception: pass
    return out

def mergerfs_branches():
    br = set()
    for mp, fs, src, _ in fstab_mounts():
        if fs == "fuse.mergerfs":
            for part in src.split(":"):
                br.update(glob.glob(part) if any(c in part for c in "*?[") else [part])
    return br

def unit_installed(name):
    """The service's unit file exists (is-enabled alone can claim "enabled" for units that aren't there)."""
    u = name if "." in name else name + ".service"
    return any(os.path.exists(f"{d}/{u}") for d in ("/etc/systemd/system", "/lib/systemd/system", "/usr/lib/systemd/system", "/run/systemd/system"))

def mount_name(mp, names):
    if mp in names: return names[mp]
    if mp == "/": return "System drive"
    return os.path.basename(mp.rstrip("/")).replace("_", " ").replace("-", " ").capitalize() or mp

# Metric names older app versions read (they're simply extra keys for everyone else).
LEGACY_METRIC = {"/": "root"}            # older apps read root_used; everything else is in metrics["storage"]

# ── state ─────────────────────────────────────────────────────────────────────
class Monitor:
    def __init__(self):
        cfg = load_json(CONFIG_FILE, {})
        for k, v in load_json(APP_NOTIFY_FILE, {}).items():      # app settings win; anything else is ignored
            ok = APP_NOTIFY_KEYS.get(k)
            if (ok is bool and isinstance(v, bool)) or (isinstance(ok, tuple) and v in ok): cfg[k] = v
        self.cfg = {**DEFAULTS, **cfg, "thresholds": {**DEFAULTS["thresholds"], **cfg.get("thresholds", {})}}
        self.st = load_json(STATE_FILE, {})
        self.st.setdefault("conditions", {})   # key -> {level,title,detail,since,last_push}
        self.st.setdefault("recent", [])       # newest first
        self.st.setdefault("outbox", [])       # unsent pushes
        self.st.setdefault("counters", {})     # misc (consecutive failures, SMART baselines…)
        self.st.setdefault("dismissed", {})    # key -> time: dismissed in the app, re-armed once it clears
        self.seen = set()                      # condition keys observed this run
        self.seen_dismissed = set()
        self.pushes = []
        self.metrics = {}
        self.first_run = "journal_cursor" not in self.st

    # A one-shot event (login, USB plug, I/O error burst…)
    def event(self, level, title, detail="", category="event", push=True):
        self._record(level, title, detail, category)
        if push: self._push(level, title, detail)

    # A condition: raised while true, resolved when it stops being true.
    def condition(self, key, active, level="warning", title="", detail=""):
        conds = self.st["conditions"]
        if not active:
            return
        if key in self.st["dismissed"]:          # you said you'd dealt with it: quiet until it clears
            self.seen_dismissed.add(key); return
        self.seen.add(key)
        c = conds.get(key)
        if c is None:
            conds[key] = {"level": level, "title": title, "detail": detail, "since": now(), "last_push": now()}
            self._record(level, title, detail, "condition")
            self._push(level, title, detail)
        else:
            escalated = LEVELS[level] > LEVELS[c["level"]]
            c.update(title=title, detail=detail)
            if escalated:
                c["level"] = level; c["last_push"] = now()
                self._record(level, title, detail, "condition"); self._push(level, title, detail)
            elif level == "critical" and now() - c["last_push"] > self.cfg["critical_reminder_hours"] * 3600:
                c["last_push"] = now()
                self._push(level, f"Still ongoing: {title}", f"Since {local_hm(c['since'])}. {detail}")
            elif LEVELS[level] < LEVELS[c["level"]]:
                c["level"] = level

    def resolve_unseen(self, prefix_owned):
        """Resolve conditions this run was responsible for checking but didn't see."""
        for key in list(self.st["conditions"]):
            if key in self.seen or not any(key.startswith(p) for p in prefix_owned):
                continue
            c = self.st["conditions"].pop(key)
            dur = human_age(now() - c["since"]).replace(" ago", "")
            self._record("resolved", f"Resolved: {c['title']}", f"Lasted {dur}.", "condition")
            self._push("resolved", f"Resolved: {c['title']}", f"Lasted {dur}.")

    def _record(self, level, title, detail, category):
        self.st["recent"].insert(0, {"t": now(), "time": local_hm(), "level": level,
                                     "title": title, "detail": detail, "category": category})
        archive_append(self.st["recent"][200:])          # aged out of the Inbox: into the permanent history
        del self.st["recent"][200:]

    def _push(self, level, title, detail):
        lvl = "info" if level == "resolved" else level
        if LEVELS[lvl] < LEVELS.get(self.cfg["push_min_level"], 0) and level != "resolved":
            return
        self.pushes.append({"level": level, "title": title, "detail": detail, "t": now()})

    # ── 1. journal events ────────────────────────────────────────────────────
    def check_journal(self):
        cursor = self.st.get("journal_cursor")
        cmd = ["journalctl", "-o", "json", "--no-pager"]
        cmd += ["--after-cursor", cursor] if cursor else ["--since", "-2min"]
        rc, out = sh(cmd, timeout=60)
        lines = out.splitlines()
        disk_errs, usb_new, last = {}, {}, None
        t = self.cfg
        pending_ts_ip = {}
        for line in lines:
            try: e = json.loads(line)
            except Exception: continue
            last = e.get("__CURSOR", last)
            msg = e.get("MESSAGE") or ""
            if not isinstance(msg, str): continue
            ident = e.get("SYSLOG_IDENTIFIER", "")

            # Logins ---------------------------------------------------------
            m = re.search(r'handling new SSH connection from (\S+) \(([\d.:a-f]+)\) to ssh-user "(\S+)"', msg)
            if m: pending_ts_ip[m.group(1)] = m.group(2)
            m = re.search(r'access granted to (\S+) as ssh-user "(\S+)"', msg)
            if m and t["push_logins"]:
                ip = pending_ts_ip.get(m.group(1), "?")
                self.event("info", f"Login: {m.group(2)} via Tailscale SSH",
                           f"From {m.group(1)} ({ip}).", "login")
            m = re.search(r'access denied for (\S+)', msg) if ident == "tailscaled" else None
            if m: self.event("warning", "Tailscale SSH access denied", f"Identity: {m.group(1)}", "login")
            m = re.search(r'^Accepted (\w+) for (\S+) from (\S+)', msg) if ident in ("sshd", "sshd-session") else None
            if m and t["push_logins"]:
                via = "SSH from a container (e.g. a browser terminal)" if m.group(3).startswith("172.17.") else f"SSH from {m.group(3)}"
                self.event("info", f"Login: {m.group(2)} via {via}", f"Method: {m.group(1)}.", "login")
            if ident in ("sshd", "sshd-session") and re.search(r'Failed password|Invalid user|authentication failure', msg):
                self.event("warning", "Failed SSH login", msg[:180], "login")
            if ident == "login" and "session opened for user" in msg and "remote:session" not in msg:
                self.event("info", "Login: local console", msg[:160], "login")
            if ident == "sudo" and re.search(r'incorrect password attempts|authentication failure', msg):
                self.event("warning", "Failed sudo attempt", msg[:180], "login")
            if ident.startswith("fail2ban") and " Ban " in msg:
                self.event("warning", "fail2ban banned an address", msg[:180], "security")

            # Kernel: disks / USB / memory ----------------------------------
            if ident == "kernel" or e.get("_TRANSPORT") == "kernel":
                m = re.search(r'I/O error, dev (\w+)|(ata\d+)[.\d]*: (?:hard resetting link|failed to IDENTIFY|exception .* frozen|SError)|EXT4-fs error \(device (\w+)\)|device offline error, dev (\w+)', msg)
                if m:
                    dev = next(g for g in m.groups() if g)
                    disk_errs[dev] = disk_errs.get(dev, 0) + 1
                m = re.search(r'usb (\S+): New USB device found, idVendor=(\w+), idProduct=(\w+)', msg)
                if m: usb_new[m.group(1)] = {"id": f"{m.group(2)}:{m.group(3)}", "name": ""}
                m = re.search(r'usb (\S+): Product: (.+)', msg)
                if m and m.group(1) in usb_new: usb_new[m.group(1)]["name"] = m.group(2).strip()
                m = re.search(r'usb (\S+): USB disconnect, device number', msg)
                if m and t["push_usb"] and not re.match(r"^usb\d+$", m.group(1)):
                    self.event("info", "USB device unplugged", f"Port {m.group(1)}.", "device")
                m = re.search(r'Out of memory: Killed process \d+ \(([^)]+)\)', msg)
                if m: self.event("critical", f"Out of memory: killed {m.group(1)}", msg[:200], "memory")

            # systemd: failed units ------------------------------------------
            m = re.search(r'^(\S+\.(?:service|mount)): Failed with result', msg) if ident == "systemd" else None
            if m and not m.group(1).startswith(("user@", "nova-alerts")):
                self.event("warning", f"Service failed: {m.group(1)}", msg[:200], "service")

        # Map ataN ports to their disk so one problem isn't reported twice (sdb + ata1).
        port_dev = {}
        for blk in glob.glob("/sys/block/sd*"):
            m2 = re.search(r"/(ata\d+)/", os.path.realpath(f"{blk}/device"))
            if m2: port_dev[m2.group(1)] = os.path.basename(blk)
        merged = {}
        for dev, n in disk_errs.items():
            key = port_dev.get(dev, dev)
            merged[key] = merged.get(key, 0) + n
        for dev, n in merged.items():
            port = next((p for p, d in port_dev.items() if d == dev), "")
            self.event("critical", f"Disk errors on {dev}" + (f" ({port})" if port else ""),
                       f"{n} error line(s) in the kernel log this minute — check cables/power.", "disk")
        # Devices enumerated during boot (built-in controllers, Bluetooth, RGB…) are not
        # "plugged in" — skip the first 3 minutes of uptime, and USB root hubs always.
        booting = float(open("/proc/uptime").read().split()[0]) < 180
        for port, u in usb_new.items():
            if t["push_usb"] and not booting and not re.match(r"^usb\d+$", port) and "Host Controller" not in u["name"]:
                self.event("info", f"USB device connected: {u['name'] or u['id']}", f"Port {port}, id {u['id']}.", "device")
        if last: self.st["journal_cursor"] = last
        elif not cursor:
            rc, out = sh(["journalctl", "-n", "0", "--show-cursor", "--no-pager"])
            m = re.search(r'-- cursor: (\S+)', out)
            if m: self.st["journal_cursor"] = m.group(1)

    # ── 1b. docker events ────────────────────────────────────────────────────
    def check_docker_events(self):
        since = int(self.st.get("docker_since", now() - 120)); until = int(now())
        rc, out = sh(["docker", "events", "--since", str(since), "--until", str(until),
                      "--filter", "type=container", "--format", "{{json .}}"], timeout=30)
        self.st["docker_since"] = until
        maint = self.maintenance_running()
        for line in out.splitlines():
            try: ev = json.loads(line)
            except Exception: continue
            act = ev.get("Action", ""); attrs = ev.get("Actor", {}).get("Attributes", {})
            name = attrs.get("name", "?")
            if act == "oom":
                self.event("critical", f"Container ran out of memory: {name}", "", "container")
            elif act == "die" and attrs.get("exitCode") not in ("0", "143", "137") and not maint:
                self.event("warning", f"Container crashed: {name}", f"Exit code {attrs.get('exitCode')}.", "container")
            elif act.startswith("health_status: unhealthy"):
                self.event("warning", f"Container unhealthy: {name}", "", "container")

    def maintenance_running(self):
        u = self.cfg.get("maintenance_unit")
        if not u: return False
        rc, _ = sh(["systemctl", "is-active", "--quiet", u])
        return rc == 0

    # ── 2. current state ─────────────────────────────────────────────────────
    def check_temps(self):
        t = self.cfg["thresholds"]
        cpu = nvme = None
        for h in glob.glob("/sys/class/hwmon/hwmon*"):
            name = open(f"{h}/name").read().strip()
            v = read_int(f"{h}/temp1_input")
            if v is None: continue
            if name in ("k10temp", "zenpower", "coretemp", "cpu_thermal") and cpu is None: cpu = v / 1000
            if name == "nvme": nvme = v / 1000
        if cpu is not None:
            self.metrics["cpu_temp"] = f"{cpu:.0f}°C"
            lvl = "critical" if cpu >= t["cpu_temp_crit"] else "warning"
            self.condition("temp:cpu", cpu >= t["cpu_temp_warn"], lvl, f"CPU is hot: {cpu:.0f}°C", f"Threshold {t['cpu_temp_warn']}°C.")
        if nvme is not None:
            self.metrics["nvme_temp"] = f"{nvme:.0f}°C"
            lvl = "critical" if nvme >= t["nvme_temp_crit"] else "warning"
            self.condition("temp:nvme", nvme >= t["nvme_temp_warn"], lvl, f"Boot NVMe is hot: {nvme:.0f}°C", "")

    def check_load_mem(self):
        t = self.cfg["thresholds"]
        cores = os.cpu_count() or 1
        l1, l5, l15 = os.getloadavg()
        self.metrics["load"] = f"{l1:.2f} / {cores} cores"
        self.condition("load:cpu", l15 / cores >= t["load_per_core_warn"], "warning",
                       f"Sustained high CPU load: {l15:.1f}", f"15-min load average on {cores} cores.")
        mi = {}
        for line in open("/proc/meminfo"):
            k, v = line.split(":", 1); mi[k] = int(v.split()[0])
        avail = 100 * mi["MemAvailable"] / mi["MemTotal"]
        self.metrics["memory"] = f"{100 - avail:.0f}% used"
        self.condition("mem:low", avail < t["mem_available_warn_pct"], "warning",
                       f"Memory nearly full: {avail:.0f}% available", "")
        if mi.get("SwapTotal"):
            sw = 100 * (mi["SwapTotal"] - mi["SwapFree"]) / mi["SwapTotal"]
            self.metrics["swap"] = f"{sw:.0f}% used"
            self.condition("mem:swap", sw >= t["swap_used_warn_pct"], "warning", f"Swap {sw:.0f}% used", "")
        up = float(open("/proc/uptime").read().split()[0])
        self.metrics["uptime"] = f"{int(up // 86400)}d {int(up % 86400 // 3600)}h"

    def check_disks(self):
        t = self.cfg["thresholds"]
        mounted = {l.split()[1] for l in open("/proc/mounts")}
        quiet = set(load_json("/run/nova-unmounted.json", []))   # safely unmounted from the app
        auto = fstab_mounts()
        req = self.cfg["required_mounts"]; req = [m for m, *_ in auto] if req == "auto" else req
        spc = self.cfg["disk_space_mounts"]
        if spc == "auto":
            br = mergerfs_branches(); spc = ["/"] + [m for m, *_ in auto if m != "/" and m not in br]
        names, storage = self.cfg.get("mount_names") or {}, []
        for mp in req:
            if mp in quiet: continue
            self.condition(f"mount:{mp}", mp not in mounted, "critical", f"Not mounted: {mp}",
                           f"{mount_name(mp, names)} isn't available — a drive dropped out or wasn't there at boot.")
        for mp in spc:
            if mp not in mounted: continue
            s = os.statvfs(mp)
            pct = 100 * (1 - s.f_bavail / s.f_blocks) if s.f_blocks else 0
            free_gb = s.f_bavail * s.f_frsize / 1e9
            storage.append({"name": mount_name(mp, names), "mount": mp, "pct": round(pct, 1),
                            "free": s.f_bavail * s.f_frsize, "total": s.f_blocks * s.f_frsize})
            if mp in LEGACY_METRIC: self.metrics[f"{LEGACY_METRIC[mp]}_used"] = f"{pct:.0f}% ({free_gb:.0f} GB free)"
            lvl = "critical" if pct >= t["disk_crit_pct"] else "warning"
            self.condition(f"space:{mp}", pct >= t["disk_warn_pct"], lvl, f"{mp} is {pct:.0f}% full", f"{free_gb:.0f} GB free.")
            # Inodes (file-count limit) run out independently of space.
            ipct = 100 * (1 - s.f_favail / s.f_files) if s.f_files else 0
            self.condition(f"inodes:{mp}", ipct >= 85, "critical" if ipct >= 95 else "warning",
                           f"{mp} is running out of inodes ({ipct:.0f}%)",
                           "The filesystem's file-count limit, separate from free space. Can't add files once full.")
        self.metrics["storage"] = storage

    def check_drives(self, smart_due):
        """Drive inventory by serial (catches plug/unplug of any disk, incl. USB) + SMART."""
        rc, out = sh(["lsblk", "-J", "-d", "-o", "NAME,SERIAL,MODEL,SIZE,TRAN,TYPE"])
        try: devs = [d for d in json.loads(out)["blockdevices"] if d.get("type") == "disk" and not d["name"].startswith(("loop", "zram"))]
        except Exception: return
        now_set = {(d.get("serial") or d["name"]): d for d in devs}
        known = self.st["counters"].setdefault("drives", {})
        for serial in self.cfg.get("retired_drives", []):     # unplugged on purpose
            known.pop(serial, None); self.st["conditions"].pop(f"drive:{serial}", None)
            self.st["counters"].get("smart", {}).pop(serial, None)
            self.st["counters"].get("smart_baseline", {}).pop(serial, None)
        now_set = {k: v for k, v in now_set.items() if k not in self.cfg.get("retired_drives", [])}
        if known:
            for serial, d in now_set.items():
                if serial not in known:
                    self.event("warning", f"New drive detected: {d.get('model') or d['name']} ({d['size']})",
                               f"/dev/{d['name']}, serial {serial}, via {d.get('tran') or '?'}.", "device")
        quiet = set(load_json("/run/nova-unmounted.json", []))
        for serial, d in known.items():
            if serial in quiet: continue
            self.condition(f"drive:{serial}", serial not in now_set, "critical",
                           f"Drive missing: {d.get('model') or d['name']} ({d.get('size')})",
                           f"Was /dev/{d['name']}, serial {serial}. Check its cable and power.")
        for serial, d in now_set.items():
            known[serial] = {k: d.get(k) for k in ("name", "model", "size", "tran")}
        self.metrics["drives"] = f"{len(now_set)} detected"

        # SMART: every 30 min (cached in between so Homepage always has temps)
        cache = self.st["counters"].setdefault("smart", {})
        if smart_due:
            for serial, d in now_set.items():
                rc, out = sh(["smartctl", "-H", "-A", "-j", f"/dev/{d['name']}"], timeout=60)
                try: j = json.loads(out)
                except Exception: continue
                attrs = {a["id"]: a["raw"]["value"] for a in j.get("ata_smart_attributes", {}).get("table", [])}
                nv = j.get("nvme_smart_health_information_log", {})
                cache[serial] = {
                    "name": d["name"], "model": d.get("model"),
                    "passed": j.get("smart_status", {}).get("passed"),
                    "temp": j.get("temperature", {}).get("current"),
                    "realloc": attrs.get(5), "pending": attrs.get(197), "uncorrect": attrs.get(187),
                    "crc": attrs.get(199), "media_errors": nv.get("media_errors"), "t": now(),
                }
        t = self.cfg["thresholds"]
        base = self.st["counters"].setdefault("smart_baseline", {})
        temps = []
        for serial, s in cache.items():
            if serial not in now_set: continue
            label = f"{s.get('model') or s['name']} (/dev/{s['name']})"
            self.condition(f"smart:fail:{serial}", s.get("passed") is False, "critical",
                           f"SMART health FAILED: {label}", "Replace this drive; restore from backup.")
            if s.get("temp") is not None and s["name"].startswith("sd"):
                temps.append(s["temp"])
                lvl = "critical" if s["temp"] >= t["sata_temp_crit"] else "warning"
                self.condition(f"temp:{serial}", s["temp"] >= t["sata_temp_warn"], lvl,
                               f"Drive hot: {label} {s['temp']}°C", "")
            b = base.setdefault(serial, {})
            for k, nice in (("realloc", "reallocated sectors"), ("pending", "pending sectors"),
                            ("uncorrect", "uncorrectable errors"), ("crc", "cable (CRC) errors"),
                            ("media_errors", "media errors")):
                v = s.get(k)
                if v is None: continue
                if k in b and v > b[k]:
                    # CRC = cable. "pending" on SSDs (esp. Crucial MX500) flickers 0→1→0 during the
                    # drive's own housekeeping — a warning, not an emergency. Reallocated /
                    # uncorrectable / media errors mean real degradation → critical.
                    lvl = "warning" if k in ("crc", "pending") else "critical"
                    self.event(lvl, f"{label}: {nice} increased {b[k]} → {v}",
                               "CRC = cable/connection; the others mean the drive itself is degrading.", "disk")
                b[k] = v
        if temps: self.metrics["drive_temps"] = f"{min(temps)}–{max(temps)}°C"
        # One light per drive on the app's server picture: green, amber (warning), red (missing/failing).
        conds, states = self.st["conditions"], []
        for serial, d in known.items():
            sm = cache.get(serial, {}); lvl, why = "ok", ""
            if f"drive:{serial}" in conds: lvl, why = "critical", "missing"
            elif sm.get("passed") is False: lvl, why = "critical", "SMART failed"
            elif f"temp:{serial}" in conds: lvl, why = conds[f"temp:{serial}"]["level"], "hot"
            elif any((sm.get(k) or 0) > 0 for k in ("realloc", "pending", "uncorrect", "media_errors")): lvl, why = "warning", "worn"
            states.append({"name": d.get("name"), "model": d.get("model"), "serial": serial, "level": lvl, "why": why,
                           "boot": (d.get("name") or "").startswith("nvme") or (d.get("tran") or "") == "nvme"})
        states.sort(key=lambda x: (not x["boot"], x["name"] or ""))
        self.metrics["drive_states"] = states

    def check_services(self):
        down = []
        svcs = self.cfg["required_services"]
        if svcs == "auto":
            svcs = [x for x in self.cfg["auto_services"] if unit_installed(x) and sh(["systemctl", "is-enabled", x])[1].strip() in ("enabled", "static", "indirect", "alias")]
        for svc in svcs:
            rc, out = sh(["systemctl", "is-active", svc])
            active = out.strip() == "active"
            if not active: down.append(svc)
            self.condition(f"svc:{svc}", not active, "critical", f"Service down: {svc}", f"systemctl is-active → {out.strip() or 'unknown'}")
        rc, ids = sh(["docker", "ps", "-aq"])
        rc, out = sh(["docker", "inspect"] + ids.split()) if ids.strip() else (0, "[]")
        try: ctrs = json.loads(out)
        except Exception: ctrs = []
        total = running = 0
        maint = self.maintenance_running()
        fails = self.st["counters"].setdefault("container_down_runs", {})
        for c in ctrs:
            name = c["Name"].lstrip("/")
            if c["HostConfig"]["RestartPolicy"]["Name"] in ("", "no"):
                continue                                    # one-off containers don't count
            total += 1
            state = c["State"]["Status"]
            health = (c["State"].get("Health") or {}).get("Status", "")
            up = state == "running" and health != "unhealthy"
            running += state == "running"
            fails[name] = 0 if up else fails.get(name, 0) + 1
            # 3 consecutive minutes down, and never during a maintenance update window
            bad = fails[name] >= 3 and not maint
            self.condition(f"ctr:{name}", bad, "critical" if state != "running" else "warning",
                           f"Container {'unhealthy' if state == 'running' else 'down'}: {name}",
                           f"State: {state}{', ' + health if health else ''}.")
        if total: self.metrics["containers"] = f"{running}/{total} running"

    def check_public(self, due):
        if not due:
            for k, c in self.st["conditions"].items():
                if k.startswith("web:"): self.seen.add(k)
            self.seen_dismissed.update(k for k in self.st["dismissed"] if k.startswith("web:"))
            return
        fails = self.st["counters"].setdefault("web_fails", {})
        ok = 0
        for url in self.cfg["public_urls"]:
            code = 0
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "nova-alerts"})
                code = urllib.request.urlopen(req, timeout=15).status
            except urllib.error.HTTPError as e: code = e.code
            except Exception: code = 0
            good = 200 <= code < 400
            ok += good
            fails[url] = 0 if good else fails.get(url, 0) + 1
            host = re.sub(r"^https?://([^/]+).*", r"\1", url)
            self.condition(f"web:{url}", fails[url] >= 2, "critical", f"Website down: {host}",
                           f"HTTP {code or 'no response'} twice in a row (checked every 5 min).")
        self.metrics["websites"] = f"{ok}/{len(self.cfg['public_urls'])} up"

    def check_backups(self):
        t = self.cfg["thresholds"]["backup_stale_hours"]
        f = "/var/lib/nova-backup/last_success"
        if not os.path.isdir("/var/lib/nova-backup"):    # not using nova-backup: only the reboot check below
            f = None
        age = now() - os.path.getmtime(f) if f and os.path.exists(f) else None
        if f:
            self.metrics["config_backup"] = human_age(age) if age is not None else "never"
            self.condition("backup:config", age is None or age > t * 3600, "warning",
                           "Server config backup is stale", f"Last success: {self.metrics['config_backup']}.")
        for d in self.cfg.get("db_dumps") or []:       # database dumps you want watched
            dumps = sorted(glob.glob(d.get("glob", "")), key=os.path.getmtime)
            if d.get("mount") and not os.path.ismount(d["mount"]): continue
            dage = now() - os.path.getmtime(dumps[-1]) if dumps else None
            metric = d.get("metric") or re.sub(r"\W+", "_", d.get("name", "db").lower()) + "_backup"
            self.metrics[metric] = human_age(dage) if dage is not None else "none found"
            self.condition(f"backup:db:{metric}", dage is None or dage > t * 3600, "warning",
                           f"{d.get('name', 'Database')} backup is stale", f"Newest dump: {self.metrics[metric]}.")
        # Data backups from a nova-backup script, if this server has one
        pf = "/var/lib/nova-backup/data_last_success"
        bm = self.cfg.get("backup_mount") or ""
        if os.path.exists(pf) or (bm and os.path.ismount(bm)):
            page = now() - os.path.getmtime(pf) if os.path.exists(pf) else None
            self.metrics["data_backup"] = human_age(page) if page is not None else "not yet run"
            first_run_pending = page is None and bm and os.path.exists(bm) and now() - os.path.getmtime(bm) < 2 * 86400
            running = sh(["systemctl", "is-active", "nova-backup.service"])[1].strip() in ("active", "activating")
            if running: self.metrics["data_backup"] = "running now"
            self.condition("backup:data", not running and not first_run_pending and (page is None or page > t * 3600), "warning",
                           "Data backup is stale", f"Last success: {self.metrics['data_backup']}. See /var/log/nova-backup.log")
            st = load_json("/var/lib/nova-backup/backup_status.json", {})
            sets = st.get("sets", {})
            if sets:
                okn = sum(1 for v in sets.values() if v == "ok")
                self.metrics["backup_sets"] = f"{okn}/{len(sets)} ok · {st.get('snapshots', 0)} snapshots"
            v = load_json("/var/lib/nova-backup/photos_last_verify.json", {})
            if v:
                self.metrics["backup_verify"] = ("✓ " if v.get("mismatched", 0) + v.get("missing", 0) == 0 else "✗ ") + \
                    f"{v.get('ok', 0)} files checked {v.get('time', '')[:10]}"
        self.condition("sys:reboot", os.path.exists("/var/run/reboot-required"), "info",
                       "Reboot needed to finish updates",
                       open("/var/run/reboot-required.pkgs").read().strip().replace("\n", ", ")
                       if os.path.exists("/var/run/reboot-required.pkgs") else "")

    # ── spool from nova-alert (other scripts) ────────────────────────────────
    def drain_spool(self):
        for p in sorted(glob.glob(f"{SPOOL_DIR}/*.json")):
            m = load_json(p, None)
            try: os.remove(p)
            except Exception: pass
            if m and m.get("level") in LEVELS:
                self.event(m["level"], m.get("title", "(no title)"), m.get("detail", ""), m.get("category", "script"))

    # ── 3. delivery ──────────────────────────────────────────────────────────
    def deliver(self):
        out = self.st["outbox"] + self.pushes
        if self.first_run:                     # don't blast the backlog on first install
            out = [p for p in out if p["level"] in ("critical",)]
        hook = self.cfg.get("discord_webhook")
        if self.cfg.get("discord_paused"):      # muted from the Nova app: still recorded, just not pushed
            hook = ""
        if not hook:
            # No channel configured yet: everything is still on Homepage, and queueing would
            # mean a burst of stale pings the moment a webhook is added. Drop instead.
            self.st["outbox"] = []
            return
        if not out:
            return
        remaining = []
        for i in range(0, len(out), 10):       # Discord: max 10 embeds per message
            chunk = out[i:i + 10]
            embeds = [{
                "title": f"{ICONS[p['level']]} {p['title']}"[:256],
                "description": (p.get("detail") or "")[:1000],
                "color": COLORS[p["level"]],
                "timestamp": iso(p["t"]),
                "footer": {"text": f"{HOST} · {p['level']}"},
            } for p in chunk]
            body = {"username": self.cfg["discord_username"], "embeds": embeds}
            if any(p["level"] == "critical" for p in chunk) and self.cfg.get("discord_mention_on_critical"):
                body["content"] = self.cfg["discord_mention_on_critical"]
                body["allowed_mentions"] = {"parse": ["users", "roles"]}
            req = urllib.request.Request(hook, data=json.dumps(body).encode(),
                                         headers={"Content-Type": "application/json", "User-Agent": "nova-alerts"})
            try:
                urllib.request.urlopen(req, timeout=15)
                time.sleep(1)                  # stay well under Discord's rate limit
            except Exception as e:
                remaining.extend(chunk)
        self.st["outbox"] = remaining[-100:]   # retried next minute

    # ── 4. Homepage status ───────────────────────────────────────────────────
    def write_status(self):
        conds = sorted(self.st["conditions"].values(), key=lambda c: (-LEVELS[c["level"]], c["since"]))
        worst = max((LEVELS[c["level"]] for c in conds), default=-1)
        level = {-1: "ok", 0: "ok", 1: "warning", 2: "critical"}[worst]
        n_problems = sum(1 for c in conds if c["level"] != "info")
        if not conds: headline = "All systems normal"
        elif n_problems == 0: headline = conds[0]["title"]
        else: headline = f"{n_problems} issue{'s' * (n_problems > 1)}: {conds[0]['title']}"
        badge = {"ok": "🟢", "warning": "🟡", "critical": "🔴"}[level]
        status = {
            "updated": iso(), "updated_local": local_hm(), "host": HOST,
            "level": level, "status": f"{badge} {headline}", "headline": headline,
            "active_count": n_problems,
            "active": [{"level": c["level"], "title": f"{ICONS[c['level']]} {c['title']}", "key": k,
                        "since": human_age(now() - c["since"]), "detail": c.get("detail", "")}
                       for k, c in sorted(self.st["conditions"].items(), key=lambda kc: (-LEVELS[kc[1]["level"]], kc[1]["since"]))]
                      or [{"level": "ok", "title": "🟢 Nothing active", "since": "", "detail": ""}],
            "recent": [{"title": f"{ICONS.get(r['level'], '•')} {r['title']}", "time": r["time"],
                        "level": r["level"], "detail": r.get("detail", "")} for r in self.st["recent"][:30]],
            "metrics": self.metrics,
            "delivery": {"discord": bool(self.cfg.get("discord_webhook")), "queued": len(self.st["outbox"])},
        }
        os.makedirs(os.path.dirname(STATUS_FILE), exist_ok=True)
        write_json_atomic(STATUS_FILE, status, 0o644)
        # Inbox for the Nova app: same events Discord gets, with exact timestamps.
        write_json_atomic(os.path.join(os.path.dirname(STATUS_FILE), "events.json"),
                          {"updated": iso(), "events": [{"t": r["t"], "time": r["time"], "level": r["level"],
                                                         "title": r["title"], "detail": r.get("detail", ""),
                                                         "category": r.get("category", "")}
                                                        for r in self.st["recent"][:200]]}, 0o644)

    def run(self):
        minute = int(now() // 60)
        smart_due = not self.st["counters"].get("smart") or minute % 30 == 0
        web_due = minute % 5 == 0 or "websites" not in self.st.get("last_metrics", {})
        steps = [
            (self.check_journal, ()), (self.check_docker_events, ()), (self.drain_spool, ()),
            (self.check_temps, ()), (self.check_load_mem, ()), (self.check_disks, ()),
            (self.check_drives, (smart_due,)), (self.check_services, ()),
            (self.check_public, (web_due,)), (self.check_backups, ()),
        ]
        for fn, args in steps:
            try: fn(*args)
            except Exception as e:   # one broken check must never stop the others
                self._record("warning", f"Monitor check failed: {fn.__name__}", repr(e)[:200], "monitor")
        # conditions checked this run that are no longer true → resolved
        self.resolve_unseen(["temp:", "load:", "mem:", "mount:", "space:", "inodes:", "drive:", "smart:",
                             "svc:", "ctr:", "web:", "backup:", "sys:"])
        for k in list(self.st["dismissed"]):       # cleared since you dismissed it → alert again next time
            if k not in self.seen_dismissed: self.st["dismissed"].pop(k, None)
        # keep metrics that are only refreshed occasionally (e.g. websites every 5 min)
        self.metrics = {**self.st.get("last_metrics", {}), **self.metrics}
        for retired in ("photo_backup",):        # metrics that no longer exist
            self.metrics.pop(retired, None)
        self.st["last_metrics"] = self.metrics
        self.deliver()
        self.write_status()
        self.st["last_run"] = now()
        write_json_atomic(STATE_FILE, self.st, 0o600)


def cli_notify(argv):
    """nova-alert <level> <title> [detail] — queue an alert from any script."""
    if len(argv) < 2 or argv[0] not in LEVELS:
        print("usage: nova-alert <info|warning|critical> <title> [details]", file=sys.stderr); return 2
    os.makedirs(SPOOL_DIR, exist_ok=True)
    p = f"{SPOOL_DIR}/{time.time_ns()}-{os.getpid()}.json"
    write_json_atomic(p, {"level": argv[0], "title": argv[1], "detail": " ".join(argv[2:]),
                          "category": os.environ.get("NOVA_ALERT_CATEGORY", "script")}, 0o600)
    return 0


def main():
    if os.path.basename(sys.argv[0]) == "nova-alert" or (len(sys.argv) > 1 and sys.argv[1] == "notify"):
        args = sys.argv[1:] if os.path.basename(sys.argv[0]) == "nova-alert" else sys.argv[2:]
        sys.exit(cli_notify(args))
    if os.geteuid() != 0:
        print("run as root", file=sys.stderr); sys.exit(1)
    os.makedirs(os.path.dirname(STATE_FILE), exist_ok=True)
    command = len(sys.argv) > 1 and sys.argv[1] in ("forget-drive", "dismiss", "delete-event", "restore-event", "test")
    with open(LOCK_FILE, "w") as lf:
        if command:                      # a command must not silently do nothing: wait for a running pass
            end = time.time() + 120
            while True:
                try: fcntl.flock(lf, fcntl.LOCK_EX | fcntl.LOCK_NB); break
                except OSError:
                    if time.time() > end: print("busy: a check pass is still running", file=sys.stderr); sys.exit(3)
                    time.sleep(.5)
        else:
            try: fcntl.flock(lf, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError: return       # previous run still going
        if len(sys.argv) > 2 and sys.argv[1] == "delete-event":
            # Swiped away in the Inbox. "all" clears the history; otherwise the event's timestamp(s).
            m = Monitor(); before = len(m.st["recent"])
            if sys.argv[2] == "all": gone, m.st["recent"] = m.st["recent"], []
            else:
                ts = [float(x) for x in sys.argv[2].split(",")[:200]]
                hit = lambda r: any(abs(r["t"] - t) < 0.000005 for t in ts)
                gone = [r for r in m.st["recent"] if hit(r)]; m.st["recent"] = [r for r in m.st["recent"] if not hit(r)]
            archive_append(gone, archived=True)              # archived, not destroyed: Inbox → Archive
            m.first_run = False; m.metrics = m.st.get("last_metrics", {}); m.write_status()
            write_json_atomic(STATE_FILE, m.st, 0o600)
            print(json.dumps({"ok": True, "deleted": before - len(m.st["recent"])}))
            return
        if len(sys.argv) > 2 and sys.argv[1] == "restore-event":
            # Back from the Archive into the Inbox (the newest 200 stay in the Inbox, as always).
            ts = [float(x) for x in sys.argv[2].split(",")[:200]]
            hit = lambda r: any(abs(r.get("t", 0) - t) < 0.000005 for t in ts)
            keep, back = [], []
            try:
                for line in open(ARCHIVE):
                    try: r = json.loads(line)
                    except ValueError: keep.append(line); continue
                    (back if hit(r) and not any(abs(b["t"] - r["t"]) < 0.000005 for b in back) else keep).append(r if hit(r) else line)
            except FileNotFoundError: pass
            m = Monitor()
            have = {round(r["t"], 6) for r in m.st["recent"]}
            for r in back:
                r.pop("archived", None)
                if round(r["t"], 6) not in have: m.st["recent"].append({k: r.get(k, "") for k in ("t", "time", "level", "title", "detail", "category")})
            m.st["recent"].sort(key=lambda r: -r["t"])
            archive_append(m.st["recent"][200:]); del m.st["recent"][200:]
            tmp = ARCHIVE + ".tmp"
            with open(tmp, "w") as f: f.writelines(x if isinstance(x, str) else json.dumps(x) + "\n" for x in keep)
            os.chmod(tmp, 0o644); os.replace(tmp, ARCHIVE)
            m.first_run = False; m.metrics = m.st.get("last_metrics", {}); m.write_status()
            write_json_atomic(STATE_FILE, m.st, 0o600)
            print(json.dumps({"ok": True, "restored": len(back)}))
            return
        if len(sys.argv) > 2 and sys.argv[1] == "dismiss":
            key = sys.argv[2]
            if not re.fullmatch(r"[a-z]+:[\w./:@+-]{1,200}", key): print("bad key", file=sys.stderr); sys.exit(2)
            m = Monitor(); c = m.st["conditions"].pop(key, None)
            if key.startswith("drive:"):       # a drive removed on purpose: forget it completely
                serial = key[6:]
                for d in ("drives", "smart", "smart_baseline"): m.st["counters"].get(d, {}).pop(serial, None)
            else:
                m.st["dismissed"][key] = now()
            if c: m._record("info", f"Dismissed: {c['title']}", "From the Nova app. It will alert again if it comes back after clearing." if not key.startswith("drive:") else "From the Nova app: removed on purpose.", "condition")
            m.first_run = False; m.metrics = m.st.get("last_metrics", {}); m.write_status()
            write_json_atomic(STATE_FILE, m.st, 0o600)
            print(json.dumps({"ok": True, "dismissed": key, "found": bool(c)}))
            return
        if len(sys.argv) > 2 and sys.argv[1] == "forget-drive":
            # A drive you removed on purpose: stop reporting it as missing.
            st = load_json(STATE_FILE, {})
            gone = [k for k in list(st.get("counters", {}).get("drives", {})) if sys.argv[2] in (k, st["counters"]["drives"][k].get("name"))]
            for k in gone:
                st["counters"]["drives"].pop(k, None); st["counters"].get("smart", {}).pop(k, None)
                st["counters"].get("smart_baseline", {}).pop(k, None); st.get("conditions", {}).pop(f"drive:{k}", None)
            write_json_atomic(STATE_FILE, st, 0o600)
            print("forgot:", gone or "nothing matched (give a serial or a name like sdc)")
            return
        if len(sys.argv) > 1 and sys.argv[1] == "test":
            m = Monitor()
            m.event("info", "Test alert from Nova", "If you can read this, alerts reach your phone. 🎉", "test")
            m.first_run = False
            m.deliver(); m.write_status()
            write_json_atomic(STATE_FILE, m.st, 0o600)
            print("queued/sent:", "discord" if m.cfg.get("discord_webhook") else "no webhook configured — shown on Homepage only")
            return
        Monitor().run()


if __name__ == "__main__":
    main()
