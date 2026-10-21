package app.novalabs.nova

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.*

/**
 * Living wallpaper (Appearance → Wallpaper, Beta): an animated wallpaper for each look, in your accent color — the same
 * scenes as Nova web's living.js. Aero: ribbons of light and drifting bokeh. Mica: a slowly turning bloom. Breeze: flowing
 * waves. Glass: liquid color moving under the glass. Material: soft shapes floating by. Default: a drifting nebula.
 * One clock for the whole app ticks 30 times a second (paused with the app, still with Reduce motion); every layer that
 * paints the background draws its part of the same scene in screen coordinates, so there are no seams — and Glass cards
 * bend the living scene, not a still one. A picture you set shows instead.
 */
object Living {
    private lateinit var sp: android.content.SharedPreferences
    var on by mutableStateOf(false); private set
    var time by mutableFloatStateOf(20f)                // seconds of scene time
    @Volatile var paused = false                         // during a back gesture (two pages draw the scene at once)
    var scene by mutableStateOf("auto"); private set     // auto = match the look, or one of SCENES
    var speed by mutableIntStateOf(100); private set     // % of normal
    var accent by mutableStateOf(""); private set        // its own color, or "" for the accent
    val SCENES = linkedMapOf("default" to "Nebula", "aero" to "Harmony (Aero)", "fluent" to "Bloom (Mica)", "breeze" to "Flow (Breeze)", "liquid" to "Liquid (Glass)", "material" to "Shapes (Material)")
    fun init(ctx: Context) { sp = ctx.getSharedPreferences("nova_ui", Context.MODE_PRIVATE); on = sp.getBoolean("living_wall", false)
        scene = sp.getString("living_scene", "auto")!!; speed = sp.getInt("living_speed", 100); accent = sp.getString("living_accent", "")!! }
    fun set(v: Boolean) { on = v; sp.edit().putBoolean("living_wall", v).apply() }
    fun chooseScene(v: String) { scene = v; sp.edit().putString("living_scene", v).apply() }
    fun chooseSpeed(v: Int) { speed = v; sp.edit().putInt("living_speed", v).apply() }
    fun chooseAccent(v: String) { accent = v; sp.edit().putString("living_accent", v).apply() }
    /** The scene in use: the one picked, or the look's own. */
    fun sceneFor(t: NovaColors) = if (scene != "auto") scene else if (t.material) "material" else t.preset.ifEmpty { "default" }
    /** Drawn where the glow would be: on, and no picture set. */
    val showing get() = on && AppPrefs.wallV == 0L
}

/** The app-wide clock. Put once near the root. */
@Composable fun LivingTicker() {
    if (!Living.showing || reduceMotion()) return
    LaunchedEffect(Unit) {
        var last = 0L; var t0 = 0L
        while (true) {
            withFrameNanos { now ->
                if (t0 == 0L) t0 = now - (Living.time * 1e9f / speed()).toLong()
                if (now - last >= (if (AppPrefs.lite == "on" || (AppPrefs.lite == "auto" && AppPrefs.slowDevice)) 66_000_000L else 33_000_000L)) { last = now; if (Living.paused) t0 += 33_000_000L else Living.time = (now - t0) / 1e9f * speed() }   // Lighter effects: 15 a second
            }
        }
    }
}
private fun speed() = (Living.speed / 100f).coerceIn(.1f, 3f)

private fun hsl(h: Float, s: Float, l: Float, a: Float = 1f) = Color.hsl(((h % 360) + 360) % 360, (s / 100f).coerceIn(0f, 1f), (l / 100f).coerceIn(0f, 1f), a.coerceIn(0f, 1f))
private fun hueSat(c: Color): Pair<Float, Float> {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    val mx = maxOf(c.red, c.green, c.blue); val mn = minOf(c.red, c.green, c.blue); val l = (mx + mn) / 2; val d = mx - mn
    val s = if (d == 0f) 0f else d / (1 - abs(2 * l - 1))
    return hsv[0] to (s * 100).coerceIn(35f, 90f)
}

