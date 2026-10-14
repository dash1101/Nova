# Plugins

Plugins add apps and one-tap tools to Nova without changing Nova itself. A plugin is a folder with
a `plugin.json`; installing it needs root on the server, and everything it runs, runs as your
normal account — never as root.

```bash
sudo nova plugin add ./system-tools      # shows exactly what it adds, then asks
sudo nova plugin list
sudo nova plugin remove system-tools
```

Installed plugins live in `/etc/nova/plugins/<id>/`, owned by root and read-only for everyone else
(Nova ignores a manifest anyone else could have changed). Switch one off in **Settings → Plugins**
(that needs your fingerprint, since a plugin's tools run as you); it stays installed.

## plugin.json

```json
{
  "id": "system-tools",                  // the folder name: lowercase letters, digits, single dashes
  "name": "System tools",
  "version": "1.0",
  "author": "you",
  "description": "What it's for, in a sentence.",
  "apps": [
    {"name": "Netdata", "port": 19999, "path": "/", "icon": "netdata"},   // a web app on this server
    {"name": "Router", "url": "http://192.168.1.1/"}                       // or any link
  ],
  "commands": [
    {"id": "temps", "name": "Temperatures", "description": "CPU and drive sensors",
     "command": "sensors", "timeout": 60, "confirm": false, "icon": "thermometer"}
  ]
}
```

- **apps** show up in Apps (and the Start page, the Menu and the dock) like any other app. `icon` is a
  [dashboard-icons](https://github.com/homarr-labs/dashboard-icons) name.
- **commands** show up in Apps as tools: tap one and its output streams in, like a command app. They
  run as the server's normal account (`shell_user`), with `timeout` seconds at most (10–3600).
  `"confirm": true` asks before running.

See `plugins/examples/system-tools/` for a complete one.

## Ideas

Status pages for your own scripts, maintenance tasks (`docker system df`, `apt list --upgradable`),
links to every web UI on your network, game-server controls, home-automation scenes. If you make
one others would like, open a pull request adding it under `plugins/`.

## Installing from the app (Store → Plugins)

The App store has a **Plugins** tab with three ways in:

- **Community plugins** — the list in [`plugins/index.json`](../plugins/index.json). Entries marked *reviewed*
  were read through by the Nova project; the rest are listed as-is, and Nova says so.
- **Install from a link** — an `https://` link to a `plugin.json`, or to a `.zip` holding one (at the top, or
  in one folder).
- **Install from a file** — the same `.zip` or `plugin.json`, from your phone or computer.

Either way Nova shows you what the plugin adds first — every app, and the exact command each tool runs —
and installing asks for your fingerprint. The server downloads and reads the plugin as its normal
(unprivileged) account; only then does root write the checked `plugin.json` to `/etc/nova/plugins/<id>/`.
Nothing in a plugin runs while it's being installed. Plugins are at most 256 KB, and a link to this
server itself is refused (use a file instead).

### Listing yours

Open a pull request that adds an entry to `plugins/index.json`:

```json
{"id": "my-plugin", "name": "My plugin", "author": "you", "description": "What it adds, in a sentence.",
 "url": "https://raw.githubusercontent.com/you/my-plugin/main/plugin.json"}
```

The `id` must match the one in your `plugin.json`. New entries start unreviewed.
