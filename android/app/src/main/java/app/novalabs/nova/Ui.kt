package app.novalabs.nova

import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.zIndex
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ChevronLeft
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
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.graphicsLayer
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
    /** Liquid Glass, Fluent or Breeze (Liquid.kt), "" otherwise. */
    val preset: String = "",
)
val DarkTokens = NovaColors(Color.Black, Color(0xFF2E2560), Color(0xFF1A2550), Color(0xFF1E1E22), Color(0xFF2A2A2F),
    Color(0xFF242428), Color(0xFF3A3A40), Color.White, Color(0xFF9E9EA4), Color(0xFF3E91FF), Color(0xFF6E9DFF),
    Color(0xFF333338), Color(0xFF3ECF6E), Color(0xFFFFB020), Color(0xFFFF5A5A), true)
val LightTokens = NovaColors(Color(0xFFF4F3F8), Color(0xFFE2DBF7), Color(0xFFDCE6FA), Color.White, Color.White,
    Color.White, Color(0xFFE8E8ED), Color(0xFF111111), Color(0xFF6D6D73), Color(0xFF2F7DF6), Color(0xFF2E68E0),
    Color(0xFFE7E7EB), Color(0xFF1FA34F), Color(0xFFE08A00), Color(0xFFE5484D), false)
val LocalNova = staticCompositionLocalOf { DarkTokens }
val N: NovaColors @Composable get() = LocalNova.current

/** Tokens for Material You: the wallpaper's dynamic color scheme, mapped onto Nova's roles. */
fun materialTokens(c: ColorScheme, dark: Boolean) = NovaColors(
    bg = c.surfaceContainer, glow = Color.Transparent, glow2 = Color.Transparent,
    card = if (dark) c.surfaceContainerHighest else c.surfaceBright, pill = c.surfaceContainerHigh, nav = c.surfaceContainerHigh,
    navSel = c.secondaryContainer, text = c.onSurface, sub = c.onSurfaceVariant, blue = c.primary, link = c.primary,
    divider = c.surfaceContainer, green = if (dark) Color(0xFF7DDC8E) else Color(0xFF2E7D45), amber = if (dark) Color(0xFFFFC66B) else Color(0xFFA15C00),
    red = c.error, dark = dark, material = true, onBlue = c.onPrimary, blueContainer = c.primaryContainer, onBlueContainer = c.onPrimaryContainer)

/** A color with the seed's hue at a Material tone (0 = black … 100 = white), chroma scaled by [sat]. */
private fun tone(seed: Color, t: Float, sat: Float = 1f): Color {
    val hsl = FloatArray(3); androidx.core.graphics.ColorUtils.colorToHSL(seed.toArgb(), hsl)
    hsl[1] = (hsl[1] * sat).coerceIn(0f, 1f); hsl[2] = (t / 100f).coerceIn(0f, 1f)
    return Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
}

/** A Material You scheme from one color (Appearance → Accent color), the way Android builds one from the wallpaper. */
fun seedScheme(seed: Color, dark: Boolean): ColorScheme = if (dark) androidx.compose.material3.darkColorScheme(
    primary = tone(seed, 80f), onPrimary = tone(seed, 20f), primaryContainer = tone(seed, 30f), onPrimaryContainer = tone(seed, 90f),
    secondary = tone(seed, 80f, .4f), onSecondary = tone(seed, 20f, .4f), secondaryContainer = tone(seed, 30f, .4f), onSecondaryContainer = tone(seed, 90f, .4f),
    tertiary = tone(seed.copy(red = seed.blue, blue = seed.red), 80f, .6f), background = tone(seed, 6f, .25f), surface = tone(seed, 6f, .25f),
    onSurface = tone(seed, 90f, .1f), onSurfaceVariant = tone(seed, 80f, .15f), surfaceContainer = tone(seed, 12f, .25f),
    surfaceContainerHigh = tone(seed, 17f, .25f), surfaceContainerHighest = tone(seed, 22f, .25f), surfaceBright = tone(seed, 24f, .25f), outline = tone(seed, 60f, .15f))
