#!/bin/sh
# Remove the Nova server from this Mac.
#
#     sudo nova uninstall            (or: sudo sh /usr/local/lib/nova/lib/uninstall.sh)
#
#   --purge   also remove its settings, paired devices and Inbox history, and Nova's own account
#   --yes     don't ask first
# Your containers, files and everything else on the Mac stay. The last line printed is "NOVA-RESULT {json}".
set -eu
APP=/usr/local/lib/nova
DATA="/Library/Application Support/Nova"
LABEL=app.novalabs.nova
PURGE=0; YES=0
for a in "$@"; do case "$a" in --purge) PURGE=1 ;; --yes|-y) YES=1 ;; esac; done
result() { printf 'NOVA-RESULT %s\n' "$1"; }
[ "$(id -u)" = 0 ] || { echo "Run it with sudo:  sudo nova uninstall"; result '{"ok":false,"error":"needs sudo"}'; exit 1; }

if [ "$PURGE" = 1 ]; then echo "  Nova, its settings and its paired devices will be removed. Your containers and files stay."
else echo "  Nova will be removed. Its settings and paired devices stay, for a reinstall (--purge removes them too)."; fi
if [ "$YES" = 0 ] && [ -t 0 ]; then
  printf "  Remove Nova? [y/N] "; read -r ans
  case "$ans" in y|Y|yes|Yes) ;; *) echo "  Nothing was removed."; result '{"ok":false,"error":"cancelled"}'; exit 1 ;; esac
fi

for part in api alerts helper; do
  launchctl bootout "system/$LABEL.$part" >/dev/null 2>&1 || true
  rm -f "/Library/LaunchDaemons/$LABEL.$part.plist"
done
for p in $(pgrep -f "$APP/lib/" 2>/dev/null || true); do    # anything Nova left running (an open Nova Server window) - but not this script
  [ "$p" = "$$" ] || [ "$p" = "$PPID" ] || kill "$p" 2>/dev/null || true
done
FW=/usr/libexec/ApplicationFirewall/socketfilterfw
[ -x "$FW" ] && "$FW" --remove "$APP/venv/bin/python" >/dev/null 2>&1 || true
rm -rf "$APP" "/Applications/Nova Server.app"
rm -f /usr/local/bin/nova
if [ "$PURGE" = 1 ]; then
  rm -rf "$DATA"
  dscl . -delete /Users/nova-api >/dev/null 2>&1 || true
  dscl . -delete /Groups/nova-api >/dev/null 2>&1 || true
  echo "  Nova and its settings are gone."
else
  echo "  Nova is gone. Its settings are still in $DATA."
fi
result "{\"ok\":true,\"purged\":$([ "$PURGE" = 1 ] && echo true || echo false)}"
