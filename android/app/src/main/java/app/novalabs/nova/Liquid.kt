package app.novalabs.nova

import android.graphics.RuntimeShader
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Appearance → Style: Glass, Mica, Breeze and Aero (stored as liquid / fluent / breeze / aero).
 *
 * Glass cards are real glass over the background: each card paints its own part of the page
 * background (your wallpaper, or Nova's glow — both drawn in window coordinates, so the part lines up
 * exactly with what's around the card), then bends it with a lens shader — flat in the middle, curving
 * more and more toward the rim, with a touch of color fringing — and blurs it lightly. On Android 13+
 * that's an AGSL shader; Android 12 gets the blur without the bending; older phones a tinted pane.
 * Light catches the top-left of the rim and fades round the sides — no thick outline.
 */

// ── tokens ──────────────────────────────────────────────────────────────────────────────
fun presetTokens(preset: String, dark: Boolean, seed: Color?): NovaColors? = when (preset) {
    "liquid" -> (if (dark) DarkTokens else LightTokens).copy(preset = "liquid",
        card = if (dark) Color(0xFF16161C) else Color(0xFFFAFAFC), divider = if (dark) Color(0x22FFFFFF) else Color(0x14000000))
    "fluent" -> {
        val acc = seed ?: Color(if (dark) 0xFF60CDFF else 0xFF005FB8)
        if (dark) NovaColors(lerp(Color(0xFF202020), acc, .06f), Color.Transparent, Color.Transparent, Color(0xFF2B2B2B), Color(0xFF2D2D2D), Color(0xFF2C2C2C),
            Color(0x14FFFFFF), Color.White, Color(0xA8FFFFFF), acc, acc, Color(0x0FFFFFFF), Color(0xFF6CCB5F), Color(0xFFFCE100), Color(0xFFFF99A4), true, preset = "fluent")
        else NovaColors(lerp(Color(0xFFF3F3F3), acc, .05f), Color.Transparent, Color.Transparent, Color(0xFFFBFBFB), Color(0xFFF9F9F9), Color(0xFFFCFCFC),
            Color(0x0A000000), Color(0xFF1B1B1B), Color(0x9E000000), acc, acc, Color(0x0F000000), Color(0xFF0F7B0F), Color(0xFF9D5D00), Color(0xFFC42B1C), false, preset = "fluent")
    }
    "breeze" -> {
        val acc = seed ?: Color(0xFF3DAEE9)
        if (dark) NovaColors(Color(0xFF141618), Color.Transparent, Color.Transparent, Color(0xFF202326), Color(0xFF292C30), Color(0xFF292C30),
            lerp(Color(0xFF292C30), acc, .28f), Color(0xFFFCFCFC), Color(0xFFA1A9B1), acc, acc, Color(0x12FFFFFF), Color(0xFF27AE60), Color(0xFFF67400), Color(0xFFDA4453), true, preset = "breeze")
        else NovaColors(Color(0xFFEFF0F1), Color.Transparent, Color.Transparent, Color.White, Color(0xFFF7F7F7), Color(0xFFFCFCFC),
            lerp(Color.White, acc, .18f), Color(0xFF232629), Color(0xFF707D8A), acc, lerp(acc, Color.Black, .15f), Color(0x14000000), Color(0xFF27AE60), Color(0xFFF67400), Color(0xFFDA4453), false, preset = "breeze")
    }
    "aero" -> {       // sky-blue glass over a deep blue desktop; the body text stays plain and dark on light
        val acc = seed ?: Color(0xFF3C8FD6)
        if (dark) NovaColors(Color(0xFF0A1B2E), lerp(acc, Color.White, .1f), Color(0xFF1A7F8E), Color(0xFF13253A), Color(0xFF1C3350), Color(0xFF142A42),
            lerp(Color(0xFF1C3350), acc, .45f), Color.White, Color(0xFFB3C6DA), lerp(acc, Color.White, .2f), lerp(acc, Color.White, .35f), Color(0x1FFFFFFF),
            Color(0xFF4CC24C), Color(0xFFF2B630), Color(0xFFE8584A), true, preset = "aero")
        else NovaColors(Color(0xFFD3E5F5), lerp(acc, Color.White, .35f), Color(0xFFB9E3D3), Color(0xFFF5F9FD), Color(0xFFE8F1FA), Color(0xFFEEF5FC),
            Color(0xFFCDE6F7), Color(0xFF1E1E1E), Color(0xFF4F5F70), lerp(acc, Color.Black, .15f), Color(0xFF0066CC), Color(0x1A0B3A66),
            Color(0xFF2E9E2E), Color(0xFFC77C00), Color(0xFFC8321F), false, preset = "aero")
    }
    else -> null
}

/** Corner roundness as a factor (25–400%); Glass stops at 200%, past that its cards turn into ovals. */
fun roundK(): Float = AppPrefs.round.coerceIn(25, if (presetStyle() == "liquid") 200 else 400) / 100f

/** Floating pills (the nav pill, the dock): Mica, Breeze and Aero use their own small corners, scaled by Corner roundness
 *  (never rounder than the usual capsule). */
@Composable fun pillCorner(default: Dp): Dp { val k = roundK()
    return when (N.preset) { "fluent" -> 8.dp * k; "breeze" -> 7.dp * k; "aero" -> 6.dp * k; else -> default }.coerceAtMost(default) }
/** Small parts — search bars, the selection inside a pill — in Mica, Breeze and Aero, scaled the same way. */
@Composable fun smallCorner(default: Dp): Dp { val k = roundK()
    return when (N.preset) { "fluent" -> 4.dp * k; "breeze", "aero" -> 3.dp * k; else -> default }.coerceAtMost(default) }

/** Corner radius for a card in the current style (Corner roundness scales the presets). */
@Composable fun cardRadius(default: Dp): Dp {
    val k = roundK()
    return when (N.preset) { "liquid" -> 28.dp * k; "fluent" -> 8.dp * k; "breeze" -> 7.dp * k; "aero" -> 6.dp * k; else -> default }
}

// ── soft, smooth small copies ───────────────────────────────────────────────────────────
/** Small copies of a picture for frosted glass that stay smooth when drawn back large: shrunk in halves (so every
 *  pixel is averaged in, no shimmer), then blurred a little (so the upscale has no blocks or diamonds). Done once
 *  per picture, never per frame. */
object Soft {
    fun shrink(src: android.graphics.Bitmap, w: Int): android.graphics.Bitmap {
        var b = src; val target = w.coerceAtLeast(8)
        while (b.width / 2 >= target) { val n = android.graphics.Bitmap.createScaledBitmap(b, b.width / 2, (b.height / 2).coerceAtLeast(1), true); if (b !== src) b.recycle(); b = n }
        if (b.width != target) { val n = android.graphics.Bitmap.createScaledBitmap(b, target, (b.height * target / b.width).coerceAtLeast(1), true); if (b !== src) b.recycle(); b = n }
        return b
    }
    /** Three box blurs ≈ a gaussian, on the pixel array (fast on a small picture). */
    fun blur(b: android.graphics.Bitmap, r: Int): android.graphics.Bitmap {
        if (r < 1) return b
        val w = b.width; val h = b.height; val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) { box(px, tmp, w, h, r, true); box(tmp, px, w, h, r, false) }
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888); out.setPixels(px, 0, w, 0, 0, w, h); return out
    }
    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val len = if (horiz) w else h; val lines = if (horiz) h else w; val div = 2 * r + 1
        for (l in 0 until lines) {
            var ra = 0; var rr = 0; var rg = 0; var rb = 0
            fun at(i: Int): Int { val k = i.coerceIn(0, len - 1); return src[if (horiz) l * w + k else k * w + l] }
            for (i in -r..r) { val c = at(i); ra += c ushr 24; rr += (c shr 16) and 255; rg += (c shr 8) and 255; rb += c and 255 }
            for (i in 0 until len) {
                dst[if (horiz) l * w + i else i * w + l] = ((ra / div) shl 24) or ((rr / div) shl 16) or ((rg / div) shl 8) or (rb / div)
                val a = at(i - r); val b2 = at(i + r + 1)
                ra += (b2 ushr 24) - (a ushr 24); rr += ((b2 shr 16) and 255) - ((a shr 16) and 255); rg += ((b2 shr 8) and 255) - ((a shr 8) and 255); rb += (b2 and 255) - (a and 255)
            }
        }
    }
}

