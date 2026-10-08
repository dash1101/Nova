# Contributing to Nova

Thanks for helping! Nova aims to be the easiest *secure* way to manage a home server from a phone.

## Getting around

| Path | What |
|---|---|
| `android/` | The app: Kotlin + Jetpack Compose, hand-drawn One UI-style components in `OneUi.kt` / `Ui.kt` |
| `server/api/server.py` | The API (Python standard library + `cryptography`); runs unprivileged |
| `server/api/helper.py` | The only privileged code: fixed verbs, validated arguments, no shell |
| `server/web/` | Nova web (plain HTML/CSS/JS, no build step, no third-party code) |
| `server/store/` | App store catalogue — the easiest place to contribute (see docs/STORE.md) |
| `packaging/` | `.deb` builder, setup helpers |

## Ground rules

- **Security first.** New endpoints are signed-request only. Anything that changes the system is a
  helper verb with strict argument checks; anything risky goes in `needs_stepup()`; browsers can't
  manage devices. Never add a verb that runs caller-supplied commands or paths without containment.
- **No new runtime dependencies** on the server without a very good reason (it's stdlib + cryptography).
- **Keep the look consistent:** use the components in `OneUi.kt` (Page, Group, Row1, OneDialog,
  OneSwitch…) rather than stock Material widgets.
- Plain-English UI text: say what happens ("Restarts the container"), not how.

## Building

```bash
# server package
packaging/build-deb.sh                 # -> dist/nova-server_<ver>_all.deb
# app (Android SDK, JDK 17+)
cd android && ./gradlew assembleDebug  # release builds need your own signing key (see README)
```

Test a server change against the real flow (pair a phone or the web app), not only the helper.
Please describe how you tested in your pull request.
