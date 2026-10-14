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
"""
import os, re, shutil, subprocess

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
    "zypper": {"gddrescue": "gddrescue", "gdisk": "gptfdisk", "dosfstools": "dosfstools", "ntfs-3g": "ntfs-3g", "docker.io": "docker", "docker-compose-v2": "docker-compose", "docker-compose": "docker-compose", "nfs-common": "nfs-client",
               "lm-sensors": "sensors", "zfsutils-linux": None, "python3-cryptography": "python3-cryptography"},
}


def which():
    for pm in ("apt-get", "dnf", "pacman", "zypper"):
        if shutil.which(pm): return "apt" if pm == "apt-get" else pm
    return ""

def name(pkg):
    """Debian's package name in this distro's words (None if it has no such package)."""
    pm = which()
    if pm in ("apt", ""): return pkg
    return NAMES.get(pm, {}).get(pkg, pkg)

def _run(cmd, timeout=900, on_line=None):
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
    if missing: raise ValueError(f"not packaged for this Linux: {', '.join(missing)}")
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
    return 127, "no package manager Nova knows (apt, dnf, pacman or zypper)"

def remove(pkgs, on_line=None, timeout=1800):
    pk = _names(pkgs); pm = which()
    return {"apt": lambda: _run(["apt-get", "remove", "-y", "--", *pk], timeout, on_line),
            "dnf": lambda: _run(["dnf", "remove", "-y", *pk], timeout, on_line),
            "pacman": lambda: _run(["pacman", "-R", "--noconfirm", *pk], timeout, on_line),
            "zypper": lambda: _run(["zypper", "--non-interactive", "remove", *pk], timeout, on_line)}.get(pm, lambda: (127, "no package manager"))()

def refresh():
    pm = which()
    cmd = {"apt": ["apt-get", "update", "-qq"], "dnf": ["dnf", "makecache", "-q"], "pacman": ["pacman", "-Sy", "--noconfirm"],
           "zypper": ["zypper", "--non-interactive", "refresh"]}.get(pm)
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
    return 127, "no package manager"

def clean():
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
    return {"apt": "apt", "dnf": "dnf", "pacman": "pacman", "zypper": "zypper"}.get(which(), "your package manager")
