package app.novalabs.nova

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Live server numbers for graphs: the last hour at 15 s, and — while a graph is on screen — a
 * 1-second feed of the last few minutes (only the new points are fetched each second).
 */
class LiveStats { var now by mutableStateOf<JSONObject?>(null); var history by mutableStateOf<List<JSONObject>>(emptyList())
    var recent by mutableStateOf<List<JSONObject>>(emptyList()); var tick by mutableIntStateOf(0) }

@Composable fun liveStats(app: AppState, everyMs: Long = 1_000): LiveStats {
    val ls = remember { LiveStats().also { s -> Cache["/api/v1/stats"]?.let { s.load(it) } } }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(everyMs) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            runCatching { app.api.get("/api/v1/stats") }.onSuccess { ls.load(it) }
            while (true) {
                kotlinx.coroutines.delay(everyMs)
                val since = ls.recent.lastOrNull()?.optDouble("t") ?: 0.0
                runCatching { app.api.get("/api/v1/stats?since=$since") }.onSuccess { r ->
                    r.optJSONObject("now")?.takeIf { it.has("cpu") }?.let { ls.now = it }
                    val add = r.optJSONArray("recent").toObjects()
                    if (add.isNotEmpty()) { ls.recent = (ls.recent + add).takeLast(RECENT_POINTS); ls.tick++ }
                    // an older server has no 1-second feed: keep refreshing the hour instead
                    if (!r.has("recent")) runCatching { app.api.get("/api/v1/stats") }.onSuccess { ls.load(it) }
                }
            }
        }
    }
    return ls
}
private const val RECENT_POINTS = 180
private fun JSONArray?.toObjects() = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
private fun LiveStats.load(j: JSONObject) {
    j.optJSONObject("now")?.takeIf { it.has("cpu") }?.let { now = it }
    history = j.optJSONArray("history").toObjects()
    j.optJSONArray("recent")?.let { recent = it.toObjects() }
    tick++
}

/**
 * Smooth line chart with a soft fill, auto-scaled (or fixed to [max]). The line is a monotone cubic
 * through the samples (no overshoot above 100% or below 0), and when a new sample arrives ([tick])
 * the whole line glides left by one step instead of jumping. [window] = how many steps are visible.
 */
@Composable fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier, max: Float? = null,
                          tick: Int = 0, window: Int = 0) {
    val glide = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(tick) {
        if (reduceMotion()) glide.snapTo(1f)
        else { glide.snapTo(0f); glide.animateTo(1f, androidx.compose.animation.core.tween(950, easing = androidx.compose.animation.core.LinearEasing)) }
    }
    val peak = if (max != null) max else ((values.maxOrNull() ?: 1f) * 1.15f).coerceAtLeast(1f)
    val top by androidx.compose.animation.core.animateFloatAsState(peak, label = "scale")
    Canvas(modifier.clipToBounds()) {
        if (values.size < 2) return@Canvas
        val steps = if (window > 0) window else values.size - 1
        val dx = size.width / steps
        // the newest point slides in from one step to the right
        val shift = if (window > 0) (1f - glide.value) * dx else 0f
        fun x(i: Int) = size.width - (values.lastIndex - i) * dx + shift
        fun y(v: Float) = size.height - (v / top).coerceIn(0f, 1f) * size.height * 0.94f - size.height * 0.03f
        val first = if (window > 0) (values.lastIndex - window - 1).coerceAtLeast(0) else 0
        val xs = (first..values.lastIndex).map { x(it) }; val ys = (first..values.lastIndex).map { y(values[it]) }
        val line = smoothPath(xs, ys)
        val fill = Path().apply { addPath(line); lineTo(xs.last(), size.height); lineTo(xs.first(), size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.0f))))
        drawPath(line, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(color.copy(alpha = 0.25f), 7.dp.toPx(), Offset(xs.last(), ys.last()))
        drawCircle(color, 3.5.dp.toPx(), Offset(xs.last(), ys.last()))
    }
}

