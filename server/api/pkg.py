"""
The server's package manager, whichever Linux it is: apt (Debian, Ubuntu, Mint, Raspberry Pi OS), dnf
(Fedora, RHEL, Rocky, Alma), pacman (Arch, Manjaro, EndeavourOS) or zypper (openSUSE).

Everything Nova installs, removes, searches for or updates goes through here, so the rest of Nova
doesn't care which one it is. Names that differ between distros (Docker, the SMART tools…) are mapped.

  which()                         "apt" | "dnf" | "pacman" | "zypper" | ""
  name(pkg)                       Debian's package name → this distro's
  install(pkgs, …) / remove(…)    (root) returns (rc, output)
  refresh()                       update the package lists
  installed(pkgs)                 {pkg: True/False}
  search(q)                       [(name, description)]
  upgradable()                    [{"name", "from", "to", "security"}]
  upgrade(names|None, on_line)    upgrade those (or everything), each output line to on_line
  clean() / autoremove(dry)       downloaded package files / packages nothing needs

On Windows the same calls go to winget (Windows' own package manager, part of App Installer on Windows 10/11 and
Server 2025): the programs there are named by their winget id (7zip.7zip, Docker.DockerDesktop…).
"""
import json, os, re, shutil, subprocess, tempfile

ENV = {**os.environ, "DEBIAN_FRONTEND": "noninteractive", "LC_ALL": "C"}

# Debian's name → the same thing elsewhere (None: not packaged there; Nova says so instead of failing oddly)
NAMES = {
    "dnf":    {"gddrescue": "ddrescue", "gdisk": "gdisk", "dosfstools": "dosfstools", "ntfs-3g": "ntfs-3g", "docker.io": "moby-engine", "docker-compose-v2": "docker-compose", "docker-compose": "docker-compose", "openssh-server": "openssh-server",
               "cifs-utils": "cifs-utils", "nfs-common": "nfs-utils", "mdadm": "mdadm", "btrfs-progs": "btrfs-progs", "smartmontools": "smartmontools",
               "lm-sensors": "lm_sensors", "fio": "fio", "stress-ng": "stress-ng", "rsync": "rsync", "mergerfs": "mergerfs", "zfsutils-linux": None,
               "python3-cryptography": "python3-cryptography", "sshpass": "sshpass", "nvme-cli": "nvme-cli", "hdparm": "hdparm"},
    "pacman": {"gddrescue": "ddrescue", "gdisk": "gptfdisk", "dosfstools": "dosfstools", "ntfs-3g": "ntfs-3g", "docker.io": "docker", "docker-compose-v2": "docker-compose", "docker-compose": "docker-compose", "openssh-server": "openssh",
               "nfs-common": "nfs-utils", "lm-sensors": "lm_sensors", "zfsutils-linux": None, "python3-cryptography": "python-cryptography",
               "btrfs-progs": "btrfs-progs", "cifs-utils": "cifs-utils"},
    # Windows: Debian names that have a Windows equivalent; anything else without a dot (winget ids always have one) isn't there
    "winget": {"docker.io": "Docker.DockerDesktop", "docker-compose-v2": None, "docker-compose": None, "rclone": "Rclone.Rclone",
               "smartmontools": "smartmontools.smartmontools", "git": "Git.Git", "python3": "Python.Python.3.12", "7zip": "7zip.7zip",
               "syncthing": "Syncthing.Syncthing", "tailscale": "Tailscale.Tailscale", "cloudflared": "Cloudflare.cloudflared"},
    "zypper": {"gddrescue": "gddrescue", "gdisk": "gptfdisk", "dosfstools": "dosfstools", "ntfs-3g": "ntfs-3g", "docker.io": "docker", "docker-compose-v2": "docker-compose", "docker-compose": "docker-compose", "nfs-common": "nfs-client",
               "lm-sensors": "sensors", "zfsutils-linux": None, "python3-cryptography": "python3-cryptography"},
}


WINDOWS = os.name == "nt"

