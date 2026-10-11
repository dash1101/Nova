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