// ── the page background, drawn anywhere ─────────────────────────────────────────────────
/** The wallpaper, made once (a small copy: scaled back up it's smoothly soft, on every Android). */
object Backdrop {
    private var key = ""; private var bmp: ImageBitmap? = null
    private var gkey = ""; private var gbmp: ImageBitmap? = null
    /** An even smaller copy for Glass cards: drawn back at full size it's frosted, with no blur pass needed. */
    fun frosted(ctx: android.content.Context): ImageBitmap? {
        val src = wallpaper(ctx) ?: return null
        if (gkey != key) { gkey = key; gbmp = runCatching { val b = src.asAndroidBitmap()
            Soft.blur(Soft.shrink(b, (b.width / 4).coerceAtLeast(16)), 3).asImageBitmap() }.getOrNull() }
        return gbmp
    }
    /** How bright the wallpaper is on average (0 dark … 1 bright), so text on it can stay readable. */
    var lum = .5f; private set
    /** The wallpaper's darker and brighter quarters (where dark text, or light text, gets lost). */
    var dark = .5f; private set
    var bright = .5f; private set
    fun wallpaper(ctx: android.content.Context): ImageBitmap? {
        val k = "${AppPrefs.wallV}:${AppPrefs.wallBlur}"
        if (AppPrefs.wallV <= 0L) return null
        if (k != key) {
            key = k
            bmp = runCatching {
                val full = android.graphics.BitmapFactory.decodeFile(java.io.File(ctx.filesDir, "wallpaper.jpg").path) ?: return@runCatching null
                val s = 1f / (1f + AppPrefs.wallBlur / 3f)
                val tiny = android.graphics.Bitmap.createScaledBitmap(full, 16, 16, true)
                val ls = FloatArray(256); var i = 0
                for (y in 0 until 16) for (x in 0 until 16) { val c = tiny.getPixel(x, y)
                    ls[i++] = (.2126f * ((c shr 16) and 255) + .7152f * ((c shr 8) and 255) + .0722f * (c and 255)) / 255f }
                ls.sort(); lum = ls.average().toFloat(); dark = ls[64]; bright = ls[191]      // also its darker and brighter quarters
                (if (AppPrefs.wallBlur <= 0) full else Soft.blur(Soft.shrink(full, (full.width * s).toInt().coerceAtLeast(8)), 2 + AppPrefs.wallBlur / 8)).asImageBitmap()
            }.getOrNull()
        }
        return bmp
    }
}

