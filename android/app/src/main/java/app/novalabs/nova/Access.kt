package app.novalabs.nova

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Who can open an address on your Cloudflare tunnel — only you (your Cloudflare login, like Nova),
 * you and guests (people you add by email; they get a one-time code by email), or anyone — and, for
 * apps that have one (Immich), public share links on their own address while the app stays private.
 * Everything here can be changed again later; every change asks for your fingerprint.
 */
/** Changes in flight, per address (kept outside the screen, so leaving and coming back still shows it — and blocks a second tap). */
object AccessBusy { val work = androidx.compose.runtime.mutableStateMapOf<String, String>() }

/** A Cloudflare change can take a while: a spinner and what's happening, instead of a screen that looks stuck. */
@Composable fun WorkingRow(text: String) = Group {
    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OneSpinner(22.dp); Spacer(Modifier.width(14.dp))
        Column { Text(text, color = N.text, fontSize = 16.sp); Text("This can take up to a minute — no need to tap again.", color = N.sub, fontSize = 13.sp) }
    }
}

private val SESSIONS = listOf("1h" to "1 hour", "24h" to "1 day", "168h" to "1 week", "720h" to "30 days")

@Composable fun AccessScreen(app: AppState, host: String, appId: String) {
    val ctx = LocalContext.current
    var st by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { try { st = app.api.get("/api/v1/cloudflare"); err = null } catch (e: Exception) { err = e.message } }
    val rec = st?.optJSONObject("nova")?.optJSONObject(host)
    val protectedSet = st?.optJSONArray("protected").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet() }
    val onTunnel = st?.optJSONArray("hostnames").let { a -> (0 until (a?.length() ?: 0)).any { a!!.getString(it) == host } }
    val mode = rec?.optString("mode")?.ifEmpty { null } ?: if (host in protectedSet) "me" else "public"
    val outsideNova = rec == null && host in protectedSet          // a login you set up in Cloudflare yourself: Nova leaves it alone
    val isShare = rec?.optString("kind") == "share"

    // guests and session length are edited here, then saved together (one fingerprint)
    var guests by remember(rec?.toString()) { mutableStateOf(rec?.optJSONArray("guests")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()) }
    var session by remember(rec?.toString()) { mutableStateOf(rec?.optString("session")?.ifEmpty { null } ?: "24h") }
    val savedGuests = rec?.optJSONArray("guests")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
    val dirty = mode == "guests" && (guests != savedGuests || session != (rec?.optString("session")?.ifEmpty { null } ?: "24h"))
    var ask by remember { mutableStateOf<String?>(null) }          // the mode being switched to (confirm first)
    var addGuest by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }

    val working = AccessBusy.work[host]
    fun change(to: String, gs: List<String> = guests, publicOk: Boolean = false) {
        if (AccessBusy.work.containsKey(host)) return                       // one change at a time
        app.act {
            val what = when (to) { "me" -> "Only you can open $host"; "guests" -> "You and ${gs.size} guest${if (gs.size == 1) "" else "s"} can open $host"; else -> "Make $host open to anyone" }
            AccessBusy.work[host] = when (to) { "me" -> "Switching to only you…"; "guests" -> if (to == mode) "Saving the guest list…" else "Switching to you and guests…"; else -> "Opening it to anyone…" }
            try {
                app.stepUp(what, "POST", "/api/v1/cloudflare/access", JSONObject().put("host", host).put("mode", to).put("guests", JSONArray(gs)).put("session", session).put("allow_public", publicOk))
                app.toast("Saved"); round++
            } finally { AccessBusy.work.remove(host) }
        }
    }

    Page("Who can open it", app::back) {
        Group {
            Row1(host, if (isShare) "Share links${rec?.optString("for_host")?.ifEmpty { null }?.let { " for $it" } ?: ""}" else
                when { outsideNova -> "Behind a login you set up in Cloudflare"; rec == null && !onTunnel -> "Not on your tunnel"; rec?.optString("kind") == "protected" -> "Yours — Nova manages its login"
                       rec != null -> "Put online by Nova"; else -> "Yours — open to anyone" },
                true, Icons.Rounded.Public, onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://$host"))) } })
        }
        when {
            err != null -> Text(err!!, color = N.sub, modifier = Modifier.padding(30.dp))
            st == null -> Text("Checking…", color = N.sub, modifier = Modifier.padding(30.dp))
            outsideNova -> Text("Its login was set up in Cloudflare, outside Nova, so Nova doesn't change it. Edit it in Zero Trust → Access → Applications.",
                color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
            isShare -> Text("Anyone with a share link can open what you've shared there — nothing else. To stop sharing publicly, take this address off below.",
                color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
            mode == "offline" -> Group { Row1("Off the internet", "A change didn't finish, so Nova took it offline instead of leaving it open. Pick who can open it to put it back.", false, Icons.Rounded.CloudOff, N.amber) }
            else -> {}
        }
        if (st != null && err == null && !outsideNova && !isShare && (rec != null || onTunnel)) {
            SectionLabel("Who can open it")
            working?.let { WorkingRow(it); Spacer(Modifier.height(10.dp)) }
            Group {
                @Composable fun Choice(k: String, title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector) =
                    Row1(title, sub, false, icon, if (k == "public") N.amber else N.blue, enabled = app.isAdmin && working == null, onClick = { if (k != mode) ask = k }) { OneRadio(mode == k) }
                Choice("me", "Only you", "Your Cloudflare login, the same as Nova", Icons.Rounded.Lock); RowDivider()
                Choice("guests", "You and guests", "People you add by email sign in with a one-time code sent to them", Icons.Rounded.Group); RowDivider()
                Choice("public", "Anyone", "No login — anyone with the address can open it", Icons.Rounded.Public)
            }
        }
        if (mode == "guests" && !isShare) {
            SectionLabel("Guests")
            Group {
                if (guests.isEmpty()) Row1("No guests yet", "Add someone by their email address")
                guests.forEachIndexed { i, g -> if (i > 0) RowDivider()
                    Row1(g, null, false, Icons.Rounded.Person, trailing = if (app.isAdmin) ({ IconButton({ guests = guests - g }) { Icon(Icons.Rounded.Close, "Remove", tint = N.sub) } }) else null) }
                if (app.isAdmin) { RowDivider(); Row1("Add a guest", null, true, Icons.Rounded.PersonAdd, onClick = { addGuest = true }) }
            }
            Text("Stays signed in for", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 30.dp, top = 12.dp))
            Segmented(SESSIONS.map { it.second }, SESSIONS.indexOfFirst { it.first == session }.let { if (it < 0) 1 else it }) { session = SESSIONS[it].first }
            if (dirty) Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { PillButton("Undo", color = N.text) { guests = savedGuests; session = rec?.optString("session")?.ifEmpty { null } ?: "24h" } }
                Box(Modifier.weight(1f)) { PillButton(if (working != null) "Saving…" else "Save changes", enabled = working == null) { change("guests") } }
            }
            Text("Guests open the address, type their email, and Cloudflare sends them a code. They can only open this app — not Nova.",
                color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        }
        if (appId.isNotEmpty() && !isShare) ShareLinks(app, appId, host)
        if (rec != null && app.isAdmin) {
            SectionLabel("Take it off")
            Group { Row1(if (rec.optString("kind") == "protected") "Take the login off" else "Take it off the internet",
                if (rec.optString("kind") == "protected") "It was yours before — it goes back to being open to anyone" else "Nova removes what it added: route, DNS record and login",
                false, Icons.Rounded.Delete, N.red, onClick = { remove = true }) }
        }
        Spacer(Modifier.height(40.dp))
    }

    ask?.let { to ->
        val t = when (to) { "me" -> "Only you?"; "guests" -> "You and guests?"; else -> "Open $host to anyone?" }
        val d = when (to) {
            "me" -> "Anyone opening $host signs in with your Cloudflare login first."
            "guests" -> "You, plus people you add by email. You can add them next — until then it's just you." +
                if (mode == "me") " $host is offline for a few seconds while the login switches over." else ""
            else -> "There will be no login at all: anyone who finds the address can open the app. Only do this if the app has its own login you trust."
        }
        OneDialog({ ask = null }, t, d, listOf(DialogButton("Cancel") { ask = null }, DialogButton(if (to == "public") "Make it public" else "Change", if (to == "public") N.red else N.blue) {
            ask = null; change(to, if (to == "guests") guests else emptyList(), publicOk = to == "public") })) {
            if (to != "public" && mode in listOf("public", "offline"))   // going behind a login: the app's own phone app needs a way in
                Text("Heads-up: an app's own phone app (Immich, Jellyfin, Home Assistant…) can't get past a web login by itself — add a Cloudflare Access service token in its settings first (Immich: Settings → Advanced → Custom proxy headers). Links you've already shared at this address stop working for others; for Immich, set up Share links below first.",
                    color = N.amber, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 26.dp))
        }
    }
    if (addGuest) { var e by remember { mutableStateOf("") }
        val ok = Regex("[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}").matches(e.trim())
        OneDialog({ addGuest = false }, "Add a guest", "Their email address. They'll get a one-time code there each time they sign in.",
            listOf(DialogButton("Cancel") { addGuest = false }, DialogButton("Add", N.blue, enabled = ok) { addGuest = false; val g = e.trim().lowercase(); if (g !in guests) guests = guests + g })) {
            OneTextField(e, { e = it.trim().take(120) }, "name@example.com", Modifier.fillMaxWidth().padding(horizontal = 22.dp)) } }
    if (remove) { val prot = rec?.optString("kind") == "protected"
        OneDialog({ remove = false }, if (prot) "Take the login off $host?" else "Take $host off?",
            if (prot) "It goes back to how it was before Nova: open to anyone. The route and DNS record stay." else "Nova removes the route, the DNS record and the login it added. Nothing else in your Cloudflare account changes.",
            listOf(DialogButton("Cancel") { remove = false }, DialogButton(if (prot) "Make it public" else "Take it off", N.red) { remove = false
                if (!AccessBusy.work.containsKey(host)) app.act { AccessBusy.work[host] = if (prot) "Taking the login off…" else "Taking it off the internet…"
                    try { app.stepUp(if (prot) "Take Nova's login off $host — it will be open to anyone" else "Take $host off your Cloudflare tunnel", "POST", "/api/v1/cloudflare/remove",
                        JSONObject().put("host", host).put("allow_public", prot)); app.toast("Done"); app.back() } finally { AccessBusy.work.remove(host) } } })) }
}

