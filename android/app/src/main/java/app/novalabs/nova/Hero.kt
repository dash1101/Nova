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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
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

/** One frame of a software effect for the whole ring, worked out once per tick. (Asking for one LED's color used to work out every LED's,
 *  for every LED, twice a tick — and the strips under the fan do the same.) A few entries, so the ring and the strips don't evict each other. */
private val memoKeys = arrayOfNulls<Pair<LightFx.Fx, Double>>(4); private val memoVals = arrayOfNulls<List<IntArray>>(4); private var memoNext = 0
private fun fxFrame(fx: LightFx.Fx, tMs: Double): List<IntArray> {
    for (i in 0 until 4) { val k = memoKeys[i]; if (k != null && k.second == tMs && k.first == fx) return memoVals[i]!! }
    val f = LightFx.frame(fx, tMs / 1000.0); memoKeys[memoNext] = fx to tMs; memoVals[memoNext] = f; memoNext = (memoNext + 1) % 4; return f
}
/** The fan blade's outline for a ring of this size, kept between frames (a new Path every frame had to be rasterized again every frame). */
private class BladeCache { private var cx = Float.NaN; private var cy = Float.NaN; private var r = Float.NaN; private val path = Path()
    fun get(c: Offset, r: Float): Path { if (c.x != cx || c.y != cy || r != this.r) { cx = c.x; cy = c.y; this.r = r
        path.reset(); path.moveTo(c.x, c.y - r * 0.2f)
        path.cubicTo(c.x + r * 0.55f, c.y - r * 0.35f, c.x + r * 0.62f, c.y - r * 0.78f, c.x + r * 0.12f, c.y - r * 0.86f)
        path.cubicTo(c.x + r * 0.25f, c.y - r * 0.55f, c.x + r * 0.1f, c.y - r * 0.32f, c.x, c.y - r * 0.2f) }
        return path }
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
        val c = fxFrame(LightFx.Fx(look.effect, n, look.speed, look.rainbow, look.palette, look.hex, look.hex2), tMs)[jj]
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

private val TRAIL_STILL = listOf(0f to 1f); private val TRAIL = listOf(-7.5f to 0.12f, -5f to 0.2f, -2.5f to 0.35f, 0f to 1f); private val TRAIL_SLOW = listOf(-5f to 0.25f, 0f to 1f)

/** The fan ring: a continuous glowing strip (the LEDs' colors blended round it), with spinning blades. */
private fun DrawScope.fanRing(c: Offset, r: Float, look: FanLook, tMs: Double, spin: Float, dark: Boolean, reverse: Boolean, bladeCache: BladeCache, slow: Boolean, blades: Boolean = true) {
    drawCircle(if (dark) Color(0xFF151518) else Color(0xFF2A2A30), r * 1.12f, c)
    if (look.on) {
        val n = look.leds; val a0 = 0.25f + 0.75f * look.brightness
        // one color stop per LED (LED 0 at the top, clockwise), its brightness as alpha; wrap round
        val stops = List(n) { j -> val (col, k) = ledColor(look, j, n, tMs, reverse); col.copy(alpha = (a0 * k).coerceIn(0.03f, 1f)) }
        val brush = Brush.sweepGradient(stops + stops.first(), c)
        rotate(-90f, c) {                  // the sweep starts at 3 o'clock; LED 0 belongs at the top
            // (a slow device draws two of the six soft rings, a little stronger: each is a wide gradient-filled stroke)
            for (i in 6 downTo 1) if (!slow || i == 6 || i == 3) drawCircle(brush, r * (1.0f + i * 0.06f), c, alpha = 0.07f * (7 - i) / 6f * (if (slow) 1.8f else 1f), style = Stroke(r * 0.08f * i))
            drawCircle(brush, r, c, style = Stroke(r * 0.12f))
        }
    } else drawCircle(Color(0xFF2C2C30), r, c, style = Stroke(r * 0.12f))
    drawCircle(if (dark) Color(0xFF0E0E10) else Color(0xFF1E1E22), r * 0.93f, c)
    if (!blades) return                    // (a laptop's screen shows the light ring, no fan)
    // Motion blur: the blades drawn a few more times just behind where they are, fading out — so a
    // 30 fps picture reads as a smooth spin instead of a stutter.
    val blade = bladeCache.get(c, r)
    val trail = if (spin == 0f) TRAIL_STILL else if (slow) TRAIL_SLOW else TRAIL
    trail.forEach { (d, a) -> rotate(spin + d, c) {
        for (b in 0 until 7) rotate(b * 360f / 7, c) { drawPath(blade, Color(0xFF3A3A40).copy(alpha = a)) }
    } }
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
@Composable private fun serverClock(skewMs: Long, still: Boolean = false): State<Double> {
    if (still || reduceMotion()) return remember(skewMs) { mutableDoubleStateOf((System.currentTimeMillis() + skewMs).toDouble()) }
    UseAmbient()           // ~30 fps is plenty for a slow light wave (~15 on older devices), and it beats together with the orb
    return remember(skewMs) { derivedStateOf { (Ambient.ms + skewMs).toDouble() } }
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

/** The shapes the home picture comes in (Settings → Server → Looks like) — the same as Nova web's hero.js. */
val LOOKS = listOf("tower" to "Tower", "mini" to "Mini PC", "nas" to "NAS", "rack" to "Rack server", "laptop" to "Laptop")

private class Bar(val x: Float, val y: Float, val w: Float, val h: Float, val base: Color = Color(0xFF202026), val keys: Boolean = false)
private class FanSpot(val c: Offset, val r: Float, val blades: Boolean = true, val squash: Float = 1f)
/** Where a shape's light, strips, one LED per drive and power LED sit, and what a tap hits — same numbers as hero.js. */
private class HeroLayout(val model: String, val left: Float, val top: Float, val bw: Float, val bh: Float, val fan: FanSpot?, val bar: Bar?,
                         val strips: List<Bar>, val leds: List<Offset>, val lr: Float, val power: Offset, val pr: Float,
                         val hitPower: Float, val hitDrives: androidx.compose.ui.geometry.Rect, val hitFan: List<androidx.compose.ui.geometry.Rect>,
                         val hitFanCircle: Float = 0f, val x: Map<String, Float> = emptyMap())
private fun rectOf(x0: Float, y0: Float, x1: Float, y1: Float) = androidx.compose.ui.geometry.Rect(x0, y0, x1, y1)
private fun rowLeds(n: Int, cx: Float, y: Float, span: Float, maxStep: Float): Pair<List<Offset>, Float> {
    val step = if (n > 1) min(maxStep, span / (n - 1)) else 0f; val x0 = cx - step * (n - 1) / 2
    return List(n) { Offset(x0 + it * step, y) } to step
}
private fun heroLayout(model: String, w: Float, h: Float, n: Int): HeroLayout = when (model) {
    "mini" -> {
        val bw = min(w * .8f, h * 1.5f); val fh = bw * .3f; val th = bw * .17f; val left = (w - bw) / 2; val top = (h - fh - th) / 2; val fy = top + th
        val (leds, step) = rowLeds(n, left + bw * .2f, fy + fh * .46f, bw * .22f, bw * .04f)
        HeroLayout(model, left, top, bw, fh + th, FanSpot(Offset(left + bw / 2, top + th * .5f), bw * .15f, true, (th * .78f) / (bw * .3f)), null,
            listOf(Bar(left + bw * .06f, fy + fh * .8f, bw * .88f, fh * .07f)), leds, max(bw * .006f, min(bw * .011f, step * .3f)), Offset(left + bw * .9f, fy + fh * .46f), bw * .011f,
            bw * .05f, rectOf(left + bw * .06f, fy + fh * .25f, left + bw * .34f, fy + fh * .68f), listOf(rectOf(left, top, left + bw, top + th * 1.05f), rectOf(left + bw * .05f, fy + fh * .72f, left + bw * .95f, fy + fh * .95f)),
            x = mapOf("th" to th, "fy" to fy, "fh" to fh))
    }
    "nas" -> {
        val bw = min(w * .58f, h * .64f); val bh = bw * 1.1f; val left = (w - bw) / 2; val top = (h - bh) / 2
        val bays = n.coerceIn(2, 8); val ax = left + bw * .07f; val aw = bw * .86f; val gap = aw * .025f; val bayW = (aw - gap * (bays - 1)) / bays
        val ay = top + bh * .2f; val ah = bh * .72f
        val leds = List(min(n, bays)) { Offset(ax + it * (bayW + gap) + bayW / 2, ay + ah * .08f) }
        HeroLayout(model, left, top, bw, bh, null, Bar(left + bw * .36f, top + bh * .075f, bw * .56f, bh * .03f), emptyList(), leds,
            max(bw * .008f, min(bw * .014f, bayW * .09f)), Offset(left + bw * .13f, top + bh * .09f), bw * .016f,
            bw * .07f, rectOf(ax, ay, ax + aw, ay + ah), listOf(rectOf(left + bw * .32f, top + bh * .03f, left + bw * .96f, top + bh * .15f)),
            x = mapOf("bays" to bays.toFloat(), "ax" to ax, "gap" to gap, "bayW" to bayW, "ay" to ay, "ah" to ah))
    }
    "rack" -> {
        val bw = min(w * .92f, h * 2.1f); val lid = bw * .12f; val bh = bw * .3f; val left = (w - bw) / 2; val top = (h - bh - lid) / 2 + lid; val ear = bw * .035f
        val bays = n.coerceIn(2, 12); val cols = (bays + 1) / 2; val ax = left + ear + bw * .03f; val aw = bw * .54f; val gap = bw * .008f
        val bayW = (aw - gap * (cols - 1)) / cols; val rowH = bh * .3f; val ay = top + bh * .14f
        val leds = List(min(n, bays)) { i -> Offset(ax + (i % cols) * (bayW + gap) + bayW * .86f, ay + (i / cols) * (rowH + bh * .05f) + rowH / 2) }
        val fx = left + bw * .77f; val fy = top + bh * .45f
        HeroLayout(model, left, top, bw, bh, FanSpot(Offset(fx, fy), bh * .27f), null, listOf(Bar(ax, top + bh * .83f, bw - 2 * ear - bw * .06f, bh * .05f)), leds,
            max(bw * .0035f, min(bw * .0065f, bayW * .05f)), Offset(left + bw - ear - bw * .045f, top + bh * .3f), bw * .007f,
            bw * .035f, rectOf(ax, ay, ax + aw, ay + rowH * 2 + bh * .05f), listOf(rectOf(fx - bh * .4f, top, fx + bh * .4f, top + bh * .8f), rectOf(ax, top + bh * .8f, left + bw - ear, top + bh * .92f)),
            x = mapOf("lid" to lid, "ear" to ear, "bays" to bays.toFloat(), "cols" to cols.toFloat(), "ax" to ax, "gap" to gap, "bayW" to bayW, "rowH" to rowH, "ay" to ay))
    }
    "laptop" -> {
        val lw = min(w * .72f, h * .8f / .82f); val lh = lw * .64f; val dh = lw * .14f; val ex = lw * .08f; val left = (w - lw) / 2; val top = (h - lh - dh) / 2; val by = top + lh
        val (leds, step) = rowLeds(n, left + lw * .1f, by + dh * .86f, lw * .14f, lw * .028f)
        HeroLayout(model, left, top, lw, lh + dh, FanSpot(Offset(left + lw / 2, top + lh * .48f), lh * .22f, blades = false), null,
            listOf(Bar(left + lw * .14f, by + dh * .16f, lw * .72f, dh * .4f, Color(0xFF18181C), keys = true)), leds, max(lw * .004f, min(lw * .008f, step * .3f)),
            Offset(left + lw * .97f, by + dh * .86f), lw * .008f,
            lw * .04f, rectOf(left - ex * .3f, by + dh * .7f, left + lw * .24f, by + dh * 1.05f), listOf(rectOf(left, top, left + lw, top + lh), rectOf(left + lw * .1f, by, left + lw * .9f, by + dh * .62f)),
            x = mapOf("lh" to lh, "by" to by, "dh" to dh, "ex" to ex))
    }
    else -> {
        val cw = min(w * .56f, h * .62f); val ch = cw * 1.42f; val left = (w - cw) / 2; val top = (h - ch) / 2
        val (leds, step) = rowLeds(n, left + cw / 2, top + ch * .83f, cw * .62f, cw * .09f)
        HeroLayout("tower", left, top, cw, ch, FanSpot(Offset(left + cw / 2, top + ch * .33f), cw * .29f), null,
            listOf(Bar(left + cw * .16f, top + ch * .62f, cw * .68f, ch * .07f, Color(0xFF26262C)), Bar(left + cw * .16f, top + ch * .72f, cw * .68f, ch * .05f, Color(0xFF1F1F25))),
            leds, max(cw * .008f, min(cw * .016f, step * .32f)), Offset(left + cw * .5f, top + ch * .935f), cw * .016f,
            cw * .07f, rectOf(left + cw * .12f, top + ch * .785f, left + cw * .88f, top + ch * .875f), listOf(rectOf(left + cw * .14f, top + ch * .6f, left + cw * .86f, top + ch * .78f)), hitFanCircle = cw * .36f)
    }
}

/** The layout for the last size and shape asked for (the live layer draws ~30 times a second; the layout only changes with them). */
private class LayoutCache { private var key = ""; private var last: HeroLayout? = null
    fun get(model: String, w: Float, h: Float, n: Int): HeroLayout { val k = "$model/$w/$h/$n"; if (k != key || last == null) { last = heroLayout(model, w, h, n); key = k }; return last!! } }

private fun DrawScope.heroBody(L: HeroLayout, dark: Boolean) {
    val left = L.left; val top = L.top; val bw = L.bw; val bh = L.bh
    drawOval(Color.Black.copy(alpha = if (dark) 0.6f else 0.18f), Offset(left + bw * .04f, top + bh - max(4f, bh * .03f)), Size(bw * .92f, max(8f, bh * .06f)))
    fun poly(vararg p: Pair<Float, Float>) = Path().apply { moveTo(p[0].first, p[0].second); for (q in p.drop(1)) lineTo(q.first, q.second); close() }
    fun vgrad(y0: Float, y1: Float, vararg c: Long) = Brush.verticalGradient(c.map { Color(it) }, y0, y1)
    when (L.model) {
        "mini" -> {
            val th = L.x["th"]!!; val fy = L.x["fy"]!!; val fh = L.x["fh"]!!; val inset = bw * .05f
            val lid = poly((left + inset) to (top), (left + bw - inset) to (top), (left + bw) to (fy), (left) to (fy))
            drawPath(lid, vgrad(top, fy, 0xFF55555E, 0xFF3A3A42)); drawPath(lid, Color.White.copy(alpha = .12f), style = Stroke(1.5f))
            val f = L.fan!!; drawOval(Color.Black.copy(alpha = .35f), Offset(f.c.x - f.r * 1.18f, f.c.y - f.r * 1.18f * f.squash), Size(f.r * 2.36f, f.r * 2.36f * f.squash))
            val front = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(left, fy, left + bw, fy + fh, CornerRadius.Zero, CornerRadius.Zero, CornerRadius(bw * .035f), CornerRadius(bw * .035f))) }
            drawPath(front, vgrad(fy, fy + fh, 0xFF2C2C33, 0xFF1B1B20)); drawPath(front, Color.White.copy(alpha = .08f), style = Stroke(1.5f))
            listOf(.52f to .07f, .6f to .07f, .68f to .045f).forEach { (x, wd) -> drawRoundRect(Color(0xFF0D0D10), Offset(left + bw * x, fy + fh * .38f), Size(bw * wd, fh * .17f), CornerRadius(2f)) }
            drawCircle(Color(0xFF0D0D10), fh * .07f, Offset(left + bw * .77f, fy + fh * .465f))
        }
        "nas" -> {
            drawRoundRect(vgrad(top, top + bh, 0xFF3C3C44, 0xFF1C1C21), Offset(left, top), Size(bw, bh), CornerRadius(bw * .05f))
            drawRoundRect(Color.White.copy(alpha = .10f), Offset(left, top), Size(bw, bh), CornerRadius(bw * .05f), style = Stroke(2f))
            val b = L.bar!!; drawRoundRect(Color(0xFF121216), Offset(b.x - bw * .02f, b.y - bh * .02f), Size(b.w + bw * .04f, b.h + bh * .04f), CornerRadius(bh * .03f))
            val bays = L.x["bays"]!!.toInt(); val ax = L.x["ax"]!!; val gap = L.x["gap"]!!; val bayW = L.x["bayW"]!!; val ay = L.x["ay"]!!; val ah = L.x["ah"]!!
            for (i in 0 until bays) {
                val x = ax + i * (bayW + gap)
                drawRoundRect(vgrad(ay, ay + ah, 0xFF2B2B32, 0xFF202026), Offset(x, ay), Size(bayW, ah), CornerRadius(min(8f, bayW * .12f)))
                drawRoundRect(Color.White.copy(alpha = .06f), Offset(x, ay), Size(bayW, ah), CornerRadius(min(8f, bayW * .12f)), style = Stroke(1f))
                for (k in 0 until 6) drawRoundRect(Color(0xFF16161A), Offset(x + bayW * .22f, ay + ah * (.3f + k * .07f)), Size(bayW * .56f, ah * .025f), CornerRadius(2f))
                drawRoundRect(Color(0xFF3A3A42), Offset(x + bayW * .15f, ay + ah * .86f), Size(bayW * .7f, ah * .05f), CornerRadius(4f))
            }
        }
        "rack" -> {
            val lid = L.x["lid"]!!; val ear = L.x["ear"]!!; val inset = bw * .04f
            val top2 = poly((left + ear + inset) to (top - lid), (left + bw - ear - inset) to (top - lid), (left + bw - ear) to (top), (left + ear) to (top))
            drawPath(top2, vgrad(top - lid, top, 0xFF4C4C55, 0xFF34343C)); drawPath(top2, Color.White.copy(alpha = .10f), style = Stroke(1f))
            for (k in 1 until 6) { val yy = top - lid + lid * k / 6; val ins = inset * (1 - k / 6f)
                drawLine(Color.Black.copy(alpha = .25f), Offset(left + ear + ins + bw * .02f, yy), Offset(left + bw - ear - ins - bw * .02f, yy), 1f) }
            listOf(left, left + bw - ear).forEach { x -> drawRoundRect(Color(0xFF2E2E35), Offset(x, top - bh * .04f), Size(ear, bh * 1.08f), CornerRadius(3f))
                listOf(.12f, .88f).forEach { y -> drawCircle(Color(0xFF121216), ear * .18f, Offset(x + ear / 2, top + bh * y)) } }
            drawRoundRect(vgrad(top, top + bh, 0xFF404049, 0xFF1E1E23), Offset(left + ear, top), Size(bw - 2 * ear, bh), CornerRadius(4f))
            drawRoundRect(Color.White.copy(alpha = .10f), Offset(left + ear, top), Size(bw - 2 * ear, bh), CornerRadius(4f), style = Stroke(1.5f))
            val bays = L.x["bays"]!!.toInt(); val cols = L.x["cols"]!!.toInt(); val ax = L.x["ax"]!!; val gap = L.x["gap"]!!; val bayW = L.x["bayW"]!!; val rowH = L.x["rowH"]!!; val ay = L.x["ay"]!!
            for (i in 0 until bays) { val x = ax + (i % cols) * (bayW + gap); val y = ay + (i / cols) * (rowH + bh * .05f)
                drawRoundRect(Color(0xFF24242A), Offset(x, y), Size(bayW, rowH), CornerRadius(3f))
                drawRoundRect(Color(0xFF18181C), Offset(x + bayW * .08f, y + rowH * .3f), Size(bayW * .62f, rowH * .4f), CornerRadius(2f)) }
            val f = L.fan!!; var gy = top + bh * .12f
            while (gy < top + bh * .78f) { var gx = f.c.x - bh * .42f; while (gx < f.c.x + bh * .42f) { drawCircle(Color.Black.copy(alpha = .45f), bh * .014f, Offset(gx, gy)); gx += bh * .07f }; gy += bh * .07f }
        }
        "laptop" -> {
            val lw = bw; val lh = L.x["lh"]!!; val by = L.x["by"]!!; val dh = L.x["dh"]!!; val ex = L.x["ex"]!!
            drawPath(poly((left) to (by), (left + lw) to (by), (left + lw + ex) to (by + dh * .82f), (left - ex) to (by + dh * .82f)), vgrad(by, by + dh, 0xFF5A5A63, 0xFF2A2A30))
            drawPath(poly((left - ex) to (by + dh * .82f), (left + lw + ex) to (by + dh * .82f), (left + lw + ex * .96f) to (by + dh), (left - ex * .96f) to (by + dh)), Color(0xFF1E1E23))
            drawRoundRect(Color.White.copy(alpha = .12f), Offset(left + lw * .4f, by + dh * .6f), Size(lw * .2f, dh * .14f), CornerRadius(3f))
            drawRoundRect(vgrad(top, by, 0xFF3A3A42, 0xFF24242A), Offset(left, top), Size(lw, lh), CornerRadius(lw * .03f))
            drawRoundRect(Color.White.copy(alpha = .10f), Offset(left, top), Size(lw, lh), CornerRadius(lw * .03f), style = Stroke(1.5f))
            val b = lw * .03f
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFF0C1020), Color(0xFF141A2A)), Offset(left, top), Offset(left + lw, by)), Offset(left + b, top + b), Size(lw - 2 * b, lh - 2 * b), CornerRadius(lw * .012f))
            drawCircle(Color(0xFF0A0A0C), b * .18f, Offset(left + lw / 2, top + b / 2))
        }
        else -> {
            drawRoundRect(vgrad(top, top + bh, 0xFF4A4A52, 0xFF26262B, 0xFF1B1B1F), Offset(left, top), Size(bw, bh), CornerRadius(bw * 0.07f))
            drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(left, top), Size(bw, bh), CornerRadius(bw * 0.07f), style = Stroke(2.5f))
            val gp = bw * 0.07f
            drawRoundRect(Brush.linearGradient(listOf(Color(0xFF101014), Color(0xFF17171C)), Offset(left, top), Offset(left + bw, top + bh)),
                Offset(left + gp, top + gp), Size(bw - 2 * gp, bh - 2 * gp * 1.6f), CornerRadius(bw * 0.04f))
        }
    }
}

