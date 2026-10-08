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
    val data by live(app, "/api/v1/events?since=0", 15_000)
    val events = data?.optJSONArray("events")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    LaunchedEffect(events.firstOrNull()?.optDouble("t")) {
        events.firstOrNull()?.optDouble("t")?.let { app.pairing.lastEventSeen = it }; app.unread = 0
    }
    val shown = events.filter { e -> when (filter) { 1 -> e.optString("level") in listOf("warning", "critical"); 2 -> e.optString("level") == "critical"
        3 -> e.optString("category") == "login"; else -> true } }
    val day = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()); val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    Page("Inbox", app::back, listOf(TopAction(Icons.Rounded.Settings, "Notification settings") { app.go(Route.NotifySettings) })) {
        Segmented(listOf("All", "Issues", "Critical", "Logins"), filter) { filter = it }
        if (shown.isEmpty()) Text(if (data == null) "Loading…" else "Nothing here — all quiet.", color = N.sub, modifier = Modifier.padding(30.dp))
        shown.groupBy { day.format(Date((it.optDouble("t") * 1000).toLong())) }.forEach { (d, list) ->
            SectionLabel(d)
            Group {
                list.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    val lvl = e.optString("level")
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(levelColor(lvl, N)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.optString("title").replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), ""), color = N.text, fontSize = 16.sp)
                            e.optString("detail").takeIf { it.isNotEmpty() }?.let { Text(it, color = N.sub, fontSize = 13.sp, maxLines = 4) }
                        }
                        Text(hm.format(Date((e.optDouble("t") * 1000).toLong())), color = N.sub, fontSize = 13.sp)
                    }
                }
            }
        }
    }
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
    Page("Notifications", app::back) {
        Row(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PhoneAndroid, null, tint = N.text, modifier = Modifier.size(64.dp))
            Text("•••", color = N.blue, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Rounded.Dns, null, tint = N.text, modifier = Modifier.size(64.dp))
        }
        SectionLabel("This phone")
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
            Row1("Servers on this phone", "${Servers.all(app.activity).count { Pairing(app.activity, it).paired }} · switch or add another", true,
                Icons.Rounded.Dns, onClick = { app.go(Route.Servers) })
        }
        SectionLabel("This phone")
        Group {
            ExpandRow("Signing key", "In this phone's secure chip", false, Icons.Rounded.Key) {
                Detail("Every request Nova sends is signed by a key that was created inside this phone's hardware security chip and can't be copied out — not even by Nova.")
                Detail("The server only accepts requests signed by a paired phone, each with a fresh time stamp and a one-time number, so a recorded request can't be replayed.")
            }
            RowDivider()
            ExpandRow("Fingerprint confirmation", if (app.pairing.stepUpRegistered) "On" else "Not set up yet",
                app.pairing.stepUpRegistered, Icons.Rounded.Fingerprint) {
                Detail("Risky actions — opening a terminal, stopping or restarting things, installs, unmounting drives, restarting the server — need your fingerprint (or PIN).")
                Detail("Behind the scenes that unlocks a second key in the secure chip, which signs the request as well. The server refuses risky actions without that second signature, so even a stolen, unlocked phone can't do them without you.")
                Detail(if (app.pairing.stepUpRegistered) "Status: set up and registered with the server."
                       else if (app.api.via == "home") "Status: not set up — this phone needs a screen lock (PIN or fingerprint) first. Add one in Android settings, then reopen Nova."
                       else "Status: waiting — it's registered automatically the next time Nova opens on home Wi-Fi (registration is only accepted from home).",
                    if (app.pairing.stepUpRegistered) N.green else N.amber)
            }
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
        Group { Row1("Unpair this phone", "Erases its keys", false, Icons.Rounded.LinkOff, N.red, onClick = { confirmUnpair = true }) }
        LinksCard(listOf("About Nova" to { app.go(Route.About) }))
    }
    if (confirmUnpair) OneDialog({ confirmUnpair = false }, "Unpair this phone?",
        "Its keys are erased. To use Nova again you'll need a new pairing code from the server.",
        listOf(DialogButton("Cancel") { confirmUnpair = false },
            DialogButton("Unpair", N.red) { confirmUnpair = false; app.pairing.clear(); app.paired = false }))
}

@Composable fun AboutScreen(app: AppState) {
    val s = app.overview?.optJSONObject("server")
    Page("About", app::back) {
        Column(Modifier.fillMaxWidth().padding(vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nova", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = N.text)
            Text("Version $APP_VERSION", color = N.sub)
        }
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
            Row1("Every request is signed", "By a key in this phone's secure chip, time-stamped and single-use", false); RowDivider()
            Row1("Risky actions need your fingerprint", "Shells, stopping things, installs, unmounting, power", false); RowDivider()
            Row1("At home: encrypted, pinned", "HTTPS to the server's own certificate, checked on every connection", false); RowDivider()
            Row1("Outside home: Cloudflare Access", "Strangers are stopped before they reach the server", false)
        }
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
