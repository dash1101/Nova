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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
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
            onLongClick = onLongClick, onClick = onClick)        // (the tap sound comes from the press effect: SoundIndication)
}

// ── Page scaffold: big title that scrolls away, sticky back button ────────────────
data class TopAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * The back arrow (and any actions) stay pinned at the top while the page scrolls.
 * At the top they sit flush with the title; once content slides under them a dark-gray
 * circle fades in behind each, and fades out again when you scroll back to the top.
 */
@Composable fun Page(title: String, onBack: () -> Unit, actions: List<TopAction> = emptyList(),
                     scroll: ScrollState = routeScroll(), bottom: Dp = 40.dp,
                     content: @Composable ColumnScope.() -> Unit) {
    val haze = remember { dev.chrisbanes.haze.HazeState() }
    Box(Modifier.fillMaxSize()) {
      // the blur source includes the background glow, so the frosted buttons pick up its color
      Box(Modifier.fillMaxSize().hazeSource(haze)) {
        GlowLayer()
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            val compact = LocalCompactHeight.current
            Row(Modifier.fillMaxWidth().statusBarsPadding()
                .padding(start = 68.dp, end = 12.dp + 52.dp * actions.size, top = if (compact) 8.dp else 18.dp, bottom = if (compact) 4.dp else 10.dp)
                .heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (N.material && !compact) Text(title, fontSize = 32.sp, fontWeight = FontWeight.Normal, color = N.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
/** True for the list pane of the tablet split view (the page the open one came from). */
val LocalListPane = androidx.compose.runtime.staticCompositionLocalOf { false }

@Composable fun TabOrPage(app: AppState, title: String, content: @Composable ColumnScope.() -> Unit) {
    // a tab, or a tab shown as the list beside the page it opened (tablets) — neither has anywhere to go back to
    val isTab = (app.stack.size == 1 && navTabs(app).any { it.route == app.top }) ||
        (LocalListPane.current && app.stack.size == 2 && navTabs(app).any { it.route == app.stack.first() })
    if (!isTab) { Page(title, app::back, content = content); return }
    Column(Modifier.fillMaxSize().verticalScroll(routeScroll())) {
        val compact = LocalCompactHeight.current
        Text(title, fontSize = if (compact) 24.sp else 32.sp, fontWeight = FontWeight.Bold, color = N.text,
            modifier = Modifier.statusBarsPadding().padding(start = Space.gutter + 8.dp, top = if (compact) 10.dp else 28.dp, bottom = if (compact) 6.dp else 12.dp))
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
    // The glyph is centered in its box (ArrowBackIosNew, not the off-center ArrowBackIos), so the
    // frosted circle that fades in behind it sits exactly around it.
    Box(Modifier.size(48.dp).frosted(LocalPageHaze.current, CircleShape, 8.dp, fade)
        .bouncy(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = N.text, modifier = Modifier.padding(start = nudge).size(iconSize))
    }
}

// ── Toast ─────────────────────────────────────────────────────────────────────────
/** One UI toast: a small rounded gray bubble, centered above the bottom bar. */
@Composable fun OneToastHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        if (N.material) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                // Material You: a tonal bar in the wallpaper's colors, lifted off the page
                Text(data.visuals.message, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 20.dp, vertical = 15.dp))
            }
            return@SnackbarHost
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
            Text(data.visuals.message, color = if (N.dark) Color(0xFFF2F2F4) else Color(0xFF1B1B1D), fontSize = 15.sp,
                textAlign = TextAlign.Center, modifier = Modifier.popupFrost(LocalRootHaze.current, RoundedCornerShape(22.dp), 10.dp)
                    .padding(horizontal = 20.dp, vertical = 12.dp))
        }
    }
}

// ── Dialog ────────────────────────────────────────────────────────────────────────
/** Dialogs open right now. A dialog is its own window, so the page's toasts would sit underneath it:
 *  the topmost dialog shows them instead, just above its card. */
object DialogStack {
    var depth by mutableIntStateOf(0); var snack: SnackbarHostState? = null
    /** Which copy of a dialog is showing: the same dialog asked for twice (a screen composed twice, a double
     *  hold) shows once; the other copy waits and only takes over if the first goes away. */
    val owners = androidx.compose.runtime.snapshots.SnapshotStateMap<String, Any>()
}

/** Inside a dialog's card: counts the dialog, and (on the top one) shows toasts just above the card. */
@Composable fun DialogToasts() {
    val mine = remember { DialogStack.depth + 1 }
    DisposableEffect(Unit) { DialogStack.depth++; onDispose { DialogStack.depth-- } }
    val snack = DialogStack.snack ?: return
    if (DialogStack.depth != mine) return
    val d = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.ui.window.Popup(alignment = Alignment.TopCenter, offset = with(d) { androidx.compose.ui.unit.IntOffset(0, -(80.dp).roundToPx()) },
        properties = androidx.compose.ui.window.PopupProperties(focusable = false, clippingEnabled = false)) {
        Box(Modifier.width(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp)) { OneToastHost(snack) }
    }
}

data class DialogButton(val label: String, val color: Color? = null, val enabled: Boolean = true, val onClick: () -> Unit)

/** One UI dialog: floats at the bottom of the screen, big rounded card, text buttons split by a hairline. */
/** A dialog window whose card eases in (fade + a small scale-up), and eases out when it's dismissed by Back or a tap outside. */
@Composable fun MotionDialog(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val p = remember { androidx.compose.animation.core.Animatable(if (reduceMotion()) 1f else 0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { if (p.value < 1f) p.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = .82f, stiffness = 520f)) }
    val dismiss: () -> Unit = { if (reduceMotion()) onDismiss() else scope.launch { p.animateTo(0f, androidx.compose.animation.core.tween(140)); onDismiss() } }
    Dialog(dismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.graphicsLayer { alpha = p.value; val k = .92f + .08f * p.value; scaleX = k; scaleY = k }) { content() }
    }
}

