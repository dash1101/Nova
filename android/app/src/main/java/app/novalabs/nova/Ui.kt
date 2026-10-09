package app.novalabs.nova

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Tokens (sampled from Galaxy Wearable / One UI 9) ──────────────────────────────
data class NovaColors(
    val bg: Color, val glow: Color, val glow2: Color, val card: Color, val pill: Color, val nav: Color,
    val navSel: Color, val text: Color, val sub: Color, val blue: Color, val link: Color,
    val divider: Color, val green: Color, val amber: Color, val red: Color, val dark: Boolean,
    /** Material You Expressive style (Settings → Appearance → Style) instead of the default One UI-like look. */
    val material: Boolean = false, val onBlue: Color = Color.White, val blueContainer: Color = Color(0x333E91FF), val onBlueContainer: Color = Color(0xFF3E91FF),
)
val DarkTokens = NovaColors(Color.Black, Color(0xFF2E2560), Color(0xFF1A2550), Color(0xFF1E1E22), Color(0xFF2A2A2F),
    Color(0xFF242428), Color(0xFF3A3A40), Color.White, Color(0xFF9E9EA4), Color(0xFF3E91FF), Color(0xFF6E9DFF),
    Color(0xFF333338), Color(0xFF3ECF6E), Color(0xFFFFB020), Color(0xFFFF5A5A), true)
val LightTokens = NovaColors(Color(0xFFF4F3F8), Color(0xFFE2DBF7), Color(0xFFDCE6FA), Color.White, Color.White,
    Color.White, Color(0xFFE8E8ED), Color(0xFF111111), Color(0xFF6D6D73), Color(0xFF2F7DF6), Color(0xFF2E68E0),
    Color(0xFFE7E7EB), Color(0xFF1FA34F), Color(0xFFE08A00), Color(0xFFE5484D), false)
val LocalNova = staticCompositionLocalOf { DarkTokens }
val N: NovaColors @Composable get() = LocalNova.current

/** Tokens for Material You: the wallpaper's dynamic colour scheme, mapped onto Nova's roles. */
fun materialTokens(c: ColorScheme, dark: Boolean) = NovaColors(
    bg = c.surfaceContainer, glow = Color.Transparent, glow2 = Color.Transparent,
    card = if (dark) c.surfaceContainerHighest else c.surfaceBright, pill = c.surfaceContainerHigh, nav = c.surfaceContainerHigh,
    navSel = c.secondaryContainer, text = c.onSurface, sub = c.onSurfaceVariant, blue = c.primary, link = c.primary,
    divider = c.surfaceContainer, green = if (dark) Color(0xFF7DDC8E) else Color(0xFF2E7D45), amber = if (dark) Color(0xFFFFC66B) else Color(0xFFA15C00),
    red = c.error, dark = dark, material = true, onBlue = c.onPrimary, blueContainer = c.primaryContainer, onBlueContainer = c.onPrimaryContainer)

@Composable fun NovaTheme(content: @Composable () -> Unit) {
    val dark = when (AppPrefs.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val material = materialStyle()
    val dyn = if (material) (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) else null
    val t = if (dyn != null) materialTokens(dyn, dark) else if (dark) DarkTokens else LightTokens
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) SideEffect {                // status/nav bar icons follow the app's theme, not just the phone's
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    val scheme = dyn ?: if (t.dark) darkColorScheme(primary = t.blue, background = t.bg, surface = t.card, onSurface = t.text)
                 else lightColorScheme(primary = t.blue, background = t.bg, surface = t.card, onSurface = t.text)
    CompositionLocalProvider(LocalNova provides t) { MaterialTheme(colorScheme = scheme, content = content) }
}

/** Per-server accent colour (set in Settings → Server) replaces the blue throughout. */
@Composable fun AccentTheme(hex: String?, content: @Composable () -> Unit) {
    val c = hex?.takeIf { it.length == 7 }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    val t = N
    if (c == null || t.material) { content(); return }          // Material You: the wallpaper's colours win
    CompositionLocalProvider(LocalNova provides t.copy(blue = c, link = lerp(c, Color.White, if (t.dark) 0.25f else 0f)), content = content)
}

fun levelColor(level: String, t: NovaColors) = when (level) {
    "critical" -> t.red; "warning" -> t.amber; "resolved", "ok" -> t.green; else -> t.blue
}

// ── Background with the soft top glow ─────────────────────────────────────────────
@Composable fun GlowBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize()) { GlowLayer(); content() }
}

