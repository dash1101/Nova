"""
Nova apps — the web apps on this server (containers with a web UI, plus ones you add), for the Apps
grid in the app and Nova web. Detection runs in the root helper (it needs Docker); the API merges in
your changes (names, hidden apps, custom links) from /var/lib/nova-api/apps.json.
"""
import json, re, subprocess

# image (substring) → name, icon (dashboard-icons slug), web port inside the container, scheme, path.
# None for the port = no web UI (databases, workers, APIs) — never listed.
KNOWN = [
    ("immich-machine-learning", None), ("immich-server", ("Immich", "immich", 2283, "http", "/")),
    ("home-assistant", ("Home Assistant", "home-assistant", 8123, "http", "/")),
    ("open-webui", ("Open WebUI", "open-webui", 8080, "http", "/")),
    ("gethomepage/homepage", ("Homepage", "homepage", 3000, "http", "/")),
    ("crafty", ("Crafty Controller", "crafty-controller", 8443, "https", "/")),
    ("wetty", ("WeTTY", "wetty", 3000, "http", "/wetty")),
    ("searxng", ("SearXNG", "searxng", 8080, "http", "/")),
    ("portainer", ("Portainer", "portainer", 9443, "https", "/")),
    ("jellyfin", ("Jellyfin", "jellyfin", 8096, "http", "/")),
    ("plex", ("Plex", "plex", 32400, "http", "/web")),
    ("vaultwarden", ("Vaultwarden", "vaultwarden", 80, "http", "/")),
    ("uptime-kuma", ("Uptime Kuma", "uptime-kuma", 3001, "http", "/")),
    ("filebrowser", ("File Browser", "filebrowser", 80, "http", "/")),
    ("gitea", ("Gitea", "gitea", 3000, "http", "/")),
    ("syncthing", ("Syncthing", "syncthing", 8384, "http", "/")),
    ("code-server", ("code-server", "code-server", 8080, "http", "/")),
    ("dozzle", ("Dozzle", "dozzle", 8080, "http", "/")),
    ("excalidraw", ("Excalidraw", "excalidraw", 80, "http", "/")),
    ("it-tools", ("IT Tools", "it-tools", 80, "http", "/")),
    ("nextcloud", ("Nextcloud", "nextcloud", 80, "http", "/")),
    ("adguard", ("AdGuard Home", "adguard-home", 3000, "http", "/")),
    ("pihole", ("Pi-hole", "pi-hole", 80, "http", "/admin")),
    ("grafana", ("Grafana", "grafana", 3000, "http", "/")),
    ("node-red", ("Node-RED", "node-red", 1880, "http", "/")),
    ("frigate", ("Frigate", "frigate", 5000, "http", "/")),
    ("paperless", ("Paperless-ngx", "paperless-ngx", 8000, "http", "/")),
    ("audiobookshelf", ("Audiobookshelf", "audiobookshelf", 80, "http", "/")),
    ("navidrome", ("Navidrome", "navidrome", 4533, "http", "/")),
    ("sonarr", ("Sonarr", "sonarr", 8989, "http", "/")), ("radarr", ("Radarr", "radarr", 7878, "http", "/")),
    ("prowlarr", ("Prowlarr", "prowlarr", 9696, "http", "/")), ("qbittorrent", ("qBittorrent", "qbittorrent", 8080, "http", "/")),
    ("transmission", ("Transmission", "transmission", 9091, "http", "/")), ("photoprism", ("PhotoPrism", "photoprism", 2342, "http", "/")),
    ("mealie", ("Mealie", "mealie", 9000, "http", "/")), ("actual", ("Actual Budget", "actual-budget", 5006, "http", "/")),
    ("homarr", ("Homarr", "homarr", 7575, "http", "/")), ("heimdall", ("Heimdall", "heimdall", 80, "http", "/")),
    ("nginx-proxy-manager", ("Nginx Proxy Manager", "nginx-proxy-manager", 81, "http", "/")),
    ("ollama", None), ("postgres", None), ("mariadb", None), ("mysql", None), ("mongo", None), ("redis", None), ("valkey", None),
    ("socket-proxy", None), ("cloudflared", None), ("caddy", None), ("watchtower", None),
]

def match(image):
    for key, info in KNOWN:
        if key in image: return key, info
    return None, False

def detect():
    """Running containers with a web UI: [{id, name, icon, port, scheme, path, host_ip, container, image, guess}]"""
    try:
        ids = subprocess.run(["docker", "ps", "-q"], capture_output=True, text=True, timeout=20).stdout.split()
        js = json.loads(subprocess.run(["docker", "inspect"] + ids, capture_output=True, text=True, timeout=30).stdout) if ids else []
    except Exception:
        return []
    out = []
    for c in js:
        name = c["Name"].lstrip("/"); image = c["Config"]["Image"]; labels = c["Config"].get("Labels") or {}
        key, info = match(image)
        if info is None and not labels.get("nova.port"): continue             # a known non-web container
        ports = (c.get("NetworkSettings") or {}).get("Ports") or {}
        host_net = (c.get("HostConfig") or {}).get("NetworkMode") == "host"
        want = int(labels.get("nova.port") or (info[2] if info else 0) or 0)
        host_port, host_ip = None, ""
        if host_net and want: host_port = want
        else:
            maps = [(int(k.split("/")[0]), m) for k, v in ports.items() if k.endswith("/tcp") and v for m in v]
            pick = [x for x in maps if x[0] == want] or ([] if info else maps)
            if pick:
                cport, m = pick[0]
                host_port = int(m["HostPort"]); host_ip = m.get("HostIp") or ""
        if not host_port: continue
        if host_ip in ("0.0.0.0", "::"): host_ip = ""
        if host_ip.startswith("127."): continue                                  # only reachable on the server itself
        title = labels.get("nova.name") or (info[0] if info else name.replace("-", " ").replace("_", " ").title())
        out.append({"id": name, "name": title, "slug": labels.get("nova.icon") or (info[1] if info else re.sub(r"[^a-z0-9-]", "", name.lower())),
                    "port": host_port, "scheme": labels.get("nova.scheme") or (info[3] if info else ("https" if host_port in (443, 8443, 9443) else "http")),
                    "path": labels.get("nova.path") or (info[4] if info else "/"), "host_ip": host_ip, "container": name, "image": image,
                    "guess": not info and not labels.get("nova.port"), "source": "container"})
    return sorted(out, key=lambda a: a["name"].lower())