else androidx.compose.material3.lightColorScheme(
    primary = tone(seed, 40f), onPrimary = Color.White, primaryContainer = tone(seed, 90f), onPrimaryContainer = tone(seed, 10f),
    secondary = tone(seed, 40f, .4f), onSecondary = Color.White, secondaryContainer = tone(seed, 90f, .4f), onSecondaryContainer = tone(seed, 10f, .4f),
    tertiary = tone(seed.copy(red = seed.blue, blue = seed.red), 40f, .6f), background = tone(seed, 98f, .25f), surface = tone(seed, 98f, .25f),
    onSurface = tone(seed, 10f, .1f), onSurfaceVariant = tone(seed, 30f, .15f), surfaceContainer = tone(seed, 94f, .25f),
    surfaceContainerHigh = tone(seed, 92f, .25f), surfaceContainerHighest = tone(seed, 90f, .25f), surfaceBright = tone(seed, 98f, .25f), outline = tone(seed, 50f, .15f))

/** Color strength (Appearance): 0 = plain grey surfaces, 50 = as Android makes them, 100 = strongly tinted. */
private fun tinted(c: ColorScheme, strength: Int): ColorScheme {
    if (strength == 50) return c
    fun f(x: Color): Color = if (strength < 50) {
        val g = (x.red * .299f + x.green * .587f + x.blue * .114f); lerp(Color(g, g, g, x.alpha), x, strength / 50f)
    } else lerp(x, c.primary, (strength - 50) / 50f * .22f)
    return c.copy(background = f(c.background), surface = f(c.surface), surfaceContainer = f(c.surfaceContainer), surfaceContainerHigh = f(c.surfaceContainerHigh),
        surfaceContainerHighest = f(c.surfaceContainerHighest), surfaceBright = f(c.surfaceBright), secondaryContainer = f(c.secondaryContainer))
}

@Composable fun NovaTheme(content: @Composable () -> Unit) {
    val dark = when (AppPrefs.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val material = materialStyle()
    val seed = AppPrefs.accent.takeIf { it.length == 7 }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    // your own accent → a scheme from it; else the wallpaper's colors (Android 12+); before that, Material's own baseline palette
    val dyn0 = if (!material) null else if (seed != null) seedScheme(seed, dark) else if (Compat.S) (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
              else (if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme())
    val dyn = dyn0?.let { tinted(it, AppPrefs.tint) }
    val t = if (dyn != null) materialTokens(dyn, dark) else presetTokens(presetStyle(), dark, seed) ?: if (dark) DarkTokens else LightTokens
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) SideEffect {                // status/nav bar icons follow the app's theme, not just the phone's
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        }
    }
    val scheme = dyn ?: if (t.dark) darkColorScheme(primary = t.blue, background = t.bg, surface = t.card, onSurface = t.text)
                 else lightColorScheme(primary = t.blue, background = t.bg, surface = t.card, onSurface = t.text)
    // "Match my accent color" (Appearance → App icon): the launcher icon follows the colors
    val iconColor = if (material) t.blue else seed ?: t.blue
    LaunchedEffect(iconColor, dark) { if (!view.isInEditMode) runCatching { AppIcon.follow(ctx, iconColor.toArgb(), dark) } }
    CompositionLocalProvider(LocalNova provides t) { MaterialTheme(colorScheme = scheme, content = content) }
}

/** Per-server accent color (set in Settings → Server) replaces the blue throughout. */
@Composable fun AccentTheme(hex: String?, content: @Composable () -> Unit) {
    // this phone's own accent wins over the server's
    val c = (AppPrefs.accent.takeIf { it.length == 7 } ?: hex)?.takeIf { it.length == 7 }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
    val t = N
    if (c == null || t.material) { content(); return }          // Material You: the wallpaper's colors win
    CompositionLocalProvider(LocalNova provides t.copy(blue = c, link = lerp(c, Color.White, if (t.dark) 0.25f else 0f)), content = content)
}

fun levelColor(level: String, t: NovaColors) = when (level) {
    "critical" -> t.red; "warning" -> t.amber; "resolved", "ok" -> t.green; else -> t.blue
}

// ── Background with the soft top glow ─────────────────────────────────────────────
@Composable fun GlowBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize()) { GlowLayer(); content() }
}

