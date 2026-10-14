package app.novalabs.nova

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun InboxScreen(app: AppState) {
    var filter by remember { mutableIntStateOf(0) }
    val dataLive = live(app, "/api/v1/events?since=0", 15_000)
    val data by dataLive
    var clearAll by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<Double>()) }       // hold an event to start selecting
    var leaving by remember { mutableStateOf(setOf<Double>()) }      // being archived: folding away
    val selecting = picked.isNotEmpty()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    LaunchedEffect(Unit) { app.banner = null }                         // you're looking at them now
    androidx.activity.compose.BackHandler(enabled = selecting) { picked = emptySet() }
    LaunchedEffect(data) { data?.optJSONArray("events")?.let { InboxArchive.merge(app.activity, app.pairing.profile, it) } }
    /** Archive: gone from the server's inbox (every device), kept in this phone's history (Inbox → Archive). */
    fun delete(ts: List<Double>) {
        if (!app.isAdmin) { app.toast("This ${DeviceForm.noun} has view-only access"); return }
        val before = dataLive.value
        before?.optJSONArray("events")?.let { ev -> InboxArchive.archive(app.activity, app.pairing.profile,
            (0 until ev.length()).map { ev.getJSONObject(it) }.filter { e -> ts.any { kotlin.math.abs(it - e.optDouble("t")) < 0.000005 } }) }
        dataLive.value = JSONObject(before.toString()).also { o ->        // gone at once; the server catches up
            val left = org.json.JSONArray(); val ev = o.optJSONArray("events") ?: org.json.JSONArray()
            for (i in 0 until ev.length()) if (ts.none { kotlin.math.abs(it - ev.getJSONObject(i).optDouble("t")) < 0.000005 }) left.put(ev.getJSONObject(i))
            o.put("events", left)
        }
        app.act { try { app.api.post("/api/v1/events/delete", JSONObject().put("t", org.json.JSONArray(ts))) }
                  catch (e: Exception) { dataLive.value = before; throw e } }
    }
    val scope = rememberCoroutineScope()
    /** Archive with the row folding away first, so the rest slide up. */
    fun archive(ts: List<Double>) {
        if (!app.isAdmin) { app.toast("This ${DeviceForm.noun} has view-only access"); return }
        leaving = leaving + ts
        scope.launch { delay(if (reduceMotion()) 0 else 300); delete(ts); leaving = leaving - ts.toSet() }
    }
    val events = data?.optJSONArray("events")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    LaunchedEffect(events.firstOrNull()?.optDouble("t")) {
        events.firstOrNull()?.optDouble("t")?.let { app.pairing.lastEventSeen = it }; app.unread = 0
    }
    val shown = events.filter { e -> when (filter) { 1 -> e.optString("level") in listOf("warning", "critical"); 2 -> e.optString("level") == "critical"
        3 -> e.optString("category") == "login"; else -> true } }
    val day = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()); val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    val today = day.format(Date()); val yesterday = day.format(Date(System.currentTimeMillis() - 86_400_000L))
    fun dayLabel(t: Double) = day.format(Date((t * 1000).toLong())).let { when (it) { today -> "Today"; yesterday -> "Yesterday"; else -> it } }
    LaunchedEffect(shown.map { it.optDouble("t") }) { picked = picked.filter { t -> shown.any { it.optDouble("t") == t } }.toSet() }
    Page("Inbox", app::back, listOf(TopAction(Icons.Rounded.Inventory2, "Archive") { app.go(Route.Archive) },
            TopAction(Icons.Rounded.DeleteSweep, "Archive everything") { clearAll = true },
            TopAction(Icons.Rounded.Settings, "Notification settings") { app.go(Route.NotifySettings) })) {
        LiveTasks(app)
        AttentionList(app)
        IgnoredList(app)
        Segmented(listOf("All", "Issues", "Critical", "Logins"), filter) { filter = it }
        if (shown.isEmpty()) Text(if (data == null) "Loading…" else "Nothing here — all quiet.", color = N.sub, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp, horizontal = 24.dp))
        if (shown.isNotEmpty()) Text(if (selecting) "Tap to add or remove · back to stop selecting" else "Swipe left to archive, or hold to select several — they move to the Archive (kept on the server, for every device).",
            color = N.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 30.dp, end = 24.dp, top = 4.dp))
        // selection bar: slides in while selecting
        androidx.compose.animation.AnimatedVisibility(selecting,
            enter = if (reduceMotion()) androidx.compose.animation.fadeIn() else androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = if (reduceMotion()) androidx.compose.animation.fadeOut() else androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
            Row(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp).fillMaxWidth().glassCard(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                .padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${picked.size} selected", color = N.text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                val all = picked.size == shown.size
                TextButton({ picked = if (all) emptySet() else shown.map { it.optDouble("t") }.toSet() }) { Text(if (all) "Select none" else "Select all", color = N.blue, fontWeight = FontWeight.SemiBold) }
                TextButton({ val ts = picked.toList(); picked = emptySet(); archive(ts) }) {
                    Icon(Icons.Rounded.Inventory2, null, tint = N.blue, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text("Archive", color = N.blue, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        val selAnim = androidx.compose.animation.core.animateFloatAsState(if (selecting) 1f else 0f, androidx.compose.animation.core.tween(if (reduceMotion()) 0 else 220), label = "sel")
        val selSlot by remember { derivedStateOf { picked.isNotEmpty() || selAnim.value > 0.01f } }      // changes twice per selection, not every frame
        // drawn in pages: the newest 60, then more on request (a long list costs every frame it animates)
        var limit by remember { mutableIntStateOf(60) }
        shown.take(limit).groupBy { dayLabel(it.optDouble("t")) }.forEach { (d, list) ->
            val dayTs = list.map { it.optDouble("t") }.toSet(); val allDay = picked.containsAll(dayTs)
            // the day's title: hold it (or tap "Select day" while selecting) to pick the whole day
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (selecting) picked = if (allDay) picked - dayTs else picked + dayTs },
                    onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); picked = if (allDay) picked - dayTs else picked + dayTs })
                .padding(end = Space.gutter), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionLabel(d) }
                androidx.compose.animation.AnimatedVisibility(selecting, enter = androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                    Text(if (allDay) "Unselect day" else "Select day", color = N.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
            Group {
                list.forEachIndexed { i, e ->
                    val lvl = e.optString("level"); val t = e.optDouble("t"); val on = t in picked
                    val bg by androidx.compose.animation.animateColorAsState(if (on) N.blue.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent, label = "pick")
                    // archived rows fold away and the ones below slide up, instead of jumping
                    key(t) { androidx.compose.animation.AnimatedVisibility(t !in leaving,
                        exit = if (reduceMotion()) androidx.compose.animation.fadeOut() else androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(280)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180))) { Column {
                    if (i > 0) RowDivider()
                    SwipeRow(start = if (selecting) null else SwipeAction("Archive", Icons.Rounded.Inventory2, N.blue) { archive(listOf(t)) },
                             end = if (selecting) null else SwipeAction("Archive", Icons.Rounded.Inventory2, N.blue) { archive(listOf(t)) }) {
                    Row(Modifier.fillMaxWidth().background(bg)
                        .combinedClickable(onClick = { if (selecting) picked = if (on) picked - t else picked + t },
                            onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); picked = if (on) picked - t else picked + t })
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .graphicsLayer { translationX = if (selSlot) -(1f - selAnim.value) * 36.dp.toPx() else 0f }) {
                        // one shared animation for every row, applied while drawing (no per-row relayout each frame)
                        if (selSlot) {
                            Box(Modifier.padding(end = 14.dp, top = 1.dp).size(22.dp).graphicsLayer { alpha = selAnim.value; scaleX = 0.6f + 0.4f * selAnim.value; scaleY = scaleX }.clip(CircleShape)
                                .background(if (on) N.blue else androidx.compose.ui.graphics.Color.Transparent)
                                .then(if (on) Modifier else Modifier.border(2.dp, N.sub, CircleShape)), contentAlignment = Alignment.Center) {
                                if (on) Icon(Icons.Rounded.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(levelColor(lvl, N)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.optString("title").replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), ""), color = N.text, fontSize = 16.sp)
                            e.optString("detail").takeIf { it.isNotEmpty() }?.let { ExpandableSub(it, N.sub, lines = 3, size = 13.sp) }     // the arrow opens the full text
                        }
                        Text(hm.format(Date((e.optDouble("t") * 1000).toLong())), color = N.sub, fontSize = 13.sp)
                    }
                    } } } }
                }
            }
        }
        if (shown.size > limit) Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            PillButton("Show older (${shown.size - limit})", color = N.blue) { limit += 100 } }
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
            Row1("Automation", "Crash-loop guard, weekly updates, old-image cleanup, Wake-on-LAN", true, Icons.Rounded.AutoMode, androidx.compose.ui.graphics.Color(0xFF30B0C7), onClick = { app.go(Route.Automation) }); RowDivider()
            Row1("Labs", "Experimental features you can try", true, Icons.Rounded.Science, androidx.compose.ui.graphics.Color(0xFFBF5AF2), onClick = { app.go(Route.Labs) })
            RowDivider()
            Row1("Features", "Switch off parts of Nova you don't use, like lighting control", true, Icons.Rounded.Tune, onClick = { app.go(Route.Features) })
            RowDivider()
            Row1("Plugins", "Add-ons that bring more apps and tools", true, Icons.Rounded.Extension, onClick = { app.go(Route.Plugins) })
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
        val ctx = androidx.compose.ui.platform.LocalContext.current
        Group { Row1("Questions", "Is it safe, what it connects to, how to undo things", true, Icons.Rounded.Help, onClick = { app.go(Route.Faq) }); RowDivider()
            Row1("Request a feature", "Tell us what Nova should do — on GitHub", true, Icons.Rounded.Lightbulb, onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/dash1101/Nova/issues/new?template=feature_request.yml"))) } }) }
        SectionLabel("Project")
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


/** What's running on the server right now (installs, backups, updates, drive setup…), live.
 *  A task slides in the first time you see it, never again; when it ends it shows Done for a moment
 *  and leaves — the server puts it in the Inbox as an ordinary event. */
@Composable fun LiveTasks(app: AppState) {
    fun keep(l: List<JSONObject>): List<JSONObject> { val now = System.currentTimeMillis() / 1000.0
        return l.filter { it.optString("state") == "running" || (now - it.optDouble("finished", 0.0) < 6 && it.optString("id") in app.liveSeen && it.optString("id") !in app.liveHidden) } }
    LaunchedEffect(Unit) {
        while (true) {
            val l = runCatching { app.api.get("/api/v1/tasks").optJSONArray("tasks").objs() }.getOrNull()
            if (l != null) app.liveTasks = keep(l)
            val now = System.currentTimeMillis() / 1000.0
            delay(if (l.orEmpty().any { it.optString("state") == "running" || now - it.optDouble("finished", 0.0) < 8 }) 2_000 else 15_000)
        }
    }
    val tasks = app.liveTasks
    Column(Modifier.animateContentSize()) {
        if (tasks.isNotEmpty()) {
            SectionLabel(if (tasks.size > 1) "In progress · ${tasks.size}" else "In progress")
            Group {
                tasks.forEachIndexed { i, t ->
                    androidx.compose.runtime.key(t.optString("id")) {
                        val id = t.optString("id")
                        val vis = remember { androidx.compose.animation.core.MutableTransitionState(id in app.liveSeen).apply { targetState = true } }
                        SideEffect { app.liveSeen += id }
                        androidx.compose.animation.AnimatedVisibility(vis, enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically()) {
                            Column {
                                if (i > 0) RowDivider()
                                // a finished one swipes away (either way), like any Inbox event
                                if (t.optString("state") == "running") LiveTaskRow(app, t)
                                else { val hide = SwipeAction("Dismiss", Icons.Rounded.Close, N.blue) { app.liveHidden += id; app.liveTasks = app.liveTasks.filter { it.optString("id") != id } }
                                    SwipeRow(start = hide, end = hide) { LiveTaskRow(app, t) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun LiveTaskRow(app: AppState, t: JSONObject) {
    val st = t.optString("state"); val pct = t.optDouble("pct", 0.0)
    Column(Modifier.fillMaxWidth().clickable { taskRoute(t)?.let { app.go(it) } }.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.optString("title"), color = N.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text(when (st) { "running" -> "${pct.toInt()}%"; "done" -> "Done"; "stopped" -> "Stopped"; else -> "Failed" },
                color = when (st) { "done" -> N.green; "failed" -> N.red; else -> N.sub }, fontSize = 13.sp)
        }
        if (st == "running") {
            val anim by androidx.compose.animation.core.animateFloatAsState((pct / 100).toFloat(), label = "pct")
            LinearProgressIndicator(progress = { anim }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)), color = N.blue, trackColor = N.pill)
        }
        val sub = if (st == "running") t.optString("note").ifEmpty { t.optString("step") } else if (st == "done") "Moved to your Inbox" else t.optString("error").ifEmpty { t.optString("step") }
        if (sub.isNotEmpty()) ExpandableSub(sub, if (st == "done") N.green else N.sub, lines = 2, size = 13.sp)
    }
}

private fun taskRoute(t: JSONObject): Route? {
    val k = t.optString("kind")
    return when {
        k.startsWith("store-") -> Route.StoreItem(t.optString("key"))
        k.startsWith("program-") -> Route.Store
        k in listOf("updates-check", "apt-upgrade", "containers-update") -> Route.Updates
        k == "backup-run" || k == "restore" -> Route.Backups
        k.startsWith("custom-") -> Route.Containers
        else -> Route.Task(t.optString("id"))
    }
}


/** Settings → Features: parts of Nova you can switch off (another program runs the lights, you don't use the terminal…). */
@Composable fun FeaturesScreen(app: AppState) {
    val live = live(app, "/api/v1/features")
    val list = live.value?.optJSONArray("features").objs().filter { it.optBoolean("available") }
    Page("Features", app::back) {
        Note("Switch off what you don't use, or what another program takes care of — for example the board maker's RGB software. Switching these needs your fingerprint.", 14.sp, 6.dp)
        if (live.value == null) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp))
        else Group { list.forEachIndexed { i, f -> if (i > 0) RowDivider()
            SwitchRow(f.optString("name"), f.optString("about"), f.optBoolean("on"), app.isAdmin) { v -> app.act("${f.optString("name")}: ${if (v) "on" else "off"}") {
                live.value = app.stepUp("${if (v) "Turn on" else "Turn off"} ${f.optString("name")}", "POST", "/api/v1/features", JSONObject().put(f.optString("id"), v))
                app.refresh() } } } }
    }
}

/** Settings → Plugins: what's installed on the server (sudo nova plugin add …), and on/off for each. */
@Composable fun PluginsScreen(app: AppState) {
    val live = live(app, "/api/v1/plugins")
    val list = live.value?.optJSONArray("plugins").objs()
    Page("Plugins", app::back) {
        Note("Plugins add apps and one-tap tools to Nova. Their tools run as your normal account on the server, never as root.", 14.sp, 6.dp)
        if (live.value == null) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp))
        else if (list.isEmpty()) Group { Row1("No plugins yet", "On the server: sudo nova plugin add FOLDER — there's an example in the Nova repo (plugins/examples)", icon = Icons.Rounded.Extension) }
        else Group { list.forEachIndexed { i, p -> if (i > 0) RowDivider()
            val adds = listOfNotNull(p.optJSONArray("apps")?.length()?.takeIf { it > 0 }?.let { "$it app${if (it > 1) "s" else ""}" }, p.optJSONArray("commands")?.length()?.takeIf { it > 0 }?.let { "$it tool${if (it > 1) "s" else ""}" }).joinToString(", ")
            SwitchRow("${p.optString("name")} ${p.optString("version")}", listOf(p.optString("description"), adds, p.optString("author").takeIf { it.isNotEmpty() }?.let { "by $it" }).filter { !it.isNullOrEmpty() }.joinToString(" · "), p.optBoolean("on"), app.isAdmin) { v ->
                app.act { live.value = app.stepUp("${if (v) "Turn on" else "Turn off"} ${p.optString("name")}", "POST", "/api/v1/plugins", JSONObject().put(p.optString("id"), v)); Cache.put("/api/v1/apps", app.api.get("/api/v1/apps")) } } } }
        Text("Make your own: a folder with a plugin.json (see docs/PLUGINS.md), then sudo nova plugin add it.", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
    }
}

/** Settings → Labs: experimental features, off until you turn them on (they're the server's settings). */
@Composable fun LabsScreen(app: AppState) = LabsPage(app, stable = false)
/** Settings → Automation: the Labs features that have had enough use (crash-loop guard, weekly updates, image cleanup, Wake-on-LAN). */
@Composable fun AutomationScreen(app: AppState) = LabsPage(app, stable = true)

@Composable private fun LabsPage(app: AppState, stable: Boolean) {
    val live = live(app, "/api/v1/labs")
    val l = live.value
    val stableKeys = l?.optJSONArray("stable").let { a -> (0 until (a?.length() ?: 0)).map { a!!.getString(it) }.toSet() }
    Page(if (stable) "Automation" else "Labs", app::back) {
        Note(if (stable) "Things the server can take care of by itself. Each is off until you turn it on."
             else "Experimental features. They work, but haven't had as much use as the rest of Nova — so they're off until you turn them on.", 14.sp, 6.dp)
        if (l == null) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp))
        else Group {
            val about = l.optJSONObject("about") ?: JSONObject(); val on = l.optJSONObject("labs") ?: JSONObject()
            about.keys().asSequence().toList().filter { (it in stableKeys) == stable }.forEachIndexed { i, k -> val a = about.getJSONObject(k)
                if (i > 0) RowDivider()
                SwitchRow(a.optString("name"), a.optString("about"), on.optBoolean(k), app.isAdmin) { v -> app.act { live.value = app.api.post("/api/v1/labs", JSONObject().put(k, v)) } }
            }
        }
        val on = l?.optJSONObject("labs") ?: JSONObject()
        if (!stable) {
            if (on.optBoolean("cloudflare_sync")) LabsCloudflare(app)
            if (on.optBoolean("openrgb")) LabsOpenRgb(app)
        } else {
            if (on.optBoolean("auto_updates")) LabsSchedule(app)
            if (on.optBoolean("image_cleanup")) LabsImages(app)
            if (on.optBoolean("wake_on_lan")) LabsWol(app)
        }
        if (stable) LinksCard(listOf("Experimental features (Labs)" to { app.go(Route.Labs) }))
    }
}

/** Labs → OpenRGB: where its SDK server is, and the devices it has (each one can be a light in Lighting). */
@Composable private fun LabsOpenRgb(app: AppState) {
    var cfg by remember { mutableStateOf<JSONObject?>(null) }
    var edit by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cfg = runCatching { app.api.get("/api/v1/labs/openrgb") }.getOrNull() }
    SectionLabel("OpenRGB")
    Group {
        val c = cfg
        Row1("OpenRGB at ${c?.optString("host") ?: "…"}:${c?.optInt("port") ?: 6742}", "Its SDK server (in OpenRGB: SDK Server → Start Server, or run openrgb --server)", true, Icons.Rounded.Lan,
            onClick = if (app.isAdmin) ({ edit = true }) else null)
        RowDivider()
        Row1(if (busy) "Looking…" else "Look for devices", "Then add them as lights in Lighting → Set up", true, Icons.Rounded.Refresh, enabled = !busy && app.isAdmin, onClick = {
            busy = true; app.act { try { cfg = app.api.post("/api/v1/labs/openrgb/scan") } finally { busy = false } } })
        c?.optJSONArray("devices").objs().forEach { d -> RowDivider()
            Row1(d.optString("name"), "${d.optInt("leds")} LEDs" + (d.optJSONArray("zones").objs().takeIf { it.size > 1 }?.let { z -> " · " + z.joinToString(", ") { it.optString("name") } } ?: ""),
                icon = Icons.Rounded.Lightbulb, iconTint = N.amber, onClick = { app.go(Route.Lights) }) }
    }
    Note("With OpenRGB on, Nova draws every effect itself and sends the colors to OpenRGB, so they work on any device it supports. Nova stops talking to the motherboard's lighting controller directly (OpenRGB does that now); turn this off to go back.", 13.sp, 4.dp)
    if (edit) { var host by remember { mutableStateOf(cfg?.optString("host") ?: "127.0.0.1") }; var port by remember { mutableStateOf((cfg?.optInt("port") ?: 6742).toString()) }
        OneDialog({ edit = false }, "Where's OpenRGB?", "On this server it's 127.0.0.1. It can also run on another computer on your network.",
            listOf(DialogButton("Cancel") { edit = false }, DialogButton("Save", N.blue) { edit = false
                app.act { cfg = app.api.post("/api/v1/labs/openrgb", JSONObject().put("host", host.trim()).put("port", port.toIntOrNull() ?: 6742)) } })) {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OneTextField(host, { host = it.trim().take(64) }, "Address, like 127.0.0.1", Modifier.fillMaxWidth(), mono = true)
                OneTextField(port, { port = it.filter(Char::isDigit).take(5) }, "Port (6742)", Modifier.fillMaxWidth(), mono = true)
            }
        } }
}

/** The app (if any) whose remote link is this address — so its page can offer share links. */
fun appForHost(h: String, service: String = ""): String {
    val apps = Cache["/api/v1/apps"]?.optJSONArray("apps").objs()
    val port = Regex(":(\\d+)").find(service.substringAfter("://"))?.groupValues?.get(1)?.toIntOrNull()
    return (apps.firstOrNull { android.net.Uri.parse(it.optString("remote_url")).host == h }       // its remote link is this address
        ?: port?.let { p -> apps.firstOrNull { it.optInt("port") == p && service.contains("localhost") || it.optInt("port") == p && service.contains("127.0.0.1") } })  // or the route goes to its port
        ?.optString("id").orEmpty()
}

/** How to make the Cloudflare API token (checked against Cloudflare's docs: listing zones needs Zone · Zone · Read too). */
val CF_STEPS = listOf(
    "Go to dash.cloudflare.com → your profile icon (top right) → My Profile → API Tokens, and select Create Token.",
    "Next to “Create Custom Token”, select Get started. Name it something like Nova.",
    "Under Permissions, add five rows: Account · Cloudflare Tunnel · Edit (newer dashboards may call it “Cloudflare One Connector: cloudflared”), Account · Access: Apps and Policies · Edit, Account · Access: Service Tokens · Edit (for app keys), Zone · DNS · Edit, and Zone · Zone · Read.",
    "Account Resources: Include → your account. Zone Resources: Include → Specific zone → your domain.",
    "Select Continue to summary → Create Token, copy it (Cloudflare shows it only once) and paste it here with Add an API token.")

/** Labs → Cloudflare: the API token Nova uses, and what's on your tunnel. Then: Apps → hold an app → Open it from anywhere → Set it up for me. */
@Composable private fun LabsCloudflare(app: AppState) {
    var st by remember { mutableStateOf<JSONObject?>(null) }; var err by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var tokenAsk by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    shown?.let { tok -> OneDialog({ shown = null }, "Cloudflare API token", "Paste it in Settings → Cloudflare on the other server. Anyone with it can change your Cloudflare setup, so keep it private.",
        listOf(DialogButton("Close") { shown = null }, DialogButton("Copy", N.blue) { shown = null
            ctx.getSystemService(android.content.ClipboardManager::class.java)?.setPrimaryClip(android.content.ClipData.newPlainText("Cloudflare token", tok)); app.toast("Copied") })) {
        Text(tok, color = N.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 22.dp)) } }
    var undo by remember { mutableStateOf<Pair<String, String>?>(null) }
    undo?.let { (h, kind) -> val pub = kind == "protected"
        OneDialog({ undo = null }, if (pub) "Take the login off $h?" else "Take $h off?",
            if (pub) "Nova only added the login to this address (it was yours before). Taking it off makes $h open to anyone again — the route and DNS record stay."
            else "Nova removes what it added for $h: the tunnel route, the DNS record and the login. Anything else in your Cloudflare account stays as it is.",
            listOf(DialogButton("Cancel") { undo = null }, DialogButton(if (pub) "Make it public" else "Take it off", N.red) { undo = null
                app.act { app.stepUp(if (pub) "Take Nova's login off $h — it will be open to anyone" else "Take $h off your Cloudflare tunnel", "POST", "/api/v1/cloudflare/remove",
                    JSONObject().put("host", h).put("allow_public", pub)); app.toast("Done"); round++ } })) }
    LaunchedEffect(round) { try { st = app.api.get("/api/v1/cloudflare"); err = null } catch (e: Exception) { err = e.message } }
    SectionLabel("Cloudflare")
    Group {
        val s = st
        when {
            err != null -> Row1(err!!)
            s == null -> Row1("Checking your Cloudflare account…")
            else -> {
                val ok = s.optBoolean("token") && s.optString("error").isEmpty()
                Row1(if (ok) "Connected" else if (!s.optBoolean("token")) "Add an API token to start" else "Not working", if (ok) "New app addresses get the login of “${s.optString("access_app")}”" else s.optString("error").ifEmpty { "Nova needs a Cloudflare API token to make changes for you." },
                    false, if (ok) Icons.Rounded.Shield else Icons.Rounded.Cloud, if (ok) N.green else N.amber)
                if (app.isAdmin) { RowDivider(); Row1(if (s.optBoolean("token")) "Change the API token" else "Add an API token", "Kept on the server, readable only by root", true, Icons.Rounded.Key, onClick = { tokenAsk = true }) }
                if (app.isAdmin && s.optBoolean("token")) { RowDivider(); Row1("Show the token", "To use the same token on another Nova server", false, Icons.Rounded.Visibility,
                    onClick = { app.act { shown = app.stepUp("Show the Cloudflare API token", "POST", "/api/v1/cloudflare/token/show", JSONObject()).optString("token").ifEmpty { null } } }) }
                val mine = s.optJSONObject("nova_added") ?: JSONObject(); val prot = s.optJSONArray("protected").let { p -> (0 until (p?.length() ?: 0)).map { p!!.getString(it) }.toSet() }
                s.optJSONArray("hostnames")?.let { hs -> for (i in 0 until hs.length()) { val h = hs.getString(i); RowDivider()
                    val kind = mine.optString(h)
                    Row1(h, when { h == s.optString("nova_host") -> "Nova itself"; kind == "added" -> "Added by Nova"; kind == "protected" -> "Yours — Nova added the login"
                                   h in prot -> "Yours · behind the login"; else -> "Yours · open to anyone (no login)" },
                        icon = Icons.Rounded.Public, iconTint = if (h in prot || h == s.optString("nova_host")) N.green else N.amber,
                        onClick = if (h == s.optString("nova_host")) null else ({ app.go(Route.Access(h, appForHost(h, s.optJSONObject("services")?.optString(h).orEmpty()))) }),
                        // only what Nova did can be undone from here; everything else stays as you set it up
                        trailing = if (app.isAdmin && kind.isNotEmpty()) ({ IconButton({ undo = h to kind }) { Icon(Icons.Rounded.Delete, "Take it off", tint = N.sub) } }) else null) } }
            }
        }
    }
    st?.optJSONArray("others").objs().takeIf { it.isNotEmpty() }?.let { os ->
        SectionLabel("Added by your other Nova servers")
        Group { os.forEachIndexed { i, o -> if (i > 0) RowDivider(); Row1(o.optString("host"), "By ${o.optString("server")} — change it there", icon = Icons.Rounded.Dns) } } }
    if (st?.optBoolean("token") == true && st?.optString("me").orEmpty().isNotEmpty())
        Note("Several Nova servers can share one token: what each adds is signed with its name (this one is “${st!!.optString("me")}”), and each only takes off its own.", 13.sp, 4.dp)
    // once it's connected, the steps fold away behind a link
    val connected = st?.optBoolean("token") == true && st?.optString("error").isNullOrEmpty()
    var showSteps by remember { mutableStateOf(false) }
    if (connected && !showSteps) Text("How to make a token", color = N.link, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 30.dp, top = 6.dp).clickable { showSteps = true }.padding(vertical = 6.dp))
    if (!connected || showSteps) SectionLabel("Making the token")
    if (!connected || showSteps) Group { Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CF_STEPS.forEachIndexed { i, step -> Row { Text("${i + 1}.", color = N.blue, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
            Text(step, color = N.text, fontSize = 14.sp) } }
    } }
    Note("Your tunnel has to be one you run with a token (set up in the Cloudflare dashboard). Then: hold an app in Apps → Open it from anywhere → Set it up for me.", 13.sp, 6.dp)
    if (tokenAsk) { var t by remember { mutableStateOf("") }
        OneDialog({ tokenAsk = false }, "Cloudflare API token", "Paste the token you created.", listOf(DialogButton("Cancel") { tokenAsk = false },
            DialogButton("Save", N.blue, enabled = t.length >= 30) { tokenAsk = false
                app.act("Saved") { app.stepUp("Save the Cloudflare API token", "POST", "/api/v1/cloudflare/token", JSONObject().put("token", t.trim())); round++ } })) {
            OneTextField(t, { t = it.trim().take(200) }, "Token", Modifier.fillMaxWidth().padding(horizontal = 22.dp), mono = true) } }
}

