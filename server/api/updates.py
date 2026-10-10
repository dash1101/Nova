"""
Nova update center: what can be updated on this server, and doing it.

  check()              apt packages that can be upgraded (security ones flagged), containers whose image
                       has a newer version in its registry (compares digests: nothing is downloaded), and
                       Nova itself. Saved to /var/lib/nova-api/updates.json for the app.
  apt_upgrade(spec)    upgrade the chosen packages (or all of them)
  containers(spec)     pull the newest image and recreate the chosen containers (Compose)
Run as background tasks (tasks.py), from the root helper.
"""
import json, os, re, subprocess, time

OUT = "/var/lib/nova-api/updates.json"
ENV = {**os.environ, "DEBIAN_FRONTEND": "noninteractive", "LC_ALL": "C"}


def run(cmd, timeout=600, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=ENV, **kw)
    return r.returncode, r.stdout, r.stderr

def load_json(p):
    try: return json.load(open(p))
    except Exception: return {}

def save(res):
    tmp = OUT + ".tmp"
    with open(tmp, "w") as f: json.dump(res, f)
    os.chmod(tmp, 0o644)
    try:
        import pwd; u = pwd.getpwnam("nova-api"); os.chown(tmp, u.pw_uid, u.pw_gid)
    except (KeyError, OSError): pass
    os.replace(tmp, OUT)

def apt_list():
    rc, so, _ = run(["apt", "list", "--upgradable"], timeout=120)
    out = []
    for l in so.splitlines():
        m = re.match(r"([^/\s]+)/(\S+)\s+(\S+)\s+\S+\s+\[upgradable from: ([^\]]+)\]", l)
        if m: out.append({"name": m.group(1), "origin": m.group(2), "to": m.group(3), "from": m.group(4), "security": "security" in m.group(2),
                          "restarts": "docker" if m.group(1) in ("docker-ce", "containerd.io", "docker.io", "containerd", "runc") else
                                      "server" if re.match(r"linux-image|systemd$|libc6$", m.group(1)) else ""})
    return out

def compose_containers():
    rc, ids, _ = run(["docker", "ps", "-q"], timeout=30)
    if rc != 0 or not ids.split(): return []
    rc, js, _ = run(["docker", "inspect"] + ids.split(), timeout=60)
    out = []
    for c in json.loads(js or "[]"):
        lab = c["Config"].get("Labels") or {}
        out.append({"name": c["Name"].lstrip("/"), "image": c["Config"]["Image"], "image_id": c["Image"],
                    "service": lab.get("com.docker.compose.service", ""), "dir": lab.get("com.docker.compose.project.working_dir", "")})
    return out

def remote_digest(ref):
    rc, so, _ = run(["docker", "buildx", "imagetools", "inspect", ref], timeout=60)
    m = re.search(r"^Digest:\s+(sha256:[0-9a-f]{64})", so, re.M)
    return m.group(1) if m else None

def local_digests(image_id):
    rc, so, _ = run(["docker", "image", "inspect", image_id, "--format", "{{json .RepoDigests}}"], timeout=30)
    try: return [d.split("@", 1)[1] for d in json.loads(so or "[]")]
    except ValueError: return []

def check(spec=None, log=print, progress=lambda p, n="": None):
    res = {"t": time.time(), "apt": [], "containers": [], "nova": {}}
    log("Refreshing the package lists…"); progress(5)
    run(["apt-get", "update", "-qq"], timeout=600)
    res["apt"] = apt_list(); progress(30, f"{len(res['apt'])} packages")
    cs = compose_containers(); seen = {}
    for i, c in enumerate(cs):
        progress(30 + 60 * i / max(1, len(cs)), c["name"])
        if "@sha256:" in c["image"]: continue                        # pinned to a digest: nothing to update
        if c["image"] not in seen:
            log(f"Checking {c['image']}…"); seen[c["image"]] = remote_digest(c["image"])
        rd = seen[c["image"]]
        if rd is None: c["status"] = "unknown"                         # local build, or the registry didn't answer
        else: c["status"] = "current" if rd in local_digests(c["image_id"]) else "update"
        c["updatable"] = bool(c["service"] and c["dir"].startswith("/"))
        res["containers"].append({k: c[k] for k in ("name", "image", "status", "updatable")})
    progress(92, "Nova")
    rc, so, _ = run(["/usr/sbin/nova-update", "--check"], timeout=200)
    m = re.search(r"installed (\S+) · release (\S+) · (.+)", so)
    res["nova"] = {"installed": m.group(1), "available": m.group(2), "update": "available" in m.group(3)} if m else {"error": so.strip()[-200:]}
    save(res)
    n = len(res["apt"]) + sum(1 for c in res["containers"] if c["status"] == "update") + (1 if res["nova"].get("update") else 0)
    log(f"{n} update{'s' if n != 1 else ''} available"); progress(100)
    return {"count": n, "apt": len(res["apt"]), "containers": sum(1 for c in res["containers"] if c["status"] == "update"), "nova": res["nova"].get("update", False)}

