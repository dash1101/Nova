"""
Installing things, as background tasks with real progress (tasks.py runs these):

  store-install / store-uninstall      an app from Nova's store (a Compose stack in /opt/<id>)
  custom-install / custom-uninstall    your own container, from a checked form (custom_spec)
  program-install / program-remove     any Debian/Ubuntu package (apt), with apt's own progress

Image downloads report progress per layer; apt reports its own percentage. Everything that's
changed is written to the server's change log and announced in the Inbox.
"""
import glob, json, os, re, secrets, shutil, socket, string, subprocess, time

HERE = os.path.dirname(os.path.realpath(__file__))
STORE = os.path.join(HERE, "store")
try: SITE = json.load(open("/etc/nova-api/config.json"))
except Exception: SITE = {}
ENV = {**os.environ, "DEBIAN_FRONTEND": "noninteractive", "LC_ALL": "C"}
NOVA_PORTS = {8095, 8495, 8496}
PKG_RE = r"[a-z0-9][a-z0-9+.-]{0,100}"
# Removing these would take the server (or Nova) down with it.
ESSENTIAL = {"nova-server", "systemd", "systemd-sysv", "openssh-server", "sudo", "apt", "dpkg", "bash", "coreutils", "libc6", "python3",
             "python3-cryptography", "docker-ce", "docker.io", "containerd.io", "linux-image-amd64", "grub-pc", "grub-efi-amd64", "network-manager",
             "ifupdown", "cloudflared", "tailscale", "login", "passwd", "util-linux", "mount", "init", "base-files", "base-passwd"}


def run(cmd, timeout=600, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=ENV, **kw)
    return r.returncode, r.stdout, r.stderr

def port_free(port):
    with socket.socket() as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try: s.bind(("0.0.0.0", port)); return True
        except OSError: return False

def catalog():
    items = {}
    for p in sorted(glob.glob(f"{STORE}/docker/*.json")):
        t = json.load(open(p)); items[t["id"]] = t
    return items

def _tool(*paths): return next((p for p in paths if os.path.exists(p)), None)
def notify(level, title, detail=""):
    t = _tool("/usr/sbin/nova-alert", "/usr/local/bin/nova-alert")
    if t: subprocess.run([t, level, title, detail], capture_output=True, timeout=30)
def changelog(msg):
    t = _tool("/usr/local/bin/nova-log", "/usr/bin/nova-log")
    try:
        if t: subprocess.run([t, "app", msg], capture_output=True, timeout=30)
    except Exception: pass


# ── pulling images with progress ─────────────────────────────────────────────────────────
def pull(cwd, log, progress, lo=5, hi=80):
    """docker compose pull, reporting layers as they finish."""
    rc, so, _ = run(["docker", "compose", "config", "--images"], timeout=60, cwd=cwd)
    images = [i for i in so.split() if i] or []
    for n, img in enumerate(images):
        log(f"Downloading {img}…")
        p = subprocess.Popen(["docker", "pull", img], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=ENV)
        layers, done = set(), set()
        base, span = lo + (hi - lo) * n / max(1, len(images)), (hi - lo) / max(1, len(images))
        for line in p.stdout:
            m = re.match(r"([0-9a-f]{12}): (.+)", line.strip())
            if m:
                layers.add(m.group(1))
                if m.group(2) in ("Pull complete", "Already exists"): done.add(m.group(1))
                elif m.group(2) == "Download complete": progress(base + span * (len(done) + 0.6) / max(1, len(layers)), f"{len(done)}/{len(layers)} layers")
                progress(base + span * len(done) / max(1, len(layers)), f"{img.split('/')[-1]} · {len(done)}/{len(layers)} layers")
        if p.wait() != 0: raise RuntimeError(f"couldn't download {img} — check the name and your internet connection")
    progress(hi)


