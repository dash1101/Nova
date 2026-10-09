package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

@Composable fun InboxScreen(app: AppState) {
    var filter by remember { mutableIntStateOf(0) }
    val dataLive = live(app, "/api/v1/events?since=0", 15_000)
    val data by dataLive
    var clearAll by remember { mutableStateOf(false) }
    LaunchedEffect(data) { data?.optJSONArray("events")?.let { InboxArchive.merge(app.activity, app.pairing.profile, it) } }
    /** Archive: gone from the server's inbox (every device), kept in this phone's history (Inbox → Archive). */
    fun delete(ts: List<Double>) {
        if (!app.isAdmin) { app.toast("This ${DeviceForm.noun} has view-only access"); return }
        val before = dataLive.value
        before?.optJSONArray("events")?.let { ev -> InboxArchive.archive(app.activity, app.pairing.profile,
            (0 until ev.length()).map { ev.getJSONObject(it) }.filter { e -> ts.any { kotlin.math.abs(it - e.optDouble("t")) < 0.0005 } }) }
        dataLive.value = JSONObject(before.toString()).also { o ->        // gone at once; the server catches up
            val left = org.json.JSONArray(); val ev = o.optJSONArray("events") ?: org.json.JSONArray()
            for (i in 0 until ev.length()) if (ts.none { kotlin.math.abs(it - ev.getJSONObject(i).optDouble("t")) < 0.0005 }) left.put(ev.getJSONObject(i))
            o.put("events", left)
        }
        app.act { try { app.api.post("/api/v1/events/delete", JSONObject().put("t", org.json.JSONArray(ts))) }
                  catch (e: Exception) { dataLive.value = before; throw e } }
    }
    val events = data?.optJSONArray("events")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    LaunchedEffect(events.firstOrNull()?.optDouble("t")) {
        events.firstOrNull()?.optDouble("t")?.let { app.pairing.lastEventSeen = it }; app.unread = 0
    }
    val shown = events.filter { e -> when (filter) { 1 -> e.optString("level") in listOf("warning", "critical"); 2 -> e.optString("level") == "critical"
        3 -> e.optString("category") == "login"; else -> true } }
    val day = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()); val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    Page("Inbox", app::back, listOf(TopAction(Icons.Rounded.Inventory2, "Archive") { app.go(Route.Archive) },
            TopAction(Icons.Rounded.DeleteSweep, "Archive everything") { clearAll = true },
            TopAction(Icons.Rounded.Settings, "Notification settings") { app.go(Route.NotifySettings) })) {
        AttentionList(app)
        Segmented(listOf("All", "Issues", "Critical", "Logins"), filter) { filter = it }
        if (shown.isEmpty()) Text(if (data == null) "Loading…" else "Nothing here — all quiet.", color = N.sub, modifier = Modifier.padding(30.dp))
        if (shown.isNotEmpty()) Text("Swipe left to archive — it moves to the Archive (kept on the server, for every device).", color = N.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 30.dp, top = 4.dp))
        shown.groupBy { day.format(Date((it.optDouble("t") * 1000).toLong())) }.forEach { (d, list) ->
            SectionLabel(d)
            Group {
                list.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    val lvl = e.optString("level")
                    key(e.optDouble("t")) { SwipeRow(end = SwipeAction("Archive", Icons.Rounded.Inventory2, N.blue) { delete(listOf(e.optDouble("t"))) }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(levelColor(lvl, N)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.optString("title").replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), ""), color = N.text, fontSize = 16.sp)
                            e.optString("detail").takeIf { it.isNotEmpty() }?.let { Text(it, color = N.sub, fontSize = 13.sp, maxLines = 4) }
                        }
                        Text(hm.format(Date((e.optDouble("t") * 1000).toLong())), color = N.sub, fontSize = 13.sp)
                    }
                    } }
                }
            }
        }
    }
    if (clearAll) OneDialog({ clearAll = false }, "Archive everything?",
        if (filter == 0) "Everything moves from the Inbox to the Archive (kept on the server, for every device). Active alerts stay until they're fixed or ignored."
        else "The ${shown.size} event(s) shown move to the Archive (kept on the server, for every device).",
        listOf(DialogButton("Cancel") { clearAll = false }, DialogButton("Archive", N.blue) { clearAll = false
            if (filter == 0) { if (!app.isAdmin) app.toast("This ${DeviceForm.noun} has view-only access") else {
                data?.optJSONArray("events")?.let { ev -> InboxArchive.archive(app.activity, app.pairing.profile, (0 until ev.length()).map { ev.getJSONObject(it) }) }
                dataLive.value = JSONObject().put("events", org.json.JSONArray())
                app.act("Archived — see Inbox → Archive") { app.api.post("/api/v1/events/delete", JSONObject().put("all", true)) } } }
            else delete(shown.map { it.optDouble("t") }) }))
}

