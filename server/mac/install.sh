#!/bin/sh
# Nova server for macOS — install it, or update it (settings and paired devices stay).
#
#     sudo sh install.sh            from the unpacked download (the "Nova" folder)
#
#   --python PATH   use this Python (3.9 or newer) instead of looking for one
#   --no-start      install without starting Nova
#   --quiet         no questions and no window at the end (hub apps and updates run it this way)
#
# What it sets up:
#   /usr/local/lib/nova                      the program (lib/) and its own Python environment (venv/)
#   /Library/Application Support/Nova        settings, paired devices, the Inbox, logs
#   /Library/LaunchDaemons/app.novalabs.nova.{api,helper,alerts}.plist   the three background services
#   /usr/local/bin/nova                      the command        /Applications/Nova Server.app   the window
# The API runs as its own account (nova-api) without administrator rights; only the helper runs as root.
# The last line printed is "NOVA-RESULT {json}" (ok, version, ports, urls) for programs that run this.
set -eu
SRC="$(cd "$(dirname "$0")/.." && pwd)"
APP=/usr/local/lib/nova; LIB="$APP/lib"; VENV="$APP/venv"; PY="$VENV/bin/python"
DATA="/Library/Application Support/Nova"
LABEL=app.novalabs.nova
PYTHON=""; NOSTART=0; QUIET=0
while [ $# -gt 0 ]; do
  case "$1" in
    --python) PYTHON="${2:-}"; shift ;;
    --no-start) NOSTART=1 ;;
    --quiet) QUIET=1 ;;
  esac
  shift
done

say()  { printf '    %s\n' "$*"; }
step() { printf '\n  \033[1m%s\033[0m\n' "$*"; }
result() { printf 'NOVA-RESULT %s\n' "$1"; }
die()  { printf '\n  \033[31mNova was not installed: %s\033[0m\n' "$*" >&2; result "{\"ok\":false,\"error\":\"$(printf %s "$*" | sed 's/["\\]/ /g')\"}"; exit 1; }

[ "$(uname -s)" = Darwin ] || die "this installer is for macOS (Linux has get.sh, Windows has get.ps1)"
[ "$(id -u)" = 0 ] || die "run it with sudo:  sudo sh $0"
[ -f "$SRC/api/server.py" ] || die "the download is incomplete (no api/server.py next to this installer)"
VERSION="$(cat "$SRC/VERSION" 2>/dev/null || echo unknown)"
PREV="$(cat "$LIB/VERSION" 2>/dev/null || true)"
FIRST=true; [ -f "$DATA/config/config.json" ] && FIRST=false
if [ -n "$PREV" ]; then printf '\n  \033[1mUpdating Nova %s -> %s\033[0m  (settings and paired devices stay)\n' "$PREV" "$VERSION"
else printf '\n  \033[1mInstalling Nova %s for macOS\033[0m\n' "$VERSION"; fi

# -- 1. Python ---------------------------------------------------------------------
step "Python"
py_ok() { [ -n "$1" ] && [ -x "$1" ] && "$1" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)' >/dev/null 2>&1; }
BASE=""
# (/usr/bin/python3 is only real once Apple's command line tools are installed — otherwise running it opens an install dialog)
for c in "$PYTHON" /opt/homebrew/bin/python3 /usr/local/bin/python3 /Library/Frameworks/Python.framework/Versions/Current/bin/python3 \
         "$(xcode-select -p >/dev/null 2>&1 && echo /usr/bin/python3 || true)"; do
  if py_ok "$c"; then BASE="$c"; break; fi
done
[ -n "$BASE" ] || die "Python 3.9 or newer is needed. Install Apple's command line tools (xcode-select --install) or Python from python.org, then run this again"
say "using $BASE"

# -- 2. stop what's running, copy the files -----------------------------------------
step "Files"
for part in api alerts helper; do launchctl bootout "system/$LABEL.$part" >/dev/null 2>&1 || true; done
mkdir -p "$APP"
rm -rf "$LIB"; mkdir -p "$LIB"
cp -R "$SRC/api/." "$LIB/"
for d in web store monitor; do [ -d "$SRC/$d" ] && cp -R "$SRC/$d" "$LIB/$d"; done
cp "$SRC/mac/uninstall.sh" "$LIB/uninstall.sh"
find "$LIB" -name __pycache__ -type d -prune -exec rm -rf {} + 2>/dev/null || true
printf '%s\n' "$VERSION" > "$LIB/VERSION"
chown -R root:wheel "$APP"; chmod -R go-w "$APP"
say "copied to $APP"

