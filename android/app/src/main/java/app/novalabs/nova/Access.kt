package app.novalabs.nova

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    var appName by remember { mutableStateOf("") }                    // what the code page calls the site
    LaunchedEffect(appId) { if (appId.isNotEmpty()) appName = runCatching { app.api.get("/api/v1/apps").optJSONArray("apps").objs().firstOrNull { it.optString("id") == appId }?.optString("name") ?: "" }.getOrDefault("") }

    val working = AccessBusy.work[host]
    fun change(to: String, gs: List<String> = guests, publicOk: Boolean = false) {
        if (AccessBusy.work.containsKey(host)) return                       // one change at a time
        app.act {
            val what = when (to) { "me" -> "Only you can open $host"; "guests" -> "You and ${gs.size} guest${if (gs.size == 1) "" else "s"} can open $host"
                "code" -> "Let anyone with an access code open $host (Cloudflare's login comes off it; the code is the lock)"; else -> "Make $host open to anyone" }
            AccessBusy.work[host] = when (to) { "me" -> "Switching to only you…"; "guests" -> if (to == mode) "Saving the guest list…" else "Switching to you and guests…"
                "code" -> "Putting the code page in front of it…"; else -> "Opening it to anyone…" }
            try {
                app.stepUp(what, "POST", "/api/v1/cloudflare/access", JSONObject().put("host", host).put("mode", to).put("guests", JSONArray(gs)).put("session", session).put("allow_public", publicOk).put("name", appName))
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
            outsideNova -> Note("Its login was set up in Cloudflare, outside Nova, so Nova doesn't change it. Edit it in Zero Trust → Access → Applications.", 14.sp, 8.dp)
            isShare -> Note(if (mode == "public") "Anyone with a share link can open what you've shared there — nothing else; Immich's own password and expiry protect each link. You can also put a Cloudflare login in front (below)."
                            else "Share links here also need a Cloudflare login first: ${if (mode == "guests") "yours, or a guest's emailed code" else "yours"} — then Immich's own password, if the link has one.", 14.sp, 8.dp)
            mode == "offline" -> Group { Row1("Off the internet", "A change didn't finish, so Nova took it offline instead of leaving it open. Pick who can open it to put it back.", false, Icons.Rounded.CloudOff, N.amber) }
            else -> {}
        }
        if (st != null && err == null && !outsideNova && (rec != null || onTunnel)) {
            SectionLabel("Who can open it")
            working?.let { WorkingRow(it); Spacer(Modifier.height(10.dp)) }
            Group {
                @Composable fun Choice(k: String, title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector) =
                    Row1(title, sub, false, icon, if (k == "public") N.amber else N.blue, enabled = app.isAdmin && working == null, onClick = { if (k != mode) ask = k }) { OneRadio(mode == k) }
                if (isShare) {      // the share address: open is the usual choice (each link has its own password and expiry in the app)
                    Choice("public", "Anyone with a link", "No Cloudflare login — the app's own password and expiry protect each link", Icons.Rounded.Public); RowDivider()
                    Choice("guests", "Only people you add", "They sign in with a one-time code sent to their email, then open the link", Icons.Rounded.Group); RowDivider()
                    Choice("me", "Only you", "Your Cloudflare login (handy for checking links before you send them)", Icons.Rounded.Lock)
                } else {
                Choice("me", "Only you", "Your Cloudflare login, the same as Nova", Icons.Rounded.Lock); RowDivider()
                Choice("guests", "You and guests", "People you add by email sign in with a one-time code sent to them", Icons.Rounded.Group); RowDivider()
                Choice("code", "Anyone with a code", "A page asks for an access code you give out — a random one, or a password you choose — and it can run out or be ended any time", Icons.Rounded.Key); RowDivider()
                Choice("public", "Anyone", "No login — anyone with the address can open it", Icons.Rounded.Public)
                }
            }
        }
        if (mode == "guests") {
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
            Note("Guests open the address, type their email, and Cloudflare sends them a code. They can only open this app — not Nova.", 13.sp, 6.dp)
        }
        if (mode == "code") { if (app.isAdmin) AccessCodes(app, host) else Note("Anyone with an access code can open this. Only an admin can make or end codes.", 14.sp, 8.dp) }
        if (appId.isNotEmpty() && app.isAdmin) PublicFix(app, appId, host)
        if (rec != null && !isShare && mode in listOf("me", "guests") && app.isAdmin) AppKey(app, host, rec) { round++ }
        if (appId.isNotEmpty() && !isShare) ShareLinks(app, appId, host)
        if (rec != null && app.isAdmin) {
            SectionLabel("Take it off")
            val existing = rec.optBoolean("existing") || rec.optString("kind") == "existing"
            Group { Row1(when { rec.optString("kind") == "protected" -> "Take the login off"; existing -> "Stop managing it in Nova"; else -> "Take it off the internet" },
                when { rec.optString("kind") == "protected" -> "It was yours before — it goes back to being open to anyone"
                       existing -> "Nova removes only a login it added; your route and DNS record stay as they are"
                       else -> "Nova removes what it added: route, DNS record and login" },
                false, Icons.Rounded.Delete, N.red, onClick = { remove = true }) }
        }
        Spacer(Modifier.height(40.dp))
    }

    ask?.let { to ->
        val t = when (to) { "me" -> "Only you?"; "guests" -> "You and guests?"; "code" -> "Anyone with a code?"; else -> "Open $host to anyone?" }
        val d = when (to) {
            "me" -> "Anyone opening $host signs in with your Cloudflare login first."
            "guests" -> "You, plus people you add by email. You can add them next — until then it's just you." +
                if (mode == "me") " $host is offline for a few seconds while the login switches over." else ""
            "code" -> "Cloudflare's login comes off $host. Instead, a page asks visitors for an access code that you make here, and only a working code opens the app. Until you make one, nobody can get in."
            else -> if (isShare) "No Cloudflare login: anyone with a share link can open what it shares (and only that) — set a password and expiry on the link in the app if you want one."
                    else "There will be no login at all: anyone who finds the address can open the app. Only do this if the app has its own login you trust."
        }
        OneDialog({ ask = null }, t, d, listOf(DialogButton("Cancel") { ask = null }, DialogButton(if (to == "public") "Make it public" else "Change", if (to == "public") N.red else N.blue) {
            ask = null; change(to, if (to == "guests") guests else emptyList(), publicOk = to == "public") })) {
            if (to != "public" && mode in listOf("public", "offline"))   // going behind a login: the app's own phone app needs a way in
                Text("Heads-up: an app's own phone app (Immich, Jellyfin, Home Assistant…) can't get past a web login by itself — use “Let its phone app in” below to give it a key. Links you've already shared at this address stop working for others; for Immich, set up Share links below first.",
                    color = N.amber, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 26.dp))
        }
    }
    if (addGuest) { var e by remember { mutableStateOf("") }
        val ok = Regex("[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}").matches(e.trim())
        OneDialog({ addGuest = false }, "Add a guest", "Their email address. They'll get a one-time code there each time they sign in.",
            listOf(DialogButton("Cancel") { addGuest = false }, DialogButton("Add", N.blue, enabled = ok) { addGuest = false; val g = e.trim().lowercase(); if (g !in guests) guests = guests + g })) {
            OneTextField(e, { e = it.trim().take(120) }, "name@example.com", Modifier.fillMaxWidth().padding(horizontal = 22.dp)) } }
    if (remove) { val prot = rec?.optString("kind") == "protected"; val existing = rec?.optBoolean("existing") == true
        OneDialog({ remove = false }, if (prot) "Take the login off $host?" else if (existing) "Stop managing $host in Nova?" else "Take $host off?",
            if (prot) "It goes back to how it was before Nova: open to anyone. The route and DNS record stay."
            else if (existing) "Nova removes the login it added (if any) and forgets it. Your route and DNS record stay as they are, so share links keep working."
            else "Nova removes the route, the DNS record and the login it added. Nothing else in your Cloudflare account changes.",
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
    Note("Keep $name behind the login and still send albums to anyone: a small share proxy serves only what you share, at its own address. Your library, login page and everything else stay private.", 14.sp, 6.dp)
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
        Note("Nova installs the share proxy and opens this address on your tunnel for share links only. If the address already points at the proxy, Nova just uses it.", 13.sp, 4.dp)
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
    if (v.optString("share_host").isNotEmpty()) Note("Tip: when you share in $name you can add a password (people type it to see the album), an expiry date, and a custom link — like ${wanted.ifEmpty { "https://share.example.com" }}/s/family-trip. They all work at the share address.", 13.sp, 4.dp)
}


/** Some apps need one setting before they work at a public address (Home Assistant's proxy trust, Homepage's allowed hosts). */
@Composable private fun PublicFix(app: AppState, appId: String, host: String) {
    var fix by remember { mutableStateOf<JSONObject?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(round) { fix = runCatching { app.api.get("/api/v1/apps/${android.net.Uri.encode(appId)}/public-fix?host=${android.net.Uri.encode(host)}") }.getOrNull() }
    val f = fix ?: return
    if (!f.optBoolean("needed")) return
    val name = f.optString("app")
    SectionLabel("Make it work there")
    Group {
        Row1("$name needs one setting", f.optString("what"), false, Icons.Rounded.Warning, N.amber)
        val will = f.optJSONArray("will")
        Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until (will?.length() ?: 0)) Text("${i + 1}. ${will!!.getString(i)}", color = N.sub, fontSize = 14.sp)
        }
        if (f.optBoolean("auto")) { RowDivider(); Row1("Fix it", "Nova keeps a backup of the file", true, Icons.Rounded.Check, onClick = { ask = true }) }
    }
    Note("Undo any time: the backup sits next to the file (…nova-backup-DATE). Put it back and restart the app.", 13.sp, 6.dp)
    if (ask) OneDialog({ ask = false }, "Change $name's setting?", "$name restarts after the change. A backup of the file is kept next to it.",
        listOf(DialogButton("Cancel") { ask = false }, DialogButton("Fix it", N.blue) { ask = false
            app.act { val r = app.stepUp("Change one setting in $name so it works at $host", "POST", "/api/v1/apps/${android.net.Uri.encode(appId)}/public-fix", JSONObject().put("host", host))
                if (r.optBoolean("changed")) app.toast("$name is set up — give it a moment to restart"); round++ } }))
}

/** A key for the app's own phone app (Immich…): a Cloudflare service token it sends as two headers, shown once. */
@Composable private fun AppKey(app: AppState, host: String, rec: JSONObject, changed: () -> Unit) {
    val ctx = LocalContext.current
    var ask by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf<JSONObject?>(null) }
    val has = rec.optBoolean("app_key")
    SectionLabel("Its phone app")
    Group {
        if (has) {
            val made = rec.optLong("app_key_made").takeIf { it > 0 }?.let { java.text.DateFormat.getDateInstance().format(java.util.Date(it * 1000)) } ?: ""
            Row1("App key is set up", "Made $made — its phone app gets past the login with it", false, Icons.Rounded.Key, N.green); RowDivider()
            Row1("Make a new key", "The old one stops working", true, Icons.Rounded.Refresh, onClick = { ask = "new" }); RowDivider()
            Row1("Remove the key", null, false, Icons.Rounded.Delete, N.red, onClick = { ask = "remove" })
        } else Row1("Let its phone app in", "Apps like Immich or Home Assistant can't do this web login. Nova makes a key just for this address that the app sends instead.",
            true, Icons.Rounded.Key, onClick = { ask = "new" })
    }
    ask?.let { what ->
        OneDialog({ ask = null }, if (what == "remove") "Remove the app key?" else if (has) "Make a new app key?" else "Let its phone app in?",
            if (what == "remove") "The app's phone app can't get in anymore until you make a new one."
            else (if (has) "The old key stops working. " else "") + "Nova makes a Cloudflare service token that opens only $host, and shows it to you once. You paste it into the app's settings as two headers.",
            listOf(DialogButton("Cancel") { ask = null }, DialogButton(if (what == "remove") "Remove" else "Make a key", if (what == "remove") N.red else N.blue) { ask = null
                app.act {
                    if (what == "remove") { app.stepUp("Remove the app key for $host", "POST", "/api/v1/cloudflare/app-key/remove", JSONObject().put("host", host)); app.toast("Removed") }
                    else shown = app.stepUp("Make a key that lets an app's own phone app open $host", "POST", "/api/v1/cloudflare/app-key", JSONObject().put("host", host))
                    changed()
                } }))
    }
    shown?.let { r ->
        fun copy(label: String, v: String) { ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText(label, v)); app.toast("Copied") }
        OneDialog({ shown = null }, "Its phone app's key", "In the app, add two custom headers (Immich: Settings → Advanced → Custom proxy headers). Then use https://$host as its server address. This is the only time Nova shows the secret.",
            listOf(DialogButton("Done", N.blue) { shown = null })) {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CF-Access-Client-Id", color = N.sub, fontSize = 13.sp)
                Chip(r.optString("client_id"), mono = true) { copy("CF-Access-Client-Id", r.optString("client_id")) }
                Text("CF-Access-Client-Secret", color = N.sub, fontSize = 13.sp)
                Chip(r.optString("client_secret"), mono = true) { copy("CF-Access-Client-Secret", r.optString("client_secret")) }
                Text("Tap one to copy it.", color = N.sub, fontSize = 12.sp)
            }
        }
    }
}


