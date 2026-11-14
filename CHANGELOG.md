# Changelog

## 0.9.27-alpha (server)
- **Fix: Nova web couldn't control another server ("only the four X-Nova signature headers").** Servers → pick another Nova server → every page failed with that message. Nova web marks each request with which app it is (`X-Nova-Client`, since 0.7.5), and the server carrying the request to the other one refused anything but the four signature headers. It now takes that one too (checked, and passed along). Only the server you open Nova web on needs this update. A new CI check signs requests exactly the way Nova web does and sends them through the relay.

## 0.9.26-alpha (server and app)
- **The Terminal works with a Windows server.** It said "SSH isn't running on the server" (0.9.25) or "Couldn't load the server's details" (before) on a PC with OpenSSH Server running, and adding the phone's key was refused: Nova only knew Linux's SSH. Now it reads Windows' host keys (`%ProgramData%\ssh`), signs in as whoever is signed in at the PC (or `ssh_user` from the config), and puts the phone's key where Windows' sshd looks for it — the shared administrators' key file for an administrator, `%USERPROFILE%\.ssh\authorized_keys` otherwise — with the strict permissions sshd insists on. Removing the phone removes the key. Without OpenSSH Server the Terminal page says how to turn it on. CI now logs in over real SSH on Windows with a phone-style key.

