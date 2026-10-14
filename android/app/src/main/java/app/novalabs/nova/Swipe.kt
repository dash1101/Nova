package app.novalabs.nova

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** What a swipe does: [start] = swipe right (revealed on the left), [end] = swipe left. */
class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val onAction: () -> Unit)

/**
 * One UI–style swipe row (like the Phone and Messages apps): drag sideways to reveal a colored
 * action; past 35 % of the width it buzzes, and letting go there slides the row away and runs it.
 * Short drags spring back. Vertical scrolling is untouched.
 */
@Composable fun SwipeRow(start: SwipeAction? = null, end: SwipeAction? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val x = remember { Animatable(0f) }
    var w by remember { mutableFloatStateOf(1f) }
    var armed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val act = if (x.value > 0) start else if (x.value < 0) end else null
    Box(modifier.fillMaxWidth().clipToBounds().onSizeChanged { w = it.width.toFloat().coerceAtLeast(1f) }) {
        if (act != null) {
            val frac = (kotlin.math.abs(x.value) / (w * 0.35f)).coerceIn(0f, 1f)
            Row(Modifier.matchParentSize().background(act.color.copy(alpha = 0.25f + 0.75f * frac)).padding(horizontal = 26.dp),
                horizontalArrangement = if (x.value > 0) Arrangement.Start else Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Icon(act.icon, null, tint = Color.White, modifier = Modifier.size(24.dp).graphicsLayer { val s = 0.8f + 0.3f * frac; scaleX = s; scaleY = s })
                Spacer(Modifier.width(10.dp))
                Text(act.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
        Box(Modifier.graphicsLayer { translationX = x.value }.background(if (x.value != 0f) N.card else Color.Transparent)
            .pointerInput(start != null, end != null) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { ch, dx ->
                        val nx = (x.value + dx).let { if (start == null) it.coerceAtMost(0f) else it }.let { if (end == null) it.coerceAtLeast(0f) else it }
                        if (nx == x.value) return@detectHorizontalDragGestures
                        ch.consume()
                        scope.launch { x.snapTo(nx) }
                        val past = kotlin.math.abs(nx) > w * 0.35f
                        if (past != armed) { armed = past; haptic.performHapticFeedback(if (past) HapticFeedbackType.LongPress else HapticFeedbackType.TextHandleMove) }
                    },
                    onDragEnd = {
                        val a = if (x.value > 0) start else end
                        scope.launch {
                            if (armed && a != null) {
                                x.animateTo(if (x.value > 0) w else -w, tween(180)); a.onAction(); x.snapTo(0f)
                            } else x.animateTo(0f, spring(dampingRatio = 0.7f))
                            armed = false
                        }
                    },
                    onDragCancel = { scope.launch { x.animateTo(0f, spring()) }; armed = false })
            }) { content() }
    }
}

// ── "Needs attention": the monitor's active alerts, with Ignore / Mount / Removed on purpose ───────

fun activeAlerts(app: AppState): List<JSONObject> =
    app.overview?.optJSONObject("status")?.optJSONArray("active")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        ?.filter { it.optString("level") != "ok" } ?: emptyList()

private fun cleanAlertTitle(t: String) = t.replace(Regex("^[^\\p{L}\\p{N}/~]+\\s*"), "")     // the status emoji goes; a path like "/ is 90% full" stays

/** Dismiss on the server ("ignore until it clears"; drives: "removed on purpose"), then refresh. */
fun dismissAlert(app: AppState, a: JSONObject, done: () -> Unit = {}) {
    val key = a.optString("key")
    if (key.isEmpty()) { app.toast("Update the server to dismiss alerts from here"); return }
    if (!app.isAdmin) { app.toast("This ${DeviceForm.noun} has view-only access"); return }
    // Hide it straight away; the server confirms in a moment.
    app.overview = JSONObject(app.overview.toString()).also { o ->
        val st = o.optJSONObject("status") ?: return@also
        val left = JSONArray(); val act = st.optJSONArray("active") ?: JSONArray()
        for (i in 0 until act.length()) if (act.getJSONObject(i).optString("key") != key) left.put(act.getJSONObject(i))
        st.put("active", left); if (left.length() == 0) { st.put("level", "ok"); st.put("headline", "All systems normal"); st.put("active_count", 0) }
    }
    app.act(if (key.startsWith("drive:")) "Forgotten — it won't be reported missing again" else "Ignored until it clears") {
        try { app.api.post("/api/v1/alerts/dismiss", JSONObject().put("key", key)) } finally { app.refresh(); done() }
    }
}

@Composable fun AttentionList(app: AppState) {
    val active = activeAlerts(app)
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    if (active.isEmpty()) return
    SectionLabel("Needs attention")
    Group {
        active.forEachIndexed { i, a ->
            if (i > 0) RowDivider()
            val key = a.optString("key"); val lvl = a.optString("level")
            val ign = SwipeAction(if (key.startsWith("drive:")) "Removed on purpose" else "Ignore", Icons.Rounded.NotificationsOff, N.amber) { dismissAlert(app, a) }
            key(key.ifEmpty { "$i" }) { SwipeRow(start = ign, end = ign) {
                Row1(cleanAlertTitle(a.optString("title")), listOf(a.optString("detail"), a.optString("since").takeIf { it.isNotEmpty() }?.let { "since $it" })
                    .filterNotNull().filter { it.isNotEmpty() }.joinToString(" · "), false, Icons.Rounded.Warning, levelColor(lvl, N), onClick = { menu = a })
            } }
        }
    }
    Text("Swipe either way to ignore an alert you've dealt with — it comes back if the problem returns after clearing.",
        color = N.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 2.dp))
    menu?.let { a ->
        val key = a.optString("key")
        OneDialog({ menu = null }, cleanAlertTitle(a.optString("title")), a.optString("detail").ifEmpty { null },
            buttons = listOf(DialogButton("Close") { menu = null })) {
            if (key.startsWith("mount:")) DialogChoice("Mount it now", "If the drive is plugged in, Nova mounts it from /etc/fstab", false, action = true) {
                menu = null
                app.act("Mounted") { app.api.post("/api/v1/mounts/mount", JSONObject().put("mount", key.removePrefix("mount:"))); app.refresh() }
            }
            DialogChoice(if (key.startsWith("drive:")) "Removed on purpose" else "Ignore until it clears",
                if (key.startsWith("drive:")) "Stop reporting this drive as missing" else "No more alerts for this until it's fixed and comes back", false) {
                menu = null; dismissAlert(app, a) }
            if (key.startsWith("drive:") || key.startsWith("smart:") || key.startsWith("temp:")) DialogChoice("Storage & hardware", null, false, action = true) { menu = null; app.go(Route.Hardware) }
        }
    }
}

