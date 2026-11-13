#!/usr/bin/env bash
# Build the Windows download: dist/nova-server_<version>_windows.zip (and the stable name nova-windows.zip).
# It holds the same server files as the Linux package plus server/windows/ (the installer). Unzip, double-click
# "Install Nova.cmd". Needs only zip; nothing is compiled.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; S="$ROOT/server"; D="$ROOT/dist"
VER="${VERSION:-$(grep -oP 'API_VERSION = "\K[^"]+' "$S/api/server.py")}"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
N="$T/Nova"; mkdir -p "$N/api" "$N/monitor" "$N/web" "$N/windows" "$D"
# what a Windows server runs (the Linux-only tools — storage, backups, apt, setup — stay out until they work there)
for f in server.py helper.py helper-win.py plat.py winhost.py winrun.py winsetup.py safeio.py nodes.py totp.py passkeys.py plugins.py nova_tz.py guides.py qrgen.py \
         nova nova-gui assistant.py assistant_examples.json brain.py gate.py apkver.py tasks.py installs.py updates.py pkg.py recommend.py winupdate.py; do
  [ -f "$S/api/$f" ] && cp "$S/api/$f" "$N/api/"
done
cp "$ROOT/packaging/release-key.pub" "$N/api/release-key.pub"     # so updates from the Nova Server window / nova update can be checked
cp "$S/monitor/nova_alerts.py" "$S/monitor/winmon.py" "$N/monitor/"
mkdir -p "$N/store" && cp -r "$S/store/." "$N/store/"          # programs-windows.json (winget) and the containers catalog
cp "$S"/web/* "$N/web/"
cp "$S/windows/install.ps1" "$S/windows/uninstall.ps1" "$N/windows/"
printf '%s\n' "$VER" > "$N/VERSION"
cp "$ROOT/LICENSE" "$N/LICENSE.txt" 2>/dev/null || true
printf '@echo off\r\nrem Installs (or updates) the Nova server on this computer. Windows will ask for permission once.\r\npowershell -NoProfile -ExecutionPolicy Bypass -File "%%~dp0windows\\install.ps1" -Relaunched\r\n' > "$N/Install Nova.cmd"
cat > "$N/README.txt" <<TXT
Nova server $VER for Windows

  1. Double-click "Install Nova.cmd" (Windows asks for permission once).
  2. Open "Nova Server" from the Start menu and press "Pair a phone or tablet",
     then scan the code with the Nova app.

Needs Windows 10 or 11 (or Windows Server 2019 or newer) and Python 3.9 or newer;
the installer gets Python with winget if it is missing.

Updating: download the newer zip and run "Install Nova.cmd" again. Your settings
and paired devices are kept.

Removing:  C:\\Program Files\\Nova\\uninstall.ps1   (add -Purge to remove settings too)

What works on Windows and what does not yet: docs/WINDOWS.md at
https://github.com/dash1101/Nova
TXT
sed -i 's/$/\r/' "$N/README.txt"
if grep -nP '[^\x00-\x7F]' "$N"/windows/*.ps1; then echo "non-ASCII characters in a .ps1 file" >&2; exit 1; fi
OUT="$D/nova-server_${VER}_windows.zip"; rm -f "$OUT"
(cd "$T" && zip -qr -X "$OUT" Nova)
cp "$OUT" "$D/nova-windows.zip"
echo "built $OUT ($(du -h "$OUT" | cut -f1))"