private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private fun hourLabel(h: Int) = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(GregorianCalendar(2000, 0, 1, h, 0).time)

@Composable private fun LabsSchedule(app: AppState) {
    var s by remember { mutableStateOf<JSONObject?>(null) }
    var pick by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { s = runCatching { app.api.get("/api/v1/labs/schedule") }.getOrNull() }
    val day = s?.optInt("day", 6) ?: 6; val hour = s?.optInt("hour", 4) ?: 4
    fun save(d: Int, h: Int) = app.act("Saved") { s = app.api.post("/api/v1/labs/schedule", JSONObject().put("day", d).put("hour", h)) }
    SectionLabel("Weekly updates")
    Group {
        Row1("Day", DAYS[day], true, Icons.Rounded.CalendarMonth, enabled = app.isAdmin, onClick = { pick = "day" }); RowDivider()
        Row1("Time", hourLabel(hour) + (s?.optDouble("last", 0.0)?.takeIf { it > 0 }?.let { " · last ran " + SimpleDateFormat("MMM d", Locale.getDefault()).format(Date((it * 1000).toLong())) } ?: ""),
            true, Icons.Rounded.Schedule, enabled = app.isAdmin, onClick = { pick = "hour" })
    }
    Text("Pick a quiet time — each container is offline for a few seconds while it restarts.", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
    pick?.let { k -> OneDialog({ pick = null }, if (k == "day") "Which day?" else "What time?") {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (k == "day") DAYS.forEachIndexed { i, d -> DialogChoice(d, selected = i == day) { pick = null; save(i, hour) } }
            else (0..23).forEach { h -> DialogChoice(hourLabel(h), selected = h == hour) { pick = null; save(day, h) } }
        }
    } }
}