/** Monotone cubic (Fritsch–Carlson) through the points: smooth, and never bulges past the data. */
private fun smoothPath(xs: List<Float>, ys: List<Float>): Path {
    val n = xs.size; val p = Path(); p.moveTo(xs[0], ys[0])
    if (n < 3) { for (i in 1 until n) p.lineTo(xs[i], ys[i]); return p }
    val d = FloatArray(n - 1) { (ys[it + 1] - ys[it]) / (xs[it + 1] - xs[it]).coerceAtLeast(0.001f) }
    val m = FloatArray(n) { i -> when (i) { 0 -> d[0]; n - 1 -> d[n - 2]; else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f } }
    for (i in 0 until n - 1) {
        if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f; continue }
        val a = m[i] / d[i]; val b = m[i + 1] / d[i]; val h = a * a + b * b
        if (h > 9f) { val t = 3f / kotlin.math.sqrt(h); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i] }
    }
    for (i in 0 until n - 1) {
        val h = (xs[i + 1] - xs[i]) / 3f
        p.cubicTo(xs[i] + h, ys[i] + m[i] * h, xs[i + 1] - h, ys[i + 1] - m[i + 1] * h, xs[i + 1], ys[i + 1])
    }
    return p
}

@Composable private fun MetricCard(title: String, value: String, sub: String, color: Color, values: List<Float>, max: Float?, modifier: Modifier,
                                   tick: Int = 0, window: Int = 0) {
    Column(modifier.glassCard(RoundedCornerShape(24.dp)).padding(16.dp)) {
        Text(title, color = N.sub, fontSize = 13.sp)
        Text(value, color = N.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = N.sub, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Sparkline(values, color, Modifier.fillMaxWidth().height(56.dp), max, tick, window)
    }
}

private fun pct(s: String?) = s?.let { Regex("(\\d+)%").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
fun rate(b: Double): String = when { b >= 1e6 -> "%.1f MB/s".format(b / 1e6); b >= 1e3 -> "%.0f kB/s".format(b / 1e3); else -> "%.0f B/s".format(b) }
private fun uptime(s: Long) = "${s / 86400}d ${s % 86400 / 3600}h ${s % 3600 / 60}m"

@Composable fun StatusScreen(app: AppState) {
    val ls = liveStats(app)
    var hourView by rememberSaveable { mutableStateOf(false) }
    val o = app.overview
    val st = o?.optJSONObject("status")
    val m = st?.optJSONObject("metrics")
    val level = st?.optString("level") ?: "ok"
    val now = ls.now
    val live = !hourView && ls.recent.size >= 2
    val pts = if (live) ls.recent else ls.history
    fun series(k: String) = pts.map { it.optDouble(k, 0.0).toFloat().let { v -> if (v.isNaN()) 0f else v } }
    val tick = if (live) ls.tick else 0
    val window = if (live) 120 else 0
    val backupRunning = m?.optString("data_backup")?.contains("running") == true
    val backup = if (backupRunning) live(app, "/api/v1/backup", 5_000).value else null

    Page("Server status", app::back) {
        // Health header
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(levelColor(level, N).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(if (level == "ok") Icons.Rounded.CheckCircle else Icons.Rounded.Error, null, tint = levelColor(level, N), modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(if (level == "ok") "All systems normal" else st?.optString("headline") ?: "", color = N.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Updated ${st?.optString("updated_local")?.let { Regex("\\d{1,2}:\\d{2}").find(it)?.value } ?: "—"} · up ${now?.optLong("uptime_s")?.let { uptime(it) } ?: m?.optString("uptime") ?: "—"}",
                color = N.sub, fontSize = 14.sp)
        }
        // Active alerts
        val active = st?.optJSONArray("active")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }?.filter { it.optString("level") != "ok" } ?: emptyList()
        if (active.isNotEmpty()) {
            SectionLabel("Needs attention")
            Group { active.forEachIndexed { i, a -> if (i > 0) RowDivider()
                Row1(a.optString("title").replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), ""), listOf(a.optString("detail"), a.optString("since").takeIf { it.isNotEmpty() }?.let { "since $it" })
                    .filterNotNull().filter { it.isNotEmpty() }.joinToString(" · "), false, Icons.Rounded.Warning, levelColor(a.optString("level"), N)) } }
        }
        // Live graphs
        SectionLabel(if (hourView) "Last hour" else "Live · last 2 minutes")
        Segmented(listOf("Live", "Last hour"), if (hourView) 1 else 0) { hourView = it == 1 }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                MetricCard("CPU", now?.let { "%.0f%%".format(it.optDouble("cpu")) } ?: "—", now?.let { "load ${it.optDouble("load")} · ${it.optInt("cores")} cores" } ?: "",
                    N.blue, series("cpu"), 100f, Modifier.weight(1f), tick, window)
                MetricCard("Memory", now?.let { "%.0f%%".format(it.optDouble("mem")) } ?: "—", now?.let { "${it.optDouble("mem_used_gb")} of ${it.optDouble("mem_total_gb")} GB" } ?: "",
                    Color(0xFFBF5AF2), series("mem"), 100f, Modifier.weight(1f), tick, window)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                MetricCard("CPU temperature", now?.optDouble("temp")?.takeIf { !it.isNaN() }?.let { "%.0f°C".format(it) } ?: m?.optString("cpu_temp") ?: "—",
                    now?.optDouble("nvme_temp")?.takeIf { !it.isNaN() }?.let { "NVMe %.0f°C".format(it) } ?: "", N.amber, series("temp"), null, Modifier.weight(1f), tick, window)
                MetricCard("Network", now?.let { "↓ ${rate(it.optDouble("rx"))}" } ?: "—", now?.let { "↑ ${rate(it.optDouble("tx"))}" } ?: "",
                    N.green, series("rx").zip(series("tx")) { a, b -> a + b }, null, Modifier.weight(1f), tick, window)
            }
            if (pts.size < 3 || (!hourView && !live)) Text(if (hourView) "Graphs fill in over the next few minutes." else "Connecting to the live feed…", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        // Storage
        SectionLabel("Storage")
        Group {
            listOf("Photos" to "photo_pool_used", "System drive" to "root_used", "Cold storage" to "cold_storage_used", "Backup drive" to "backup_drive_used")
                .filter { m?.has(it.second) == true }.forEachIndexed { i, (label, k) ->
                    if (i > 0) RowDivider()
                    val v = m!!.optString(k); val p = pct(v) ?: 0f
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                        Row { Text(label, color = N.text, fontSize = 16.sp, modifier = Modifier.weight(1f)); Text(v, color = N.sub, fontSize = 14.sp) }
                        Spacer(Modifier.height(8.dp))
                        UsageBar(p / 100f, if (p > 95) N.red else if (p > 85) N.amber else N.blue)
                    }
                }
            RowDivider()
            Row1("Drives", "${m?.optString("drives") ?: "—"} · ${m?.optString("drive_temps") ?: ""}", true, Icons.Rounded.Storage, onClick = { app.go(Route.Hardware) })
        }
        // Backups
        SectionLabel("Backups")
        Group {
            Row1("Data backup", if (backupRunning) backup?.let { backupLine(it) } ?: "Running now" else m?.optString("data_backup"), backupRunning, Icons.Rounded.Backup)
            if (backupRunning && backup != null) BackupProgress(backup)
            RowDivider(); Row1("Backup sets", m?.optString("backup_sets"))
            RowDivider(); Row1("Photo check", m?.optString("backup_verify")?.replace("✗", "")?.trim())
            RowDivider(); Row1("Server settings backup", m?.optString("config_backup"))
            RowDivider(); Row1("Photo database backup", m?.optString("immich_db_backup"))
        }
        // Services
        SectionLabel("Services")
        Group {
            Row1("Containers", m?.optString("containers"), true, Icons.Rounded.ViewInAr, onClick = { app.go(Route.Containers) })
            RowDivider(); Row1("Websites", m?.optString("websites"), false, Icons.Rounded.Language)
            RowDivider(); Row1("Swap", m?.optString("swap") ?: now?.let { "%.0f%% used".format(it.optDouble("swap")) }, false, Icons.Rounded.SwapHoriz)
        }
    }
}
