# Nova on a Windows server

Nova's server runs on Windows 10 and 11 and on Windows Server 2019 or newer. It is the **same server** as on Linux
— the same files, the same signed `/api/v1` protocol — so the Nova app, the watch app and Nova web work against it
unchanged. This page says how to install it, what works today, and how the two systems share one codebase.

> **Status: preview.** The core is tested on real Windows machines on every change (see *How it's tested*). The
> parts that are missing are listed below; the apps hide those pages on a Windows server instead of showing errors.

## Install

1. Download **nova-windows.zip** from the [newest release](https://github.com/dash1101/Nova/releases/latest) and unzip it.
2. Double-click **Install Nova.cmd** (Windows asks for permission once).
3. Open **Nova Server** from the Start menu, press **Pair a phone or tablet**, and scan the code with the Nova app.

Or, in an administrator PowerShell, in one line:

```powershell
irm https://raw.githubusercontent.com/dash1101/Nova/main/get.ps1 | iex
```

It needs Python 3.9 or newer installed for all users; the installer gets Python with `winget` if it's missing.
**Updating** is the same steps again with the newer zip — settings, paired devices and the server's certificate
are kept. **Removing:** `C:\Program Files\Nova\uninstall.ps1` (add `-Purge` to delete settings and devices too).

## What works

| | Windows | Notes |
|---|---|---|
| Pairing, devices, roles, approvals, 2FA codes, passkeys | ✅ | identical to Linux |
| Encrypted home-network listener with a pinned certificate | ✅ | identical |
| Tailscale and Cloudflare tunnel access | ✅ | set `tailnet_*` / `remote_url` in `config.json` (found automatically when Tailscale is running at install) |
| Home: status, uptime, live CPU / per-core / memory / page file / network / drive activity graphs | ✅ | graphics use: NVIDIA via `nvidia-smi`, others via Windows' own counter |
| CPU and drive temperatures | ◐ | drive temperature where Windows reports it; Windows has no general CPU temperature sensor |
| Inbox, alerts, Discord, dismiss / ignore / archive | ✅ | the same monitor, with Windows checks |
| "Why it went down" after a crash or power loss | ✅ | from Windows' event log: blue-screen stop codes explained, power loss recognized |
| Low space, failing or worn drives, hot drives | ✅ | health as Windows reports it (the same source as Settings → Storage) |
| Stopped services (OpenSSH, Docker, Tailscale, cloudflared), Windows needing a restart for updates | ✅ | |
| Task manager: processes, end / force stop | ✅ | Windows' own processes are protected |
| Power: restart, shut down, schedule either, restart or stop Nova | ✅ | schedules are one-time tasks in Task Scheduler |
| Drives list with space and health | ✅ | |
| Containers (Docker Desktop): list, start / stop / restart, logs, details | ✅ | untested with running containers in CI — the test machines have none |
| `nova` command: add, approve, devices, remove, status, doctor, log, alert, link, plugin | ✅ | run from an administrator terminal |
| **Nova Server** window (status, pairing QR, devices, approvals, check-up) | ✅ | Start menu |
| Nova web in a browser | ✅ | `http://<address>:8095` or `https://<address>:8495` |
| Programs and updates (winget), Windows Update | ❌ | planned: winget joins apt/dnf/pacman/zypper in `pkg.py` |
| App store, new containers, compose editing, container updates | ❌ | planned |
| Files, terminal | ❌ | planned (terminal needs Windows' ConPTY) |
| Backups | ❌ | planned |
| Storage wizard, partitions, RAID, SMART self-tests, unmounting | ❌ | Windows' own tools for now |
| Lighting module, assistant brain, Labs, in-app server update | ❌ | Linux only for now |

## How it's put together

Windows gets the same two halves as Linux, so a bug in the part that faces the network can't manage the machine:

| Part | Runs as | Linux equivalent |
|---|---|---|
| **Api** — what phones and browsers talk to | `Local Service` (no administrator rights) | `nova-api.service` (user `nova-api`) |
| **Helper** — the fixed list of privileged verbs | `SYSTEM`, listening on `127.0.0.1` only; every request needs a key only Nova's accounts can read | `nova-helper.socket` |
| **Alerts** — the monitor behind the Inbox | `SYSTEM` | `nova-alerts.timer` |

They are three tasks in Task Scheduler's `\Nova\` folder that start with Windows and restart if they stop.

| | Where |
|---|---|
| Program | `C:\Program Files\Nova` (`lib\` the server, `venv\` its Python environment) |
| Settings | `C:\ProgramData\Nova\config\config.json` — the same keys as `/etc/nova-api/config.json` |
| Devices, certificate, app settings | `C:\ProgramData\Nova\data` |
| Status and Inbox | `C:\ProgramData\Nova\alerts` |
| Logs | `C:\ProgramData\Nova\log` (`api.log`, `helper.log`, `alerts.log`, `audit.log`) |

Only `SYSTEM`, administrators and (where it needs to) `Local Service` can read `C:\ProgramData\Nova`; the alerts
settings, which hold the Discord webhook, are closed to `Local Service` too. Windows Firewall gets two rules in the
group "Nova" that let in ports 8095/8495 (and 8496 for finding the server) **from the local subnet and Tailscale only**.

## One codebase

Nothing was rewritten for Windows. Three small seams keep it that way:

- **`server/api/plat.py`** is the only place that asks which system this is. `plat.p("/var/lib/nova-api/x")` maps
  Nova's Linux paths to `C:\ProgramData\Nova\…` (and returns them unchanged on Linux); `plat.meminfo()`,
  `plat.uptime_s()`, `plat.node()` and friends replace reading `/proc` directly.
- **`server/api/winhost.py`** holds everything Windows-specific: the live numbers, what the machine is, and the
  helper's Windows verbs. `helper.py` hands a verb to it first; verbs that are the same on both systems (Docker,
  notification settings, alerts) carry on into the shared code, and a verb nobody has done yet answers
  "That isn't available on a Windows server yet."
- **`server/monitor/winmon.py`** holds the monitor's Windows checks; remembering, de-duplicating, the Inbox and
  Discord are `nova_alerts.py` on both.

When you add something:

1. Write Linux paths literally and wrap them in `plat.p()`.
2. Read system facts through `plat`, not `/proc`.
3. A new helper verb works on Linux at once. For Windows, add its twin in `winhost.verb()` — or, if the same code
   is right for both, add its name to `winhost.SHARED`.
4. A part Windows doesn't have goes in `plat.WITHOUT`, which is sent in `overview.features` so the apps hide it.
   Remove the line when it works.
5. Keep `server/windows/*.ps1` plain ASCII (Windows PowerShell misreads UTF-8 without a byte-order mark; CI checks).

## How it's tested

There is no Windows machine in the loop by hand: `.github/workflows/windows.yml` builds `nova-windows.zip`, installs
it on GitHub's Windows Server 2025 and 2022 machines, and runs `packaging/windows_test.py` — pairing a device,
signed requests, the live numbers, processes, drives, power schedules (really in Task Scheduler), the monitor
writing the Inbox, the `nova` command, the Nova Server window, who each part runs as and who can read its data —
then installs again over itself (settings and certificate must survive) and uninstalls (nothing may be left).

Not covered there, so treat as untested: Windows 10/11 desktop editions specifically, real graphics cards, drives
that are actually failing, Docker Desktop with running containers, and the blue-screen explainer against a real crash
(its decoding is unit-tested against Windows' event format, not a live event).
