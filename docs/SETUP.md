# Setting up Nova

Nova has two parts: the **server package** on the Linux machine you want to control, and the
**app** on your Android phone. The whole thing takes about ten minutes.

> Nova is **alpha**. Try it on a machine where you have backups and console access.

## 1. Install the server package

Requirements: Linux with systemd and Python 3.11+ — Debian 12+, Ubuntu 22.04+, Fedora 39+, RHEL/Rocky/Alma 9+,
Arch, openSUSE. Nothing else: Docker, Tailscale and cloudflared are optional, and the setup offers to install
each one that's missing (Docker for containers and the container store; Tailscale to reach Nova and your apps
from anywhere privately; cloudflared for a Cloudflare Tunnel on your own domain). Without Docker, Containers
says so and the store's Programs tab still works — with apt, dnf, pacman or zypper, whichever your Linux has.

The easiest way, on any of them — one file from the [latest release](https://github.com/dash1101/Nova/releases/latest):

```bash
sudo sh nova-installer.run          # installs, then asks: set up in this terminal, or in a browser
```

Or pick the package for your system:

```bash
sudo apt install ./nova-server_<version>_all.deb        # Debian, Ubuntu, Mint, Raspberry Pi OS

tar xzf nova-server_<version>.tar.gz                     # any other Linux
sudo sh nova-server-<version>/install.sh
```

Every file in a release is listed in `SHA256SUMS`, signed with Nova's release key (`SHA256SUMS.sig`), and
Nova's own updates check both before installing anything.

## 2. Set it up

```bash
sudo nova-setup          # in the terminal
sudo nova-setup --gui    # in a browser — on this computer, or a phone or laptop on your home network
```

The browser setup prints an address with a one-time key in it (and opens it, if the server has a desktop).
It only answers this computer and your home network, and stops when setup is done or after an hour.
If the server's firewall is on, it tells you the one command to let another device reach it.

Both ask the same few questions, with the suggestions filled in:

| Step | What it means |
|---|---|
| Network | The address phones use, and the network that's allowed to pair new devices. |
| Away from home | Tailscale (a private network between your devices) and/or a Cloudflare Tunnel. Both optional — see [REMOTE.md](REMOTE.md). |
| Notifications | Alerts always show in the app; Discord is an optional extra copy. |
| Tools | Docker and the drive tools (pools, RAID, NAS backups, tests). Optional — Nova offers each one when a feature needs it. |
| This server | The account the in-app terminal signs in as, your location for sunrise/sunset light schedules, and (advanced) protected mounts and where updates come from. |
| Start | Saves everything, opens Nova's ports (tcp 8495, udp 8496) to your home network only if `ufw` or `firewalld` is on, and starts Nova. |

Run `sudo nova-setup` again whenever you want to change something: it asks which part, and your current
answers are the defaults. For unattended installs: `sudo nova-setup --yes`, or `--answers answers.json --yes`.

## 3. Pair your phone

1. Install the Nova app (the APK from a release, or build it, see the README).
2. On home Wi-Fi run `sudo nova add` and scan the QR code with the app.
   - Typing instead: enter the server address and code, and check the certificate code matches.
3. The first time you open the app at home, fingerprint confirmation for risky actions is set up automatically.

## 4. Optional extras

| Want | Do |
|---|---|
| **Use Nova away from home** | [REMOTE.md](REMOTE.md) (Cloudflare Access, free), then `sudo nova-setup` |
| **Family or a wall tablet** | App → Menu → Users & devices → *Invite a phone* → **View only** |
| **Use it from a computer** | Open `https://<server>:8495/`, choose *Get a code*, then approve it in the app (*Approve a browser*) |
| **Always-on screen** | App → Menu → *Dashboard mode*. Pair a spare tablet as View only and pin the screen |
| **Fan / case lighting** (Gigabyte RGB Fusion 2) | `sudo nova-setup-lighting` |
| **Alerts, Discord, what's watched** | Built in — see [MONITOR.md](MONITOR.md) |
| **More servers** | Install Nova on each; in the app, Settings → Servers lists the ones it finds on your network |
| **Your own look** | App → Settings → *Appearance & privacy* (theme, Home layout, app lock); *Server* (name, accent color) |

## Updating

- **Server:** install the new `.deb` the same way. Settings and paired phones are kept.
- **App:** publish a new APK to your own server with `sudo nova-app-publish app.apk "what's new"`.
  Phones then update from Settings → Software update.

## Removing

```bash
sudo apt remove nova-server        # keeps settings and paired devices
sudo apt purge nova-server         # removes them too (your containers and data are never touched)
```

## Troubleshooting

| Problem | Try |
|---|---|
| App says *Can't reach Nova* at home | `systemctl status nova-api`; check the firewall allows port 8495 from your network |
| Service won't start | `journalctl -u nova-api -n 50`. A config permissions message means: `sudo chown root:nova-api /etc/nova-api/config.json && sudo chmod 640 /etc/nova-api/config.json` |
| Pairing says *only on the home network* | You're on mobile data or a guest network. Join the same network as the server |
| Installs / Free RAM fail | `systemctl status nova-helper.socket` (the privileged helper) |
| Browser warns about the certificate | Expected: Nova uses its own certificate. Continue once; the app pins it, and browsers sign every request |
| Lost your phone | From another admin device: Users & devices → remove it. Or on the server: `sudo nova remove <name or id>` |
