#!/usr/bin/env bash
# Make a signed release in dist/:  server package + app APKs + SHA256SUMS + SHA256SUMS.sig
#   packaging/release.sh            (build the APKs first: cd android && ./gradlew assembleRelease ...)
# The signing key stays on the release machine: $NOVA_RELEASE_KEY (default ~/.nova-release/release-ed25519.pem).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; D="$ROOT/dist"
KEY="${NOVA_RELEASE_KEY:-$HOME/.nova-release/release-ed25519.pem}"
[[ -f "$KEY" ]] || { echo "no release key at $KEY"; exit 1; }
bash "$ROOT/packaging/build-deb.sh"
bash "$ROOT/packaging/build-tar.sh"            # any Linux: the tarball and the one-file installer
bash "$ROOT/packaging/build-win.sh"            # Windows: the zip with its installer
VER="$(grep -oP 'API_VERSION = "\K[^"]+' "$ROOT/server/api/server.py")"
cd "$D"
ls nova-*.apk >/dev/null 2>&1 || echo "note: no APKs in dist/ — this release only updates the server"
rm -f SHA256SUMS SHA256SUMS.sig
# a stable name too, so a link can always point at the newest: releases/latest/download/nova-server.deb
cp "nova-server_$(echo "$VER" | sed 's/-/~/')_all.deb" nova-server.deb
sha256sum nova-server_*.deb nova-server.deb nova-server_*.tar.gz nova-installer.run nova-server_*_windows.zip nova-windows.zip $(ls nova-*.apk 2>/dev/null) > SHA256SUMS
python3 - "$KEY" <<'PY'
import base64, sys
from cryptography.hazmat.primitives import serialization
k = serialization.load_pem_private_key(open(sys.argv[1], "rb").read(), None)
open("SHA256SUMS.sig", "w").write(base64.b64encode(k.sign(open("SHA256SUMS", "rb").read())).decode() + "\n")
PY
# Other things fetch these by name: Nova's own installer and updater, the website's download buttons, and hub apps such as
# NovaLabs' Orbit (which also checks SHA256SUMS.sig against the release key). A release without one of them breaks those.
for f in nova-server.deb nova-installer.run nova-windows.zip SHA256SUMS SHA256SUMS.sig; do [[ -s "$f" ]] || { echo "release is missing $f" >&2; exit 1; }; done
if ls nova-*.apk >/dev/null 2>&1; then
  for f in nova-universal.apk nova-wear.apk; do [[ -s "$f" ]] || { echo "release has app builds but no $f (the stable name others download)" >&2; exit 1; }; done
fi
echo "signed release $VER in $D:"; cat SHA256SUMS