# ── the store ────────────────────────────────────────────────────────────────────────────
def store_install(spec, log, progress):
    t = catalog().get(str(spec.get("id", ""))) or (_ for _ in ()).throw(ValueError("not in the store"))
    d = f"/opt/{t['id']}"
    if os.path.exists(f"{d}/.nova-store"): raise ValueError("already installed")
    if os.path.exists(f"{d}/docker-compose.yml"): raise ValueError(f"{d} already has a compose file that isn't from the store")
    for p in [t["port"]] + t.get("extra_ports", []):
        if not port_free(p): raise ValueError(f"port {p} is already in use")
    log(f"Setting up {d}"); progress(3)
    os.makedirs(f"{d}/data", exist_ok=True)
    alphabet = string.ascii_letters + string.digits
    subs = {"DATA": f"{d}/data", "PORT": str(t["port"])}
    for i in range(1, 4): subs[f"SECRET{i}"] = "".join(secrets.choice(alphabet) for _ in range(32))
    compose = t["compose"]
    for k, v in subs.items(): compose = compose.replace("{" + k + "}", v)
    with open(f"{d}/docker-compose.yml", "w") as f: f.write(compose)
    if "SECRET1" in t["compose"]:
        with open(f"{d}/.nova-secrets", "w") as f:
            f.write("".join(f"{k}={subs[k]}\n" for k in ("SECRET1", "SECRET2", "SECRET3") if "{" + k + "}" in t["compose"]))
        os.chmod(f"{d}/.nova-secrets", 0o600)
    try:
        pull(d, log, progress)
        log("Starting…"); progress(85)
        rc, so, se = run(["docker", "compose", "up", "-d"], cwd=d, timeout=900)
        if rc != 0: raise RuntimeError("couldn't start it: " + se.strip()[-300:])
    except Exception:
        run(["docker", "compose", "down"], cwd=d, timeout=300)
        try: os.remove(f"{d}/docker-compose.yml")
        except OSError: pass
        raise
    with open(f"{d}/.nova-store", "w") as f: json.dump({"id": t["id"], "installed": time.strftime("%F %T")}, f)
    changelog(f"Installed {t['name']} from the app store → {d} (port {t['port']})")
    host = SITE.get("lan_host") or "localhost"
    url = f"http://{host}:{t['port']}{t.get('path', '')}"
    notify("info", f"Installed {t['name']}", url)
    log("Done."); progress(100)
    return {"ok": True, "url": url, "id": t["id"]}

def store_uninstall(spec, log, progress):
    t = catalog().get(str(spec.get("id", ""))) or (_ for _ in ()).throw(ValueError("not in the store"))
    d = f"/opt/{t['id']}"
    if not os.path.exists(f"{d}/.nova-store"): raise ValueError("not installed from the store")
    log(f"Stopping {t['name']}…"); progress(20)
    rc, so, se = run(["docker", "compose", "down"], cwd=d, timeout=600)
    if rc != 0: raise RuntimeError("couldn't stop it: " + se.strip()[-200:])
    keep = f"/opt/.nova-uninstalled/{t['id']}-{time.strftime('%Y%m%d-%H%M%S')}"     # never destructive: the data is kept
    os.makedirs(os.path.dirname(keep), exist_ok=True); shutil.move(d, keep)
    changelog(f"Uninstalled {t['name']} (data kept in {keep})"); progress(100)
    return {"ok": True, "kept": keep}


# ── your own containers: a form, not free-form compose, so risky options can't be expressed ──
CUSTOM_NAME = r"[a-z0-9][a-z0-9_-]{0,39}"
IMAGE_RE = r"[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[0-9]{1,5})?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127})?(?:@sha256:[0-9a-f]{64})?"
VOLUME_ROOTS = ("/mnt/", "/srv/", "/media/", "/home/")

