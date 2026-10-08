package app.novalabs.nova

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** Everything that can live in the Quick panel. Ids are stored on the phone, in order. */
data class QuickDef(val id: String, val label: String, val icon: ImageVector, val hint: String)

val QUICK_ACTIONS = listOf(
    QuickDef("backup", "Back up now", Icons.Rounded.Backup, "Start a backup now"),
    QuickDef("freeram", "Free RAM", Icons.Rounded.Memory, "Drop the disk cache"),
    QuickDef("fan", "Fan light", Icons.Rounded.Lightbulb, "On / off"),
    QuickDef("dim", "Dim fan", Icons.Rounded.BrightnessLow, "Brightness 5%"),
    QuickDef("bright", "Bright fan", Icons.Rounded.BrightnessHigh, "Brightness 60%"),
    QuickDef("status_light", "Status light", Icons.Rounded.Traffic, "Fan shows server health"),
    QuickDef("discord", "Discord pings", Icons.Rounded.NotificationsActive, "Pause / resume alerts"),
    QuickDef("terminal", "Terminal", Icons.Rounded.Terminal, "SSH into the server"),
    QuickDef("status", "Server status", Icons.Rounded.MonitorHeart, "Live graphs and health"),
    QuickDef("dashboard", "Dashboard", Icons.Rounded.Dashboard, "Always-on screen"),
    QuickDef("containers", "Containers", Icons.Rounded.ViewInAr, "Open the list"),
    QuickDef("lighting", "Lighting", Icons.Rounded.Palette, "Colours and effects"),
    QuickDef("inbox", "Inbox", Icons.Rounded.Notifications, "Alerts and logins"),
    QuickDef("storage", "Storage", Icons.Rounded.Storage, "Drives and temperatures"),
    QuickDef("store", "App store", Icons.Rounded.Storefront, "Install apps"),
)
val DEFAULT_QUICK = listOf("backup", "freeram", "fan", "dim", "terminal", "status")

fun quickDef(id: String): QuickDef? = if (id.startsWith("restart:"))
    QuickDef(id, "Restart ${id.removePrefix("restart:")}", Icons.Rounded.RestartAlt, "Fingerprint to confirm")
else QUICK_ACTIONS.firstOrNull { it.id == id }

