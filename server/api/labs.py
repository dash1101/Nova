#!/usr/bin/python3 -I
"""
Nova Labs: experimental features that run by themselves once you turn them on in Settings → Labs.

  labs.py tick      (nova-labs.timer, every minute, as root)
    crash_guard     a container that keeps crashing is stopped (and you're told) instead of
                    restarting forever: 5 restarts within 10 minutes
    auto_updates    once a week, update the containers that have a newer image; one that won't
                    start on its new version is put back on the old one (updates.containers)
  labs.py images    what an image cleanup would remove (JSON)
  prune(...)        remove them (a background task: tasks.py "images-prune")

Wake-on-LAN lives in the API itself: it's one UDP packet and needs no root.
"""
import json, os, re, shutil, subprocess, sys, time

HERE = os.path.dirname(os.path.realpath(__file__))
sys.path.insert(0, HERE)
DATA = "/var/lib/nova-api"
SETTINGS = f"{DATA}/settings.json"
STATE = f"{DATA}/labs-state.json"
HELD = f"{DATA}/force-stopped.json"
GUARD_RESTARTS, GUARD_WINDOW = 5, 600
KEEP_PREFIX = "nova-rollback/"          # the versions Nova keeps for "Go back to the previous version"
DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def load(p, default):
    try: return json.load(open(p))
    except Exception: return default

def save(p, d):
    tmp = p + ".tmp"
    with open(tmp, "w") as f: json.dump(d, f)
    os.replace(tmp, p)

def run(cmd, timeout=60):
    r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout, r.stderr

def on(k): return bool(load(SETTINGS, {}).get("labs", {}).get(k))

def schedule():
    s = load(SETTINGS, {}).get("labs_cfg", {}).get("auto_updates", {})
    day = s.get("day", 6); hour = s.get("hour", 4)
    return {"day": day if isinstance(day, int) and 0 <= day <= 6 else 6, "hour": hour if isinstance(hour, int) and 0 <= hour <= 23 else 4}

def notify(level, title, detail=""):
    t = next((p for p in ("/usr/sbin/nova-alert", "/usr/local/bin/nova-alert") if os.path.exists(p)), None)
    if t:
        try: subprocess.run([t, level, title, detail], capture_output=True, timeout=30)
        except Exception: pass

def changelog(msg):
    t = next((p for p in ("/usr/local/bin/nova-log", "/usr/bin/nova-log") if os.path.exists(p)), None)
    try:
        if t: subprocess.run([t, "app", msg], capture_output=True, timeout=30)
    except Exception: pass


# ── crash-loop guard ────────────────────────────────────────────────────────────────
def guard(st):
    rc, ids, _ = run(["docker", "ps", "-aq"])
    if rc != 0 or not ids.split(): return
    rc, so, _ = run(["docker", "inspect", *ids.split()])
    try: ctrs = json.loads(so)
    except Exception: return
    seen = st.setdefault("restarts", {}); t = time.time()
    for c in ctrs:
        name = c["Name"].lstrip("/"); pol = (c["HostConfig"].get("RestartPolicy") or {}).get("Name") or "no"
        if pol == "no": seen.pop(name, None); continue
        n = int(c.get("RestartCount") or 0); hist = [h for h in seen.get(name, []) if t - h[0] < GUARD_WINDOW]
        if hist and n < hist[-1][1]: hist = []                    # recreated: the count started over
        hist.append([t, n]); seen[name] = hist
        crashing = c["State"].get("Restarting") or c["State"].get("Status") in ("restarting", "exited", "dead") or (len(hist) > 1 and n > hist[-2][1])
        if n - hist[0][1] >= GUARD_RESTARTS and crashing:
            held = load(HELD, {}); held[name] = pol; save(HELD, held)
            run(["docker", "update", "--restart", "no", name], timeout=30)
            run(["docker", "stop", "-t", "2", name], timeout=60)
            seen.pop(name, None)
            changelog(f"Labs crash-loop guard stopped {name} after {n - hist[0][1]} restarts in {GUARD_WINDOW // 60} minutes")
            notify("warning", f"{name} kept crashing, so Nova stopped it",
                   f"It restarted {n - hist[0][1]} times in {GUARD_WINDOW // 60} minutes. Open it in Containers → Troubleshoot to see why; starting it again turns its automatic restarts back on.")
    for name in [k for k in seen if k not in {c["Name"].lstrip("/") for c in ctrs}]: seen.pop(name)


# ── automatic container updates ─────────────────────────────────────────────────────
def auto_update(st):
    s = schedule(); lt = time.localtime()
    if lt.tm_wday != s["day"] or lt.tm_hour != s["hour"]: return
    if time.time() - st.get("auto_updates_last", 0) < 20 * 3600: return
    import tasks
    st["auto_updates_last"] = time.time()
    try: tasks.start("containers-update", {"containers": "outdated", "scheduled": True})
    except ValueError as e: st["auto_updates_error"] = str(e)       # something else is updating: next week


