# Releasing Nova

A release is a set of files in `dist/` plus one signed list of their checksums. Every installed server decides what to
trust from that list alone, so the steps below are mostly about not publishing a list you haven't tested.

## The two channels

- **Stable** is whatever GitHub calls the *latest* release. `get.sh`, `get.ps1`, the website's buttons and every
  server's daily check use `releases/latest/download/…`.
- **Beta** is one tag named in [`channels.json`](channels.json) on `main`. A server that has run
  `sudo nova update --beta` looks there too and takes the beta when it is newer than the latest release;
  `sudo nova update --stable` goes back to waiting for regular releases. A blank `"beta"` means there is no beta out.

A beta is published as a GitHub **pre-release**, which GitHub never serves as "latest" — so nobody gets it by
accident, and it can be installed and checked from its tag before anyone does.

## Steps

1. **Versions.** `API_VERSION` in `server/api/server.py`; `versionName` and `versionCode` in
   `android/app/build.gradle.kts` and `android/wear/build.gradle.kts` (only when the apps changed);
   `MARKETING_VERSION` in `ios/project.yml`. Betas are `1.0.0-beta.1`, `1.0.0-beta.2`, … and sort before `1.0.0`
   everywhere Nova compares versions (`dpkg`, `nova-update`, `selfupdate.py`, the app — each has a test).
2. **CHANGELOG.md**, written for the person who runs a server, not for whoever wrote the change.
3. **CI green on the branch** — `ci`, `linux`, `windows`, `macos`, `ios`. The `linux` run includes the upgrade from
   the previously published release and the backup restore drill.
4. **Build and sign:** the APKs (`./gradlew assembleRelease`, see the header of `release.sh`), the iPhone build from
   the `ios` workflow's artifact, then `packaging/release.sh`. It refuses to finish without the files other things
   download by name.
5. **Publish.**
   - A beta: set `"beta": "v1.0.0-beta.1"` in `packaging/channels.json`, merge to `main`, then
     `gh release create v1.0.0-beta.1 --prerelease dist/…`.
   - A regular release: `gh release create vX.Y.Z dist/…` (it becomes *latest*), and blank the beta in
     `channels.json` if this release replaces it.
6. **Check what was published**, from GitHub, on fresh machines:

       gh workflow run linux   -f released=true -f tag=v1.0.0-beta.1
       gh workflow run windows -f released=true -f tag=v1.0.0-beta.1
       gh workflow run macos   -f released=true -f tag=v1.0.0-beta.1

   Each installs with the one-line installer pointed at that tag, runs the full test, has the updater find the
   release (through the beta channel when a tag is given) and update over itself. Leave `tag` out to check the latest
   regular release.
7. **Promote a beta** once it has been run for real: `gh release edit v1.0.0-beta.N --prerelease=false --latest`
   makes it what everyone gets — or, for 1.0 itself, publish `v1.0.0` built from the same commit with only the
   version changed.

## Taking one back

A release that turns out bad is replaced, not deleted: servers that already took it need a newer version to move to.
Publish the fix as the next version. If a *beta* is bad, blank `"beta"` in `channels.json` first — that stops other
beta servers from taking it within the day — then fix forward.

## The key

Releases are signed with an Ed25519 key that lives only on the release machine (`$NOVA_RELEASE_KEY`, default
`~/.nova-release/release-ed25519.pem`); its public half is `packaging/release-key.pub`, shipped inside every server.
Losing it means no installed server will accept another update; leaking it means anyone can sign one. It is never
committed, never put in CI (the workflows sign their test releases with a key made for that run), and backed up
offline.
