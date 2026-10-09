package app.novalabs.nova

import kotlin.math.*

/**
 * The fan's software effects, ported line for line from the server (nova_rgb.frame), so the app's
 * picture shows exactly what the fan shows at the same moment. Colors are 0..255 RGB triples at
 * full brightness. Keep in sync with server/modules/fan-gigabyte-fusion2/nova_rgb.py.
 */
object LightFx {
    val SOFTWARE = setOf("wave", "comet", "scanner", "twinkle", "fire", "breathe")
    /** Controller effects that also take a palette: with 2+ colors Nova draws them itself (same as the server). */
    val PALETTE_FX = setOf("static", "pulse", "blink", "cycle", "gradient")
    fun usesPalette(effect: String, palette: List<String>, rainbow: Boolean) =
        effect in PALETTE_FX && palette.count { it.isNotEmpty() } >= 2 && !(effect == "cycle" && rainbow)
    fun isSoftware(effect: String, palette: List<String>, rainbow: Boolean) = effect in SOFTWARE || usesPalette(effect, palette, rainbow)
    private val FIRE = listOf("#200000", "#ff1800", "#ff6000", "#ffb000", "#fff0a0")
    data class Fx(val effect: String, val leds: Int, val speed: Int, val rainbow: Boolean, val palette: List<String>, val color: String, val color2: String)

    fun periodMs(speed: Int) = round(10000.0 * 0.02.pow((speed.coerceIn(1, 100) - 1) / 99.0))