private fun DrawScope.blob(x: Float, y: Float, r: Float, c: Color, blend: BlendMode = BlendMode.SrcOver) {
    if (r <= 0f) return
    drawCircle(Brush.radialGradient(listOf(c, c.copy(alpha = 0f)), center = Offset(x, y), radius = r), r, Offset(x, y), blendMode = blend)
}

/** What Glass cards bend: the scene, drawn once per tick (on a background thread, at half size) into one window-sized
 *  picture that every card copies its part of. Each card used to draw the whole scene itself — during a back gesture
 *  (two pages, every card re-recording its backdrop as the page moves) that was the scene a dozen times a frame, and
 *  Glass dropped frames even on fast phones. The page behind still draws the scene itself, at full sharpness. */
private object LivingFrame {
    @Volatile var front: ImageBitmap? = null; @Volatile var frontKey: List<Any>? = null
    var version by mutableIntStateOf(0)
    @Volatile private var busy = false
    private val spares = java.util.concurrent.CopyOnWriteArrayList<ImageBitmap>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "nova-living").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun request(key: List<Any>, t: NovaColors, W: Float, H: Float, k: Float, density: androidx.compose.ui.unit.Density) {
        if (busy || key == frontKey) return
        busy = true
        worker.execute {
            val bw = (W * k).toInt().coerceAtLeast(1); val bh = (H * k).toInt().coerceAtLeast(1)
            // two pictures, taking turns: draw into the one not on screen (no new 4 MB picture every tick)
            val spare = spares.firstOrNull { it !== front && it.width == bw && it.height == bh }
                ?: ImageBitmap(bw, bh).also { nb -> spares.removeAll { it.width != bw || it.height != bh }; if (spares.size < 2) spares.add(nb) }
            val b = runCatching { spare.also { bmp ->
                Canvas(bmp).nativeCanvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                CanvasDrawScope().draw(density, androidx.compose.ui.unit.LayoutDirection.Ltr, Canvas(bmp), Size(bw.toFloat(), bh.toFloat())) {
                    scale(k, k, Offset.Zero) { drawLiving(t, Offset.Zero, W, H, key[0] as Float) } } } }.getOrNull()
            main.post { if (b != null) { front = b; frontKey = key; version++ }; busy = false
                // the scene moved on while this one was drawn: the next draw asks again
            }
        }
    }
}

/** The scene at this spot for a Glass card, from the shared picture ([origin]: where it sits on the screen, [W]×[H] the
 *  window). Until the first picture is ready it draws the scene itself. */
fun DrawScope.drawLivingShared(t: NovaColors, origin: Offset, W: Float, H: Float) {
    if (W < 2f || H < 2f) return
    LivingFrame.version                                  // (read: a new picture redraws this card)
    val k = if (lite()) .35f else .5f
    val key = listOf(Living.time, t.blue, t.dark, t.material, t.preset, Living.sceneFor(t), Living.accent, W.toInt(), H.toInt(), k)
    LivingFrame.request(key, t, W, H, k, androidx.compose.ui.unit.Density(1f))   // (the scenes are drawn in pixels; reading this scope's density inside a card's recording loops in Compose)
    val b = LivingFrame.front
    val fk = LivingFrame.frontKey
    // nothing yet, or a picture for another look or window size: draw the scene directly this once
    if (b == null || fk == null || fk.subList(1, fk.size) != key.subList(1, key.size)) { drawLiving(t, origin, W, H); return }
    drawImage(b, dstOffset = androidx.compose.ui.unit.IntOffset(-origin.x.roundToInt(), -origin.y.roundToInt()),
        dstSize = androidx.compose.ui.unit.IntSize(W.roundToInt(), H.roundToInt()), filterQuality = FilterQuality.Low)
}

