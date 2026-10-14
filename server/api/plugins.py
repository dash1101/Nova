"""
Nova plugins: small add-ons that give Nova more apps and one-tap tools.

A plugin is a folder in /etc/nova/plugins/<id>/ with a plugin.json. Only root can put one there
(`sudo nova plugin add <folder>`), so a plugin is as trusted as anything else you install with sudo —
but what it runs still runs as your normal account (the same as command apps), never as root.

plugin.json:
{
  "id": "system-tools",                 // folder name: lowercase letters, digits, dashes
  "name": "System tools", "version": "1.0", "author": "you", "description": "…",
  "apps":     [{"name": "Netdata", "port": 19999, "path": "/", "icon": "netdata"},     // a web app on this server
               {"name": "Router", "url": "http://192.168.1.1/"}],                      // or any link
  "commands": [{"id": "sensors", "name": "Temperatures", "command": "sensors",          // a one-tap tool (output shown live)
                "description": "…", "timeout": 60, "confirm": false, "icon": "thermometer"}]
}
Switch one off in Settings → Plugins (it stays installed); `sudo nova plugin remove <id>` deletes it.
"""
import json, os, re

DIR = "/etc/nova/plugins"
ID_RE = r"[a-z0-9][a-z0-9-]{0,39}"
CMD_RE = r"[a-z0-9][a-z0-9-]{0,39}"


def _clean(s, n): return "".join(c for c in str(s or "") if c.isprintable()).strip()[:n]

def load(pid):
    """One plugin's manifest, checked (anything malformed is dropped, not half-used)."""
    if not re.fullmatch(ID_RE, pid) or "--" in pid: return None
    p = os.path.join(DIR, pid, "plugin.json")
    try:
        st = os.stat(p)
        if st.st_uid != 0 or st.st_mode & 0o022: return None          # root's, and not writable by anyone else
        m = json.load(open(p))
    except Exception: return None
    if m.get("id") != pid: return None
    return check(m)

def check(m):
    """A manifest, cleaned: only what Nova understands, within limits (None if it isn't a plugin.json)."""
    if not isinstance(m, dict): return None
    pid = str(m.get("id", ""))
    if not re.fullmatch(ID_RE, pid) or "--" in pid: return None
    out = {"id": pid, "name": _clean(m.get("name"), 40) or pid, "version": _clean(m.get("version"), 20), "author": _clean(m.get("author"), 60),
           "description": _clean(m.get("description"), 300), "apps": [], "commands": []}
    for a in (m.get("apps") or [])[:20]:
        e = {"name": _clean(a.get("name"), 40), "icon": _clean(a.get("icon"), 60).lower()}
        if not e["name"]: continue
        if a.get("url") and re.fullmatch(r"https?://[^\s\"'<>]{1,300}", str(a["url"])): e["url"] = str(a["url"])
        elif isinstance(a.get("port"), int) and 1 <= a["port"] <= 65535:
            e.update(port=a["port"], path=str(a.get("path") or "/")[:200], scheme="https" if a.get("https") else "http")
        else: continue
        out["apps"].append(e)
    for c in (m.get("commands") or [])[:30]:
        if not re.fullmatch(CMD_RE, str(c.get("id", ""))) or "--" in str(c.get("id")) or not str(c.get("command", "")).strip() or len(str(c["command"])) > 8000: continue
        out["commands"].append({"id": c["id"], "name": _clean(c.get("name"), 40) or c["id"], "description": _clean(c.get("description"), 200),
                                "command": str(c["command"]), "timeout": max(10, min(3600, int(c.get("timeout") or 600))),
                                "confirm": bool(c.get("confirm")), "icon": _clean(c.get("icon"), 60).lower()})
    return out

def disabled():
    try: return set(json.load(open("/var/lib/nova-api/settings.json")).get("plugins_off", []))
    except Exception: return set()

def all_plugins():
    try: ids = sorted(os.listdir(DIR))
    except OSError: return []
    return [p for p in (load(i) for i in ids) if p]

def command(app_id):
    """The command behind an app id like plugin-<plugin>-<command> (looked up here, never taken from a request)."""
    m = re.fullmatch(rf"plugin-({ID_RE})--({CMD_RE})", str(app_id))
    if not m: return None
    if m.group(1) in disabled(): return None
    p = load(m.group(1))
    return next((c for c in (p or {}).get("commands", []) if c["id"] == m.group(2)), None)


