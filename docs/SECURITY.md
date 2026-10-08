# Security model

Nova controls a whole server, so it's designed so that losing any one piece is not enough to take it over.

## Who can talk to the API

| Layer | What it stops |
|---|---|
| **Network** | The API listens only on the LAN address (and `127.0.0.1` for the Cloudflare tunnel). The firewall allows only the LAN subnet. |
| **Cloudflare Access** (remote only) | Requests from the internet must carry the Access service token; Cloudflare rejects everyone else before they reach the server. The server also verifies the `Cf-Access-Jwt-Assertion` (signature, audience, issuer). |
| **Device signature** | Every request is signed with the phone's P-256 key: `METHOD\nPATH\nTIME_MS\nNONCE\nsha256(BODY)`. The key is generated in the Android Keystore (StrongBox when available) and can't be exported. Unknown or revoked keys get 401. |
| **Replay protection** | Timestamps must be within ±60 s, and every nonce is accepted once. |
| **Step-up (fingerprint)** | Shells, stop/restart, restart policy, installs/uninstalls, unmounting, power, and removing devices need a second signature from a key that only works right after a biometric/PIN prompt for that exact request. |
| **Throttling** | 20 failed attempts in 10 minutes block the source address. |

## Pairing

`sudo nova-api pair` creates a 10-character single-use code (valid 10 minutes, stored only as a hash).
Pairing is accepted only from the LAN. The QR code also carries the SHA-256 of the server's TLS
certificate, which the app pins. If you type the code instead, the app shows the certificate's
first 12 hex digits for you to compare with the CLI output. Every new pairing raises an alert.

## Home-network encryption

The API serves HTTPS on the LAN (`tls_port`, default 8495) with a self-signed certificate created
on first start (`/var/lib/nova-api/tls`). The app trusts exactly that certificate (pinned), not any
CA, so a look-alike server on the network is refused.

## Users and roles

Every phone or browser belongs to a named user and has a role:

- **Admin**: full control. Risky actions still need the fingerprint step-up.
- **View only**: can see everything (status, logs, containers, settings) but can't change anything.
  This is enforced by the server, not just hidden in the app. It suits family members or a
  wall-mounted dashboard.

Admins invite phones from the app (Users & devices → Invite a phone: a single-use QR code with the
role baked in) or with `sudo nova-api pair --role viewer --user Alex`. Changing roles needs a
fingerprint. The server refuses to demote the last admin.

## Browsers (Nova web)

- A browser makes its own P-256 key with Web Crypto, marked **non-extractable**, so page scripts can
  sign with it but can't read it out. It's stored in IndexedDB, and every request is signed exactly
  like the app's.
- A browser can't pair itself. It shows a 6-letter code, and an admin phone approves it with a
  fingerprint (Users & devices → Approve a browser). Codes expire after 10 minutes and are
  rate-limited.
- Browsers have no fingerprint key, so a **risky action from a browser is forwarded to your admin
  phones** as a notification. It runs only after a phone approves it with its fingerprint-bound
  key. Phones remain the only things that can authorize risky actions.
- The page is served with a strict Content-Security-Policy (no third-party scripts, no framing).

## Privilege separation

- `nova-api` runs as an unprivileged system user in a systemd sandbox (`ProtectSystem=strict`,
  `ProtectHome`, `PrivateTmp`, `NoNewPrivileges`, …). It can never gain privileges.
- The only privileged code is `/usr/local/lib/nova-api/helper`. It runs outside the sandbox,
  started by systemd per request on `/run/nova-helper.sock` (`root:nova-api 0660`, so only the
  API can connect).
  Every verb is hard-coded. Arguments are checked against live state (container names must exist
  and be Compose-managed, store items must be in the catalogue, drives are addressed by serial,
  protected mounts are refused). The helper never runs a shell or a caller-supplied command.
- The audit log is `/var/log/nova-api/audit.log`: every change, who made it, from where, and whether a fingerprint was used.

## The SSH terminal

The app is a normal SSH client to the server's sshd. Its key is a hardware-keystore key that
unlocks for 30 seconds after a fingerprint, and you add it to `~/.ssh/authorized_keys` yourself.
The server's host keys come over the signed API and are pinned. Nova never exposes SSH to the
internet; use your LAN or Tailscale.

## Reporting a problem

Please open a private security advisory on GitHub rather than a public issue.