@Composable private fun LabsImages(app: AppState) {
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { try { d = app.api.get("/api/v1/labs/images") } catch (e: Exception) { err = e.message } }
    val imgs = d?.optJSONArray("images").objs()
    SectionLabel("Old images")
    Group {
        when {
            err != null -> Row1(err!!)
            d == null -> Row1("Looking through your images…")
            imgs.isEmpty() -> Row1("Nothing to clean up", "Every image is in use", icon = Icons.Rounded.CheckCircle, iconTint = N.green)
            else -> {
                imgs.take(8).forEachIndexed { i, e -> if (i > 0) RowDivider()
                    Row1(e.optJSONArray("tags")?.optString(0)?.ifEmpty { null } ?: e.optString("what").ifEmpty { null }?.let { "Old version of $it" } ?: "Untagged image", listOf(e.optString("size"), e.optString("created")).filter { it.isNotEmpty() }.joinToString(" · "), icon = Icons.Rounded.Storage) }
                if (imgs.size > 8) { RowDivider(); Row1("…and ${imgs.size - 8} more") }
                if (app.isAdmin) { RowDivider(); Row1("Clean up", "Removes these ${imgs.size}. You have ${bytesHuman(d!!.optLong("free"))} free now.", false, Icons.Rounded.Delete, N.red, onClick = { ask = true }) }
            }
        }
    }
    Note("Images no container uses — mostly old versions left behind by updates. The versions kept for rolling back stay.", 13.sp, 4.dp)
    if (ask) OneDialog({ ask = false }, "Remove ${imgs.size} unused image${if (imgs.size == 1) "" else "s"}?", "Images a container uses, and the versions kept for rolling back, stay.",
        listOf(DialogButton("Cancel") { ask = false }, DialogButton("Clean up", N.red) { ask = false
            app.act { val r = app.stepUp("Clean up old images", "POST", "/api/v1/labs/images/clean"); r.optString("task").ifEmpty { null }?.let { app.go(Route.Task(it)) } } }))
}

