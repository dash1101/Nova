# Monitoring & alerts

Nova's monitor is part of the server package. It runs once a minute (`nova-alerts.timer`) and
feeds the Status page, the Inbox, the status light, phone notifications and (optionally) Discord.
It works without any settings on a normal Debian/Ubuntu machine:

| Watches | How it decides what to watch |
|---|---|
| Mounts | Every real filesystem in `/etc/fstab` must be mounted (except `noauto` and `/boot`) |
| Free space | Those same mounts plus `/`; the branches of a mergerfs pool are skipped (the pool counts) |
| Drives | Every disk by serial: one that disappears is reported missing; SMART health, temperature, error counters |
| Services | Whichever of ssh, docker, cloudflared, tailscaled, fail2ban, cron, nova-api are installed and enabled |
| Containers | Down or unhealthy for 3 minutes (one-off containers are ignored) |
| Logs | Logins (SSH, Tailscale SSH, console), failed logins, disk I/O errors, USB plug/unplug, OOM kills, failed units |
| Temperatures | CPU (AMD, Intel, Raspberry Pi), NVMe, SATA drives |

## Settings — `/etc/nova-alerts/config.json` (root only)

```json
{
  "discord_webhook": "https://discord.com/api/webhooks/…",
  "public_urls": ["https://example.com"],
  "mount_names": { "/mnt/media": "Media", "/srv/backup": "Backups" },
  "db_dumps": [{ "name": "Photo database", "glob": "/srv/photos/backups/*.sql.gz", "metric": "photos_db_backup" }],
  "required_mounts": "auto",
  "disk_space_mounts": "auto",
  "required_services": "auto",
  "thresholds": { "disk_warn_pct": 90, "cpu_temp_warn": 85 }
}
```

Lists replace the automatic choice (`"required_services": ["docker", "ssh"]`). Phone and Discord
choices (levels, logins, USB, pausing Discord) are set from the app.

## Dealing with alerts

- **Ignore** (swipe right in the app or web): the alert stays quiet while the problem lasts and
  re-arms once it clears, so you hear about it next time.
- **Removed on purpose** (for a missing drive): forgets the drive completely.
- **Mount it now** (for a "Not mounted" alert): mounts it from `/etc/fstab` — handy for a drive
  that was plugged in after boot.
- **Delete** (swipe left in the Inbox) removes past events for every device.

From a shell: `sudo nova-alert info "Title" "details"` sends an alert;
`sudo python3 /usr/lib/nova-api/monitor/nova_alerts.py dismiss <key>` or `forget-drive <serial>`.

## Drive health tests

Storage & hardware → a drive → **Quick test** (a minute or two) or **Full surface scan** (hours;
best for second-hand drives). They're SMART self-tests: read-only and safe while the drive is in
use. Needs `smartmontools` (installed with the package by default).
