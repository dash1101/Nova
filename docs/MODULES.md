# Optional modules

| Module | Install | What it adds |
|---|---|---|
| `fan-gigabyte-fusion2` | `install.sh --with lighting` | Fan/case LED control for Gigabyte RGB Fusion 2 (USB `048d:5702`): colors and palettes, effects (incl. software wave, comet, scanner, twinkle, fire, breathe), presets, schedules (time or sunrise/sunset, fades, end times), a status light that turns amber/red with server health. |
| your own monitor | see below | Feed Nova alerts and richer status from any monitoring script. |

The API detects which modules are present and reports them as `features` in `/api/v1/overview`;
the app hides anything that isn't available.

To support another lighting controller, implement the same small interface as `nova_rgb.py`
(`load`, `save`, `validate`, `apply`, `status_override`) and put it at `/usr/local/lib/nova-rgb/nova_rgb.py`.

## Bring your own monitor

Without a monitor, Nova shows a basic built-in status (CPU temperature, memory, disk space, and an
"all normal" headline unless something is obviously wrong). For alerts and richer status, have any
script write two JSON files that nova-api can read:

- `/var/lib/nova-alerts/www/status.json`:
  `{"level": "ok|warning|critical", "headline": "…", "active_count": 0, "active": [{"level", "title", "detail", "since"}], "metrics": {"cpu_temp": "41°C", "photo_pool_used": "83% (344 GB free)", …}, "updated_local": "Oct 08 09:50"}`
- `/var/lib/nova-alerts/www/events.json`:
  `{"events": [{"t": 1791440000.0, "level": "warning", "title": "…", "detail": "…", "category": "login|disk|…"}]}` (newest first)

The app's inbox, the instant notifications, the status light and the status page all read these.
Metric keys the app knows how to display: `cpu_temp`, `memory`, `swap`, `uptime`, `root_used`,
`photo_pool_used`, `cold_storage_used`, `backup_drive_used`, `drives`, `drive_temps`, `containers`,
`websites`, `data_backup`, `backup_sets`, `backup_verify`, `config_backup`.