def custom_spec(sp):
    """Check every field of a custom container and return (name, dir, compose, volumes)."""
    if not isinstance(sp, dict): raise ValueError("bad request")
    name = str(sp.get("name", "")).strip().lower()
    if not re.fullmatch(CUSTOM_NAME, name): raise ValueError("name: lowercase letters, digits, - and _ (up to 40)")
    image = str(sp.get("image", "")).strip()
    if not re.fullmatch(IMAGE_RE, image) or len(image) > 255: raise ValueError("image: like nginx:latest or ghcr.io/owner/app:1.2")
    d = f"/opt/{name}"
    ports, vols, env = [], [], {}
    for p in (sp.get("ports") or [])[:12]:
        try: h, c = int(p.get("host")), int(p.get("container"))
        except (TypeError, ValueError, AttributeError): raise ValueError("ports: numbers, like 8080 → 80")
        proto = p.get("proto", "tcp")
        if proto not in ("tcp", "udp"): raise ValueError("ports: tcp or udp")
        if not (1 <= h <= 65535 and 1 <= c <= 65535): raise ValueError("ports: 1–65535")
        if h in NOVA_PORTS: raise ValueError(f"port {h} is Nova's own")
        if proto == "tcp" and not port_free(h): raise ValueError(f"port {h} is already in use")
        ports.append(f"{h}:{c}" + ("/udp" if proto == "udp" else ""))
    for v in (sp.get("volumes") or [])[:12]:
        host, cont = str((v or {}).get("host", "")).strip(), str((v or {}).get("container", "")).strip()
        if not host or not cont.startswith("/") or ".." in host.split("/") or ".." in cont.split("/") or any(ch in host + cont for ch in ":\n\r\"'"):
            raise ValueError("folders: a folder on the server and a path inside the container, like /mnt/media → /media")
        if not host.startswith("/"): host = f"{d}/data/{host.lstrip('./')}"
        real = os.path.realpath(host) if os.path.exists(host) else os.path.normpath(host)
        if not (real.startswith(f"{d}/data") or real.startswith(VOLUME_ROOTS)) or real.rstrip("/") in [r.rstrip("/") for r in VOLUME_ROOTS]:
            raise ValueError(f"{host}: share a folder inside {', '.join(VOLUME_ROOTS)} or the app's own data folder")
        vols.append((real, cont, bool((v or {}).get("ro"))))
    for k, val in list((sp.get("env") or {}).items())[:40]:
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,63}", str(k)) or len(str(val)) > 2000 or "\n" in str(val):
            raise ValueError(f"setting {str(k)[:20]}: letters, digits and _ ; one line")
        env[str(k)] = str(val)
    restart = sp.get("restart", "unless-stopped")
    if restart not in ("no", "always", "unless-stopped", "on-failure"): raise ValueError("restart: no, always, unless-stopped or on-failure")
    q = json.dumps                                       # JSON strings are valid YAML strings: no injection
    lines = ["services:", f"  {name}:", f"    image: {q(image)}", f"    container_name: {q(name)}", f"    restart: {q(restart)}",
             "    security_opt:", "      - \"no-new-privileges:true\"", "    labels:", "      nova.custom: \"true\""]
    if ports: lines += ["    ports:"] + [f"      - {q(p)}" for p in ports]
    if vols: lines += ["    volumes:"] + [f"      - {q(h + ':' + c + (':ro' if ro else ''))}" for h, c, ro in vols]
    if env: lines += ["    environment:"] + [f"      {k}: {q(v)}" for k, v in env.items()]
    return name, d, "\n".join(lines) + "\n", vols

def custom_install(spec, log, progress):
    name, d, compose, vols = custom_spec(spec)
    if os.path.exists(d): raise ValueError(f"{d} already exists — pick another name")
    os.makedirs(f"{d}/data", exist_ok=True)
    for h, _, _ in vols:
        if h.startswith(f"{d}/data"): os.makedirs(h, exist_ok=True)
    with open(f"{d}/docker-compose.yml", "w") as f: f.write(compose)
    try:
        pull(d, log, progress)
        log("Starting…"); progress(88)
        rc, so, se = run(["docker", "compose", "up", "-d"], cwd=d, timeout=900)
        if rc != 0: raise RuntimeError("couldn't start it: " + se.strip()[-300:])
    except Exception:
        run(["docker", "compose", "down"], cwd=d, timeout=300); shutil.rmtree(d, ignore_errors=True); raise
    with open(f"{d}/.nova-custom", "w") as f: json.dump({"name": name, "installed": time.strftime("%F %T")}, f)
    changelog(f"Added custom container {name} ({spec.get('image')}) → {d} (from the Nova app)")
    notify("info", f"Added container {name}", "Your own container, set up from Nova")
    progress(100); return {"ok": True, "name": name}