/** The background color + glow on its own (pages paint it again inside their blur source). */
@Composable fun GlowLayer() {
    val t = N
    if (AppPrefs.wallV > 0L) { WallpaperLayer(); return }
    if (t.material || t.glow == Color.Transparent) { Box(Modifier.fillMaxSize().background(t.bg)); return }
    // drawn in screen coordinates: every layer (a page, the pane beside it, the strip behind the side pill)
    // shows its own part of one glow, so there's never a seam between them
    var origin by remember { mutableStateOf(Offset.Zero) }
    val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    Canvas(Modifier.fillMaxSize().background(t.bg).onGloballyPositioned { origin = it.positionInWindow() }) {
        val W = if (win.width > 0) win.width.toFloat() else size.width; val H = if (win.height > 0) win.height.toFloat() else size.height
        drawRect(Brush.radialGradient(listOf(t.glow.copy(alpha = if (t.dark) 0.9f else 0.8f), Color.Transparent),
            center = Offset(W * 0.85f, H * 0.18f) - origin, radius = W * 0.95f))
        drawRect(Brush.radialGradient(listOf(t.glow2.copy(alpha = 0.6f), Color.Transparent),
            center = Offset(W * 0.1f, H * 0.35f) - origin, radius = W * 0.8f))
    }
}

// ── Headers ───────────────────────────────────────────────────────────────────────
@Composable fun SectionLabel(text: String) =
    Text(text, color = if (N.material) N.blue else N.sub, fontSize = 14.sp, fontWeight = if (N.material) FontWeight.SemiBold else FontWeight.Medium,
        modifier = Modifier.padding(start = Space.gutter + Space.inner - 6.dp, end = 30.dp, top = 18.dp, bottom = 8.dp))

// ── Group cards ───────────────────────────────────────────────────────────────────
@Composable fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)
        .glassCard(RoundedCornerShape(if (N.material) 24.dp else cardRadius(26.dp))), content = content)
}

/** Default: a hairline. Material You: a small gap, so a group reads as Android 16's segmented list. */
@Composable fun RowDivider() = if (N.material) Box(Modifier.fillMaxWidth().height(3.dp).background(N.bg))
    else HorizontalDivider(Modifier.padding(horizontal = 22.dp), thickness = 0.8.dp, color = N.divider)

/** A row's description: three lines, and if there's more, a small arrow that opens the rest (and closes it again). */
/** An explanation under a section (instructions, what something does): long ones fold to two lines with an arrow to open them. */
@Composable fun Note(text: String, size: androidx.compose.ui.unit.TextUnit = 13.sp, vertical: Dp = 4.dp, color: Color = N.sub) =
    Box(Modifier.padding(horizontal = 30.dp, vertical = vertical)) { ExpandableSub(text, color, lines = 2, size = size) }

@Composable fun ExpandableSub(text: String, color: Color, lines: Int = 3, size: androidx.compose.ui.unit.TextUnit = 14.sp) {
    var open by remember(text) { mutableStateOf(false) }
    var more by remember(text) { mutableStateOf(false) }
    Row(Modifier.padding(top = 2.dp).then(if (more || open) Modifier.animateContentSize() else Modifier), verticalAlignment = Alignment.Bottom) {   // only rows that can open pay for it
        Text(text, fontSize = size, color = color, maxLines = if (open) Int.MAX_VALUE else lines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!open) more = it.hasVisualOverflow }, modifier = Modifier.weight(1f, fill = false))
        if (more || open) {
            val turn by animateFloatAsState(if (open) 180f else 0f, label = "more")
            Box(Modifier.padding(start = 2.dp).size(28.dp).clip(CircleShape).clickable { open = !open }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.KeyboardArrowDown, if (open) "Show less" else "Show more", tint = N.sub, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
            }
        }
    }
}

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
            if (!subtitle.isNullOrEmpty()) ExpandableSub(subtitle, if (subtitleBlue) t.link else t.sub)
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
/** The same soft fade at the bottom edge, so content slides out of sight under the navigation pill. */
/** A phone on its side: little height, so titles, padding and fades shrink and the status bar hides. */
val LocalCompactHeight = staticCompositionLocalOf { false }

@Composable fun BottomScrim(modifier: Modifier = Modifier) {
    if (LocalCompactHeight.current) return                 // a phone on its side: no room for fades
    Box(modifier.fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars.add(WindowInsets(bottom = if (LocalCompactHeight.current) 16.dp else 56.dp)))
        .background(Brush.verticalGradient(listOf(Color.Transparent, N.bg.copy(alpha = 0.85f), N.bg))))
}