@Composable fun NotifySettingsScreen(app: AppState) {
    val live = live(app, "/api/v1/notify")
    var s by live
    var phone by remember { mutableStateOf(app.pairing.phoneNotifyLevel) }
    fun set(k: String, v: Any) {
        val before = s
        s = JSONObject(s?.toString() ?: "{}").put(k, v)               // shows the change right away
        app.act { try { s = app.api.post("/api/v1/notify", JSONObject().put(k, v)) } catch (e: Exception) { s = before; throw e } }
    }
    val levels = listOf("info" to "Everything", "warning" to "Warnings and critical", "critical" to "Critical only")
    var levelDialog by remember { mutableStateOf(false) }
    val discord = if (app.isAdmin) live(app, "/api/v1/notify/discord") else remember { mutableStateOf<JSONObject?>(null) }
    var discordDialog by remember { mutableStateOf(false) }
    if (discordDialog) {
        var url by remember { mutableStateOf("") }
        OneDialog({ discordDialog = false }, "Discord alerts",
            "In Discord: open the channel's settings → Integrations → Webhooks → New Webhook → Copy Webhook URL, and paste it here. Nova never shows the link again.",
            listOfNotNull(
                if (discord.value?.optBoolean("configured") == true) DialogButton("Send a test") { app.act("Sent — check the channel") { app.api.post("/api/v1/notify/discord/test") } } else null,
                if (discord.value?.optBoolean("configured") == true) DialogButton("Turn off", N.red) { discordDialog = false
                    app.act("Discord alerts off") { app.stepUp("Turn off Discord alerts", "POST", "/api/v1/notify/discord", JSONObject().put("webhook", "")); discord.value = app.api.get("/api/v1/notify/discord") } } else null,
                DialogButton("Save", N.blue, enabled = url.startsWith("https://")) { discordDialog = false
                    app.act("Saved — sending a test") { app.stepUp("Send alerts to Discord", "POST", "/api/v1/notify/discord", JSONObject().put("webhook", url.trim()))
                        discord.value = app.api.get("/api/v1/notify/discord"); app.api.post("/api/v1/notify/discord/test") } })) {
            Box(Modifier.padding(horizontal = 22.dp)) { OneTextField(url, { url = it.trim().take(300) }, "https://discord.com/api/webhooks/…", Modifier.fillMaxWidth()) }
        }
    }
    Page("Notifications", app::back) {
        Row(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PhoneAndroid, null, tint = N.text, modifier = Modifier.size(64.dp))
            Text("•••", color = N.blue, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Rounded.Dns, null, tint = N.text, modifier = Modifier.size(64.dp))
        }
        SectionLabel("This ${DeviceForm.noun}")
        Group {
            var instant by remember { mutableStateOf(Alerts.enabled(app.activity)) }
            SwitchRow("Instant alerts", if (instant) "Alerts arrive within a minute, even with Nova closed" else "Checked about every 15 minutes (Android may delay it)",
                instant, subtitleBlue = instant) { instant = it; Alerts.setEnabled(app.activity, it) }
            RowDivider()
            levels.forEachIndexed { i, (k, label) -> if (i > 0) RowDivider()
                Row1(label, if (k == "info") "Includes logins and USB plug/unplug" else null, onClick = { phone = k; app.pairing.phoneNotifyLevel = k }) {
                    OneRadio(phone == k) } }
        }
        Text("Instant alerts keep one quiet connection to each server and show a silent notification Android requires — long-press it to hide it. Uses very little battery.", color = N.sub, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        s?.let { s ->
            SectionLabel("Discord")
            Group {
                val dis = discord.value
                if (app.isAdmin) Row1(if (dis?.optBoolean("configured") == true) "Discord channel" else "Set up Discord",
                    if (dis?.optBoolean("configured") == true) "Sending to ${dis.optString("hint")} · tap to change or test" else "Get alerts in a Discord channel too — paste its webhook link",
                    dis?.optBoolean("configured") == true, Icons.Rounded.Forum, androidx.compose.ui.graphics.Color(0xFF5865F2),
                    onClick = { discordDialog = true })
                if (app.isAdmin) RowDivider()
                SwitchRow("Discord pings", if (s.optBoolean("discord_paused")) "Paused — alerts still show here" else "On",
                    !s.optBoolean("discord_paused"), subtitleBlue = !s.optBoolean("discord_paused")) { set("discord_paused", !it) }
                RowDivider()
                Row1("Send to Discord", levels.firstOrNull { it.first == s.optString("push_min_level") }?.second, true, onClick = { levelDialog = true })
            }
            SectionLabel("What counts")
            Group {
                SwitchRow("Logins", "Someone signs in to the server", s.optBoolean("push_logins")) { set("push_logins", it) }
                RowDivider()
                SwitchRow("USB devices", "Plugged in or unplugged", s.optBoolean("push_usb")) { set("push_usb", it) }
            }
        }
        LinksCard(listOf("Inbox" to { app.go(Route.Inbox) }, "Phone notification settings" to {
            app.activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, app.activity.packageName)) }))
    }
    if (levelDialog) OneDialog({ levelDialog = false }, "Send to Discord", buttons = listOf(DialogButton("Cancel") { levelDialog = false })) {
        levels.forEach { (k, label) -> DialogChoice(label, null, s?.optString("push_min_level") == k) { levelDialog = false; set("push_min_level", k) } }
    }
}

