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
 * Appearance → Style: Glass, Mica and Breeze (stored as liquid / fluent / breeze).
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
    else -> null
}

/** Corner radius for a card in the current style (Corner roundness scales the three presets). */
@Composable fun cardRadius(default: Dp): Dp {
    val k = AppPrefs.round.coerceIn(25, 150) / 100f
    return when (N.preset) { "liquid" -> 28.dp * k; "fluent" -> 8.dp * k; "breeze" -> 7.dp * k; else -> default }
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
    } else if (!t.material && t.glow != Color.Transparent) {
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
    half r = content.eval(p0 + d * 1.06).r;                      // a faint prism fringe at the edge
    half b = content.eval(p0 + d * 0.94).b;
    half glint = half(e * e * 0.10);                             // the curved edge catches a little light, on all four sides
    return half4(min(r + glint, 1.0), min(c.g + glint, 1.0), min(b + glint, 1.0), c.a);
}
"""

private fun lensEffect(w: Float, h: Float, radius: Float, blurPx: Float, offY: Float = 0f, bend: Float = 1f): RenderEffect? {
    if (!Compat.S) return null
    val blur = if (blurPx > 0.5f) android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP) else null
    if (android.os.Build.VERSION.SDK_INT < 33) return blur?.asComposeRenderEffect()
    val sh = runCatching { RuntimeShader(LENS) }.getOrNull()?.apply {          // (if this GPU driver won't take it: blur only)
        setFloatUniform("size", w, h); setFloatUniform("radius", radius.coerceAtMost(minOf(w, h) / 2))
        setFloatUniform("bevel", minOf(maxOf(radius * 1.1f, 40f), 100f, w / 3, h / 3)); setFloatUniform("strength", minOf(140f, maxOf(50f, minOf(w, h) * .3f)) * bend)
        setFloatUniform("off", 0f, offY)
    } ?: return blur?.asComposeRenderEffect()
    val lens = android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "content")
    return (if (blur != null) android.graphics.RenderEffect.createChainEffect(blur, lens) else lens).asComposeRenderEffect()   // bend first, then blur
}

/** A Glass card: the background behind it, bent and blurred, a thin tint, and light on the rim. */
@Composable fun Modifier.liquidCard(shape: Shape, corner: Dp, elevation: Dp = 6.dp): Modifier {
    val t = N
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    var origin by remember { mutableStateOf(Offset.Zero) }
    val inDialog = LocalInDialog.current
    val clear = AppPrefs.glass.coerceIn(0, 100) / 100f
    // Glass clarity sets how liquid it looks: clear glass sees the page sharply (only bent at the rim), frosted glass a
    // soft copy. From about the middle up it's the real, sharp backdrop — that's the liquid look.
    val wall = if (clear >= .45f) Backdrop.wallpaper(ctx) else Backdrop.frosted(ctx)
    // the tint thins out with Glass clarity, but over a wallpaper that clashes with the theme (bright under dark mode, dark
    // under light) it keeps enough body that the text on the card stays easy to read
    val clash = if (wall == null) 0f else if (t.dark) Backdrop.bright else 1f - Backdrop.dark
    // tint: thick when frosted, almost nothing when clear (100% is clearer than it used to be: the old 100% is now about
    // 50%); a clashing wallpaper adds a little body, less the clearer you ask for. Popups keep enough to read.
    val hiA = if (t.dark) .62f else .78f; val loA = if (t.dark) .05f else .12f
    val tint = (if (t.dark) Color(0xFF16161C) else Color(0xFFFAFAFC)).copy(alpha = maxOf(hiA + (loA - hiA) * clear + clash * .3f * (1f - clear),
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
            var lensOff = -1f
            val outline = shape.createOutline(size, layoutDirection, this)
            val sheen = Brush.linearGradient(listOf(hi.copy(alpha = (if (t.dark) .10f else .30f) * (1f - .5f * clear)), Color.Transparent), Offset.Zero, Offset(size.width * .35f, size.height * .38f))
            val rim = Brush.sweepGradient(0f to hi.copy(alpha = .06f), .12f to hi.copy(alpha = .03f), .35f to hi.copy(alpha = .30f), .5f to hi.copy(alpha = .06f),
                .62f to hi.copy(alpha = .75f), .7f to hi.copy(alpha = .75f), .85f to hi.copy(alpha = .06f), 1f to hi.copy(alpha = .06f), center = Offset(size.width / 2, size.height / 2))
            val stroke = Stroke(0.7.dp.toPx())
            onDrawBehind {
                val shot = if (inDialog) Behind.shot else null
                val offY = if (lh >= size.height) 0f else ((((-origin.y - 384f).coerceAtLeast(0f)) / 256f).toInt() * 256f).coerceAtMost(size.height - lh)
                val wantOff = if (Dev.noLens) -2f else offY                 // Developer options → Glass without bending
                if (wantOff != lensOff) { layer.renderEffect = if (Dev.noLens) null else lensEffect(size.width, size.height, r, blurPx, offY, .75f + .55f * clear); lensOff = wantOff }
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