# -- 3. Python environment ----------------------------------------------------------
step "Python environment"
if ! py_ok "$PY"; then rm -rf "$VENV"; "$BASE" -m venv "$VENV" || die "couldn't create the Python environment"; fi
"$PY" -m pip install --quiet --disable-pip-version-check --upgrade cryptography psutil \
  || die "couldn't download Nova's two Python libraries (cryptography, psutil) — check the internet connection and run this again"
say "ready"

# -- 4. Nova's own account (no password, no login, hidden from the login window) ------
step "Account"
free_id() {   # the first id from 350 up that no user and no group has
  i=350
  while dscl . -list /Users UniqueID | awk '{print $2}' | grep -qx "$i" || dscl . -list /Groups PrimaryGroupID | awk '{print $2}' | grep -qx "$i"; do i=$((i + 1)); done
  echo "$i"
}
if ! dscl . -read /Groups/nova-api >/dev/null 2>&1; then
  ID="$(free_id)"
  dscl . -create /Groups/nova-api; dscl . -create /Groups/nova-api PrimaryGroupID "$ID"
  dscl . -create /Groups/nova-api RealName "Nova"; dscl . -create /Groups/nova-api Password '*'
fi
GID="$(dscl . -read /Groups/nova-api PrimaryGroupID | awk '{print $2}')"
if ! dscl . -read /Users/nova-api >/dev/null 2>&1; then
  ID="$GID"; dscl . -list /Users UniqueID | awk '{print $2}' | grep -qx "$ID" && ID="$(free_id)"
  dscl . -create /Users/nova-api; dscl . -create /Users/nova-api UniqueID "$ID"; dscl . -create /Users/nova-api PrimaryGroupID "$GID"
  dscl . -create /Users/nova-api UserShell /usr/bin/false; dscl . -create /Users/nova-api NFSHomeDirectory /var/empty
  dscl . -create /Users/nova-api RealName "Nova server"; dscl . -create /Users/nova-api Password '*'; dscl . -create /Users/nova-api IsHidden 1
  say "created the account nova-api (it can't sign in)"
else say "nova-api"; fi

# -- 5. settings and data folders (only Nova's account and administrators can read them) ----
step "Settings"
mkdir -p "$DATA"; chown root:wheel "$DATA"; chmod 755 "$DATA"
own() { mkdir -p "$DATA/$1"; chown "$2" "$DATA/$1"; chmod "$3" "$DATA/$1"; }
own config        root:nova-api     750      # the API reads its settings...
own config/alerts root:wheel        700      # ...but not the Discord webhook
own data          nova-api:nova-api 750      # devices, pairing, its certificate
own log           root:nova-api     770
own run           root:nova-api     750      # where the helper listens
own run/tasks     root:wheel        755
own alerts        root:wheel        755
own alerts/www    root:wheel        755      # status and Inbox, written by the monitor: readable by everyone here, as on Linux (hub apps read it)
own apps          root:wheel        755
R="$("$PY" "$LIB/autosetup.py")" || die "couldn't write the settings: $R"
cfg() { "$PY" -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2]) or "")' "$DATA/config/config.json" "$1"; }
HOST="$(cfg lan_host)"; PORT="$(cfg port)"; TLS="$(cfg tls_port)"; TAIL="$(cfg tailnet_host)"
say "home-network address $HOST"
[ -n "$TAIL" ] && say "Tailscale address    $TAIL"

# -- 6. the three background services ------------------------------------------------
step "Background services"
plist() {   # part, account ("" = root), what it is
  f="/Library/LaunchDaemons/$LABEL.$1.plist"
  {
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict>\n'
    printf '  <key>Label</key><string>%s.%s</string>\n' "$LABEL" "$1"
    printf '  <key>ProgramArguments</key><array><string>%s</string><string>%s/runpart.py</string><string>%s</string></array>\n' "$PY" "$LIB" "$1"
    printf '  <key>WorkingDirectory</key><string>%s</string>\n' "$LIB"
    [ -n "$2" ] && printf '  <key>UserName</key><string>%s</string><key>GroupName</key><string>%s</string>\n' "$2" "$2"
    printf '  <key>EnvironmentVariables</key><dict><key>PATH</key><string>/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin</string></dict>\n'
    printf '  <key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>ThrottleInterval</key><integer>10</integer>\n'
    printf '</dict></plist>\n'
  } > "$f"
  chown root:wheel "$f"; chmod 644 "$f"
  say "$1 (${2:-root}) — $3"
}
plist helper ""       "the only part allowed to manage this Mac; reachable from this Mac only"
plist api    nova-api "what the Nova app and browsers talk to; no administrator rights"
plist alerts ""       "watches the server and fills the Inbox"