@Composable fun StatusBarScrim(modifier: Modifier = Modifier) {
    if (LocalCompactHeight.current) return
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

/** The floating bottom bar. The "you are here" bead is its own little pane of frosted glass that
 *  slides from tab to tab; hold the bar to choose its tabs. */
@Composable fun FloatingNav(selected: Int, icons: List<ImageVector>, labels: List<String>, onSelect: (Int) -> Unit,
                            modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null,
                            apps: List<AppSessions.Session> = emptyList(), selectedApp: String? = null, onApp: (String) -> Unit = {},
                            onAppHold: ((String) -> Unit)? = null) {
    val itemW = 76.dp; val gap = 4.dp
    // Both parts can scroll: the tabs (as many as you like) and the apps strip. The bead rides along with
    // whichever it's on, and fades as that item scrolls out of view.
    val strip = rememberScrollState(); val tabScroll = rememberScrollState()
    val inner = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 40.dp - 12.dp
    val tabsFull = (itemW + gap) * icons.size - gap
    val tabsRoom = if (apps.isEmpty()) inner else (inner - gap - 9.dp - gap - 52.dp * minOf(apps.size, 2)).coerceAtLeast(itemW * 2)
    // more tabs than fit: stop halfway through one, so you can see there are more to slide to
    val tabsView = if (tabsFull <= tabsRoom) tabsFull else peek(tabsRoom, itemW + gap)
    val room = (inner - tabsView - gap - 9.dp - gap).coerceAtLeast(56.dp)
    val stripStart = tabsView + gap + 9.dp + gap                          // 9dp: the hairline and its padding
    val stripW = minOf(room, 52.dp * apps.size)
    val bead = rememberPillBead(selected, apps.indexOfFirst { it.id == selectedApp }, itemW, gap, stripStart)
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(selected, icons.size) { if (selected >= 0) with(density) {          // the tab you're on scrolls into view
        val x = ((itemW + gap) * selected).roundToPx(); val w = itemW.roundToPx(); val v = tabsView.roundToPx()
        val to = when { x < tabScroll.value -> x; x + w > tabScroll.value + v -> x + w - v; else -> tabScroll.value }
        if (to != tabScroll.value) tabScroll.animateScrollTo(to.coerceIn(0, tabScroll.maxValue)) } }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    if (N.material) {                         // M3 Expressive: a floating bar, pill indicator behind the icon, labels
        Row(modifier.navigationBarsPadding().padding(bottom = 12.dp).frosted(null, RoundedCornerShape(32.dp), 6.dp)
            .pointerInput(onLongClick) { detectTapGestures(onLongPress = { onLongClick?.let { l -> haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); l() } }) }
            .padding(horizontal = 8.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val mInner = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 40.dp - 16.dp
            val mFull = 84.dp * icons.size - 4.dp
            val mRoom = if (apps.isEmpty()) mInner else (mInner - 61.dp - 52.dp * minOf(apps.size, 2)).coerceAtLeast(160.dp)
            val mView = if (mFull <= mRoom) mFull else peek(mRoom, 84.dp)
            val mScroll = rememberScrollState()
            LaunchedEffect(selected) { if (selected >= 0) with(density) { val x = (84.dp * selected).roundToPx(); val w = 80.dp.roundToPx(); val v = mView.roundToPx()
                val to = when { x < mScroll.value -> x; x + w > mScroll.value + v -> x + w - v; else -> mScroll.value }; if (to != mScroll.value) mScroll.animateScrollTo(to.coerceIn(0, mScroll.maxValue)) } }
            Row(Modifier.width(mView).fadeEdges(mScroll, true, 40.dp).horizontalScroll(mScroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
            PillApps(apps, selectedApp, onApp, vertical = false, maxWidth = (mInner - mView - 13.dp).coerceAtLeast(56.dp), onHold = onAppHold)
        }
        return
    }
    Box(modifier.navigationBarsPadding().padding(bottom = 14.dp).frosted(LocalRootHaze.current, RoundedCornerShape(40.dp), 18.dp)
        .pointerInput(onLongClick) { detectTapGestures(onLongPress = { onLongClick?.let { l -> haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); l() } }) }
        .padding(6.dp)) {
        // on an app the bead narrows to a true circle around it (52 across, centered in the pill's height).
        // Everything here is read while laying out / drawing, so a moving bead never recomposes the bar.
        Box(Modifier
            .layout { m, _ ->
                val w = bead.w.value.dp.roundToPx(); val h = minOf(56f, bead.w.value).dp.roundToPx()
                val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
                layout(w, h) { pl.place(((bead.x.value - strip.value.toDp().value * bead.onApp.value - tabScroll.value.toDp().value * (1f - bead.onApp.value)).dp.roundToPx()), (56.dp.roundToPx() - h) / 2) }
            }
            .graphicsLayer {
                val a = bead.onApp.value
                val x = bead.x.value - strip.value.toDp().value * a - tabScroll.value.toDp().value * (1f - a)
                // out of its strip's view: fade (the apps strip, or the tabs when they scroll)
                val lo = stripStart.value * a; val hi = (stripStart + stripW - 52.dp).value * a + (tabsView - itemW).value * (1f - a)
                val out = maxOf(lo - x, x - hi, 0f) / 44f
                alpha = bead.shown.value * (1f - out).coerceIn(0f, 1f)
            }
            .frosted(LocalRootHaze.current, RoundedCornerShape(50), 4.dp)
            .background(if (N.dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.05f)))
        Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(tabsView).fadeEdges(tabScroll, true, 40.dp).horizontalScroll(tabScroll), horizontalArrangement = Arrangement.spacedBy(gap)) {
                icons.forEachIndexed { i, ic ->
                    Box(Modifier.size(width = itemW, height = 56.dp).clip(RoundedCornerShape(28.dp))
                        .bouncy(onLongClick = onLongClick) { onSelect(i) }, contentAlignment = Alignment.Center) {
                        Icon(ic, labels[i], tint = N.text, modifier = Modifier.size(26.dp))
                    }
                }
            }
            PillApps(apps, selectedApp, onApp, vertical = false, maxWidth = room, sc = strip, beadMarks = true, onHold = onAppHold)
        }
    }
}