/**
 * The home-screen picture: the machine Nova runs on (Settings → Server → Looks like: a tower, a mini PC, a NAS, a rack
 * server or a laptop), with its live light, its LED strips, one LED per drive (boot drive included) and the power LED in the
 * status color. Drive LEDs: green with activity when healthy, amber when worn or hot, red (pulsing) when missing or failing.
 */
@Composable fun ServerHero(fan: JSONObject?, statusColor: Color, drives: List<JSONObject>?, skewMs: Long, modifier: Modifier = Modifier, io: JSONObject? = null, model: String = "tower",
                          still: Boolean = false) {        // still: one frame, no clock (the picker's five previews would otherwise all animate)
    val look = fanLook(fan); val dark = N.dark; val reverse = AppPrefs.fanReverse; val slow = lite()
    val t by serverClock(skewMs, still); val blade = remember { BladeCache() }; val layouts = remember { LayoutCache() }
    val list = drives?.takeIf { it.isNotEmpty() }; val n = list?.size ?: 6
    Box(modifier) {
    // the body never changes: drawn once in a layer of its own, so each tick of the light doesn't redraw it
    Canvas(Modifier.fillMaxSize().graphicsLayer {}) { heroBody(heroLayout(model, size.width, size.height, n), dark) }
    Canvas(Modifier.fillMaxSize().graphicsLayer {}) {
        val L = layouts.get(model, size.width, size.height, n)
        val spin = if (still || reduceMotion()) 0f else ((t % 2200.0) / 2200.0 * 360).toFloat()
        L.fan?.let { f ->
            scale(1f, f.squash, f.c) {
                if (look.on) drawCircle(Brush.radialGradient(listOf(ringGlow(look, t, reverse).copy(alpha = 0.18f + 0.22f * look.brightness), Color.Transparent), f.c, f.r * 2.05f), f.r * 2.05f, f.c)
                fanRing(f.c, f.r, look, t, spin, dark, reverse, blade, slow, f.blades)
            }
        }
        L.bar?.let { b ->                                        // a NAS: its light is a bar across the top
            drawRoundRect(Color(0xFF1A1A1F), Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(b.h / 2))
            if (look.on) clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(b.x, b.y, b.x + b.w, b.y + b.h, CornerRadius(b.h / 2))) }) {
                val k = 16; val seg = b.w / k
                for (j in 0 until k) { val (col, a) = ledColor(look, j, k, t, reverse); val al = a.coerceAtLeast(.08f) * (.3f + .7f * look.brightness)
                    val c0 = Offset(b.x + seg * (j + .5f), b.y + b.h / 2); drawCircle(Brush.radialGradient(listOf(col.copy(alpha = al), Color.Transparent), c0, seg * 1.4f), seg * 1.4f, c0) }
            }
        }
        // the bars: plain, or your LED strips (Lighting → your lights), showing what they show (a laptop's: its keyboard backlight)
        val strips = fan?.optJSONArray("zones")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }?.filter { it.optString("shape") == "strip" }?.take(2) ?: emptyList()
        L.strips.forEachIndexed { k, b ->
            drawRoundRect(b.base, Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(min(8f, b.h / 2)))
            val z = strips.getOrNull(k) ?: if (b.keys && look.on && fan != null) fan else null
            if (z != null && (z.optBoolean("on", true) || z === fan)) clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(b.x, b.y, b.x + b.w, b.y + b.h, CornerRadius(min(8f, b.h / 2)))) }) {
                val zl = fanLook(z); val n2 = z.optInt("led_count", 20).coerceIn(6, 30); val seg = (b.w - 8f) / n2
                for (j in 0 until n2) {
                    val (col, a) = ledColor(zl, j, n2, t, false)
                    val al = a.coerceAtLeast(0.15f) * (0.35f + 0.65f * zl.brightness) * (if (b.keys) .55f else 1f)
                    val c0 = Offset(b.x + 4f + seg * (j + 0.5f), b.y + b.h / 2)
                    drawCircle(Brush.radialGradient(listOf(col.copy(alpha = al), Color.Transparent), c0, seg * 1.3f), seg * 1.3f, c0)
                }
                if (b.keys) for (r in 0 until 4) for (q in 0 until 14)          // the keys over the backlight
                    drawRoundRect(Color(0xD10A0A0C), Offset(b.x + b.w * q / 14f + 1f, b.y + b.h * r / 4f + 1f), Size(b.w / 14f - 2f, b.h / 4f - 2f), CornerRadius(1.5f))
            }
        }
        // one LED per drive
        L.leds.forEachIndexed { i, p ->
            val lvl = list?.get(i)?.optString("level") ?: "ok"
            val (col, a) = when (lvl) {
                "critical" -> Color(0xFFFF4040) to (0.35f + 0.65f * (0.5f + 0.5f * sin(t / 1800.0 * 2 * PI).toFloat()))
                "warning" -> Color(0xFFFFB020) to (0.45f + 0.55f * activity(i, t, blinkRate(io?.optJSONArray(list?.get(i)?.optString("name") ?: ""))))   // amber still shows activity
                else -> Color(0xFF3ECF6E) to (0.38f + 0.62f * activity(i, t, blinkRate(io?.optJSONArray(list?.get(i)?.optString("name") ?: ""))))
            }
            if (a > 0.5f) drawCircle(col.copy(alpha = (a - 0.5f) * 0.6f), L.lr * 2.6f, p)
            drawCircle(col.copy(alpha = a), L.lr, p)
        }
        // glass: over the whole window, fading out by itself (a rectangle cut short of the fade left a hard line down the middle)
        if (L.model == "tower") { val gp = L.bw * 0.07f
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), Offset(L.left, L.top), Offset(L.left + L.bw * 0.5f, L.top + L.bh * 0.5f)),
                Offset(L.left + gp, L.top + gp), Size(L.bw - 2 * gp, L.bh - 2 * gp * 1.6f), CornerRadius(L.bw * 0.04f)) }
        if (L.model == "laptop") { val b = L.bw * .03f; val lh = L.x["lh"]!!
            drawRoundRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Transparent), Offset(L.left, L.top), Offset(L.left + L.bw * .5f, L.top + lh * .6f)),
                Offset(L.left + b, L.top + b), Size(L.bw - 2 * b, lh - 2 * b), CornerRadius(L.bw * .012f)) }
        if (L.model != "tower") drawCircle(Color.White.copy(alpha = .18f), L.pr * 2.1f, L.power, style = Stroke(1.2f))     // the button around the light
        drawCircle(statusColor.copy(alpha = 0.35f), L.pr * 1.9f, L.power)
        drawCircle(statusColor, L.pr, L.power)
    }
    }
}

