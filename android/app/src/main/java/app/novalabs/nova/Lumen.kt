package app.novalabs.nova

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lumen (beta): light on dark glass — the same look as Nova web's. Hairline glass cards over a slow aurora, a beam of
 * light running round the edge of the navigation pill, dialogs and the main button, liquid-metal buttons and selector
 * beads, gooey switches that stretch as they move, and icons that glow. Inspired by libraries.dev's border beam, liquid
 * metal, gooey and magnetic UI; all drawn here.
 */
fun lumenTokens(dark: Boolean, seed: Color?): NovaColors {
    val acc = seed ?: Color(0xFF6E8BFF)
    return if (dark) NovaColors(Color(0xFF05060A), lerp(Color(0xFF05060A), acc, .55f), lerp(Color(0xFF05060A), Color(0xFF8A3CFF), .45f), Color(0xFF0E0F16), Color(0x10FFFFFF), Color(0xE00E0F16),
        Color(0x14FFFFFF), Color(0xFFF3F4F8), Color(0x94E8EAF5), acc, lerp(acc, Color.White, .3f), Color(0x12FFFFFF), Color(0xFF3EE58A), Color(0xFFFFC04D), Color(0xFFFF5C7A), true, preset = "lumen")
    else NovaColors(Color(0xFFEEF0F5), lerp(Color(0xFFEEF0F5), acc, .35f), lerp(Color(0xFFEEF0F5), Color(0xFF8A3CFF), .25f), Color(0xFFFBFCFF), Color(0xB3FFFFFF), Color(0xE6FAFBFF),
        Color(0x0F0A0C18), Color(0xFF0B0C12), Color(0x940F1220), lerp(acc, Color.Black, .1f), lerp(acc, Color.Black, .2f), Color(0x120A0C18), Color(0xFF15A35A), Color(0xFFC77C00), Color(0xFFD6334F), false, preset = "lumen")
}

/** Liquid metal: chrome, a bright band near the top and a darker one under the middle. */
fun lumenMetal(dark: Boolean): Brush = if (dark) Brush.verticalGradient(0f to Color(0xFFFBFCFF), .38f to Color(0xFFC9CCD6), .52f to Color(0xFF8D919E), .70f to Color(0xFFD8DBE3), 1f to Color.White)
    else Brush.verticalGradient(0f to Color.White, .40f to Color(0xFFE3E5EC), .52f to Color(0xFFB8BCC8), .72f to Color(0xFFECEEF3), 1f to Color.White)

private fun edgeColor(t: NovaColors) = if (t.dark) Color(0x17FFFFFF) else Color(0x170A0C18)

/** A card: hairline glass, a little lighter at the top, with a soft shadow. */
@Composable fun Modifier.lumenCard(shape: Shape, elevation: Dp = 6.dp): Modifier {
    val t = N
    val fill = Brush.verticalGradient(if (t.dark) listOf(Color(0x0DFFFFFF), Color(0x08FFFFFF)) else listOf(Color(0xC0FFFFFF), Color(0x9EFFFFFF)))
    val solid = solidGlass() || lite()
    return this.then(if (elevation > 0.dp && !lite()) Modifier.shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = .5f), spotColor = Color.Black.copy(alpha = .6f)) else Modifier)
        .clip(shape).background(if (solid) SolidColor(t.card) else fill).background(if (solid) SolidColor(Color.Transparent) else SolidColor(t.card.copy(alpha = if (t.dark) .55f else .35f)))
        .border(1.dp, Brush.verticalGradient(listOf(if (t.dark) Color(0x24FFFFFF) else Color(0xFFFFFFFF), edgeColor(t))), shape)
}

/** A beam of light running round the edge (the nav pill, dialogs, a field you're typing in): a sweep of light that
 *  turns round the shape's middle, drawn only on its rim. Still with Reduce motion or Lighter effects. */
fun Modifier.lumenBeam(shape: Shape, color: Color, periodMs: Int = 4000, width: Dp = 1.5.dp): Modifier = composed {
    val still = reduceMotion() || lite()
    val a by rememberInfiniteTransition(label = "beam").animateFloat(0f, 360f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "a")
    val cols = remember(color) { intArrayOf(0, 0, color.copy(alpha = .4f).toArgb(), color.toArgb(), android.graphics.Color.WHITE, 0) }
    val pos = remember { floatArrayOf(0f, .64f, .78f, .86f, .9f, .95f) }
    this.drawWithContent {
        drawContent()
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithContent)) }
        val cx = size.width / 2; val cy = size.height / 2; val ang = if (still) 30f else a
        val brush = object : ShaderBrush() {
            override fun createShader(size: androidx.compose.ui.geometry.Size): Shader =
                android.graphics.SweepGradient(cx, cy, cols, pos).apply { setLocalMatrix(android.graphics.Matrix().apply { setRotate(ang, cx, cy) }) }
        }
        drawPath(path, brush, style = Stroke(width.toPx()))
    }
}

