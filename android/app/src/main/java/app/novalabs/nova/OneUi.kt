package app.novalabs.nova

import android.view.Gravity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.flow.distinctUntilChanged

// One UI building blocks drawn by hand, so nothing looks like stock Material.

/** Click with a soft press-in (scale 0.96 while held), like One UI tiles. Respects Reduce motion. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun Modifier.bouncy(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed && !reduceMotion()) 0.96f else 1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "press")
    return this.graphicsLayer { scaleX = sc; scaleY = sc }
        .combinedClickable(interactionSource = src, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled,
            onLongClick = onLongClick, onClick = onClick)
}

// ── Page scaffold: big title that scrolls away, sticky back button ────────────────
data class TopAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * The back arrow (and any actions) stay pinned at the top while the page scrolls.
 * At the top they sit flush with the title; once content slides under them a dark-grey
 * circle fades in behind each, and fades out again when you scroll back to the top.
 */
@Composable fun Page(title: String, onBack: () -> Unit, actions: List<TopAction> = emptyList(),
                     scroll: ScrollState = routeScroll(), bottom: Dp = 40.dp,
                     content: @Composable ColumnScope.() -> Unit) {
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    Box(Modifier.fillMaxSize()) {
      // the blur source includes the background glow, so the frosted buttons pick up its colour
      Box(Modifier.fillMaxSize().hazeSource(haze)) {
        GlowLayer()
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding()
                .padding(start = 68.dp, end = 12.dp + 52.dp * actions.size, top = 18.dp, bottom = 10.dp)
                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (N.material) Text(title, fontSize = 32.sp, fontWeight = FontWeight.Normal, color = N.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    lineHeight = 38.sp, modifier = Modifier.padding(top = 14.dp))          // M3 large top app bar
                else Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = N.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            content()
            Spacer(Modifier.height(bottom + LocalNavPad.current))
        }
      }
        val px = with(LocalDensity.current) { 36.dp.toPx() }
        CompositionLocalProvider(LocalPageHaze provides haze) { StickyBar(onBack, actions, (scroll.value / px).coerceIn(0f, 1f)) }
    }
}

/** A page that can also be a tab in the navigation pill (Store, Menu): as the current tab it has a big
 *  title and no back button; opened from somewhere else (Menu → Store) it's a normal page with one. */
@Composable fun TabOrPage(app: AppState, title: String, content: @Composable ColumnScope.() -> Unit) {
    val isTab = app.stack.size == 1 && navTabs(app).any { it.route == app.top }
    if (!isTab) { Page(title, app::back, content = content); return }
    Column(Modifier.fillMaxSize().verticalScroll(routeScroll())) {
        Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = N.text,
            modifier = Modifier.statusBarsPadding().padding(start = Space.gutter + 8.dp, top = 28.dp, bottom = 12.dp))
        content()
        Spacer(Modifier.height(40.dp + LocalNavPad.current))
    }
}

/** For screens that manage their own scrolling (logs, terminal): same bar, circle always on. */
@Composable fun FixedTopBar(title: String, onBack: () -> Unit, actions: List<TopAction> = emptyList()) {
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding()
            .padding(start = 64.dp, end = 12.dp + 52.dp * actions.size, top = 18.dp, bottom = 10.dp)
            .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = N.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        StickyBar(onBack, actions, 0f)
    }
}

@Composable private fun StickyBar(onBack: () -> Unit, actions: List<TopAction>, fade: Float) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 10.dp, end = 10.dp, top = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircleButton(Icons.Rounded.ArrowBackIosNew, "Back", fade, onBack, iconSize = 20.dp)
        Spacer(Modifier.weight(1f))
        actions.forEach { a -> Spacer(Modifier.width(4.dp)); CircleButton(a.icon, a.label, fade, a.onClick) }
    }
}

@Composable fun CircleButton(icon: ImageVector, label: String, fade: Float, onClick: () -> Unit,
                             iconSize: Dp = 24.dp, nudge: Dp = 0.dp) {
    // The glyph is centred in its box (ArrowBackIosNew, not the off-centre ArrowBackIos), so the
    // frosted circle that fades in behind it sits exactly around it.
    Box(Modifier.size(48.dp).frosted(LocalPageHaze.current, CircleShape, 8.dp, fade)
        .bouncy(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = N.text, modifier = Modifier.padding(start = nudge).size(iconSize))
    }
}