/** A strip width that ends halfway through an item (whole items, then half of the next). */
private fun peek(room: Dp, per: Dp): Dp { val k = (room / per).toInt(); return (per * k - per / 2).coerceAtLeast(per) }

/** How much of the screen's width is left for open apps after the tabs (the pill never grows past the screen). */
@Composable private fun appRoom(tabs: Int, perTab: Dp): Dp =
    (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 40.dp - perTab * tabs - 14.dp).coerceAtLeast(56.dp)

/** Fades the start/end of a scrolling strip when there's more to see that way. */
fun Modifier.fadeEdges(s: androidx.compose.foundation.ScrollState, horizontal: Boolean, edge: Dp = 22.dp) = this
    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val len = if (horizontal) size.width else size.height; if (len <= 0f) return@drawWithContent
        val f = (edge.toPx() / len).coerceAtMost(0.45f)
        val start = if (s.value > 0) Color.Transparent else Color.Black; val end = if (s.value < s.maxValue) Color.Transparent else Color.Black
        val stops = arrayOf(0f to start, f to Color.Black, 1f - f to Color.Black, 1f to end)
        drawRect(if (horizontal) Brush.horizontalGradient(*stops) else Brush.verticalGradient(*stops), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
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

/** The pill's sliding bead: on a tab, or on an open app past the divider. It remembers where it
 *  last was, so it glides from there (fading out on pages that aren't in the pill) instead of
 *  jumping in from the first tab. [item]/[appItem]: a tab's and an app's size along the pill. */
class Bead(val pos: Dp, val size: Dp, val alpha: Float)
@Composable fun rememberBead(selected: Int, tabs: Int, apps: List<AppSessions.Session>, selectedApp: String?, item: Dp, appItem: Dp, gap: Dp,
                             appShift: ((Int) -> Dp)? = null): Bead {
    val j = apps.indexOfFirst { it.id == selectedApp }
    val target = when {
        selected >= 0 -> (item + gap) * selected to item
        // a scrolling strip: its items sit side by side (no gap); appShift says how far it's scrolled
        j >= 0 && appShift != null -> (item + gap) * tabs + 9.dp + gap + appItem * j + appShift(j) to appItem
        j >= 0 -> (item + gap) * tabs + 9.dp + gap + (appItem + gap) * j to appItem     // 9dp: the hairline and its padding
        else -> null
    }
    var last by remember { mutableStateOf(target ?: (0.dp to item)) }
    if (target != null) last = target
    val spec = if (reduceMotion()) androidx.compose.animation.core.snap<Dp>() else androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 420f)
    val pos by androidx.compose.animation.core.animateDpAsState(last.first, spec, label = "bead")
    val size by androidx.compose.animation.core.animateDpAsState(last.second, spec, label = "beadSize")
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (target != null) 1f else 0f, label = "beadAlpha")
    return Bead(pos, size, alpha)
}

