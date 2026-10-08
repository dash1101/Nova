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

@Composable fun glassStyle(): HazeStyle = HazeStyle(
    backgroundColor = N.bg,
    tints = listOf(HazeTint((if (N.dark) Color(0xFF26262B) else Color.White).copy(alpha = if (N.dark) 0.45f else 0.55f))),
    blurRadius = 26.dp,
    noiseFactor = 0.05f,
)

/** Soft top-edge highlight — the "light catching the glass" line. */
@Composable private fun edge(): Brush = Brush.verticalGradient(
    listOf(Color.White.copy(alpha = if (N.dark) 0.14f else 0.85f), Color.White.copy(alpha = if (N.dark) 0.02f else 0.25f)))

/** Real frosted glass (backdrop blur) for floating elements. Falls back to a translucent fill. */
@Composable fun Modifier.frosted(state: HazeState?, shape: Shape, elevation: Dp = 14.dp, alpha: Float = 1f): Modifier {
    val base = this.then(if (elevation > 0.dp && alpha > 0.01f) Modifier.shadow(elevation * alpha, shape,
        ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f)) else Modifier).clip(shape)
    val glass = if (state != null) base.hazeEffect(state, glassStyle()) { this.alpha = alpha }
                else base.background((if (N.dark) Color(0xFF26262B) else Color.White).copy(alpha = 0.86f * alpha))
    return if (alpha > 0.01f) glass.border(0.8.dp, edge(), shape) else glass
}

/** Glass card for content inside a page: translucent, highlighted edge, soft shadow. */
@Composable fun Modifier.glassCard(shape: Shape, elevation: Dp = 6.dp): Modifier =
    this.shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.3f))
        .clip(shape)
        // Solid fill (a see-through one lets the shadow show as a grey box inside the card), lit
        // slightly from the top so it reads as a raised pane of glass.
        .background(Brush.verticalGradient(if (N.dark) listOf(lerp(N.card, Color.White, 0.045f), N.card)
                                           else listOf(Color.White, lerp(Color.White, N.bg, 0.35f))))
        .border(0.8.dp, edge(), shape)
