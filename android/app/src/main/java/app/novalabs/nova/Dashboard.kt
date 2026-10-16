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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
    DashTile("top", "Top containers", Icons.Rounded.Leaderboard, 1), DashTile("server", "Server info", Icons.Rounded.Dns, 1),
    DashTile("gpu", "Graphics graph", Icons.Rounded.Speed, 1), DashTile("swap", "Swap graph", Icons.Rounded.SwapHoriz, 1), DashTile("io", "Disk I/O graph", Icons.Rounded.Storage, 1),
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
    /** How dark night dimming gets, in percent (it used to be a fixed, very dark 72%). */
    var dimLevel: Int get() = p.getInt("dim_level", 40); set(v) { p.edit().putInt("dim_level", v).apply() }
    var fullscreen: Boolean get() = p.getBoolean("fullscreen", true); set(v) { p.edit().putBoolean("fullscreen", v).apply() }
    var cols: Int get() = p.getInt("cols", 0); set(v) { p.edit().putInt("cols", v).apply() }                     // 0 = as many as fit
    var scale: Int get() = p.getInt("scale", 100); set(v) { p.edit().putInt("scale", v).apply() }                // text and tile size, %
    var bg: String get() = p.getString("bg", "theme")!!; set(v) { p.edit().putString("bg", v).apply() }       // theme / black / wallpaper
    var plain: Boolean get() = p.getBoolean("plain", false); set(v) { p.edit().putBoolean("plain", v).apply() }
    var clock: String get() = p.getString("clock", "auto")!!; set(v) { p.edit().putString("clock", v).apply() } // auto / 24 / 12
    var seconds: Boolean get() = p.getBoolean("seconds", false); set(v) { p.edit().putBoolean("seconds", v).apply() }
    /** Tiles made wide or square by hand (id → columns). */
    var sizes: Map<String, Int>
        get() = p.getString("sizes", "")!!.split(",").mapNotNull { it.split(":").takeIf { x -> x.size == 2 }?.let { (k, v) -> v.toIntOrNull()?.let { k to it } } }.toMap()
        set(v) { p.edit().putString("sizes", v.entries.joinToString(",") { "${it.key}:${it.value}" }).apply() }
    fun span(t: DashTile) = sizes[t.id] ?: t.span
}

/** The dashboard's look settings, for the tiles to read. */
private val LocalDash = staticCompositionLocalOf<DashPrefs?> { null }

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
        if (prefs.fullscreen) c.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); c.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // Burn-in protection: drift the whole layout by a few pixels every minute.
    val minute = (now / 60_000L).toInt()
    val shiftX = ((minute * 7) % 9 - 4).dp; val shiftY = ((minute * 5) % 7 - 3).dp
    val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    val night = prefs.dimNight && (if (prefs.dimFrom > prefs.dimTo) hour >= prefs.dimFrom || hour < prefs.dimTo else hour in prefs.dimFrom until prefs.dimTo)

    if (editing) { EditDashboard(app, prefs, tiles, { tiles = it; prefs.tiles = it }) { editing = false
        val w = app.activity.window; val c = WindowCompat.getInsetsController(w, w.decorView)            // the full-screen setting may have changed
        if (prefs.fullscreen) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars()) }; return }
    val k = prefs.scale / 100f

    val stats = liveStats(app)
    val events by live(app, "/api/v1/events?since=0", 30_000)
    val density = androidx.compose.ui.platform.LocalDensity.current
    CompositionLocalProvider(LocalDash provides prefs, androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, density.fontScale * k)) {
    Box(Modifier.fillMaxSize().then(if (prefs.bg == "black") Modifier.background(Color.Black) else Modifier).clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
        indication = null) { chrome = !chrome }) {
        if (prefs.bg == "wallpaper" && AppPrefs.wallV > 0L) WallpaperLayer()
        BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp).offset(shiftX, shiftY)) {
            val fit = (maxWidth / (260.dp * k)).toInt().coerceIn(1, 6)
            val cols = if (prefs.cols > 0) minOf(prefs.cols, maxOf(fit, 1)) else fit.coerceAtLeast(2)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Spacer(Modifier.height(if (chrome) 56.dp else 4.dp))
                // Pack tiles into rows of `cols` columns, honoring each tile's span (sizeV: repack after a tile is resized).
                var sizeV by remember { mutableIntStateOf(0) }
                val rows = mutableListOf<MutableList<DashTile>>(); var used = cols + sizeV * 0
                tiles.mapNotNull { id -> DASH_TILES.firstOrNull { it.id == id } }.forEach { t ->
                    val span = prefs.span(t).coerceAtMost(cols)
                    if (used + span > cols) { rows.add(mutableListOf()); used = 0 }
                    rows.last().add(t); used += span
                }
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth().height(190.dp * k), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // hold a tile to change its size, like a home-screen widget: small → wide → the whole row → small
                        row.forEach { t -> Box(Modifier.weight(prefs.span(t).coerceAtMost(cols).toFloat()).fillMaxHeight()
                            .pointerInput(t.id, cols) { detectTapGestures(onLongPress = {
                                val cur = prefs.span(t).coerceAtMost(cols); val next = when { cur < 2 && cols >= 2 -> 2; cur < cols -> cols; else -> 1 }
                                prefs.sizes = prefs.sizes + (t.id to next); Sounds.feel("toggleOn"); sizeV++
                                app.toast(when (next) { 1 -> "${t.label}: small"; cols -> "${t.label}: the whole row"; else -> "${t.label}: wide" }) }) }) {
                            key(sizeV) { DashTileView(app, t, stats, events, now) } } }
                        val left = cols - row.sumOf { prefs.span(it).coerceAtMost(cols) }
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
        if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = prefs.dimLevel / 100f)))     // night dimming
    }
    }
}

