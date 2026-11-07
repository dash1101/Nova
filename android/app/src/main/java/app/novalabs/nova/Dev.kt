package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Developer options (About → Developer options): a frame-rate meter, the requests the app made, outlines of the
 * glass layers, the lens switched off for comparison, and device details to paste into a bug report.
 * All of it is per device and off by default.
 */
object Dev {
    var fps by mutableStateOf(false); private set
    var bounds by mutableStateOf(false); private set
    var noLens by mutableStateOf(false); private set
    /** Glass quality, percent: 65%+ keeps the prism fringe (Light splitting); below that the fringe is left out and the
     *  bending rim narrows (the default, 70%, looks nearly the same for much less GPU work); below 50% there's no lens. */
    var glassQ by mutableIntStateOf(70); private set
    /** Frame rate cap (0 = as fast as the screen goes). */
    var fpsCap by mutableIntStateOf(0); private set
    fun load(ctx: android.content.Context) {
        val p = ctx.getSharedPreferences("nova_dev", 0)
        fps = p.getBoolean("fps", false); bounds = p.getBoolean("bounds", false); noLens = p.getBoolean("nolens", false)
        glassQ = p.getInt("glass_q", 70).coerceIn(30, 100); fpsCap = p.getInt("fps_cap", 0)
    }
    fun setInt(ctx: android.content.Context, k: String, v: Int) {
        ctx.getSharedPreferences("nova_dev", 0).edit().putInt(k, v).apply()
        when (k) { "glass_q" -> glassQ = v; "fps_cap" -> { fpsCap = v; (ctx as? android.app.Activity)?.let { applyFpsCap(it) } } }
    }
    /** Asks the screen for the chosen refresh rate (Android picks the nearest mode the screen has; 0 = no preference). */
    fun applyFpsCap(a: android.app.Activity) {
        val w = a.window ?: return
        w.attributes = w.attributes.apply {
            preferredRefreshRate = fpsCap.toFloat()
            val disp = if (android.os.Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay
            val modes = disp?.supportedModes.orEmpty().toList()
            val cur = disp?.mode
            // same resolution, the highest rate that's no faster than the cap (or the fastest, with no cap)
            val pick = modes.filter { cur == null || (it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight) }
                .filter { fpsCap == 0 || it.refreshRate <= fpsCap + 0.5f }.maxByOrNull { it.refreshRate }
            preferredDisplayModeId = if (fpsCap == 0) 0 else pick?.modeId ?: 0
        }
    }
    fun set(ctx: android.content.Context, k: String, v: Boolean) {
        ctx.getSharedPreferences("nova_dev", 0).edit().putBoolean(k, v).apply()
        when (k) { "fps" -> fps = v; "bounds" -> bounds = v; "nolens" -> noLens = v }
    }
}

/** The last requests to the server: what, how long, how it went, and over which route (LAN, Tailscale, remote). */
object DevLog {
    data class Entry(val t: Long, val method: String, val path: String, val code: Int, val ms: Long, val via: String, val error: String?)
    val entries = mutableStateListOf<Entry>()
    fun add(method: String, path: String, code: Int, ms: Long, via: String, error: String?) {
        val e = Entry(System.currentTimeMillis(), method, path.substringBefore('?').take(80), code, ms, via, error?.take(120))
        android.os.Handler(android.os.Looper.getMainLooper()).post { entries.add(0, e); while (entries.size > 60) entries.removeAt(entries.lastIndex) }
    }
}

/** What Android itself timed for each frame Nova drew (FrameMetrics): how long the app's thread took, how long drawing took after that, and the
 *  total. It only hears about frames that were really drawn, so watching it never makes the screen redraw — an idle screen reads as idle. */
object FrameStats {
    private const val CAP = 600
    private val ui = FloatArray(CAP); private val gpu = FloatArray(CAP); private val tot = FloatArray(CAP); private val at = LongArray(CAP); private var n = 0L
    private var window: android.view.Window? = null; private var listener: android.view.Window.OnFrameMetricsAvailableListener? = null
    private var thread: android.os.HandlerThread? = null
    class Snap(val fps: Int, val frames: Int, val seconds: Float, val tot: FloatArray, val ui: FloatArray, val gpu: FloatArray)
    fun start(w: android.view.Window) {
        if (window === w) return; stop()
        val t = android.os.HandlerThread("nova-frames").also { it.start() }; thread = t
        val l = android.view.Window.OnFrameMetricsAvailableListener { _, fm, _ ->
            fun ms(k: Int) = fm.getMetric(k) / 1e6f
            val u = ms(android.view.FrameMetrics.INPUT_HANDLING_DURATION) + ms(android.view.FrameMetrics.ANIMATION_DURATION) + ms(android.view.FrameMetrics.LAYOUT_MEASURE_DURATION) + ms(android.view.FrameMetrics.DRAW_DURATION)
            val g = ms(android.view.FrameMetrics.SYNC_DURATION) + ms(android.view.FrameMetrics.COMMAND_ISSUE_DURATION) + ms(android.view.FrameMetrics.SWAP_BUFFERS_DURATION)
            synchronized(this) { val i = (n % CAP).toInt(); ui[i] = u; gpu[i] = g; tot[i] = ms(android.view.FrameMetrics.TOTAL_DURATION); at[i] = System.nanoTime(); n++ }
        }
        listener = l; window = w; w.addOnFrameMetricsAvailableListener(l, android.os.Handler(t.looper))
    }
    fun stop() { listener?.let { window?.removeOnFrameMetricsAvailableListener(it) }; listener = null; window = null; thread?.quitSafely(); thread = null }
    @Synchronized fun snapshot(): Snap {
        val k = minOf(n, CAP.toLong()).toInt(); val now = System.nanoTime()
        val idx = (0 until k).map { ((n - 1 - it) % CAP).toInt() }
        val fps = idx.count { now - at[it] < 1_000_000_000L }
        val span = if (k > 1) (at[idx.first()] - at[idx.last()]) / 1e9f else 0f
        return Snap(fps, k, span, FloatArray(k) { tot[idx[it]] }, FloatArray(k) { ui[idx[it]] }, FloatArray(k) { gpu[idx[it]] })
    }
    private fun pct(a: FloatArray, p: Float): Float = if (a.isEmpty()) 0f else a.sortedArray()[((a.size - 1) * p).toInt()]
    /** Plain text for a bug report: how the last few hundred frames went, split into the app's part and the drawing part. */
    fun report(s: Snap = snapshot()): String {
        fun row(name: String, a: FloatArray) = "%-22s p50 %5.1f   p90 %5.1f   p99 %5.1f   worst %5.1f ms".format(name, pct(a, .5f), pct(a, .9f), pct(a, .99f), a.maxOrNull() ?: 0f)
        return "Nova $APP_VERSION · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
            "${s.frames} frames drawn over ${"%.1f".format(s.seconds)} s (frames are only drawn when something changes)\n" +
            row("whole frame", s.tot) + "\n" + row("app thread (UI)", s.ui) + "\n" + row("drawing (GPU side)", s.gpu)
    }
}

/** A small meter at the top: frames actually drawn in the last second (0 when nothing is moving), and where the time goes. */
@Composable fun FpsMeter(modifier: Modifier = Modifier) {
    val view = androidx.compose.ui.platform.LocalView.current
    var snap by remember { mutableStateOf(FrameStats.snapshot()) }
    val hz = view.display?.refreshRate?.toInt() ?: 60
    DisposableEffect(view) {
        var c: android.content.Context = view.context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        (c as? android.app.Activity)?.window?.let { FrameStats.start(it) }
        onDispose { FrameStats.stop() }
    }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); snap = FrameStats.snapshot() } }
    val recent = snap.tot.take(30)
    val c = when { snap.fps == 0 -> Color(0xFF8E8E93); recent.isNotEmpty() && recent.average() < 1000f / hz * 1.1 -> Color(0xFF30D158); recent.isNotEmpty() && recent.average() < 1000f / hz * 2 -> Color(0xFFFF9F0A); else -> Color(0xFFFF453A) }
    fun avg(a: FloatArray) = a.take(30).let { if (it.isEmpty()) 0 else it.average().toInt() }
    Row(modifier.statusBarsPadding().padding(top = 4.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(if (snap.fps == 0) "idle" else "${snap.fps} drawn/s", color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text("  ·  app ${avg(snap.ui)} ms  ·  drawing ${avg(snap.gpu)} ms  ·  worst ${(snap.tot.take(60).maxOrNull() ?: 0f).toInt()} ms", color = Color.White.copy(alpha = .8f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable fun DevScreen(app: AppState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    Page("Developer options", app::back) {
        Note("Tools for checking how Nova runs on this ${DeviceForm.noun}. They're only on this ${DeviceForm.noun}, and off unless you turn them on.", 13.sp, 4.dp)
        SectionLabel("Drawing")
        Group {
            SwitchRow("Show frame rate", "A meter at the top: frames drawn per second (0 = idle), and how long the app and the drawing each take", Dev.fps) { Dev.set(ctx, "fps", it) }
            if (Dev.fps) { RowDivider(); Row1("Copy a frame report", "How the last few hundred frames went — paste it into a bug report", false, Icons.Rounded.ContentCopy, onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Nova", FrameStats.report())); app.toast("Copied") }) }
            RowDivider()
            RowDivider()
            SwitchRow("Show glass areas", "Outlines every Glass layer, to see what's being drawn", Dev.bounds) { Dev.set(ctx, "bounds", it) }
            RowDivider()
            SwitchRow("Glass without bending", "Turns the lens off, to compare how smooth it runs", Dev.noLens) { Dev.set(ctx, "nolens", it) }
            RowDivider()
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Row { Text("Glass quality", color = N.text, fontSize = 17.sp, modifier = Modifier.weight(1f)); Text("${Dev.glassQ}%", color = N.sub, fontSize = 15.sp) }
                Text("How much of Glass's lens is drawn. 65% and up keeps the light splitting at the edges; lower narrows the bending rim and drops it; under 50% the lens is off. Lower is lighter on the GPU and battery.", color = N.sub, fontSize = 13.sp)
                OneSlider(Dev.glassQ.toFloat(), { Dev.setInt(ctx, "glass_q", (it / 5).toInt() * 5) }, 30f..100f)
            }
        }
        SectionLabel("Frame rate")
        Group {
            val hz = view.display?.supportedModes?.map { it.refreshRate.toInt() }?.distinct()?.sorted().orEmpty()
            val caps = listOf(0) + listOf(30, 60, 90, 120).filter { c -> hz.any { it >= c } && c < (hz.maxOrNull() ?: 60) }
            caps.forEachIndexed { i, c -> if (i > 0) RowDivider()
                Row1(if (c == 0) "As fast as the screen goes" else "$c fps", if (c == 0) "Up to ${hz.maxOrNull() ?: 60} Hz" else if (hz.none { it == c }) "The screen picks its nearest rate" else null,
                    onClick = { Dev.setInt(ctx, "fps_cap", c) }) { OneRadio(Dev.fpsCap == c) } }
        }
        SectionLabel("This ${DeviceForm.noun}")
        val info = remember {
            val d = view.display
            listOf("App" to "$APP_VERSION", "Android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                "Device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}", "Screen" to "${view.width}×${view.height} · ${d?.refreshRate?.toInt() ?: 60} Hz",
                "Hardware drawing" to if (view.isHardwareAccelerated) "Yes" else "No", "Server" to (app.overview?.optJSONObject("server")?.optString("version")?.ifEmpty { null } ?: "—"))
        }
        Group {
            info.forEachIndexed { i, (k, v) -> if (i > 0) RowDivider(); Row1(k, v) }
            RowDivider()
            Row1("Copy these details", "For a bug report", false, Icons.Rounded.ContentCopy, onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Nova", info.joinToString("\n") { "${it.first}: ${it.second}" })); app.toast("Copied") })
        }
        SectionLabel("Requests · newest first")
        Group {
            if (DevLog.entries.isEmpty()) Row1("Nothing yet", enabled = false)
            val hm = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
            DevLog.entries.take(40).forEachIndexed { i, e -> if (i > 0) RowDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.code in 200..299) "${e.code}" else if (e.code < 0) "—" else "${e.code}", color = if (e.code in 200..299) N.green else N.red,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(38.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${e.method} ${e.path}", color = N.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("${hm.format(java.util.Date(e.t))} · ${e.ms} ms${if (e.via.isNotEmpty()) " · ${e.via}" else ""}${e.error?.let { " · $it" } ?: ""}", color = N.sub, fontSize = 11.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}