/** Inbox → "Ignored · N": issues still going on that you said to ignore until they clear. Folded away until you open it. */
@Composable fun IgnoredList(app: AppState) {
    val ign = app.overview?.optJSONObject("status")?.optJSONArray("ignored") ?: return
    if (ign.length() == 0) return
    var open by rememberSaveable { mutableStateOf(false) }
    val turn by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Spacer(Modifier.height(10.dp))
    Group {
        Row1("Ignored · ${ign.length()}", "Still going on — quiet until ${if (ign.length() == 1) "it clears" else "they clear"}", false,
            Icons.Rounded.VisibilityOff, N.sub, onClick = { open = !open }) {
            Icon(Icons.Rounded.ExpandMore, if (open) "Fold" else "Show", tint = N.sub, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        androidx.compose.animation.AnimatedVisibility(open,
            enter = if (reduceMotion()) androidx.compose.animation.fadeIn() else androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = if (reduceMotion()) androidx.compose.animation.fadeOut() else androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) {
            Column {
                for (i in 0 until ign.length()) {
                    val a = ign.getJSONObject(i); val key = a.optString("key")
                    RowDivider()
                    Row1(cleanAlertTitle(a.optString("title")), listOf(a.optString("detail"), a.optString("since").takeIf { it.isNotEmpty() }?.let { "since $it" },
                        a.optString("ignored").takeIf { it.isNotEmpty() }?.let { "ignored $it" }).filterNotNull().filter { it.isNotEmpty() }.joinToString(" · "),
                        false, Icons.Rounded.Warning, levelColor(a.optString("level"), N)) {
                        Box(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(N.pill).bouncy(app.isAdmin, onClick = {
                            app.act("It's an active alert again") { try { app.api.post("/api/v1/alerts/undismiss", JSONObject().put("key", key)) } finally { app.refresh() } }
                        }).padding(horizontal = 12.dp, vertical = 7.dp)) { Text("Stop ignoring", color = if (app.isAdmin) N.blue else N.sub, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