/** The background colour + glow on its own (pages paint it again inside their blur source). */
@Composable fun GlowLayer() {
    val t = N
    if (t.material) { Box(Modifier.fillMaxSize().background(t.bg)); return }
    Canvas(Modifier.fillMaxSize().background(t.bg)) {
        drawRect(Brush.radialGradient(listOf(t.glow.copy(alpha = if (t.dark) 0.9f else 0.8f), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.18f), radius = size.width * 0.95f))
        drawRect(Brush.radialGradient(listOf(t.glow2.copy(alpha = 0.6f), Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.35f), radius = size.width * 0.8f))
    }
}

// ── Headers ───────────────────────────────────────────────────────────────────────
@Composable fun SectionLabel(text: String) =
    Text(text, color = if (N.material) N.blue else N.sub, fontSize = 14.sp, fontWeight = if (N.material) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.padding(start = Space.gutter + Space.inner - 6.dp, end = 30.dp, top = 18.dp, bottom = 8.dp))

// ── Group cards ───────────────────────────────────────────────────────────────────
@Composable fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)
        .glassCard(RoundedCornerShape(if (N.material) 24.dp else 26.dp)), content = content)
}

/** Default: a hairline. Material You: a small gap, so a group reads as Android 16's segmented list. */
@Composable fun RowDivider() = if (N.material) Box(Modifier.fillMaxWidth().height(3.dp).background(N.bg))
    else HorizontalDivider(Modifier.padding(horizontal = 22.dp), thickness = 0.8.dp, color = N.divider)

@Composable fun Row1(title: String, subtitle: String? = null, subtitleBlue: Boolean = false,
                     icon: ImageVector? = null, iconTint: Color? = null, enabled: Boolean = true,
                     onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val t = N
    Row(Modifier.fillMaxWidth().then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 22.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(36.dp).clip(CircleShape).background((iconTint ?: t.blue).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center) { Icon(icon, null, tint = iconTint ?: t.blue, modifier = Modifier.size(20.dp)) }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, color = if (enabled) t.text else t.sub, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, fontSize = 14.sp, color = if (subtitleBlue) t.link else t.sub,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
    }
}

/** One UI switch row: text area clickable, a thin vertical divider, then the switch. */
@Composable fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, enabled: Boolean = true,
                          subtitleBlue: Boolean = false, onClick: (() -> Unit)? = null, onChange: (Boolean) -> Unit) {
    Row1(title, subtitle, subtitleBlue, enabled = enabled, onClick = onClick ?: { onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onClick != null) Box(Modifier.width(1.dp).height(28.dp).background(N.divider))
            Spacer(Modifier.width(12.dp))
            OneSwitch(checked, onChange, enabled)
        }
    }
}

/** One UI slider: thick rounded track, round white thumb, no Material "gap + stop dot". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun OneSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true,
                          steps: Int = 0, onDone: () -> Unit = {}, modifier: Modifier = Modifier) {
    val t = N
    Slider(value = value, onValueChange = onChange, modifier = modifier, enabled = enabled, valueRange = range,
        steps = steps, onValueChangeFinished = onDone,
        thumb = { Box(Modifier.size(28.dp).clip(CircleShape).background(if (enabled) Color.White else t.sub)
            .then(Modifier)) },
        track = { st: SliderState ->
            val frac = ((st.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(t.divider)) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(5.dp))
                    .background(if (enabled) t.blue else t.sub))
            }
        })
}

/** Fades scrolled content out under the status bar. */
@Composable fun StatusBarScrim(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars.add(WindowInsets(top = 14.dp)))
        .background(Brush.verticalGradient(listOf(N.bg, N.bg.copy(alpha = 0.85f), Color.Transparent))))
}

@Composable fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: String,
                          enabled: Boolean = true, steps: Int = 0, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
        Row { Text(title, fontSize = 17.sp, color = if (enabled) N.text else N.sub, modifier = Modifier.weight(1f))
              Text(label, fontSize = 15.sp, color = N.blue) }
        OneSlider(value, onChange, range, enabled, steps, onDone)
    }
}