/** The floating bar's bead, in the bar's own coordinates (dp): where it is, how wide, how much it's
 *  "on an app" (0 on a tab, 1 on an app — the strip's scroll is subtracted by that much, so moving
 *  between a tab and an app blends smoothly), and how visible. */
class PillBead(val x: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
               val w: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
               val onApp: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
               val shown: androidx.compose.animation.core.Animatable<Float, androidx.compose.animation.core.AnimationVector1D>)
@Composable fun rememberPillBead(selected: Int, appIndex: Int, item: Dp, gap: Dp, stripStart: Dp): PillBead {
    // (x, width, onApp) for where it should be; null on pages that aren't in the bar (it fades where it was)
    val target = when {
        selected >= 0 -> Triple(((item + gap) * selected).value, item.value, 0f)
        appIndex >= 0 -> Triple((stripStart + 52.dp * appIndex).value, 52f, 1f)
        else -> null
    }
    val A = { v: Float -> androidx.compose.animation.core.Animatable(v) }
    val b = remember { (target ?: Triple(0f, item.value, 0f)).let { (x, w, a) -> PillBead(A(x), A(w), A(a), A(if (target != null) 1f else 0f)) } }
    val reduce = reduceMotion()
    LaunchedEffect(target) {
        if (target == null) { b.shown.animateTo(0f, androidx.compose.animation.core.tween(180)); return@LaunchedEffect }
        val spec = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.78f, stiffness = 380f)
        val (x, w, a) = target
        if (reduce || b.shown.value == 0f) { b.x.snapTo(x); b.w.snapTo(w); b.onApp.snapTo(a) }   // nothing to glide from
        launch { b.shown.animateTo(1f, androidx.compose.animation.core.tween(160)) }
        launch { b.x.animateTo(x, spec) }; launch { b.w.animateTo(w, spec) }; launch { b.onApp.animateTo(a, spec) }
    }
    return b
}

/** One UI segmented tabs (pill). */
/** Open apps in the navigation pill: a hairline, then each app's icon (a ring on the one you're in). */
@Composable fun PillApps(apps: List<AppSessions.Session>, selected: String?, onApp: (String) -> Unit, vertical: Boolean, maxWidth: Dp? = null,
                         sc: androidx.compose.foundation.ScrollState = rememberScrollState(), beadMarks: Boolean = false, onHold: ((String) -> Unit)? = null) {
    if (apps.isEmpty()) return
    if (vertical) Box(Modifier.padding(vertical = 4.dp).width(32.dp).height(1.dp).background(N.sub.copy(alpha = 0.35f)))
    else Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(30.dp).background(N.sub.copy(alpha = 0.35f)))
    if (!vertical && maxWidth != null) {
        // more apps than fit: they slide, with the edges fading; the one you're in scrolls into view
        val density = androidx.compose.ui.platform.LocalDensity.current
        val idx = apps.indexOfFirst { it.id == selected }
        LaunchedEffect(idx, apps.size) { if (idx >= 0) with(density) { sc.animateScrollTo(((52.dp * idx) - maxWidth / 2 + 26.dp).roundToPx().coerceIn(0, sc.maxValue)) } }
        Box(contentAlignment = Alignment.Center) {
            Row(Modifier.widthIn(max = maxWidth).fadeEdges(sc, true, 40.dp).horizontalScroll(sc), verticalAlignment = Alignment.CenterVertically) {
                apps.forEach { s -> key(s.id) { PillApp(s, s.id == selected, onApp, false, highlight = !beadMarks, onHold = onHold) } }
            }
            // small arrows on the faded edges: there's more this way
            // (on a small disc of the pill's own color, so it never sits on top of a half-faded icon)
            val disc = if (N.material) N.nav else if (N.dark) Color(0xFF26262B) else Color.White
            @Composable fun More(icon: ImageVector) = Box(Modifier.size(20.dp).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
                Icon(icon, "More apps", tint = N.sub, modifier = Modifier.size(16.dp)) }
            androidx.compose.animation.AnimatedVisibility(sc.value > 0, Modifier.align(Alignment.CenterStart), enter = fadeIn(), exit = fadeOut()) { More(Icons.Rounded.ChevronLeft) }
            androidx.compose.animation.AnimatedVisibility(sc.value < sc.maxValue, Modifier.align(Alignment.CenterEnd), enter = fadeIn(), exit = fadeOut()) { More(Icons.Rounded.ChevronRight) }
        }
        return
    }
    apps.forEach { s -> key(s.id) { PillApp(s, s.id == selected, onApp, vertical, highlight = false, onHold = onHold) } }
}

