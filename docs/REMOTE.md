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

The service-token policy only lets the app through. To use the web version from anywhere, add a
second policy to the same Access application: **Action: Allow, Include: Emails → your address**
(and set up the One-time PIN login method, or your identity provider). Cloudflare then asks you to
log in before the page loads, and Nova still requires the browser's own key on top of that.
