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

## Requests

- Bodies are capped (64 KB, or the upload chunk size for file uploads) and the length must be a plain
  number: negative or odd `Content-Length` values and chunked bodies are refused before anything is read.
- Malformed or absurdly nested JSON gets a 400, never a crash. Each connection has a 30-second idle limit.
- Browser pairing requests are limited per address (3 waiting at once), so one device can't fill the
  queue and lock real browsers out.

## Pairing

`sudo nova add` creates a 10-character single-use code (valid 10 minutes, stored only as a hash).
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
role baked in) or with `sudo nova add --role viewer --user Alex`. Changing roles needs a
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

## Watches

- **Approver key on the phone** (Approve from notifications): a second hardware key, registered with a
  fingerprint, that signs only approve/deny — used by the notification buttons and a paired watch's
  notification mirror.
- **The Nova watch app** pairs like a browser — its own P-256 key in the watch's keystore (usable only
  while the watch is unlocked), a 6-letter code approved on an admin phone with a fingerprint (never
  from the server shell), and the server's certificate pinned. It may read the overview, live numbers,
  events and approvals, and approve or deny; every other request is refused by the server. It belongs
  to the phone that approved it and stops working if that phone stops being an admin. See [WEAR.md](WEAR.md).

## Privilege separation

- `nova-api` runs as an unprivileged system user in a systemd sandbox (`ProtectSystem=strict`,
  `ProtectHome`, `PrivateTmp`, `NoNewPrivileges`, …). It can never gain privileges.
- The only privileged code is `/usr/local/lib/nova-api/helper`. It runs outside the sandbox,
  started by systemd per request on `/run/nova-helper.sock` (`root:nova-api 0660`, so only the
  API can connect).
  Every verb is hard-coded. Arguments are checked against live state (container names must exist
  and be Compose-managed, store items must be in the catalog, drives are addressed by serial,
  protected mounts are refused). The helper never runs a shell or a caller-supplied command.
- Root never writes through a path the `nova-api` account could have planted: files in Nova's own
  folders (`/var/lib/nova-api`, `/var/log/nova-api`) are written by root with `safeio` — a fresh,
  unpredictable temp file opened with `O_EXCL | O_NOFOLLOW` relative to the folder, owned through its
  file descriptor, then renamed into place. A symlink left there (to `/etc/shadow`, say) is replaced,
  never followed. Logrotate runs as `nova-api` for its logs.
- Background tasks (installs, updates, backups, image cleanup) run as transient systemd units started
  by the helper; their specs are checked again before they start.
- The audit log is `/var/log/nova-api/audit.log`: every change, who made it, from where, and whether a fingerprint was used.

## Labs

Labs features are off until an admin turns them on. Each one keeps the same rules: the crash-loop
guard and weekly updates run as root from `nova-labs.timer` but only do what their switch allows;
cleaning up images needs a fingerprint (or phone approval from a browser) and never removes images a
container uses or the versions kept for rolling back; Wake-on-LAN only sends a magic packet on the
local network; Cloudflare auto-setup protects an address with your Access login before it creates
the DNS record and tunnel route.

## The SSH terminal

The app is a normal SSH client to the server's sshd. Its key is a hardware-keystore key that
unlocks for 30 seconds after a fingerprint, and you add it to `~/.ssh/authorized_keys` yourself.
The server's host keys come over the signed API and are pinned. Nova never exposes SSH to the
internet; use your LAN or Tailscale.

## Reporting a problem

Please open a private security advisory on GitHub rather than a public issue.
