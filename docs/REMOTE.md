# Remote access with Cloudflare (no VPN)

You need a domain on Cloudflare and `cloudflared` on the server.

1. **Tunnel.** Zero Trust → Networks → Tunnels → *Create a tunnel* (cloudflared). Install the
   connector it shows, then add a *public hostname*, e.g. `nova.example.com` → `http://127.0.0.1:8095`.
2. **Service token.** Zero Trust → Access → Service auth → *Create service token*. Copy the
   Client ID and Client Secret (the secret is shown once).
3. **Access application.** Access → Applications → *Self-hosted*, domain `nova.example.com`.
   Add a policy with action **Service Auth** that includes your service token. Copy the
   application's **AUD tag** (Overview tab).
4. **Tell Nova.** Edit `/etc/nova-api/config.json`:
   ```json
   "remote_url": "https://nova.example.com",
   "cf_access_team": "yourteam.cloudflareaccess.com",
   "cf_access_aud": "<AUD tag>",
   "cf_client_id": "<client id>.access",
   "cf_client_secret": "<client secret>"
   ```
   then `sudo systemctl restart nova-api`.
5. **Phones.** Pair (or just open the app) once on the home network. The app picks up the remote
   settings automatically, and the secret is stored encrypted by the phone's keystore.

From then on the app uses the LAN when it's home and the tunnel everywhere else.

## Using Nova web remotely

The service-token policy only lets the app through; a browser gets **403** from Cloudflare. To open
Nova web from anywhere, let *you* log in to Cloudflare as well:

1. **Login method.** Zero Trust → Settings → Authentication → Login methods → *Add new* →
   **One-time PIN** (Cloudflare emails you a code). Any identity provider works too.
2. **Second policy.** Access → Applications → your Nova application → Policies → *Add a policy*:
   - Action: **Allow**
   - Include: **Emails** → your email address
   - Keep the existing **Service Auth** policy (that's the app's).
3. Open `https://nova.example.com` in the browser. Cloudflare asks for your email and a PIN, then
   Nova web loads. Choose **Get a code**, and approve it in the app on an admin phone
   (Menu → Users & devices → *Approve a browser*).

Nova still needs the browser's own signing key on top of the Cloudflare login, and risky actions
from a browser are always approved on your phone.
