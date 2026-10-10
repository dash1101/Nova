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

`{PORT}`, `{DATA}` (`/opt/<id>/data`), `{TZ}` (the server's time zone) and `{SECRET1}`…`{SECRET3}`
(random passwords, saved to `/opt/<id>/.nova-secrets`) are filled in at install time. `"featured": true`
puts an app under *Editor's choice*. Use generic host folders (`/srv/media`, `/srv/books`…) — never a
path from your own machine. Uninstalling stops the app
and moves its folder to `/opt/.nova-uninstalled/`, so the data is never deleted.

Anything not in the catalog: the store's search also looks on Docker Hub, and a result opens
*Your own container* with the image filled in.

`server/store/programs.json` lists the hand-picked apt packages for the *Programs* tab (the search
there covers everything apt offers) (`pkg`, `name`, `category`,
`description`, optional `"protected": true` for packages that may not be removed).