@Composable private fun PillApp(s: AppSessions.Session, on: Boolean, onApp: (String) -> Unit, vertical: Boolean, highlight: Boolean, onHold: ((String) -> Unit)? = null) {
    run {
        // a cell of the pill's size, with a true circle centered in it: the touch target and, when you're in it, the highlight
        // hold and let go: the app's menu; hold and slide: move it along the dock (one place per cell you slide).
        // The hold itself comes from the tap handler (so a hold never also opens the app); the slide is only
        // taken over while holding, so an ordinary swipe still scrolls the dock.
        var drag by remember { mutableFloatStateOf(0f) }; var dragging by remember { mutableStateOf(false) }; var moved by remember { mutableStateOf(false) }
        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
        val cell = with(androidx.compose.ui.platform.LocalDensity.current) { (if (vertical) 64.dp else 52.dp).toPx() }
        Box(Modifier.size(if (vertical) 64.dp else 52.dp, 56.dp).zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { if (vertical) translationY = drag else translationX = drag; val k = if (dragging) 1.12f else 1f; scaleX = k; scaleY = k }
            .then(if (onHold == null) Modifier else Modifier.pointerInput(s.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    while (true) {
                        val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        if (dragging) {
                            val d = ch.position - ch.previousPosition
                            drag += if (vertical) d.y else d.x; ch.consume()
                            val dir = if (drag > cell * 0.6f) 1 else if (drag < -cell * 0.6f) -1 else 0
                            if (dir != 0 && AppSessions.canMove(s.id, dir)) {
                                AppSessions.move(s.id, dir); drag -= dir * cell; moved = true
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                    if (dragging && !moved) onHold(s.id)                // held and let go without sliding: the menu
                    dragging = false; moved = false; drag = 0f
                }
            }), contentAlignment = Alignment.Center) {
        Box(Modifier.size(48.dp).clip(CircleShape)
            .background(if ((highlight && on && !N.material) || dragging) (if (N.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f)) else Color.Transparent)
            .bouncy(onLongClick = onHold?.let { { dragging = true; moved = false; haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) } }) { onApp(s.id) }, contentAlignment = Alignment.Center) {
            val ring = on && N.material
            // Material: a round ring around the icon (centered in the cell), not a squircle hugging it
            Box(Modifier.size(46.dp).then(if (ring) Modifier.border(2.dp, N.blue, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(N.card), contentAlignment = Alignment.Center) {
                val b = s.icon
                if (s.app.has("builtin")) Icon(Builtins.icon(s.id), s.app.optString("name"), tint = N.blue, modifier = Modifier.size(20.dp))
                else if (b != null) androidx.compose.foundation.Image(b, s.app.optString("name"), Modifier.fillMaxSize().padding(3.dp))
                else Text(s.app.optString("name").take(1).uppercase(), color = N.blue, fontWeight = FontWeight.Bold)
            }
            }
        }
        }
    }
}

/** The same floating pill, stood on its end for landscape and tablets: down the left edge, same
 *  frosted glass, same sliding bead, same tabs (hold it to choose them). */
@Composable fun VerticalNav(selected: Int, icons: List<ImageVector>, labels: List<String>, onSelect: (Int) -> Unit,
                            modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null,
                            apps: List<AppSessions.Session> = emptyList(), selectedApp: String? = null, onApp: (String) -> Unit = {},
                            onAppHold: ((String) -> Unit)? = null) {
    val itemH = 64.dp; val gap = 4.dp
    val bead = rememberBead(selected, icons.size, apps, selectedApp, itemH, 56.dp, gap)
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val hold = Modifier.pointerInput(onLongClick) { detectTapGestures(onLongPress = { onLongClick?.let { l -> haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); l() } }) }
    // more tabs and apps than fit the height: the whole pill scrolls, with its ends fading (the bead scrolls along)
    val vs = rememberScrollState()
    val maxH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp - 48.dp
    if (N.material) {
        Column(modifier.frosted(null, RoundedCornerShape(32.dp), 6.dp).then(hold).heightIn(max = maxH).fadeEdges(vs, false, 40.dp).verticalScroll(vs)
            .padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
            icons.forEachIndexed { i, ic ->
                val on = i == selected
                val w by androidx.compose.animation.core.animateDpAsState(if (on) 56.dp else 40.dp, if (reduceMotion()) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(0.55f, 380f), label = "vind")
                Column(Modifier.width(72.dp).height(itemH).clip(RoundedCornerShape(20.dp)).bouncy(onLongClick = onLongClick) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(width = w, height = 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) N.navSel else Color.Transparent), contentAlignment = Alignment.Center) {
                        Icon(ic, labels[i], tint = if (on) N.text else N.sub, modifier = Modifier.size(24.dp))
                    }
                    Text(labels[i], fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) N.text else N.sub, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
                }
            }
            PillApps(apps, selectedApp, onApp, vertical = true, onHold = onAppHold)
        }
        return
    }
    Box(modifier.frosted(LocalRootHaze.current, RoundedCornerShape(40.dp), 18.dp).then(hold)) {
      Box(Modifier.heightIn(max = maxH).fadeEdges(vs, false, 40.dp).verticalScroll(vs).padding(6.dp)) {
        Box(Modifier.offset(y = bead.pos).size(width = 64.dp, height = bead.size).graphicsLayer { alpha = bead.alpha }
            .frosted(LocalRootHaze.current, RoundedCornerShape(28.dp), 4.dp)
            .background(if (N.dark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.05f)))
        Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
            icons.forEachIndexed { i, ic ->
                Box(Modifier.size(width = 64.dp, height = itemH).clip(RoundedCornerShape(28.dp))
                    .bouncy(onLongClick = onLongClick) { onSelect(i) }, contentAlignment = Alignment.Center) {
                    Icon(ic, labels[i], tint = N.text, modifier = Modifier.size(26.dp))
                }
            }
            PillApps(apps, selectedApp, onApp, vertical = true, onHold = onAppHold)
        }
      }
    }
}

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


