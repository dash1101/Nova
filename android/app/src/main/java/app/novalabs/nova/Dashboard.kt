package app.novalabs.nova

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/** Dashboard tiles: id, name, icon, how many grid columns it spans. */
data class DashTile(val id: String, val label: String, val icon: ImageVector, val span: Int)

val DASH_TILES = listOf(
    DashTile("clock", "Clock", Icons.Rounded.Schedule, 1),
    DashTile("health", "Health", Icons.Rounded.CheckCircle, 1),
    DashTile("cpu", "CPU graph", Icons.Rounded.Memory, 1),
    DashTile("mem", "Memory graph", Icons.Rounded.DeveloperBoard, 1),
    DashTile("temp", "Temperature graph", Icons.Rounded.Thermostat, 1),
    DashTile("net", "Network graph", Icons.Rounded.SwapVert, 1),
    DashTile("storage", "Storage", Icons.Rounded.Storage, 2),
    DashTile("containers", "Containers", Icons.Rounded.ViewInAr, 1),
    DashTile("backup", "Backups", Icons.Rounded.Backup, 1),
    DashTile("fan", "Fan light", Icons.Rounded.Lightbulb, 2),
    DashTile("alerts", "Recent alerts", Icons.Rounded.Notifications, 2),
    DashTile("uptime", "Uptime", Icons.Rounded.Timer, 1),
)
val DEFAULT_DASH = listOf("clock", "health", "cpu", "mem", "temp", "net", "storage", "containers", "backup", "alerts")

/** Per-server dashboard settings (tiles in order, night dimming). Stored on this device. */
class DashPrefs(ctx: android.content.Context, profile: String) {
    private val p = ctx.getSharedPreferences(if (profile.isEmpty()) "nova_dash" else "nova_dash_$profile", android.content.Context.MODE_PRIVATE)
    var tiles: List<String>
        get() = p.getString("tiles", null)?.split(",")?.filter { id -> DASH_TILES.any { it.id == id } } ?: DEFAULT_DASH
        set(v) { p.edit().putString("tiles", v.joinToString(",")).apply() }
    var dimNight: Boolean
        get() = p.getBoolean("dim", true)
        set(v) { p.edit().putBoolean("dim", v).apply() }
    var dimFrom: Int get() = p.getInt("dim_from", 23); set(v) { p.edit().putInt("dim_from", v).apply() }
    var dimTo: Int get() = p.getInt("dim_to", 7); set(v) { p.edit().putInt("dim_to", v).apply() }
}

@Composable fun DashboardScreen(app: AppState) {
    val prefs = remember { DashPrefs(app.activity, app.pairing.profile) }
    var tiles by remember { mutableStateOf(prefs.tiles) }
    var editing by remember { mutableStateOf(false) }
    var chrome by remember { mutableStateOf(true) }            // top bar visible (tap to toggle)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(chrome) { if (chrome && !editing) { delay(6000); chrome = false } }

    // Always-on: keep the screen awake and go full-screen while the dashboard is showing.
    DisposableEffect(Unit) {
        val w = app.activity.window
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val c = WindowCompat.getInsetsController(w, w.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); c.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // Burn-in protection: drift the whole layout by a few pixels every minute.
    val minute = (now / 60_000L).toInt()
    val shiftX = ((minute * 7) % 9 - 4).dp; val shiftY = ((minute * 5) % 7 - 3).dp
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val night = prefs.dimNight && (if (prefs.dimFrom > prefs.dimTo) hour >= prefs.dimFrom || hour < prefs.dimTo else hour in prefs.dimFrom until prefs.dimTo)

    if (editing) { EditDashboard(app, prefs, tiles, { tiles = it; prefs.tiles = it }) { editing = false }; return }

    val stats = liveStats(app)
    val events by live(app, "/api/v1/events?since=0", 30_000)
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null) { chrome = !chrome }) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp).offset(shiftX, shiftY)) {
            val cols = (maxWidth / 260.dp).toInt().coerceIn(2, 6)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(if (chrome) 56.dp else 4.dp))
                // Pack tiles into rows of `cols` columns, honoring each tile's span.
                val rows = mutableListOf<MutableList<DashTile>>(); var used = cols
                tiles.mapNotNull { id -> DASH_TILES.firstOrNull { it.id == id } }.forEach { t ->
                    val span = t.span.coerceAtMost(cols)
                    if (used + span > cols) { rows.add(mutableListOf()); used = 0 }
                    rows.last().add(t); used += span
                }
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { t -> Box(Modifier.weight(t.span.coerceAtMost(cols).toFloat()).fillMaxHeight()) { DashTileView(app, t, stats, events, now) } }
                        val left = cols - row.sumOf { it.span.coerceAtMost(cols) }
                        if (left > 0) Spacer(Modifier.weight(left.toFloat()))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        if (chrome) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp))
            .background(N.card.copy(alpha = 0.92f)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ app.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Leave dashboard", tint = N.text) }
            Text(serverName(app), color = N.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!app.isAdmin) Text("View only  ", color = N.sub, fontSize = 13.sp)
            IconButton({ editing = true }) { Icon(Icons.Rounded.Edit, "Customize", tint = N.text) }
        }
        if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))     // night dimming
    }
}

