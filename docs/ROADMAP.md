# Roadmap

Where Nova is heading next. Nothing here is promised; it's the plan as it stands.

## Windows (server side)

People will want to manage a Windows PC the same way, and nothing about the phone side stops them.
The Linux server package leans on systemd, apt, Docker, mdadm and `/proc`, so Windows needs its own
small agent rather than a port of everything:

- **Same API, same security.** The agent speaks the signed `/api/v1` protocol unchanged (P-256
  device keys, step-up for risky actions, browser approvals), so the existing app and web work
  against it with no changes. Unsupported features are reported in `overview.features`, which the
  app already uses to hide screens.
- **Two halves, like on Linux.** An unprivileged service answers the API; a separate privileged
  Windows service runs a fixed list of verbs with checked arguments (restart, shut down, services,
  Windows Update, Docker Desktop containers), reached over a local named pipe that only the API
  service can open.
- **First version:** status and live graphs (CPU, memory, disks, network, temperatures where the
  hardware reports them), services, processes, Docker Desktop containers, drives and free space,
  alerts, power, pairing and devices. Later: Windows Update, Storage Spaces, backups.
- **Install:** a signed MSI that installs both services, opens the firewall for the home network only,
  and shows the pairing QR code (`nova add` equivalent) in a small tray window.
- **Remote access:** the same Cloudflare Access setup works (`cloudflared` runs on Windows).

## iOS app

- SwiftUI app with the same screens. The security model carries over directly: keys made in the
  **Secure Enclave** (P-256, non-exportable), Face ID / Touch ID for the step-up signature,
  certificate pinning on the home network.
- Distribution is the hard part: the App Store needs an Apple Developer account ($99/year) and
  review; TestFlight works for testing. Sideloading (AltStore and similar) is possible but awkward.
- Until then, **Nova web** works on iPhone and iPad in Safari, and can be added to the home screen.

## Google Play

The Android app can be published on Google Play (the developer account exists). What it needs:

- A **Play build** without the built-in updater: Play policy doesn't allow apps to install their own
  updates, so that build gets updates from Play instead. The direct-download APK keeps the updater.
- Store listing: screenshots, a short and long description, the icon, a privacy policy (Nova
  collects nothing; everything stays between your phone and your server), and the Data safety form.
- Target the current Android SDK level Play requires, and an app bundle (`.aab`) signed with Play
  App Signing.

## Also planned

- A terminal for the server itself in Nova web (the phone app already has SSH).
- Servers page, next step: manage another server right from this one's Nova web (a signed relay, so each server still checks your browser's own key and approvals), instead of opening its own Nova web.
- Apps from anywhere, next step: create the Cloudflare hostname and Access rule automatically (needs a scoped Cloudflare API token on the server).
- A Wear OS app with its own sign-in, approvals and a glanceable status.
- A reworked app store with categories, search and one-tap installs.