@Composable private fun LabsWol(app: AppState) {
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var add by remember { mutableStateOf(false) }
    var rm by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { list = runCatching { app.api.get("/api/v1/labs/wol").optJSONArray("devices").objs() }.getOrNull() }
    SectionLabel("Wake-on-LAN")
    Group {
        val l = list
        if (l == null) Row1("Loading…")
        else if (l.isEmpty()) Row1("No computers yet", "Add one with its MAC address")
        l?.forEachIndexed { i, e -> if (i > 0) RowDivider()
            Row1(e.optString("name"), e.optString("mac"), false, Icons.Rounded.PowerSettingsNew, onClick = { app.act { app.toast(app.api.post("/api/v1/labs/wol/${e.optString("id")}/wake").optString("note").ifEmpty { "Sent" }) } },
                trailing = if (app.isAdmin) ({ IconButton({ rm = e }) { Icon(Icons.Rounded.Delete, "Remove", tint = N.sub) } }) else null) }
        if (app.isAdmin) { if (l != null) RowDivider(); Row1("Add a computer", null, false, Icons.Rounded.Add, onClick = { add = true }) }
    }
    Note("Tap a computer to turn it on. Wake-on-LAN has to be turned on in its BIOS (and its network settings) first.", 13.sp, 4.dp)
    if (add) { var name by remember { mutableStateOf("") }; var mac by remember { mutableStateOf("") }
        OneDialog({ add = false }, "Add a computer", "Its MAC address is in its network settings (Windows: ipconfig /all, “Physical Address”; Linux: ip link).",
            listOf(DialogButton("Cancel") { add = false }, DialogButton("Add", N.blue) { add = false
                app.act { list = app.api.post("/api/v1/labs/wol", JSONObject().put("name", name).put("mac", mac)).optJSONArray("devices").objs() } })) {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OneTextField(name, { name = it.take(40) }, "Name, like Gaming PC", Modifier.fillMaxWidth())
                OneTextField(mac, { mac = it.take(17) }, "MAC, like 3c:7c:3f:12:34:56", Modifier.fillMaxWidth(), mono = true)
            }
        } }
    rm?.let { e -> OneDialog({ rm = null }, "Remove ${e.optString("name")}?", "It only leaves this list.",
        listOf(DialogButton("Cancel") { rm = null }, DialogButton("Remove", N.red) { rm = null
            app.act { list = app.api.post("/api/v1/labs/wol/${e.optString("id")}/remove").optJSONArray("devices").objs() } })) }
}