def custom_uninstall(spec, log, progress):
    name = str(spec.get("name", ""))
    if not re.fullmatch(CUSTOM_NAME, name): raise ValueError("bad name")
    d = f"/opt/{name}"
    if not os.path.exists(f"{d}/.nova-custom"): raise ValueError("that container wasn't added in Nova")
    log(f"Stopping {name}…"); progress(20)
    rc, so, se = run(["docker", "compose", "down"], cwd=d, timeout=600)
    if rc != 0: raise RuntimeError("couldn't stop it: " + se.strip()[-200:])
    keep = f"/opt/.nova-uninstalled/{name}-{time.strftime('%Y%m%d-%H%M%S')}"
    os.makedirs(os.path.dirname(keep), exist_ok=True); shutil.move(d, keep)
    changelog(f"Removed custom container {name} (data kept in {keep})"); progress(100)
    return {"ok": True, "kept": keep}


# ── programs (apt) ───────────────────────────────────────────────────────────────────────
def _apt(cmd, log, progress, lo=10, hi=98):
    """Run apt-get with its machine-readable progress (APT::Status-Fd)."""
    r, w = os.pipe()
    p = subprocess.Popen(["apt-get", "-y", "-o", f"APT::Status-Fd={w}", "-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold", *cmd],
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=ENV, pass_fds=(w,))
    os.close(w)
    import threading
    def status():
        with os.fdopen(r) as f:
            for line in f:
                m = re.match(r"(dlstatus|pmstatus):[^:]*:([\d.]+):(.*)", line.strip())
                if m:
                    pct = float(m.group(2)); dl = m.group(1) == "dlstatus"
                    progress(lo + (hi - lo) * (pct * 0.4 if dl else 40 + pct * 0.6) / 100, m.group(3)[:80])
    th = threading.Thread(target=status, daemon=True); th.start()
    tail = []
    for line in p.stdout:
        line = line.rstrip()
        if line: tail = (tail + [line])[-20:]
        if line.startswith(("Unpacking", "Setting up", "Removing", "Get:")): log(line[:140])
    rc = p.wait(); th.join(timeout=5)
    if rc != 0: raise RuntimeError("apt couldn't finish: " + " / ".join(tail[-3:])[-300:])

def program_install(spec, log, progress):
    pk = str(spec.get("pkg", ""))
    if not re.fullmatch(PKG_RE, pk): raise ValueError("bad package name")
    log("Refreshing the package lists…"); progress(3)
    run(["apt-get", "update", "-qq"], timeout=600)
    rc, so, _ = run(["apt-cache", "policy", pk], timeout=60)
    if "Candidate: (none)" in so or not so.strip(): raise ValueError(f"there's no package called {pk}")
    _apt(["install", "--", pk], log, progress)
    changelog(f"Installed program {pk} (from the Nova app)")
    progress(100); return {"ok": True, "pkg": pk}

def program_remove(spec, log, progress):
    pk = str(spec.get("pkg", ""))
    if not re.fullmatch(PKG_RE, pk): raise ValueError("bad package name")
    if pk in ESSENTIAL: raise ValueError(f"{pk} keeps the server running, so it can't be removed from Nova")
    rc, so, _ = run(["apt-get", "-s", "remove", "--", pk], timeout=120)              # what else would go with it?
    gone = re.findall(r"^Remv (\S+)", so, re.M)
    hit = sorted(set(gone) & ESSENTIAL)
    if hit: raise ValueError(f"removing {pk} would also remove {', '.join(hit)} — do that from a terminal if you really mean it")
    _apt(["remove", "--", pk], log, progress)
    changelog(f"Removed program {pk} (from the Nova app)")
    progress(100); return {"ok": True, "pkg": pk, "removed": gone}

