# A brief for reviewers

Nova lets a phone or a browser run a whole server: stop containers, open a root shell, erase a drive. Before 1.0 its
pairing and approval design should be read by someone who didn't write it. This page is for that person: what the
design claims, where the code that enforces each claim lives, how to stand up a throwaway server, and what we already
know is weak. It assumes [SECURITY.md](SECURITY.md) (the design) and [API.md](API.md) (the contract) are open next to it.

## What to try to break

In order of how much it would hurt:

1. **Acting without a paired device.** Any request the server carries out that wasn't signed by a key in
   `devices.json` — other than `GET /api/v1/ping`, pairing with a valid one-time code, and a browser's own request to
   be let in.
2. **Getting paired without the owner.** A device in `devices.json` that no one at the server's terminal
   (`sudo nova add`) and no admin phone with a fingerprint agreed to.
3. **A risky action without the second factor.** Anything on the step-up list (shells, power, erasing, installs,
   removing devices, widening access) run with only the device signature — from a phone without the fingerprint
   signature, or from a browser without an admin phone's approval.
4. **A view-only device changing something**, or an admin action through another server's relay that the far server
   wouldn't have accepted directly.
5. **Leaving the API's sandbox.** The API process runs unprivileged; everything privileged goes through a helper
   with a fixed list of verbs. An input that makes the helper do something not on the list — a path, a container
   name, a package name, a drive serial that becomes an option or a second command.
6. **A release the owner didn't mean to install.** An update accepted without Nova's release signature, or a
   signed-but-older one installed as a downgrade.

## Where each claim is enforced

| Claim | Code |
|---|---|
| Every request is signed; ±60 s; nonces once; per-address lock-out | `server/api/server.py` → `authenticate()`, `throttled()` |
| The signed bytes (method, path with query, time, nonce, body hash) | same; examples in `packaging/signing_vectors.json` |
| Pairing: one-time code, hashed, 10 minutes, home network only unless made for remote | `server.py` → `pair()`; `server/api/nova` → `add` |
| Step-up: a second signature over the same message by a biometric-bound key | `server.py` (search `stepup`); Android `Security.kt`; iPhone `ios/Nova/Core/Keys.swift` |
| Browsers: non-extractable Web Crypto key; let in and approved by a phone, an authenticator code or a passkey | `server/web/core.js`; `server.py` (search `approval`, `totp`, `passkey`) |
| Roles are enforced by the server | `server.py` (search `view_only`) |
| Remote: Cloudflare Access token checked by the server too | `server.py` → `access_jwt_ok()` |
| The certificate the apps pin | `server.py` → `tls_material()`; Android `Api.kt` → `pinned()`; iPhone `Core/API.swift` → `Transport` |
| Privilege boundary: socket only the API user can open, fixed verbs, validated arguments | `server/api/helper.py`, `helper-sock.py`, the systemd units in `server/systemd/` |
| Controlling another server: the far server still checks the browser's own signature | `server/api/nodes.py` |
| Updates: one signed checksum list, the package checked against it, no downgrade | `server/api/nova-update`, `server/api/selfupdate.py`, `packaging/release-key.pub` |

The server is Python's standard library plus `cryptography`; there is no web framework, ORM or template engine to
audit around it. `server.py` is about 3,000 lines and `helper.py` about 1,300.

## A throwaway server

On a fresh Debian or Ubuntu VM (not a machine you care about):

    curl -fsSL https://raw.githubusercontent.com/dash1101/Nova/main/get.sh | sh
    sudo nova setup            # or packaging/ci-setup.sh from a checkout, for an unattended one
    sudo nova add              # a pairing code and QR

`packaging/pentest.py` is the existing attack script (several hundred checks: unsigned and mis-signed requests, replays,
roles, injection into every helper verb, the relay, updates). `sudo python3 packaging/pentest.py register` makes three
throwaway identities — an admin phone with a step-up key, a view-only phone and a browser — and the script shows how to
sign as each, which is the quickest way to write a new attack. `packaging/linux_test.py` walks the ordinary paths the
same way. Both run in CI against a real install on every change ([PENTEST.md](PENTEST.md) has the history of what
they've found).

## What we already know

Not bugs to report — the edges of the design, stated so they can be judged:

- **The home network is trusted to reach the API, not to use it.** Anyone on the LAN can connect, try a pairing code
  (10 characters, single use, rate-limited) and read `GET /ping`. They can't do more without a paired key.
- **A paired admin phone that is unlocked and in someone else's hand** can do everything not on the step-up list, and
  everything on it if they can pass the phone's biometric or PIN prompt.
- **Root on the server is out of scope.** Someone who is already root can add a device by editing `devices.json`;
  `sudo nova approve` is that power, on purpose.
- **A browser's key is only as safe as the browser.** It can't be exported by page scripts, but an extension or
  malware in that browser profile can ask it to sign. That is why browsers can't approve anything.
- **The LAN certificate is self-signed and pinned at pairing.** Typing the code instead of scanning the QR relies on
  the person comparing 12 hex digits.
- **The Windows and macOS servers and the iPhone app are previews.** They share the signing, pairing and approval code
  paths, but their privilege boundaries (a Windows service account, a launchd daemon and socket) have had far less
  time than the Linux one.
- **Plugins and the app store run what they install** as containers or commands the admin approved with a
  fingerprint; Nova checks who asked, not what the software then does.

## Reporting

Privately, through GitHub's *Report a vulnerability* on the repository ([SECURITY.md](../SECURITY.md)). Useful
reports say which claim above fell, the request that did it (the pentest script's helpers make that short), and the
version. Design objections with no exploit are welcome too — "this is safe only because X" is exactly what the
review is for.