def which():
    if WINDOWS:
        import winhost
        return "winget" if winhost.winget() else ""
    for pm in ("apt-get", "dnf", "pacman", "zypper"):
        if shutil.which(pm): return "apt" if pm == "apt-get" else pm
    return ""

def name(pkg):
    """Debian's package name in this distro's words (None if it has no such package)."""
    pm = which()
    if pm in ("apt", ""): return pkg
    if pm == "winget": return NAMES["winget"].get(pkg, pkg if "." in pkg else None)
    return NAMES.get(pm, {}).get(pkg, pkg)

def _run(cmd, timeout=900, on_line=None):
    if WINDOWS: return _wrun(cmd, timeout, on_line)
    try:
        if on_line is None:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=ENV)
            return r.returncode, r.stdout + r.stderr
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=ENV)
        out = []
        for line in p.stdout: out.append(line); on_line(line.rstrip())
        return p.wait(timeout=timeout), "".join(out)
    except FileNotFoundError: return 127, f"{cmd[0]} isn't installed"
    except subprocess.TimeoutExpired: return 124, "it took too long"

def _names(pkgs):
    out, missing = [], []
    for p in pkgs:
        n = name(p)
        (out if n else missing).append(n or p)
    if missing: raise ValueError(f"not available {'from winget' if which() == 'winget' else 'for this Linux'}: {', '.join(missing)}")
    return out

def install(pkgs, recommends=True, on_line=None, timeout=1800):
    pk = _names(pkgs); pm = which()
    if not pk: return 0, ""
    if pm == "apt":
        cmd = ["apt-get", "install", "-y", "-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold"] + ([] if recommends else ["--no-install-recommends"]) + ["--", *pk]
        rc, out = _run(cmd, timeout, on_line)
        if rc != 0 and "Unable to locate" in out: refresh(); rc, out = _run(cmd, timeout, on_line)
        return rc, out
    if pm == "dnf": return _run(["dnf", "install", "-y", *([] if recommends else ["--setopt=install_weak_deps=False"]), *pk], timeout, on_line)
    if pm == "pacman": return _run(["pacman", "-S", "--noconfirm", "--needed", *pk], timeout, on_line)
    if pm == "zypper": return _run(["zypper", "--non-interactive", "install", *([] if recommends else ["--no-recommends"]), *pk], timeout, on_line)
    if pm == "winget": return _wg_each("install", pk, timeout, on_line)
    return 127, "no package manager Nova knows (apt, dnf, pacman or zypper)"

def remove(pkgs, on_line=None, timeout=1800):
    pk = _names(pkgs); pm = which()
    return {"apt": lambda: _run(["apt-get", "remove", "-y", "--", *pk], timeout, on_line),
            "dnf": lambda: _run(["dnf", "remove", "-y", *pk], timeout, on_line),
            "pacman": lambda: _run(["pacman", "-R", "--noconfirm", *pk], timeout, on_line),
            "zypper": lambda: _run(["zypper", "--non-interactive", "remove", *pk], timeout, on_line),
            "winget": lambda: _wg_each("uninstall", pk, timeout, on_line)}.get(pm, lambda: (127, "no package manager"))()

def refresh():
    pm = which()
    cmd = {"apt": ["apt-get", "update", "-qq"], "dnf": ["dnf", "makecache", "-q"], "pacman": ["pacman", "-Sy", "--noconfirm"],
           "zypper": ["zypper", "--non-interactive", "refresh"], "winget": ["winget", "source", "update", "--disable-interactivity"]}.get(pm)
    return _run(cmd, 600) if cmd else (127, "")

def installed(pkgs):
    pm = which(); out = {p: False for p in pkgs}
    names = {p: name(p) for p in pkgs}
    want = [n for n in names.values() if n]
    if not want: return out
    if pm == "apt":
        rc, so = _run(["dpkg-query", "-W", "-f", "${Package} ${db:Status-Abbrev}\n", *want], 60)
        have = {l.split()[0] for l in so.splitlines() if len(l.split()) > 1 and l.split()[1].startswith("ii")}
    elif pm in ("dnf", "zypper"):
        rc, so = _run(["rpm", "-q", "--qf", "%{NAME}\n", *want], 60)
        have = {l.strip() for l in so.splitlines() if l.strip() and " " not in l.strip()}
    elif pm == "pacman":
        have = {n for n in want if _run(["pacman", "-Q", n], 30)[0] == 0}
    elif pm == "winget":
        have = _wg_installed() & set(want)
    else: have = set()
    return {p: (names[p] in have) for p in pkgs}

