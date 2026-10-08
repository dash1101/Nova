package app.novalabs.nova

import androidx.compose.animation.core.*
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

/** What the fan ring should look like, derived from the server's fan state. */
data class FanLook(val on: Boolean, val effect: String, val color: Color, val color2: Color,
                   val brightness: Float, val speed: Int, val rainbow: Boolean)

fun fanLook(f: JSONObject?): FanLook {
    fun c(s: String, d: String) = Color(android.graphics.Color.parseColor(f?.optString(s, d)?.ifEmpty { d } ?: d))
    val ov = f?.optJSONObject("status_override")
    return FanLook(on = (ov?.optBoolean("on") ?: f?.optBoolean("on", true)) ?: true,
        effect = ov?.optString("effect") ?: f?.optString("effect", "static") ?: "static",
        color = if (ov != null) Color(android.graphics.Color.parseColor(ov.optString("color", "#ffffff"))) else c("color", "#3e91ff"),
        color2 = c("color2", "#bf5af2"), brightness = ((f?.optInt("brightness", 50) ?: 50) / 100f).coerceIn(0f, 1f),
        speed = f?.optInt("speed", 4) ?: 4, rainbow = f?.optBoolean("rainbow", true) ?: true)
}

private val RAINBOW = listOf(Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759),
    Color(0xFF00C7BE), Color(0xFF007AFF), Color(0xFFAF52DE), Color(0xFFFF3B30))

@Composable private fun anim(look: FanLook): Pair<Float, Float> {
    val dur = (3200 - look.speed * 280).coerceAtLeast(500)
    val inf = rememberInfiniteTransition(label = "fan")
    val phase by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(dur, easing = LinearEasing)), label = "phase")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "spin")
    return phase to spin
}

/** Glowing ARGB fan ring + spinning blades, centred at [c] with radius [r]. */
private fun DrawScope.fanRing(c: Offset, r: Float, look: FanLook, phase: Float, spin: Float, dark: Boolean) {
    val frame = if (dark) Color(0xFF151518) else Color(0xFF2A2A30)
    drawCircle(frame, r * 1.12f, c)
    if (look.on) {
        val pulse = when (look.effect) {
            "pulse" -> 0.25f + 0.75f * (0.5f + 0.5f * kotlin.math.sin(phase * 2 * Math.PI).toFloat())
            "blink" -> if (phase < 0.5f) 1f else 0.08f
            else -> 1f
        }
        val a = (0.25f + 0.75f * look.brightness) * pulse
        val brush: Brush = when {
            look.effect == "gradient" -> Brush.sweepGradient(listOf(look.color, look.color2, look.color), c)
            (look.effect == "cycle" || look.effect == "random") && look.rainbow ->
                SolidColor(RAINBOW[(phase * 7).toInt().coerceIn(0, 6)])
            look.effect == "wave" && look.rainbow -> Brush.sweepGradient(RAINBOW, c)
            look.effect == "wave" -> Brush.sweepGradient(listOf(look.color, look.color.copy(alpha = 0.15f), look.color), c)
            else -> SolidColor(look.color)
        }
        rotate(if (look.effect == "wave") phase * 360f else 0f, c) {
            for (i in 6 downTo 1) {     // soft bloom
                drawCircle(brush, r * (1.0f + i * 0.06f), c, alpha = a * 0.07f * (7 - i) / 6f, style = Stroke(r * 0.08f * i))
            }
            drawCircle(brush, r, c, alpha = a, style = Stroke(r * 0.12f))
        }
    } else {
        drawCircle(Color(0xFF2C2C30), r, c, style = Stroke(r * 0.12f))
    }
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

/** The home-screen server: tower case, glass side, live fan, drive LEDs, power LED in status colour. */
@Composable fun ServerHero(fan: JSONObject?, statusColor: Color, modifier: Modifier = Modifier) {
    val look = fanLook(fan); val (phase, spin) = anim(look); val dark = N.dark
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val cw = (w * 0.56f).coerceAtMost(h * 0.62f); val ch = cw * 1.42f
        val left = (w - cw) / 2; val top = (h - ch) / 2
        // shadow
        drawOval(Color.Black.copy(alpha = if (dark) 0.6f else 0.18f), Offset(left + cw * 0.05f, top + ch * 0.97f), Size(cw * 0.9f, ch * 0.06f))
        // case
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF4A4A52), Color(0xFF26262B), Color(0xFF1B1B1F)), top, top + ch),
            Offset(left, top), Size(cw, ch), CornerRadius(cw * 0.07f))
        drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(left, top), Size(cw, ch), CornerRadius(cw * 0.07f), style = Stroke(2.5f))
        // glass side panel
        val gp = cw * 0.07f
        drawRoundRect(Brush.linearGradient(listOf(Color(0xFF101014), Color(0xFF17171C)), Offset(left, top), Offset(left + cw, top + ch)),
            Offset(left + gp, top + gp), Size(cw - 2 * gp, ch - 2 * gp * 1.6f), CornerRadius(cw * 0.04f))
        // fan glow onto the glass
        if (look.on) drawCircle(Brush.radialGradient(listOf(look.color.copy(alpha = 0.22f * look.brightness + 0.05f), Color.Transparent),
            Offset(left + cw / 2, top + ch * 0.33f), cw * 0.6f), cw * 0.6f, Offset(left + cw / 2, top + ch * 0.33f))
        fanRing(Offset(left + cw / 2, top + ch * 0.33f), cw * 0.29f, look, phase, spin, dark)
        // motherboard / GPU hint
        drawRoundRect(Color(0xFF26262C), Offset(left + cw * 0.16f, top + ch * 0.62f), Size(cw * 0.68f, ch * 0.07f), CornerRadius(8f))
        drawRoundRect(Color(0xFF1F1F25), Offset(left + cw * 0.16f, top + ch * 0.72f), Size(cw * 0.68f, ch * 0.05f), CornerRadius(8f))
        // drive-bay activity LEDs
        for (i in 0 until 6) drawCircle(Color(0xFF3ECF6E).copy(alpha = if ((phase * 6).toInt() == i) 0.95f else 0.45f), cw * 0.014f,
            Offset(left + cw * (0.28f + i * 0.09f), top + ch * 0.83f))
        // reflection
        drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), Offset(left, top), Offset(left + cw * 0.5f, top + ch * 0.5f)),
            Offset(left + gp, top + gp), Size(cw * 0.42f, ch * 0.55f))
        // power LED (server health)
        drawCircle(statusColor.copy(alpha = 0.35f), cw * 0.03f, Offset(left + cw * 0.5f, top + ch * 0.935f))
        drawCircle(statusColor, cw * 0.016f, Offset(left + cw * 0.5f, top + ch * 0.935f))
    }
}

/** Big fan for the Lighting page. */
@Composable fun FanHero(fan: JSONObject?, modifier: Modifier = Modifier) {
    val look = fanLook(fan); val (phase, spin) = anim(look); val dark = N.dark
    Canvas(modifier) {
        val r = size.minDimension * 0.34f; val c = Offset(size.width / 2, size.height / 2)
        if (look.on) drawCircle(Brush.radialGradient(listOf(look.color.copy(alpha = 0.25f * look.brightness + 0.05f), Color.Transparent), c, r * 1.9f), r * 1.9f, c)
        fanRing(c, r, look, phase, spin, dark)
    }
}
