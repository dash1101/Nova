# Roadmap

Where Nova is heading next. Nothing here is promised; it's the plan as it stands.

## The road to 1.0

**1.0 is Linux servers, the Android app and Nova web.** Those three have run day in, day out since the first alpha, and
they are what 1.0 promises to keep working. The server on Windows and macOS, the iPhone app and the Wear OS app ship
alongside as **previews**: usable, tested on every change, and labeled as previews until they've had the same time
on real machines ([PREVIEWS.md](PREVIEWS.md) is how to help with that).

Done:

- [x] One-line install on any Linux with systemd (`get.sh`), signed releases, self-updating server and app
- [x] A documented API contract with levels and a deprecation policy, and what 1.0 freezes ([API.md](API.md))
- [x] Privacy policy ([site/privacy.html](../site/privacy.html)) and an upgrade guide ([UPGRADING.md](UPGRADING.md))
- [x] Nova web installable on iPhone and iPad (Add to Home Screen)
- [x] A beta channel (`sudo nova update --beta`), and release checks that run against a beta *before* it becomes the
      release everyone gets ([packaging/RELEASING.md](../packaging/RELEASING.md))
- [x] Every change is tested as an **upgrade**: the previously published release is installed and paired, updated by
      its own updater, and the paired device must still get in with the same certificate
- [x] Backups proven by restoring: on every kind of destination (a drive, an exFAT or FAT drive, an SMB share, NFS
      exports) files are backed up, changed, wiped, restored and compared byte for byte, on every change
- [x] The app's pairing-code reader and request signing checked against vectors made with the server's own rule
- [x] A brief for an outside security review ([REVIEW.md](REVIEW.md))

Open, in the order they block 1.0:

- [ ] **The beta period.** `1.0.0-beta.N` on several machines and distributions for a few weeks, with no new features
      going in — only fixes. 1.0 is the last beta, renamed.
- [ ] **An outside review of the pairing and approval design.** The brief is written; the review itself needs a second
      pair of eyes.
- [ ] **Translations.** English is complete. The nine other languages each cover about two thirds of the text (2,826 of
      4,197 pieces; everything written since 0.8.3 is English only), so today they show a mix. 1.0 ships the languages
      that are finished and holds back the rest — fewer, finished languages over many half-done ones.
- [ ] App store rework: dynamic listings and easier installs *(may land after 1.0 — it adds, it doesn't change)*

Not part of 1.0: a Google Play listing (below), the previews leaving preview.

## Windows (server side)

**Started — see [WINDOWS.md](WINDOWS.md).** The server now runs on Windows as a preview, from the same files as the
Linux one (a small platform layer instead of a separate agent), speaking the same signed `/api/v1` so the app, watch
and web are unchanged. It keeps the two halves — an API without administrator rights and a privileged helper with a
fixed list of verbs — and is installed, tested and uninstalled on real Windows machines by CI on every change.

- [x] Pairing, devices, approvals, encrypted listener, Tailscale / Cloudflare access
- [x] Status and live graphs, Inbox and alerts (with "why it went down" from Windows' event log), processes, power
      and schedules, drives with health, Docker Desktop containers (list, start/stop, logs)
- [x] Installer and uninstaller (firewall for the home network only), the `nova` command, the Nova Server window
- [x] Programs and updates through **winget** (one more backend in `pkg.py`, next to apt/dnf/pacman/zypper)
- [x] Background tasks without systemd (`tasks.py` starts each as a detached process)
- [x] The app's Terminal over SSH (Windows' OpenSSH Server); updating Nova from the app
- [ ] Windows Update; backups; the app store's containers on Docker Desktop (tested)
- [ ] Files, and the server terminal (ConPTY)
- [ ] A signed MSI; winget / Chocolatey packages for Nova itself
- [ ] Storage: Storage Spaces, SMART self-tests

## macOS (server side)

**Started — see [MACOS.md](MACOS.md).** A Mac mini makes a quiet home server, and the server now runs on macOS as a
preview, from the same files as the Linux and Windows ones: the unprivileged API and the privileged helper (the same
fixed list of verbs) as `launchd` services, the helper reached over a local socket only the API can use. It is
installed, tested and uninstalled on a real Mac by CI on every change.

- [x] Pairing, devices, approvals, encrypted listener, Tailscale / Cloudflare access
- [x] Status and live graphs, Inbox and alerts, processes, power and schedules, keep awake, drives with health
- [x] The server terminal, the file manager, the app's Terminal over SSH (Remote Login)
- [x] Installer and uninstaller, the `nova` command, the Nova Server app, updating itself (signed releases)
- [ ] Containers on Docker Desktop / OrbStack (there, untested); programs and updates through **Homebrew**; backups
- [ ] Temperatures and graphics use (`powermetrics`); a signed, notarized `.pkg`

## Other Linux (done in 0.7)

Nova now runs on any Linux with systemd: Debian and Ubuntu (the .deb), and Fedora/RHEL, Arch and openSUSE
(a tarball with an installer, or the one-file `nova-installer.run`). Everything that installs or updates
packages goes through one layer (`server/api/pkg.py`) that speaks apt, dnf, pacman and zypper, and Nova's
own updates use the signed tarball on those systems. The setup can also run in a browser
(`sudo nova setup --gui`), for people who'd rather not use a terminal.

## iOS app

**Started — see [IOS.md](IOS.md).** A SwiftUI app with the everyday pages (Home, Inbox, containers, power, the task
manager, drives, devices). The security model carried over directly: keys made in the **Secure Enclave** (P-256,
non-exportable), Face ID / Touch ID for the second signature on risky actions, the server's certificate pinned. CI
builds it and walks it through a real server in the Simulator on every change.

- [ ] The remaining pages (storage, backups, the store, updates, files, terminal, lighting, the assistant, several servers)
- [ ] Notifications while the app is closed (needs Apple's push service)
- [ ] The App Store: an Apple Developer account ($99/year) and review; TestFlight for testing. Until then it's
      sideloaded (AltStore and similar) or built from source, and **Nova web** works on iPhone and iPad in Safari.

## Google Play

Not planned. The Android app comes from your own server (Settings → Software update) or from the releases page, checks
each update's signature and checksum itself, and needs no account anywhere — a Play build would have to drop its
updater and add a second way of shipping. If that changes, it gets its own entry here.

## Also planned

- Servers page, next step: manage another server right from this one's Nova web (a signed relay, so each server still checks your browser's own key and approvals), instead of opening its own Nova web.
- Wear OS, next: a watch-face complication and more quick actions (restart a container).
- A reworked app store with categories, search and one-tap installs.
