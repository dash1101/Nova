package app.novalabs.nova

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import org.json.JSONObject
import kotlin.math.*

/** What the fan ring should look like, derived from the server's fan state. */
data class FanLook(val on: Boolean, val effect: String, val color: Color, val color2: Color,
                   val brightness: Float, val speed: Int, val rainbow: Boolean, val leds: Int,
                   val palette: List<String> = emptyList(), val hex: String = "#3e91ff", val hex2: String = "#bf5af2")

fun fanLook(f: JSONObject?): FanLook {
    fun c(s: String, d: String) = Color(android.graphics.Color.parseColor(f?.optString(s, d)?.ifEmpty { d } ?: d))
    val ov = f?.optJSONObject("status_override")
    return FanLook(on = (ov?.optBoolean("on") ?: f?.optBoolean("on", true)) ?: true,
        effect = ov?.optString("effect") ?: f?.optString("effect", "static") ?: "static",
        color = if (ov != null) Color(android.graphics.Color.parseColor(ov.optString("color", "#ffffff"))) else c("color", "#3e91ff"),
        color2 = c("color2", "#bf5af2"),
        brightness = (((ov?.optInt("brightness") ?: f?.optInt("brightness", 50)) ?: 50) / 100f).coerceIn(0f, 1f),
        speed = (ov?.optInt("speed") ?: f?.optInt("speed", 50)) ?: 50, rainbow = f?.optBoolean("rainbow", true) ?: true,
        leds = (f?.optInt("led_count", 12) ?: 12).coerceIn(4, 40),
        palette = if (ov != null) emptyList() else f?.optJSONArray("palette")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        hex = ov?.optString("color")?.ifEmpty { null } ?: f?.optString("color")?.ifEmpty { null } ?: "#3e91ff",
        hex2 = f?.optString("color2")?.ifEmpty { null } ?: "#bf5af2")
}

private fun hsv(h: Float) = Color.hsv(((h % 1f) + 1f) % 1f * 360f, 1f, 1f)
private val CYCLE = listOf(Color(0xFFFF0000), Color(0xFFFF7F00), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF0000FF), Color(0xFF4B0082), Color(0xFF8F00FF))

/**
 * Color of LED [j] (of [n]) at server time [tMs], exactly as the fan shows it:
 *  - wave: Nova draws it itself (nova_rgb.wave_frame) from the clock, so this matches frame for frame;
 *  - pulse / blink / cycle / random: the motherboard runs these on its own clock, so the speed matches
 *    but not necessarily the moment.
 */
private fun ledColor(look: FanLook, j: Int, n: Int, tMs: Double, reverse: Boolean): Pair<Color, Float> {
    val per = periodMs(look.speed)
    val jj = if (reverse) (n - j) % n else j
    if (LightFx.isSoftware(look.effect, look.palette, look.rainbow)) {        // drawn by Nova's animator from the clock: identical maths
        val c = LightFx.frame(LightFx.Fx(look.effect, n, look.speed, look.rainbow, look.palette, look.hex, look.hex2), tMs / 1000.0)[jj]
        val m = maxOf(c[0], c[1], c[2]).coerceAtLeast(1)
        return Color(c[0] / m.toFloat(), c[1] / m.toFloat(), c[2] / m.toFloat()) to m / 255f       // hue + level
    }
    return when (look.effect) {
        "wave" -> {
            val phase = ((tMs / (per * 1.5)) % 1.0).toFloat()
            if (look.rainbow) hsv(jj.toFloat() / n + phase) to 1f
            else { val x = (cos(2 * PI * (jj.toFloat() / n - phase)) + 1) / 2; look.color to (0.08f + 0.92f * x.pow(3).toFloat()) }
        }
        "gradient" -> lerp(look.color, look.color2, if (n > 1) jj / (n - 1f) else 0f) to 1f
        "pulse" -> { val hold = max(50.0, per / 5); val cyc = per + hold; val p = (tMs % cyc) / per
            look.color to (if (p >= 1) 0f else (1 - abs(2 * p - 1)).toFloat()).let { 0.05f + 0.95f * it } }
        "blink" -> { val cyc = max(200.0, per) + 200; look.color to (if (tMs % cyc < 100) 1f else 0.04f) }
        "cycle" -> { val p = max(400.0, per); val k = ((tMs / p) % 7).toFloat()
            val a = if (look.rainbow) CYCLE[k.toInt() % 7] else look.color; val b = if (look.rainbow) CYCLE[(k.toInt() + 1) % 7] else look.color
            lerp(a, b, ((k % 1f) * 3f - 2f).coerceIn(0f, 1f)) to 1f }
        "random" -> { val slot = (tMs / max(30.0, min(1000.0, per / 10))).toLong()
            hsv(((slot * 7919 + jj * 104729) % 997) / 997f) to 1f }
        else -> look.color to 1f
    }
}

