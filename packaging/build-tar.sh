#!/usr/bin/env bash
# Nova for any Linux:  packaging/build-tar.sh  ->  dist/nova-server_<version>.tar.gz  (+ dist/nova-installer.run)
# The tarball holds the same files as the .deb plus install.sh; the .run is the tarball with a small shell
# header, so "download, chmod +x, sudo ./nova-installer.run" is the whole install.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${VERSION:-$(grep -oP 'API_VERSION = "\K[^"]+' "$ROOT/server/api/server.py" | sed 's/-/~/')}"
B="$(mktemp -d)"; trap 'rm -rf "$B"' EXIT
T="$B/nova-server-$VERSION"; mkdir -p "$T/root"
NOVA_STAGE="$T/root" VERSION="$VERSION" bash "$ROOT/packaging/build-deb.sh" >/dev/null
install -m 755 "$ROOT/packaging/tar/install.sh" "$T/install.sh"
install -m 644 "$ROOT/packaging/deb/postinst" "$T/postinst"
mkdir -p "$ROOT/dist"; OUT="$ROOT/dist/nova-server_${VERSION}.tar.gz"
tar --owner=0 --group=0 -C "$B" -czf "$OUT" "nova-server-$VERSION"
RUN="$ROOT/dist/nova-installer.run"
cat > "$RUN" <<HDR
#!/bin/sh
# Nova installer ($VERSION) — sudo sh nova-installer.run [--gui]
set -e
[ "\$(id -u)" = 0 ] || { echo "Run with sudo:  sudo sh \$0 \$*"; exit 1; }
D=\$(mktemp -d); trap 'rm -rf "\$D"' EXIT
sed '1,/^__TARBALL__\$/d' "\$0" | tar -xz -C "\$D"
sh "\$D/nova-server-$VERSION/install.sh" "\$@"
exit 0
__TARBALL__
HDR
cat "$OUT" >> "$RUN"; chmod 755 "$RUN"
echo "built $OUT ($(du -h "$OUT" | cut -f1)) and $RUN"