/** A tile's surface: glass, or with Plain tiles just the numbers on the background. */
@Composable private fun Modifier.dashCard(): Modifier = if (LocalDash.current?.plain == true) this else this.glassCard(RoundedCornerShape(28.dp))

@Composable private fun Tile(title: String, icon: ImageVector, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().dashCard().padding(18.dp)) {
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
        "clock" -> BoxWithConstraints(Modifier.fillMaxSize().dashCard().padding(18.dp)) {
          val dp = LocalDash.current
          val h24 = when (dp?.clock) { "24" -> true; "12" -> false; else -> android.text.format.DateFormat.is24HourFormat(app.activity) }
          val pattern = (if (h24) "HH:mm" else "h:mm") + (if (dp?.seconds == true) ":ss" else "")
          val big = (maxWidth.value / (if (dp?.seconds == true) 4.4f else 3.1f)).coerceIn(28f, 72f).sp          // fits the tile, phone or tablet
          Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Text(SimpleDateFormat(pattern, Locale.getDefault()).format(Date(now)), color = N.text, fontSize = big, fontWeight = FontWeight.Light,
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
        "gpu" -> GraphTile("Graphics", Icons.Rounded.Speed, N.red, n?.takeIf { it.has("gpu") && !it.isNull("gpu") }?.let { "%.0f%%".format(it.optDouble("gpu")) } ?: "—",
            if (n?.isNull("gpu") != false) "No graphics reading on this server" else "GPU busy", series("gpu"), 100f, tick, window, stats.added)
        "swap" -> { val tot = n?.optDouble("swap_total_gb", -1.0) ?: -1.0
            GraphTile("Swap", Icons.Rounded.SwapHoriz, N.amber, if (tot == 0.0) "None" else n?.let { "%.0f%%".format(it.optDouble("swap", 0.0)) },
                if (tot == 0.0) "no swap set up" else if (tot > 0) "${n!!.optDouble("swap_used_gb")} of $tot GB" else "of swap in use", series("swap"), 100f, tick, window, stats.added) }
        "io" -> GraphTile("Disk I/O", Icons.Rounded.Storage, Color(0xFF64D2FF), n?.let { rate(it.optDouble("io", 0.0)) }, "read + write, all drives", series("io"), null, tick, window, stats.added)
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
        "top" -> { val cst = live(app, "/api/v1/containers/stats", 10_000).value?.optJSONObject("stats")
            Tile("Top containers", Icons.Rounded.Leaderboard, N.amber) {
                val rows = cst?.keys()?.asSequence()?.map { it to cst.getJSONObject(it) }?.sortedByDescending { (_, v) -> v.optDouble("cpu") * 4 + v.optDouble("mem_pct") }?.take(4)?.toList().orEmpty()
                if (cst == null) Text("—", color = N.sub) else if (rows.isEmpty()) Text("Nothing running", color = N.sub)
                rows.forEach { (name, v) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Text(name, color = N.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${"%.0f".format(v.optDouble("cpu"))}% · ${v.optString("mem")}", color = N.sub, fontSize = 12.sp, maxLines = 1)
                    }
                }
            } }
        "server" -> { val sv = o?.optJSONObject("server")
            Tile("Server", Icons.Rounded.Dns, N.blue) {
                Text(serverName(app), color = N.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sv?.optString("cpu")?.replace(Regex("\\s+"), " ") ?: "", color = N.sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Text(listOfNotNull(sv?.optInt("ram_gb")?.takeIf { it > 0 }?.let { "$it GB memory" }, sv?.optString("kernel")?.ifEmpty { null }?.let { "Linux $it" }).joinToString(" · "),
                    color = N.sub, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } }
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
    var sizes by remember { mutableStateOf(prefs.sizes) }
    var level by remember { mutableIntStateOf(prefs.dimLevel) }; var scale by remember { mutableIntStateOf(prefs.scale) }
    var full by remember { mutableStateOf(prefs.fullscreen) }; var plain by remember { mutableStateOf(prefs.plain) }; var secs by remember { mutableStateOf(prefs.seconds) }
    var ask by remember { mutableStateOf<String?>(null) }; var tick by remember { mutableIntStateOf(0) }
    var from by remember { mutableIntStateOf(prefs.dimFrom) }; var to by remember { mutableIntStateOf(prefs.dimTo) }
    Page("Customize dashboard", done) {
        SectionLabel("On the dashboard · in this order")
        Group {
            tiles.forEachIndexed { i, id -> val d = DASH_TILES.first { it.id == id }
                if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(d.icon, null, tint = N.blue, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(14.dp))
                    val wide = (sizes[id] ?: d.span) > 1
                    Column(Modifier.weight(1f)) { Text(d.label, color = N.text, fontSize = 17.sp); Text(if (wide) "Wide" else "Square", color = N.sub, fontSize = 12.sp) }
                    IconButton({ sizes = sizes + (id to if (wide) 1 else 2); prefs.sizes = sizes }) {
                        Icon(if (wide) Icons.Rounded.CropSquare else Icons.Rounded.Crop169, if (wide) "Make it square" else "Make it wide", tint = N.text) }
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
        SectionLabel("Layout")
        Group {
            Row1("Columns", if (prefs.cols > 0) "${prefs.cols} (fewer on a narrow screen)" else "Automatic — as many as fit", true, Icons.Rounded.ViewColumn, onClick = { ask = "cols" })
            RowDivider()
            SliderRow("Text and tile size", scale.toFloat(), 80f..160f, "$scale%", steps = 15, onChange = { scale = it.toInt() }) { prefs.scale = scale }
            RowDivider()
            Row1("Background", mapOf("theme" to "Same as Nova", "black" to "Black — best for OLED screens and dark rooms", "wallpaper" to "Your wallpaper")[prefs.bg], true, Icons.Rounded.Palette, onClick = { ask = "bg" })
            RowDivider()
            SwitchRow("Plain tiles", "No glass or borders — just the numbers", plain) { plain = it; prefs.plain = it }
            RowDivider()
            SwitchRow("Full screen", "Hides the status and navigation bars (swipe from an edge to see them)", full) { full = it; prefs.fullscreen = it }
        }
        SectionLabel("Clock")
        Group {
            Row1("Time format", mapOf("auto" to "Same as this ${DeviceForm.noun}", "24" to "24-hour", "12" to "12-hour")[prefs.clock], true, Icons.Rounded.Schedule, onClick = { ask = "clock" })
            RowDivider()
            SwitchRow("Show seconds", null, secs) { secs = it; prefs.seconds = it }
        }
        SectionLabel("Always-on")
        Group {
            SwitchRow("Dim at night", if (dim) "%02d:00 – %02d:00".format(from, to) else "Off", dim, subtitleBlue = dim) { dim = it; prefs.dimNight = it }
            if (dim) {
                RowDivider()
                SliderRow("How dark", level.toFloat(), 10f..90f, "$level%", steps = 15, onChange = { level = it.toInt() }) { prefs.dimLevel = level }
                SliderRow("From", from.toFloat(), 0f..23f, "%02d:00".format(from), steps = 22, onChange = { from = it.toInt() }) { prefs.dimFrom = from }
                SliderRow("Until", to.toFloat(), 0f..23f, "%02d:00".format(to), steps = 22, onChange = { to = it.toInt() }) { prefs.dimTo = to }
            }
        }
        key(tick) { }
        when (ask) {
            "cols" -> OneDialog({ ask = null }, "Columns", null, listOf(DialogButton("Cancel") { ask = null })) {
                (0..6).forEach { c -> DialogChoice(if (c == 0) "Automatic" else "$c column${if (c > 1) "s" else ""}", null, prefs.cols == c) { prefs.cols = c; ask = null; tick++ } } }
            "bg" -> OneDialog({ ask = null }, "Background", null, listOf(DialogButton("Cancel") { ask = null })) {
                listOf("theme" to ("Same as Nova" to null), "black" to ("Black" to "Best for OLED screens and dark rooms"), "wallpaper" to ("Your wallpaper" to "Set one in Appearance"))
                    .forEach { (v, l) -> DialogChoice(l.first, l.second, prefs.bg == v) { prefs.bg = v; ask = null; tick++ } } }
            "clock" -> OneDialog({ ask = null }, "Time format", null, listOf(DialogButton("Cancel") { ask = null })) {
                listOf("auto" to "Same as this ${DeviceForm.noun}", "24" to "24-hour", "12" to "12-hour").forEach { (v, l) -> DialogChoice(l, null, prefs.clock == v) { prefs.clock = v; ask = null; tick++ } } }
        }
        Note("The screen stays on and the layout shifts slightly every minute to protect the display. For a wall tablet, pair it as View only and use Android's screen pinning to keep it in Nova.", 13.sp, 8.dp)
        LinksCard(listOf("Reset to the default tiles" to { save(DEFAULT_DASH); sizes = emptyMap(); prefs.sizes = sizes }))
    }
}