@Composable fun SettingsScreen(app: AppState) {
    var update by remember { mutableStateOf<JSONObject?>(null) }
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { checking = true; update = Updater.latest(app.api); checking = false }
    val newer = update?.let { Updater.isNewer(it, ctx) } == true
    Page("Settings", app::back) {
        SectionLabel("Software update")
        Group {
            Row1("Updates for the server", "System packages, containers and Nova", true, Icons.Rounded.Update, N.green, onClick = { app.go(Route.Updates) })
            RowDivider()
            Row1(if (newer) "Update to ${update!!.optString("version_name")}" else "Nova is up to date",
                when { checking -> "Checking…"; installing -> "Downloading…"; newer -> update!!.optString("notes").ifEmpty { "Tap to install" }
                       else -> "Version $APP_VERSION" }, newer, Icons.Rounded.SystemUpdate, onClick = {
                if (newer && !installing) {
                    if (!ctx.packageManager.canRequestPackageInstalls()) {
                        app.toast("Allow Nova to install updates, then tap again")
                        ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            android.net.Uri.parse("package:${ctx.packageName}")))
                    } else app.act { installing = true; try { Updater.install(ctx, app.api, update!!) } finally { installing = false } }
                } else app.act { checking = true; update = Updater.latest(app.api); checking = false }
            })
            if (app.isAdmin) { RowDivider(); ServerUpdateRow(app) }
        }
        SectionLabel("Server")
        Group {
            Row1("Name", serverName(app), true, Icons.Rounded.Edit, onClick = { app.go(Route.ServerSettings) })
            RowDivider()
            Row1("Servers on this ${DeviceForm.noun}", "${Servers.all(app.activity).count { Pairing(app.activity, it).paired }} · switch or add another", true,
                Icons.Rounded.Dns, onClick = { app.go(Route.Servers) })
        }
        SectionLabel("This ${DeviceForm.noun}")
        Group {
            ExpandRow("Signing key", "In this ${DeviceForm.noun}'s secure chip", false, Icons.Rounded.Key) {
                Detail("Every request Nova sends is signed by a key that was created inside this ${DeviceForm.noun}'s hardware security chip and can't be copied out — not even by Nova.")
                Detail("The server only accepts requests signed by a paired phone, each with a fresh time stamp and a one-time number, so a recorded request can't be replayed.")
            }
            RowDivider()
            ExpandRow("Fingerprint confirmation", if (app.pairing.stepUpRegistered) "On" else "Not set up yet",
                app.pairing.stepUpRegistered, Icons.Rounded.Fingerprint) {
                Detail("Risky actions — opening a terminal, stopping or restarting things, installs, unmounting drives, restarting the server — need your fingerprint (or PIN).")
                Detail("Behind the scenes that unlocks a second key in the secure chip, which signs the request as well. The server refuses risky actions without that second signature, so even a stolen, unlocked phone can't do them without you.")
                Detail(if (app.pairing.stepUpRegistered) "Status: set up and registered with the server."
                       else if (app.api.via == "home") "Status: not set up — this ${DeviceForm.noun} needs a screen lock (PIN or fingerprint) first. Add one in Android settings, then reopen Nova."
                       else "Status: waiting — it's registered automatically the next time Nova opens on home Wi-Fi (registration is only accepted from home).",
                    if (app.pairing.stepUpRegistered) N.green else N.amber)
            }
            RowDivider()
            var quickOn by remember { mutableStateOf(app.pairing.quickApproverId.isNotEmpty()) }
            SwitchRow("Approve from notifications", if (quickOn) "Approve / Deny buttons on approval notifications — also on your watch"
                else "Adds Approve / Deny buttons to approval notifications, so you can answer from your watch", quickOn, enabled = app.isAdmin) { on ->
                app.act {
                    if (on) {
                        val pem = app.pairing.keys.ensureQuick()
                        val r = app.stepUp("Approve from notifications", "POST", "/api/v1/devices/watch",
                            org.json.JSONObject().put("public_key", pem).put("name", "${android.os.Build.MODEL} notifications"))
                        app.pairing.quickApproverId = r.optString("device_id"); quickOn = true
                        app.toast("On — approval notifications get Approve and Deny buttons")
                    } else {
                        val id = app.pairing.quickApproverId
                        if (id.isNotEmpty()) runCatching { app.stepUp("Turn off approving from notifications", "DELETE", "/api/v1/devices/$id") }
                        app.pairing.quickApproverId = ""; app.pairing.keys.dropQuick(); quickOn = false
                    }
                }
            }
            Text("The buttons don't ask for your fingerprint, so anyone holding your unlocked phone or watch could tap them. They can only approve or deny something a paired browser is waiting on — nothing else.",
                color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 14.dp))
            RowDivider()
            ExpandRow("Connection", when (app.api.via) { "home" -> "Home Wi-Fi (direct)"; "remote" -> "Cloudflare (from anywhere)"; else -> "—" },
                true, Icons.Rounded.Wifi) {
                Detail("At home Nova talks to the server directly. Elsewhere it goes through Cloudflare Access, which turns away anyone without this app's access token before they ever reach the server.")
                DetailLine("Home address", app.pairing.lanUrl.ifEmpty { "—" })
                DetailLine("Remote address", app.pairing.remoteUrl.ifEmpty { "not set up" })
            }
        }
        Group { Row1("Users & devices", (if (app.isAdmin) "You're an admin" else "You have view-only access") +
            (app.pairing.user.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""), true, Icons.Rounded.Group, onClick = { app.go(Route.Devices) }) }
        Group {
            Row1("Notifications", null, icon = Icons.Rounded.Notifications, onClick = { app.go(Route.NotifySettings) })
            RowDivider()
            Row1("Appearance & privacy", "Theme, Home layout, app lock", true, Icons.Rounded.Palette, onClick = { app.go(Route.Appearance) })
            RowDivider()
            Row1("Setup guide", "Install Nova on a server, remote access, browsers", true, androidx.compose.material.icons.Icons.AutoMirrored.Rounded.MenuBook, onClick = { app.go(Route.SetupGuide) })
        }
        Group { Row1("Unpair this ${DeviceForm.noun}", "Erases its keys", false, Icons.Rounded.LinkOff, N.red, onClick = { confirmUnpair = true }) }
        LinksCard(listOf("About Nova" to { app.go(Route.About) }))
    }
    if (confirmUnpair) OneDialog({ confirmUnpair = false }, "Unpair this ${DeviceForm.noun}?",
        "Its keys are erased. To use Nova again you'll need a new pairing code from the server.",
        listOf(DialogButton("Cancel") { confirmUnpair = false },
            DialogButton("Unpair", N.red) { confirmUnpair = false; app.pairing.clear(); app.paired = false }))
}

