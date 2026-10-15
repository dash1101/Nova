package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Frosted glass & depth.
 *
 *  - Things that float over content (bottom nav, back/action buttons, toasts) get a real backdrop
 *    blur via Haze: the page underneath is the "source", the floating element the "effect".
 *  - Cards inside the page get a glass treatment instead: a translucent fill that lets the
 *    background glow through, a soft highlight on the top edge, and a gentle shadow for depth.
 */
val LocalRootHaze = staticCompositionLocalOf<HazeState?> { null }      // the whole page area
val LocalPageHaze = staticCompositionLocalOf<HazeState?> { null }      // one scrolling page

/** Layout rhythm used everywhere, so screens line up. */
object Space {
    val gutter = 16.dp          // screen edge → card
    val inner = 20.dp           // card edge → text
    val gap = 12.dp             // between cards
    val section = 22.dp         // before a section label
}

@Composable fun glassStyle(): HazeStyle = when (N.preset) {
    // Liquid Glass: clear — a little blur, a light tint; Fluent: acrylic (more blur, a bit of grain)
    "liquid" -> HazeStyle(backgroundColor = N.bg, tints = listOf(HazeTint((if (N.dark) Color(0xFF1A1A20) else Color.White).copy(alpha = (if (N.dark) .42f else .5f) - AppPrefs.glass / 100f * .3f))),
        blurRadius = 8.dp, noiseFactor = 0f)
    "fluent" -> HazeStyle(backgroundColor = N.bg, tints = listOf(HazeTint(N.nav.copy(alpha = .78f))), blurRadius = 30.dp, noiseFactor = 0.08f)
    "aero" -> HazeStyle(backgroundColor = N.bg, tints = listOf(HazeTint(aeroFrame(N))), blurRadius = 22.dp, noiseFactor = 0f)
    else -> HazeStyle(backgroundColor = N.bg,
        tints = listOf(HazeTint((if (N.dark) Color(0xFF26262B) else Color.White).copy(alpha = if (N.dark) 0.45f else 0.55f))),
        blurRadius = 26.dp, noiseFactor = 0.05f)
}

/** Soft top-edge highlight — the "light catching the glass" line. */
@Composable private fun edge(): Brush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = if (N.dark) 0.14f else 0.85f), Color.White.copy(alpha = if (N.dark) 0.02f else 0.25f)))

/** Real frosted glass (backdrop blur) for floating elements. Falls back to a translucent fill. */
@Composable fun Modifier.frosted(state: HazeState?, shape: Shape, elevation: Dp = 14.dp, alpha: Float = 1f): Modifier {
    if (N.material || N.preset == "breeze" || solidGlass())    // Material You, Breeze, Reduce transparency: solid surfaces with a soft shadow, no glass
        return this.then(if (elevation > 0.dp && alpha > 0.01f) Modifier.shadow(elevation * 0.5f * alpha, shape) else Modifier).clip(shape)
            .background(N.nav.copy(alpha = alpha))
    val light = lite()                    // older phones: no live blur and a smaller shadow (by far the costliest parts)
    val base = this.then(if (elevation > 0.dp && alpha > 0.01f) Modifier.shadow((if (light) elevation / 3 else elevation) * alpha, shape,
        ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f)) else Modifier).clip(shape)
    val glass = if (state != null && !light) base.hazeEffect(state, glassStyle()) { this.alpha = alpha }
                else base.background((if (N.dark) Color(0xFF26262B) else Color.White).copy(alpha = 0.86f * alpha))
    return if (alpha <= 0.01f) glass else if (N.preset == "liquid") glass.liquidRim(shape) else if (N.preset == "aero") glass.aeroGloss(shape, N.dark) else if (N.preset == "fluent") glass.border(1.dp, if (N.dark) Color(0x14FFFFFF) else Color(0x0F000000), shape)
        else glass.border(0.8.dp, edge(), shape)
}

/** Pop-ups that land on top of any page (toasts, alert banners). Aero frosts them much more heavily, so the words
 *  stay clear over a busy page; the other styles use their usual frosted pane. */
@Composable fun Modifier.popupFrost(state: HazeState?, shape: Shape, elevation: Dp = 10.dp): Modifier {
    if (N.preset != "aero" || state == null || N.material || solidGlass() || lite()) return frosted(state, shape, elevation)
    val f = aeroFrame(N)
    return this.shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f)).clip(shape)
        .hazeEffect(state, HazeStyle(backgroundColor = N.bg, tints = listOf(HazeTint(f.copy(alpha = maxOf(f.alpha, if (N.dark) .84f else .8f)))), blurRadius = 36.dp, noiseFactor = 0f))
        .aeroGloss(shape, N.dark)
}

/** Glass card for content inside a page: translucent, highlighted edge, soft shadow. */
@Composable fun Modifier.glassCard(shape: Shape, elevation: Dp = 6.dp): Modifier =
    if (N.material) this.clip(shape).background(N.card)
    else if (N.preset == "liquid") { val r = cardRadius(26.dp); this.liquidCard(androidx.compose.foundation.shape.RoundedCornerShape(r), r, elevation) }
    else if (N.preset == "aero") this.aeroCard(androidx.compose.foundation.shape.RoundedCornerShape(cardRadius(6.dp)), elevation)
    else if (N.preset.isNotEmpty()) this.presetCard(androidx.compose.foundation.shape.RoundedCornerShape(cardRadius(8.dp)))
    else this.shadow(if (lite()) 0.dp else elevation, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.3f))
        .clip(shape)
        // Solid fill (a see-through one lets the shadow show as a gray box inside the card), lit
        // slightly from the top so it reads as a raised pane of glass.
        .background(Brush.verticalGradient(if (N.dark) listOf(lerp(N.card, Color.White, 0.045f), N.card)
                                           else listOf(Color.White, lerp(Color.White, N.bg, 0.35f))))
        .border(0.8.dp, edge(), shape)
