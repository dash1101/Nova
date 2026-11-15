#!/usr/bin/env bash
# Set a freshly installed Nova up without questions on a CI machine (what a person does with sudo nova setup),
# wait until it answers, and say where it is (NOVA_HOST, for the tests). Used by .github/workflows/linux.yml.
set -euo pipefail
IP=$(ip -4 route get 1.1.1.1 | grep -oP 'src \K\S+'); NET=$(ip -4 -o addr show | awk -v ip="$IP" '$4 ~ "^"ip"/" {print $4}')
NET=$(python3 -c "import ipaddress,sys; print(ipaddress.ip_interface(sys.argv[1]).network)" "$NET")
printf '{"lan_host": "%s", "lan_subnet": "%s", "install_tailscale": false, "install_cloudflared": false, "remote_manual": false, "discord_webhook": "", "install_docker": false, "install_tools": false, "assistant": false, "ssh_user": "runner", "advanced": false, "location": "", "firewall": true}' "$IP" "$NET" > /tmp/answers.json
sudo nova-setup --answers /tmp/answers.json --yes
for i in $(seq 30); do curl -sf http://127.0.0.1:8095/api/v1/ping && break; sleep 1; done
echo
[ -n "${GITHUB_ENV:-}" ] && echo "NOVA_HOST=$IP" >> "$GITHUB_ENV"
