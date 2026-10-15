# The Nova API (v1)

Nova's apps — the Android app, the Wear OS app, Nova web and other Nova servers controlling this one — all
talk to the server through one HTTPS API under `/api/v1/`. This page is the contract: what a client can rely
on, how changes are made, and how a client and server that are different ages keep working together.

## The promise

- **`/api/v1` is stable.** Within v1, an endpoint or a field in a response is never removed and never changes
  meaning. New endpoints and new fields can appear at any time, so clients must ignore fields they don't know.
- **The API level only goes up.** Every response carries `X-Nova-Api-Level`, and `/api/v1/ping` and
  `/api/v1/whoami` return `api_level` and `min_level`. A new level means something was *added*. A client that
  needs something new checks the level and hides that feature on an older server instead of failing.
- **Old clients keep working.** `min_level` is the oldest level the server still serves in full. It only rises
  after a deprecation period (below).
- **A breaking change means `/api/v2`**, served next to v1 for at least a year.

| Level | Nova    | Added |
|-------|---------|-------|
| 1     | 0.7.5   | The contract itself: `api_level`/`min_level`, `X-Nova-Api-Level`, `X-Nova-Client`, deprecation headers. Everything the server offered at 0.7.4 is part of level 1. |

## Deprecation

When something has to go:

1. It keeps working, but responses carry `Deprecation: true` and `X-Nova-Deprecated: <what to use instead>`.
   It's listed in the changelog and below.
2. It stays like that for **at least two releases and at least six months**.
3. Only then can it be removed — and only for clients that have had an update with the replacement.

Deprecated now: nothing.

## Client identification

Clients send `X-Nova-Client: <platform> <build>` — `android 50`, `wear 50`, `web 1`. The header isn't part of
the signature and grants nothing; it lets the server answer a client it can no longer serve with
**`426`** `{"error": "...", "update": true}` instead of a confusing failure. The update endpoints
(`/api/v1/app/…`, `/api/v1/server/update`, `/api/v1/ping`, `/api/v1/whoami`) always answer, so an old app can
always update itself. No client is turned away today.

## Authentication

Every request except `GET /api/v1/ping` and pairing is signed by a paired device:

```
X-Nova-Device:    the device id from pairing
X-Nova-Time:      unix time in milliseconds (within ±60 s of the server)
X-Nova-Nonce:     random, used once (a replay is refused)
X-Nova-Signature: base64 ECDSA P-256 / SHA-256 over
                  METHOD \n PATH?QUERY \n TIME \n NONCE \n sha256hex(BODY)
```

- **Step-up (fingerprint) actions** need a second signature over the same message in `X-Nova-StepUp`, made
  with a key that only works after the fingerprint prompt. Without it they answer `403`
  `{"error": "stepup_required"}`.
- **Browsers** never hold a step-up key. A risky request from a browser answers **`202`**
  `{"approval": "<id>", "code": "..."}` and waits for an admin phone (or `sudo nova approve`) to approve it.
- **Remote** requests through Cloudflare must also carry a valid Cloudflare Access token.
- **View-only** devices can read everything; changes answer `403` `{"error": "view_only"}`.

## Responses and errors

- JSON, UTF-8. Success is `200` (or `202` for something that waits for approval, see above).
- Errors are `{"error": "a sentence a person can read"}` with the matching status: `400` bad input, `401`
  not signed or not paired, `403` not allowed (`view_only`, `stepup_required`, a feature switched off), `404`,
  `413` body over 64 KB, `426` client too old, `429` too many attempts (with a cooldown), `502` something
  on the server (Docker, the helper) didn't answer.
- Long jobs (drive setup, backups, installs, updates, tests) return a **task** `{"id": "…"}`. Follow it with
  `GET /api/v1/tasks/<id>`; `GET /api/v1/tasks` lists running and just-finished ones.
- Live updates: `GET /api/v1/stats?since=<t>` for the per-second graphs, and
  `GET /api/v1/events/wait?since=<t>&timeout=50` as a long poll for new alerts, approvals and task changes.

## Endpoints (level 1)

Grouped by area; each is `GET` unless marked. Fields are documented by the apps that use them
(`server/web/*.js`, `android/…/*.kt`) — the shapes below are the stable core.

- **Server:** `ping` · `whoami` · `overview` (`status`, `server`, `features`, `containers`) · `status` · `stats` ·
  `settings` (GET/POST) · `features` (POST, step-up) · `server/update` (GET/POST)
- **Inbox:** `events` · `events/wait` · `events/delete` (POST) · `events/restore` (POST) · `archive` ·
  `alerts/dismiss`, `alerts/undismiss` (POST) · `notify`, `notify/discord` (POST, step-up), `notify/discord/test`
- **Devices:** `devices` · `devices/<id>` (DELETE, step-up) · `devices/invite`, `devices/watch`,
  `devices/remove-all` (POST, step-up) · `device/form`, `device/stepup-key` (POST) · `browser/approve`,
  `approvals`, `approvals/<id>/approve` (POST, step-up) · `totp` · `totp/setup`, `totp/remove` (POST, step-up) · `totp/confirm` (POST) ·
  `approvals/<id>/totp` (POST, the browser's own held request) · `browser/request/<id>/totp` (POST, unsigned, rate-limited)
- **Containers and apps:** `containers` · `containers/stats` · `containers/<name>` · `containers/<name>/<stop|start|restart|…>` (POST) ·
  `containers/custom` (POST, step-up) · `apps` · `apps/command` · `store` · `store/<id>/install` (POST, step-up) ·
  `programs`, `programs/search` · `plugins`
- **Storage:** `storage` · `hardware` · `storage/task/<kind>` (POST; erasing kinds need step-up) · `drives/<serial>/…` ·
  `backups` (GET/POST) · `backups/<id>/…` · `backups/suggest` · `backups/legacy/script` (GET/POST, step-up) ·
  `freeup` (GET/POST, step-up)
- **Lights:** `fan` (GET/POST) · `lights` · `lights/<id>` · `lights/ha` (GET/POST step-up/DELETE) · `lights/ha/lights` (POST)
- **Remote access:** `cloudflare` · `cloudflare/<plan|publish|protect|access|remove|token|…>` (POST, step-up) · `remote-config`
- **Other servers:** `nodes` (GET/POST, step-up) · `nodes/probe`
- **Tasks and diagnostics:** `tasks` · `tasks/<id>` · `tasks/<id>/stop` (POST) · `diag/*` · `updates`, `updates/check`,
  `updates/packages`, `updates/containers` (POST, step-up)

## Checking the contract

The release security test (`packaging/pentest.py`) includes an **API contract** section: it checks that
`ping` and `whoami` report the level, that every response carries `X-Nova-Api-Level`, that the core
responses still have their stable fields, and that an unknown client header changes nothing. A release that
breaks the contract fails the test.