/** Access codes for an address set to "Anyone with a code": a random one (shown once), or a password you choose — each for one
 *  address, with an expiry, ended any time. The secrets are never stored in a form that can be read back. */
@Composable private fun AccessCodes(app: AppState, host: String) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var make by remember { mutableStateOf<String?>(null) }           // "random" or "password": the dialog
    var shown by remember { mutableStateOf<JSONObject?>(null) }       // the new code, once
    var ending by remember { mutableStateOf<String?>(null) }          // a code id (or "all") to confirm ending
    LaunchedEffect(round) { data = runCatching { app.api.get("/api/v1/cloudflare/codes?host=${android.net.Uri.encode(host)}") }.getOrNull() }
    val list = data?.optJSONArray("codes").objs()
    fun when_(t: Long) = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(t * 1000))
    SectionLabel("Access codes")
    Group {
        if (data == null) Row1("Loading codes…", null)
        else {
            if (list.isEmpty()) { Row1("No codes yet", "Make one below and send it to whoever should get in"); RowDivider() }
            list.forEach { c ->
                val expires = c.optLong("expires"); val expired = c.optBoolean("expired"); val logins = c.optInt("logins")
                val kind = if (c.optString("kind") == "password") "Password" else "Random code"
                val sub = "$kind · " + (if (expired) "ran out ${when_(expires)}" else if (expires > 0) "ends ${when_(expires)}" else "until you end it") + " · " +
                    (if (logins > 0) "used $logins×" + (c.optLong("last_used").takeIf { it > 0 }?.let { ", last ${when_(it)}" } ?: "") else "not used yet")
                Row1(c.optString("label").ifEmpty { if (c.optString("kind") == "password") "A password" else "A random code" }, sub, false,
                    if (c.optString("kind") == "password") Icons.Rounded.Key else Icons.Rounded.Smartphone, if (expired) N.sub else N.blue,
                    trailing = { IconButton({ ending = c.optString("id") }) { Icon(Icons.Rounded.Close, "End this code", tint = N.sub) } })
                RowDivider()
            }
            Row1("Make a random code", "Nova picks a long one like K7QM-2XDF-9TPA-HW3R, and shows it once", true, Icons.Rounded.Add, onClick = { make = "random" }); RowDivider()
            Row1("Make a password", "You choose it (at least 8 characters). Nova keeps only a scrambled copy.", true, Icons.Rounded.Key, onClick = { make = "password" })
            if (list.isNotEmpty()) { RowDivider(); Row1("End every code", "Everyone signed in with a code is signed out right away", false, Icons.Rounded.Delete, N.red, onClick = { ending = "all" }) }
            RowDivider(); Row1("Copy the address", host, true, Icons.Rounded.ContentCopy, onClick = {
                ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("address", "https://$host")); app.toast("Copied") })
        }
    }
    Note("A visitor opens the address, types the code, and gets into this app only — never Nova. Codes can't be seen again after they're made, and too many wrong guesses lock a visitor out for a while.", 13.sp, 6.dp)

    make?.let { kind ->
        var label by remember { mutableStateOf("") }; var pw by remember { mutableStateOf("") }; var exp by remember { mutableIntStateOf(1) }
        val opts = listOf("1h" to "1 hour", "1d" to "1 day", "7d" to "7 days", "30d" to "30 days", "never" to "Until I end it")
        OneDialog({ make = null }, if (kind == "password") "Make a password" else "Make a random code",
            if (kind == "password") "Whoever you give it to types it to open the app. You'll confirm with your fingerprint." else "Nova makes the code and shows it to you once. You'll confirm with your fingerprint.",
            listOf(DialogButton("Cancel") { make = null }, DialogButton("Make it", N.blue, enabled = kind != "password" || pw.length >= 8) {
                make = null
                app.act {
                    val body = JSONObject().put("host", host).put("kind", kind).put("label", label.trim()).put("expires", opts[exp].first)
                    if (kind == "password") body.put("password", pw)
                    val r = app.stepUp(if (kind == "password") "Make a password that opens $host" else "Make a random access code that opens $host", "POST", "/api/v1/cloudflare/codes/new", body)
                    round++
                    if (r.optString("code").isNotEmpty()) shown = r else app.toast("Password saved — give it to them")
                } })) {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OneTextField(label, { label = it.take(40) }, "Who is it for? (optional)", Modifier.fillMaxWidth())
                if (kind == "password") OneTextField(pw, { pw = it.take(100) }, "The password (8+ characters)", Modifier.fillMaxWidth())
                Text("How long it works", color = N.sub, fontSize = 13.sp)
                Segmented(opts.map { it.second }, exp) { exp = it }
            }
        }
    }
    shown?.let { r ->
        OneDialog({ shown = null }, "Your access code", "Send this to the person — it's shown only now. They open https://$host, type it in, and they're in${if (r.optLong("expires") > 0) " until it runs out" else ""}. You can end it any time from this page.",
            listOf(DialogButton("Done", N.blue) { shown = null })) {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(r.optString("code"), mono = true) { ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("access code", r.optString("code"))); app.toast("Copied") }
                Text("Tap it to copy.", color = N.sub, fontSize = 12.sp)
            }
        }
    }
    ending?.let { id ->
        OneDialog({ ending = null }, if (id == "all") "End every code?" else "End this code?",
            if (id == "all") "Everyone who got in with a code is signed out right away, and none of the codes work again." else "Anyone using it is signed out right away, and it won't work again.",
            listOf(DialogButton("Cancel") { ending = null }, DialogButton("End it", N.red) { ending = null
                app.act { app.api.post("/api/v1/cloudflare/codes/revoke", JSONObject().put("host", host).put("id", id)); app.toast("Ended"); round++ } }))
    }
}
