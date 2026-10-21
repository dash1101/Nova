#!/bin/sh
# Install (or update) Nova on any Linux with systemd: Debian/Ubuntu, Fedora/RHEL, Arch, openSUSE…
#   sudo ./install.sh            install, then start the setup (in the terminal, or --gui in a browser)
#   sudo ./install.sh --upgrade  replace the files and restart (what nova-update does)
# On Debian and Ubuntu the .deb is the better choice (apt keeps track of it); this does the same by hand.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
[ "$(id -u)" = 0 ] || { echo "Run with sudo:  sudo $0 $*"; exit 1; }
command -v systemctl >/dev/null || { echo "Nova needs systemd."; exit 1; }
UPGRADE=0; GUI=0
for a in "$@"; do case "$a" in --upgrade) UPGRADE=1;; --gui) GUI=1;; esac; done
say() { printf '  %s\n' "$*"; }

# what Nova needs, in this distro's words
if command -v apt-get >/dev/null; then PM=apt; DEPS="python3 python3-cryptography qrencode iproute2 rsync parted util-linux"
elif command -v dnf >/dev/null; then PM=dnf; DEPS="python3 python3-cryptography qrencode iproute rsync parted util-linux"
elif command -v pacman >/dev/null; then PM=pacman; DEPS="python python-cryptography qrencode iproute2 rsync parted util-linux"
elif command -v zypper >/dev/null; then PM=zypper; DEPS="python3 python3-cryptography qrencode iproute2 rsync parted util-linux"
else echo "No package manager Nova knows (apt, dnf, pacman, zypper). Install by hand: python3 (3.10+), python3-cryptography, qrencode, iproute2, rsync, parted."; PM=""; fi
if [ "$UPGRADE" = 0 ] && [ -n "$PM" ]; then
    say "Installing what Nova needs ($PM)…"
    case "$PM" in
        apt) DEBIAN_FRONTEND=noninteractive apt-get install -y $DEPS >/dev/null ;;
        dnf) dnf install -y $DEPS >/dev/null ;;
        pacman) pacman -S --noconfirm --needed $DEPS >/dev/null ;;
        zypper) zypper --non-interactive install $DEPS >/dev/null ;;
    esac
fi
python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)' || { echo "Nova needs Python 3.10 or newer."; exit 1; }
python3 -c 'import cryptography' 2>/dev/null || { echo "Nova needs the Python cryptography module (python3-cryptography)."; exit 1; }

say "Copying Nova's files…"
# (tar, not cp: on merged-/usr systems /usr/sbin is a link to bin, and it has to stay one)
tar -C "$HERE/root/usr" -cf - . | tar -C /usr --keep-directory-symlink --no-same-owner -xf -
mkdir -p /usr/lib/systemd/system /etc/logrotate.d
tar -C "$HERE/root/lib/systemd/system" -cf - . | tar -C /usr/lib/systemd/system --keep-directory-symlink --no-same-owner -xf -
[ -f /etc/logrotate.d/nova-api ] || cp "$HERE/root/etc/logrotate.d/nova-api" /etc/logrotate.d/
[ -d /etc/update-motd.d ] && cp "$HERE/root/etc/update-motd.d/60-nova" /etc/update-motd.d/ 2>/dev/null || true   # "not set up yet" at login
chown -R root:root /usr/lib/nova-api /usr/lib/nova-rgb 2>/dev/null || true
# the same first-time setup the .deb does (user, folders, timers), then start
sed 's/^if \[ "\$1" = configure \]; then/if true; then/' "$HERE/postinst" > /tmp/nova-postinst.$$ && sh /tmp/nova-postinst.$$; rm -f /tmp/nova-postinst.$$
echo "$(cat /usr/lib/nova-api/VERSION)" > /usr/lib/nova-api/INSTALLED_FROM_TARBALL
if [ "$UPGRADE" = 0 ] && [ ! -f /etc/nova-api/config.json ]; then
    if [ "$GUI" = 1 ]; then exec /usr/sbin/nova-setup --gui; fi
    printf '\n  Nova is installed. Set it up now?\n    1  In this terminal\n    2  In a browser (easier)\n    q  Later (sudo nova-setup)\n\n  Choose [2]: '
    read -r c </dev/tty || c=q
    case "${c:-2}" in 1) exec /usr/sbin/nova-setup;; 2) exec /usr/sbin/nova-setup --gui;; esac
fi
