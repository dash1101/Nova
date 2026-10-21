# Changelog

## 0.8.9-alpha (server, Nova web, app and watch)
- **Drives, in more detail** (Storage & hardware, app and web): each drive shows its model and serial number, a small activity light that blinks as it reads and writes, and — when it has any — how much space its bad or pending sectors cover.
- **Approve a browser by scanning it** (app and web): the web's pairing screen shows a QR code; in the app, Users & devices → Approve a browser → Scan the browser's code.
- **Install Nova web as an app** (Menu → Install Nova, in browsers that offer it).
- **Backup script, without writing bash** (web, Backups → Edit the script → Build it for me): pick folders and a destination, dated snapshots (keep the newest N; unchanged files are shared) or one mirror copy, skip patterns, and a guard that won't back up onto the system drive when the backup drive is missing. Look it over, then Save.
- **Light splitting** (Appearance, Glass, app and web): how strongly Glass's rim splits light into its colors — off to strong. Glass also notes that it's the heaviest look on older devices.
- **Default look: Transparency and Frost** (Appearance, web): how see-through and how frosted cards are.
- **Aero sliders** have Windows 7's glossy track and thumb (app and web).
- **Controller button hints** (Nova web): the on-screen hints show Xbox, PlayStation or generic buttons, matched to your controller automatically or chosen in Appearance → Controller.
- **Fewer settings at once:** the list of which sounds play is folded into a "Which sounds" dropdown, and docs/DESIGN.md writes down "don't overwhelm" and the other rules every screen follows.
- **Nova web always loads its newest version after an update** (no mix of old and new pieces).
- **Docs:** the README covers 0.8's features; the website's iPhone steps and minimum Android version (7.0) are up to date.

## 0.8.8-alpha (server, Nova web, app and watch)
- **The terminal never runs a command by itself** (web): a paste — including a middle-click or a trackpad's three-finger tap, which on Linux pastes your last selection — is held as text until you press Enter (or asks first, if the shell can't hold it); middle-click paste is off in the terminal; and Enter on `shutdown`, `reboot`, `poweroff`, `halt` or `systemctl poweroff/reboot` asks "Run this on the server?" first. Keystrokes now go in order, one request at a time (less lag when typing fast), and typing while scrolled up no longer makes the terminal jump.
- **Living wallpaper stays on** (web): it was switching off on the Inbox, which used the same name for its in-progress card; Aero's is sharp again; and on Android it always moves again after a back gesture.
- **Clicks land the first time** (web): a page refreshing its numbers while you pressed something swallowed the click — refreshes now wait until you let go. Holding something only counts if you don't move (dragging the fan slider in the Quick panel no longer opens the fan page).
- **Inbox → the right page** (app and web): each notification has an arrow to what it's about — the drive, the container, Security & privacy, Updates, Backups, Status.
- **Drive tests and backups show they're running:** a quick or full drive test now appears in progress (with its percent, from the drive itself) and lands in the Inbox as passed or failed; a backup says what it's doing from the first second.
- **Apps in Nova windows:** resizing no longer sticks to the cursor, and windows scroll again after a drag; no white flash while an app loads.
- **No white flashes on load** (web): the page starts in its own color, not white, before the styles arrive; the back arrow no longer flickers when a page refreshes.
- **Pop-ups are frosted from the first frame** (all looks, web; Aero showed it only after the animation).
- **The in-progress card** (Inbox) keeps a readable width and running tasks can be hidden.
- **Offline?** Reloading warns that the page would be empty until the server's back — reload anyway if you like (web).
- **Each CPU core's percent** shows on its graph (Status, split view, web).
- **Corner roundness:** 25–200% in every look, kept separately for each look (200% is the old 400% where looks went that far; Glass stops a little short of where its cards turn into ovals).
- **Smaller things:** the navigation pill now starts as Inbox · Home · Menu; Home has a house icon in the app's pill; the Home shortcut bar follows your corner roundness (app); a switch row makes the switch's own sound (app); the dock's arrows show only when there's really more; the tablet's list pane slides in smoothly; Glass says it's the most demanding look.

