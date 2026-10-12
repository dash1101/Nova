"""
Apps that need one setting changed before they work at a public address (run by the root helper).

Some apps refuse requests that come through a proxy until you tell them to expect it:
  * Home Assistant answers "400: Bad Request" unless its `http:` settings trust the proxy
    (use_x_forwarded_for + trusted_proxies).
  * Homepage answers "Host validation failed" unless the address is in HOMEPAGE_ALLOWED_HOSTS.

check(app_id, host) says whether the app needs a change and exactly what Nova would do. apply(app_id, host)
does it: a dated backup of the file first, the smallest possible edit, then the app restarts. When the file
doesn't look the way Nova expects, it changes nothing and says what to add yourself.
"""
import json, os, re, shutil, subprocess, time

HOST_RE = r"(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}"


def _inspect(cid):
    r = subprocess.run(["docker", "inspect", cid], capture_output=True, text=True, timeout=30)
    if r.returncode != 0: raise ValueError("no such container")
    return json.loads(r.stdout)[0]

def _kind(info):
    img = (info.get("Config") or {}).get("Image", "").lower()
    if "home-assistant" in img or "homeassistant" in img: return "homeassistant"
    if "gethomepage/homepage" in img: return "homepage"
    return None

def _compose(info):
    lb = (info.get("Config") or {}).get("Labels") or {}
    f = (lb.get("com.docker.compose.project.config_files") or "").split(",")[0]
    return f if f and os.path.isfile(f) else None


# ── Home Assistant ──────────────────────────────────────────────────────────────────────
def _ha_config(info):
    m = next((x for x in info.get("Mounts") or [] if x.get("Destination") == "/config"), None)
    f = os.path.join(m["Source"], "configuration.yaml") if m else ""
    return f if f and os.path.isfile(f) else None

def _ha_proxies(info):
    """Where the proxy's requests come from: this machine (host network), or the container's Docker network."""
    if (info.get("HostConfig") or {}).get("NetworkMode") == "host": return ["127.0.0.1", "::1"]
    subnets = []
    for name in ((info.get("NetworkSettings") or {}).get("Networks") or {}):
        r = subprocess.run(["docker", "network", "inspect", name, "--format", "{{range .IPAM.Config}}{{.Subnet}} {{end}}"], capture_output=True, text=True, timeout=20)
        subnets += [x for x in r.stdout.split() if x]
    return ["127.0.0.1", "::1"] + subnets

def _ha_check(info):
    f = _ha_config(info)
    if not f: return {"needed": False, "why": "couldn't find Home Assistant's configuration.yaml"}
    txt = open(f).read()
    ok = re.search(r"^\s+use_x_forwarded_for:\s*true", txt, re.M) and re.search(r"^\s+trusted_proxies:", txt, re.M)
    if ok: return {"needed": False}
    proxies = _ha_proxies(info)
    block = "http:\n  use_x_forwarded_for: true\n  trusted_proxies:\n" + "".join(f"    - {p}\n" for p in proxies)
    auto = not re.search(r"^http:", txt, re.M)          # an http: section already there → you add the two lines yourself
    return {"needed": True, "app": "Home Assistant", "file": f, "auto": auto,
            "what": "Home Assistant turns away requests that come through a proxy (that's the “400: Bad Request”) until it's told to trust it.",
            "will": [f"Back up {os.path.basename(f)}", "Add these lines to it:\n" + block.rstrip(), "Restart Home Assistant (about a minute)"] if auto else
                    [f"Your {os.path.basename(f)} already has an http: section, so Nova leaves it to you. Add to it:\n  use_x_forwarded_for: true\n  trusted_proxies:\n"
                     + "".join(f"    - {p}\n" for p in proxies).rstrip(), "Then restart Home Assistant"],
            "_block": block}

def _ha_apply(info, chk):
    f = chk["file"]
    shutil.copy2(f, f"{f}.nova-backup-{time.strftime('%Y%m%d-%H%M%S')}")
    with open(f, "a") as fh: fh.write(("" if open(f).read().endswith("\n") else "\n") + "\n# added by Nova so Home Assistant works behind your Cloudflare address\n" + chk["_block"])
    subprocess.run(["docker", "restart", info["Name"].lstrip("/")], capture_output=True, timeout=180)


# ── Homepage ────────────────────────────────────────────────────────────────────────────
def _hp_check(info, host):
    env = [e for e in (info.get("Config") or {}).get("Env") or [] if e.startswith("HOMEPAGE_ALLOWED_HOSTS=")]
    have = env[0].split("=", 1)[1].split(",") if env else []
    if host in have or "*" in have: return {"needed": False}
    f = _compose(info)
    txt = open(f).read() if f else ""
    lines = re.findall(r"^\s*-?\s*HOMEPAGE_ALLOWED_HOSTS[=:].*$", txt, re.M)
    auto = bool(f) and len(lines) == 1
    return {"needed": True, "app": "Homepage", "file": f or "", "auto": auto,
            "what": f"Homepage only answers the addresses listed in HOMEPAGE_ALLOWED_HOSTS (that's the “Host validation failed”), and {host} isn't one of them.",
            "will": [f"Back up {os.path.basename(f)}", f"Add {host} to HOMEPAGE_ALLOWED_HOSTS", "Restart Homepage (a few seconds)"] if auto else
                    [f"Add {host} to HOMEPAGE_ALLOWED_HOSTS in Homepage's settings (its compose file), then restart it."]}

def _hp_apply(info, chk, host):
    f = chk["file"]; txt = open(f).read()
    shutil.copy2(f, f"{f}.nova-backup-{time.strftime('%Y%m%d-%H%M%S')}")
    def add(m):
        line = m.group(0)
        sep = "=" if re.search(r"HOMEPAGE_ALLOWED_HOSTS=", line) else ":"
        head, val = line.split(sep, 1)
        q = '"' if val.strip().startswith('"') else ("'" if val.strip().startswith("'") else "")
        v = val.strip().strip("\"'")
        return f"{head}{sep}{' ' if sep == ':' else ''}{q}{v + ',' if v else ''}{host}{q}"
    new = re.sub(r"^\s*-?\s*HOMEPAGE_ALLOWED_HOSTS[=:].*$", add, txt, count=1, flags=re.M)
    with open(f, "w") as fh: fh.write(new)
    subprocess.run(["docker", "compose", "-f", f, "up", "-d"], cwd=os.path.dirname(f), capture_output=True, timeout=300)


def check(cid, host):
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host): raise ValueError("bad address")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", str(cid)): raise ValueError("bad container")
    info = _inspect(cid); k = _kind(info)
    if k == "homeassistant": c = _ha_check(info)
    elif k == "homepage": c = _hp_check(info, host)
    else: return {"needed": False, "known": False}
    return {k2: v for k2, v in c.items() if not k2.startswith("_")} | {"known": True}

def apply(cid, host):
    host = str(host).strip().lower()
    if not re.fullmatch(HOST_RE, host): raise ValueError("bad address")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}", str(cid)): raise ValueError("bad container")
    info = _inspect(cid); k = _kind(info)
    c = _ha_check(info) if k == "homeassistant" else _hp_check(info, host) if k == "homepage" else {"needed": False}
    if not c.get("needed"): return {"ok": True, "changed": False}
    if not c.get("auto"): raise ValueError("Nova can't change this one safely by itself — " + " ".join(c["will"]))
    if k == "homeassistant": _ha_apply(info, c)
    else: _hp_apply(info, c, host)
    return {"ok": True, "changed": True, "app": c["app"], "backup": True}