/** One UI quick-panel tile: blue when "on", card-coloured otherwise. Long-press opens its settings. */
@Composable fun QuickTile(icon: ImageVector, label: String, state: String?, active: Boolean, modifier: Modifier = Modifier,
                          onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) N.blue else N.card, label = "tile")
    val fg = if (active) Color.White else N.text
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(modifier.height(76.dp).bouncy(onLongClick = onLongClick?.let { l -> { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); l() } }, onClick = onClick)
        .clip(RoundedCornerShape(24.dp)).background(bg)
        .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (active) Color.White.copy(alpha = 0.22f) else N.pill), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (active) Color.White else N.blue, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!state.isNullOrEmpty()) Text(state, color = fg.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Google Home–style light tile: the fill shows the brightness; drag sideways to change it,
 * tap to switch on/off, long-press for the full Lighting page.
 */
@Composable fun SliderTile(icon: ImageVector, label: String, on: Boolean, value: Int, enabled: Boolean, modifier: Modifier = Modifier,
                           onToggle: () -> Unit, onSet: (Int) -> Unit, onLongClick: () -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }          // 0..1 while dragging
    var width by remember { mutableFloatStateOf(1f) }
    val shown = drag ?: (if (on) value / 100f else 0f)
    val fill by androidx.compose.animation.core.animateFloatAsState(shown, label = "fill",
        animationSpec = if (drag != null) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring())
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(modifier.height(76.dp).clip(RoundedCornerShape(24.dp)).background(N.card)
        .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectHorizontalDragGestures(
                onDragStart = { o -> drag = (o.x / width).coerceIn(0f, 1f) },
                onDragEnd = { drag?.let { onSet((it * 100).toInt().coerceIn(1, 100)) }; drag = null },
                onDragCancel = { drag = null },
                onHorizontalDrag = { ch, dx -> ch.consume(); drag = ((drag ?: 0f) + dx / width).coerceIn(0f, 1f) })
        }
        .combinedClickable(onClick = onToggle, onLongClick = { haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongClick() })) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(N.blue.copy(alpha = if (on || drag != null) 0.9f else 0.25f)))
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (on) Color.White else N.blue, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = if (on || fill > 0.35f) Color.White else N.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(if (drag != null) "${(drag!! * 100).toInt().coerceAtLeast(1)}%" else if (on) "$value%" else "Off",
                    color = (if (on || fill > 0.35f) Color.White else N.text).copy(alpha = 0.8f), fontSize = 12.sp)
            }
            Text("Slide to dim", color = (if (fill > 0.8f) Color.White else N.sub).copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

/** Where a long-press on each tile goes. */
private fun settingsFor(id: String): Route? = when {
    id.startsWith("restart:") -> Route.Container(id.removePrefix("restart:"))
    id in listOf("fan", "dim", "bright", "status_light", "lighting") -> Route.Lighting
    id in listOf("backup", "freeram", "status") -> Route.Status
    id == "discord" || id == "inbox" -> Route.NotifySettings
    id == "terminal" -> Route.Ssh
    id == "containers" -> Route.Containers
    id == "storage" -> Route.Hardware
    else -> null
}

@Composable fun QuickPanelScreen(app: AppState) {
    var confirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val backupState = live(app, "/api/v1/backup", 3_000)
    val backup = backupState.value
    val notify = live(app, "/api/v1/notify")
    val needs = mapOf("fan" to "lighting", "dim" to "lighting", "bright" to "lighting", "status_light" to "lighting",
        "lighting" to "lighting", "backup" to "backup", "terminal" to "ssh", "store" to "store", "status" to "monitor")
    val ids = app.pairing.quickActions.filter { quickDef(it) != null && needs[it]?.let { f -> app.has(f) } != false }
    val running = busy == "backup" || backup?.optBoolean("running") == true
    val fan = app.fan

    fun state(id: String): Pair<String?, Boolean> = when (id) {
        "backup" -> (if (running) backup?.let { backupLine(it).removePrefix("Backing up ") } ?: "Starting…" else "Last: ${backup?.optString("time")?.drop(5)?.take(11) ?: "—"}") to running
        "fan" -> (if (fan?.optBoolean("on") != false) "On · ${fan?.optInt("brightness") ?: 0}%" else "Off") to (fan?.optBoolean("on") != false)
        "dim" -> null to (fan?.optBoolean("on") != false && fan?.optInt("brightness") == 5)
        "bright" -> null to (fan?.optBoolean("on") != false && fan?.optInt("brightness") == 60)
        "status_light" -> (if (fan?.optBoolean("status_light") == true) "On" else "Off") to (fan?.optBoolean("status_light") == true)
        "discord" -> notify.value?.let { n -> if (n.optBoolean("discord_paused")) "Paused" to false else "On" to true } ?: (null to false)
        "terminal" -> (if (SshSession.active) "Connected" else null) to SshSession.active
        "freeram" -> (if (busy == "freeram") "Working…" else null) to (busy == "freeram")
        else -> (if (busy == id) "Working…" else null) to false
    }

    fun run(id: String) {
        val opensScreen = id in listOf("terminal", "status", "dashboard", "containers", "lighting", "inbox", "storage", "store")
        if (!app.isAdmin && !opensScreen) { app.toast("This phone has view-only access"); return }
        when (id) {
            "backup" -> if (!running) { busy = "backup"
                app.act { try { app.api.post("/api/v1/actions/backup"); backupState.value = app.api.get("/api/v1/backup") } finally { busy = null } } }
            "freeram" -> { busy = "freeram"; app.act { try { val r = app.api.post("/api/v1/actions/free-ram")
                app.toast("Freed ${r.optInt("freed_mb")} MB · ${r.optInt("available_mb")} MB available") } finally { busy = null } } }
            "fan" -> app.changeFan(JSONObject().put("on", fan?.optBoolean("on") == false))
            "dim" -> app.changeFan(JSONObject().put("on", true).put("brightness", 5))
            "bright" -> app.changeFan(JSONObject().put("on", true).put("brightness", 60))
            "status_light" -> app.changeFan(JSONObject().put("status_light", fan?.optBoolean("status_light") != true))
            "discord" -> { val paused = notify.value?.optBoolean("discord_paused") == true
                val before = notify.value; notify.value = JSONObject(before?.toString() ?: "{}").put("discord_paused", !paused)
                app.act { try { notify.value = app.api.post("/api/v1/notify", JSONObject().put("discord_paused", !paused)) } catch (e: Exception) { notify.value = before; throw e } } }
            "terminal" -> app.go(if (SshSession.active) Route.SshTerm else Route.Ssh)
            "status" -> app.go(Route.Status)
            "dashboard" -> app.go(Route.Dashboard)
            "containers" -> app.go(Route.Containers)
            "lighting" -> app.go(Route.Lighting)
            "inbox" -> app.go(Route.Inbox)
            "storage" -> app.go(Route.Hardware)
            "store" -> app.tab(Route.Store)
            else -> if (id.startsWith("restart:")) { val name = id.removePrefix("restart:"); busy = id
                app.act("$name restarted") { try { app.stepUp("Restart $name", "POST", "/api/v1/containers/$name/restart") } finally { busy = null } } }
        }
    }

    Page("Quick panel", app::back, listOf(TopAction(Icons.Rounded.Edit, "Edit") { app.go(Route.EditQuick) })) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // The fan light gets a full-width slider tile; everything else sits two per row.
            if ("fan" in ids) {
                val f = app.fan
                SliderTile(Icons.Rounded.Lightbulb, "Fan light", f?.optBoolean("on") != false, f?.optInt("brightness") ?: 50, app.isAdmin,
                    Modifier.fillMaxWidth(), onToggle = { run("fan") },
                    onSet = { v -> app.changeFan(JSONObject().put("on", true).put("brightness", v)) },
                    onLongClick = { app.go(Route.Lighting) })
            }
            ids.filter { it != "fan" }.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { id -> val d = quickDef(id)!!; val (st, on) = state(id)
                        QuickTile(d.icon, d.label, st, on, Modifier.weight(1f), onLongClick = settingsFor(id)?.let { r -> { app.go(r) } }) { run(id) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Text("Tip: long-press a tile for its settings.", color = N.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            if (ids.isEmpty()) Text("No quick actions — tap the pencil to add some.", color = N.sub, modifier = Modifier.padding(16.dp))
        }
        if (running && backup != null) { SectionLabel("Backup"); Group { Spacer(Modifier.height(16.dp)); BackupProgress(backup, inset = 22.dp) } }
        if (app.isAdmin) SectionLabel("Power")
        if (app.isAdmin) Group {
            Row1("Restart server", "Everything goes offline for about 2 minutes", false, Icons.Rounded.RestartAlt, N.amber,
                onClick = { confirm = "reboot" })
            RowDivider()
            Row1("Shut down server", "You'll need to press the power button to turn it back on", false,
                Icons.Rounded.PowerSettingsNew, N.red, onClick = { confirm = "poweroff" })
        }
    }
    confirm?.let { what ->
        OneDialog({ confirm = null }, if (what == "reboot") "Restart the server?" else "Shut down the server?",
            "Everything running on it will be unavailable until it's back.",
            listOf(DialogButton("Cancel") { confirm = null },
                DialogButton(if (what == "reboot") "Restart" else "Shut down", N.red) { confirm = null
                    app.act("Done — the server will ${if (what == "reboot") "restart" else "shut down"} in 5 seconds") {
                        app.stepUp(if (what == "reboot") "Restart the server" else "Shut down the server", "POST", "/api/v1/power/$what") } }))
    }
}

@Composable fun EditQuickScreen(app: AppState) {
    var ids by remember { mutableStateOf(app.pairing.quickActions) }
    var pickContainer by remember { mutableStateOf(false) }
    val containers by live(app, "/api/v1/containers")
    fun save(v: List<String>) { ids = v; app.pairing.quickActions = v }
    Page("Edit quick panel", app::back) {
        SectionLabel("In the panel · in this order")
        Group {
            if (ids.isEmpty()) Text("Nothing yet — add some below.", color = N.sub, modifier = Modifier.padding(22.dp))
            ids.forEachIndexed { i, id -> val d = quickDef(id) ?: return@forEachIndexed
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(d.icon, null, tint = N.blue, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp))
                    Text(d.label, color = N.text, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ if (i > 0) save(ids.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                        Icon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = if (i > 0) N.text else N.divider) }
                    IconButton({ if (i < ids.lastIndex) save(ids.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < ids.lastIndex) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Move down", tint = if (i < ids.lastIndex) N.text else N.divider) }
                    IconButton({ save(ids - id) }) { Icon(Icons.Rounded.RemoveCircle, "Remove", tint = N.red) }
                }
            }
        }
        SectionLabel("Add")
        Group {
            val more = QUICK_ACTIONS.filter { it.id !in ids }
            more.forEachIndexed { i, d -> if (i > 0) RowDivider()
                Row1(d.label, d.hint, icon = d.icon, onClick = { save(ids + d.id) }) { Icon(Icons.Rounded.AddCircle, "Add", tint = N.green) } }
            if (more.isNotEmpty()) RowDivider()
            Row1("Restart a container…", "Pick one — restarting asks for your fingerprint", icon = Icons.Rounded.RestartAlt,
                onClick = { pickContainer = true }) { Icon(Icons.Rounded.AddCircle, "Add", tint = N.green) }
        }
        LinksCard(listOf("Reset to the default tiles" to { save(DEFAULT_QUICK) }))
    }
    if (pickContainer) {
        val names = containers?.optJSONArray("containers")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }?.sorted() ?: emptyList()
        OneDialog({ pickContainer = false }, "Restart which container?", buttons = listOf(DialogButton("Cancel") { pickContainer = false })) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                names.forEach { n -> DialogChoice(n, null, "restart:$n" in ids) { pickContainer = false; if ("restart:$n" !in ids) save(ids + "restart:$n") } }
            }
        }
    }
}
