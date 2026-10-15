#!/bin/sh
# Install Nova on this Linux server:   curl -fsSL https://raw.githubusercontent.com/dash1101/Nova/main/get.sh | sh
#
# Downloads the latest release, checks it against the release's signed SHA256SUMS, and installs it:
# the .deb on Debian/Ubuntu (so apt updates it like any package), the one-file installer everywhere else.
# Then run  sudo nova setup  (or  sudo nova setup --gui  for the setup in a browser).
set -eu
REPO=dash1101/Nova
BASE="https://github.com/$REPO/releases/latest/download"
KEY_URL="https://raw.githubusercontent.com/$REPO/main/packaging/release-key.pub"
say() { printf '  %s\n' "$*"; }
die() { printf '  \033[31m%s\033[0m\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = Linux ] || die "Nova's server runs on Linux (Windows and macOS aren't supported yet). Nova web works in any browser."
[ -d /run/systemd/system ] || die "Nova needs systemd (it runs as a systemd service)."
if command -v curl >/dev/null 2>&1; then get() { curl -fsSL --retry 3 -o "$2" "$1"; }
elif command -v wget >/dev/null 2>&1; then get() { wget -q -O "$2" "$1"; }
else die "Needs curl or wget."; fi
SUDO=""; [ "$(id -u)" = 0 ] || { command -v sudo >/dev/null 2>&1 || die "Run as root, or install sudo."; SUDO=sudo; }

T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
say "Getting the latest Nova release…"
get "$BASE/SHA256SUMS" "$T/SHA256SUMS" || die "Couldn't reach GitHub."
get "$BASE/SHA256SUMS.sig" "$T/SHA256SUMS.sig"

# The checksums are signed with Nova's release key (ed25519). Check it when this system's openssl can.
if command -v openssl >/dev/null 2>&1 && get "$KEY_URL" "$T/key.pub" 2>/dev/null && base64 -d "$T/SHA256SUMS.sig" > "$T/sig.bin" 2>/dev/null; then
  if openssl pkeyutl -verify -pubin -inkey "$T/key.pub" -rawin -in "$T/SHA256SUMS" -sigfile "$T/sig.bin" >/dev/null 2>&1; then say "✓ Release signature checks out"
  elif openssl pkeyutl -help 2>&1 | grep -q rawin; then die "The release's signature doesn't match — not installing."
  else say "(this openssl can't check ed25519 signatures; checking the file's checksum only)"; fi
fi

if command -v apt-get >/dev/null 2>&1 && command -v dpkg >/dev/null 2>&1; then
  F=$(sed -n 's/^[0-9a-f]\{64\}  \(nova-server_.*_all\.deb\)$/\1/p' "$T/SHA256SUMS" | head -1)
else
  F=nova-installer.run
fi
[ -n "$F" ] || die "The release has no server package."
say "Downloading $F…"
get "$BASE/$(printf %s "$F" | tr '~' '.')" "$T/$F"          # (GitHub stores "~" in file names as ".")
(cd "$T" && grep "  $F\$" SHA256SUMS | sha256sum -c --status) || die "$F doesn't match its checksum — not installing."
say "✓ $F checks out"

case "$F" in
  *.deb) $SUDO apt-get install -y "$T/$F" ;;
  *)     if [ -r /dev/tty ]; then $SUDO sh "$T/$F" </dev/tty; else $SUDO sh "$T/$F"; fi ;;
esac
printf '\n  \033[1mNova is installed.\033[0m Next:  sudo nova setup   (or: sudo nova setup --gui for the setup in a browser)\n\n'