/** Liquid-metal button: chrome a light sweeps across, dark text, a glow of the accent under it. */
@Composable fun LumenButton(text: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val t = N; val src = remember { MutableInteractionSource() }; val pressed by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (pressed) .97f else 1f, spring(.5f, 700f), label = "press")
    val sweep by rememberInfiniteTransition(label = "sheen").animateFloat(-1f, 2.2f, infiniteRepeatable(tween(3600, easing = FastOutSlowInEasing)), label = "s")
    val sh = CircleShape
    Box(modifier.height(52.dp).graphicsLayer { scaleX = k; scaleY = k; alpha = if (enabled) 1f else .45f }
        .shadow(if (enabled) 14.dp else 0.dp, sh, ambientColor = t.blue, spotColor = t.blue)
        .clip(sh).background(lumenMetal(t.dark))
        .drawWithContent {
            drawContent()
            if (!reduceMotion() && !lite()) {
                val x = size.width * sweep
                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .7f), Color.Transparent), Offset(x - size.width * .25f, 0f), Offset(x + size.width * .25f, size.height)), blendMode = BlendMode.Overlay)
            }
        }
        .border(1.dp, Color.White.copy(alpha = .35f), sh)
        .clickable(src, null, enabled = enabled, onClick = onClick).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color(0xFF0B0C12), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A gooey switch: the metal thumb stretches as it travels, and the track glows in the accent when it's on. */
@Composable fun LumenSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean) {
    val t = N
    val pos by animateFloatAsState(if (checked) 1f else 0f, spring(.55f, 380f), label = "pos")
    val track by animateColorAsState(if (checked) lerp(t.blue, Color.Black, .3f) else if (t.dark) Color(0x1AFFFFFF) else Color(0x1A0A0C18), label = "tr")
    Box(Modifier.size(48.dp, 26.dp).graphicsLayer { alpha = if (enabled) 1f else .4f }
        .then(if (checked) Modifier.shadow(10.dp, CircleShape, ambientColor = t.blue, spotColor = t.blue) else Modifier)
        .clip(CircleShape).background(track).border(1.dp, if (checked) lerp(t.blue, Color.White, .3f) else edgeColor(t), CircleShape)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(3.dp)) {
        // stretch: widest halfway across (the goo), round at either end
        val stretch = 1f - kotlin.math.abs(pos - .5f) * 2f
        val w = 20.dp + 8.dp * stretch
        Box(Modifier.offset(x = (22.dp + 20.dp - w) * pos).size(w, 20.dp).shadow(2.dp, CircleShape).clip(CircleShape)
            .background(if (checked) SolidColor(Color.White) else lumenMetal(t.dark)))
    }
}

@Composable fun LumenRadio(selected: Boolean, enabled: Boolean) {
    val t = N
    Box(Modifier.size(22.dp).graphicsLayer { alpha = if (enabled) 1f else .5f }
        .then(if (selected) Modifier.shadow(8.dp, CircleShape, ambientColor = t.blue, spotColor = t.blue) else Modifier)
        .clip(CircleShape).background(if (selected) lerp(t.blue, Color.Black, .25f) else Color.Transparent)
        .border(1.5.dp, if (selected) lerp(t.blue, Color.White, .3f) else t.sub, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(9.dp).clip(CircleShape).background(Color.White))
    }
}

/** A Lumen dialog: dark frosted glass with a beam running round it, a metal main button. */
@Composable fun LumenDialogBody(title: String?, text: String?, buttons: List<DialogButton>, content: (@Composable ColumnScope.() -> Unit)?) {
    val t = N; val shape = RoundedCornerShape(24.dp * roundK() * winK())
    Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 480.dp).fillMaxWidth().shadow(30.dp, shape, ambientColor = t.blue.copy(alpha = .5f), spotColor = Color.Black)
        .clip(shape).background(if (t.dark) Color(0xF20D0E15) else Color(0xF5FAFBFF)).border(1.dp, edgeColor(t), shape).lumenBeam(shape, lerp(t.blue, Color.White, .3f), 4500)
        .padding(top = 22.dp, bottom = 16.dp)) {
        DialogToasts()
        if (title != null) Text(title, color = t.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 24.dp))
        if (text != null) Text(text, color = t.text.copy(alpha = .85f), fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = if (title != null) 8.dp else 0.dp))
        if (content != null) Column(Modifier.padding(top = 10.dp)) { content() }
        if (buttons.isNotEmpty()) {
            @Composable fun B(b: DialogButton, m: Modifier) {
                val primary = b.color != null && b.color != t.red
                if (primary) LumenButton(b.label, m.height(46.dp), b.enabled, b.onClick)
                else Box(m.height(46.dp).clip(CircleShape).background(if (b.color == t.red) lerp(t.red, Color.Black, .3f) else t.pill).border(1.dp, edgeColor(t), CircleShape)
                    .clickable(enabled = b.enabled, onClick = b.onClick).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                    Text(b.label, color = if (!b.enabled) t.sub else if (b.color == t.red) Color.White else t.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (buttons.size >= 3) Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons.forEach { B(it, Modifier.fillMaxWidth()) } }
            else Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons.forEach { B(it, Modifier.weight(1f)) } }
        }
    }
}