# ── Installing from the app (Store → Plugins) ──────────────────────────────────────────
# The API (an unprivileged account) downloads and reads the plugin; the root helper only writes a
# manifest it checked again itself. Nothing in a plugin runs at install time.
CATALOG = "https://raw.githubusercontent.com/dash1101/Nova/main/plugins/index.json"
MAX = 256 * 1024

def manifest_from(raw):
    """plugin.json bytes from a .json file or a .zip holding one (at the top, or in one folder)."""
    import io, zipfile
    if raw[:2] == b"PK":
        try: z = zipfile.ZipFile(io.BytesIO(raw))
        except zipfile.BadZipFile: raise ValueError("that isn't a zip file")
        names = [n for n in z.namelist() if n.rstrip("/").split("/")[-1] == "plugin.json" and n.count("/") <= 1 and ".." not in n and not n.startswith("/")]
        if len(names) != 1: raise ValueError("the zip needs exactly one plugin.json (at the top, or in one folder)")
        i = z.getinfo(names[0])
        if i.file_size > 64 * 1024: raise ValueError("plugin.json is too big")
        raw = z.read(i)
    if len(raw) > 64 * 1024: raise ValueError("plugin.json is too big")
    try: m = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError): raise ValueError("plugin.json isn't valid JSON")
    if not check(m): raise ValueError("that plugin.json didn't check out — it needs an id (lowercase letters, digits, dashes) and apps or tools (see docs/PLUGINS.md)")
    return raw

def download(url):
    import urllib.request
    url = str(url).strip()
    if not re.fullmatch(r"https://[A-Za-z0-9.-]{1,253}(:\d{1,5})?/[^\s\"'<>]{0,1000}", url): raise ValueError("give an https:// link to a plugin.json or a .zip")
    import ipaddress, socket
    host = re.match(r"https://([^/:]+)", url).group(1)
    try: addrs = {ipaddress.ip_address(a[4][0]) for a in socket.getaddrinfo(host, 443)}
    except (OSError, ValueError): raise ValueError(f"couldn't find {host}")
    if any(a.is_loopback or a.is_link_local or a.is_unspecified or a.is_multicast for a in addrs): raise ValueError("that address is this server itself — use Install from a file instead")
    req = urllib.request.Request(url, headers={"User-Agent": "Nova-plugins"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read(MAX + 1)
    except Exception as e: raise ValueError(f"couldn't download it: {str(e)[:120]}")
    if len(raw) > MAX: raise ValueError("that's too big for a plugin (256 KB at most)")
    return raw

def preview(raw):
    """What installing it would add — shown before you confirm (with the exact commands its tools run)."""
    import hashlib
    m = check(json.loads(raw.decode("utf-8")))
    have = load(m["id"])
    return {"plugin": m, "sha256": hashlib.sha256(raw).hexdigest(), "installed": bool(have), "installed_version": (have or {}).get("version", "")}

def install_root(raw):
    """(root) Write a checked plugin.json to /etc/nova/plugins/<id>/, root's and read-only for everyone else."""
    m = json.loads(raw.decode("utf-8")); c = check(m)
    if not c: raise ValueError("that plugin.json didn't check out")
    d = os.path.join(DIR, c["id"])
    os.makedirs(DIR, mode=0o755, exist_ok=True); os.chmod(DIR, 0o755)
    if os.path.islink(d): raise ValueError("refusing a symlink")
    os.makedirs(d, mode=0o755, exist_ok=True); os.chown(d, 0, 0); os.chmod(d, 0o755)
    tmp = os.path.join(d, ".plugin.json.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o644)
    with os.fdopen(fd, "wb") as f: f.write(raw)
    os.chown(tmp, 0, 0); os.chmod(tmp, 0o644); os.replace(tmp, os.path.join(d, "plugin.json"))
    if not load(c["id"]): raise ValueError("installed, but it didn't load")
    return c

def remove_root(pid):
    import shutil
    if not re.fullmatch(ID_RE, str(pid)): raise ValueError("no such plugin")
    d = os.path.join(DIR, pid)
    if not os.path.isdir(d) or os.path.islink(d): raise ValueError("no such plugin")
    shutil.rmtree(d)