def search(q, limit=60):
    """(name, description) pairs, names first then descriptions."""
    pm = which(); q = str(q)[:60]
    if not re.fullmatch(r"[\w .+-]{2,60}", q): return []
    res = []
    if pm == "apt":
        _, a = _run(["apt-cache", "search", "--names-only", q], 30); _, b = _run(["apt-cache", "search", q], 30)
        for l in (a + b).splitlines():
            if " - " in l: n, d = l.split(" - ", 1); res.append((n.strip(), d.strip()))
    elif pm == "dnf":
        _, so = _run(["dnf", "search", "-q", q], 60)
        for l in so.splitlines():
            m = re.match(r"\s*([\w.+-]+)\.(?:x86_64|aarch64|i686|noarch|armv7hl|ppc64le|s390x|\w+)(?:\s*:\s*|\t+)(.+)", l)     # dnf4 "name.arch : text", dnf5 "name.arch<TAB>text"
            if m: res.append((m.group(1), m.group(2)))
    elif pm == "pacman":
        _, so = _run(["pacman", "-Ss", q], 30); lines = so.splitlines()
        for i, l in enumerate(lines):
            m = re.match(r"\S+/(\S+)\s", l)
            if m: res.append((m.group(1), lines[i + 1].strip() if i + 1 < len(lines) else ""))
    elif pm == "zypper":
        _, so = _run(["zypper", "--non-interactive", "search", q], 60)
        for l in so.splitlines():
            c = [x.strip() for x in l.split("|")]
            if len(c) >= 4 and c[1] and c[1] != "Name": res.append((c[1], c[3]))
    elif pm == "winget":
        _, so = _run(["winget", "search", q, "--source", "winget", *WG], 90)
        res = [(r[1], r[0]) for r in _wg_table(so) if len(r) >= 2 and "." in r[1]]          # (id, name: winget has no short description here)
    seen, out = set(), []
    for n, d in res:
        if n not in seen: seen.add(n); out.append((n, d))
    return out[:limit]

def upgradable():
    pm = which(); out = []
    if pm == "apt":
        _, so = _run(["apt", "list", "--upgradable"], 120)
        for l in so.splitlines():
            m = re.match(r"([^/\s]+)/(\S+)\s+(\S+)\s+\S+\s+\[upgradable from: ([^\]]+)\]", l)
            if m: out.append({"name": m.group(1), "origin": m.group(2), "to": m.group(3), "from": m.group(4), "security": "security" in m.group(2)})
    elif pm == "dnf":
        _, sec = _run(["dnf", "updateinfo", "list", "--security", "-q"], 120)
        secs = {l.split()[-1].rsplit("-", 2)[0] for l in sec.splitlines() if l.split()}
        _, so = _run(["dnf", "check-update", "-q"], 300)
        for l in so.splitlines():
            c = l.split()
            if len(c) == 3 and "." in c[0]: n = c[0].rsplit(".", 1)[0]; out.append({"name": n, "origin": c[2], "to": c[1], "from": "", "security": n in secs})
    elif pm == "pacman":
        _, so = _run(["pacman", "-Qu"], 120)
        for l in so.splitlines():
            m = re.match(r"(\S+) (\S+) -> (\S+)", l)
            if m: out.append({"name": m.group(1), "origin": "", "to": m.group(3), "from": m.group(2), "security": False})
    elif pm == "zypper":
        _, so = _run(["zypper", "--non-interactive", "list-updates"], 300)
        for l in so.splitlines():
            c = [x.strip() for x in l.split("|")]
            if len(c) >= 6 and c[0] == "v": out.append({"name": c[2], "origin": c[1], "to": c[4], "from": c[3], "security": False})
    elif pm == "winget":
        _, so = _run(["winget", "upgrade", "--source", "winget", *WG], 180)
        for r in _wg_table(so):            # Name · Id · Version · Available · Source
            if len(r) >= 4 and "." in r[1] and r[3]: out.append({"name": r[1], "title": r[0], "origin": "winget", "to": r[3], "from": r[2], "security": False})
    return out