/** Big fan for the Lighting page. */
@Composable fun FanHero(fan: JSONObject?, skewMs: Long, modifier: Modifier = Modifier) {
    val look = fanLook(fan); val dark = N.dark; val reverse = AppPrefs.fanReverse; val slow = lite()
    val t by serverClock(skewMs); val blade = remember { BladeCache() }
    Canvas(modifier) {
        val r = size.minDimension * 0.34f; val c = Offset(size.width / 2, size.height / 2)
        val spin = if (reduceMotion()) 0f else ((t % 2200.0) / 2200.0 * 360).toFloat()
        if (look.on) drawCircle(Brush.radialGradient(listOf(ringGlow(look, t, reverse).copy(alpha = 0.2f + 0.25f * look.brightness), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
        fanRing(c, r, look, t, spin, dark, reverse, blade, slow)
    }
}


/** Which shape this server's picture has (its setting, else what its firmware says it is; an older server: the tower). */
fun heroLook(app: AppState): String = app.overview?.optJSONObject("server")?.optString("look")?.takeIf { v -> LOOKS.any { it.first == v } } ?: "tower"

/** What's under a tap on the home picture: "drives" (the drive lights), "power" (the power button), "fan", or null. */
fun heroHit(w: Float, h: Float, x: Float, y: Float, model: String = "tower", drives: Int = 6): String? {
    val L = heroLayout(model, w, h, drives.coerceAtLeast(1)); val p = Offset(x, y)
    return when {
        (p - L.power).getDistance() < L.hitPower -> "power"
        L.hitDrives.contains(p) -> "drives"
        L.hitFanCircle > 0f && L.fan != null && (p - L.fan.c).getDistance() < L.hitFanCircle -> "fan"
        L.hitFan.any { it.contains(p) } -> "fan"
        else -> null
    }
}