@Composable fun AboutScreen(app: AppState) {
    val s = app.overview?.optJSONObject("server")
    Page("About", app::back) {
        Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nova", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = N.text)
        }
        val serverVer = live(app, "/api/v1/whoami", 0).value?.optString("api").orEmpty()
        Group { Row1("Version", "App $APP_VERSION${if (serverVer.isNotEmpty()) " · server $serverVer" else ""} · check for updates", true,
            Icons.Rounded.Update, onClick = { app.go(Route.Updates) }) }
        SectionLabel("Server")
        Group {
            Row1("Name", s?.optString("name")); RowDivider()
            Row1("Board", s?.optString("board")); RowDivider()
            Row1("CPU", s?.optString("cpu")); RowDivider()
            Row1("Memory", s?.optInt("ram_gb")?.let { "$it GB" }); RowDivider()
            Row1("Kernel", s?.optString("kernel")); RowDivider()
            Row1("Up for", s?.optLong("uptime_s")?.let { val d = it / 86400; val h = it % 86400 / 3600; "${d}d ${h}h" })
        }
        SectionLabel("Security")
        Group {
            Row1("Every request is signed", "By a key in this ${DeviceForm.noun}'s secure chip, time-stamped and single-use", false); RowDivider()
            Row1("Risky actions need your fingerprint", "Shells, stopping things, installs, unmounting, power", false); RowDivider()
            Row1("At home: encrypted, pinned", "HTTPS to the server's own certificate, checked on every connection", false); RowDivider()
            Row1("Outside home: Cloudflare Access", "Strangers are stopped before they reach the server", false)
        }
        SectionLabel("Project")
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Group { Row1("Nova by dash1101", "Open source · AGPL-3.0 · github.com/dash1101/Nova", true, onClick = {
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/dash1101/Nova"))) } }) }
    }
}


/** Server package updates: signed releases only (nova-update verifies the release key). */
@Composable fun ServerUpdateRow(app: AppState) {
    val live = live(app, "/api/v1/server/update", 5_000)
    val u = live.value
    var busy by remember { mutableStateOf(false) }
    val state = u?.optString("state") ?: ""
    val running = u?.optBoolean("running") == true || state == "installing"
    val avail = state == "available"
    Row1(when { running -> "Updating the server…"; avail -> "Update the server to ${u!!.optString("available")}"; else -> "Server" },
        when {
            u == null -> "Checking…"
            u.optString("source").isEmpty() -> "Version ${u.optString("installed")} · updates aren't set up yet (sudo nova-setup)"
            running -> "Installing a signed release — Nova restarts on its own"
            state == "failed" -> "Last update failed: ${u.optString("error").take(80)}"
            avail -> "Signed release · tap to install (fingerprint)"
            else -> "Version ${u.optString("installed")} · tap to check"
        }, avail || running, Icons.Rounded.Dns, if (state == "failed") N.red else null, enabled = !busy && !running, onClick = {
            busy = true
            app.act { try {
                if (avail) { app.stepUp("Update the server", "POST", "/api/v1/server/update"); app.toast("Update started") }
                else { val r = app.api.post("/api/v1/server/update/check"); app.toast(r.optString("message").ifEmpty { "Checked" }) }
                live.value = app.api.get("/api/v1/server/update")
            } finally { busy = false } }
        }) { if (busy || running) OneSpinner() }
}

/** "Disconnected · last contact 4 min ago" + a sheet explaining what each route said. */
@Composable fun DisconnectedBanner(app: AppState) {
    var open by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) } }
    val ago = app.lastContact.takeIf { it > 0 }?.let { ((now - it) / 60_000).let { m -> if (m < 1) "just now" else if (m < 60) "$m min ago" else "${m / 60} h ${m % 60} min ago" } }
    Banner(if (ago != null) "Disconnected · last contact $ago" else "Disconnected — tap for details", N.red) { open = true }
    if (open) OneDialog({ open = false }, "Can't reach your server",
        null, listOf(DialogButton("Close") { open = false }, DialogButton("Try again", N.blue) { open = false; app.act { app.refresh() } })) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            (app.api.lastAttempts.ifEmpty { listOf(app.error ?: "No answer yet") }).forEach { line ->
                Text(line, color = N.text, fontSize = 14.sp) }
            Text("Showing the last data Nova had${ago?.let { " (from $it)" } ?: ""}. Nova keeps trying in the background and reconnects by itself when the server is back.",
                color = N.sub, fontSize = 13.sp)
        }
    }
}
