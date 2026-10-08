package app.novalabs.nova

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** A shortcut that can sit in the Home screen's chip bar. */
class Shortcut(val id: String, val icon: ImageVector, val label: String, val route: Route, val feature: String? = null, val short: String = label)

val SHORTCUTS = listOf(
    Shortcut("inbox", Icons.Rounded.Notifications, "Inbox", Route.Inbox),
    Shortcut("quick", Icons.Rounded.Widgets, "Quick panel", Route.QuickPanel, short = "Quick"),
    Shortcut("containers", Icons.Rounded.ViewInAr, "Containers", Route.Containers),
    Shortcut("storage", Icons.Rounded.Storage, "Storage", Route.Hardware),
    Shortcut("status", Icons.Rounded.MonitorHeart, "Status", Route.Status),
    Shortcut("lighting", Icons.Rounded.Light, "Lighting", Route.Lighting, "lighting"),
    Shortcut("terminal", Icons.Rounded.Terminal, "Terminal", Route.Ssh, "ssh"),
    Shortcut("store", Icons.Rounded.Storefront, "Store", Route.Store, "store"),
    Shortcut("dashboard", Icons.Rounded.Dashboard, "Dashboard", Route.Dashboard),
    Shortcut("schedules", Icons.Rounded.Schedule, "Schedules", Route.Schedules, "lighting"),
    Shortcut("devices", Icons.Rounded.Group, "Devices", Route.Devices),
    Shortcut("settings", Icons.Rounded.Settings, "Settings", Route.Settings),
)
const val MAX_SHORTCUTS = 5
val DEFAULT_SHORTCUTS = listOf("inbox", "quick", "containers", "storage")

fun homeShortcuts(app: AppState): List<Shortcut> =
    AppPrefs.homeChips.mapNotNull { id -> SHORTCUTS.firstOrNull { it.id == id } }.filter { it.feature == null || app.has(it.feature) }

/** Settings → Appearance → Home shortcuts (also: long-press the bar on Home). Hold and drag to reorder. */
@Composable fun EditShortcutsScreen(app: AppState) {
    val ids = AppPrefs.homeChips
    fun save(v: List<String>) = AppPrefs.set("home_chips", v.joinToString(","))
    Page("Home shortcuts", app::back) {
        // live preview of the bar
        Box(Modifier.padding(top = 4.dp, bottom = 6.dp)) {
            if (ids.isEmpty()) Text("No shortcuts — add some below.", color = N.sub, modifier = Modifier.padding(horizontal = 30.dp, vertical = 16.dp))
            else homeShortcuts(app).let { l -> PillBar(l.map { s -> PillItem(s.icon, if (l.size >= 5) s.short else s.label) {} }) }
        }
        SectionLabel("On Home · hold and drag to reorder")
        Group {
            if (ids.isEmpty()) Text("Nothing here yet.", color = N.sub, modifier = Modifier.padding(22.dp))
            ReorderList(ids, onMove = ::save) { id, dragging ->
                val s = SHORTCUTS.firstOrNull { it.id == id } ?: return@ReorderList
                Row(Modifier.fillMaxWidth().height(ROW_H).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DragHandle, "Hold to move", tint = if (dragging) N.blue else N.sub, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Icon(s.icon, null, tint = N.blue, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp))
                    Text(s.label, color = N.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    IconButton({ save(ids - id) }) { Icon(Icons.Rounded.RemoveCircle, "Remove", tint = N.red) }
                }
            }
        }
        val more = SHORTCUTS.filter { it.id !in ids && (it.feature == null || app.has(it.feature)) }
        SectionLabel(if (ids.size >= MAX_SHORTCUTS) "Add · the bar is full ($MAX_SHORTCUTS max)" else "Add")
        Group {
            more.forEachIndexed { i, s -> if (i > 0) RowDivider()
                Row1(s.label, null, icon = s.icon, onClick = { if (ids.size < MAX_SHORTCUTS) save(ids + s.id) else app.toast("Remove one first — $MAX_SHORTCUTS fit") }) {
                    Icon(Icons.Rounded.AddCircle, "Add", tint = if (ids.size < MAX_SHORTCUTS) N.green else N.divider) }
            }
        }
        LinksCard(listOf("Reset to the default shortcuts" to { save(DEFAULT_SHORTCUTS) }))
    }
}

private val ROW_H = 60.dp

/**
 * A column of equal-height rows you can reorder by holding one and dragging it. The dragged row
 * lifts (shadow + scale) and follows the finger; the others slide out of its way.
 */
@Composable fun <T> ReorderList(items: List<T>, onMove: (List<T>) -> Unit, row: @Composable (T, Boolean) -> Unit) {
    val rowPx = with(LocalDensity.current) { ROW_H.toPx() }
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<T?>(null) }
    var dy by remember { mutableFloatStateOf(0f) }
    val latest by rememberUpdatedState(items)
    val from = dragging?.let { items.indexOf(it) } ?: -1
    val target = if (from < 0) -1 else (from + Math.round(dy / rowPx)).coerceIn(0, items.lastIndex)
    Column {
        items.forEachIndexed { i, item ->
            if (i > 0) RowDivider()
            val me = item == dragging
            // rows between the dragged row's start and its target shift one slot to make room
            val shift = when {
                from < 0 || me -> 0
                i in (from + 1)..target -> -1
                i in target until from -> 1
                else -> 0
            }
            val off by animateDpAsState(ROW_H * shift, label = "reorder")
            Box(Modifier.zIndex(if (me) 1f else 0f)
                .graphicsLayer {
                    translationY = if (me) dy else off.toPx()
                    val s = if (me) 1.03f else 1f; scaleX = s; scaleY = s
                }
                .then(if (me) Modifier.shadow(12.dp, RoundedCornerShape(18.dp)).background(N.card, RoundedCornerShape(18.dp)) else Modifier)
                .pointerInput(item) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { dragging = item; dy = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                        onDrag = { ch, d -> ch.consume(); dy += d.y },
                        onDragEnd = {
                            val l = latest; val a = l.indexOf(item)
                            val b = (a + Math.round(dy / rowPx)).coerceIn(0, l.lastIndex)
                            if (a >= 0 && a != b) { onMove(l.toMutableList().apply { add(b, removeAt(a)) }); haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                            dragging = null; dy = 0f
                        },
                        onDragCancel = { dragging = null; dy = 0f })
                }) { row(item, me) }
        }
    }
}