def upgrade(names=None, on_line=None, timeout=3600):
    pm = which()
    if names is not None and not all(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9+._-]{0,100}", str(n)) for n in names): raise ValueError("bad package name")
    if pm == "apt":
        o = ["-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold"]
        return _run(["apt-get", "upgrade", "-y", *o] if names is None else ["apt-get", "install", "--only-upgrade", "-y", *o, "--", *names], timeout, on_line)
    if pm == "dnf": return _run(["dnf", "upgrade", "-y", *(names or [])], timeout, on_line)
    if pm == "pacman": return _run(["pacman", "-Syu", "--noconfirm"] if names is None else ["pacman", "-S", "--noconfirm", *names], timeout, on_line)   # (Arch: partial upgrades aren't supported, so "all" is safest)
    if pm == "zypper": return _run(["zypper", "--non-interactive", "update", *(names or [])], timeout, on_line)
    if pm == "winget":
        if names is None: return _run(["winget", "upgrade", "--all", "--source", "winget", "--silent", *WG, "--accept-package-agreements"], timeout, on_line)
        return _wg_each("upgrade", names, timeout, on_line)
    return 127, "no package manager"

def clean():
    if which() == "winget": return 0, ""
    return _run({"apt": ["apt-get", "clean"], "dnf": ["dnf", "clean", "packages"], "pacman": ["pacman", "-Sc", "--noconfirm"],
                 "zypper": ["zypper", "clean"]}.get(which(), ["true"]), 300)

def cache_dir():
    return {"apt": "/var/cache/apt/archives", "dnf": "/var/cache/dnf", "pacman": "/var/cache/pacman/pkg", "zypper": "/var/cache/zypp/packages"}.get(which(), "")

def autoremove(dry=True, on_line=None):
    """dry: how many packages nothing needs anymore; else remove them."""
    pm = which()
    if pm == "apt":
        rc, so = _run(["apt-get", "-s" if dry else "-y", "autoremove"], 1800 if not dry else 60, on_line)
        return len(re.findall(r"^Remv ", so, re.M)) if dry else (rc, so)
    if pm == "dnf":
        if dry: _, so = _run(["dnf", "autoremove", "--assumeno"], 120); return len(re.findall(r"^ \S+\s+\S+\s+\S+", so, re.M))
        return _run(["dnf", "autoremove", "-y"], 1800, on_line)
    if pm == "pacman":
        rc, so = _run(["pacman", "-Qdtq"], 60); orphans = so.split() if rc == 0 else []
        if dry: return len(orphans)
        return _run(["pacman", "-Rns", "--noconfirm", *orphans], 1800, on_line) if orphans else (0, "")
    if pm == "zypper":
        _, so = _run(["zypper", "--non-interactive", "packages", "--unneeded"], 120)
        names = [c[2] for c in ([x.strip() for x in l.split("|")] for l in so.splitlines()) if len(c) > 2 and c[0].startswith("i")]
        if dry: return len(names)
        return _run(["zypper", "--non-interactive", "remove", *names], 1800, on_line) if names else (0, "")
    return 0 if dry else (127, "")

def label():
    """For the UI: what to call this server's package manager."""
    return {"apt": "apt", "dnf": "dnf", "pacman": "pacman", "zypper": "zypper", "winget": "winget"}.get(which(), "your package manager")


# ── winget (Windows) ─────────────────────────────────────────────────────────────────
WG = ["--accept-source-agreements", "--disable-interactivity"]

