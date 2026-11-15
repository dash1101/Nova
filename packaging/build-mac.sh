#!/usr/bin/env bash
# Build the macOS download: dist/nova-server_<version>_macos.tar.gz (and the stable name nova-macos.tar.gz).
# It holds the same server files as the Linux package plus server/mac/ (the installer). Unpack it and run
#   sudo sh Nova/mac/install.sh      — or let get.sh do both. Needs only tar; nothing is compiled.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; S="$ROOT/server"; D="$ROOT/dist"
VER="${VERSION:-$(grep -oP 'API_VERSION = "\K[^"]+' "$S/api/server.py")}"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
N="$T/Nova"; mkdir -p "$N/api" "$N/monitor" "$N/web" "$N/mac" "$D"
# what a Mac server runs (the Linux-only tools — storage, backups, apt, setup — stay out until they work there)
for f in server.py helper.py helper-sock.py helper-mac.py plat.py machost.py runpart.py autosetup.py selfupdate.py safeio.py nodes.py totp.py passkeys.py \
         plugins.py nova_tz.py guides.py qrgen.py files.py nova nova-gui assistant.py assistant_examples.json brain.py gate.py apkver.py tasks.py installs.py \
         updates.py pkg.py recommend.py; do
  cp "$S/api/$f" "$N/api/"
done
cp "$ROOT/packaging/release-key.pub" "$N/api/release-key.pub"     # so `sudo nova update` and the Nova Server window can check an update
cp "$S/monitor/nova_alerts.py" "$S/monitor/macmon.py" "$N/monitor/"
mkdir -p "$N/store" && cp -r "$S/store/." "$N/store/"
cp "$S"/web/* "$N/web/"
cp "$S/mac/install.sh" "$S/mac/uninstall.sh" "$N/mac/"
printf '%s\n' "$VER" > "$N/VERSION"
cp "$ROOT/LICENSE" "$N/LICENSE.txt" 2>/dev/null || true
cat > "$N/README.txt" <<TXT
Nova server $VER for macOS (preview)

  1. In Terminal:   sudo sh Nova/mac/install.sh
  2. Open "Nova Server" from Applications and press "Pair a phone or tablet",
     then scan the code with the Nova app.   (Or in Terminal:  sudo nova add)

Needs macOS 12 or newer and Python 3.9 or newer — Apple's command line tools
have it (xcode-select --install), as do Homebrew and python.org.

Updating:  sudo nova update   (your settings and paired devices are kept)
Removing:  sudo nova uninstall   (add --purge to remove settings too)

What works on a Mac and what does not yet: docs/MACOS.md at
https://github.com/dash1101/Nova
TXT
OUT="$D/nova-server_${VER}_macos.tar.gz"; rm -f "$OUT"
tar --owner=0 --group=0 -C "$T" -czf "$OUT" Nova
cp "$OUT" "$D/nova-macos.tar.gz"
echo "built $OUT ($(du -h "$OUT" | cut -f1))"