def search_programs(q, limit=40):
    """apt-cache search, with whether each one is installed. (Read-only.)"""
    q = str(q).strip().lower()[:60]
    if len(q) < 2 or not re.fullmatch(r"[a-z0-9+.\- ]+", q): return []
    rc, so, _ = run(["apt-cache", "search", "--names-only", q], timeout=30)
    rc2, so2, _ = run(["apt-cache", "search", q], timeout=30)
    seen, out = set(), []
    for l in (so + so2).splitlines():
        if " - " not in l: continue
        name, desc = l.split(" - ", 1)
        if name in seen: continue
        seen.add(name); out.append({"pkg": name, "description": desc[:160]})
    out.sort(key=lambda p: (not p["pkg"].startswith(q), p["pkg"] != q, len(p["pkg"])))
    out = out[:limit]
    if out:
        rc, so, _ = run(["dpkg-query", "-W", "-f", "${Package} ${db:Status-Abbrev}\n", *[p["pkg"] for p in out]], timeout=30)
        inst = {l.split()[0] for l in so.splitlines() if len(l.split()) > 1 and l.split()[1].startswith("ii")}
        for p in out: p["installed"] = p["pkg"] in inst; p["essential"] = p["pkg"] in ESSENTIAL
    return out

# ── command apps: a saved command or script, run as your normal account ──────────────────
APPS = "/var/lib/nova-api/apps.json"

def run_command(spec, log, progress):
    """Run a command app (its command is looked up here, never taken from the request)."""
    import pwd
    aid = str(spec.get("id", ""))
    cfg = json.load(open(APPS)) if os.path.exists(APPS) else {}
    a = next((c for c in cfg.get("custom", []) if c.get("id") == aid and c.get("command")), None)
    if not a: raise ValueError("no such command app")
    user = SITE.get("shell_user", "")
    if not user:
        ok = set(l.strip() for l in open("/etc/shells") if l.startswith("/")) if os.path.exists("/etc/shells") else {"/bin/bash"}
        people = [u.pw_name for u in pwd.getpwall() if 1000 <= u.pw_uid < 60000 and u.pw_shell in ok]
        user = people[0] if len(people) == 1 else ""
    if not user: raise ValueError("set shell_user in /etc/nova-api/config.json first")
    log(f"$ {a['command'][:300]}"); progress(5, f"as {user}")
    p = subprocess.Popen(["/usr/sbin/runuser", "-l", user, "-c", a["command"]], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace",
                         env={"PATH": "/usr/local/bin:/usr/bin:/bin", "LANG": "C.UTF-8", "TERM": "dumb"})
    t0 = time.time(); limit = max(10, min(3600, int(a.get("timeout") or 600))); lines = 0
    for line in p.stdout:
        log(line.rstrip()[:400]); lines += 1
        progress(min(95, 5 + 90 * (time.time() - t0) / limit), f"{lines} line{'s' if lines != 1 else ''} of output")
        if time.time() - t0 > limit: p.kill(); raise RuntimeError(f"stopped after {limit} s (its time limit)")
    rc = p.wait()
    if rc != 0: raise RuntimeError(f"finished with exit code {rc}")
    progress(100, "Finished"); return {"ok": True, "exit": rc}

OPS = {"run-command": run_command, "store-install": store_install, "store-uninstall": store_uninstall, "custom-install": custom_install, "custom-uninstall": custom_uninstall,
       "program-install": program_install, "program-remove": program_remove}
TITLES = {"run-command": "Run", "store-install": "Install", "store-uninstall": "Uninstall", "custom-install": "Add container", "custom-uninstall": "Remove container",
          "program-install": "Install program", "program-remove": "Remove program"}
