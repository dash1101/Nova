#!/bin/sh
# Remove Nova that was installed from the tarball or nova-installer.run (Fedora, Arch, openSUSE…).
# A .deb install is removed with apt instead. Easiest either way:  sudo nova uninstall [--purge]
#   sudo sh /usr/lib/nova-api/uninstall.sh            remove Nova; its settings and paired devices stay (for a reinstall)
#   sudo sh /usr/lib/nova-api/uninstall.sh --purge    remove its settings and paired devices too
# Your containers, files and backups are never touched.
set -eu
[ "$(id -u)" = 0 ] || { echo "Run with sudo:  sudo sh $0 $*"; exit 1; }
PURGE=0; YES=0
for a in "$@"; do case "$a" in --purge) PURGE=1;; --yes|-y) YES=1;; esac; done
say() { printf '  %s\n' "$*"; }
if [ -f /var/lib/dpkg/info/nova-server.list ]; then
    say "Nova was installed as a package here — remove it with apt:  sudo apt remove nova-server   (or: sudo apt purge nova-server)"; exit 1
fi
if [ "$YES" = 0 ]; then
    if [ "$PURGE" = 1 ]; then q="Remove Nova, its settings and its list of paired devices?"
    else q="Remove Nova? Its settings and paired devices stay, for a later reinstall."; fi
    printf '\n  %s [y/N] ' "$q"
    { read -r c </dev/tty; } 2>/dev/null || c=n
    case "$c" in y|Y|yes|Yes) ;; *) say "Nothing changed."; exit 0;; esac
fi
# What the .deb does on its way out (prerm): Cloudflare is left as it is — that's your call
if [ -s /etc/nova-api/cloudflare-added.json ] && [ "$(cat /etc/nova-api/cloudflare-added.json)" != "{}" ]; then
    say "Note: Nova put apps on your Cloudflare tunnel. They stay there after Nova is removed."
    say "      To take them off first:  sudo nova cloudflare undo   (it lists them and asks), then run this again."
fi
systemctl disable --now nova-api.service nova-gate.service nova-helper.socket nova-rgb-anim.service nova-rgb-tick.timer nova-alerts.timer nova-alerts.path nova-backups.timer nova-labs.timer nova-update-check.timer nova-brain.service nova-ear.service >/dev/null 2>&1 || true
for t in /etc/systemd/system/nova-sched-*.timer; do [ -e "$t" ] || continue; systemctl disable --now "$(basename "$t")" >/dev/null 2>&1 || true; rm -f "$t" "${t%.timer}.service"; done
# Nova's own files (the same list the tarball put there)
rm -f /usr/bin/nova /usr/sbin/nova-api /usr/sbin/nova-alert /usr/sbin/nova-setup /usr/sbin/nova-setup-gui /usr/sbin/nova-setup-lighting \
      /usr/sbin/nova-gui /usr/sbin/nova-update /usr/sbin/nova-app-publish
for u in /usr/lib/systemd/system/nova-*.service /usr/lib/systemd/system/nova-*.timer /usr/lib/systemd/system/nova-*.socket /usr/lib/systemd/system/nova-*.path \
         /usr/lib/systemd/system/nova-helper@.service; do rm -f "$u"; done
rm -f /usr/share/applications/nova-server.desktop /usr/share/applications/nova-setup.desktop /usr/share/icons/hicolor/scalable/apps/nova.svg \
      /usr/share/polkit-1/actions/app.novalabs.nova.policy /etc/logrotate.d/nova-api /etc/update-motd.d/60-nova
rm -rf /usr/share/doc/nova-server /usr/lib/nova-rgb
if [ "$PURGE" = 1 ]; then              # the .deb's postrm purge: settings and paired devices
    rm -rf /etc/nova-api /var/lib/nova-api /var/log/nova-api /var/lib/nova-rgb /var/lib/nova-alerts /etc/nova-alerts /etc/nova-backup /var/lib/nova-backups /var/lib/nova-gate
    rm -f /etc/systemd/system/nova-api.service.d/rgb.conf /etc/udev/rules.d/60-nova-rgb.rules
    rm -rf /var/lib/nova-brain; rm -f /etc/systemd/system/nova-brain.service /etc/systemd/system/multi-user.target.wants/nova-brain.service \
                                      /etc/systemd/system/nova-ear.service /etc/systemd/system/multi-user.target.wants/nova-ear.service
    getent passwd nova-api >/dev/null && userdel nova-api || true
    getent passwd nova-gate >/dev/null && userdel nova-gate || true
fi
rm -rf /usr/lib/nova-api                # last: this script lives there (the shell already has it open)
systemctl daemon-reload >/dev/null 2>&1 || true
echo
say "✓ Nova is removed. Your containers, files and backups weren't touched."
[ "$PURGE" = 1 ] || say "Its settings and paired devices stay in /etc/nova-api and /var/lib/nova-api for a reinstall (sudo rm -rf those two removes them)."