// ── Toast ─────────────────────────────────────────────────────────────────────────
/** One UI toast: a small rounded grey bubble, centred above the bottom bar. */
@Composable fun OneToastHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        if (N.material) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(data.visuals.message, color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.inverseSurface).padding(horizontal = 18.dp, vertical = 14.dp))
            }
            return@SnackbarHost
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
            Text(data.visuals.message, color = if (N.dark) Color(0xFFF2F2F4) else Color(0xFF1B1B1D), fontSize = 15.sp,
                textAlign = TextAlign.Center, modifier = Modifier.frosted(LocalRootHaze.current, RoundedCornerShape(22.dp), 10.dp)
                    .padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

// ── Dialog ────────────────────────────────────────────────────────────────────────
data class DialogButton(val label: String, val color: Color? = null, val enabled: Boolean = true, val onClick: () -> Unit)

/** One UI dialog: floats at the bottom of the screen, big rounded card, text buttons split by a hairline. */
@Composable fun OneDialog(onDismiss: () -> Unit, title: String? = null, text: String? = null,
                          buttons: List<DialogButton> = emptyList(), content: (@Composable ColumnScope.() -> Unit)? = null) {
    if (N.material) {                       // M3 dialog: centred, 28dp corners, text buttons on the right
        Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(top = 24.dp, bottom = 12.dp)) {
                if (title != null) Text(title, color = N.text, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 24.dp))
                if (text != null) Text(text, color = N.sub, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = if (title != null) 16.dp else 0.dp))
                if (content != null) Column(Modifier.padding(top = 12.dp)) { content() }
                if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
                    buttons.forEach { b ->
                        Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = b.enabled, onClick = b.onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(b.label, color = if (!b.enabled) N.sub else b.color?.takeIf { it == N.red } ?: N.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        return
    }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.BOTTOM) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 28.dp).widthIn(max = 600.dp).fillMaxWidth()
            .clip(RoundedCornerShape(30.dp)).background(N.card).padding(top = 24.dp, bottom = 6.dp)) {
            if (title != null) Text(title, color = N.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 26.dp))
            if (text != null) Text(text, color = N.text.copy(alpha = 0.85f), fontSize = 15.sp,
                modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = if (title != null) 10.dp else 0.dp))
            if (content != null) Column(Modifier.padding(top = 10.dp)) { content() }
            if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                buttons.forEachIndexed { i, b ->
                    if (i > 0) Box(Modifier.width(1.dp).height(20.dp).background(N.divider))
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable(enabled = b.enabled, onClick = b.onClick)
                        .padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(b.label, color = if (!b.enabled) N.sub else b.color ?: N.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        }
    }
}

/** Radio list inside a dialog (choose one). */
@Composable fun DialogChoice(label: String, sub: String? = null, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = N.text, fontSize = 17.sp)
            if (sub != null) Text(sub, color = N.sub, fontSize = 13.sp)
        }
        OneRadio(selected)
    }
}

// ── Menu ──────────────────────────────────────────────────────────────────────────
@Composable fun OneMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<Pair<String, () -> Unit>>) {
    DropdownMenu(expanded, onDismiss, shape = RoundedCornerShape(24.dp), containerColor = N.card,
        tonalElevation = 0.dp, shadowElevation = 12.dp, modifier = Modifier.widthIn(min = 190.dp)) {
        items.forEach { (label, go) ->
            DropdownMenuItem({ Text(label, color = N.text, fontSize = 17.sp) }, { onDismiss(); go() },
                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp))
        }
    }
}

// ── Controls ──────────────────────────────────────────────────────────────────────
/** One UI switch: blue track with the white thumb inside; grey when off. */
@Composable fun OneSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    if (N.material) {
        androidx.compose.material3.Switch(checked, onChange, enabled = enabled, thumbContent = if (checked) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null)
        return
    }
    val x by animateDpAsState(if (checked) 22.dp else 0.dp, spring(stiffness = Spring.StiffnessMediumLow), label = "sw")
    val track by animateColorAsState(if (checked) N.blue else if (N.dark) Color(0xFF5A5A60) else Color(0xFFB9B9BF), label = "swc")
    Box(Modifier.size(50.dp, 28.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(CircleShape).background(track)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(3.dp)) {
        Box(Modifier.offset(x = x).size(22.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable fun OneRadio(selected: Boolean, enabled: Boolean = true) {
    if (N.material) { androidx.compose.material3.RadioButton(selected, null, enabled = enabled); return }
    val c = if (!enabled) N.sub.copy(alpha = 0.5f) else if (selected) N.blue else N.sub
    Box(Modifier.size(24.dp).border(2.dp, c, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(12.dp).clip(CircleShape).background(c))
    }
}

@Composable fun OneSpinner(size: Dp = 26.dp, color: Color = N.blue) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(Modifier.size(size).rotate(a)) {
        drawArc(color, 0f, 270f, false, style = Stroke(width = size.toPx() / 9, cap = StrokeCap.Round))
    }
}

@Composable fun OneBadge(count: Int) {
    if (count <= 0) return
    Box(Modifier.defaultMinSize(18.dp, 18.dp).clip(CircleShape).background(N.red).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99+" else "$count", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** Determinate bar, or (fraction == null) a sliding segment for "working, no number yet". */
@Composable fun ProgressBar(fraction0: Float?, color: Color = N.blue) {
    val fraction = fraction0?.takeIf { !it.isNaN() && !it.isInfinite() }      // NaN would crash the animation
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(N.divider)) {
        if (fraction != null) {
            val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "pb")
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(color))
        } else {
            val t = rememberInfiniteTransition(label = "ind")
            val x by t.animateFloat(-0.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)), label = "x")
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * 0.35f
                drawLine(color, Offset((size.width * x).coerceAtLeast(0f), size.height / 2),
                    Offset((size.width * x + w).coerceAtMost(size.width), size.height / 2), size.height, StrokeCap.Round)
            }
        }
    }
}

