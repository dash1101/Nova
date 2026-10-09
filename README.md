# Nova  ·  0.5.4-alpha

**A secure remote control for your home server, from your Android phone.**
Status and live graphs, containers (logs, shell, start/stop), a one-tap app store, drives,
backups, alerts, an SSH terminal and more, in a One UI–style app. It works on your home
network, and optionally from anywhere through Cloudflare Access, without a VPN.

```
 phone (Nova app) ──home network: HTTPS, pinned certificate──┐
        │                                                    ├─► nova-api (unprivileged)
        └── anywhere: Cloudflare Access ─► cloudflared ──────┘        │
                                                             fixed-verb root helper
```

## What you get

| | |
|---|---|
| **Home** | Server health, live fan/LED preview, banner for anything that needs attention |
| **Status** | CPU, memory, temperature and network graphs (last hour), storage, backups, services |
| **Containers** | Every Docker Compose container: logs, a shell inside it, start/stop/restart, update, restart policy |
| **App store** | One-tap installs of popular self-hosted apps (Jellyfin, Vaultwarden, Uptime Kuma, …) and apt programs |
| **Storage** | A map of every drive and pool with suggestions; a step-by-step wizard to combine drives into one, build RAID 0/1/5/6/10, set up a backup drive, grow a pool or replace a failed drive (with "?" help everywhere); SMART health, temperatures and drive self-tests |
| **Backups** | Scheduled, versioned snapshots of any folders to another drive or a NAS (SMB / NFS), missing-drive protection, history, browse & restore |
| **Diagnostics** | Internet speed (down/up/ping/jitter/loss), phone ↔ server speed, drive speed (sequential + random), CPU stress with live temperature/clock/power, memory test, ping/traceroute/DNS/port tools, top processes |
| **Terminal** | Real SSH to the server, with a key that lives in the phone's secure chip |
| **Quick panel** | Your own tiles: back up, free RAM, lights, pause alerts, restart a container… |
| **Alerts** | Inbox plus instant notifications (no Google push service, no third party) |
| **Several servers** | Pair with as many machines as you like and switch between them, each with its own name and colour |
| **Users & roles** | Named users, each Admin or View only; invite phones by QR, approve browsers from your phone |
| **Tablets & dashboard** | One UI tablet layout (rail + list/detail) and an always-on, customisable dashboard mode |
| **Two looks** | Default (One UI-style frosted glass) or Material You Expressive with wallpaper colours — chosen automatically by phone maker, switchable in Appearance |
| **Nova web** | The same design in any browser, at `https://<server>:8495/`. Risky actions are approved on your phone |
| **Optional modules** | Case/fan lighting (Gigabyte RGB Fusion 2); plug in your own monitor for alerts |

## Security in one paragraph

Every request is signed by an ECDSA key that is generated inside the phone's hardware keystore
(StrongBox when available) and never leaves it. Requests carry a timestamp and a single-use nonce,
so they can't be replayed. Risky actions (shells, stopping things, installs, unmounting, power,
removing devices) also need a second, fingerprint-bound signature. Phones are added only on the
server's own network, with a single-use code from `sudo nova add`. On the home network the app
talks HTTPS to a self-signed certificate that it pins at pairing time. Remotely, Cloudflare Access
stops strangers before they reach your machine. The API runs as an unprivileged, sandboxed user;
the only privileged code is a root helper with a fixed list of verbs. Details: [docs/SECURITY.md](docs/SECURITY.md).

## Install the server (Debian / Ubuntu)

```bash
sudo apt install ./nova-server_*_all.deb    # from https://github.com/dash1101/Nova/releases/latest
sudo nova setup                             # guided setup: network, Tailscale, remote, location, tools, firewall
sudo nova add                               # shows a QR code: scan it with the app
```

Full walkthrough, extras and troubleshooting: **[docs/SETUP.md](docs/SETUP.md)**.

Everything else: `sudo nova` lists all commands (`approve`, `devices`, `remove`, `status`, `update`, `backup`, …).

## Install the app

Build it yourself (below), or install a release APK. Updates after that come from inside the app
(Settings → Software update), served by your own server: `sudo nova-app-publish app.apk "notes"`.

```bash
cd android
# Android SDK (compileSdk 37) + JDK 17+. Create your own signing key once:
keytool -genkeypair -keystore signing/nova-release.jks -alias nova -keyalg EC -groupname secp256r1 -validity 20000
cat > signing/signing.properties <<EOF
storeFile=signing/nova-release.jks
storePassword=…
keyAlias=nova
keyPassword=…
EOF
./gradlew assembleRelease            # add -Pabi=arm64-v8a for a smaller phone-only APK
```

Keep the signing key safe. Updates must be signed with the same key, or Android refuses them.

## Remote access (optional)

Nova works on your home network out of the box. To use it from anywhere without a VPN, put it
behind a Cloudflare Tunnel with Access and a service token. Step by step: [docs/REMOTE.md](docs/REMOTE.md).

## Layout

```
android/                 the app (Kotlin, Jetpack Compose)
server/api/              server.py (API), helper.py (root helper), nova-api (CLI), nova-app-publish
server/store/            app store catalogue: docker/*.json (compose-based apps) + programs.json (apt)
server/modules/          optional: lighting (fan-gigabyte-fusion2)
packaging/               .deb builder (build-deb.sh), nova-setup-lighting, logrotate
server/install.sh        installer
docs/                    security model, remote access, modules, adding store apps
```

## Adding apps to the store

Drop a JSON file in `server/store/docker/`; see [docs/STORE.md](docs/STORE.md). Pull requests welcome.

## Nova web

Open `https://<server>:8495/` on your home network. The certificate is Nova's own, so the browser
warns once; that's expected. Choose "Get a code", then in the app go to Users & devices → Approve a
browser. Over Tailscale, set `tailnet_subnet`/`tailnet_host` in the config. Remotely through
Cloudflare, add an Access policy that allows your own email (alongside the service-token policy the
app uses). See docs/REMOTE.md.

## Known limitations

- Fan **speed** control needs a kernel driver for your board's fan chip. Fan/case **lighting** is
  supported only on Gigabyte RGB Fusion 2 boards for now (the module is small, so other controllers
  are welcome).
- Monitoring and alerts are built in (docs/MONITOR.md); you can still plug in your own monitor.
- Pools use mergerfs (combined) or Linux software RAID (mdadm); ZFS isn't offered from the app yet.