/** The fan ring: a continuous glowing strip (the LEDs' colors blended round it), with spinning blades. */
private fun DrawScope.fanRing(c: Offset, r: Float, look: FanLook, tMs: Double, spin: Float, dark: Boolean, reverse: Boolean) {
    drawCircle(if (dark) Color(0xFF151518) else Color(0xFF2A2A30), r * 1.12f, c)
    if (look.on) {
        val n = look.leds; val a0 = 0.25f + 0.75f * look.brightness
        // one color stop per LED (LED 0 at the top, clockwise), its brightness as alpha; wrap round
        val stops = List(n) { j -> val (col, k) = ledColor(look, j, n, tMs, reverse); col.copy(alpha = (a0 * k).coerceIn(0.03f, 1f)) }
        val brush = Brush.sweepGradient(stops + stops.first(), c)
        rotate(-90f, c) {                  // the sweep starts at 3 o'clock; LED 0 belongs at the top
            for (i in 6 downTo 1) drawCircle(brush, r * (1.0f + i * 0.06f), c, alpha = 0.07f * (7 - i) / 6f, style = Stroke(r * 0.08f * i))
            drawCircle(brush, r, c, style = Stroke(r * 0.12f))
        }
    } else drawCircle(Color(0xFF2C2C30), r, c, style = Stroke(r * 0.12f))
    drawCircle(if (dark) Color(0xFF0E0E10) else Color(0xFF1E1E22), r * 0.93f, c)
    rotate(spin, c) {
        for (b in 0 until 7) rotate(b * 360f / 7, c) {
            val p = Path().apply {
                moveTo(c.x, c.y - r * 0.2f)
                cubicTo(c.x + r * 0.55f, c.y - r * 0.35f, c.x + r * 0.62f, c.y - r * 0.78f, c.x + r * 0.12f, c.y - r * 0.86f)
                cubicTo(c.x + r * 0.25f, c.y - r * 0.55f, c.x + r * 0.1f, c.y - r * 0.32f, c.x, c.y - r * 0.2f)
            }
            drawPath(p, Color(0xFF3A3A40))
        }
    }
    drawCircle(Color(0xFF2E2E33), r * 0.22f, c)
    drawCircle(Color(0xFF45454B), r * 0.22f, c, style = Stroke(r * 0.02f))
}

/** Average color the ring throws onto the glass (for the soft glow behind it). */
private fun ringGlow(look: FanLook, tMs: Double, reverse: Boolean): Color {
    var r = 0f; var g = 0f; var b = 0f; val n = look.leds
    for (j in 0 until n) { val (c, k) = ledColor(look, j, n, tMs, reverse); r += c.red * k; g += c.green * k; b += c.blue * k }
    return Color(min(1f, r / n), min(1f, g / n), min(1f, b / n))
}

/** Frame clock in server time (ms), so the picture's wave lines up with the real fan. */
@Composable private fun serverClock(skewMs: Long): State<Double> {
    val t = remember { mutableDoubleStateOf(System.currentTimeMillis().toDouble()) }
    LaunchedEffect(skewMs) {
        if (reduceMotion()) { t.doubleValue = (System.currentTimeMillis() + skewMs).toDouble(); return@LaunchedEffect }
        // ~30 fps is plenty for a slow light wave, and lets a 120 Hz screen (and the blur over it) rest in between
        while (true) { withFrameNanos { t.doubleValue = (System.currentTimeMillis() + skewMs).toDouble() }; kotlinx.coroutines.delay(30) }
    }
    return t
}

/** How often a drive's light blinks (% of 110 ms slots), from its real activity on the server
 *  ([busy share, bytes/s]), or a gentle flicker when the server doesn't report it. */
private fun blinkRate(io: org.json.JSONArray?): Int {
    if (io == null) return 9
    val busy = io.optDouble(0, 0.0); val bps = io.optDouble(1, 0.0)
    return if (busy > 0 || bps > 0) min(85.0, 6 + 80 * sqrt(busy)).toInt() else 0
}
/** Disk-activity look for a healthy drive's LED: quick blips that fade (deterministic per LED). */
private fun activity(i: Int, tMs: Double, pct: Int = 9): Float {
    val slotMs = 110.0; val now = (tMs / slotMs).toLong(); var v = 0f
    for (k in 0..4) {
        val s = now - k; val h = ((s * 2654435761L + i * 40503L) xor (s shr 3)) and 0xFFFF
        if (h % 100 < pct) v = max(v, exp(-((tMs - s * slotMs) / 160.0)).toFloat())
    }
    return v
}

/**
 * The home-screen server: tower case, glass side, the live fan, one LED per drive (boot drive
 * included) and the power LED in the status color. Drive LEDs: green with activity when healthy,
 * amber when worn or hot, red (pulsing) when missing or failing.
 */