/** Tonal pill button (store "Get", "Open"…). */
@Composable fun PillButton(text: String, enabled: Boolean = true, color: Color = N.blue, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(N.pill).bouncy(enabled, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 9.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) color else N.sub, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable fun Chip(text: String, mono: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(N.pill).clickable(onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(text, color = N.text, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else null)
    }
}

/** Filled rounded text field (no Material outline/label animation). */
@Composable fun OneTextField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier,
                             mono: Boolean = false, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
                             keyboardActions: KeyboardActions = KeyboardActions.Default,
                             visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None) {
    BasicTextField(value, onChange, modifier, singleLine = true, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = N.text, fontSize = 17.sp, fontFamily = if (mono) FontFamily.Monospace else null),
        cursorBrush = SolidColor(N.blue),
        decorationBox = { inner ->
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(N.pill).padding(horizontal = 18.dp, vertical = 14.dp)) {
                if (value.isEmpty()) Text(placeholder, color = N.sub, fontSize = 17.sp, fontFamily = if (mono) FontFamily.Monospace else null)
                inner()
            }
        })
}

// ── Wheel time picker (Samsung Clock style) ───────────────────────────────────────
@Composable fun Wheel(count: Int, value: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, label: (Int) -> String) {
    val itemH = 64.dp
    val loops = 400; val mid = loops / 2 * count
    val state = rememberLazyListState(initialFirstVisibleItemIndex = mid + value)
    val itemPx = with(LocalDensity.current) { itemH.toPx() }
    val selected by remember { derivedStateOf {
        state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0 } }
    LaunchedEffect(state) {
        snapshotFlow { selected % count }.distinctUntilChanged().collect { onChange(it) }
    }
    LazyColumn(modifier.height(itemH * 3), state = state, flingBehavior = rememberSnapFlingBehavior(state),
        contentPadding = PaddingValues(vertical = itemH), horizontalAlignment = Alignment.CenterHorizontally) {
        items(loops * count) { i ->
            val sel = i == selected
            Box(Modifier.height(itemH).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(label(i % count), fontSize = if (sel) 46.sp else 32.sp, fontWeight = if (sel) FontWeight.Normal else FontWeight.Light,
                    color = if (sel) N.text else N.sub.copy(alpha = 0.55f))
            }
        }
    }
}

@Composable fun TimeWheels(hour: Int, minute: Int, onChange: (Int, Int) -> Unit) {
    val h = rememberUpdatedState(hour); val m = rememberUpdatedState(minute)
    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Wheel(24, hour, { onChange(it, m.value) }, Modifier.weight(1f)) { "%02d".format(it) }
        Text(":", fontSize = 42.sp, color = N.text)
        Wheel(60, minute, { onChange(h.value, it) }, Modifier.weight(1f)) { "%02d".format(it) }
    }
}

// ── Expandable row: tap for the details ───────────────────────────────────────────
@Composable fun ExpandRow(title: String, subtitle: String? = null, subtitleBlue: Boolean = false,
                          icon: ImageVector? = null, iconTint: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
    Row1(title, subtitle, subtitleBlue, icon, iconTint, onClick = { open = !open }) {
        Icon(Icons.Rounded.KeyboardArrowDown, if (open) "Less" else "More", tint = N.sub, modifier = Modifier.rotate(rot))
    }
    AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.fillMaxWidth().padding(start = if (icon != null) 72.dp else 22.dp, end = 22.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable fun Detail(text: String, color: Color = N.sub) = Text(text, color = color, fontSize = 14.sp)

@Composable fun DetailLine(label: String, value: String) {
    Row { Text(label, color = N.sub, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text(value, color = N.text, fontSize = 14.sp) }
}

/** The remembered scroll position for the page being shown (kept while it's in the back stack). */
val LocalRouteScroll = staticCompositionLocalOf<ScrollState?> { null }
@Composable fun routeScroll(): ScrollState = LocalRouteScroll.current ?: rememberScrollState()
/** Extra space at the bottom of a page that has the floating bottom bar over it. */
val LocalNavPad = staticCompositionLocalOf { 0.dp }
