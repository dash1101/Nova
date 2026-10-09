#!/usr/bin/env bash
# Build the Nova server package:  packaging/build-deb.sh  ->  dist/nova-server_<version>_all.deb
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; S="$ROOT/server"
VERSION="${VERSION:-$(grep -oP 'API_VERSION = "\K[^"]+' "$S/api/server.py" | sed 's/-/~/')}"
B="$(mktemp -d)"; P="$B/pkg"; trap 'rm -rf "$B"' EXIT
L=/usr/lib/nova-api
install -d "$P$L/web" "$P$L/store" "$P$L/monitor" "$P/usr/bin" "$P/usr/sbin" "$P/lib/systemd/system" "$P/usr/lib/nova-rgb" \
           "$P/etc/logrotate.d" "$P/usr/share/doc/nova-server" "$P/DEBIAN"
install -m 755 "$S/api/server.py" "$P$L/server.py"
install -m 755 "$S/api/helper.py" "$P$L/helper"
install -m 644 "$S/api/nova_tz.py" "$S/api/storage.py" "$S/api/diag.py" "$S/api/apps.py" "$S/api/apkver.py" "$P$L/"
install -m 755 "$S/api/backups.py" "$S/api/tasks.py" "$P$L/"
install -m 755 "$S/api/helper-sock.py" "$P$L/helper-sock"
install -m 755 "$S/monitor/nova_alerts.py" "$P$L/monitor/nova_alerts.py"
ln -s "$L/monitor/nova_alerts.py" "$P/usr/sbin/nova-alert"
install -m 644 "$S"/web/* "$P$L/web/"
cp -r "$S/store/." "$P$L/store/"
install -m 755 "$S/api/nova" "$P/usr/bin/nova"
ln -s ../bin/nova "$P/usr/sbin/nova-api"   # the old name still works
install -m 755 "$S/api/nova-setup" "$P/usr/sbin/nova-setup"
install -m 755 "$ROOT/packaging/nova-setup-lighting" "$P/usr/sbin/nova-setup-lighting"
install -m 755 "$S/api/nova-app-publish" "$P/usr/sbin/nova-app-publish"
install -m 755 "$S/api/nova-update" "$P/usr/sbin/nova-update"
install -m 644 "$ROOT/packaging/release-key.pub" "$P$L/release-key.pub"
install -m 644 "$ROOT/packaging/nova-update-check.service" "$ROOT/packaging/nova-update-check.timer" "$P/lib/systemd/system/"
M="$S/modules/fan-gigabyte-fusion2"
install -m 644 "$M/fusion2.py" "$M/nova_rgb.py" "$M/60-nova-rgb.rules" "$M/nova-api-rgb.conf" "$P/usr/lib/nova-rgb/"
for u in "$S"/systemd/*.service "$S"/systemd/*.socket "$S"/systemd/*.timer "$M"/nova-rgb-*.service "$M"/nova-rgb-tick.timer; do
    sed -e "s#/usr/local/lib/nova-api#$L#g" -e "s#/usr/local/lib/nova-rgb#/usr/lib/nova-rgb#g" "$u" > "$P/lib/systemd/system/$(basename "$u")"
done
install -m 644 "$ROOT/packaging/logrotate" "$P/etc/logrotate.d/nova-api"
install -m 644 "$ROOT/README.md" "$ROOT"/docs/*.md "$P/usr/share/doc/nova-server/"
install -m 644 "$S/config.example.json" "$P/usr/share/doc/nova-server/"
[ -f "$ROOT/LICENSE" ] && install -m 644 "$ROOT/LICENSE" "$P/usr/share/doc/nova-server/copyright"
SIZE=$(du -sk "$P" | cut -f1)
sed -e "s/@VERSION@/$VERSION/" -e "s/@SIZE@/$SIZE/" "$ROOT/packaging/deb/control" > "$P/DEBIAN/control"
for f in postinst prerm postrm; do install -m 755 "$ROOT/packaging/deb/$f" "$P/DEBIAN/$f"; done
echo "/etc/logrotate.d/nova-api" > "$P/DEBIAN/conffiles"
mkdir -p "$ROOT/dist"; OUT="$ROOT/dist/nova-server_${VERSION}_all.deb"
dpkg-deb --root-owner-group --build "$P" "$OUT" >/dev/null
echo "built $OUT ($(du -h "$OUT" | cut -f1))"
