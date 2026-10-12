package app.novalabs.nova

import androidx.compose.runtime.Composable

/** Questions people ask before trusting Nova with their server — the same answers as the website's FAQ. */
private val FAQ = listOf(
    "Safety and privacy" to listOf(
        "Can Nova read or send away my data?" to "No. Nova runs on your own server and the app only ever talks to that server. There's no Nova account, no Nova cloud and no analytics or tracking of any kind. Your files, photos and settings never pass through anyone else. The code is open source, so anyone can check this for themselves.",
        "What does Nova connect to on the internet?" to "Only the services you'd expect, and never with your data. GitHub, to check for and download signed Nova updates. Your system's package mirrors and container registries (Docker Hub, GitHub's registry and others), when you install or update programs and apps. Cloudflare and Tailscale, only if you set them up for remote access. Discord, only if you add a webhook for alerts. Docker Hub's search, when you search the app store. A speed-test server, when you run a speed test. A public icon set, for app icons. DuckDuckGo, for search suggestions on the Start page as you type (you can turn them off).",
        "Is it safe to have on my server?" to "Nova's API runs as its own unprivileged user in a systemd sandbox. The only part with root rights is a small helper that can run a fixed list of commands, and each one checks its input. Your containers, files and SSH settings are left alone unless you ask Nova to change them, and the risky changes also need your fingerprint.",
        "What can someone do with a stolen phone?" to "Very little. This app's key only works after the phone is unlocked, and anything risky needs your fingerprint too. Remove a lost phone in Users & devices from another phone, or with sudo nova remove NAME on the server, and its key stops working instantly.",
        "How is Nova tested?" to "Every release goes through an automated security test of more than 400 checks against a real server: forged and replayed requests, stolen keys, path tricks, oversized requests, brute force, and more. Releases are signed, and the server only installs updates whose signature checks out."),
    "Undoing things" to listOf(
        "How do I uninstall Nova completely?" to "sudo apt remove nova-server stops Nova and removes it. Your containers, apps and data stay exactly as they are. sudo apt purge nova-server also deletes Nova's settings and the list of paired devices. If Nova put apps on your Cloudflare account, run sudo nova cloudflare undo first: it lists what it added and asks before taking anything off.",
        "Can I undo what Nova changed on Cloudflare?" to "Yes. Nova keeps a record of exactly what it added, and only ever removes those things. sudo nova cloudflare list shows them and sudo nova cloudflare undo takes them off. An address you had before Nova protected it keeps its login unless you say otherwise.",
        "An update broke a container. Can I go back?" to "Yes. When Nova updates a container it keeps the version it replaced. If the new one won't start, Nova puts the old one back by itself. Any time later, open Containers → the container → Troubleshoot → Go back to the previous version.",
        "I ignored an alert or archived something by mistake." to "Ignored alerts that are still going on stay in the Inbox under “Ignored”, with a Stop ignoring button. Archived events are kept on the server, and you can move them back from Inbox → Archive.",
        "Can I go back to an older Nova version?" to "Yes. Download that version's .deb from the releases page and install it with sudo apt install ./nova-server_VERSION_all.deb (apt asks you to confirm going back). Your settings and paired devices stay on the server."),
    "Using it" to listOf(
        "Can I use several servers?" to "Yes. Pair the app with each one (Menu → Servers) and switch between them. In Nova web you can also control them all from one page: approve your browser on each server once.",
        "Do I need to open ports on my router?" to "No. At home the app talks to the server directly. For access from anywhere, Nova uses a Cloudflare Tunnel with Cloudflare Access in front, or Tailscale if you already use it."),
)

@Composable fun FaqScreen(app: AppState) {
    Page("Questions", app::back) {
        FAQ.forEach { (title, qs) ->
            SectionLabel(title)
            Group { qs.forEachIndexed { i, (q, a) -> if (i > 0) RowDivider(); ExpandRow(q) { Detail(a) } } }
        }
    }
}
