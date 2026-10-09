# Changelog

## 0.4.5-alpha (app) · server 0.4.4-alpha
- Alerts: swipe right to **ignore** one you've dealt with (quiet until it clears, then re-armed), **removed on purpose** for drives, **Mount it now** for unmounted drives; swipe left in the Inbox to delete, or clear it. App and web.
- Drive health tests: quick test and full surface scan with progress and results (SMART self-tests).
- Multiple servers: Settings → Servers shows every paired server's health live; servers on the home network are found automatically (UDP 8496 discovery) and prefill pairing.
- Any Debian machine: the monitor is now part of the package (auto-detects mounts from fstab, enabled services, CPU sensors for AMD/Intel/Pi); storage, backups and fan text are no longer specific to one machine; helper works without site tools. Tested by installing in a clean Debian 13 container.
- Fixed: QR scanner crash in release builds (ML Kit stripped by R8); live graphs no longer snap back a step when new samples arrive (glide timed from the data, poll aligned to the server's tick).

## 0.4.3-alpha (server: Nova web rebuilt)
- Nova web now mirrors the app: Home (server picture, customizable shortcuts, at-a-glance stats, Home layout editor), Menu, Quick panel (fan slider tile, tiles, power) with editor, Status with live 1-second smooth graphs, Containers (actions, restart policy, logs, shell), App store (apps + programs), Inbox with filters, Notifications, Lighting (effects, colours, speed, rainbow, LEDs, status light) and Schedules, Storage & drives (SMART, mount/unmount), Users & devices, Settings, Server name & accent, Appearance (theme, reduce motion, Home layout, shortcuts, bottom bar), Dashboard mode with tile editor and night dimming, About, Setup guide.
- Same look: glass cards, frosted bottom bar with a sliding indicator, frosted back button and toasts, One UI dialogs, slide transitions, scroll memory, back from a tab goes Home; side rail on wide screens.
- "Disconnected" with details instead of raw Cloudflare errors; risky actions still go to your phone for approval.
- Live graphs glide by the number of samples that arrived and poll just after the server's tick (no snapping back).

## 0.4.4-alpha (app) · server 0.4.2-alpha
- Terminal: "Let this phone log in" installs the phone's hardware-backed SSH key into the terminal user's authorized_keys (admin + fingerprint, phones only; forwarding disabled; tagged per device and removed when the device is removed — app, `nova-api revoke`, or "remove all").
- "Disconnected · last contact …" with per-route details instead of raw Cloudflare errors (HTTP 530 etc.).
- Fan light slider: label colour follows the fill edge (readable in light mode).
- Lighting: boot no longer drops the wave animator (systemd ordering cycle); a wave fades in at boot before your setting; lights switch off at shutdown (the ARGB header keeps standby power).
- Restart/shutdown from the app: the alert is pushed out immediately (phones + Discord) and the server goes down 8 s later instead of 5.

## 0.4.3-alpha (app only)
- Bottom bar: the selected-tab indicator is frosted glass and slides between tabs; choose and reorder the tabs (hold the bar, or Settings → Appearance → Bottom bar).
- Back from any tab goes to Home; back on Home closes the app (predictive back previews Home).
- Home's live numbers keep the last value instead of showing "—" when a page is redrawn.

## 0.4.2-alpha (app only)
- Toasts are real frosted glass.
- Home layout: reorder or hide the server picture, shortcuts and at-a-glance stats (Home ⋮ → Edit Home, or Settings → Appearance).
- Predictive back: on release the page finishes leaving while the one underneath settles, then swaps with no transition — no blank frame or re-fade.

## 0.4.1-alpha
- App: frosted glass (real backdrop blur on the bottom bar, back/action buttons and toasts) and raised glass cards; back-button circle centred on the arrow.
- Pages remember their scroll position when you go back; stronger predictive back (page lifts, scales and slides; the one underneath comes forward).
- Home shortcuts: choose up to 5 and drag to reorder (hold the bar, or Settings → Appearance); tidier Home layout on one spacing grid.
- Status (app and web) and Dashboard: live 1-second graphs with smooth monotone curves that glide in; "Live" / "Last hour" switch.
- Server: samples stats every second (`/api/v1/stats?since=` returns only new points); network interface detected instead of hard-coded; device "last seen" written at most once a minute instead of on every request.

## 0.4.0-alpha
- **Server package** (`nova-server_*.deb`) with a guided `nova-setup` wizard; `install.sh` now builds and installs it.
- Root helper runs as a socket-activated service outside the API sandbox; the API can't gain privileges; tighter systemd sandbox (exposure score 1.8).
- Refuses to start with an unreadable config instead of running with empty settings.
- App: predictive back, slide transitions, press animations, rolling numbers; Appearance (theme, Home layout, reduce motion); App lock; hide in recent apps; built-in setup guide.
- Web: Permissions-Policy / COOP headers; browsers can't manage devices or approvals.

## 0.3.6
- Nova web with browser pairing approved from a phone and phone approval for risky browser actions; Tailscale route.

## 0.3.5
- Users & roles (admin / view only), invites by QR; brightness slider tile; long-press tiles; dashboard mode; tablet layout.

## 0.3.0
- Multiple servers, device management, server name/accent, instant alerts, encrypted home connection (pinned TLS), SSH terminal, status page, custom quick panel.
