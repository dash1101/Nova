# Changelog

## 0.5.6-alpha (app and server)
- **New-alert banners:** while Nova is open, a new alert slides in at the top (tap it for the Inbox), in the app and on the web.
- **Browser notifications:** Nova web can pop up new alerts on your computer while a tab is open (Notifications → This browser, or the prompt in the Inbox).
- **Favorites:** star any screen (Apps, Diagnostics, …) and it sits at the top of Menu (app and web).
- **Pin apps to the dock:** hold an app in Apps → Pin to dock; it stays in the navigation pill, open or not. The pill's indicator now moves onto the app you're in, and glides from where it was instead of appearing from nowhere.
- **Search history:** recent searches, pin the ones you use, remove or clear them (app and web).
- **Lighting:** Fire works in Flame, One color (a flame in your color) and Palette (your own heat ramp). Static, Pulse, Flash, Gradient and Color cycle can use a palette too — Nova then draws them itself, and the app, the web and the fan all show the same thing.
- **Drive lights on the server picture follow real disk activity:** each one blinks with its drive's reads and writes.
- **Remove devices from the web:** Users & devices → the bin icon; it's approved on an admin phone with your fingerprint (or `sudo nova approve`).
- Web: Esc goes back; copy buttons next to `sudo nova approve …`; Ctrl+A selects everything in the Inbox; selecting fades in smoothly and archived rows fold away; the dock stays still while pages slide; no stray scrollbars in dialogs; spinners no longer spin wildly with reduced motion.
- American spelling everywhere (color, customize, center, license).
- Security test: 170 checks, all passing.

## 0.5.5-alpha (app and server)
- **New license: AGPL-3.0** (was MIT), with a few extra terms in `NOTICE.md`: keep the credit, and a fork you share needs its own name, icon and app ID. Still open source; pull requests welcome. Versions before 0.5.5 stay MIT.
- **Start page** (app and web): a clock, one search box for Nova and the web (suggestions, ten search engines, shortcuts like `yt cats`, typing an address opens it), the server at a glance, your apps and your own bookmarks. Customize the sections and default engine; put it in the navigation pill; on the web it works as a browser new-tab page.
- **Open Nova on…** your choice of Home, Start page, Status, Apps, Inbox or Containers (Settings → Appearance), app and web.
- **Search everything:** screens and settings (by the words you'd use, like "dark mode" or "raid"), containers, apps, drives, pools and backups. Search icon on Home and in Menu; on the web press `/` or Ctrl+K.
- **Update center:** check for and install system package and container updates from Nova (fingerprint; warns before a Docker update restarts your containers). A daily check notifies you when updates are ready.
- **Discord alerts** can be set up, tested or turned off from the app and web (and in `nova setup`). Nova only ever shows the end of the webhook link, and only to admins.
- About shows the app and server version; tap it to check for updates. About also links the project.
- The website has a light/dark switch.
- Security test: 168 checks, all passing (new: updates, Discord, apps, folder list, suggestions).

## 0.5.4-alpha (app and server)
- **Apps:** the web apps on your server (detected from Docker: Immich, Home Assistant, Jellyfin, … with icons from dashboard-icons, cached on the server) in a grid. On Android they open inside Nova in a rounded frame with the app's name, the server's status, back, reload and close; open apps stay alive in the navigation pill until you close them. Rename, hide, add your own links, set a remote link. Nova web opens them in a new tab.
- **Web Inbox with a mouse:** no dragging or text selection; tick boxes (Shift-click for a range), an Archive bar for the selection, a visible Archive button on each row, keys x / e / j / k.
- **Folder browser** for backup sources (app and web).
- Store, Menu and other pages that aren't in your navigation pill open as normal pages with a back button (the pill no longer slides to a tab you don't have).
- Generic defaults: no names, mounts or drive models from one particular server; drive names come from the maker's prefix.

## 0.5.3-alpha (app and server)
- **Approve from your watch:** Settings → *Approve from notifications* (fingerprint once) adds Approve / Deny buttons to approval notifications, on the phone and on a paired watch. It uses a separate key the server registers as an approver: it can list, approve and deny what a browser is waiting on, nothing else; it follows its phone (removed with it, and stops if the phone loses admin).
- Security test: 142 checks, all passing.