/** How much of the theme's background to lay over the wallpaper: your Dim setting, but at least enough that the page's
 *  text stays readable — a bright wallpaper under dark mode (or a dark one under light mode) gets dimmed further. */
fun wallScrim(t: NovaColors): Float {
    // light mode loses its dark text over the wallpaper's dark parts, dark mode its light text over the bright parts
    val clash = if (t.dark) Backdrop.bright else 1f - Backdrop.dark
    return maxOf(AppPrefs.wallDim / 100f, (clash - .3f).coerceAtLeast(0f) * .95f).coerceAtMost(.8f)
}

/** What's behind everything at this spot: the wallpaper (cover-fit to the window) or the glow, dimmed as set. */
fun DrawScope.drawBackdrop(t: NovaColors, origin: Offset, win: IntSize, wall: ImageBitmap?) {
    val W = if (win.width > 0) win.width.toFloat() else size.width; val H = if (win.height > 0) win.height.toFloat() else size.height
    drawRect(t.bg)
    if (wall != null) {
        val k = maxOf(W / wall.width, H / wall.height); val dw = wall.width * k; val dh = wall.height * k
        drawImage(wall, dstOffset = IntOffset(((W - dw) / 2 - origin.x).toInt(), ((H - dh) / 2 - origin.y).toInt()),
            dstSize = IntSize(dw.toInt(), dh.toInt()), filterQuality = FilterQuality.Medium)
        drawRect(t.bg.copy(alpha = wallScrim(t)))
    } else if (Living.showing) drawLiving(t, origin, W, H)                    // Glass bends the living scene too
    else if (!t.material && t.glow != Color.Transparent) {
        drawRect(Brush.radialGradient(listOf(t.glow.copy(alpha = if (t.dark) 0.9f else 0.8f), Color.Transparent), center = Offset(W * 0.85f, H * 0.18f) - origin, radius = if (W < H) W * 0.95f else H * 1.25f))
        drawRect(Brush.radialGradient(listOf(t.glow2.copy(alpha = 0.6f), Color.Transparent), center = Offset(W * 0.1f, H * 0.35f) - origin, radius = if (W < H) W * 0.8f else H * 1.1f))
    }
}