/** Your own wallpaper (Appearance → Wallpaper), blurred and dimmed behind every page. */
@Composable fun WallpaperLayer() {
    val t = N
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val blur = AppPrefs.wallBlur
    // a soft blur that works on every Android version: the picture is drawn from a much smaller copy, then scaled up smoothly
    val bmp = remember(AppPrefs.wallV, blur) { Backdrop.wallpaper(ctx) }          // made once, shared with Liquid Glass cards
    // in screen coordinates too, so it continues seamlessly from a page to the strip behind the side pill
    var origin by remember { mutableStateOf(Offset.Zero) }
    val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    Box(Modifier.fillMaxSize().background(t.bg).onGloballyPositioned { origin = it.positionInWindow() }) {
        bmp?.let { img -> Canvas(Modifier.fillMaxSize().then(if (Compat.S && blur > 0) Modifier.blur((blur / 2).dp) else Modifier)) {
            val W = if (win.width > 0) win.width.toFloat() else size.width; val H = if (win.height > 0) win.height.toFloat() else size.height
            val k = maxOf(W / img.width, H / img.height)                     // "cover" the whole window
            val dw = img.width * k; val dh = img.height * k
            drawImage(img, dstOffset = androidx.compose.ui.unit.IntOffset(((W - dw) / 2 - origin.x).toInt(), ((H - dh) / 2 - origin.y).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.High)
        } }
        Box(Modifier.fillMaxSize().background(t.bg.copy(alpha = AppPrefs.wallDim / 100f)))
    }
}