@Composable private fun Tile(title: String, icon: ImageVector, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().glassCard(RoundedCornerShape(28.dp)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
            Text(title, color = N.sub, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable private fun DashTileView(app: AppState, t: DashTile, stats: LiveStats, events: JSONObject?, now: Long) {
    val o = app.overview; val st = o?.optJSONObject("status"); val m = st?.optJSONObject("metrics")
    val n = stats.now; val live = stats.recent.size >= 2
    val pts = if (live) stats.recent else stats.history
    fun series(k: String) = pts.map { it.optDouble(k, 0.0).toFloat().let { v -> if (v.isNaN()) 0f else v } }
    val tick = if (live) stats.tick else 0; val window = if (live) 120 else 0
    when (t.id) {
        "clock" -> BoxWithConstraints(Modifier.fillMaxSize().glassCard(RoundedCornerShape(28.dp)).padding(18.dp)) {
          val big = (maxWidth.value / 3.1f).coerceIn(28f, 72f).sp          // fits the tile, phone or tablet
          Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)), color = N.text, fontSize = big, fontWeight = FontWeight.Light,
                maxLines = 1, softWrap = false)
            Text(SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(Date(now)), color = N.sub, fontSize = 16.sp, maxLines = 1)
          }
        }
        "health" -> { val lvl = st?.optString("level") ?: "ok"
            Tile("Health", if (lvl == "ok") Icons.Rounded.CheckCircle else Icons.Rounded.Error, levelColor(lvl, N)) {
                Text(if (lvl == "ok") "All good" else st?.optString("headline") ?: "", color = N.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(o?.optJSONObject("containers")?.let { "${it.optInt("running")}/${it.optInt("total")} containers running" } ?: "", color = N.sub, fontSize = 14.sp)
                if (app.error != null) Text("Offline — showing the last data", color = N.amber, fontSize = 13.sp)
            } }
        "cpu" -> GraphTile("CPU", Icons.Rounded.Memory, N.blue, n?.let { "%.0f%%".format(it.optDouble("cpu")) }, n?.let { "load ${it.optDouble("load")}" }, series("cpu"), 100f, tick, window, stats.added)
        "mem" -> GraphTile("Memory", Icons.Rounded.DeveloperBoard, Color(0xFFBF5AF2), n?.let { "%.0f%%".format(it.optDouble("mem")) },
            n?.let { "${it.optDouble("mem_used_gb")} / ${it.optDouble("mem_total_gb")} GB" }, series("mem"), 100f, tick, window, stats.added)
        "temp" -> GraphTile("CPU temperature", Icons.Rounded.Thermostat, N.amber, n?.optDouble("temp")?.takeIf { !it.isNaN() }?.let { "%.0f°C".format(it) } ?: m?.optString("cpu_temp"),
            n?.optDouble("nvme_temp")?.takeIf { !it.isNaN() }?.let { "NVMe %.0f°C".format(it) }, series("temp"), null, tick, window, stats.added)
        "net" -> GraphTile("Network", Icons.Rounded.SwapVert, N.green, n?.let { "↓ ${rate(it.optDouble("rx"))}" }, n?.let { "↑ ${rate(it.optDouble("tx"))}" },
            series("rx").zip(series("tx")) { a, b -> a + b }, null, tick, window, stats.added)
        "storage" -> Tile("Storage", Icons.Rounded.Storage, N.green) {
            storageList(m).take(5).forEach { su ->
                    val p = su.pct
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Text(su.name, color = N.text, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(84.dp))
                        Box(Modifier.weight(1f)) { UsageBar(p / 100f, if (p > 95) N.red else if (p > 85) N.amber else N.blue) }
                        Text("  " + su.free, color = N.sub, fontSize = 12.sp, maxLines = 1)
                    }
                }
        }
        "containers" -> { val cs = o?.optJSONObject("containers")
            Tile("Containers", Icons.Rounded.ViewInAr, N.amber) {
                Text(cs?.let { "${it.optInt("running")}/${it.optInt("total")}" } ?: "—", color = N.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("running", color = N.sub, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(m?.optString("websites")?.takeIf { it.isNotEmpty() }?.let { "Websites $it" } ?: "", color = N.sub, fontSize = 13.sp)
            } }
        "backup" -> { val running = m?.optString("data_backup")?.contains("running") == true
            val b = if (running) live(app, "/api/v1/backup", 10_000).value else null
            Tile("Backups", Icons.Rounded.Backup, N.blue) {
                Text(if (running) "Running" else m?.optString("data_backup") ?: "—", color = N.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(if (running) b?.let { backupLine(it) } ?: "" else m?.optString("backup_sets") ?: "", color = N.sub, fontSize = 13.sp, maxLines = 2)
                Spacer(Modifier.weight(1f))
                if (running) ProgressBar(b?.optJSONObject("progress")?.takeIf { it.optString("phase") == "copying" }?.optInt("pct")?.div(100f))
            } }
        "fan" -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            val f = app.fan
            SliderTile(Icons.Rounded.Lightbulb, "Fan light", f?.optBoolean("on") != false, f?.optInt("brightness") ?: 50, app.isAdmin,
                Modifier.fillMaxWidth().fillMaxHeight(), onToggle = { app.changeFan(JSONObject().put("on", f?.optBoolean("on") == false)) },
                onSet = { v -> app.changeFan(JSONObject().put("on", true).put("brightness", v)) }, onLongClick = { app.go(Route.Lighting) })
        }
        "alerts" -> Tile("Recent alerts", Icons.Rounded.Notifications, N.red) {
            val ev = events?.optJSONArray("events")?.let { a -> (0 until minOf(4, a.length())).map { a.getJSONObject(it) } } ?: emptyList()
            if (ev.isEmpty()) Text("Nothing lately", color = N.sub)
            ev.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(levelColor(e.optString("level"), N))); Spacer(Modifier.width(8.dp))
                    Text(e.optString("title").replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), ""), color = N.text, fontSize = 13.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date((e.optDouble("t") * 1000).toLong())), color = N.sub, fontSize = 12.sp)
                }
            }
        }
        "uptime" -> Tile("Uptime", Icons.Rounded.Timer, N.green) {
            val up = n?.optLong("uptime_s") ?: 0
            Text(if (up > 0) "${up / 86400}d ${up % 86400 / 3600}h" else m?.optString("uptime") ?: "—", color = N.text, fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text(o?.optJSONObject("server")?.optString("kernel") ?: "", color = N.sub, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable private fun GraphTile(title: String, icon: ImageVector, color: Color, value: String?, sub: String?, values: List<Float>, max: Float?,
                                   tick: Int = 0, window: Int = 0, added: Int = 1) =
    Tile(title, icon, color) {
        Text(value ?: "—", color = N.text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(sub ?: "", color = N.sub, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Sparkline(values, color, Modifier.fillMaxWidth().weight(1f), max, tick, window, added)
    }

@Composable private fun EditDashboard(app: AppState, prefs: DashPrefs, tiles: List<String>, save: (List<String>) -> Unit, done: () -> Unit) {
    var dim by remember { mutableStateOf(prefs.dimNight) }
    var from by remember { mutableIntStateOf(prefs.dimFrom) }; var to by remember { mutableIntStateOf(prefs.dimTo) }
    Page("Customize dashboard", done) {
        SectionLabel("On the dashboard · in this order")
        Group {
            tiles.forEachIndexed { i, id -> val d = DASH_TILES.first { it.id == id }
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(d.icon, null, tint = N.blue, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(d.label, color = N.text, fontSize = 17.sp); Text(if (d.span > 1) "Wide" else "Square", color = N.sub, fontSize = 12.sp) }
                    IconButton({ if (i > 0) save(tiles.toMutableList().apply { add(i - 1, removeAt(i)) }) }, enabled = i > 0) {
                        Icon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = if (i > 0) N.text else N.divider) }
                    IconButton({ if (i < tiles.lastIndex) save(tiles.toMutableList().apply { add(i + 1, removeAt(i)) }) }, enabled = i < tiles.lastIndex) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Move down", tint = if (i < tiles.lastIndex) N.text else N.divider) }
                    IconButton({ save(tiles - id) }) { Icon(Icons.Rounded.RemoveCircle, "Remove", tint = N.red) }
                }
            }
        }
        val more = DASH_TILES.filter { it.id !in tiles }
        if (more.isNotEmpty()) { SectionLabel("Add"); Group { more.forEachIndexed { i, d -> if (i > 0) RowDivider()
            Row1(d.label, if (d.span > 1) "Wide tile" else "Square tile", icon = d.icon, onClick = { save(tiles + d.id) }) { Icon(Icons.Rounded.AddCircle, "Add", tint = N.green) } } } }
        SectionLabel("Always-on")
        Group {
            SwitchRow("Dim at night", if (dim) "%02d:00 – %02d:00".format(from, to) else "Off", dim, subtitleBlue = dim) { dim = it; prefs.dimNight = it }
            if (dim) {
                RowDivider()
                SliderRow("From", from.toFloat(), 0f..23f, "%02d:00".format(from), steps = 22, onChange = { from = it.toInt() }) { prefs.dimFrom = from }
                SliderRow("Until", to.toFloat(), 0f..23f, "%02d:00".format(to), steps = 22, onChange = { to = it.toInt() }) { prefs.dimTo = to }
            }
        }
        Text("The screen stays on and the layout shifts slightly every minute to protect the display. For a wall tablet, pair it as View only and use Android's screen pinning to keep it in Nova.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
        LinksCard(listOf("Reset to the default tiles" to { save(DEFAULT_DASH) }))
    }
}
