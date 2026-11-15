# Nova on a Mac server

Nova's server runs on macOS 12 or newer, on Apple silicon and Intel — a Mac mini makes a quiet home server. It is the
**same server** as on Linux and Windows — the same files, the same signed `/api/v1` protocol — so the Nova app, the
watch app and Nova web work against it unchanged. This page says how to install it, what works today, and how it's
put together.

> **Status: preview.** The core is tested on a real Mac on every change (see *How it's tested*). The parts that are
> missing are listed below; the apps hide those pages on a Mac server instead of showing errors.

## Install

In Terminal, one line (it asks for your password):

```sh
curl -fsSL https://raw.githubusercontent.com/dash1101/Nova/main/get.sh | sh
```

Or download **nova-macos.tar.gz** from the [newest release](https://github.com/dash1101/Nova/releases/latest),
double-click it to unpack, and run `sudo sh Nova/mac/install.sh`.

Then open **Nova Server** from Applications (it asks for your password, and opens in your browser), get the app on
your phone, press **Pair a phone or tablet** and scan the code with the app. In a terminal, `sudo nova add` does the same.

It needs Python 3.9 or newer. Apple's command line tools have it (`xcode-select --install`), as do Homebrew and
python.org; the installer uses whichever it finds and builds Nova its own environment from it.

**Updating:** `sudo nova update`, or Nova Server → **Check for updates** → **Update now**. Either downloads the newest
release, checks its signature against Nova's release key (as Linux and Windows do) and runs its installer. Settings,
paired devices and the server's certificate are kept.
**Removing:** `sudo nova uninstall` (add `--purge` to delete settings and devices too).

**If phones can't connect:** when the Mac's firewall is on (System Settings → Network → Firewall), allow incoming
connections for Nova's Python under *Options*. The installer does this when the firewall is on at install time, and
`sudo nova doctor` says when it may be in the way. Keep the Mac from sleeping (System Settings → Energy), or use
**Power → Keep awake** in the app.

**For programs that install Nova** (hub apps such as Orbit): `install.sh --quiet` and `uninstall.sh --yes` ask nothing,
exit with 0 or 1, and end with one line to parse, the same as on Windows:
`NOVA-RESULT {"ok":true,"version":…,"previous":…,"firstRun":…,"running":…,"devices":…,"ports":{"https":…,"http":…},"urls":{"local":…,"home":…,"plain":…,"tailnet":…}}`
(on failure `{"ok":false,"error":…}`). While Nova runs, `/Library/Application Support/Nova/alerts/www/status.json`
(readable by every user) has its health, what waits for approval, `setup`, `ports`, `urls` and the check-up's findings
(`doctor`); `sudo nova status --json` and `sudo nova doctor --json` give the same as data.

## What works

| | Mac | Notes |
|---|---|---|
| Pairing, devices, roles, approvals, 2FA codes, passkeys | ✅ | identical to Linux |
| Encrypted home-network listener with a pinned certificate | ✅ | identical |
| Tailscale and Cloudflare tunnel access | ✅ | Tailscale is found automatically when it's running at install; `remote_url` in `config.json` |
| Home: status, uptime, live CPU / per-core / memory / swap / network / drive activity graphs | ✅ | |
| CPU and drive temperatures, graphics use | ❌ | a Mac's sensors aren't readable without Apple's private interfaces |
| Inbox, alerts, Discord, dismiss / ignore / archive | ✅ | the same monitor, with Mac checks |
| "Why it went down" after an unclean shutdown | ✅ | from macOS's own record (power lost, power button held, heat) |
| Low space, failing drives (S.M.A.R.T. status), high load, low memory | ✅ | |
| Task manager: processes, end / force stop | ✅ | macOS's own processes are protected |
| Power: restart, shut down, schedule either, restart or stop Nova, keep awake | ✅ | keep awake is macOS's `caffeinate`; schedules are carried out by Nova's helper |
| Drives list with space and health | ✅ | |
| The server terminal and the file manager (as your own account, never root) | ✅ | folders macOS protects (Desktop, Documents, Downloads) need Full Disk Access for Nova's Python; deleted files go to `~/.local/share/Trash` |
| The app's Terminal (SSH) | ✅ | turn on **Remote Login** (System Settings → General → Sharing); the app adds its key for you after your fingerprint |
| Containers (Docker Desktop, OrbStack, Colima): list, start / stop / restart, logs, details | ◐ | untested — the test machines have no Docker |
| `nova` command: add, approve, devices, remove, status, doctor, log, alert, link, plugin, update, uninstall | ✅ | with `sudo` |
| **Nova Server** window (status, pairing QR, devices, approvals, check-up, updates) | ✅ | Applications, or `sudo nova gui` |
| Nova web in a browser | ✅ | `https://<address>:8495` |
| Updating Nova itself | ✅ | from the app (Settings → Software update), the Nova Server window or `sudo nova update` — signature checked each way |
| Programs and updates (Homebrew), macOS updates | ❌ | planned |
| App store containers, compose editing, backups | ❌ | planned |
| Storage wizard, partitions, RAID | ❌ | Disk Utility for now |
| Lighting, diagnostics, free up space, the assistant's on-server model | ❌ | Linux only for now |

**Have an older or Intel Mac?** That's what hasn't been tried — [PREVIEWS.md](PREVIEWS.md) has a short list of what to check.

## How it's put together

The Mac runs the files Linux runs. `server/api/plat.py` is the one place that knows which system it is; everything
macOS does differently is in three files next to their Windows counterparts:

| | Linux | Windows | macOS |
|---|---|---|---|
| system facts, live numbers, privileged verbs | inline (`/proc`, systemd, lsblk) | `winhost.py` | `machost.py` |
| the helper's front door | `nova-helper.socket` (systemd) | `helper-win.py` | `helper-mac.py` |
| what the monitor checks | `nova_alerts.py` | `winmon.py` | `macmon.py` |
| background services | systemd units | Task Scheduler (`\Nova\`) | launchd (`app.novalabs.nova.*`) |
| first settings, logs, self-update | `nova-setup`, journal, `nova-update` | `autosetup.py`, `runpart.py`, `selfupdate.py` | the same three |

The split between the two halves is the same as everywhere: the API runs as its own account (`nova-api`, which can't
sign in and has no administrator rights), and only the helper — a fixed list of verbs — runs as root. The helper
listens on a socket file in Nova's folder that only root and `nova-api` can open.

| What | Where |
|---|---|
| The program and its Python environment | `/usr/local/lib/nova` (`lib/`, `venv/`) |
| Settings, paired devices, Inbox, logs | `/Library/Application Support/Nova` (`config/`, `data/`, `alerts/`, `log/`) |
| The three services | `/Library/LaunchDaemons/app.novalabs.nova.{api,helper,alerts}.plist` |
| The command and the window | `/usr/local/bin/nova`, `/Applications/Nova Server.app` |

## How it's tested

`.github/workflows/macos.yml` builds the download on every change to the server, installs it on a real Mac (GitHub's
macOS machines: Apple silicon, macOS 26 at the time of writing) twice — once with the Python from Apple's command line tools (3.9) and once with a
current one — and runs `packaging/mac_test.py`: pairing, signed requests, the live numbers, drives, processes, power
schedules, keep awake (checked against macOS's own list of sleep-preventing programs), the terminal, the file manager,
a real SSH login with a phone-style key, the monitor, the `nova` command and the Nova Server window. Then it installs
again over itself (settings and the certificate must survive) and uninstalls (nothing may be left behind).

Not tested there: Intel Macs, macOS versions before 26, a real Mac mini's sleep behavior, Docker, Tailscale, and the firewall prompt — the test
machines are virtual, with no Docker, no Tailscale and the firewall off.