@Composable fun LinksCard(links: List<Pair<String, () -> Unit>>) {
    Group {
        Text("Looking for something else?", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = N.text,
            modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 6.dp))
        links.forEach { (label, go) ->
            Text(label, color = N.link, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable(onClick = go)
                .padding(horizontal = 22.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(10.dp))
    }
}

// ── Pills ─────────────────────────────────────────────────────────────────────────
data class PillItem(val icon: ImageVector, val label: String, val badge: Int = 0, val onLongClick: (() -> Unit)? = null, val onClick: () -> Unit)

@Composable fun PillBar(items: List<PillItem>, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = Space.gutter).fillMaxWidth().glassCard(RoundedCornerShape(30.dp))
        .padding(vertical = 12.dp, horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        items.forEach { it ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).bouncy(onLongClick = it.onLongClick, onClick = it.onClick).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    Icon(it.icon, null, tint = N.text, modifier = Modifier.size(26.dp))
                    Box(Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-6).dp)) { OneBadge(it.badge) }
                }
                Spacer(Modifier.height(6.dp))
                Text(it.label, fontSize = if (items.size >= 5) 12.sp else 13.sp, color = N.text, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}

/** True when the tablet / wide layout is in use (screens can rearrange themselves). */
val LocalWide = staticCompositionLocalOf { false }

/** One UI tablet navigation rail: icons in rounded pills down the left edge, with labels. */
@Composable fun NavRail(items: List<Pair<ImageVector, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxHeight().width(96.dp).statusBarsPadding().navigationBarsPadding().padding(top = 28.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, (ic, label) ->
            val bg by animateColorAsState(if (i == selected) N.navSel else Color.Transparent, label = "rail")
            Column(Modifier.clip(RoundedCornerShape(22.dp)).clickable { onSelect(i) }.padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(20.dp)).background(bg), contentAlignment = Alignment.Center) {
                    Icon(ic, label, tint = N.text, modifier = Modifier.size(24.dp))
                }
                Text(label, color = if (i == selected) N.text else N.sub, fontSize = 12.sp, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

/** The floating bottom bar. The "you are here" bead is its own little pane of frosted glass that
 *  slides from tab to tab; hold the bar to choose its tabs. */
@Composable fun FloatingNav(selected: Int, icons: List<ImageVector>, labels: List<String>, onSelect: (Int) -> Unit,
                            modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val itemW = 76.dp; val gap = 4.dp
    val x by androidx.compose.animation.core.animateDpAsState((itemW + gap) * selected.coerceAtLeast(0),
        if (reduceMotion()) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f), label = "bead")
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    if (N.material) {                         // M3 Expressive: a floating bar, pill indicator behind the icon, labels
        Row(modifier.navigationBarsPadding().padding(bottom = 12.dp).frosted(null, RoundedCornerShape(32.dp), 6.dp)
            .pointerInput(onLongClick) { detectTapGestures(onLongPress = { onLongClick?.let { l -> haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); l() } }) }
            .padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icons.forEachIndexed { i, ic ->
                val on = i == selected
                val w by androidx.compose.animation.core.animateDpAsState(if (on) 64.dp else 40.dp, if (reduceMotion()) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(0.55f, 380f), label = "ind")
                Column(Modifier.width(80.dp).clip(RoundedCornerShape(20.dp)).bouncy(onLongClick = onLongClick) { onSelect(i) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(width = w, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) N.navSel else Color.Transparent), contentAlignment = Alignment.Center) {
                        Icon(ic, labels[i], tint = if (on) N.text else N.sub, modifier = Modifier.size(24.dp))
                    }
                    Text(labels[i], fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) N.text else N.sub, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        return
    }
    Box(modifier.navigationBarsPadding().padding(bottom = 14.dp).frosted(LocalRootHaze.current, RoundedCornerShape(40.dp), 18.dp)
        .pointerInput(onLongClick) { detectTapGestures(onLongPress = { onLongClick?.let { l -> haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); l() } }) }
        .padding(6.dp)) {
        if (selected >= 0) Box(Modifier.offset(x = x).size(width = itemW, height = 56.dp)
            .frosted(LocalRootHaze.current, RoundedCornerShape(28.dp), 4.dp)
            .background(if (N.dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.05f)))
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            icons.forEachIndexed { i, ic ->
                Box(Modifier.size(width = itemW, height = 56.dp).clip(RoundedCornerShape(28.dp))
                    .bouncy(onLongClick = onLongClick) { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Icon(ic, labels[i], tint = N.text, modifier = Modifier.size(26.dp))
                }
            }
        }
    }
}

@Composable fun CancelSavePill(onCancel: () -> Unit, onSave: () -> Unit, saveEnabled: Boolean = true,
                               saveLabel: String = "Save", modifier: Modifier = Modifier, cancelLabel: String = "Cancel") {
    Row(modifier.navigationBarsPadding().padding(bottom = 14.dp).frosted(null, RoundedCornerShape(40.dp), 18.dp)
        .padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(cancelLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = N.text,
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onCancel).padding(horizontal = 28.dp, vertical = 14.dp))
        Box(Modifier.width(1.dp).height(22.dp).background(N.divider))
        Text(saveLabel, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (saveEnabled) N.text else N.sub,
            modifier = Modifier.clip(RoundedCornerShape(24.dp)).clickable(enabled = saveEnabled, onClick = onSave)
                .padding(horizontal = 28.dp, vertical = 14.dp))
    }
}