# ── old images ──────────────────────────────────────────────────────────────────────
def images():
    """Images no container uses (running or stopped), leaving out the versions Nova keeps to roll back to."""
    rc, so, se = run(["docker", "image", "ls", "-a", "--no-trunc", "--format", "{{json .}}"], timeout=60)
    if rc != 0: raise ValueError("couldn't list images: " + se.strip()[-200:])
    rc, ids, _ = run(["docker", "ps", "-aq", "--no-trunc"])
    used = set()
    if ids.split():
        rc, ins, _ = run(["docker", "inspect", "--format", "{{.Image}}", *ids.split()], timeout=60)
        used = {l.strip() for l in ins.splitlines() if l.strip()}
    by = {}
    for line in so.splitlines():
        try: i = json.loads(line)
        except Exception: continue
        e = by.setdefault(i["ID"], {"id": i["ID"], "tags": [], "size": i.get("Size", ""), "created": i.get("CreatedSince", "")})
        if i.get("Repository") not in (None, "", "<none>"): e["tags"].append(f"{i['Repository']}:{i.get('Tag', '')}")
    rollback = set()                      # the version each container could go back to stays too
    if ids.split():
        rc, ins, _ = run(["docker", "inspect", *ids.split()], timeout=60)
        try: rollback = {p for p in (previous_image(c) for c in json.loads(ins)) if p}
        except Exception: pass
    nameless = [e["id"] for e in by.values() if not e["tags"]]
    if nameless:                          # untagged: say what it was an old version of, from its labels
        rc, so2, _ = run(["docker", "image", "inspect", *nameless], timeout=60)
        try:
            for i in json.loads(so2):
                l = (i.get("Config") or {}).get("Labels") or {}
                what = l.get("org.opencontainers.image.title") or (l.get("org.opencontainers.image.source") or l.get("org.opencontainers.image.url") or "").rstrip("/").split("/")[-1]
                if what: by[i["Id"]]["what"] = what
        except Exception: pass
    out = []
    for e in by.values():
        if e["id"] in used or e["id"] in rollback or any(t.startswith(KEEP_PREFIX) for t in e["tags"]): continue
        out.append(e)
    return {"images": out, "kept": sum(1 for e in by.values() if e["id"] not in used and (e["id"] in rollback or any(t.startswith(KEEP_PREFIX) for t in e["tags"]))),
            "free": shutil.disk_usage(docker_root()).free}

def _labels_key(info):
    l = (info.get("Config") or {}).get("Labels") or {}
    return l.get("org.opencontainers.image.source") or l.get("org.opencontainers.image.title") or l.get("org.opencontainers.image.url") or ""

def previous_image(c):
    """The image a container ran before its last update: Nova's rollback tag, or else the newest untagged
    older build of the same image (an update leaves the old one behind, nameless). None if there isn't one."""
    name = c["Name"].lstrip("/")
    rc, so, _ = run(["docker", "image", "inspect", f"nova-rollback/{re.sub(r'[^a-z0-9_.-]', '-', name.lower())}:previous", "--format", "{{.Id}}"], timeout=30)
    if rc == 0 and so.strip() and so.strip() != c["Image"]: return so.strip()
    rc, so, _ = run(["docker", "image", "inspect", c["Image"]], timeout=30)
    try: cur = json.loads(so)[0]
    except Exception: return None
    key = _labels_key(cur)
    if not key: return None
    rc, ids, _ = run(["docker", "image", "ls", "-a", "-q", "--no-trunc", "--filter", "dangling=true"], timeout=30)
    if rc != 0 or not ids.split(): return None
    rc, so, _ = run(["docker", "image", "inspect", *sorted(set(ids.split()))], timeout=60)
    try: cands = [i for i in json.loads(so) if _labels_key(i) == key and i.get("Created", "") < cur.get("Created", "") and i["Id"] != c["Image"]]
    except Exception: return None
    return max(cands, key=lambda i: i.get("Created", ""))["Id"] if cands else None

def docker_root():
    rc, so, _ = run(["docker", "info", "--format", "{{.DockerRootDir}}"], timeout=30)
    return so.strip() if rc == 0 and so.strip().startswith("/") else "/var/lib/docker"

def prune(spec, log=print, progress=lambda p, n="": None):
    lst = images()["images"]; root = docker_root(); before = shutil.disk_usage(root).free
    if not lst: log("Nothing to clean up."); progress(100); return {"removed": 0, "freed": 0}
    gone = 0
    for i, e in enumerate(lst):
        progress(100 * i / len(lst), (e["tags"] or [e["id"][7:19]])[0])
        # by tag first (an image can have several), then by id; never forced, so an image that just got used stays
        for ref in e["tags"] or [e["id"]]:
            rc, _, se = run(["docker", "rmi", ref], timeout=300)
            if rc != 0 and "No such image" not in se: log(f"kept {ref}: {se.strip()[-120:]}")
        if run(["docker", "image", "inspect", e["id"]], timeout=30)[0] != 0: gone += 1
    run(["docker", "image", "prune", "-f"], timeout=300)                       # leftover untagged layers
    freed = max(0, shutil.disk_usage(root).free - before)
    log(f"Removed {gone} image{'s' if gone != 1 else ''} and freed {freed / 1e9:.1f} GB."); progress(100)
    changelog(f"Labs: cleaned up {gone} unused container images ({freed / 1e9:.1f} GB) (from the Nova app)")
    return {"removed": gone, "freed": freed}


def tick():
    st = load(STATE, {})
    if on("crash_guard"):
        try: guard(st)
        except Exception as e: st["guard_error"] = str(e)[:200]
    else: st.pop("restarts", None)
    if on("auto_updates"):
        try: auto_update(st)
        except Exception as e: st["auto_updates_error"] = str(e)[:200]
    save(STATE, st)


if __name__ == "__main__":
    if os.geteuid() != 0: print("run as root", file=sys.stderr); sys.exit(1)
    cmd = sys.argv[1:] or ["tick"]
    if cmd[0] == "tick": tick()
    elif cmd[0] == "images": print(json.dumps(images()))
    else: print("usage: labs.py tick | images", file=sys.stderr); sys.exit(2)