@Composable fun OneDialog(onDismiss: () -> Unit, title: String? = null, text: String? = null,
                          buttons: List<DialogButton> = emptyList(), content: (@Composable ColumnScope.() -> Unit)? = null) {
    val key = (title ?: "") + "\u0000" + (text ?: "")
    val me = remember { Any().also { if (DialogStack.owners[key] == null) DialogStack.owners[key] = it } }
    DisposableEffect(key) { onDispose { if (DialogStack.owners[key] === me) DialogStack.owners.remove(key) } }
    if (DialogStack.owners[key] !== me) { if (DialogStack.owners[key] == null) SideEffect { DialogStack.owners.putIfAbsent(key, me) }; return }
    DisposableEffect(Unit) { Sounds.play("open"); onDispose { Sounds.play("close") } }      // a pop-up opening and closing has its sound
    if (N.preset.isNotEmpty()) { PresetDialog(onDismiss, title, text, buttons, content); return }
    if (N.material) {                       // M3 dialog: centered, 28dp corners, text buttons on the right
        MotionDialog(onDismiss) {
            Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp * winK()))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(top = 24.dp, bottom = 12.dp)) {
                DialogToasts()
                if (title != null) Text(title, color = N.text, fontSize = 24.sp, modifier = Modifier.padding(horizontal = 24.dp))
                if (text != null) Text(text, color = N.sub, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = if (title != null) 16.dp else 0.dp))
                if (content != null) Column(Modifier.padding(top = 12.dp)) { content() }
                // a FlowRow: long labels move to the next line instead of squeezing a button down to nothing
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                if (buttons.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp, start = 12.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
                    buttons.forEach { b ->
                        Box(Modifier.clip(RoundedCornerShape(20.dp)).clickable(enabled = b.enabled, onClick = b.onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(b.label, color = if (!b.enabled) N.sub else b.color?.takeIf { it == N.red } ?: N.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
        }
        return
    }
    MotionDialog(onDismiss) {
        val view = LocalView.current
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.setGravity(Gravity.BOTTOM) }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 28.dp).widthIn(max = 600.dp).fillMaxWidth()
            .clip(RoundedCornerShape(20.dp * winK())).background(N.card).padding(top = 24.dp, bottom = 6.dp)) {
            DialogToasts()
            if (title != null) Text(title, color = N.text, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 26.dp))
            if (text != null) Text(text, color = N.text.copy(alpha = 0.85f), fontSize = 15.sp,
                modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = if (title != null) 10.dp else 0.dp))
            if (content != null) Column(Modifier.padding(top = 10.dp)) { content() }
            // three buttons with a long label don't fit side by side: one per line then
            if (buttons.size >= 3 && buttons.sumOf { it.label.length } > 30) Column(Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp)) {
                buttons.reversed().forEach { b ->
                    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(enabled = b.enabled, onClick = b.onClick).padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                        Text(b.label, color = if (!b.enabled) N.sub else b.color ?: N.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            } else if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 14.dp, start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                buttons.forEachIndexed { i, b ->
                    if (i > 0) Box(Modifier.width(1.dp).height(20.dp).background(N.divider))
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable(enabled = b.enabled, onClick = b.onClick)
                        .padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                        Text(b.label, color = if (!b.enabled) N.sub else b.color ?: N.text, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
        }
        }
    }
}