## 0.8.7-alpha (server, Nova web, app and watch)
- **Glass splits the light like iOS:** at the rim, white separates ever so slightly into its colors — red bends a touch more, blue a touch less, more so toward the very edge (app and web; on the web it's off with Lighter effects).
- **No more double sounds or buzzes** (app): a tap that comes with a switch or a page change is folded into it — one sound, one buzz.
- **Sliders tick** as you drag them, a light haptic every twentieth of the way (app, and phone browsers).
- **Smoother back gesture with a living wallpaper** (app): the scene holds still while you drag back, since two pages are drawing it at once.
- **Dashboard tiles resize in place** (app): hold one to go small → wide → the whole row.

## 0.8.6-alpha (server, Nova web, app and watch)
- **Sounds and haptics on every tap** (app): they come from the press itself now, so tapping a switch's row (not just the switch), a list item or any button makes its sound and buzz.
- **Icon shape** (Appearance, app and web): rounded square, squircle (like One UI), circle, square or teardrop — your apps' icons inside Nova, and on the web Nova's own tab and home-screen icon. (On Android the launcher decides the shape of Nova's own icon.)
- **More to see? Now it shows:** a full dock or navigation pill, and the Menu's apps row, fade at the edge and show a small arrow where there's more (app and web).
- **Tablets:** the dashboard and app pages fill the screen instead of keeping the previous page's list beside them.
- **Living wallpaper is lighter:** no full-screen blur on every frame (the scenes are drawn soft already), fewer layers in Aero's ribbons, and 15 frames a second with Lighter effects on (app and web).

## 0.8.5-alpha (server, Nova web, app and watch)
- **Android 7.0 and newer** (was 9): the app now installs on older phones and tablets; on Android 7–8 the fingerprint prompt is AndroidX's own.
- **Android sounds and haptics:** sounds now play at media volume (the system volume was muted on many phones), and taps use the phone's own touch feedback, which feels crisper.
- **Living wallpaper:** pick any scene for any look, or your own GIF or video (web); its own color, speed, blur, tint and frame rate limit (web; scene, color and speed in the app). Reduce transparency no longer turns wallpapers off. Material's shapes are no longer pixelated (web). On tablets, the living wallpaper no longer covers the list pane and the dock's fades.
- **Ambience:** choose the track separately from the look; switching crossfades gently, and the volume follows the slider much faster.
- **Sounds:** turn each kind on or off (taps, switches, pages, pop-ups, windows, finished tasks), and a soft sound when the navigation pill's selection slides.
- **Type a value into any slider** (tap its number): e.g. 0.8× animation speed. Animation speed now moves in steps of 0.05.
- **Apps in Nova windows:** resize from any edge or corner; Window corners slider in Labs; right-click or hold an app in the dock for Minimize, Maximize, Open in a tab, Pin to the dock (it stays after you close it) and Close; command apps like Temperatures open in their own window too; Files has back and forward buttons (back greyed out when there's nowhere to go).
- **Dashboard:** hold (or right-click) a tile to arrange it like a phone home screen — four tile sizes, remove in place; night dimming fades in and out gradually (choose over how long); the living wallpaper or its own picture as the background.
- **Fixed:** the Inbox selection bar is properly frosted in Mica and the other looks.
- A few things to find, too.

## 0.8.4-alpha (server, Nova web, app and watch)
- **Sounds and haptics** (Appearance → Sounds & haptics, app and web): taps, switches, going into and back out of pages, pop-ups, windows, finished tasks and alerts each have their sound, with a sound pack for every look — Aero's glassy chimes and classic click, Mica's soft taps, Breeze's plucks, Glass's pings, Material's quiet ticks and the Default look's droplets — or pick one pack for all of them. Alerts chime (a more urgent one when it's serious), and on phones taps and alerts give a light buzz. Every sound is made on the device; there are no sound files.
- **Living wallpaper · Beta** (Appearance → Wallpaper, app and web): an animated wallpaper for each look, in your accent color — Aero's ribbons of light and drifting bokeh, Mica's slowly turning bloom, Breeze's flowing waves, Glass's liquid color (which the glass bends, as it should), Material's floating shapes and a drifting nebula for Default. Drawn small and at 30 frames a second, paused when Nova isn't on screen and still with Reduce motion.
- **Ambience · Beta** (Appearance → Sounds & haptics, app and web): soft, slowly changing music while Nova is open, with chords and a sound of its own for each look.
- **Controller support** (Nova web): plug in or pair a game controller — D-pad or stick to move, A to select, B to go back, X for an item's menu, Y to search, LB/RB to switch tabs, Start for Home, the right stick to scroll.
- **CPU, core by core** (Status, app and web): double-click (or double-tap) the CPU graph and it splits into a graph per core, like Task Manager; again to join them. The separate core list is gone.
- **Files and Terminal are apps of their own** (Nova web, with apps in Nova windows on): they open in their own window with a place in the dock, like your other apps.
- **Apps in Nova windows:** the sign-in note now shows only when an app really sends you to Cloudflare's sign-in (apps you're signed in to just open), "I've signed in" works, and if your browser won't share the sign-in with Nova at a home address it says so and offers Nova's own address. Windows follow Corner roundness and Glass clarity, and Mica's titlebar is frosted. Nova now lets a window show only the addresses of your own apps.
- **Mica:** the wallpaper keeps your Blur and Dim settings instead of being washed out.
- **Aero:** buttons, switches and selections follow your accent color (app and web).

## 0.8.3-alpha (server, Nova web, app and watch)
- **Apps in Nova windows** (Labs → This browser, Nova web): apps open in a window inside Nova instead of a new tab, with a titlebar in the look you picked — Aero's glass frame and glossy buttons, Mica's flat caption buttons, Breeze's round ones, Glass's traffic lights, Material and Default. Drag, resize, maximize (double-click the titlebar), minimize to the navigation pill and back, or open the app in a tab. On a phone they fill the screen. Nova checks first whether an app lets itself be shown inside another site (Home Assistant doesn't, out of the box) and says so, and for an app behind Cloudflare sign-in it asks you to sign in once in a tab.
- **Fix an app that doesn't open from anywhere:** when an app's from-anywhere address gives an error such as Homepage's "Host validation failed" or Home Assistant's "400: Bad Request", Recommended now says so, and the app's **From anywhere** dialog (app and web) has a **Fix it** button that makes the one change the app needs.
- **Stop opening from anywhere** (an app's From anywhere dialog, app and web): takes the app's from-anywhere address away again, and the Cloudflare address too if Nova made it.
- **Rename things:** passkeys (Security & privacy), devices (web) and Wake-on-LAN computers (app and web) can now be renamed.
- **Web app icons match the app:** every icon choice on the web (Appearance → App icon) is now drawn from the Android app's own launcher icons, and **Your color** follows your accent like the app does.
- **See-through Glass icon** (app): launchers drew it on black; it's now a real see-through icon.
- **Translations:** the last of Nova's words in Spanish, German, French, Portuguese, Italian, Russian, Chinese, Japanese and Korean; counts like "Update 3 containers" now read right in every language.

## 0.8.2-alpha (server, Nova web, app and watch)
- **Hold a recommendation** (or right-click it on the web) for a quick menu: **Not now** hides it for a month, its fix opens straight away, and on Home **Hide Recommended from Home** takes the whole card off (Edit Home brings it back).
- **No more blank flash between pages** (web): the page you're leaving stays up until the next one has something to show, instead of flashing an empty "Loading…" page. Containers now draws as soon as the list arrives; the live CPU and memory figures fill in a moment later.
- **Animation speed** (Appearance, app and web): a slider from 0.25× to 3× for every animation in Nova.
- **Per-core CPU graphs** (Status, app and web): a small live graph for each CPU core under the main graphs.
- **Your own Nova icon on the web** (Appearance → App icon): the browser tab icon, and the icon Nova gets on an iPhone or iPad home screen, in any of the app's styles.
- **App icons that match the styles** (app, Appearance → App icon): new Aero, see-through Glass, Mica and Breeze icons.
- **Aero:** pop-up notifications and alerts get a much heavier frost, so they stay readable over anything; and the style no longer mentions Windows 7 by name.
- **Mica, Breeze and Aero:** the selection in the navigation pill and in segmented controls now keeps the pill's own corner shape at any corner roundness.
- **Translations:** about 1,200 more of Nova's words in Spanish, German, French, Portuguese, Italian, Russian, Chinese, Japanese and Korean — nearly everything is translated now.

## 0.8.1-alpha (server, Nova web, app and watch)
- **Fairer drive health:** the health % now weighs real failure signs — sectors the drive had to remap and read errors it reported — on a scale where the first few count for little and hundreds count a lot. A handful of unreadable (pending) sectors, which a power cut mid-write leaves behind and which go away when the file is written again, cost only a few points, and age, the cable-error history and vendor-specific raw values don't count. A drive with a few bad sectors now reads **Mostly healthy**; **Worn — keep an eye on it** (and the amber light on the server picture) is kept for drives that are really wearing out.
- **Health checks fold away** (a drive's page, app and web): the SMART readings sit in one row with a summary ("Passed · 42°C · 4 unreadable") — tap it for the details.
- **Amber drive lights blink too:** a drive with a warning still shows its disk activity on the server picture, like the green ones.
- **Restart Nova and Shut down Nova** in the power menu (Quick panel and the server picture's power button, app and web): restart just Nova in a few seconds, or stop it, without touching the server or anything else running on it. A stopped Nova comes back at the next boot or with `sudo systemctl start nova-api`. Both need your phone's fingerprint.
- **Fixed:** **Not now** on some recommendations said "bad id", and the fix buttons for some mount problems in the Partition manager didn't work (web).

## 0.8.0-alpha (server, Nova web, app and watch)
- **Security & privacy** (Settings or Menu, app and web): everything about who and what can reach the server, on one page — this device's key, app lock and hiding Nova in recent apps (app), how risky actions are approved, authenticator codes, passkeys, users and devices, remote access, privacy (Start page suggestions, the privacy policy) and **recent security activity** (pairings, approvals, devices, codes and passkeys, anything refused).
- **Passkeys** (Nova web): add a passkey from your password manager (Security & privacy → Passkeys), and it can add a new browser to the server or approve what a browser asks for — your fingerprint, face or PIN instead of the admin phone. Passkeys belong to a web address, so they work at Nova's web address (your Cloudflare one), not at an IP address. Adding or removing one needs your phone's fingerprint.
- **Recommended** (Home and its own page, app and web): what's worth doing on the server right now, worked out from what Nova already watches — a drive or pool filling up, space that can be freed, memory and swap, updates, a restart an update is waiting on, unreadable sectors, a backup that skipped a folder (it noticed yours skipped cold storage while it wasn't mounted), devices nobody has used in months, mount problems, and more. Each one goes straight to its fix; **Not now** hides one for a month.
- **Search finds everything:** every screen, plus settings inside them — authenticator codes, passkeys, app lock, Glass clarity, corner roundness, icons, swap, frame rate, Developer options, the dashboard and Home editors and more.
- **API level 2** ([docs/API.md](docs/API.md)): `GET /api/v1/capabilities` tells apps what a server can do, so new features stay modular — an app shows a feature only where the server lists it. The API doc now says what's stable, what's still settling before 1.0, and the rules every endpoint follows.

## 0.7.9-alpha (server, Nova web, app and watch)
- **Moving files onto a drive is smarter:** the same file on both sides (byte for byte, whatever its date) no longer counts as a clash — the extra copy just goes. Two different files with one name: Nova tests archives (.gz, .zst, .xz, .bz2, .zip), and when the drive's copy is broken (what a failing drive does) the good one takes its place and the broken one is kept beside it, renamed. If it can't tell, both are kept. Nothing is ever lost, and no SSH needed.
- **Force unmount** (app and web): when something is using a drive, Nova says what — containers and programs — and offers Force unmount, which stops those containers and detaches the drive. Start the containers again once it's back.
- **A drive you removed on purpose** (Forget it in the Inbox) leaves the server picture right away — no red light for it.

## 0.7.8-alpha (server, Nova web, app and watch)
- **Partition manager** (Storage & hardware → Partition manager, or search for it): every drive and its partitions on one page, like Windows Disk Management — tap a drive to edit its partitions, tap a partition to change where it's mounted.
- **Change where a drive is mounted** (like "Change drive letter and paths"): pick a folder like /mnt/name and the partition is mounted there only, kept after a restart. Files that were saved into that folder while the drive wasn't there are moved onto the drive first (checked byte for byte; if a file clashes with a different one on the drive, nothing is removed).
- **Storage fixes it spots for you**, with one tap to fix (in Storage & hardware and the Partition manager): a partition mounted in several places or listed several times in /etc/fstab, files hidden under a mount point, and mounts left behind by a drive that's been unplugged.

## 0.7.7-alpha (server, Nova web, app and watch)
- **Partitions** (a drive's page → Partitions, app and web), laid out like KDE Partition Manager: the drive drawn to scale, its partitions and free space, and the changes you queue up — new GPT or MBR table, create, delete, format (ext4, XFS, btrfs, exFAT, FAT32, swap), label, flags (EFI, BIOS boot, RAID, LVM), grow, and shrink (ext4). You see the drive as it will be, then apply everything together with your fingerprint and the drive's serial. Nova checks every change against the real drive, only touches a drive nothing is using (never the system drive), refuses if the drive changed since you planned, and saves the old table first — **Restore the previous table** puts it back. Moving partitions isn't offered.
- **Authenticator codes, for computers without a phone or SSH at hand** (Users & devices → Authenticator codes, app and web): scan a QR code into any authenticator app, and a 6-digit code from it can add a browser to the server, or approve something a browser asks for. It's off until an admin turns it on with their fingerprint; every code works once, and after five wrong codes in 15 minutes codes are refused for a while (your phone still works).

## 0.7.6-alpha (server, Nova web, app and watch)
- **Stopped containers aren't problems:** a container you stop on purpose (Stop in Nova, `docker stop`) no longer shows up as "Container down" or "needs attention". It's counted as stopped instead ("10/10 running · 4 stopped"), on Home, in Containers and in Status. A container that crashes is still an alert.
- **Swap, clearer:** the Swap graph shows how much is in use and how big it is ("1.1 of 6.2 GB"). A server with no swap at all hides the graph and offers **Set up swap**, which makes a swap file on the system drive, switches it on and keeps it after a restart (asks for your fingerprint).
- **Glass is lighter on the GPU (app):** Developer options → **Glass quality** (default 70%) leaves out the prism fringe and narrows the bending rim, where the lens does its work; under 50% the lens is off. And **Frame rate** caps the app at 30, 60, 90 or 120 fps — the biggest saving on a 120 Hz phone.
- **Dashboard:** night dimming is gentler (40% instead of 72%) and has a **How dark** slider; new **Top containers** and **Server info** tiles; on the web, **Full screen when tapped**; and the app catches up with the web — columns, text and tile size, black or wallpaper background, plain tiles, wide or square tiles, 12/24-hour clock with seconds, and a full screen switch.
- **Corner roundness up to 400%**, with dialogs keeping their buttons clear of the corners at any roundness; Glass stops at 200% (past that its cards turn into ovals). Aero's, Mica's and Breeze's small parts — the nav pill and its selection, search bars, sliders, buttons — now follow the roundness too.
- **Glossy icons:** a new icon style, white symbols on shiny glossy tiles — Aero's own.
- **Fixes:** swiping an Inbox alert away in Glass keeps it glass while it moves (it turned flat for a moment); Material You's back arrow lines up with the page title on the web; Aero's color picker's Done button reads properly; tablets: no seam beside the side pill at the top and bottom, and a tab shown beside the page it opened no longer has a back arrow.

## 0.7.5-alpha (server, Nova web, app and watch)
- **A long-term API** ([docs/API.md](docs/API.md)): the server reports an API level (`api_level`, `min_level`, and an `X-Nova-Api-Level` header on every response) that only ever goes up, endpoints are never removed or changed within `/api/v1`, and anything retired is announced with deprecation headers for at least two releases and six months first. The apps tell the server which build they are, so a server can ask a too-old app to update instead of failing strangely; the app shows a banner when it or the server needs updating. The security test now checks the contract.
- **Landscape back gesture, cleaned up:** the page underneath stays full size and brightens as you drag, and the page on top slides away with a soft shadow on its edge — no more hard edges or a black strip.
- **New style: Aero** (Appearance → Style, app and web): tinted glass — sky-blue frames that frost what's behind them, a soft shine with faint diagonal streaks, a dark edge with a light one inside, glossy buttons that glow blue, list rows that light up, and popups laid out like classic task dialogs. Glass clarity sets how deep the tint is; your accent color tints the glass.
- **No more white flash in Nova web:** changing pages could briefly show a white frame — the browser's blank page showing through the page transition, or a fresh page load drawn in the device's light theme before your chosen theme was applied. Both now use Nova's own background from the first frame.
- **Glass clarity, re-scaled again:** 10% is now the frosted Glass from before 0.7.4, and everything above it gets clearer, until 100% is almost bare glass — next to no tint, the page behind seen sharply and bent only at the rim (app and web). The default, 60%, is a clear liquid look.
- **iPhone and iPad:** Nova web can be added to the Home Screen (Share → Add to Home Screen) and opens full screen with its own icon.
- **Privacy policy** on the website (and in About): Nova collects nothing; the apps talk only to your server. Plus an upgrade guide ([docs/UPGRADING.md](docs/UPGRADING.md)) and a road-to-1.0 checklist in the roadmap.

## 0.7.4-alpha (server, Nova web, app and watch)
- **Glass is liquid again:** Glass clarity now sets how sharp the page behind the glass is, not just how tinted it is. Clear glass sees the page sharply and only bends it at the rim, with a stronger bend; low clarity gives the soft, frosted look. The scale moved too: the old 100% is now about 50%, so 100% is clearer than ever (app and web).
- **Developer options** (About → Developer options, app and web): a frame-rate meter (frames per second and the slowest frame, colored against your screen's refresh rate), a log of the last requests with their timing and route, outlines of every glass layer, the glass lens switched off for comparison, and device details you can copy into a bug report.

## 0.7.3-alpha (server, Nova web, app and watch)
- **Install in one line:** `curl -fsSL https://raw.githubusercontent.com/dash1101/Nova/main/get.sh | sh` downloads the latest release, checks its signature and installs it (with apt on Debian and Ubuntu). On the website and in the README too.
- **Container usage:** Containers shows each one's CPU and memory, and **Heaviest first** sorts by what's using the most (web and app).
- **Quiet hours** (app → Inbox → notification settings): at night only critical alerts make a notification; the rest wait in the Inbox.
- **Glass, sharper and easier to read:** blurred wallpapers and the page behind popups are smooth instead of pixelated, popup text sits on a calmer layer, and long lists like the Inbox now get their glass edges too (a very tall card used to lose its lens).
- **Landscape:** the side navigation pill is frosted glass again instead of black, and the back gesture shows the page underneath while you drag.
- **FAQ:** setting Nova up on several computers on the same network, and sharing one Cloudflare token and domain between them. The website also says where Windows and macOS stand (not yet; Nova web works on them).
- **Quieter Inbox:** a drive repair or clone that's refused, or one of Nova's own helper calls refusing something, no longer shows up as an alert.

## 0.7.2-alpha (server, Nova web, app and watch)
- **Home Assistant light** (Lighting → Automation): any light Home Assistant knows — a Govee strip, a Hue bulb — shows the server's status too: amber for a warning, red for something critical, then back exactly to how it was. Nova only changes it when the status changes, so your own switches and automations keep working. It needs your HA address and a long-lived access token (saving it needs your fingerprint).
- **Lights look right:** colors are gamma-corrected and brightness follows how bright it looks, so the status light's red stays red (no more pink at high brightness) and lower brightness really is dimmer. You may want to raise your brightness a little after updating.
- **Glass on Android:** popups now bend the real page behind them (cards, text) instead of only the wallpaper, every edge catches a little light, and the glass is cheaper to draw (no live blur) so scrolling stays smooth. Over a wallpaper, Nova dims it and thickens the glass automatically where text would be hard to read.
- **Popups and tips animate** in and out (Android and web).
- **Icon styles** show what each style looks like right in the list. The drive and help icons are clearer.
- **Rotating the phone** no longer jumps to the Inbox, and the default background keeps its glow in landscape. The fade at the bottom of the screen is lighter.
- **Running tasks** (repairs, clones, installs, backups) show up as live notifications right away, even with the app closed, and a repair or clone that's refused or fails is always in the Inbox.
- **Fixed:** a drive repair could fail as "not started" after it had run, when the repair tool printed text that wasn't valid UTF-8. Every tool's output is now read safely.

## 0.7.1-alpha (server, Nova web, app and watch)
- **Glass, Mica and Breeze, reworked** (the looks are renamed, without brand names). Glass now really bends the light behind dialogs, menus and notifications too, with a smooth edge, and gets alerts with capsule buttons, menus with checkmarks, grouped lists and switches in the same style. Mica gets its tinted base, content dialogs and Windows-style buttons and fields; Breeze its framed buttons and window-style dialogs. Every corner in a style now matches — favorites, tiles, chips and the navigation bar — and the Glass side bar sits where it should. Selectors slide between options.
- **Icon styles** (Appearance → Icons): tonal, tiles, circles, outline, symbols only or monochrome, for the icons throughout the app and Nova web.
- **Status graphs** hold still while you point at (or touch) a moment, so the value you're reading doesn't slide away. New graphs for **graphics (GPU)**, **swap** and **disk I/O**, on Status and as dashboard tiles.
- **Dashboard:** choose each tile's width, the number of columns, 12/24-hour time and seconds, text size, a black or wallpaper background, and plain tiles without glass.
- **Repair a drive** (a drive's page → Fix and copy): checks every filesystem on it and fixes what it can, mounts it back, then runs the drive's own quick test.
- **Clone a drive** onto another: an exact copy that grows into a bigger drive, or, onto a smaller drive, the files are copied if they fit. Copies from a failing drive skip the bad spots and say exactly how much couldn't be read. The copy gets new IDs, so the two are never mixed up. Both refuse a drive another program is using (like a copy already running).
- **Backups:** edit your own backup script from the app (Nova checks it before saving and keeps the old version next to it; saving needs your fingerprint), and **Nova's own settings** are picked by default for a new backup.
- **Effects check:** when a browser draws without the graphics card, can't blur, or the phone is in Battery Saver, Appearance says so and how to fix it — and lighter effects switch on by themselves.
- **Safer setup:** setup first checks this machine — systemd, ports already taken, the clock, free space, the network and Docker — in the terminal and in the browser, and after starting checks that Nova answers on the home address. `sudo nova-setup --check` runs just the checks.
- **Several servers on one Cloudflare account:** a name another Nova server uses is named as such (and never offered to take over), a tunnel that runs on two machines is refused before anything goes wrong, and changes to the tunnel and the login are checked and retried so two servers editing at once can't undo each other.
- Edges fade where a row scrolls on (apps in Menu, servers, drives) instead of cutting off.
- More of Nova is translated (about 57%).
- Security test: new checks for repair, clone and the backup script editor.

## 0.7.0-alpha (server, Nova web, app, watch and website)
- **Runs on more Linux:** besides Debian and Ubuntu, Nova now runs on Fedora, RHEL, Arch and openSUSE. Installing, searching for programs, updates and Free up use whichever package manager the server has (apt, dnf, pacman or zypper). Releases carry a tarball and a one-file **nova-installer.run**, and Nova updates itself from them on those systems.
- **Set up in a browser:** `sudo nova-setup --gui` opens the setup as a web page (on the server or any device at home) behind a one-time key, and ends with the code to pair your phone. The terminal setup is tidier too: six clear steps, and running it again asks which part you want to change.
- **New looks** (Appearance → Style): **Liquid Glass** (clear glass that really bends what's behind it at the edges), **Fluent** (Windows 11) and **Breeze** (KDE Plasma), with sliders for glass clarity and corner roundness. Plus **Reduce transparency** for solid surfaces everywhere.
- **Material You on the web:** surfaces are now tinted the way Android does it instead of washing over with the accent, and blue subtext follows your accent color.
- **App icon follows your color:** turn on **Match my accent color** and the launcher icon becomes a Material You tonal icon in your accent's color. Your own icon colors can now become the real app icon too (the closest of Nova's icons).
- **Plugins in the App store:** a community list (reviewed ones marked), and install from a link or a file. Before installing you see every app and the exact command each tool runs, and your fingerprint confirms it.
- **One Cloudflare token for several servers:** what each server adds is signed with its name, each only takes off its own, and you can see what your other servers added. **Show the token** (fingerprint) to copy it to another server.
- **Drives:** a drive that's mounted and working no longer stays "not responding", one that's just unmounted shows as "Not mounted" instead of missing, and a drive with read/write errors is flagged. Storage shows each drive's estimated health and powered-on time, and quick tests fill in SMART details on USB drives too. A failing drive's light on the server picture pulses red.
- **Server picture:** tap the drive lights for Storage, the fan for Lighting and the power button for the power menu. Light strips show in the two bars.
- **Inbox:** tap an event's text to read all of it, and **Select all** turns into **Select none**.
- **Files:** your drives are one tap away at the top.
- **Android terminal:** colors, lines that wrap where the screen ends, line and screen clearing, and full-screen programs like top and nano.
- **Languages (preview):** Spanish, German, French, Portuguese, Italian, Russian, Chinese, Japanese and Korean in Nova web, the app (Appearance → Language) and the website. About 40% of the text is translated so far, by machine; the rest stays English for now.
- Menus that open something show an arrow instead of a radio button, the status light pulses more slowly, spinners turn smoothly, and Free up's memory step no longer stalls the server.
- **Fixed:** "Data backup failed" now says why, and a backup no longer crashes on a drive with errors.
- Security test: 495 checks, now including plugin installs (bad links, links to the server itself, zip path tricks, changed checksums) and showing the Cloudflare token.

## 0.6.8-alpha (server)
- **Let its phone app in, safer:** if making the key fails halfway (for example, your Cloudflare token doesn't have the new permission yet), the address goes offline instead of losing its route, and an older key keeps working. Pick who can open it again to bring it back.
- **Free up:** the Trash is no longer ticked by default (those files are gone for good), and Nova's own audit and alert history are never removed.

## 0.6.7-alpha (server, Nova web, app and website)
- **Free up** (Menu → Server, the Quick panel, or search): Nova measures what it can safely remove and you pick. That covers container images no app uses, Docker's build cache, downloaded package files, old system logs, rotated logs and Nova's leftovers, and your Trash, plus memory the system can let go of. Also offered, but unticked: the rollback copies Nova keeps of updated apps (often several GB), packages nothing needs and older backup snapshots. Your files, apps' data, containers and volumes are never touched.
- **Status graphs:** point at one (or drag along it on a phone) to see the value at that moment, and tap one to open it large, live.
- **Make an app public, and it works:** Home Assistant (“400: Bad Request”) and Homepage (“Host validation failed”) need one setting before they work at a public address. Nova now notices, shows exactly what it would change, and **Fix it** does it, with a backup of the file next to it.
- **Let an app's own phone app in:** apps like Immich can't do Cloudflare's web login. **Let its phone app in** (Who can open it) makes a key that opens only that address. You paste it into the app as two headers (Immich: Settings → Advanced → Custom proxy headers). Make a new one or remove it any time. Your Cloudflare token needs the Access: Service Tokens · Edit permission for this.
- **Share proxy:** it now shows its public address, and tapping it explains that it only opens share links instead of showing an empty page.
- **Colors and wallpaper** (Appearance, web and app): your own accent color for this device (Material You builds its colors from it), a **Color strength** slider, and a **wallpaper** behind every page with **Blur** and **Dim** sliders.
- **Shutting down:** the lights ease into white, then breathe slowly while they fade to dark.
- **Request a feature** in About, Questions and on the website.
- **Fixed:** in Nova web, the fan light's on/off and the status light switch turned the light off instead of toggling it. The server now also refuses anything but true/false for those switches.
- **Fixed:** “Drive not responding” now names the drive, like “Media — sdc (ata3)”.
- Security test: 465 checks.

## 0.6.6-alpha (server, Nova web, app and website)
- **Nova web: control your other servers from here.** Under Servers, pick a server and choose **Control from here**: Nova looks and works the same, only it's that server you see and change, with a **Controlling …** bar and a **Switch back** button always on screen. The first time, approve this browser on that server, once (on its phone, or `sudo nova approve CODE` there). That server checks your browser itself and asks its own phones for anything risky; this server only passes the requests along. Files, the terminal and speed tests open on that server's own Nova web. Both servers need Nova 0.6.6.
- **Home shows all your servers** in a row of small cards with their health and live numbers. Tap one to switch to it. You can move or hide the row in Edit Home.
- **Inbox → Ignored:** alerts you ignored that are still going on now sit in a folded **Ignored** section, with **Stop ignoring** to make one an active alert again. On the phone and the web.
- **Questions** (About, the setup guide or search): is it safe, what Nova connects to, and how to undo or uninstall everything. The same answers are on the website.
- **Website:** switching between light and dark fades smoothly, and the phone picture switches between Nova's light and dark looks too. The FAQ has more answers about safety and undoing things.
- **Nova web:** the Home button has a home icon again.
- **Security:** the pentest has grown to cover the new parts: controlling another server (forged, replayed and tampered requests, path tricks, lockouts), who-can-open and share-link addresses, lights, OpenRGB, Stop ignoring and large requests. A bad share-link address is now refused straight away.

## 0.6.5-alpha (app, watch, server and website)
- **App:** everything from 0.6.3 and 0.6.4 comes to the phone too —
  - **Lighter effects** (Appearance → Lighter effects: Automatic · On · Off): no live glass blur, smaller shadows, the server picture and live graphs at half the frame rate. Automatic turns it on for older or low-memory phones.
  - **Long notes fold** to two lines with the small arrow (instructions, explanations — about 40 of them).
  - **As many tabs as you like** in the navigation pill (up to 12): it slides with the edges fading, the next tab peeks in so you can tell there's more, the glass bead follows, and the tab you're on scrolls into view. Same in the side pill on tablets and in landscape.
  - **The share address's login is your choice** (Anyone with a link · Only people you add · Only you).
- **Notifications:** browser pop-ups now have the same three levels as the phone and Discord (Everything · Warnings and critical · Critical only).
- **Website and Nova web:** a **Get the Android app** button (and the Wear OS app) that always downloads the newest release.

## 0.6.4-alpha (server and Nova web)
- **A drive that stops responding is now a critical alert** (“Drive not responding: Cold storage”), not just a string of “disk errors” events: Nova checks that each drive can actually be read, not only that it's mounted.
- **Nova web: Lighter effects** (Appearance): no frosted-glass blur or soft shadows, quicker transitions — much smoother on older computers. *Automatic* turns it on by itself when the device looks slow (it measures the frame rate once).
- **Nova web: long notes fold** to two lines with a small arrow to open them.
- **Nova web: the navigation pill takes as many tabs as you like** (up to 12); when they don't fit it slides, with the edges fading, and the tab you're on scrolls into view.
- The phone app's side of these comes with the next app release.

## 0.6.3-alpha (app and server)
- **Share address: your choice of who can open it.** It stays **Anyone with a link** by default (no Cloudflare login — the app's own password and expiry protect each link), or switch it to **Only people you add** (they get a one-time code by email first, then the link's password) or **Only you** — and back, any time. Tap the share address in Labs → Cloudflare.
- Taking the share address out of Nova only removes a login Nova added; your own route and DNS record stay, so links keep working.

## 0.6.2-alpha (app and server)
- **Fixed: Share links setup failed** (“takes 1 positional argument but 2 were given”) while downloading the share proxy.
- **Fixed: the status light** showed in the app right away but only reached the fan at the next once-a-minute check (and turning it off waited the same way). It now applies the moment you switch it, and the Home picture shows the status color too.
- **Cloudflare changes show what's happening:** a spinner and “Switching to you and guests… this can take up to a minute”, and a second tap does nothing until it's done (app and web) — including Take it off and Share links.
- Share links: a tip that Immich's own password, expiry date and custom links (like /s/family-trip) all work at the share address.

## 0.6.1-alpha (app, server and watch)
- **Who can open it** (Labs → Cloudflare → tap an address, or an app's *Open it from anywhere*): **Only you**, **You and guests** (add people by email — they sign in with a one-time code and can open only that app; choose how long they stay signed in), or **Anyone**. Change it any time; every change asks for your fingerprint, and switching logins never leaves the address open in between (if something fails it goes offline, never public).
- **Share links for Immich:** keep Immich behind the login and still send albums to family — Nova installs a read-only share proxy and puts it on a share address (or uses yours if it already points there), then shows exactly which Immich setting to change and checks it. Works the same from the web.
- **Your own icon colors:** Settings → App icon → *Your own colors* — pick the background and symbol color and add that Nova icon to your home screen.
- **Phone in landscape:** the status bar steps aside, titles and padding shrink, the server picture fits, Menu shows its tiles four across.
- **Dock:** hold and slide an app to move it (hold and let go still opens its menu).
- **Favorites are Menu tiles** now, right after the live ones.
- **Settings → Automation:** the crash-loop guard, weekly updates, old-image cleanup and Wake-on-LAN graduate from Labs (same switches). Labs keeps the experimental ones.
- **Lighting:** a schedule can be for any light (or all of them), and the status light can show on whichever light you pick.
- **After a container update,** Nova also checks that its web page answers again, and tells you if it doesn't within 2 minutes.
- **Watch:** a *Nova status* complication for your watch face (status word or a CPU ring).
- **Android 9:** downloads in web apps work (Nova asks for storage access the first time).
- Fixed: picking *Hard shake* showed *Normal*. Cloudflare token steps fold away once you're connected.

## 0.6.0-alpha (app, server and watch)
- **Cloudflare, safely:** Nova keeps a record of exactly what it adds for each address, and only ever removes those parts — your own routes, DNS records and Access settings are never touched. Every other tunnel rule stays, in order; a setup that fails halfway is rolled back.
- **Address already in use?** *Set it up for me* never overwrites it: it offers a free name nearby (like `photos-nova.example.com`), or — for an address already on your tunnel but open to anyone — **Protect it**, which puts just the Cloudflare login in front of it (route and DNS record stay). It warns that the app's own phone app then needs an Access service token.
- **Taking things off:** Labs → Cloudflare shows which addresses Nova added (or put the login on) and lets you remove exactly those, with your fingerprint. `sudo nova cloudflare list` / `undo` shows or takes back everything (it asks first); uninstalling leaves your account as it is and tells you about that command. Addresses an older Nova published are recognized too.
- **OpenRGB (Labs):** run your lights through OpenRGB, so every Nova effect works on any device it supports (motherboards, RAM, GPUs, fans, strips). Point Nova at OpenRGB's SDK server, look for devices, then add each one as a light in Lighting. Nova stops talking to the motherboard's controller directly while it's on.
- **Dock:** hold an app to move it left or right, pin or unpin it, reload it or close it. The glass bead rides along with the app you're in as the dock scrolls (and fades as it slides out of view), and moves smoothly — it no longer redraws the whole bar each frame. The dock slides away and back on pages without it, instead of blinking.
- **Web apps:** a ⋮ menu with *Open in your browser*, *Copy the link*, *Desktop site* (remembered per app) and text size; full-screen video works.
- **Watch:** a *Nova lights* tile (tap to switch them), and a Lights card in the app. A watch may only switch the lights on or off.
- **Store:** category chips to browse the containers; an installed app's page links straight to its container (logs, restart, troubleshoot, roll back) and to updates.
- Toasts show above dialogs. The same dialog can't open twice, and a second shake no longer stacks another Report screen. Closing *Open it from anywhere* closes the edit dialog behind it too.
- **Hard shake works** (shakes are measured faster, and the levels are reachable).
- Store descriptions (and a few others) that get cut off have the little arrow to expand them.
- **A removed phone goes quiet:** it checks in every 5 minutes instead of every 4 seconds, and the server counts a removed phone against its address once a minute — so it can't lock out the other devices on that address.
- Dialog buttons wrap onto a new line instead of squeezing when there are three long ones.

## 0.5.19-alpha (app and server)
- **Web apps render properly inside Nova:** pages got a zero-height viewport, so full-height backgrounds stopped short (Immich), lazy photo grids drew nothing and fixed wallpapers (Homepage) went missing. They now fill the frame like in a browser.
- **Uploads and downloads in web apps:** an app's upload button opens your phone's file picker (pick one or several), and downloads go to Downloads with a notification. (Android 9: downloads aren't supported yet.)
- **The dock's glass bead follows you into apps:** it glides from a tab to the app you open (even in a scrolled dock) and back, instead of jumping; on an app it becomes a circle.
- **More apps this way:** small arrows on the dock's edges when it can scroll, and a longer fade.
- **Tap the page you're on** in the navigation pill to glide back to the top.
- **Shake settings where you need them:** turn shaking off, pick what it does, and choose how hard you have to shake (Hard · Normal · Light), in Appearance and on the Report screen — at the top when a shake opened it, in case it was an accident.
- **Cloudflare token steps, corrected:** step-by-step instructions, now including **Zone · Zone · Read** (needed to find your domain) and the newer name of the Tunnel permission. Tokens made under *Manage Account → API Tokens* work too.

## 0.5.18-alpha (app)
- **The dock slides:** when more apps are open or pinned than fit in the navigation pill, they scroll sideways, with the edges fading; the one you're in scrolls into view.
- **Round, centered highlight** on the app you're in (and a round ring in the Material look).
- **Pin Files and Terminal to the dock:** hold them in Apps or the Menu. Up to 8 pinned apps now.
- Button text in dialogs is centered (two-line labels like “Command or script” no longer sit off to one side).

## 0.5.17-alpha (app and server)
- **Android 9 and newer** (was 12). On 9–11, Material uses its own palette instead of wallpaper colors, and on 9 fingerprint confirmation can't fall back to your PIN; everything else is the same.
- **Updates on open:** when Nova opens it checks for a newer app and server release and shows a small note on Home (close it to hide it until the next one). The server checks GitHub at most every 30 minutes, however often you open the app.
- **Menu, reorganized:** four live tiles (containers, storage, lighting, Inbox), your **apps** right there (with Files, Terminal and “All apps”), favorites, then Server · Apps & tools · Alerts · You. Same on the web.
- **Plugins:** add apps and one-tap tools with a small plugin.json — `sudo nova plugin add FOLDER` shows what it adds and asks first; its tools run as your normal account, never root; switch each on or off in **Settings → Plugins**. An example (*System tools*: temperatures, disk space, busiest processes, who's logged in) is in `plugins/examples`. See docs/PLUGINS.md.
- **Files and Terminal open like your apps:** in the dock, with their own spot and a close button.
- **Shake to…** report a problem (new screen: saved on your server, or opened as a GitHub issue), search, the quick panel, the Inbox, lights on/off, Home — or nothing (Appearance → Shake).
- **Favorites** can be a page in the navigation pill.
- **Start page:** hold an app to edit it, **+** to add one, your own search engine, and suggestions on/off.
- Swipe an alert in *Needs attention* either way to ignore it.
- Security test: 309 checks, all passing.

## 0.5.16-alpha (app and server)
- **App store, reworked:** *Containers* and *Programs*, each with a search box. Containers: 51 ready-made apps (Plex, Home Assistant, Nextcloud, Paperless-ngx, Immich-style photo apps, the *arr apps, Open WebUI + Ollama, Stirling PDF, AdGuard Home and more), an **Editor's choice**, and a **Docker Hub search** for anything else — a result opens *Your own container* with the image filled in. Programs: our picks, and typing searches everything apt offers.
- **Works without Docker:** the store and Containers say when Docker isn't there and offer **Install Docker** (with Compose). The package no longer pulls Docker in by itself.
- **Fresh installs:** `nova-setup` offers to install Docker, Tailscale and cloudflared if they're missing (each optional; with a Cloudflare tunnel token if you have one).
- **Settings → Features:** switch off parts of Nova — **Nova controls the lights** (off: Nova leaves the RGB controller alone for the board maker's software or OpenRGB), the server terminal, the file manager, and being found on the network.
- **Swipe between pages:** on a page from the navigation pill, swipe left or right for the next one. It leaves sliders, chip rows, swipe-to-archive rows and scrolling alone, and ignores the screen edges (system back).
- **Tailscale:** the app learns your server's Tailscale address and uses it when home Wi-Fi isn't reachable (before Cloudflare); apps open at the Tailscale address then too.
- **Cloudflare auto-setup on the phone:** Labs → Cloudflare to add the API token, then hold an app → *Open it from anywhere* → **Set it up for me**.
- Search finds the Labs features (Cloudflare, crash-loop guard, weekly updates, image cleanup, Wake-on-LAN), Features, app icons and more.
- The Home fan spins a little slower with motion blur, so it looks smooth at 30 fps.
- The Automatic look's description no longer mentions phone makers.
- Security test: 303 checks, all passing.

## 0.5.15-alpha (watch and server)
- **Watch: a screen lock is now required** to pair and to approve. Without one a watch counts as always unlocked, so anyone holding it could have approved things that need your fingerprint on the phone. Status, events and the tile still work without a lock.
- **Watch:** if the server stops recognizing it (removed on the phone, or the watch's clock is off), the watch says so and keeps its pairing until you choose *Pair again* — it no longer forgets it by itself. An address handed to the app at launch is offered as a button instead of being used right away.
- **Weekly container updates** now count the size of the image about to be downloaded: an update only starts if the drive keeps at least ~8 GB (or 8%) free after it, otherwise it stops and tells you.

## 0.5.14-alpha (app, watch and server)
- **Nova for Wear OS** (new, `nova-wear-0.5.14-alpha.apk`): your server's health at a glance (status, CPU temperature, memory, containers, anything running), recent events, and anything waiting for your approval — approve or deny on your wrist. Plus a **status tile**. The watch finds the server on your Wi-Fi (or type an address, e.g. Tailscale), shows a code, and you approve it on your phone (Users & devices → *Approve a browser or watch*), comparing the certificate it shows. Its key stays in the watch and works only while the watch is unlocked; the server lets it read status and approve, nothing else. (0.5.15 adds a required screen lock — use that.) See docs/WEAR.md.
- Security test: 294 checks, all passing (new: the watch's narrow access).

## 0.5.13-alpha (app and server)
- **Lighting for more than one light:** Lighting → *Your lights* lists what's plugged in where — a fan, an LED strip or something else — and *Add a light* puts another one on a free header (the board's second ARGB header, or its 12 V RGB header). Each light has its own effect, color, brightness and LED count; pick one with the chips at the top of Lighting. A 12 V RGB header only offers the one-color effects. Presets, schedules, the status light and the Home picture stay with the main light for now. The whole controller is set in one pass, so changing one light never resets another, and the animator draws every moving effect together. Only the first ARGB header has been tried on real hardware so far; the others are marked “untested”.
- **Weekly container updates check the disk first:** before each download Nova needs about 8 GB (or 8% of the drive) free, and stops with an alert instead of filling the drive. Each update now also removes the version from two updates ago, so only one old version is kept per container.
- Finished background tasks no longer post to Discord (they're in the Inbox and on your phone); failed ones still do.
- Tapping a notification opens the right screen again with a custom app icon.
- Security test: 278 checks, all passing.

## 0.5.12-alpha (app and server)
- **Security fixes** (found by trying to break Nova):
  - Root no longer writes through files the service account could have tampered with. Several root tools (the root helper, updates, Labs, `nova`, `nova-update`, `nova-setup`, `nova-app-publish`) wrote into Nova's own folders by following paths; if the API were ever compromised, a planted symlink could have made root overwrite, or hand over, a system file. They now write with `safeio` (no symlinks followed, unpredictable temp files, ownership set on the open file).
  - A negative `Content-Length` made the server read a request body with no size limit, before authentication. Odd lengths and chunked bodies are now refused at once, and absurdly nested JSON gets a 400.
  - Browser pairing requests are limited per address, so one device can't fill the queue.
  - Security test: 270 checks, all passing (new: HTTP parsing).
- **App icon** (Settings → Appearance): Default, Material You (follows your wallpaper's colors), Dark, Outline, Outline light, Black on white, White on black, Glass, and Blue, Green, Orange, Red, Pink.
- **Long descriptions** that used to end in “…” now have a small arrow to open the rest.
- **Smoother Inbox:** starting a selection is one shared animation instead of every row re-laying itself out each frame, and the Inbox shows the newest 60 events with *Show older* below.
- **Battery:** the server picture on Home and the live graphs draw at 30 fps instead of every frame of a 120 Hz screen, and the background alert listener keeps one connection open instead of a new TLS handshake every minute.
- Docs: README, SECURITY and ROADMAP brought up to date.

## 0.5.11-alpha (app and server)
- **Inbox → In progress:** a task slides in the first time you see it and then stays put (no more jumping in every time you open the Inbox). Several can run at once, with a count. When one finishes it shows *Done* for a moment and becomes an ordinary Inbox event within seconds — swipe or archive it like any other. Finished ones can also be swiped away (or dismissed with ×) right away.
- A task that ends with an error, or is cut short by a restart, is now an Inbox event too.
- **Home** shows a small spinning note while something runs in the background (“Install Jellyfin · 42%”, or “2 in progress”); tap it for the Inbox.
- **Files (app):** going back from one folder to another just slides the list — no page drag.
- Security test: 265 checks, all passing.

## 0.5.10-alpha (app and server)
- **Inbox:** swipe an event **either way** to archive it, and the ones below slide up into its place. **Hold a day's heading** (or *Select day*) to select that whole day. **Archive → Back to Inbox** puts an event back (swipe it or tap it).
- **Containers that won't stay up:** *Stop* is always there, even while a container is restarting over and over — and it stays stopped (its automatic restarts are off until you start it again). A **Troubleshoot** card shows the last error from its logs and the fixes that make sense: stop it, **go back to the previous version**, update it again, recreate it, or read its logs. Nova now finds the version a container ran before its last update even when the update left it nameless.
- Container updates keep the version they replaced (for *Go back*), and an update that finds nothing new no longer replaces it.
- **Labs** has four more: **Crash-loop guard** (a container that restarts 5 times in 10 minutes is stopped and you're told), **Weekly container updates** (pick the day and time; anything that won't start on its new version is put back), **Clean up old images** (see what nothing uses anymore and remove it — the versions kept for rolling back stay; cleaning up asks for your fingerprint), and **Wake-on-LAN** (turn on other computers on your network).
- **Files:** going into or out of a folder slides only the list; the title, path and buttons stay put.
- The boot drive's free space is right again (it showed the small EFI partition's numbers).
- Material You toasts; a soft fade at the bottom of the screen like the one at the top; *Add another server* has a back arrow at the top.
- Security test: 265 checks, all passing.

## 0.5.9-alpha.2 (server)
- **Security fix — file changes from a browser now need your phone.** In 0.5.9 a paired browser could change files as your normal account without approval. Because that account can be as powerful as root (it may use sudo or Docker, and files like `~/.ssh/authorized_keys` or `~/.bashrc` control how it logs in), a browser's first change — upload, save, new, rename, move, copy or delete — is now held for approval on your phone, and then allowed for 15 minutes. Reading and downloading are unchanged; phones are unchanged.
- The approval for the server terminal now says plainly that it's full control when the account can use sudo or Docker.
- The phone's progress notification refreshes every 20 seconds instead of every 4 (less battery during long backups).
- Security test: 247 checks, all passing.

## 0.5.9-alpha (app and server)
- **Server terminal in the browser:** Terminal → *Server terminal* (also in Apps). A real terminal — colors, nano, htop, less — as your normal account on the server, never root; `sudo` asks your password as usual. Opening one is approved on your phone (or with `sudo nova approve`), announced in the Inbox, and closes after 10 minutes unused.
- **Files** (app and web, also in Apps and Menu): browse the server as your normal account; open and edit text files; upload (drag and drop on the web, any size, in chunks), download, new files and folders, rename, move, copy; delete goes to the server's Trash so it can be undone. It can only do what your account can — root-only files stay out of reach.
- **Install progress you can come back to:** store installs, program installs and your own containers run as background tasks with real progress (image layers, apt's own percentage). Leave the page and come back — it picks up where it is. The **Inbox shows what's running**, live, and the phone shows a progress notification.
- **Any program from apt:** Store → Programs → *Find any program*: search your package manager and install or remove (fingerprint). Nova won't remove what the server needs to run.
- **Updates:** check boxes for every package and container, all selected to begin with, with *Select all / Select none*. **Container updates now check that the container comes back up** — if the new version won't start, Nova puts the previous one back and tells you.
- **Command apps:** Apps → Add → *Command or script*: a saved command you run with one tap (as your normal account), with its output shown live and *Run it again*. Saving one needs your fingerprint.
- **Labs** (Settings → Labs): experimental features, off until you turn them on. First one: **Cloudflare auto-setup** — with a Cloudflare API token, *Set it up for me* in an app's *From anywhere* puts it on your domain: Nova protects the address with your Access login first, then adds the DNS record and the tunnel route, after showing you exactly what it'll change.
- Security test: 240 checks, all passing.

## 0.5.8-alpha (app and server)
- **Inbox on the phone:** grouped as Today, Yesterday and then by date; **hold** an event to start selecting, tap to add more, **Select all** / **Clear**, and **Archive** the selection in one go (back stops selecting). The new-alert banner goes away when you open the Inbox.
- **Add your own container:** Containers → **+** → *Your own*: name, image, ports, folders (with a folder browser), settings and restart policy — Nova checks everything and shows the compose file it will run (*Preview*). For safety it can't be privileged, use the host network or devices, or reach system folders or the Docker socket; your files can be shared from /mnt, /srv, /media or /home. Adding one needs your fingerprint. Containers you added this way can be removed from their page (their folder is kept in /opt/.nova-uninstalled).
- **Apps:** an **Add** tile at the end of the grid (app and web), for any link on your network.
- Security test: 216 checks, all passing.

## 0.5.7-alpha (app and server)
- **Servers (Nova web):** see all your Nova servers on one page — health, CPU, memory, containers, uptime — and add more with **+** (the server's address, then the code from `sudo nova add` on it, after checking its certificate). This server reads the others through a special read-only pairing: it can see their overview, live numbers and events, and nothing else. "Open its Nova web" takes you to that server to manage it. Adding or removing a server is approved with your fingerprint (or `sudo nova approve`).
- **Apps from anywhere:** hold an app → *Open it from anywhere* shows exactly what to add to your Cloudflare tunnel (address and service, with copy buttons), and checks that the app's remote link really asks for a Cloudflare login before anything reaches the app — and warns if it's open to the internet.
- Away from home is now decided by the server (Cloudflare or not), so apps open directly at home and over Tailscale without the "home network only" message.
- **Tablets:** pages slide the right way, the list pane opens and closes smoothly, predictive back follows your thumb, and one glow sits behind the side pill and both panes.
- `docs/ROADMAP.md`: a Windows version, an iOS app, Google Play, and what's next.
- Security test: 201 checks, all passing.

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