## 0.5.2-alpha (app and server)
- **One command:** `sudo nova` lists everything; `nova add` pairs a phone (with a live countdown), `nova approve CODE` approves a browser's pairing code or an action a browser is waiting on (`nova approve` alone lists what's waiting), plus `devices`, `remove`, `status`, `setup`, `update`, `lighting`, `backup`, `log`, `alert`, `publish`. `nova-api` still works as an alias.
- Shell approvals are a one-time file only root can write, valid for 2 minutes and for one code; announced like any pairing.
- Nova web: live countdown on the pairing page; the "approve on your phone" sheet also shows `sudo nova approve CODE`; starts with reduced motion; archiving from a browser no longer needs phone approval (it's kept in the server's archive).
- 0.5.1: Material You style on the web; the navigation pill stands down the left edge in landscape and on wide screens (app and web).
- Security test: 127 checks, all passing.

## 0.5.0-alpha (app and server)
- **Storage map & suggestions:** every drive and pool with what it's used for (system, pool member, backup destination, data, unused), which containers use it, whether a backup covers it, and suggested next steps (unused drives, unbacked-up folders, full or degraded pools, drives that would hold up the boot).
- **Drive setup wizard** with "?" help throughout: one big drive (mergerfs — erase drives or keep them with their files), mirror/parity/double parity/mirror+stripe (mdadm RAID 1/5/6/10), stripe (RAID 0), a backup drive, a single drive, add to a pool, replace a failed array drive; live usable space and how many drives may fail; ext4 / XFS / Btrfs / exFAT; typed ERASE + fingerprint; runs as a background task with progress. Remove a pool (drives keep their files, or wipe an array).
- **Backups:** scheduled (daily at a time, every N hours, or manual), versioned hard-link snapshots to a local drive or a NAS over SMB or NFS (test the connection first; mirror + versions where hard links aren't possible), smart retention, missing-drive protection with a one-time override, history, browse & restore (in place or beside). An existing nova-backup script is shown alongside and keeps working.
- **Diagnostics:** internet speed (down/up/ping/jitter/loss), phone/browser ↔ server speed, drive speed (fio), CPU stress with live temperature/clock/power chart, memory test, ping / traceroute / DNS / port check, top processes.
- **Material You Expressive style** (Settings → Appearance → Style): wallpaper colors, tonal surfaces, segmented lists, large titles, Material switches/radios/dialogs, pill buttons that morph when pressed, springy motion. Automatic: Samsung and other One UI-/iOS-like phones get the Default look, everyone else Material You. Themes are now "Default light/dark" or "Material light/dark".
- **New icon** with a Material You themed-icon layer (Android 13+) and a matching notification icon; web icon and maskable icon.
- **Update notifications:** phones are notified when a new app is published (only if they don't have it yet) and when the daily check finds a server update; tapping opens Software update.
- Reduce motion now also turns off the predictive back animation.
- Tools: the package recommends mergerfs, mdadm, xfsprogs, btrfs-progs, exfatprogs, nfs-common, cifs-utils, fio, stress-ng, iputils-tracepath; nova-setup checks and installs anything missing; features install what they need on first use.
- Security test: 119 checks (35 new for storage, backups and diagnostics), all passing.

## 0.4.8-alpha (app) · server 0.4.7-alpha
- **Device type:** the app and web work out whether they're on a phone, tablet or computer (foldables count as phones; Chromebooks as computers) and tell the server (`POST /api/v1/device/form`). Users & devices shows the matching icon and label; the app says "this tablet" / "this computer" instead of always "this phone".
- **Schedules:** "No change" for brightness, color and effect, so a schedule can only dim the light (e.g. over 5 minutes); "Turn the light on if it's off" can be switched off so a schedule only runs while the light is on (`if_on`).
- **Server location** for sunrise/sunset: automatic from the server's time zone (tzdata's zone1970.tab, offline), set in `sudo nova-setup` (new step 7) or the app (Settings → Server, or Schedules). Stored in the server's settings (`/api/v1/settings` `location`; `null` = time zone again).

## 0.4.7-alpha (app) · server 0.4.6-alpha
- **Archive lives on the server.** Archiving from the Inbox (swipe left, or "Archive everything") moves events into the server's permanent archive (`/api/v1/archive`, newest 50 000); events that age out of the Inbox's 200 go there too. Every phone and browser sees the same history (Inbox → Archive, "Load older", filters, CSV export); each device's own copy only fills gaps.
- **Fan picture:** back to the smooth glowing strip, now driven by the same frame maths as the fan (server, app and web share one implementation, checked against test vectors), and lined up with the server's clock so it moves in step with the real fan.
- **Color picker:** hue/saturation/brightness plus R, G, B sliders with number boxes and a hex field (app and web).
- **New effects:** comet, scanner, twinkle, fire and breathe, alongside wave. Wave and the new effects take a **palette** (gradient) of 2–8 colors: edit your own or pick Ocean, Lava, Forest, Sunset, Party, Aurora, Ice, Candy or Fire; or use rainbow or one color.
- **Presets:** save the current look, apply in one tap, update/rename/delete (hold).
- **Schedules:** start at a time or at sunrise/sunset ± up to 2 h (server location; sun times worked out on the server); set the light, turn it off, or apply a preset; fade in over up to 2 h (step per minute, also from off and to off); optional end time that puts the light back how it was; skip next time, run now, duplicate, pause all.
- (0.4.6-alpha app) One status LED per drive in the server picture (boot drive included), amber for a warning and pulsing red for a missing/failing drive; predictive back always reveals the previous page from the left.

## 0.4.5-alpha (app) · server 0.4.4-alpha
- Alerts: swipe right to **ignore** one you've dealt with (quiet until it clears, then re-armed), **removed on purpose** for drives, **Mount it now** for unmounted drives; swipe left in the Inbox to delete, or clear it. App and web.
- Drive health tests: quick test and full surface scan with progress and results (SMART self-tests).
- Multiple servers: Settings → Servers shows every paired server's health live; servers on the home network are found automatically (UDP 8496 discovery) and prefill pairing.
- Any Debian machine: the monitor is now part of the package (auto-detects mounts from fstab, enabled services, CPU sensors for AMD/Intel/Pi); storage, backups and fan text are no longer specific to one machine; helper works without site tools. Tested by installing in a clean Debian 13 container.
- Fixed: QR scanner crash in release builds (ML Kit stripped by R8); live graphs no longer snap back a step when new samples arrive (glide timed from the data, poll aligned to the server's tick).

## 0.4.3-alpha (server: Nova web rebuilt)
- Nova web now mirrors the app: Home (server picture, customizable shortcuts, at-a-glance stats, Home layout editor), Menu, Quick panel (fan slider tile, tiles, power) with editor, Status with live 1-second smooth graphs, Containers (actions, restart policy, logs, shell), App store (apps + programs), Inbox with filters, Notifications, Lighting (effects, colors, speed, rainbow, LEDs, status light) and Schedules, Storage & drives (SMART, mount/unmount), Users & devices, Settings, Server name & accent, Appearance (theme, reduce motion, Home layout, shortcuts, bottom bar), Dashboard mode with tile editor and night dimming, About, Setup guide.
- Same look: glass cards, frosted bottom bar with a sliding indicator, frosted back button and toasts, One UI dialogs, slide transitions, scroll memory, back from a tab goes Home; side rail on wide screens.
- "Disconnected" with details instead of raw Cloudflare errors; risky actions still go to your phone for approval.
- Live graphs glide by the number of samples that arrived and poll just after the server's tick (no snapping back).

## 0.4.4-alpha (app) · server 0.4.2-alpha
- Terminal: "Let this phone log in" installs the phone's hardware-backed SSH key into the terminal user's authorized_keys (admin + fingerprint, phones only; forwarding disabled; tagged per device and removed when the device is removed — app, `nova-api revoke`, or "remove all").
- "Disconnected · last contact …" with per-route details instead of raw Cloudflare errors (HTTP 530 etc.).
- Fan light slider: label color follows the fill edge (readable in light mode).
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
- App: frosted glass (real backdrop blur on the bottom bar, back/action buttons and toasts) and raised glass cards; back-button circle centered on the arrow.
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
