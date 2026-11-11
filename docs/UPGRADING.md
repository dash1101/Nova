# Upgrading Nova

Nova updates itself: **Settings → Software update** (app or Nova web) updates the server, and the app
updates itself from your server. Every server release is signed, and the server only installs a release
whose signature checks out. This page is for the cases around that.

## Server

- **From the app or Nova web:** Settings → Software update → Update the server (needs your fingerprint).
- **From a terminal:** `sudo nova update` (checks Nova's releases on GitHub, verifies the signature, installs);
  `sudo nova update --check` only looks.
- **Debian / Ubuntu by hand:** `sudo apt install ./nova-server_VERSION_all.deb`.
- **Other Linux by hand:** `sudo sh nova-installer.run`.
- **Fresh install or reinstall:** `curl -fsSL https://raw.githubusercontent.com/dash1101/Nova/main/get.sh | sh`.

**Where updates come from** (`sudo nova setup` → advanced settings): blank means Nova's official releases, checked
once a day and never installed without you; `github:owner/repo` or a folder for your own builds; `manual` turns
the daily check off (`sudo nova update` at the server still works). Before 0.9.17, blank meant "never check" — a
server set up then can catch up once with `sudo nova update github:dash1101/Nova`.

Your settings, paired devices, alerts and backup jobs stay where they are (`/etc/nova-api`, `/var/lib/nova-api`,
`/etc/nova-backup`) through every update and reinstall.

## Going back

Install the older version's package the same way (`sudo apt install ./nova-server_OLD_all.deb` — apt asks you
to confirm the downgrade). Settings stay. Containers Nova updated can each go back on their own:
Containers → the container → Troubleshoot → Go back to the previous version.

## App and server versions

The app and the server don't have to be the same version. They agree through the API level (see
[API.md](API.md)): a newer app hides what an older server can't do yet, and an older app keeps working with a
newer server. If one side ever is too old, the app says so on Home and links to the update.

## From the alpha releases to 1.0

- Nothing to migrate by hand: the 1.0 server reads the same settings files as the 0.7 alphas.
- The API contract starts at level 1 (Nova 0.7.5). Apps from before it still work; they just don't see the level.
- Lighting brightness follows a perceptual curve since 0.7.2: if your lights look dimmer than before, raise the
  brightness a little.
- Glass clarity was re-scaled in 0.7.5: the frosted look of 0.7.3 and earlier (at 100%) is about 10% now, and
  100% is almost bare glass. If you liked the frosted look, set Glass clarity to about 10%.