@Composable fun Banner(text: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.padding(horizontal = Space.gutter).fillMaxWidth().glassCard(RoundedCornerShape(26.dp))
        .bouncy(onClick = onClick).padding(horizontal = Space.inner, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(12.dp))
        Text(text, color = N.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = N.text)
    }
}

/** One UI segmented tabs (pill). */
@Composable fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (N.material) {                       // M3 Expressive connected button group
        Row(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            options.forEachIndexed { i, o ->
                val on = i == selected
                val r by androidx.compose.animation.core.animateDpAsState(if (on) 24.dp else 8.dp, androidx.compose.animation.core.spring(0.6f, 500f), label = "seg")
                val shape = RoundedCornerShape(topStart = if (i == 0 || on) 24.dp else r, bottomStart = if (i == 0 || on) 24.dp else r,
                    topEnd = if (i == options.lastIndex || on) 24.dp else r, bottomEnd = if (i == options.lastIndex || on) 24.dp else r)
                Row(Modifier.weight(1f).height(48.dp).clip(shape).background(if (on) N.blue else N.pill).clickable { onSelect(i) }.padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    if (on) { Icon(androidx.compose.material.icons.Icons.Rounded.Done, null, tint = N.onBlue, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)) }
                    Text(o, color = if (on) N.onBlue else N.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    Row(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp).fillMaxWidth().glassCard(RoundedCornerShape(24.dp), 3.dp)
        .padding(4.dp)) {
        options.forEachIndexed { i, o ->
            Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(if (i == selected) N.navSel else Color.Transparent)
                .clickable { onSelect(i) }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(o, color = N.text, fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp)
            }
        }
    }
}

@Composable fun Callout(text: String, size: Dp = 108.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).background(if (N.dark) Color.Black else Color(0xFF111111))
        .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.padding(10.dp))
    }
}

@Composable fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true,
                              color: Color? = null, onClick: () -> Unit) {
    val bg = color ?: N.blue
    if (N.material) {                        // Expressive: taller pill whose corners tighten while pressed
        val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val pressed by src.collectIsPressedAsState()
        val r by androidx.compose.animation.core.animateDpAsState(if (pressed && !reduceMotion()) 12.dp else 28.dp, androidx.compose.animation.core.spring(0.55f, 600f), label = "btn")
        Box(modifier.height(56.dp).clip(RoundedCornerShape(r)).background(if (enabled) bg else bg.copy(alpha = 0.35f))
            .clickable(src, androidx.compose.material3.ripple(), enabled = enabled, onClick = onClick).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (bg == N.pill || bg == N.card) N.text else if (bg == N.blue) N.onBlue else Color.White)
        }
        return
    }
    Box(modifier.height(52.dp).clip(RoundedCornerShape(26.dp)).background(if (enabled) bg else bg.copy(alpha = 0.35f))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = if (bg == N.pill || bg == N.card) N.text else Color.White.copy(alpha = if (enabled) 1f else 0.7f))
    }
}

@Composable fun UsageBar(fraction: Float, color: Color) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(N.divider)) {
        Box(Modifier.fillMaxWidth(if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
    }
}

val Mono = FontFamily.Monospace

fun bytesHuman(b: Long): String {
    val u = listOf("B", "KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = 0
    while (v >= 1000 && i < u.lastIndex) { v /= 1000; i++ }
    return if (i >= 3) "%.1f %s".format(v, u[i]) else "%.0f %s".format(v, u[i])
}