## 0.9.25-alpha (server and app)
- **Fingerprint confirmation without a home network.** It could only be set up on home Wi-Fi (or in a phone's first 30 minutes after pairing from away), so a phone paired with a computer that's wired straight to the internet — no router, no home network — never got it, and every risky action waited for some other approval. Now Tailscale counts as home for this (it's your own private network): the app sets it up by itself there. From anywhere else, **Settings → This phone → Fingerprint confirmation → Set it up now** asks the server to approve it, with a code — `sudo nova approve CODE` there, **Nova Server → Waiting for approval**, another admin phone, or an authenticator code — so a stolen phone away from home still can't give itself a fingerprint key.
- **Fix: the Terminal couldn't connect over Tailscale ("Can't reach the server here") while the rest of the app could.** Its list of SSH addresses was saved once, during setup: one where Tailscale was turned on later, or the home address changed, kept sending the Terminal to an address that wasn't there. The server now works the list out from the addresses Nova itself is using (anything added to `ssh_hosts` by hand stays), and **Automatic** first tries the address the app is reaching Nova on right now, then each of the others. Clearer messages too: "SSH isn't running on the server" when it has no SSH host keys.
- **Fix: the frame-rate meter (Developer options) could say "idle" while the app was drawing.** When the meter was recreated (turning the screen, coming back to the app), the old one switched the counter off under the new one. It counts who's watching now, and checks itself every couple of seconds.

## 0.9.24-alpha (server and app)
A phone that stayed "Disconnected" from a desktop it had paired with over Tailscale, and two bugs that stopped `sudo nova update` there.

**The app**
- **Fix: a phone could stay "Disconnected" forever without trying a single address.** A server whose remote address was typed without `https://` (`nova.example.com`) handed it out that way; the app then refused the whole pairing as "plain HTTP" — before trying the home, Tailscale or Cloudflare address at all, so the server never even saw it. The app now reads such an address as https, and skips (rather than refuses everything for) any address that isn't. A phone stuck this way reconnects by itself once it has 0.9.24 — no pairing again.
- **"Can't reach your server" → Try another address:** type the server's Tailscale address (`sudo nova status` shows it) or its new home address; the app takes it only if the server's certificate matches the one it paired with. For a phone that never learned the Tailscale address (paired with an app before 0.9.20) or a server that moved.
- **When nothing answers and there's no Tailscale route, the app looks the server's name up over Tailscale** (MagicDNS) and tries its Tailscale address, certificate-checked.
- **Reached over Tailscale or Cloudflare, the app takes the server's current home address** — so after `sudo nova setup` → Network on the server, phones follow by themselves.
- Something other than Nova answering an address (a website on the same domain, another program on the port) counts as "not reachable" and the app tries the next way in, instead of stopping on "HTTP 404"; "Can't reach your server" lists what each way in said.

**The server**
- A remote address saved without `https://` is handed out with it (and `sudo nova setup` adds it when you type one without).
- **Fix: updating from the tarball failed on Python 3.12 and newer** ("is a link to an absolute path"): the server tarball had one absolute link (`/usr/sbin/nova-alert`), which the updater's safe unpacking refuses. Nothing was installed and the old version kept running. The link is relative now, and a test unpacks every tarball exactly the way the updater does.
- **Fix: a server first installed from the tarball and later from the .deb updated the wrong way.** The tarball install's marker stayed behind, so `sudo nova update` (and `sudo nova uninstall`) treated a .deb install as a tarball one. Now what the package manager owns decides; installing the .deb removes the leftover marker; and the tarball's installer, run over a .deb install, no longer claims it.
- **Already hit this?** On a Debian/Ubuntu server: `sudo rm /usr/lib/nova-api/INSTALLED_FROM_TARBALL`, then `sudo nova update` as usual. (Without that, an updater from 0.9.23 or older still picks the tarball — which now installs, after which the next update switches back to the package.)

## 0.9.23-alpha (server)
Windows' Nova Server window works again, and installing and updating Nova on Windows is smoother.

**Windows**
- **Fix: Nova Server closed as soon as it opened** ("Nova Server has closed — open it again"). Started from the Start menu it runs without a console, and the `nova` command it loads asked that missing console whether it was a terminal — so every request failed, and the window took that for having closed. It works now; the test on real Windows starts it exactly the way the Start menu does; and a real problem shows as one (the error, with **Try again**), not as "closed".
- **After installing, Nova Server opens by itself** with a **Get started** card: a QR code to get the app on your phone, then **Pair a phone or tablet**. The installer also says whether it installs or updates (from which version), points to Nova web's real address (https, port 8495, and https://localhost:8495 on the PC), and keeps its window open until you've read it.
- **Update Nova from Windows:** Nova Server → **Check for updates** → **Update now**, or `nova update` in an administrator terminal. It downloads the newest release, checks its signature against Nova's release key (as Linux always has) and runs its installer in a window of its own, which opens Nova Server again when it's done.
- **The one-line install asks for administrator permission itself** instead of telling you to start over in an administrator PowerShell; removing Nova (`uninstall.ps1`) does the same.
- **Nothing of Nova's opens a console window**: its three background parts run as services, and everything the Nova Server window starts runs hidden. Its one-time key now travels in the address's query (some ways Windows opens an address drop the part after "#").

**For hub apps (Orbit) and scripts**
- `install.ps1` and `uninstall.ps1` never wait for a key when a program runs them, exit with 0 or 1, and end with a line to parse: `NOVA-RESULT {"ok":…,"version":…,"previous":…,"firstRun":…,"running":…,"devices":…,"ports":…,"urls":…}`.
- `status.json` also says whether setup is done, which ports and addresses Nova answers on, and the check-up's latest findings (refreshed at most every 10 minutes); on Windows every user can read it, as on Linux (`C:\ProgramData\Nova\alerts\www\status.json`).
- `nova status --json` and `nova doctor --json`.

**Linux**
- **Fix: "the last update check failed" after a restart.** The daily check catches up right after boot, often before Wi-Fi is connected; it failed and stayed failed. Now it waits a few minutes for the network and, if it's still offline, says so and looks again the next day. A check you start yourself still reports the problem.
- `sudo nova status` shows the address phones use and says when nothing is paired yet; running `sudo nova setup` again offers to pair *another* device instead of "your phone".

## 0.9.22-alpha (server and app)
Phones that couldn't reach a computer over Tailscale, and more ways for the Home picture to look like yours.

**Connecting over Tailscale**
- **Fix: Nova could start before Tailscale (or Wi-Fi) had its address and then never answer there.** At boot, if tailscaled or the network was slower than Nova, listening on that address failed once and Nova ran on without it — phones over Tailscale (or, on a server whose home address is its Tailscale one, every phone) couldn't connect until Nova was restarted. Now Nova keeps trying until the address is there.
- **Tailscale's "shields up"** ("Allow incoming connections" off) lets the computer reach its own Tailscale address but turns every other device away — exactly "it works on my PC, not on my phone". `sudo nova doctor` now says so (on Windows too), and setup's Tailscale step offers to turn it off.
- **`sudo nova doctor` checks Tailscale:** connected on this computer, the same address as at setup (reinstalling Tailscale can change it), and Nova answering there. `sudo nova status` says when it isn't connected.
- **Test each way in** (app: Settings → This phone → Connection): tries the home network, Tailscale and Cloudflare side by side and says how long each took or why it didn't get through — at home, it tells you whether Tailscale will work before you leave. The Connection row knows Tailscale now (it said "—").
- **Pairing over Tailscale is more patient:** a phone pairing at a Tailscale address waits as long as Tailscale needs (8 s instead of 3–4), tries that address once instead of twice, and if it can't get through says to check Tailscale on the phone. "Can't reach your server" calls a Tailscale address Tailscale (even when it's the server's "home" one), with the same hint, and only says Nova works at home only when there's really no other way in.
- **A server whose "home" address is its Tailscale one:** `sudo nova add --remote` works there now (it was refused — the Tailscale address *is* the way in), and once `sudo nova setup` → Network picks the real home address, a phone paired before moves to it by itself and keeps Tailscale for when you're away.
- **A VPN on the phone that hides the home network** (Tailscale with an exit node, without Allow LAN access): the app says so when it can't find or reach the server at home.
- **Changing only setup's Network (or Away from home) also opens the firewall** for the new network and for Tailscale devices (ufw/firewalld) — before, only a full setup did.
- The remote-access guide (REMOTE.md) starts with Tailscale now; `sudo nova help troubleshoot` covers "works at home, not away".

**The Home picture**
- **A sixth shape: a single-board computer** (a Raspberry Pi and the like) — the board from above with its pin header, ports and a fan on its chip. A board with no firmware chassis type but a device tree (most Pis) shows it until you pick.
- **Dark or light case.** Under the shapes, pick the case's color — black/gunmetal or white/silver — for every shape but the board. Every phone and browser of that server shows it.
- **Until you pick, a quiet "Doesn't look like your computer? Pick its look"** sits under the picture on Home (for admins; × hides it).
- **Settings → Server → Looks like:** still previews instead of live ones (lighter on slow tablets); cards on the web; a view-only device sees them without being offered to change them. Windows reads its chassis type too, so a Windows laptop shows the laptop. The picture works out its layout once per size and shape instead of on every frame.
- Server versions read 0.9.22-alpha everywhere (the update row said 0.9.21~alpha).

## 0.9.21-alpha (server and app)
- **Your server, as it looks.** The picture on Home now comes in five shapes — a **tower** (as before), a **mini PC**, a **NAS** with one tray per drive, a **rack server** and a **laptop**. Pick it in Settings → Server → Looks like, and every phone and browser of that server shows it. Until you pick, Nova goes by what the computer's firmware says it is (a laptop shows the laptop, a rack server the rack). Each one keeps what the tower has: the live light (on a laptop, its screen shows the ring and the keyboard glows), your LED strips, one LED per drive that blinks with real activity, the power LED in the status color — and tapping them still opens Lighting, Storage and the power menu.

## 0.9.20-alpha (server and app)
A phone that couldn't connect to a desktop running Nova and Tailscale, Nova web on the server itself, and approvals for hub apps.
- **Fix: a phone on Tailscale couldn't use Nova on a computer whose setup picked its Tailscale address as "home"** — the usual case on a desktop whose internet goes through Tailscale or a public Ethernet port. Nova counted only that one address as home, so other devices were turned away — unless setup's Tailscale question had been answered yes. Where it had, the app still tried that address with the 1.5 s patience of a home network, which a relayed Tailscale connection can miss: most likely why a phone could pair with `sudo nova add --remote` and then never connect. Now such a server accepts its tailnet's devices; the app gives that address Tailscale's patience; setup opens the firewall for Tailscale devices too (ufw/firewalld), and `sudo nova doctor` says when it isn't open.
- **Setup picks the right home address**: it looks past Tailscale, VPN, Docker and public interfaces for the computer's address on your Wi-Fi or Ethernet (10.x, 172.16–31.x, 192.168.x). Run again on a server that has a Tailscale address saved, `sudo nova setup` → Network says so and suggests the home one (Tailscale devices keep working).
- **Nova web on the server itself: https://localhost:8495.** It only answered at the network addresses before. `sudo nova status` and the Nova Server window list it.
- **For hub apps (Orbit):** the monitor's status file says how many browsers and actions wait for approval — counts only, never the codes.
- Updating a server set up before 0.9.17 that never hears about updates: `sudo nova update github:dash1101/Nova` once.

## 0.9.19-alpha (server and app)
A fix for something 0.9.18 broke, and what a brand-new phone and server showed when tried from scratch.
- **Fix: the Updates page always said "Nova is up to date · Installed: ?"** (since 0.9.18). It read Nova's version from the updater's printed line, which 0.9.18 reworded — so it also missed a new Nova release. It reads the updater's status file now, and a test on a fresh Ubuntu machine checks it. (Settings → Software update, the daily check and the Inbox notice weren't affected.)
- **Fix: a task that finished at the very moment Nova looked in on it** (an install, an update, a backup) could be marked "stopped unexpectedly" with a warning in the Inbox, though it had worked. Caught by the Windows test; it could happen on Linux too.
- **An old update error no longer lingers.** A server that updated from 0.9.16 kept showing "Last update failed: no update source" in Settings until the next check; an error from before the installed version is dropped.
- **One warning per drive.** "/home is 91% full" next to "The system drive is 91% full" — Fedora's and openSUSE's default btrfs puts both on the same disk — or the same disk mounted in several places, is now one recommendation, by its plainest name.
- **Nova in a container (LXC, Docker)** no longer says "still mounted, but its drive is gone" for every mounted folder: a container can't see the host's drives, so that check is skipped there.
- **A fresh server:** Containers says "No Docker yet" or "No containers yet" instead of "0 of 0 running"; the Menu's Inbox tile says "2 need attention" instead of "All caught up" while something is wrong; and an empty card reader (0 B) is shown as "No media" instead of being offered to be set up with your real drives.
- **Tested:** a new phone paired by typing the address and code, the released 0.9.18 installer installed, removed and purged on Arch, the Updates page against a 0.9.16 server (it now offers 0.9.18).

## 0.9.18-alpha (server and app)
A second pass the same night, on the first hour with Nova: installing it, setting it up, pairing a browser, updating and removing it — on Debian, Fedora and Arch.
- **Coming from 0.9.16 or earlier?** Setup used to leave "where updates come from" empty, which meant "never check", so those servers never hear about new releases. Run `sudo nova update github:dash1101/Nova` once (tested from 0.9.16: signature checked, settings and paired devices kept); after that Nova checks by itself. `manual` now means what setup says: no daily check, and `sudo nova update` at the server still installs the official release.
- **`sudo nova uninstall`** removes Nova the way it was installed (`--purge` takes its settings and paired devices too). Installs from the one-file installer on Fedora, Arch or openSUSE had no way to be removed at all — now they do.
- **Asking a browser's code again** (a reload, a second try) no longer locks that browser out for 10 minutes with "too many pending requests": the newest request replaces its oldest, still at most three waiting per address.
- **Setup in the terminal:** one line follows along while Docker and the drive tools install (the package manager's full output only if something goes wrong); the Terminal account defaults to the one person's account when setup runs as root, and a name that doesn't exist is caught; questions you can skip say so. **In a browser:** it suggests your account when started from the desktop's "Set up Nova", problems that stop setup are red, the summary fits a phone, and a failed run points to the lines *below*.
- **The installer for any Linux** says what to do when the package manager can't install what Nova needs (bring the system up to date first) instead of a bare error.
- **Updating reads like the rest of Nova:** versions show as 0.9.18-alpha (not 0.9.18~alpha), and `nova version` / `nova --version` answer.
- **Setup guide** (Nova web and the app): the one-line installer, any Linux with systemd or Windows, Tailscale first for away from home, and Copy buttons on web — in all nine languages.
- **Website on a phone:** the Windows one-liner no longer made the whole page wider than the screen, and the command list puts each explanation under its command.
- Smaller: roomier text on the browser approval screen; the FAQ's security-test count is up to date everywhere (650+); `sudo nova setup`, `sudo nova lighting`, `sudo nova publish` and `sudo nova alert` everywhere instead of the old dashed names; an unsynced clock is explained by what it actually affects now (authenticator codes, schedules, alert times).
- **Tested:** the security test on a fresh Ubuntu machine (617 of 618, including a new check that a new code replaces the oldest), Windows Server 2025 and 2022, the released one-line Windows installer, and installing and removing from the tarball on Fedora 41 and Arch.

## 0.9.17-alpha (server and app)
A pass of testing Nova the way a person uses it — every page of Nova web at phone and desktop size in all six looks, the Android app on an Android 11 phone and tablet, the `nova` command, a brand-new server, and the full security test — and fixing what turned up.
- **Fix: "This phone isn't authorized anymore. Pair again." when nothing was wrong.** When a network blip made a request go out twice, or a sleeping phone sent one late, the server rightly refused it — and the app told you to pair again (Nova web went further and forgot its pairing). Now the server says *why* a genuinely signed request was refused, the app, web and watch quietly sign it again, and a phone or computer whose clock is off simply works (Nova corrects for it, and says so if it's minutes off). These refusals no longer count toward locking an address out. A repeated *action* is never sent again — only a read.
- **Keep awake.** Power → **Keep awake** stops the server from going to sleep by itself — for an hour, 4, 12, a day, or until you stop it — on Linux and Windows. A desktop or laptop that sleeps can't be reached; now it's one tap (no fingerprint needed, view-only devices can't).
- **Windows: programs and updates.** On a Windows server, Programs browses a Windows catalog and searches, installs and removes programs through **winget**, and Updates lists what winget can upgrade and upgrades one or all — tested on real Windows machines. (On Windows Server 2019/2022, which don't come with winget, Nova says so.)
- **A brand-new server looks right.** No more "0/0 running" and "Services 0/0" on a server without containers (it shows how long it's been up), "No Docker" where Docker isn't installed, no Lighting controls or fan-light tiles on a server whose lighting was never set up (a light in Home Assistant still has its own Menu entry), and a proper "turn it on in Labs" card instead of a bare sentence on Labs pages that are off.
- **Updates are on by default.** A server whose setup left "where updates come from" empty used to never check for updates. Empty now means Nova's official signed releases (checked daily, never installed without you); `manual` turns checking off.
- **The `nova` command:** `nova log` and `nova backup list` read like sentences instead of raw data; `nova devices` lines up, says "today 20:16" or "3 weeks ago", and points out devices quiet for over a month; a wrong `nova approve` code answers at once instead of waiting 45 seconds; the version reads 0.9.17-alpha (not 0.9.17~alpha); `nova gui` works without sudo (it asks for your password itself); its guides fit Windows too.
- **Files, the terminal and command apps** use the account setup asked about ("Account the app's Terminal signs in as") — no more "set shell_user in config.json" on a machine with more than one account.
- **Nova Server window:** wraps properly in a narrow window, says when each device was last seen in words, and flags ones that have gone quiet.
- **App:** the server's name shrinks a little before it's cut short (tablets), widgets use the same name as the app and show uptime instead of "0/0 containers", the Quick-actions widget has no light button where there are no lights, "Back up now" says "None set up yet" instead of "Last:", quick tiles fit "Back up now" on a phone, a newly paired phone isn't warned about its own pairing, and the pairing screen's wording is clearer.
- **Web:** pages opened without saying which drive or backup go to the list instead of a page about "undefined"; quick tiles wrap to two lines.
- **Smaller fixes:** a single file mounted on its own (in a container or LXC) is no longer treated as a full drive or a missing one; audit-log lines are written in one piece.
- **Tested:** a new CI job installs the package on a fresh Ubuntu machine, sets it up unattended, checks it like a person would and runs the security test there (616 of 617 checks; the last needs a second network address). The full security test with the server-to-server checks: 661 of 666 in a container (the rest need systemd timers, an fstab and a second address, all covered on the Ubuntu machine). Windows: 93 checks on Windows Server 2025 (programs and updates among them) and 86 on 2022.

## 0.9.16-alpha (server and app)
- **Nova runs on Windows (preview).** The same server — not a second one — now installs on Windows 10/11 and Windows Server: unzip **nova-windows.zip**, double-click **Install Nova.cmd**, pair your phone. The app, the watch and Nova web work against it unchanged. What's there: pairing, devices and approvals; the encrypted home-network listener; live graphs (CPU, each core, memory, network, drive activity, graphics); the Inbox with alerts and Discord; **"why it went down"** from Windows' own event log (blue-screen stop codes explained in plain words, power loss recognized); low space, failing, worn and hot drives; stopped services; the task manager; restart, shut down and schedules; the drives list; Docker Desktop containers. What isn't yet (the apps hide these on a Windows server): programs and updates, the app store, files, the terminal, backups, the storage wizard, lighting. The full list, and how it's built, is in [docs/WINDOWS.md](docs/WINDOWS.md).
  - It keeps Nova's two halves: the part phones talk to runs as Windows' low-privilege "Local Service" account, and only a separate helper running as SYSTEM can manage the machine — reachable from that computer alone, with a key only Nova's accounts can read. Nova's settings folder is closed to ordinary users, and the firewall rule lets in the local subnet and Tailscale only.
  - Every change is installed on real Windows Server 2025 and 2022 machines and checked there (78 checks: pairing, signed requests, graphs, processes, drives, schedules in Task Scheduler, the Inbox, the `nova` command, who runs as what), then installed again over itself and uninstalled, which must leave nothing behind.
- **Nova Server: a window for servers with a screen.** Open **Nova Server** from the app menu (Linux) or the Start menu (Windows) — or run `sudo nova gui` — and do without the terminal: see that Nova is running and how the server is doing, **pair a phone with a QR code** and a live countdown, see and remove devices, approve a browser or an action that's waiting (or type its code), run the check-up with its fixes, start, stop or restart Nova, see where to reach it, send a test alert, and look at recent activity. It talks only to the computer it's on: the desktop asks for your password (or Windows shows its usual prompt) once, each request needs a one-time key that only that window has, and it closes itself a little after the window does.
- **Pairing codes no longer need an outside program.** Nova now draws its own QR codes (checked bit-for-bit against `qrencode` on 83 codes of every size it uses), so the Nova Server window shows one on any system.
- **App and web:** on a server that doesn't have backups, updates, Free up or diagnostics (a Windows one, for now), those rows are hidden instead of opening pages that can't load.
- For maintainers: the operating system is asked about in exactly one file, `server/api/plat.py` (paths through `plat.p()`, system facts through `plat.*`); Windows code lives in `winhost.py`, `winmon.py` and `server/windows/`. `packaging/build-win.sh` builds the zip, `release.sh` signs it with the rest, and `.github/workflows/windows.yml` is the real-Windows test.

## 0.9.15-alpha (server and app)
- **Nova tells you why the server went down.** After a restart that wasn't a normal shutdown, Nova reads what the system logged just before and puts it in your Inbox in plain words: a crash inside the system and which driver and program were involved (a graphics driver crashing under a video converter gets the fix too: turn off hardware acceleration in that app), a hardware error, overheating, a freeze, drive errors, running out of memory — or "nothing was logged", with what that usually means. It says when it stopped and came back, counts repeats (a second time in a week is marked critical), and `sudo nova doctor` mentions it. Nothing is reported for normal restarts, or for anything from before Nova was installed.
- **Widgets, finished.** A new page, **Menu → Home-screen widgets**, shows each of the five widgets as it looks right now and (on Android 8 and newer) adds one to your home screen with a tap. The home screen's own widget list now shows a picture of each widget instead of Nova's icon. Fixed while looking at them for real: the Stats widget cut off its small print and bars, and the Status widget its last line. Quick actions (Back up, Free RAM, the fan light, Refresh) are now handed to Android's background worker, so they also finish when the server is reached the long way round (Tailscale, Cloudflare), and the button says what it's doing meanwhile.
- **Fix: a rare crash when opening the app** ("Reading a state that was created after the snapshot was taken"), most likely on a slow device right at startup: a piece of shared state was first created by the network thread while the first page was being drawn. All of it is now created up front.
- **A quieter system log.** Nova's checks run every few seconds to a minute, and each pass wrote "Starting… / Finished…" lines — about four in every ten lines of the system log on a home server. Routine passes are now silent (errors are still logged), so the log keeps more history of everything else.
- For maintainers: `packaging/monitor_test.py` (the crash explainer, 31 checks), and `packaging/release.sh` refuses to finish a release that lacks a file others download by name.

## 0.9.14-alpha (app)
- **Home-screen widgets** — five of them, for the server you're on in the app (long-press your home screen → Widgets → Nova):
  - **Status**: all good or what needs attention, and how many containers are running.
  - **Stats**: CPU, memory, temperature and disk, with bars.
  - **Quick actions**: Back up now, Free RAM, fan light on/off, and Refresh — they run right from the home screen (anything that needs your fingerprint stays in the app).
  - **Inbox**: the newest three alerts and how many are new.
  - **Storage**: how full each drive is.
  They refresh every 15 minutes, whenever the app refreshes, and when you tap Refresh; tapping one opens the matching page.
- **Fix: a server paired over Tailscale showed "Disconnected".** The app kept only the server's home-network address from pairing, which a device on Tailscale can't reach, and threw away the Tailscale address that worked. It now keeps the address you paired at. (A device already paired this way: remove it and pair again once.)

## 0.9.13-alpha (app)
- **The Inbox no longer redoes itself for every batch of rows.** Version 0.9.12 built rows in batches by changing one counter on the page, which made Android re-run the whole page's code for every row already built — more work, not less. Each row now waits for its own turn, so the page is only built once; the date formats are made once instead of on every pass too.

## 0.9.12-alpha (app)
- **Heavy pages no longer freeze while they open.** The Inbox used to build all its rows (25 to 60) before showing anything; it now shows the first screenful at once and builds the rest over the next few frames. The Menu does the same with the sections below the screen. Each Inbox row is lighter to build too (no animation host or color animation per row unless it's actually leaving or you're using full animations). In testing, the heaviest frame when opening the Inbox fell from about 42 ms to about 24 ms, and the Menu's from about 24 ms to about 12 ms.
- Still the heaviest moment: the first time each page is opened after an update, when Android has to run its code for the first time. Letting the tablet sit idle on its charger once after updating lets Android compile Nova ahead of time (Nova ships a profile for that), which is the real cure.

## 0.9.11-alpha (app)
- **A frame meter that tells you where the time goes** (Developer options → Show frame rate). The old one asked for a new frame every refresh, so an idle screen always read 50–60 fps. It now listens to what Android timed for each frame Nova really drew: **"idle"** when nothing is moving, otherwise frames drawn per second, how long the app's own work took, how long the drawing took, and the slowest recent frame. **Copy a frame report** (right under it) puts the last few hundred frames, split into app and drawing time, on the clipboard for a bug report.
- Pages no longer make an extra copy of themselves for the frosted-glass blur on devices that never show it (older or slow devices, Material, Breeze, Reduce transparency).

## 0.9.10-alpha (server, Nova web and app)
- **A lot less lag on older tablets** (found by measuring a tablet-sized Android 7 emulator, frame by frame):
  - **The assistant's orb was making the whole screen redraw about 50 times a second, on every page** — working out and sorting 190 dots each time. It now allocates nothing per frame, draws in eight depth layers instead of sorting, and moves on one shared heartbeat with the server picture. An idle page (Inbox, Menu…) now redraws about 8 times a second instead of about 50 — and not at all with the assistant button turned off; Home about 13 (the fan and lights move there) instead of about 46.
  - **Switching pages:** the Inbox compiled a text pattern for every row, every time it was drawn (about eight per row, sixty rows) — now compiled once everywhere (about 40 places). It also opens with 25 rows (not 60) on a slow device, with "Show older" for the rest. On a slow device pages now cross-fade quickly instead of sliding, which repaints two whole pages for twenty frames. In testing, the slowest 1% of frames while switching pages went from 150 ms to 53 ms, and the heaviest frame opening the Inbox from 64 ms to 26 ms.
  - **Home:** the stat cards no longer animate for 600 ms every time a live number changes (every 3 seconds), the server picture draws its unchanging case once, works out each light effect once per beat (it used to work out every light's color for every light, twice), and keeps its fan blade between frames.
  - **The shake detector** reads the motion sensor on its own thread instead of waking the one that draws about 50 times a second.
  - **Android 7 and 8** run an app's own code uncompiled until the tablet is idle and charging; Nova now ships a profile of its own code so Android compiles it ahead of time (after an update, once the tablet has been idle on the charger for a while).
- **Windows on a tablet:** a **minimize** button (and Back, or tapping the app in the dock, minimizes the one in front, like on web); a minimized app waits in the dock and a tap brings it back; **the dock shows the app you're in** when its window is maximized (the tab underneath lets go), with a small dot on apps that have a window showing; a maximized window stays clear of the dock instead of tucking under it; choosing a tab while one is maximized puts it away instead of changing a page you can't see. The dock's divider line is centered in the Material look.
- **Dashboard mode (app and web):** **AMOLED black** — pure black, tiles with just an outline, softer whites, no glow, so most pixels stay off — and **Pixel shift**: how far the layout drifts every minute (off, small, medium, large), moving to evenly spread spots instead of cycling through a few. The assistant's orb and the page's edge fades no longer sit over the dashboard.

## 0.9.9-alpha (server and app)
- **Devices with no fingerprint can still do risky actions.** A phone or tablet with no fingerprint reader, no screen lock, or no fingerprint set up used to be stuck on anything that needs your fingerprint (stopping a container, a shell, inviting a phone…). It now shows a **Waiting for approval** box with a short code instead, and the action runs as soon as someone says yes, any of three ways:
  - on **another admin phone**: Nova → Approvals (it also arrives as a notification), confirmed with that phone's fingerprint;
  - on the server: `sudo nova approve CODE`;
  - with a code from your **authenticator app**, typed straight into the box (if you've set one up).
  The box finishes the action and shows its result, can be cancelled, and gives up after 10 minutes. A device can never approve its own request, and view-only devices still can't do risky actions at all. Browsers already worked this way.

## 0.9.8-alpha (server, Nova web, app and watch)
- **Settings when you're not connected to a server.** The pairing screen has a Settings button (top right): **update Nova** straight from its public releases (no server needed — handy when you're signed out and the app is out of date), plus Appearance, app lock, help and About.
- **Pair from anywhere.** A new phone or tablet no longer has to be on your home network:
  - **Sign in with Cloudflare** (pairing screen → Away from home): sign in with your usual Cloudflare login, and the app asks the server to let it in; you approve it on an admin phone (Users & devices → approve a browser, watch or tablet) or on the server with `sudo nova approve CODE`. No code to carry, and it works on any Android, including old tablets with no Tailscale.
  - **Setup codes for away from home:** `sudo nova add --remote` (or Users & devices → Invite a phone → Away from home) makes a QR and a paste-able `nova1:…` code that also carries the server's Tailscale address and the key to your Cloudflare door. The app tries your home network, then Tailscale, then Cloudflare. Codes still work once, for 10 minutes, and the server only accepts them from outside when you made them for that.
  - A phone paired from away can set up its fingerprint key right then (normally that only happens on home Wi-Fi).
- **Android 7 and older:** the app icon showed the Android robot (those versions can't read the adaptive icon); it now has a proper icon fallback.
- **Nova web on an old browser** says so, instead of showing a blank page.

## 0.9.7-alpha (server, Nova web, app and watch)
- **Access codes.** A new choice under **Who can open it** (an address you've put on your Cloudflare tunnel): **Anyone with a code**. Cloudflare's login comes off that address and a page asks for a code instead — a **random one** Nova makes (like K7QM-2XDF-9TPA-HW3R, shown once), or a **password you choose**. Each code is for one address, runs out after 1 hour, 1 day, 7 days, 30 days or when you end it, can be ended any time (everyone who used it is signed out right away), and shows how often it was used. Nothing else on your server is reachable through it, and too many wrong guesses lock a visitor out.
  - It works through a small new service, `nova-gate`, locked in about as tightly as a service can be. Switching an address to or from codes never leaves it open for a moment, and if a step fails everything goes back as it was. Making a code asks for your fingerprint; a password you choose never goes on a command line or in a log.
  - Any app with a public address can use it (Apps → hold an app → Who can open it) — web and app.
- **Security fix:** the action log (audit log) kept whatever you sent along with a request, so an API token or webhook entered in the app could end up readable there. Fields like tokens, passwords, webhooks and codes are now written as `<hidden>`. If you ever saved a Cloudflare token or Discord webhook through the app on an older version, make a new one and revoke the old (Cloudflare → My Profile → API Tokens).
- **Fixes:** `nova cloudflare undo` and taking an address off now put an address that went through the gate straight back to its app first.

## 0.9.6-alpha (app)
- **Swiping back works properly again:** opening Home or a tab page from search, a shortcut or the assistant switched to it instead of stacking it on the page you were on, so back showed the wrong page underneath (a second Home, or the page you'd come from) instead of leaving the app or going where you expected.

## 0.9.5-alpha (server, Nova web, app and watch)
- **The assistant on phones:** the text box stays at the bottom of the screen (above the keyboard) while the conversation scrolls, bubbles use the width of the screen, and Read aloud, Earlier conversations and Settings are buttons at the top instead of links under the box (app and web).
- **When the assistant finishes in the background** you get a notification with a short line, not a pop-up with everything it said.
- **Leaving the app stops Nova reading aloud** — it used to keep talking after you'd backed out.
- **Frost works on the Default look's cards and pop-ups:** they now really blur what's behind them (wallpaper, glow or living wallpaper) by the Frost amount on Android 12 and newer, and dialogs and the assistant's box are see-through and frosted like the cards.

## 0.9.4-alpha (server, Nova web, app and watch)
- **Task manager** (app and web; Menu, or search "task manager"): every process on the server with its CPU right now and its memory, like top — sort by CPU, memory or name, search, tap one for its details, and **End** or **Force stop** it with your fingerprint. The system's own core, kernel threads, Nova and SSH are refused.
- **Lumen is gone.** Fewer looks means fewer things that break: if you were using it, Nova switches you to the Default look.
- **Apps in windows on tablets:** apps open in windows over Nova, like on the web — drag them by the title bar, resize from the corner, maximize (double-tap the title bar) or close. Turn it off in Appearance → Home. Apps also use the whole width now instead of a phone-shaped column.
- **Side by side on wide screens** (web, Appearance → Home): pages you open from the Menu show next to it, like on a tablet.
- **The assistant:**
  - keeps working when you leave its page; if it finishes while you're elsewhere you get a notification (and a browser notification when the tab is hidden). Each visit starts on a clean page; **Earlier conversations** has everything you've asked, by day.
  - what you were typing is still there when you come back.
  - drag the orb anywhere; drop it on the navigation pill and it becomes a tab there (add or remove it in the pill's editor too).
  - switching between the Smart and Light brains is instant once both are downloaded, nothing is downloaded twice, and you can remove one you don't use. Downloads show their speed and time left.
- **Fixes:**
  - the cards' far side no longer goes black while you drag back (Glass, Aero and the see-through Default look).
  - Glass's light splitting works at the default Glass quality (it needed 90%).
  - the tablet's side list now slides away without leaving its space behind for a moment.
  - "Show each core" sits right under the CPU graph (app and web) — it used to be a hidden double-tap.
  - the Default look's frost and transparency reach the alert banner and toasts; the app explains when Frost has nothing to blur (no picture, or Transparency at 0%).
  - Icon style shows the current style beside its arrow; All apps is first in the Home apps row; the living wallpaper has a frame-rate limit in the app too.
  - web: spinners turn smoothly (they restarted with every refresh); no more frosted square at the bottom when switching pages; clicking into the assistant's box with a mouse no longer jumps the page; notes line up with the cards around them; the download progress isn't cropped.

## 0.9.3-alpha (server, Nova web, app and watch)
- **The assistant knows a lot more about your server:** Nova's version, the system and kernel, uptime, CPU, memory and swap — and, when you ask about them, the Linux processes using the most memory and CPU, each container's memory and CPU, your drives, network addresses, scheduled restarts and backups. It answers from those real numbers (“what are the top 10 processes by RAM?”) instead of opening a page, and says when it can't see something rather than guessing.
- **Faster, longer answers:** the brain keeps its instructions and examples ready between questions, has room for twice as much, and can write longer lists.
- **Assistant settings** has its own page (app and web): the brain, voice, the assistant button, and forgetting the conversation.
- **“Hide the assistant button”** (or “disable the orb”) works — it used to turn off the status light.
- **The orb** (app and web) is a sphere of dots in your accent color over a dark sphere, so the dots stand out on any page; the app shows **Thinking…** and the step it's on, like the web.
- **Tidier chat:** bubble corners stay the same however many lines they have; the text box is centered and grows as you type; on phones the keyboard pushes the page up so you can see what you're typing (app and web).
- The app now loads the same shared conversation as Nova web.

## 0.9.2-alpha (server, Nova web, app and watch)
- **One ongoing conversation** (assistant): kept on your server and shared by every phone and browser. After a few quiet hours it starts afresh, but Nova remembers what it did (for a month), so “how much RAM did we free yesterday?” still works. **Forget the conversation** is in the assistant's settings.
- **Just talk to it:** with its brain, Nova chats and answers general questions too (“yo, what's up?”, “give me a dinner idea”), friendlier all round. Without a brain, greetings get a friendly answer instead of “I didn't understand”.
- **More it can change:** the living wallpaper and its scene, your accent color, window and corner roundness, the frame rate meter, Reduce transparency, reading replies aloud, and the Lumen look — and when the brain is there, it fills in whatever the rules missed instead of dropping it.
- **A new orb** (web): a turning sphere of dots in your accent color that thinks, listens and works; a **Thinking…** bubble while Nova plans and the current step while it works; no more box around the text field.
- **Off by default:** the assistant and its brain stay off on new installs; setup now asks whether you'd like them (default no).

## 0.9.1-alpha (server, Nova web, app and watch)
- **Talk to Nova** (assistant, app and web): tap the mic and say it. With **Voice** downloaded on the assistant page (0.2 GB, Whisper), your server turns speech into text itself — nothing goes to an outside service. Without it, the app uses the phone's own speech recognizer (offline when it can). It stops listening when you pause.
- **Read replies aloud** (optional): Nova says what it's about to do, and what it did, in your device's own voice.
- **A real conversation:** the assistant remembers what you asked, what it said and what its steps actually did, so follow-ups work — “free up some RAM”, then “how much did we free?” gets the real number (the app didn't send this before). **New conversation** starts over.
- **Assistant button:** the assistant's orb in the corner of every page, to ask from anywhere. Turn it off in Appearance → Icons and text, or on the assistant page.
- **Longer searches:** Search takes up to 500 characters (it was 80), so longer requests to the assistant fit.
- **Small things:** “All done! I freed 4.9 GB of RAM.” reads right; Enter sends on a hardware keyboard; removing Nova also removes the voice service.

## 0.9.0-alpha (server, Nova web, app and watch)
- **Nova assistant · Labs** (app and web): ask in plain words — “apply the fire light theme, free up some storage and RAM, turn off the status light, dim the fan a bit, install Brave and restart the server at 5pm a week from now” — and Nova shows its plan, then does it one step at a time with a hint of the next, and a Stop button.
  - **Safe by design:** the server only plans; every step is one of Nova's ordinary actions that your phone or browser signs itself, so risky steps still need your fingerprint (or a phone's approval) and everything is in the action log. A drive is never erased from here: the plan waits in the Partition manager for the serial and your fingerprint.
  - **Asks instead of guessing:** two drives that match, several Brave containers on Docker Hub, an ambiguous container — you pick from a list.
  - **Real numbers:** “Freed 4.1 GB of storage and 900 MB of RAM” is measured, not estimated.
  - From **Search** (type a request, pick “Ask Nova”) or its own page.
- **The assistant's brain** (optional download in the assistant): a small language model that runs on your server — no Ollama, nothing to set up. It understands what the rules don't (“make the lights look like a police car”) and answers questions about your server (“is my disk ok?”). **Smart** (Qwen3 4B, 2.5 GB) or **Light** (Qwen2.5 1.5B, 1.1 GB), run by llama.cpp; both pinned to an exact file and checked against its fingerprint. It runs as its own service on 127.0.0.1 only, with no access to your files or the internet and a low share of the CPU. It learns from a set of worked examples, and it can only suggest Nova's own commands, which the rules check again.
- **Lumen · Beta** — a new look (app and web), inspired by libraries.dev: light on dark glass, a slow aurora behind it all, a beam of light running round the navigation pill and dialogs, liquid-metal buttons, gooey switches, glowing icons and bars, and (web) a navigation pill that leans toward the pointer. Light and dark, plus an Aurora living wallpaper.
- **Schedule a restart or shutdown** from the power menu (Quick panel, and the server picture's power button): pick a day and time, cancel it any time before. It survives a restart in between.
- **NTFS** in the Partition manager, for drives you also plug into Windows.

## 0.8.14-alpha (server, Nova web, app and watch)
- **A tidier Appearance** (app and web): seven short groups — Look, Colors, Background, Icons and text, Motion and effects, Sounds and touch, Home — each showing what it's set to now, with the details folded into dropdowns and dialogs.
  - **Style** is one row with the current style; tap it to pick another. Light or dark is a three-way switch.
  - **Shape and glass** holds the look's sliders together: light splitting, glass clarity or transparency and frost, corner roundness, and the new **Window roundness** (dialogs and windows, every look).
  - **Living wallpaper:** pick the scene from a gallery of previews; speed, blur, tint and frame rate sit in one dropdown. Mica's bloom is crisper (it looked blurred).
  - **Colors:** tapping a color (fade color, wallpaper color, accent) opens the color picker straight away, with your recent colors and the "page's color" choice on top.
  - **Icons** has its own page: the app icon gallery (now in your icon shape), your own colors (now on the web too), icon shape, in-app icon style, and **Icons in your accent color**.
  - **New app icons:** Silhouette, black or white — just Nova's symbol.
  - Settings that do nothing on a device are hidden: **Vibration** where there's no vibration motor (and on computers), **Color strength** outside Material You.
- **Readability** (Appearance → Icons and text): Automatic makes text stronger only where it's hard to read — see-through glass over a picture or a living wallpaper, or a picture whose brightness fights the text (Nova measures it) — with a soft halo, gray and accent text closer to the text color and, where it's worst, a little bolder. Or set it to Off or Always stronger.
- **Softer windows:** the Default look's dialogs and windows are much less round.
- **Small things:** Breeze's dialogs are solid (the page showed through); every button-like row works with Enter and Space and shows a clear focus ring; switches tell screen readers whether they're on; icon-only buttons have labels (web).

## 0.8.13-alpha (server, Nova web, app and watch)
- **Alerts arrive in a second or two, not a minute:** a new message goes out the moment it's queued, the server checks for new events every 15 seconds instead of every minute, and Nova web now waits on the server like the app does instead of checking every 20 seconds.
- **Home Assistant behind Cloudflare, fixed for new versions:** Home Assistant 2026 keeps its proxy settings in its own store and ignores configuration.yaml, so the old fix no longer worked (that's the "400: Bad Request"). Recommended → **Fix it** now changes the right setting, with a backup.
- **Containers, like docker ps and more:** each row shows how long it's been up (or when it exited), its ports, and when it's on the server's own network. A container shows where to open it (for host-network apps like Home Assistant, the port it really listens on, and why docker ps shows none), its ID, command and networks — and **Edit its Compose file**: Docker Compose checks your change before it's saved, the old file is kept, it's applied with your fingerprint, and **Undo** puts it back (app and web).
- **Background gallery** (Appearance → Wallpaper, app and web): every look's built-in background — Default's glow, Glass's vivid glow, Mica's soft wash, Aero's sky, plain — on any look, shown as previews.
- **Top and bottom fades:** turn the tint at the top and bottom of the screen off, or give it your own color (app and web).
- **Bars in each look's style** (CPU, memory, disk, progress): Aero's glossy Windows 7 progress bar, Mica's thin line, Glass's capsule, Material's tonal track (app and web).
- **Search bars and text fields in each look's style** — Aero's is Windows 7's search box (app and web).
- **Dialogs have the same corners as windows** in every look (web).
- **The Inbox wears Aero's glass** (and every look's) instead of plain white rows (web).
- **Matching corners:** the Home shortcut bar and its buttons have the same corners as the cards around them in every look (app).
- **Sliders and dragging, without pixel-perfect aim** (web): press anywhere on a slider's row and drag; drag to reorder starts as soon as you move the mouse.
- **The server illustration** has no more hard line down its middle, and it can't vanish for a few seconds any more.

## 0.8.12-alpha (server, Nova web, app and watch)
- **Install from a desktop, no terminal:** open `nova-server.deb` with your software app, then **Set up Nova** in your apps menu — it asks for your password and opens the setup in your browser. A notification says so when it's installed that way.
- **One package for every machine:** `nova-server.deb` (a stable download link: releases/latest/download/nova-server.deb) installs on x86-64 and ARM alike, and now on **Ubuntu 22.04** too (Python 3.10 is enough).
- **Link two servers by approving it:** in Nova web, Servers → Add a server lists the Nova servers it finds at home; pick one → Link, then approve it on that server (in the Nova app, or `sudo nova approve CODE`). Or from the terminal: `sudo nova link OTHER-SERVER`. The certificate is checked the whole way, and a linked server only ever reads.
- **A friendlier `nova` command:** plain `nova` shows the few things you'd come for; `nova help TOPIC` gives short guides (pair, browser, servers, remote, backup, update, troubleshoot, uninstall); a typo gets "did you mean"; `sudo nova status` shows this server's certificate.
- **`sudo nova doctor`:** checks Nova, its ports, the home address, the firewall, the clock, disk space and Tailscale — each problem with how to fix it.
- **Setup explains itself:** type `?` at any question in `sudo nova setup` for what that step means. On a server that isn't set up yet, logging in says how to start.

## 0.8.11-alpha (server, Nova web, app and watch)
- **Living wallpaper is sharp on the web:** Aero's ribbons, Breeze's waves, Material's shapes and the nebula's stars are drawn at your screen's real sharpness, like the app (Mica's bloom and Glass's liquid color stay soft; Lighter effects still draws small).
- **Glass is much lighter with a living wallpaper** (app): the scene is drawn once per tick on a background thread and every Glass card copies its part, instead of each card drawing the whole scene again — that was the scene a dozen times a frame during a back gesture.
- **Light splitting applies as you slide it** (app; it used to wait until Glass was picked again).
- **Background** (Appearance → Wallpaper, app and web): each look's own, the Default look's glow on any look, or plain.
- **The Home shortcut bar** has the same corners as the cards in every look (web).

## 0.8.10-alpha (server, Nova web, app and watch)
- **Glass on the web shows the right colors again:** since 0.8.7 its light splitting dropped the red and blue of whatever was behind, so glass over a red wallpaper looked green. Fixed (web).
- **The app's terminal asks first, like the web's:** shutdown, reboot, `rm -rf /` and the like, and pastes of several lines, wait for you to confirm.
- **Backup script builder in the app** (Backups → Edit the script → Build it for me): the same form and the same script as Nova web.
- **Default look: Transparency and Frost in the app**, as on the web — see-through cards, frosted as much as you like.
- **Each core's percent** shows on its graph in the app, too.
- **Tablets:** the side pill's selection glides again instead of jumping (it now moves without redrawing the whole pill, like the phone's).
- **The navigation pill no longer shrinks when you add a page** to it; it keeps its width and shows there's more with its fade and arrow.
- **Mica, Breeze and Aero selectors** keep their corners lined up at high corner roundness (app and web).

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