def apt_upgrade(spec, log=print, progress=lambda p, n="": None):
    pk = spec.get("packages")
    if pk == "all" or pk is None:
        log("Upgrading every package…")
        cmd = ["apt-get", "upgrade", "-y", "-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold"]
    else:
        names = [p for p in pk if re.fullmatch(r"[a-z0-9][a-z0-9+.-]{0,100}", str(p))]
        if not names or len(names) != len(pk): raise ValueError("bad package name")
        log(f"Upgrading {', '.join(names[:6])}{'…' if len(names) > 6 else ''}")
        cmd = ["apt-get", "install", "--only-upgrade", "-y", "-o", "Dpkg::Options::=--force-confdef", "-o", "Dpkg::Options::=--force-confold", "--", *names]
    progress(10)
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=ENV)
    n = 0
    for line in p.stdout:
        line = line.strip()
        if line.startswith(("Unpacking", "Setting up")): n += 1; progress(min(95, 10 + n * 3), line[:80]); log(line[:120])
    if p.wait() != 0: raise RuntimeError("apt couldn't finish the upgrade — see the log, or run: sudo apt upgrade")
    try: check(log=lambda m: None)
    except Exception: pass
    progress(100); log("Done."); return {"upgraded": n}

def containers(spec, log=print, progress=lambda p, n="": None):
    want = spec.get("containers")
    if want == "outdated":                                              # the weekly automatic update (Labs): only what has a newer image
        log("Checking which containers have a newer version…")
        check(log=lambda m: None); progress(10)
        want = [c["name"] for c in load_json(OUT).get("containers", []) if c.get("status") == "update" and c.get("updatable")]
        if not want: log("Everything is up to date."); progress(100); return {"updated": [], "rolled_back": []}
    cs = {c["name"]: c for c in compose_containers()}
    names = list(cs) if want == "all" else [n for n in (want or []) if n in cs]
    rolled = []
    if want != "all" and (not names or len(names) != len(want or [])): raise ValueError("no such container")
    done = []
    for i, n in enumerate(names):
        c = cs[n]
        if not (c["service"] and c["dir"].startswith("/") and os.path.isdir(c["dir"])): log(f"{n}: not from a Compose file — skipped"); continue
        progress(100 * i / len(names), n)
        base = f"nova-rollback/{re.sub(r'[^a-z0-9_.-]', '-', n.lower())}"
        keep = f"{base}:candidate"                                      # the running image, held while we find out whether the new one works
        run(["docker", "tag", c["image_id"], keep], timeout=30)
        log(f"Pulling the newest {c['image']}…")
        rc, so, se = run(["docker", "compose", "pull", c["service"]], timeout=1800, cwd=c["dir"])
        if rc != 0: log(f"{n}: pull failed: {se.strip()[-160:]}"); run(["docker", "rmi", keep], timeout=30); continue
        log(f"Restarting {n} on the new image…")
        rc, so, se = run(["docker", "compose", "up", "-d", c["service"]], timeout=600, cwd=c["dir"])
        rc2, newid, _ = run(["docker", "inspect", n, "--format", "{{.Image}}"], timeout=30)
        if newid.strip() == c["image_id"]: log(f"{n} was already up to date"); run(["docker", "rmi", keep], timeout=30); done.append(n); continue
        ok = rc == 0 and settled(n, log)
        if not ok:
            # the new version doesn't start: put the old one back
            log(f"{n} doesn't run on the new image — going back to the previous one")
            run(["docker", "tag", keep, c["image"]], timeout=30)
            run(["docker", "compose", "up", "-d", "--pull", "never", c["service"]], timeout=600, cwd=c["dir"])
            run(["docker", "rmi", keep], timeout=30)
            rolled.append(n); notify_warn(f"{n}: update rolled back", "The new version wouldn't start, so Nova put the previous one back. Check its release notes for changes it needs.")
            continue
        # keep the version it replaced, so Troubleshoot → "Go back to the previous version" works later
        run(["docker", "tag", keep, f"{base}:previous"], timeout=30); run(["docker", "rmi", keep], timeout=30)
        done.append(n)
    try: check(log=lambda m: None)
    except Exception: pass
    progress(100); log(f"Updated {len(done)} of {len(names)}." + (f" Rolled back: {', '.join(rolled)}." if rolled else ""))
    if names and not done: raise RuntimeError("nothing could be updated" + (f" — {', '.join(rolled)} wouldn't start on the new version, so the previous one was put back" if rolled else " — see the log"))
    return {"updated": done, "rolled_back": rolled}

def settled(name, log, wait=40):
    """True once the container is running (and healthy, if it has a health check) and stays up; False if it keeps crashing."""
    end = time.time() + wait; up_since = None
    while time.time() < end:
        rc, so, _ = run(["docker", "inspect", name, "--format", "{{.State.Status}} {{.RestartCount}} {{if .State.Health}}{{.State.Health.Status}}{{end}}"], timeout=20)
        st = (so.split() + ["", "", ""])[:3]
        if st[0] in ("restarting", "exited", "dead") or (st[1].isdigit() and int(st[1]) > 0): return False
        if st[0] == "running" and st[2] in ("", "healthy"):
            up_since = up_since or time.time()
            if time.time() - up_since > 12: return True
        time.sleep(2)
    return st[0] == "running"

def notify_warn(title, detail):
    t = next((p for p in ("/usr/sbin/nova-alert", "/usr/local/bin/nova-alert") if os.path.exists(p)), None)
    if t: subprocess.run([t, "warning", title, detail], capture_output=True, timeout=30)