@Composable fun ServerHero(fan: JSONObject?, statusColor: Color, drives: List<JSONObject>?, skewMs: Long, modifier: Modifier = Modifier, io: JSONObject? = null) {
    val look = fanLook(fan); val dark = N.dark; val reverse = AppPrefs.fanReverse
    val t by serverClock(skewMs)
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cw = (w * 0.56f).coerceAtMost(h * 0.62f); val ch = cw * 1.42f
        val left = (w - cw) / 2; val top = (h - ch) / 2
        val spin = if (reduceMotion()) 0f else ((t % 1400.0) / 1400.0 * 360).toFloat()
        drawOval(Color.Black.copy(alpha = if (dark) 0.6f else 0.18f), Offset(left + cw * 0.05f, top + ch * 0.97f), Size(cw * 0.9f, ch * 0.06f))
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF4A4A52), Color(0xFF26262B), Color(0xFF1B1B1F)), top, top + ch),
            Offset(left, top), Size(cw, ch), CornerRadius(cw * 0.07f))
        drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(left, top), Size(cw, ch), CornerRadius(cw * 0.07f), style = Stroke(2.5f))
        val gp = cw * 0.07f
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF101014), Color(0xFF17171C)), Offset(left, top), Offset(left + cw, top + ch)),
            Offset(left + gp, top + gp), Size(cw - 2 * gp, ch - 2 * gp * 1.6f), CornerRadius(cw * 0.04f))
        val fc = Offset(left + cw / 2, top + ch * 0.33f)
        if (look.on) drawCircle(Brush.radialGradient(listOf(ringGlow(look, t, reverse).copy(alpha = 0.18f + 0.22f * look.brightness), Color.Transparent), fc, cw * 0.6f), cw * 0.6f, fc)
        fanRing(fc, cw * 0.29f, look, t, spin, dark, reverse)
        drawRoundRect(Color(0xFF26262C), Offset(left + cw * 0.16f, top + ch * 0.62f), Size(cw * 0.68f, ch * 0.07f), CornerRadius(8f))
        drawRoundRect(Color(0xFF1F1F25), Offset(left + cw * 0.16f, top + ch * 0.72f), Size(cw * 0.68f, ch * 0.05f), CornerRadius(8f))
        // one LED per drive
        val list = drives?.takeIf { it.isNotEmpty() }
        val n = list?.size ?: 6
        val span = cw * 0.62f; val step = if (n > 1) min(cw * 0.09f, span / (n - 1)) else 0f
        val x0 = left + cw / 2 - step * (n - 1) / 2; val y = top + ch * 0.83f; val rr = min(cw * 0.016f, step * 0.32f).coerceAtLeast(cw * 0.008f)
        for (i in 0 until n) {
            val lvl = list?.get(i)?.optString("level") ?: "ok"
            val p = Offset(x0 + i * step, y)
            val (col, a) = when (lvl) {
                "critical" -> Color(0xFFFF4040) to (0.35f + 0.65f * (0.5f + 0.5f * sin(t / 1000.0 * 2 * PI).toFloat()))
                "warning" -> Color(0xFFFFB020) to 0.9f
                else -> Color(0xFF3ECF6E) to (0.38f + 0.62f * activity(i, t, blinkRate(io?.optJSONArray(list?.get(i)?.optString("name") ?: ""))))
            }
            if (a > 0.5f) drawCircle(col.copy(alpha = (a - 0.5f) * 0.6f), rr * 2.6f, p)
            drawCircle(col.copy(alpha = a), rr, p)
        }
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), Offset(left, top), Offset(left + cw * 0.5f, top + ch * 0.5f)),
            Offset(left + gp, top + gp), Size(cw * 0.42f, ch * 0.55f))
        drawCircle(statusColor.copy(alpha = 0.35f), cw * 0.03f, Offset(left + cw * 0.5f, top + ch * 0.935f))
        drawCircle(statusColor, cw * 0.016f, Offset(left + cw * 0.5f, top + ch * 0.935f))
    }
}

/** Big fan for the Lighting page. */
@Composable fun FanHero(fan: JSONObject?, skewMs: Long, modifier: Modifier = Modifier) {
    val look = fanLook(fan); val dark = N.dark; val reverse = AppPrefs.fanReverse
    val t by serverClock(skewMs)
    Canvas(modifier) {
        val r = size.minDimension * 0.34f; val c = Offset(size.width / 2, size.height / 2)
        val spin = if (reduceMotion()) 0f else ((t % 1400.0) / 1400.0 * 360).toFloat()
        if (look.on) drawCircle(Brush.radialGradient(listOf(ringGlow(look, t, reverse).copy(alpha = 0.2f + 0.25f * look.brightness), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
        fanRing(c, r, look, t, spin, dark, reverse)
    }
}
