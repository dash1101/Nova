#!/usr/bin/env bash
# Install Nova from a source checkout (Debian/Ubuntu: as a .deb; any other Linux with systemd: as the tarball).
#
#   sudo ./install.sh                  build the package, install it, run the setup wizard
#   sudo ./install.sh --with lighting  …and turn on the Gigabyte RGB Fusion 2 lighting module
#   sudo ./install.sh --yes            no questions (accept detected defaults)
#
# Same as:  packaging/build-deb.sh && apt install ./dist/nova-server_*.deb && nova-setup
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(dirname "$HERE")"
LIGHTING=0; SETUP_ARGS=()
for a in "$@"; do
    case "$a" in
        --with) ;; lighting) LIGHTING=1 ;;
        --yes) SETUP_ARGS+=(--yes) ;;
        -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
        *) echo "unknown option: $a" >&2; exit 2 ;;
    esac
done
[[ $EUID -eq 0 ]] || { echo "run with sudo" >&2; exit 1; }
if ! command -v dpkg-deb >/dev/null; then              # Fedora, Arch, openSUSE…: the same files, installed by install.sh
    echo "==> Building the tarball"
    VERSION="$(grep -oP 'API_VERSION = "\K[^"]+' "$ROOT/server/api/server.py" | sed 's/-/~/')"
    bash "$ROOT/packaging/build-tar.sh"
    T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
    tar -xzf "$ROOT/dist/nova-server_${VERSION}.tar.gz" -C "$T"
    sh "$T/nova-server-${VERSION}/install.sh" --upgrade
    nova-setup "${SETUP_ARGS[@]}"
    [[ $LIGHTING -eq 1 ]] && nova-setup-lighting
    exit 0
fi

echo "==> Building the package"
bash "$ROOT/packaging/build-deb.sh"
DEB="$(ls -t "$ROOT"/dist/nova-server_*_all.deb | head -1)"

echo "==> Installing $(basename "$DEB")"
apt-get update -qq
apt-get install -y "$DEB"
if [[ -d /usr/local/lib/nova-api ]]; then
    echo "   note: an older source install lives in /usr/local/lib/nova-api; the package uses /usr/lib/nova-api."
    echo "   After checking the new one works:  sudo rm -rf /usr/local/lib/nova-api"
fi

echo "==> Setup"
nova-setup "${SETUP_ARGS[@]}"
[[ $LIGHTING -eq 1 ]] && nova-setup-lighting
exit 0