    /** 32-bit integer hash, identical to the server's _h (Python) and the web's. */
    fun h(a: Int, b: Long): Int {
        var x = ((a * 73856093) xor (b.toInt() * 19349663)) and 0x7fffffff
        x = x xor (x ushr 13)
        x = (x * 1274126177) and 0x7fffffff
        return x xor (x ushr 16)
    }
    fun hexRgb(s: String): IntArray { val h = s.removePrefix("#").padEnd(6, '0'); return intArrayOf(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16)) }
    /** Python's x % 1.0 (never negative), bit for bit. */
    private fun pmod(x: Double): Double { val r = x % 1.0; return if (r < 0) r + 1.0 else r }
    private fun pyRound(x: Double): Int = Math.rint(x).toInt()                 // Python's round(): halves to even
    private fun hsv(x: Double): IntArray {
        val hh = pmod(x); val i = floor(hh * 6).toInt(); val f = hh * 6 - i; val q = 1 - f
        val (r, g, b) = when (i % 6) { 0 -> Triple(1.0, f, 0.0); 1 -> Triple(q, 1.0, 0.0); 2 -> Triple(0.0, 1.0, f); 3 -> Triple(0.0, q, 1.0); 4 -> Triple(f, 0.0, 1.0); else -> Triple(1.0, 0.0, q) }
        return intArrayOf(pyRound(r * 255), pyRound(g * 255), pyRound(b * 255))
    }
    /** Fire in one color: from nearly black, through the color, to almost white (same as the server). */
    private fun tintFlame(c: IntArray): List<IntArray> {
        fun k(f: Double) = IntArray(3) { pyRound(c[it] * f) }
        fun w(f: Double) = IntArray(3) { pyRound(c[it] + (255 - c[it]) * f) }
        return listOf(k(0.08), k(0.55), c, w(0.45), w(0.8))
    }
    private fun pal(fx: Fx): List<IntArray>? {
        val p = fx.palette.filter { it.isNotEmpty() }
        if (fx.effect == "fire") {          // Flame (rainbow on) · Palette: your own heat ramp · One color: a flame in it
            if (p.size >= 2) return p.map { hexRgb(it) }
            if (fx.rainbow) return FIRE.map { hexRgb(it) }
            return tintFlame(hexRgb(fx.color.ifEmpty { "#ff6000" }))
        }
        if (fx.rainbow) return null
        if (p.size >= 2) return p.map { hexRgb(it) }
        return listOf(hexRgb(fx.color.ifEmpty { "#3e91ff" }))
    }
    private fun at(pal: List<IntArray>?, x0: Double): IntArray {
        val x = pmod(x0)
        if (pal == null) return hsv(x)
        if (pal.size == 1) return pal[0]
        val f = x * pal.size; val i = f.toInt() % pal.size; val k = f - f.toInt(); val a = pal[i]; val b = pal[(i + 1) % pal.size]
        return IntArray(3) { pyRound(a[it] + (b[it] - a[it]) * k) }
    }
    private fun heat(pal: List<IntArray>, x0: Double): IntArray {
        val x = x0.coerceIn(0.0, 0.999); val f = x * (pal.size - 1); val i = f.toInt(); val k = f - i; val a = pal[i]; val b = pal[min(i + 1, pal.size - 1)]
        return IntArray(3) { pyRound(a[it] + (b[it] - a[it]) * k) }
    }
    private fun sc(c: IntArray, k: Double) = IntArray(3) { pyRound(c[it] * k.coerceIn(0.0, 1.0)) }

    /** LED colors for a software effect at server time [t] (seconds). */
    fun frame(fx: Fx, t: Double): List<IntArray> {
        val n = max(1, fx.leds); val per = periodMs(fx.speed) / 1000; val p = pal(fx)
        if (usesPalette(fx.effect, fx.palette, fx.rainbow)) {
            val pp = fx.palette.filter { it.isNotEmpty() }.map { hexRgb(it) }; val c = floor(t / per).toLong()
            when (fx.effect) {
                "static", "gradient" -> return List(n) { at(pp, it.toDouble() / n) }
                "pulse" -> { val k = 0.06 + 0.94 * (0.5 - 0.5 * cos(2 * PI * ((t / per) % 1.0))); return List(n) { sc(pp[(c % pp.size).toInt()], k) } }
                "blink" -> return List(n) { if ((t / per) % 1.0 < 0.5) pp[(c % pp.size).toInt()] else intArrayOf(0, 0, 0) }
                "cycle" -> return List(n) { at(pp, t / (per * 2 * pp.size)) }
            }
        }
        return when (fx.effect) {
            "wave" -> { val ph = (t / (per * 1.5)) % 1.0
                if (p == null || p.size >= 2) List(n) { at(p, it.toDouble() / n + ph) }
                else List(n) { j -> val x = (cos(2 * PI * (j.toDouble() / n - ph)) + 1) / 2; sc(p[0], 0.08 + 0.92 * x.pow(3)) } }
            "comet" -> { val head = (t / (per * 1.5)) % 1.0 * n
                List(n) { j -> val d = ((head - j) % n).let { if (it < 0) it + n else it }; val k = max(0.0, 1 - d / (n * 0.6)).pow(2); sc(at(p, head / n), max(k, 0.03)) } }
            "scanner" -> { val q = (t / (per * 1.5)) % 1.0; val pos = (if (q < 0.5) q * 2 else 2 - q * 2) * (n - 1)
                List(n) { j -> sc(at(p, j.toDouble() / n), max(0.03, max(0.0, 1 - abs(j - pos) / 2.2).pow(1.5))) } }
            "twinkle" -> List(n) { j ->
                val L = per * 1.5; val loc = t / L + (h(j, 7) % 1000) / 1000.0; val c = floor(loc).toLong(); val f = loc - c; val r = h(j, c)
                val k = if (r % 100 < 55) sin(PI * f).pow(2) else 0.0
                val col = if (p == null || p.size > 1) at(p, (r % 997) / 997.0) else p[0]
                sc(col, max(0.05, k)) }
            "fire" -> List(n) { j ->
                val q = t / 0.12; val c = floor(q).toLong(); var f = q - c; f = f * f * (3 - 2 * f)
                val a = (h(j, c) % 1000) / 1000.0; val b = (h(j, c + 1) % 1000) / 1000.0
                val v = 0.45 + 0.55 * (a + (b - a) * f); sc(heat(p!!, v), 0.35 + 0.65 * v) }
            "breathe" -> { val T = per * 2; val c = floor(t / T).toLong(); val k = 0.06 + 0.94 * (0.5 - 0.5 * cos(2 * PI * ((t / T) % 1.0)))
                val col = if (p == null) at(null, (c % 12) / 12.0) else p[(c % p.size).toInt()]; List(n) { sc(col, k) } }
            else -> List(n) { hexRgb(fx.color.ifEmpty { "#ffffff" }) }
        }
    }
}
