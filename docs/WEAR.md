# Nova on your watch (Wear OS)

A small companion for Wear OS 3 and newer: your server's health at a glance, anything waiting for
your approval (approve or deny on your wrist), and the latest events. There's also a **tile** with
the status, so a swipe from the watch face is enough.

## Install

The watch app is `nova-wear-<version>.apk` on the [releases page](https://github.com/dash1101/Nova/releases).
Until it's on Google Play, install it with ADB over Wi-Fi:

1. On the watch: Settings → System → About → tap *Build number* 7 times; then Developer options →
   *ADB debugging* and *Wireless debugging* on. Note the address and port shown there.
2. On a computer on the same Wi-Fi: `adb pair <address>:<pairing port>` (enter the code the watch shows),
   then `adb connect <address>:<port>` and `adb install nova-wear-<version>.apk`.

## Pair it

1. Open Nova on the watch. It looks for your server on the Wi-Fi; pick it (or *Type an address* —
   for example your server's Tailscale address).
2. The watch shows a 6-letter code and the server's certificate fingerprint.
3. On your phone: Nova → Menu → Users & devices → **Approve a browser or watch**, enter the code and
   confirm with your fingerprint. The dialog shows the certificate your phone trusts: it should match
   the one on the watch.

## What a watch can do

- **See:** the overview (health, CPU temperature, memory, containers, anything running), recent
  events, and what's waiting for approval.
- **Approve or deny** what a browser asked for — the same as on your phone.
- **Nothing else.** It can't open logs, files, terminals or settings, change lights or containers, or
  add devices. The server enforces this, not just the app.

Its key is made in the watch's own keystore and only works while the watch is unlocked (on your
wrist), which is why approving there counts as your fingerprint. A watch belongs to the admin phone
that approved it: if that phone is removed or made view-only, the watch stops working. *Unpair* on
the watch (or removing it in Users & devices) takes it off the server.

## Away from home

The watch talks to your server's home address. Away from home it works over Tailscale (type the
Tailscale address when pairing) — through its own Wi-Fi/LTE, or through your phone's connection.
Cloudflare Access addresses need a browser login, so they're not available on the watch.