// ── what's behind a dialog ──────────────────────────────────────────────────────────────
/** A popup sits in its own window, so it can't see the live page. When one opens, Nova takes one picture of the app's
 *  window (small, which also softens it like frosted glass) and the popup's glass bends that — the real cards and text
 *  behind it, not just the wallpaper. */
object Behind {
    var shot by mutableStateOf<ImageBitmap?>(null); private set
    var shotSize = IntSize.Zero; private set                                              // the window it was taken of
    private var taken = 0L
    fun take(activity: android.app.Activity?) {
        val w = activity?.window ?: return
        if (System.currentTimeMillis() - taken < 300 && shot != null) return              // dialogs opening one after another
        val v = w.decorView; if (v.width <= 0 || v.height <= 0) return
        val full = runCatching { android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        if (android.os.Build.VERSION.SDK_INT < 26) { full.recycle(); return }      // Android 7: no window snapshot (dialogs show plain frost)
        runCatching {
            android.view.PixelCopy.request(w, full, { r ->
                if (r == android.view.PixelCopy.SUCCESS) {
                    shot = Soft.blur(Soft.shrink(full, (v.width / 4).coerceAtLeast(8)), 4).asImageBitmap(); shotSize = IntSize(v.width, v.height)
                    taken = System.currentTimeMillis()
                }
                if (shot?.asAndroidBitmap() !== full) full.recycle()
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
    }
}
/** True inside a popup: Glass there bends the picture of the page (Behind.shot) instead of the wallpaper. */
val LocalInDialog = androidx.compose.runtime.staticCompositionLocalOf { false }

// ── the lens ────────────────────────────────────────────────────────────────────────────
private const val LENS = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float strength;
uniform float2 off;                                              // where this layer starts inside the card (tall cards draw only what's on screen)
uniform float fringe;                                            // 1: the prism fringe (two more samples at the rim); 0: off (Glass quality)
half4 main(float2 p0) {
    float2 p = p0 + off;
    float2 h = size * 0.5;
    float2 q = abs(p - h) - (h - radius);
    float2 o = max(q, 0.0);
    float ol = length(o);
    float depth = -(ol + min(max(q.x, q.y), 0.0) - radius);   // how far in from the rim
    float2 n = ol > 0.0 ? o / ol : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    n *= sign(p - h + 0.0001);
    float t = clamp(1.0 - depth / bevel, 0.0, 1.0);
    if (t <= 0.0) return content.eval(p0);                       // the flat middle: one plain sample (most of the card, so it stays cheap)
    float e = t * t * (3.0 - 2.0 * t);
    float m = e * strength;                                      // none inside the bevel, most at the rim
    float2 d = -n * m;                                           // look inward: the rim gathers what's behind the middle
    half4 c = content.eval(p0 + d);
    half glint0 = half(e * e * 0.10);
    if (fringe < 0.5) return half4(min(c.rgb + glint0, 1.0), c.a);
    // the prism: white splits into its colors at the rim, like iOS — red bends a little more, blue a little less,
    // and the split grows toward the very edge
    half r = content.eval(p0 + d * (1.0 + 0.16 * e)).r;
    half b = content.eval(p0 + d * (1.0 - 0.16 * e)).b;
    half glint = half(e * e * 0.10);                             // the curved edge catches a little light, on all four sides
    return half4(min(r + glint, 1.0), min(c.g + glint, 1.0), min(b + glint, 1.0), c.a);
}
"""

/** [q]: Glass quality (Developer options) — under 90% the prism fringe is left out and the bending rim gets narrower,
 *  which is where the lens spends its time (the flat middle is one plain sample either way). */
private fun lensEffect(w: Float, h: Float, radius: Float, blurPx: Float, offY: Float = 0f, bend: Float = 1f, q: Float = 1f): RenderEffect? {
    if (!Compat.S) return null
    val blur = if (blurPx > 0.5f) android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP) else null
    if (android.os.Build.VERSION.SDK_INT < 33) return blur?.asComposeRenderEffect()
    val sh = runCatching { RuntimeShader(LENS) }.getOrNull()?.apply {          // (if this GPU driver won't take it: blur only)
        setFloatUniform("size", w, h); setFloatUniform("radius", radius.coerceAtMost(minOf(w, h) / 2))
        val rim = (0.4f + 0.6f * q).coerceIn(0.4f, 1f)
        setFloatUniform("bevel", minOf(maxOf(radius * 1.1f, 40f), 100f, w / 3, h / 3) * rim); setFloatUniform("strength", minOf(140f, maxOf(50f, minOf(w, h) * .3f)) * bend)
        setFloatUniform("off", 0f, offY); setFloatUniform("fringe", if (q >= 0.9f) 1f else 0f)
    } ?: return blur?.asComposeRenderEffect()
    val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content")
    return (if (blur != null) android.graphics.RenderEffect.createChainEffect(blur, lens) else lens).asComposeRenderEffect()   // bend first, then blur
}

/** A Glass card: the background behind it, bent and blurred, a thin tint, and light on the rim. */
/** How much body Glass has at a clarity (0..1): 1 at 10% (the old frosted look), 0 at 100%, a little more below 10%. */
fun glassBody(clear: Float): Float { val x = (1f - clear) / .9f; return x * x }

@Composable fun Modifier.liquidCard(shape: Shape, corner: Dp, elevation: Dp = 6.dp): Modifier {
    val t = N
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    var origin by remember { mutableStateOf(Offset.Zero) }
    val inDialog = LocalInDialog.current
    val clear = AppPrefs.glass.coerceIn(0, 100) / 100f
    // Glass clarity sets how liquid it looks. 10% is the old frosted Glass (0.7.3's 100%); from there up it clears steadily
    // until 100% is almost bare glass: no tint to speak of, the real backdrop seen sharply, bent only at the rim.
    val f = glassBody(clear)
    val wall = if (clear >= .4f) Backdrop.wallpaper(ctx) else Backdrop.frosted(ctx)
    // the tint thins out with Glass clarity, but over a wallpaper that clashes with the theme (bright under dark mode, dark
    // under light) it keeps a little body so the text on the card stays readable. Popups keep enough to read.
    val clash = if (wall == null) 0f else if (t.dark) Backdrop.bright else 1f - Backdrop.dark
    val tint = (if (t.dark) Color(0xFF16161C) else Color(0xFFFAFAFC)).copy(alpha = maxOf((if (t.dark) .02f + .28f * f else .05f + .25f * f) + clash * (.06f + .39f * minOf(f, 1f)),
        if (inDialog) (if (t.dark) .5f else .6f) else 0f).coerceIn(0f, .9f))
    val hi = Color.White
    if (solidGlass() || lite()) return this.shadow(if (lite()) 0.dp else elevation, shape).clip(shape).background(t.card)
    // on-screen position: the same in the app's window and in a dialog's own window, so dialogs line up with the wallpaper too
    return this.onGloballyPositioned { origin = it.positionOnScreen() }
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = .22f), spotColor = Color.Black.copy(alpha = .28f))
        .clip(shape)
        .drawWithCache {
            val layer = obtainGraphicsLayer()
            val r = corner.toPx()
            // no live blur: the wallpaper copy, the glow and the popup's snapshot are all soft already, and a blur over a whole
            // card every frame is what costs the most while scrolling — only Android 12 (no lens shader) still blurs a little
            val blurPx = if (android.os.Build.VERSION.SDK_INT < 33) 4.dp.toPx() else 0f
            // A tall card (a long list) is drawn only where it can be seen, plus a margin: a layer taller than the GPU's
            // biggest texture loses its lens (that's why long lists showed no glass), and the smaller layer is cheaper too.
            // The window moves in steps, so the lens is rebuilt only every few hundred pixels of scrolling.
            val winH = if (win.height > 0) win.height.toFloat() else size.height
            val lh = minOf(size.height, winH + 768f)
            var lensOff = -1f; var lensQ = -1f
            val outline = shape.createOutline(size, layoutDirection, this)
            val sheen = Brush.linearGradient(listOf(hi.copy(alpha = (if (t.dark) .10f else .30f) * (1f - .6f * clear)), Color.Transparent), Offset.Zero, Offset(size.width * .35f, size.height * .38f))
            val rim = Brush.sweepGradient(0f to hi.copy(alpha = .06f), .12f to hi.copy(alpha = .03f), .35f to hi.copy(alpha = .30f), .5f to hi.copy(alpha = .06f),
                .62f to hi.copy(alpha = .75f), .7f to hi.copy(alpha = .75f), .85f to hi.copy(alpha = .06f), 1f to hi.copy(alpha = .06f), center = Offset(size.width / 2, size.height / 2))
            val stroke = Stroke(0.7.dp.toPx())
            onDrawBehind {
                val shot = if (inDialog) Behind.shot else null
                val offY = if (lh >= size.height) 0f else ((((-origin.y - 384f).coerceAtLeast(0f)) / 256f).toInt() * 256f).coerceAtMost(size.height - lh)
                val wantOff = if (Dev.noLens) -2f else offY                 // Developer options → Glass without bending
                // Glass quality (Developer options): lighter lens under 90%, no lens at all under 50% (just the tint and the rim light)
                val q = Dev.glassQ / 100f
                if (wantOff != lensOff || q != lensQ) {
                    layer.renderEffect = if (Dev.noLens || q < 0.5f) null else lensEffect(size.width, size.height, r, blurPx, offY, .75f + .55f * clear, q)
                    lensOff = wantOff; lensQ = q }
                layer.record(size = IntSize(size.width.toInt(), lh.toInt())) {
                    translate(0f, -offY) {
                        if (shot != null) {                      // the page behind this popup, as the popup window dims it
                            drawImage(shot, dstOffset = IntOffset(-origin.x.toInt(), -origin.y.toInt()), dstSize = Behind.shotSize, filterQuality = FilterQuality.Medium)
                            drawRect(t.bg.copy(alpha = .5f))             // calmer than the page itself, so the popup's text reads easily
                        } else drawBackdrop(t, origin, win, wall)
                    }
                }
                translate(0f, offY) { drawLayer(layer)
                    if (Dev.bounds) drawRect(Color.Magenta, size = androidx.compose.ui.geometry.Size(size.width, lh), style = Stroke(2f)) }   // Developer options → Show glass areas
                drawRect(tint)
                drawRect(sheen)
                drawOutline(outline, rim, style = stroke)
            }
        }
}

/** The rim light alone (for floating Glass: the nav pill and buttons, blurred by Haze). */
fun Modifier.liquidRim(shape: Shape): Modifier = this.drawWithCache {
    val hi = Color.White; val outline = shape.createOutline(size, layoutDirection, this)
    val rim = Brush.sweepGradient(0f to hi.copy(alpha = .08f), .35f to hi.copy(alpha = .35f), .5f to hi.copy(alpha = .08f), .62f to hi.copy(alpha = .8f),
        .7f to hi.copy(alpha = .8f), .85f to hi.copy(alpha = .08f), 1f to hi.copy(alpha = .08f), center = Offset(size.width / 2, size.height / 2))
    val stroke = Stroke(0.7.dp.toPx())
    onDrawWithContent { drawContent(); drawOutline(outline, rim, style = stroke) }
}

/** Fluent and Breeze cards: a solid layer with a 1 px stroke (Fluent's is lighter on top). */
@Composable fun Modifier.presetCard(shape: Shape): Modifier {
    val t = N
    val stroke = if (t.preset == "fluent") (if (t.dark) Color(0x14FFFFFF) else Color(0x0F000000)) else (if (t.dark) Color(0x1AFFFFFF) else Color(0x24000000))
    return this.shadow(if (lite()) 0.dp else 1.dp, shape).clip(shape).background(t.card)
        .then(Modifier.drawWithCache { val o = shape.createOutline(size, layoutDirection, this); val s = Stroke(1.dp.toPx()); onDrawWithContent { drawContent(); drawOutline(o, stroke, style = s) } })
}

// ── Aero ────────────────────────────────────────────────────────────────────────────────
/** Aero's frame color: the accent mixed into the glass; Glass clarity thins it from a deep tint to a light wash. */
fun aeroFrame(t: NovaColors): Color {
    val clear = AppPrefs.glass.coerceIn(0, 100) / 100f
    return (if (t.dark) lerp(Color(0xFF0E2238), t.blue, .35f) else lerp(Color.White, t.blue, .38f)).copy(alpha = .82f - .5f * clear)
}

/** Aero's shine, drawn over the frame and under the content: a soft wash of light from the top edge,
 *  two faint diagonal streaks, a dark outer edge and a thin light edge just inside it. */
fun Modifier.aeroGloss(shape: Shape, dark: Boolean): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val w = Color.White
    // the wash is measured from the top in dp, not as a share of the card, so a tall list gets the same shine as a small tile
    val washH = minOf(size.height, 90.dp.toPx())
    val wash = Brush.verticalGradient(0f to w.copy(alpha = if (dark) .16f else .34f), 1f to Color.Transparent, endY = washH)
    val streaks = Brush.linearGradient(0f to Color.Transparent, .40f to Color.Transparent, .46f to w.copy(alpha = .11f), .52f to Color.Transparent,
        .60f to Color.Transparent, .64f to w.copy(alpha = .08f), .69f to Color.Transparent, 1f to Color.Transparent,
        start = Offset.Zero, end = Offset(minOf(size.width, 420.dp.toPx()), minOf(size.width, 420.dp.toPx()) * .9f), tileMode = TileMode.Clamp)
    val px = 1.dp.toPx()
    val inner = (if (shape is androidx.compose.foundation.shape.RoundedCornerShape) shape else androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        .createOutline(androidx.compose.ui.geometry.Size(size.width - 2 * px, size.height - 2 * px), layoutDirection, this)
    val edge = if (dark) Color.Black.copy(alpha = .55f) else Color(0xFF0B2E4F).copy(alpha = .40f)
    onDrawWithContent {
        drawOutline(outline, wash); drawOutline(outline, streaks)
        translate(px, px) { drawOutline(inner, w.copy(alpha = if (dark) .22f else .55f), style = Stroke(px)) }
        drawOutline(outline, edge, style = Stroke(px))
        drawContent()
    }
}

/** An Aero card: the wallpaper (or Nova's glow) behind it, frosted, tinted with the frame color, with the shine on top.
 *  No lens and no live blur — the frosted copy is made once — so it's as cheap as a plain card while scrolling. */
@Composable fun Modifier.aeroCard(shape: Shape, elevation: Dp = 6.dp): Modifier {
    val t = N
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    var origin by remember { mutableStateOf(Offset.Zero) }
    val inDialog = LocalInDialog.current
    if (solidGlass() || lite()) return this.shadow(if (lite()) 0.dp else elevation, shape).clip(shape).background(t.card).aeroGloss(shape, t.dark)
    val wall = Backdrop.frosted(ctx)
    val frame = aeroFrame(t).let { if (inDialog) it.copy(alpha = maxOf(it.alpha, if (t.dark) .6f else .55f)) else it }
    return this.onGloballyPositioned { origin = it.positionOnScreen() }
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = .3f), spotColor = Color.Black.copy(alpha = .4f))
        .clip(shape)
        .drawBehind {
            val shot = if (inDialog) Behind.shot else null
            if (shot != null) {
                drawImage(shot, dstOffset = IntOffset(-origin.x.toInt(), -origin.y.toInt()), dstSize = Behind.shotSize, filterQuality = FilterQuality.Medium)
                drawRect(t.bg.copy(alpha = .45f))
            } else drawBackdrop(t, origin, win, wall)
            drawRect(frame)
        }
        .aeroGloss(shape, t.dark)
}

/** Aero's raised button: a two-part gloss (light on top, a step at the middle), blue and glowing when it's the main one. */
@Composable fun Modifier.aeroButton(primary: Boolean, shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)): Modifier {
    val t = N
    // every tone follows your accent color (it used to be Windows' own blue whatever you picked)
    val a = t.blue; val w = Color.White; val k = Color.Black
    val (stops, edge) = when {
        primary && t.dark -> listOf(lerp(w, a, .62f), lerp(w, a, .86f), lerp(k, a, .82f), lerp(w, a, .92f)) to lerp(k, a, .35f)
        primary -> listOf(lerp(w, a, .10f), lerp(w, a, .18f), lerp(w, a, .32f), lerp(w, a, .42f)) to lerp(k, a, .75f)
        t.dark -> listOf(lerp(Color(0xFF4A5464), a, .22f), lerp(Color(0xFF3A4352), a, .22f), lerp(Color(0xFF2B323E), a, .22f), lerp(Color(0xFF333B48), a, .22f)) to lerp(Color(0xFF0B0F16), a, .2f)
        else -> listOf(lerp(Color(0xFFF2F2F2), a, .03f), lerp(Color(0xFFEBEBEB), a, .05f), lerp(Color(0xFFDDDDDD), a, .08f), lerp(Color(0xFFCFCFCF), a, .10f)) to lerp(Color(0xFF707070), a, .25f)
    }
    return this.clip(shape).background(Brush.verticalGradient(0f to stops[0], .5f to stops[1], .5f to stops[2], 1f to stops[3]))
        .drawWithCache { val o = shape.createOutline(size, layoutDirection, this); val px = 1.dp.toPx()
            onDrawWithContent { drawOutline(o, Color.White.copy(alpha = if (t.dark) .12f else .6f), style = Stroke(px * 3)); drawOutline(o, edge, style = Stroke(px)); drawContent() } }
}

/** Text on Aero glass carries a soft glow behind it, like a glass title bar (light mode only — dark text needs it). */
fun aeroGlow(dark: Boolean): androidx.compose.ui.graphics.Shadow? = if (dark) null else androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = .9f), Offset.Zero, 10f)