/** Share links: the app stays behind your login; a small companion (Immich's share proxy) serves only what you share, on its own address. */
@Composable private fun ShareLinks(app: AppState, appId: String, host: String) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { s = runCatching { app.api.get("/api/v1/apps/$appId/sharing?host=$host") }.getOrNull() }
    val v = s ?: return
    if (!v.optBoolean("supported")) return
    var shareHost by remember(v.optString("suggest")) { mutableStateOf(v.optString("share_host").ifEmpty { v.optString("suggest") }) }
    val name = v.optString("name")
    val wanted = v.optString("wanted"); val ext = if (v.isNull("external_domain")) null else v.optString("external_domain")
    val extOk = wanted.isNotEmpty() && ext?.trimEnd('/') == wanted
    val ready = v.optBoolean("running") && v.optBoolean("online") && extOk
    SectionLabel("Share links")
    Text("Keep $name behind the login and still send albums to anyone: a small share proxy serves only what you share, at its own address. Your library, login page and everything else stay private.",
        color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
    Group {
        @Composable fun Step(ok: Boolean, title: String, sub: String) = Row1(title, sub, false, if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, if (ok) N.green else N.sub)
        Step(v.optBoolean("running"), "Share proxy", if (v.optBoolean("running")) "Running on the server" else if (v.optBoolean("installed")) "Installed, not answering yet" else "Not installed yet"); RowDivider()
        Step(v.optBoolean("online"), "Share address", if (v.optString("share_host").isEmpty()) "Not set up yet" else if (v.optBoolean("online")) "https://${v.optString("share_host")} — open to anyone, share links only" else "https://${v.optString("share_host")} — not reachable yet"); RowDivider()
        Step(extOk, "$name uses it", when { wanted.isEmpty() -> "After the steps above"; extOk -> "Share links point at the share address"; ext == null -> "Couldn't check — is $name running?"
                                            else -> "Set ${v.optString("setting_name")} to $wanted" })
    }
    if (app.isAdmin && (v.optString("share_host").isEmpty() || !v.optBoolean("installed"))) {
        Group { Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            OneTextField(shareHost, { shareHost = it.trim().lowercase().take(253) }, "share.example.com", Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            val busyShare = AccessBusy.work["share:$appId"]
            PillButton(busyShare ?: "Set it up", enabled = shareHost.contains('.') && busyShare == null) { if (busyShare == null) app.act {
                AccessBusy.work["share:$appId"] = "Starting…"
                try {
                    val r = app.stepUp("Turn on public share links at $shareHost", "POST", "/api/v1/apps/$appId/sharing", JSONObject().put("share_host", shareHost).put("for_host", host))
                    r.optString("task").ifEmpty { null }?.let { app.go(Route.Task(it)) }     // the install's own progress page
                } finally { AccessBusy.work.remove("share:$appId") } } }
        } }
        Text("Nova installs the share proxy and opens this address on your tunnel for share links only. If the address already points at the proxy, Nova just uses it.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
    } else if (!extOk && wanted.isNotEmpty()) {
        Group {
            Row1("Copy $wanted", "Then paste it into ${v.optString("setting_name")}", true, Icons.Rounded.ContentCopy, onClick = {
                ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("share address", wanted)); app.toast("Copied") })
            if (v.optString("setting_url").isNotEmpty()) { RowDivider()
                Row1("Open $name's settings", "Sign in as an admin, paste it and save", true, Icons.Rounded.Launch, onClick = {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(v.optString("setting_url")))) } }) }
            RowDivider(); Row1("Check again", null, true, Icons.Rounded.Refresh, onClick = { s = null; round++ })
        }
    } else if (ready) Text("All set: share a photo or album in $name and its link opens at $wanted for anyone.",
        color = N.green, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
    if (v.optString("share_host").isNotEmpty()) Text("Tip: when you share in $name you can add a password (people type it to see the album), an expiry date, and a custom link — like ${wanted.ifEmpty { "https://share.example.com" }}/s/family-trip. They all work at the share address.",
        color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
}
