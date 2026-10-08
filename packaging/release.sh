#!/usr/bin/env bash
# Make a signed release in dist/:  server package + app APKs + SHA256SUMS + SHA256SUMS.sig
#   packaging/release.sh            (build the APKs first: cd android && ./gradlew assembleRelease ...)
# The signing key stays on the release machine: $NOVA_RELEASE_KEY (default ~/.nova-release/release-ed25519.pem).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; D="$ROOT/dist"
KEY="${NOVA_RELEASE_KEY:-$HOME/.nova-release/release-ed25519.pem}"
[[ -f "$KEY" ]] || { echo "no release key at $KEY"; exit 1; }
bash "$ROOT/packaging/build-deb.sh"
VER="$(grep -oP 'API_VERSION = "\K[^"]+' "$ROOT/server/api/server.py")"
cd "$D"
ls nova-*.apk >/dev/null 2>&1 || echo "note: no APKs in dist/ — this release only updates the server"
rm -f SHA256SUMS SHA256SUMS.sig
sha256sum nova-server_*.deb $(ls nova-*.apk 2>/dev/null) > SHA256SUMS
python3 - "$KEY" <<'PY'
import base64, sys
from cryptography.hazmat.primitives import serialization
k = serialization.load_pem_private_key(open(sys.argv[1], "rb").read(), None)
open("SHA256SUMS.sig", "w").write(base64.b64encode(k.sign(open("SHA256SUMS", "rb").read())).decode() + "\n")
PY
echo "signed release $VER in $D:"; cat SHA256SUMS