/** A button's role in a dialog: red = destructive, any other color = the main action, none = cancel. */
private fun DialogButton.role(t: NovaColors) = when { color == null -> "cancel"; color == t.red -> "destructive"; else -> "primary" }

/**
 * Dialogs in the Glass, Mica and Breeze styles.
 *  Glass:  centered and round, title and message centered, capsule buttons side by side (stacked when 3+), on real glass.
 *  Mica:   a content dialog — title and text on the left, the buttons on their own band at the bottom, the main one filled.
 *  Breeze: a framed window — bold title on the left, buttons right-aligned, the main one outlined in the accent.
 */
@Composable private fun PresetDialog(onDismiss: () -> Unit, title: String?, text: String?, buttons: List<DialogButton>, content: (@Composable ColumnScope.() -> Unit)?) {
    val t = N
    val host = androidx.compose.ui.platform.LocalContext.current.let { c -> var x: android.content.Context? = c; while (x is android.content.ContextWrapper && x !is android.app.Activity) x = x.baseContext; x as? android.app.Activity }
    if (t.preset == "liquid" || t.preset == "aero") LaunchedEffect(Unit) { Behind.take(host) }          // what the glass will bend: the page as it is now
    MotionDialog(onDismiss) {
      androidx.compose.runtime.CompositionLocalProvider(LocalInDialog provides true) {
        when (t.preset) {
            "liquid" -> {
                val k = roundK(); val wk = winK(); val shape = RoundedCornerShape(34.dp * k * wk)
                Column(Modifier.padding(horizontal = 28.dp).widthIn(max = if (content != null) 440.dp else 320.dp).fillMaxWidth()
                    .liquidCard(shape, 34.dp * k * wk, 18.dp).padding(start = maxOf(16.dp, 34.dp * k * .3f), end = maxOf(16.dp, 34.dp * k * .3f), top = 22.dp, bottom = maxOf(16.dp, 34.dp * k * .3f)),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    DialogToasts()
                    if (title != null) Text(title, color = t.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
                    if (text != null) Text(text, color = t.text.copy(alpha = .82f), fontSize = 14.sp, lineHeight = 19.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp))
                    if (content != null) Column(Modifier.fillMaxWidth().padding(top = 10.dp)) { content() }
                    val fill = if (t.dark) Color(0x29787880) else Color(0x1A787880)
                    @Composable fun Capsule(b: DialogButton, m: Modifier) {
                        val r = b.role(t)
                        Box(m.height(46.dp).clip(RoundedCornerShape(50)).background(if (r == "primary") t.blue else fill).clickable(enabled = b.enabled, onClick = b.onClick)
                            .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                            Text(b.label, color = if (!b.enabled) t.sub else when (r) { "primary" -> Color.White; "destructive" -> t.red; else -> t.text },
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (buttons.size >= 3) Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons.forEach { Capsule(it, Modifier.fillMaxWidth()) } }
                    else if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { buttons.forEach { Capsule(it, Modifier.weight(1f)) } }
                }
            }
            "lumen" -> LumenDialogBody(title, text, buttons, content)
            "fluent" -> {
                val shape = RoundedCornerShape(8.dp * roundK() * winK())
                Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 480.dp).fillMaxWidth().shadow(24.dp, shape).clip(shape).background(t.card)
                    .border(1.dp, if (t.dark) Color(0x1FFFFFFF) else Color(0x14000000), shape)) {
                    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 20.dp)) {
                        DialogToasts()
                        if (title != null) Text(title, color = t.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (text != null) Text(text, color = t.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = if (title != null) 12.dp else 0.dp))
                    }
                    if (content != null) Column(Modifier.padding(bottom = 12.dp)) { content() }
                    if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth().background(if (t.dark) Color(0xFF202020) else Color(0xFFF3F3F3)).padding(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        buttons.forEach { b -> val r = b.role(t); val bs = RoundedCornerShape(4.dp)
                            Box(Modifier.weight(1f).height(32.dp).clip(bs).background(if (r == "primary") t.blue else if (t.dark) Color(0x0FFFFFFF) else Color(0xB3FFFFFF))
                                .border(1.dp, if (r == "primary") Color.Transparent else if (t.dark) Color(0x17FFFFFF) else Color(0x29000000), bs)
                                .clickable(enabled = b.enabled, onClick = b.onClick), contentAlignment = Alignment.Center) {
                                Text(b.label, color = if (!b.enabled) t.sub else if (r == "primary") (if (t.dark) Color.Black else Color.White) else if (r == "destructive") t.red else t.text,
                                    fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            "aero" -> {       // a glass task dialog: the title on the glass frame, a plain panel for the words, glossy buttons
                val k = roundK(); val shape = RoundedCornerShape(8.dp * k * winK())
                Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 480.dp).fillMaxWidth().aeroCard(shape, 20.dp).padding(maxOf(8.dp, 8.dp * k * .3f))) {   // keeps the buttons clear of a very round corner
                    DialogToasts()
                    if (title != null) Text(title, style = TextStyle(color = t.text, fontSize = 16.sp, shadow = aeroGlow(t.dark)),
                        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 8.dp))
                    val ps = RoundedCornerShape(4.dp * k)
                    if (text != null || content != null) Column(Modifier.fillMaxWidth().clip(ps).background(if (t.dark) Color(0xF0141E2B) else Color(0xF7FFFFFF))
                        .border(1.dp, if (t.dark) Color(0x33000000) else Color(0x4D0B2E4F), ps).padding(vertical = if (text != null) 14.dp else 4.dp)) {
                        if (text != null) Text(text, color = t.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 14.dp))
                        if (content != null) Column(Modifier.padding(top = if (text != null) 8.dp else 0.dp)) { content() }
                    }
                    if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        buttons.forEach { b -> val r = b.role(t)
                            Box(Modifier.heightIn(min = 34.dp).widthIn(min = 84.dp).aeroButton(r == "primary", RoundedCornerShape(3.dp * k)).clickable(enabled = b.enabled, onClick = b.onClick)
                                .padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                                Text(b.label, color = if (!b.enabled) t.sub else if (r == "destructive") t.red else if (t.dark) Color.White else Color(0xFF1E1E1E), fontSize = 14.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            else -> {   // breeze
                val shape = RoundedCornerShape(6.dp * roundK() * winK())
                Column(Modifier.padding(horizontal = 24.dp).widthIn(max = 480.dp).fillMaxWidth().shadow(16.dp, shape).clip(shape).background(t.card)
                    .border(1.dp, if (t.dark) Color(0x1AFFFFFF) else Color(0x24000000), shape).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp)) {
                    DialogToasts()
                    if (title != null) Text(title, color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    if (text != null) Text(text, color = t.text, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = if (title != null) 8.dp else 0.dp))
                    if (content != null) Column(Modifier.padding(top = 8.dp)) { content() }
                    if (buttons.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        buttons.forEach { b -> val r = b.role(t); val bs = RoundedCornerShape(4.dp)
                            Box(Modifier.heightIn(min = 34.dp).clip(bs).background(if (r == "primary") t.navSel else t.pill)
                                .border(1.dp, if (r == "primary") t.blue else if (t.dark) Color(0x24FFFFFF) else Color(0x33000000), bs)
                                .clickable(enabled = b.enabled, onClick = b.onClick).padding(horizontal = 14.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                                Text(b.label, color = if (!b.enabled) t.sub else if (r == "destructive") t.red else t.text, fontSize = 14.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
      }
    }
}

/** Radio list inside a dialog (choose one). */
/** A row in a dialog: a choice (radio) — or, with [action], something to do (an arrow, like a menu). */
@Composable fun DialogChoice(label: String, sub: String? = null, selected: Boolean, action: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = N.text, fontSize = 17.sp)
            if (sub != null) Text(sub, color = N.sub, fontSize = 13.sp)
        }
        if (action) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = N.sub, modifier = Modifier.size(22.dp)) else OneRadio(selected)
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
/** One UI switch: blue track with the white thumb inside; gray when off. */
@Composable fun OneSwitch(checked: Boolean, onChange0: (Boolean) -> Unit, enabled: Boolean = true) {
    val onChange: (Boolean) -> Unit = { Sounds.feel(if (it) "toggleOn" else "toggleOff"); onChange0(it) }     // on and off sound different
    if (N.material) {
        androidx.compose.material3.Switch(checked, onChange, enabled = enabled, thumbContent = if (checked) { { Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) } } else null)
        return
    }
    when (N.preset) {
        "lumen" -> { LumenSwitch(checked, onChange, enabled); return }
        "liquid" -> {     // a capsule: green when on, a round white knob with a soft shadow
            val x by animateDpAsState(if (checked) 20.dp else 0.dp, spring(0.7f, 500f), label = "sw")
            val track by animateColorAsState(if (checked) (if (N.dark) Color(0xFF30D158) else Color(0xFF34C759)) else (if (N.dark) Color(0x52787880) else Color(0x29787880)), label = "swc")
            Box(Modifier.size(51.dp, 31.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(CircleShape).background(track)
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(2.dp)) {
                Box(Modifier.offset(x = x).size(27.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White))
            }
            return
        }
        "fluent" -> {     // an outlined pill when off, filled with the accent when on; the knob grows a little when on
            val x by animateDpAsState(if (checked) 20.dp else 0.dp, spring(0.8f, 600f), label = "sw")
            Box(Modifier.size(40.dp, 20.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(CircleShape)
                .background(if (checked) N.blue else Color.Transparent).border(1.dp, if (checked) N.blue else N.sub, CircleShape)
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(4.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.offset(x = x).size(12.dp).clip(CircleShape).background(if (checked) (if (N.dark) Color.Black else Color.White) else N.sub))
            }
            return
        }
        "aero" -> {       // a glossy track that lights up in the accent, and a glossy knob
            val x by animateDpAsState(if (checked) 20.dp else 0.dp, spring(0.8f, 600f), label = "sw")
            val a = N.blue; val ts = RoundedCornerShape(11.dp)
            Box(Modifier.size(44.dp, 22.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
                .then(if (checked) Modifier.clip(ts).background(Brush.verticalGradient(0f to lerp(a, Color.White, .5f), .5f to lerp(a, Color.White, .2f), .5f to a, 1f to lerp(a, Color.White, .3f)))
                    .border(1.dp, lerp(a, Color.Black, .45f), ts) else Modifier.aeroButton(false, ts))
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(2.dp)) {
                Box(Modifier.offset(x = x).size(18.dp).shadow(2.dp, CircleShape).aeroButton(false, CircleShape))
            }
            return
        }
        "breeze" -> {     // a small framed track; the knob carries a frame too
            val x by animateDpAsState(if (checked) 18.dp else 0.dp, spring(0.85f, 600f), label = "sw")
            Box(Modifier.size(36.dp, 18.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(CircleShape)
                .background(if (checked) N.blue else N.pill).border(1.dp, if (checked) N.blue else (if (N.dark) Color(0x33FFFFFF) else Color(0x33000000)), CircleShape)
                .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(1.dp)) {
                Box(Modifier.offset(x = x).size(16.dp).clip(CircleShape).background(Color.White).border(1.dp, Color(0x33000000), CircleShape))
            }
            return
        }
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
    if (N.preset == "lumen") { LumenRadio(selected, enabled); return }
    if (N.preset == "liquid") {       // Glass: a checkmark on the chosen one, nothing on the rest
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Rounded.Check, null, tint = if (enabled) N.blue else N.sub, modifier = Modifier.size(22.dp)) }
        return
    }
    if (N.preset == "aero") {         // Aero: a sunken ring, and a glossy ball in the accent when chosen
        Box(Modifier.size(20.dp).clip(CircleShape).background(Brush.verticalGradient(listOf(if (N.dark) Color(0xFF1A2533) else Color(0xFFE6E6E6), if (N.dark) Color(0xFF2B394A) else Color.White)))
            .border(1.dp, if (N.dark) Color(0xFF0B1520) else Color(0xFF8E8F8F), CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Brush.radialGradient(listOf(lerp(N.blue, Color.White, .7f), N.blue, lerp(N.blue, Color.Black, .35f)),
                center = Offset(8f, 8f), radius = 30f)).graphicsLayer { alpha = if (enabled) 1f else .5f }) }
        return
    }
    if (N.preset == "fluent") {       // Mica: a ring that fills with the accent, a white dot inside
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (selected) N.blue else Color.Transparent).border(1.dp, if (selected) N.blue else N.sub, CircleShape), contentAlignment = Alignment.Center) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(if (N.dark) Color.Black else Color.White)) }
        return
    }
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
    if (fraction != null) { val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "pb"); LookBar(f, color); return }
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(N.divider)) {
        run {
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
                             visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
                             singleLine: Boolean = true) {
    BasicTextField(value, onChange, modifier, singleLine = singleLine, minLines = if (singleLine) 1 else 3, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = N.text, fontSize = 17.sp, fontFamily = if (mono) FontFamily.Monospace else null),
        cursorBrush = SolidColor(N.blue),
        decorationBox = { inner ->
            Box(Modifier.fieldSurface(22.dp).padding(horizontal = 18.dp, vertical = 14.dp)) {
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


/** Rename something: one text field, Save. */
@Composable fun RenameDialog(title: String, initial: String, hint: String = "Name", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    OneDialog(onDismiss, title, null, listOf(DialogButton("Cancel") { onDismiss() },
        DialogButton("Save", N.blue, enabled = v.isNotBlank() && v.trim() != initial) { onSave(v.trim()) })) {
        OneTextField(v, { v = it.take(40) }, hint, Modifier.fillMaxWidth().padding(horizontal = 22.dp))
    }
}


/** A text field's (and the search bar's) surface in each look: Aero — Windows 7's light field in a beveled frame;
 *  Mica — a small-cornered field with a line along the bottom; Breeze — a thin frame; Glass — a capsule; otherwise the
 *  rounded fill. [round]: the corner in the default look. Same as Nova web. */
@Composable fun Modifier.fieldSurface(round: androidx.compose.ui.unit.Dp): Modifier {
    val t = N
    return when (t.preset) {
        "aero" -> { val sh = RoundedCornerShape(3.dp * roundK())
            this.clip(sh).background(Brush.verticalGradient(if (t.dark) listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.06f))
                    else listOf(androidx.compose.ui.graphics.lerp(Color.White, t.blue, 0.08f), Color.White)))
                .border(1.dp, Brush.verticalGradient(if (t.dark) listOf(Color.Black.copy(alpha = 0.55f), Color.White.copy(alpha = 0.25f)) else listOf(Color(0xFF7F8FA6), Color(0xFFD6DFEB))), sh) }
        "fluent" -> { val sh = RoundedCornerShape(4.dp * roundK())
            this.clip(sh).background(t.text.copy(alpha = 0.06f)).border(1.dp, t.text.copy(alpha = 0.08f), sh)
                .then(androidx.compose.ui.Modifier.drawWithContent { drawContent(); drawLine(t.sub, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) }) }
        "breeze" -> { val sh = RoundedCornerShape(3.dp * roundK()); this.clip(sh).background(t.card).border(1.dp, t.divider, sh) }
        "liquid" -> this.clip(RoundedCornerShape(50)).background(N.pill)
        "lumen" -> { val sh = RoundedCornerShape(14.dp * roundK()); this.clip(sh).background(if (t.dark) Color(0x47000000) else Color(0xB3FFFFFF)).border(1.dp, if (t.dark) Color(0x17FFFFFF) else Color(0x170A0C18), sh) }
        else -> this.clip(RoundedCornerShape(round)).background(N.pill)
    }
}