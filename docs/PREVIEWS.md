# Trying the previews on real devices

The server on Windows and macOS and the iPhone app are tested on every change — but on GitHub's machines: virtual, new,
and nothing like the old laptop in a drawer. What they need before they stop being previews is time on real hardware.
If you have some, this is the half hour that helps most, and what to send back.

Nothing here touches a server you depend on: each can pair with the same server next to your existing phone, and each
uninstalls cleanly.

## An iPhone, from a Windows PC

The release has `nova-ios.ipa`, an unsigned build. Apple only lets an iPhone run apps signed for it, so a sideloading
tool signs it with your own Apple ID (free works; the app then needs re-signing every 7 days — the tool does it).

1. On the PC, install **iTunes and iCloud from Apple's website** (the Microsoft Store versions don't work with these
   tools), then [Sideloadly](https://sideloadly.io) (simplest for a one-off) or [AltStore](https://altstore.io) (keeps
   it refreshed over Wi-Fi).
2. Plug the iPhone in, tap **Trust** on it, drag `nova-ios.ipa` into the tool, sign in with your Apple ID, install.
3. On the iPhone: **Settings → General → VPN & Device Management** → trust your Apple ID; and on iOS 16 or newer
   **Settings → Privacy & Security → Developer Mode** → on (the phone restarts once).
4. On the server: `sudo nova add`. In the app: scan the QR.

Worth trying, in this order — the first three have never run on a real iPhone:

- [ ] **Pairing by QR** with the real camera (the Simulator has none).
- [ ] **Face ID / Touch ID on a risky action** — restart a container. The key behind it lives in the Secure Enclave,
      which the Simulator only imitates. Then cancel the prompt once: nothing should happen.
- [ ] **Away from home**: Wi-Fi off, over Tailscale or your Cloudflare address.
- [ ] Leave it a day, open it again: still paired, numbers load at once?
- [ ] Remove the iPhone under Users & devices on another device: the app should say so and offer to pair again.

A cracked or dim screen doesn't matter for any of this as long as touch works.

## The server on an older Mac

`get.sh` works on macOS 12 or newer, Intel or Apple silicon — but CI only has Apple silicon on the newest macOS. An
Intel Mac on macOS 12 (a 2015 MacBook Air is about the oldest that runs it) is exactly what hasn't been tried.

    curl -fsSL https://raw.githubusercontent.com/dash1101/Nova/main/get.sh | sh
    sudo nova setup
    sudo nova add

If macOS asks to install the *command line developer tools* first, say yes (that's where Python comes from) and run
the line again.

- [ ] Does the install finish, and does `sudo nova doctor` come back clean?
- [ ] Pair a phone; Home shows live numbers; **Storage** lists the Mac's drive with its health.
- [ ] **Power → Keep awake**, then close the lid for a minute (on a laptop): still reachable?
- [ ] The app's **Terminal** (needs Remote Login on: the app tells you) and **Files**.
- [ ] Restart the Mac: Nova is back by itself, the phone still paired.
- [ ] `sudo nova update --check`, then `sudo nova uninstall` when you're done — anything left behind?

See [MACOS.md](MACOS.md) for what the Mac server does and doesn't do yet.

## What to send back

Open an issue with the device and OS version, which boxes passed, and for anything that didn't: what you tapped,
what you expected, what happened. From a server, `sudo nova doctor` and the last lines of
`sudo nova log` say most of what's needed. Passing reports matter as much as failures — "works on an Intel
MacBook Air, macOS 12.7" is how a preview stops being one.
