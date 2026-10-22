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

/** A small meter at the top: frames per second over the last half second, and the slowest frame in it. */
@Composable fun FpsMeter(modifier: Modifier = Modifier) {
    var fps by remember { mutableIntStateOf(0) }; var worst by remember { mutableIntStateOf(0) }
    val hz = androidx.compose.ui.platform.LocalView.current.display?.refreshRate?.toInt() ?: 60
    LaunchedEffect(Unit) {
        var last = 0L; var n = 0; var t0 = 0L; var w = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) { n++; w = maxOf(w, now - last) }
            if (t0 == 0L) t0 = now
            if (now - t0 >= 500_000_000L) { fps = (n * 1_000_000_000L / (now - t0)).toInt(); worst = (w / 1_000_000).toInt(); n = 0; w = 0; t0 = now }
            last = now
        }
    }
    val c = when { fps >= hz * 0.9 -> Color(0xFF30D158); fps >= hz * 0.6 -> Color(0xFFFF9F0A); else -> Color(0xFFFF453A) }
    Row(modifier.statusBarsPadding().padding(top = 4.dp).background(Color.Black.copy(alpha = .72f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text("$fps fps", color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text("  ·  worst ${worst} ms  ·  ${hz} Hz", color = Color.White.copy(alpha = .8f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable fun DevScreen(app: AppState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    Page("Developer options", app::back) {
        Note("Tools for checking how Nova runs on this ${DeviceForm.noun}. They're only on this ${DeviceForm.noun}, and off unless you turn them on.", 13.sp, 4.dp)
        SectionLabel("Drawing")
        Group {
            SwitchRow("Show frame rate", "A meter at the top: frames per second and the slowest frame", Dev.fps) { Dev.set(ctx, "fps", it) }
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