/** The scene at this spot: [origin] is where this layer sits on the screen, [W]×[H] the whole window. */
fun DrawScope.drawLiving(t: NovaColors, origin: Offset, W: Float, H: Float, time: Float = Living.time) {
    val own = Living.accent.takeIf { it.length == 7 }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    val (h, s) = hueSat(own ?: t.blue); val d = t.dark; val m = max(W, H); val sc = Living.sceneFor(t)
    translate(-origin.x, -origin.y) {
        when {
            sc == "material" -> {                            // soft shapes in the accent's tones
                drawRect(if (d) hsl(h, s * .25f, 7f) else hsl(h, s * .35f, 96f), size = Size(W, H))
                for (i in 0 until 9) {
                    val x = (((i * .37f + time * (.004f + i % 3 * .002f)) % 1.3f) - .15f) * W
                    val y = H * ((i * .29f) % 1f) + sin(time * .15f + i) * H * .04f; val r = m * (.06f + (i % 4) * .025f)
                    val c = if (d) hsl(h + (i % 4) * 12, s * .45f, 22f + (i % 4) * 5, .9f) else hsl(h + (i % 4) * 12, s * .6f, 86f - (i % 4) * 5, .9f)
                    translate(x, y) { rotate((time * .05f * (if (i % 2 == 1) 1 else -1) + i) * 57.3f, Offset.Zero) {
                        when (i % 3) {
                            0 -> drawCircle(c, r, Offset.Zero)
                            1 -> drawRoundRect(c, Offset(-r, -r), Size(2 * r, 2 * r), CornerRadius(r * .45f))
                            else -> drawPath(Path().apply { for (k in 0..64) { val a = k / 64f * 2 * PI.toFloat(); val rr = r * (1 + .14f * cos(a * 6)); if (k == 0) moveTo(cos(a) * rr, sin(a) * rr) else lineTo(cos(a) * rr, sin(a) * rr) }; close() }, c)
                        }
                    } }
                }
            }
            sc == "aero" -> {                    // ribbons of light, and bokeh drifting up
                drawRect(Brush.linearGradient(listOf(if (d) hsl(h, s * .9f, 14f) else hsl(h, s * .8f, 62f), if (d) hsl(h + 10, s, 5f) else hsl(h + 10, s * .9f, 40f)), Offset.Zero, Offset(W, H)), size = Size(W, H))
                for (i in 0 until 5) {
                    val ph = time * (.05f + i * .012f) + i * 1.7f; val y0 = H * (.25f + i * .13f)
                    val p = Path().apply { moveTo(-W * .1f, y0 + sin(ph) * H * .2f)
                        cubicTo(W * .3f, y0 - H * .35f * cos(ph * .8f), W * .65f, y0 + H * .35f * sin(ph * .9f), W * 1.1f, y0 - H * .15f * cos(ph)) }
                    val br = Brush.horizontalGradient(listOf(hsl(h + i * 8, s, 70f, 0f), hsl(h + i * 8 - 10, s * .7f, 78f, .2f), hsl(h + i * 8, s, 70f, 0f)), 0f, W)
                    val wdt = m * (.05f + .03f * sin(ph * 1.3f))
                    drawPath(p, br, style = Stroke(wdt * 1.4f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)        // one wide soft pass (it was three)
                    if (i % 2 == 0) drawPath(p, Color.White.copy(alpha = if (d) .1f else .18f), style = Stroke(wdt * .18f, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
                }
                for (i in 0 until 9) {
                    val sp = .008f + (i % 4) * .004f; val y = (1.1f - ((time * sp + i * .137f) % 1.2f)) * H
                    blob(W * ((i * .618f) % 1f) + sin(time * .2f + i) * W * .03f, y, m * (.02f + (i % 3) * .015f), Color.White.copy(alpha = if (d) .14f else .22f), BlendMode.Plus)
                }
            }
            sc == "fluent" -> {                  // a bloom turning slowly, breathing
                drawRect(if (d) hsl(h, s * .35f, 8f) else hsl(h, s * .3f, 94f), size = Size(W, H))
                val cx = W * .55f; val cy = H * .55f
                for (i in 0 until 7) {
                    val a = time * .03f + i / 7f * 2 * PI.toFloat(); val br = 1 + .08f * sin(time * .4f + i)
                    translate(cx, cy) { rotate(a * 57.3f, Offset.Zero) { scale(1f, .42f, Offset.Zero) {
                        blob(m * .22f * br, 0f, m * .3f * br, if (d) hsl(h + (i - 3) * 7, s, 54f, .5f) else hsl(h + (i - 3) * 9, s, 62f, .38f))
                    } } }
                }
                blob(cx, cy, m * .2f, if (d) hsl(h + 15, s * .7f, 70f, .3f) else hsl(h + 15, s * .5f, 92f, .65f))
            }
            sc == "breeze" -> {                  // layered waves flowing across
                drawRect(Brush.verticalGradient(listOf(if (d) hsl(h, s * .5f, 12f) else hsl(h, s * .5f, 90f), if (d) hsl(h, s * .6f, 6f) else hsl(h, s * .55f, 76f)), 0f, H), size = Size(W, H))
                for (k in 0 until 5) {
                    val y0 = H * (.45f + k * .1f); val amp = H * (.08f - k * .008f); val sp = .12f + k * .05f
                    val p = Path().apply { moveTo(0f, H); var x = 0f
                        while (x <= W) { lineTo(x, y0 + sin(x / W * (3 + k * .6f) + time * sp + k) * amp + sin(x / W * 7 - time * sp * .7f) * amp * .3f); x += W / 120 }
                        lineTo(W, H); close() }
                    drawPath(p, if (d) hsl(h - k * 6, s * (.6f + k * .08f), 18f + k * 7, .55f) else hsl(h - k * 6, s * (.5f + k * .1f), 75f - k * 7, .55f))
                }
            }
            sc == "liquid" -> {                  // liquid color under the glass
                drawRect(if (d) hsl(h, s * .4f, 6f) else hsl(h, s * .3f, 94f), size = Size(W, H))
                listOf(h, h + 40, h - 50, h + 160, h + 20).forEachIndexed { i, hu ->
                    val x = W * (.5f + .38f * sin(time * (.05f + i * .013f) + i * 1.3f)); val y = H * (.5f + .38f * cos(time * (.04f + i * .011f) + i * 2.1f))
                    blob(x, y, m * (.38f + .06f * sin(time * .3f + i)), if (d) hsl(hu, s, 50f, .55f) else hsl(hu, s * .9f, 74f, .6f), if (d) BlendMode.Screen else BlendMode.Multiply)
                }
            }
            else -> {                                  // a drifting nebula, and a few stars
                drawRect(if (d) hsl(h, s * .5f, 4f) else hsl(h, s * .4f, 95f), size = Size(W, H))
                listOf(Triple(h, .55f to .35f, 47f to 61f), Triple(h + 50, .45f to .7f, 59f to 43f), Triple(h - 40, .3f to .55f, 71f to 53f)).forEachIndexed { i, (hu, xy, pp) ->
                    blob(W * (xy.first + .18f * sin(time / pp.first * 6.28f + i)), H * (xy.second + .16f * cos(time / pp.second * 6.28f + i * 2)), m * .55f,
                        if (d) hsl(hu, s, 45f, .55f) else hsl(hu, s, 72f, .42f), if (d) BlendMode.Plus else BlendMode.SrcOver)
                }
                if (d) for (i in 0 until 26) { val a = .25f + .25f * sin(time * (.6f + i % 5 * .2f) + i)
                    drawCircle(Color.White.copy(alpha = a), 1.6f, Offset((i * .6180339f % 1f) * W, (i * .5377f % 1f) * H)) }
            }
        }
    }
}