def _wrun(cmd, timeout, on_line):
    """Like _run, for winget: found where it really is (not on SYSTEM's PATH), UTF-8, its progress bars dropped."""
    import winhost
    if cmd[0] == "winget": cmd = [winhost.winget() or "winget", *cmd[1:]]
    try:
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                             creationflags=0x08000000, cwd=os.environ.get("SystemRoot") or None)
    except FileNotFoundError: return 127, f"{cmd[0]} isn't installed"
    out, buf = [], b""
    try:
        import time; end = time.time() + timeout
        while True:
            ch = p.stdout.read(1)
            if not ch: break
            if ch in (b"\r", b"\n"):
                line = buf.decode("utf-8", "replace").strip(); buf = b""
                # winget redraws a progress bar and a spinner on one line: keep only what reads like a sentence or a table row
                # (a spinner frame is one of - \ | / alone; a bar is drawn with blocks — a table's ----- separator line stays)
                if line and not re.fullmatch(r"[-\\|/]", line) and not re.search(r"[█▒░▓]", line):
                    out.append(line)
                    if on_line and ch == b"\n": on_line(line)
            else: buf += ch
            if time.time() > end: p.kill(); return 124, "it took too long"
        if buf.strip(): out.append(buf.decode("utf-8", "replace").strip())
        return p.wait(timeout=30), "\n".join(out)
    except subprocess.TimeoutExpired: p.kill(); return 124, "it took too long"

def _wg_each(action, ids, timeout, on_line):
    """install / uninstall / upgrade, one winget id at a time (winget takes one per call). For everyone on the
    machine where the program allows it, else the way it allows."""
    log = []
    for i in ids:
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9+._-]{0,100}", str(i)): return 2, f"bad program id: {i}"
        base = ["winget", action, "--id", i, "--exact", "--source", "winget", "--silent", *WG]
        if action != "uninstall": base += ["--accept-package-agreements"]
        rc, out = _run(base + (["--scope", "machine"] if action == "install" else []), timeout, on_line)
        if rc != 0 and action == "install" and re.search(r"scope|No applicable installer", out, re.I):
            rc, out = _run(base, timeout, on_line)                       # (some only install one way)
        log.append(out)
        if rc != 0 and not (action == "upgrade" and re.search(r"No available upgrade|No applicable upgrade|already installed", out, re.I)):
            return rc, "\n".join(log)
    _wg_installed.cache = None
    return 0, "\n".join(log)

def _wg_installed():
    """Ids of what's installed (from winget's JSON export: the same whatever language Windows speaks). Cached a minute."""
    import time
    c = getattr(_wg_installed, "cache", None)
    if c and time.time() - c[0] < 60: return c[1]
    fd, tmp = tempfile.mkstemp(suffix=".json"); os.close(fd)
    try:
        _run(["winget", "export", "-o", tmp, "--source", "winget", *WG], 180)
        ids = {p.get("PackageIdentifier") for s in json.load(open(tmp, encoding="utf-8-sig")).get("Sources", []) for p in s.get("Packages", [])}
    except (OSError, ValueError): ids = set()
    finally:
        try: os.remove(tmp)
        except OSError: pass
    _wg_installed.cache = (time.time(), ids - {None})
    return _wg_installed.cache[1]

def _wg_table(text):
    """A winget table (search, upgrade) → rows of cells, by the columns its header lines up (Name · Id · Version · …)."""
    lines = text.splitlines()
    for i in range(len(lines) - 1):
        if re.fullmatch(r"-{20,}", lines[i + 1].strip()) and len(lines[i].split()) >= 3:
            head = lines[i]; break
    else: return []
    starts = [m.start() for m in re.finditer(r"(?:^|(?<=\s))\S", head)]
    rows = []
    for l in lines[i + 2:]:
        if not l.strip() or re.fullmatch(r"\d+ .*", l.strip()) and len(l.split()) < 8 and not re.search(r"\S+\.\S+", l): continue
        cells = [l[a:b].strip() if b else l[a:].strip() for a, b in zip(starts, starts[1:] + [None])]
        if len(cells) >= 2 and cells[1]: rows.append(cells)
    return rows
