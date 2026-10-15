package app.novalabs.nova

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.drawText
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
    var recent by mutableStateOf<List<JSONObject>>(emptyList()); var tick by mutableIntStateOf(0)
    /** how many 1-second points the last update added (the graph glides that many steps) */
    var added by mutableIntStateOf(1) }

@Composable fun liveStats(app: AppState, everyMs: Long = 1_000): LiveStats {
    val ls = remember { LiveStats().also { s -> Cache["/api/v1/stats"]?.let { s.load(it) } } }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(everyMs) {
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            runCatching { app.api.get("/api/v1/stats") }.onSuccess { ls.load(it) }
            while (true) {
                // The server samples on the second; asking ~350 ms after it means each answer carries
                // exactly one new point (clock difference estimated from the points themselves).
                val lastT = ls.recent.lastOrNull()?.optDouble("t")
                val wait = if (lastT != null && everyMs == 1_000L) {
                    val skew = lastT - System.currentTimeMillis() / 1000.0
                    val serverNow = System.currentTimeMillis() / 1000.0 + skew
                    (((Math.ceil(serverNow) + 0.35 - serverNow) % 1.0) * 1000).toLong().let { if (it < 200) it + 1000 else it }
                } else everyMs
                kotlinx.coroutines.delay(wait)
                val since = ls.recent.lastOrNull()?.optDouble("t") ?: 0.0
                runCatching { app.api.get("/api/v1/stats?since=$since") }.onSuccess { r ->
                    r.optJSONObject("now")?.takeIf { it.has("cpu") }?.let { ls.now = it; app.lastNow = it }
                    val add = r.optJSONArray("recent").toObjects()
                    if (add.isNotEmpty()) { ls.recent = (ls.recent + add).takeLast(RECENT_POINTS); ls.added = add.size.coerceIn(1, 5); ls.tick++ }
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
    added = 1; tick++
}

/**
 * Smooth line chart with a soft fill, auto-scaled (or fixed to [max]). The line is a monotone cubic
 * through the samples (no overshoot above 100% or below 0), and when a new sample arrives ([tick])
 * the whole line glides left by one step instead of jumping. [window] = how many steps are visible.
 */
@Composable fun Sparkline(values0: List<Float>, color: Color, modifier: Modifier = Modifier, max: Float? = null,
                          tick0: Int = 0, window: Int = 0, added0: Int = 1, times0: List<Long>? = null, fmt: ((Float) -> String)? = null, scrub: Boolean = false) {
    var hx by remember { mutableStateOf<Float?>(null) }          // where the finger is (scrub = true): read the value there
    // while a finger reads a point the graph holds still; it catches up when the finger lifts
    class Held(val v: List<Float>, val t: List<Long>?, val tick: Int, val added: Int)
    var held by remember { mutableStateOf<Held?>(null) }
    val cur by androidx.compose.runtime.rememberUpdatedState(Held(values0, times0, tick0, added0))
    val values = held?.v ?: values0; val times = held?.t ?: times0; val tick = held?.tick ?: tick0; val added = held?.added ?: added0
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val tipBg = N.card; val tipInk = N.text; val tipSub = N.sub
    // The glide is timed from the composition that brought the new values (not from an effect that
    // starts a frame later), so the newest point never flashes at the edge and snaps back.
    val t0 = remember(tick) { System.nanoTime() }
    val steps0 = remember(tick) { added.coerceIn(1, 5) }
    var frameNow by remember { mutableLongStateOf(System.nanoTime()) }
    if (window > 0 && !reduceMotion()) LaunchedEffect(Unit) { while (true) { androidx.compose.runtime.withFrameNanos { frameNow = System.nanoTime() }; kotlinx.coroutines.delay(if (lite()) 66 else 30) } }      // ~30 fps glide
    val peak = if (max != null) max else ((values.maxOrNull() ?: 1f) * 1.15f).coerceAtLeast(1f)
    val top by androidx.compose.animation.core.animateFloatAsState(peak, label = "scale")
    val touch = if (!scrub) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(); held = cur; hx = down.position.x; down.consume()
            while (true) {
                val e = awaitPointerEvent(); val c = e.changes.firstOrNull() ?: break
                if (!c.pressed) break
                hx = c.position.x; c.consume()
            }
            hx = null; held = null
        }
    }
    Canvas(modifier.clipToBounds().then(touch)) {
        if (values.size < 2) return@Canvas
        val steps = if (window > 0) window else values.size - 1
        val dx = size.width / steps
        // the newest point slides in from one step to the right
        val p = if (reduceMotion()) 1f else ((frameNow - t0) / 950e6f).coerceIn(0f, 1f)
        val shift = if (window > 0) (1f - p) * dx * steps0 else 0f
        fun x(i: Int) = size.width - (values.lastIndex - i) * dx + shift
        fun y(v: Float) = size.height - (v / top).coerceIn(0f, 1f) * size.height * 0.94f - size.height * 0.03f
        val first = if (window > 0) (values.lastIndex - window - 1 - steps0).coerceAtLeast(0) else 0
        val xs = (first..values.lastIndex).map { x(it) }; val ys = (first..values.lastIndex).map { y(values[it]) }
        val line = smoothPath(xs, ys)
        val fill = Path().apply { addPath(line); lineTo(xs.last(), size.height); lineTo(xs.first(), size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f), color.copy(alpha = 0.0f))))
        drawPath(line, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawCircle(color.copy(alpha = 0.25f), 7.dp.toPx(), Offset(xs.last(), ys.last()))
        drawCircle(color, 3.5.dp.toPx(), Offset(xs.last(), ys.last()))
        hx?.let { fx ->                                            // the point under the finger: a line, a dot and its value + time
            val k = xs.indices.minByOrNull { kotlin.math.abs(xs[it] - fx) } ?: return@let
            drawLine(color.copy(alpha = 0.55f), Offset(xs[k], 0f), Offset(xs[k], size.height), 1.dp.toPx(),
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(tipBg, 7.dp.toPx(), Offset(xs[k], ys[k])); drawCircle(color, 5.dp.toPx(), Offset(xs[k], ys[k]))
            val v = values[first + k]; val t = times?.getOrNull(first + k)
            val label = (fmt?.invoke(v) ?: "%.1f".format(v)) + (t?.let { "  " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it * 1000)) } ?: "")
            val lay = measurer.measure(label, androidx.compose.ui.text.TextStyle(color = tipInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            val pad = 8.dp.toPx(); val w = lay.size.width + pad * 2; val h = lay.size.height + pad
            val left = (xs[k] - w / 2).coerceIn(0f, size.width - w)
            drawRoundRect(tipBg, Offset(left, 4.dp.toPx()), androidx.compose.ui.geometry.Size(w, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
            drawText(lay, topLeft = Offset(left + pad, 4.dp.toPx() + pad / 2))
        }
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
                                   tick: Int = 0, window: Int = 0, added: Int = 1, times: List<Long>? = null, fmt: ((Float) -> String)? = null, live: Boolean = true) {
    var big by remember { mutableStateOf(false) }
    if (big) androidx.compose.ui.window.Dialog({ big = false }, androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(28.dp)).background(N.card).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = N.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${if (live) "Live · last 2 minutes" else "Last hour"} — drag along the line to see the value at that time", color = N.sub, fontSize = 13.sp)
                }
                androidx.compose.material3.IconButton({ big = false }) { Icon(Icons.Rounded.Close, "Close", tint = N.sub) }
            }
            Text(value, color = N.text, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Sparkline(values, color, Modifier.fillMaxWidth().height(320.dp).padding(top = 8.dp), max, tick, window, added, times, fmt, scrub = true)
        }
    }
    Column(modifier.glassCard(RoundedCornerShape(24.dp)).bouncy(onClick = { big = true }).padding(16.dp)) {
        Text(title, color = N.sub, fontSize = 13.sp)
        Text(value, color = N.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = N.sub, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Sparkline(values, color, Modifier.fillMaxWidth().height(56.dp), max, tick, window, added)
    }
}

@Composable private fun CoreCard(i: Int, v: Double, values: List<Float>, modifier: Modifier, tick: Int, window: Int, added: Int) {
    Column(modifier.glassCard(RoundedCornerShape(18.dp)).padding(12.dp)) {
        Text("Core $i", color = N.sub, fontSize = 12.sp)
        Text("%.0f%%".format(v), color = N.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Sparkline(values, N.blue, Modifier.fillMaxWidth().height(34.dp), 100f, tick, window, added)
    }
}

/** Mounted storage, from the monitor's list (any machine), or the older fixed keys. (name, used 0..100, "x GB free") */
data class StorageUse(val name: String, val pct: Float, val free: String, val mount: String)
fun storageList(m: JSONObject?): List<StorageUse> {
    val list = m?.optJSONArray("storage")
    if (list != null && list.length() > 0) return (0 until list.length()).map { list.getJSONObject(it) }.map {
        StorageUse(it.optString("name"), it.optDouble("pct").toFloat(), "${bytesHuman(it.optLong("free"))} free", it.optString("mount")) }
    val root = m?.optString("root_used")?.takeIf { it.isNotEmpty() } ?: return emptyList()      // servers without the monitor's list
    return listOf(StorageUse("System drive", Regex("(\\d+)%").find(root)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f, Regex("\\(([^)]*)\\)").find(root)?.groupValues?.get(1) ?: root, "/"))
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
    val times = pts.map { it.optLong("t") }
    val pctF: (Float) -> String = { "%.0f%%".format(it) }
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
        AttentionList(app)
        // Live graphs
        SectionLabel(if (hourView) "Last hour" else "Live · last 2 minutes")
        Segmented(listOf("Live", "Last hour"), if (hourView) 1 else 0) { hourView = it == 1 }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.gap)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                MetricCard("CPU", now?.let { "%.0f%%".format(it.optDouble("cpu")) } ?: "—", now?.let { "load ${it.optDouble("load")} · ${it.optInt("cores")} cores" } ?: "",
                    N.blue, series("cpu"), 100f, Modifier.weight(1f), tick, window, ls.added, times, pctF, live)
                MetricCard("Memory", now?.let { "%.0f%%".format(it.optDouble("mem")) } ?: "—", now?.let { "${it.optDouble("mem_used_gb")} of ${it.optDouble("mem_total_gb")} GB" } ?: "",
                    Color(0xFFBF5AF2), series("mem"), 100f, Modifier.weight(1f), tick, window, ls.added, times, pctF, live)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                MetricCard("CPU temperature", now?.optDouble("temp")?.takeIf { !it.isNaN() }?.let { "%.0f°C".format(it) } ?: m?.optString("cpu_temp") ?: "—",
                    now?.optDouble("nvme_temp")?.takeIf { !it.isNaN() }?.let { "NVMe %.0f°C".format(it) } ?: "", N.amber, series("temp"), null, Modifier.weight(1f), tick, window, ls.added, times, { "%.0f°C".format(it) }, live)
                MetricCard("Network", now?.let { "↓ ${rate(it.optDouble("rx"))}" } ?: "—", now?.let { "↑ ${rate(it.optDouble("tx"))}" } ?: "",
                    N.green, series("rx").zip(series("tx")) { a, b -> a + b }, null, Modifier.weight(1f), tick, window, ls.added, times, { rate(it.toDouble()) }, live)
            }
            val hasGpu = now != null && !now.isNull("gpu") && now.has("gpu")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                if (hasGpu) MetricCard("Graphics", "%.0f%%".format(now!!.optDouble("gpu")), "GPU busy",
                    N.red, series("gpu"), 100f, Modifier.weight(1f), tick, window, ls.added, times, pctF, live)
                val noSwap = now != null && now.has("swap_total_gb") && now.optDouble("swap_total_gb") == 0.0
                if (!noSwap) MetricCard("Swap", now?.let { "%.0f%%".format(it.optDouble("swap", 0.0)) } ?: "—",
                    now?.takeIf { it.optDouble("swap_total_gb") > 0 }?.let { "${it.optDouble("swap_used_gb")} of ${it.optDouble("swap_total_gb")} GB" } ?: "of swap in use",
                    Color(0xFFFF9F0A), series("swap"), 100f, Modifier.weight(1f), tick, window, ls.added, times, pctF, live)
                if (!hasGpu) MetricCard("Disk I/O", now?.let { rate(it.optDouble("io", 0.0)) } ?: "—", "read + write, all drives",
                    Color(0xFF64D2FF), series("io"), null, Modifier.weight(1f), tick, window, ls.added, times, { rate(it.toDouble()) }, live)
            }
            if (hasGpu) MetricCard("Disk I/O", rate(now!!.optDouble("io", 0.0)), "read + write, all drives",
                Color(0xFF64D2FF), series("io"), null, Modifier.fillMaxWidth(), tick, window, ls.added, times, { rate(it.toDouble()) }, live)
            // one small graph per core (live view only — the hour view keeps the overall CPU)
            val cores = now?.optJSONArray("cpus")?.length() ?: 0
            if (live && cores > 1) {
                Box(Modifier.offset(x = -Space.gutter)) { SectionLabel("CPU cores · $cores") }      // the page column is already inset
                (0 until cores).chunked(3).forEach { rowIdx ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                        rowIdx.forEach { i ->
                            val vals = pts.map { it.optJSONArray("cpus")?.optDouble(i, 0.0)?.toFloat()?.takeIf { v -> !v.isNaN() } ?: 0f }
                            CoreCard(i, now!!.optJSONArray("cpus")!!.optDouble(i, 0.0), vals, Modifier.weight(1f), tick, window, ls.added)
                        }
                        repeat(3 - rowIdx.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (pts.size < 3 || (!hourView && !live)) Text(if (hourView) "Graphs fill in over the next few minutes." else "Connecting to the live feed…", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        // Storage
        SectionLabel("Storage")
        Group {
            storageList(m).forEachIndexed { i, su ->
                    if (i > 0) RowDivider()
                    val p = su.pct
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                        Row { Text(su.name, color = N.text, fontSize = 16.sp, modifier = Modifier.weight(1f)); Text("${p.toInt()}% · ${su.free}", color = N.sub, fontSize = 14.sp) }
                        Spacer(Modifier.height(8.dp))
                        UsageBar(p / 100f, if (p > 95) N.red else if (p > 85) N.amber else N.blue)
                    }
                }
            RowDivider()
            Row1("Drives", "${m?.optString("drives") ?: "—"} · ${m?.optString("drive_temps") ?: ""}", true, Icons.Rounded.Storage, onClick = { app.go(Route.Hardware) })
        }
        // Backups — only what this server actually reports (nova-backup, database dumps…)
        val dbRows = m?.keys()?.asSequence()?.filter { it.endsWith("_db_backup") }?.map { k ->
            (k.removeSuffix("_db_backup").replace('_', ' ').replaceFirstChar { it.uppercase() } + " database backup") to m.optString(k) }?.toList() ?: emptyList()
        val rows = listOf("Backup sets" to m?.optString("backup_sets"), "Photo check" to m?.optString("backup_verify")?.replace("✗", "")?.trim(),
            "Server settings backup" to m?.optString("config_backup")).filter { !it.second.isNullOrEmpty() } + dbRows
        if (backupRunning || !m?.optString("data_backup").isNullOrEmpty() || rows.isNotEmpty()) {
            SectionLabel("Backups")
            Group {
                if (backupRunning || !m?.optString("data_backup").isNullOrEmpty()) {
                    Row1("Data backup", if (backupRunning) backup?.let { backupLine(it) } ?: "Running now" else m?.optString("data_backup"), backupRunning, Icons.Rounded.Backup)
                    if (backupRunning && backup != null) BackupProgress(backup)
                }
                rows.forEachIndexed { i, (l, v) -> if (i > 0 || backupRunning || !m?.optString("data_backup").isNullOrEmpty()) RowDivider(); Row1(l, v) }
            }
        }
        // Services
        var swapAsk by remember { mutableStateOf(false) }
        SectionLabel("Services")
        Group {
            Row1("Containers", m?.optString("containers")?.ifEmpty { null } ?: app.overview?.optJSONObject("containers")?.let { "${it.optInt("running")}/${it.optInt("total")} running" },
                true, Icons.Rounded.ViewInAr, onClick = { app.go(Route.Containers) })
            if (!m?.optString("websites").isNullOrEmpty()) { RowDivider(); Row1("Websites", m?.optString("websites"), false, Icons.Rounded.Language) }
            val noSwap = now != null && now.has("swap_total_gb") && now.optDouble("swap_total_gb") == 0.0
            // no swap at all: the graph is gone, and an admin can make a swap file (the graph comes back with it)
            if (noSwap && app.isAdmin) { RowDivider(); Row1("Swap", "None set up — add a swap file, so a busy moment doesn't run memory out", true, Icons.Rounded.SwapHoriz, onClick = { swapAsk = true }) }
            else { RowDivider(); Row1("Swap", m?.optString("swap")?.ifEmpty { null } ?: now?.let { "%.0f%% used".format(it.optDouble("swap")) }, false, Icons.Rounded.SwapHoriz) }
        }
        if (swapAsk) OneDialog({ swapAsk = false }, "Set up swap", "A swap file on the system drive. It's switched on now and comes back after a restart.",
            listOf(DialogButton("Cancel") { swapAsk = false })) {
            listOf(2 to null, 4 to "A good size for most servers", 8 to null, 16 to null).forEach { (gb, sub) ->
                DialogChoice("$gb GB", sub, selected = false, action = true) { swapAsk = false; app.act {
                    val r = app.stepUp("Add a $gb GB swap file", "POST", "/api/v1/storage/task/swap-setup", org.json.JSONObject().put("spec", org.json.JSONObject().put("gb", gb)))
                    app.go(Route.Task(r.optString("id"))) } }
            }
        }
    }
}
