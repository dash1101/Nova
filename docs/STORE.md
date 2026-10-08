# Adding an app to the store

Each file in `server/store/docker/` is one app:

```json
{
  "id": "uptime-kuma",                 // folder name under /opt/<id>; letters, digits, dashes
  "name": "Uptime Kuma",
  "category": "Monitoring",            // groups apps in the store
  "icon": "monitor_heart",             // see storeIcon() in android/.../Store.kt
  "port": 3201,                        // host port; the app warns if it's taken
  "path": "/",                         // what "Open" opens
  "image": "louislam/uptime-kuma:1",
  "description": "One or two sentences.",
  "notes": "Optional 'good to know' line (first-run steps, default passwords…)",
  "compose": "services:\n  uptime-kuma:\n    image: …\n    ports:\n      - \"{PORT}:3001\"\n    volumes:\n      - {DATA}:/app/data\n"
}
```

`{PORT}` and `{DATA}` (`/opt/<id>/data`) are filled in at install time. Uninstalling stops the app
and moves its folder to `/opt/.nova-uninstalled/`, so the data is never deleted.

`server/store/programs.json` lists apt packages for the *Programs* tab (`pkg`, `name`, `category`,
`description`, optional `"protected": true` for packages that may not be removed).