# -- 7. firewall, command, the Nova Server app ----------------------------------------
FW=/usr/libexec/ApplicationFirewall/socketfilterfw
if [ -x "$FW" ] && "$FW" --getglobalstate 2>/dev/null | grep -qi enabled; then
  step "Firewall"
  REAL="$("$PY" -c 'import psutil; print(psutil.Process().exe())' 2>/dev/null || true)"
  for exe in "$REAL" "$PY"; do [ -n "$exe" ] && { "$FW" --add "$exe" >/dev/null 2>&1 || true; "$FW" --unblockapp "$exe" >/dev/null 2>&1 || true; }; done
  say "the Mac's firewall is on: Nova's Python is allowed to accept connections"
fi
mkdir -p /usr/local/bin
printf '#!/bin/sh\nexec "%s" "%s/nova" "$@"\n' "$PY" "$LIB" > /usr/local/bin/nova; chmod 755 /usr/local/bin/nova
B="/Applications/Nova Server.app"; rm -rf "$B"; mkdir -p "$B/Contents/MacOS" "$B/Contents/Resources"
printf '#!/bin/sh\n# Nova Server: status, pairing, devices and approvals (macOS asks for your password; the window opens in your browser)\nexec "%s" "%s/nova-gui" "$@"\n' "$PY" "$LIB" > "$B/Contents/MacOS/NovaServer"
chmod 755 "$B/Contents/MacOS/NovaServer"
cat > "$B/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>Nova Server</string><key>CFBundleDisplayName</key><string>Nova Server</string>
  <key>CFBundleIdentifier</key><string>app.novalabs.nova.server</string><key>CFBundleExecutable</key><string>NovaServer</string>
  <key>CFBundlePackageType</key><string>APPL</string><key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>LSUIElement</key><true/>
</dict></plist>
PLIST

# -- 8. start ---------------------------------------------------------------------------
RUNNING=false
if [ "$NOSTART" = 0 ]; then
  step "Starting"
  for part in helper api alerts; do
    launchctl enable "system/$LABEL.$part" >/dev/null 2>&1 || true
    launchctl bootstrap system "/Library/LaunchDaemons/$LABEL.$part.plist" || say "(couldn't start $part — see $DATA/log/$part.log)"
  done
  i=0
  while [ $i -lt 40 ]; do
    if curl -fs -m 3 "http://127.0.0.1:$PORT/api/v1/ping" >/dev/null 2>&1; then RUNNING=true; break; fi
    i=$((i + 1)); sleep 0.5
  done
  if [ "$RUNNING" = true ]; then say "Nova is running."; else printf '    \033[33mNova did not answer yet - see %s/log/api.log\033[0m\n' "$DATA"; fi
fi

DEVICES="$("$PY" -c 'import json,sys
try: print(len(json.load(open(sys.argv[1]))))
except Exception: print(0)' "$DATA/data/devices.json")"
printf '\n  \033[32mDone.\033[0m\n'
if [ "$DEVICES" = 0 ]; then printf '  Next, pair your phone:  open "Nova Server" in Applications -> Pair a phone   (or:  sudo nova add)\n'
else printf '  Your %s paired device(s) keep working.   Nova Server in Applications shows them.\n' "$DEVICES"; fi
printf '  Nova web:     https://%s:%s   (on this Mac: https://localhost:%s)\n' "$HOST" "$TLS" "$TLS"
[ -n "$TAIL" ] && printf '  Tailscale:    https://%s:%s\n' "$TAIL" "$TLS"
printf '  Check-up:     sudo nova doctor      Remove:  sudo nova uninstall\n\n'
URLS="\"local\":\"https://127.0.0.1:$TLS\",\"home\":\"https://$HOST:$TLS\",\"plain\":\"http://127.0.0.1:$PORT\""
[ -n "$TAIL" ] && URLS="$URLS,\"tailnet\":\"https://$TAIL:$TLS\""
result "{\"ok\":true,\"version\":\"$VERSION\",\"previous\":\"$PREV\",\"firstRun\":$FIRST,\"running\":$RUNNING,\"devices\":$DEVICES,\"ports\":{\"https\":$TLS,\"http\":$PORT},\"urls\":{$URLS}}"
