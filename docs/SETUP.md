# Setting up Nova

Nova has two parts: the **server package** on the Linux machine you want to control, and the
**app** on your Android phone. The whole thing takes about ten minutes.

> Nova is **alpha**. Try it on a machine where you have backups and console access.

## 1. Install the server package

Requirements: Debian 12+ or Ubuntu 22.04+, systemd, Python 3.11+. Docker is optional but needed for
containers and the app store.

```bash
# from a release:
sudo apt install ./nova-server_<version>_all.deb

# or from source:
git clone https://github.com/<you>/nova && cd nova
sudo ./server/install.sh               # builds the package, installs it, runs the wizard
```

## 2. Run the setup wizard

```bash
sudo nova-setup
```

It asks a few questions. Press Enter to accept the suggested answer.

| Step | What it means |
|---|---|
| Home network | The address phones use, and the network that's allowed to pair new devices. |
| Tailscale | If it's running, you can let your Tailscale devices use Nova too. Optional. |
| Terminal | The account the in-app SSH terminal logs in to. |
| Remote access | Cloudflare Access, so Nova works away from home without a VPN. You can skip this and do it later (see [REMOTE.md](REMOTE.md)). |
| Storage safety | Mounts that can never be unmounted from the app. |
| Firewall | Opens Nova's port (tcp 8495) and discovery (udp 8496) to your home network only, if `ufw` is active. |

Run `sudo nova-setup` again whenever you want to change something. Your current answers are the
defaults.

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
