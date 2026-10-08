package app.novalabs.nova

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.repeatOnLifecycle
import org.json.JSONObject

/** Every screen in the app. */
sealed class Route {
    data object Home : Route(); data object Store : Route(); data object Menu : Route()
    data object Lighting : Route(); data object Schedules : Route()
    data class EditSchedule(val index: Int) : Route()
    data object Containers : Route(); data class Container(val name: String) : Route()
    data class Logs(val name: String) : Route(); data class Shell(val name: String) : Route()
    data class StoreItem(val id: String) : Route()
    data object Hardware : Route(); data class Drive(val serial: String) : Route()
    data object Inbox : Route(); data object NotifySettings : Route()
    data object QuickPanel : Route(); data object EditQuick : Route(); data object Status : Route()
    data object Ssh : Route(); data object SshTerm : Route()
    data object Servers : Route(); data object ServerSettings : Route(); data object Dashboard : Route(); data object Approvals : Route()
    data object Appearance : Route(); data object SetupGuide : Route(); data object EditShortcuts : Route(); data object EditHome : Route(); data object EditTabs : Route()
    data object Settings : Route(); data object Devices : Route(); data object About : Route()
}

/** Shared app state: the API, live data, navigation, messages. */
class AppState(val activity: Activity, val pairing: Pairing, val scope: CoroutineScope,
               val switchServer: (String) -> Unit = {}) {
    val api = NovaApi(pairing)
    val snack = SnackbarHostState()
    val stack = mutableStateListOf<Route>(Route.Home)
    var overview by mutableStateOf(Cache["/api/v1/overview"])      // last known, so nothing flashes empty
    var fan by mutableStateOf(Cache["/api/v1/fan"] ?: Cache["/api/v1/overview"]?.optJSONObject("fan"))
    var error by mutableStateOf<String?>(null)        // shown only after repeated failures
    var reconnecting by mutableStateOf(false)
    var unread by mutableIntStateOf(0)
    /** Last live numbers (CPU, memory…) from any screen, so a freshly drawn page never shows "—". */
    var lastNow by mutableStateOf<JSONObject?>(null)
    var paired by mutableStateOf(pairing.paired)
    var notAuthorized by mutableStateOf(false)
    private var failures = 0

    val top get() = stack.last()
    var role by mutableStateOf(pairing.role)
    val isAdmin get() = role != "viewer"
    /** Features the server reports (older servers report none: assume everything). */
    fun has(f: String) = overview?.optJSONObject("features")?.optBoolean(f, true) ?: true
    var wide = false                 // tablet layout in use
    var leftPane = false             // last touch was in the list pane (tablet list/detail)
    /** In the tablet list/detail view, opening something from the list replaces the detail instead of stacking. */
    /** Which way the last navigation went, so transitions slide the right way. */
    var navDir by mutableIntStateOf(0)          // 1 forward, -1 back, 0 tab switch / instant
    fun go(r: Route) { navDir = 1; if (wide && leftPane && stack.size >= 2) pop(); stack.add(r) }
    fun tab(r: Route) { navDir = 0; stack.clear(); scrolls.clear(); stack.add(r) }
    /** [instant]: the page underneath is already on screen (predictive back finished) — swap with no transition. */
    fun back(instant: Boolean = false) {
        if (stack.size > 1) { navDir = if (instant) 2 else -1; pop() }
        else if (top != Route.Home) {                     // back from another tab goes Home; back on Home leaves the app
            navDir = if (instant) 2 else -1; scrolls.keys.removeAll { it.second != Route.Home }; stack[0] = Route.Home
        }
    }
    /** Where back goes from here (null: back leaves the app). */
    val backTarget: Route? get() = stack.getOrNull(stack.size - 2) ?: if (top != Route.Home) Route.Home else null
    private fun pop() { scrolls.keys.removeAll { it.first >= stack.lastIndex }; stack.removeAt(stack.lastIndex) }
    /** Scroll position of every page in the back stack, so going back lands where you left off. */
    private val scrolls = mutableMapOf<Pair<Int, Route>, androidx.compose.foundation.ScrollState>()
    fun scrollFor(r: Route): androidx.compose.foundation.ScrollState {
        val depth = stack.lastIndexOf(r).coerceAtLeast(0)
        return scrolls.getOrPut(depth to r) { androidx.compose.foundation.ScrollState(0) }
    }
    fun toast(m: String) = scope.launch { snack.currentSnackbarData?.dismiss(); snack.showSnackbar(m) }

    suspend fun refresh() {
        try {
            val o = api.get("/api/v1/overview"); overview = o
            o.optJSONObject("server")?.let { srv -> pairing.label = srv.optString("display_name").ifEmpty { srv.optString("name") } }
            if (fanInFlight == 0) o.optJSONObject("fan")?.let { f -> fan = JSONObject(fan?.toString() ?: "{}").also { m -> f.keys().forEach { k -> m.put(k, f.get(k)) } } }
            error = null; reconnecting = false; failures = 0; notAuthorized = false
            if (pairing.lastEventSeen == 0.0) {     // fresh install: history isn't "new"
                api.get("/api/v1/events?since=0").optJSONArray("events")?.optJSONObject(0)?.optDouble("t")?.let { pairing.lastEventSeen = it }
            }
            val ev = api.get("/api/v1/events?since=${pairing.lastEventSeen}").optJSONArray("events")
            unread = (0 until (ev?.length() ?: 0)).count { ev!!.getJSONObject(it).optString("level") in listOf("warning", "critical") }
        } catch (e: ApiException) {
            failures++
            if (e.code == 401) {
                // One 401 can be a clock hiccup or a replayed nonce; only a repeat means revoked.
                // Never wipe keys automatically — the user decides on the banner.
                if (failures >= 2) notAuthorized = true
            } else {
                reconnecting = true
                if (failures >= 2) error = e.message
            }
        }
    }

    // ── Optimistic fan changes: the UI moves first, the server catches up ──
    private var fanSeq = 0
    var fanInFlight = 0; private set
    fun changeFan(patch: JSONObject) {
        if (!isAdmin) { toast("This phone has view-only access"); return }
        val before = fan
        fan = JSONObject(fan?.toString() ?: "{}").also { m -> patch.keys().forEach { k -> m.put(k, patch.get(k)) } }
        val seq = ++fanSeq; fanInFlight++
        scope.launch {
            try {
                val r = api.post("/api/v1/fan", patch)
                if (seq == fanSeq) { fan = r; Cache.put("/api/v1/fan", r) }       // only the newest reply wins
            } catch (e: Exception) {
                if (seq == fanSeq) { fan = before; toast(e.message ?: "Couldn't change the light") }
            } finally { fanInFlight-- }
        }
    }

    /** Run something, show its error as a message. */
    fun act(okMsg: String? = null, block: suspend () -> Unit) = scope.launch {
        try { block(); okMsg?.let { toast(it) } } catch (e: Exception) { toast(e.message ?: "Something went wrong") }
    }

    /** Risky action: fingerprint, then the call. */
    suspend fun stepUp(title: String, method: String, path: String, body: JSONObject? = null) =
        api.stepUp(activity, title, method, path, body)

    /** Background jobs (installs/updates): poll until done. */
    suspend fun waitJob(job: JSONObject, onUpdate: (JSONObject) -> Unit = {}): JSONObject {
        var j = job
        while (j.optString("state") == "running") {
            delay(2000); j = api.get("/api/v1/jobs/${j.getString("id")}"); onUpdate(j)
        }
        return j
    }

    /** First run on v0.2 (and every launch on home Wi-Fi): crash reports, step-up key, remote settings. */
    suspend fun housekeeping() {
        CrashReporter.flush(activity, pairing, api)
        runCatching {
            val who = api.get("/api/v1/whoami")
            who.optString("role").takeIf { it.isNotEmpty() }?.let { pairing.role = it; role = it }
            pairing.user = who.optString("user")
            // Upgrade older pairings to the encrypted home listener (pin comes over the signed connection).
            if (pairing.lanPin.isEmpty()) runCatching {
                val t = api.get("/api/v1/lan-tls")
                if (t.optString("url").startsWith("https") && t.optString("pin").length == 64) pairing.updateLan(t.getString("url"), t.getString("pin"))
            }
            if (api.via == "home") {
                if (!who.optBoolean("stepup")) {
                    pairing.keys.ensureStepUp()
                    api.post("/api/v1/device/stepup-key", JSONObject().put("public_key", pairing.keys.stepUpPublicKeyPem()))
                }
                pairing.stepUpRegistered = true
                val rc = api.get("/api/v1/remote-config")
                if (rc.optString("remote_url").isNotEmpty() && rc.optString("remote_url") != pairing.remoteUrl)
                    pairing.updateRemote(rc.optString("remote_url"), rc.optString("cf_client_id"), rc.optString("cf_client_secret"))
            } else if (who.optBoolean("stepup") && pairing.keys.hasStepUp()) pairing.stepUpRegistered = true
        }
    }
}

var openApprovals by mutableStateOf(false)

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); if (intent.getStringExtra("open") == "approvals") openApprovals = true
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val openInbox = intent?.getStringExtra("open") == "inbox"
        openApprovals = intent?.getStringExtra("open") == "approvals"
        Alerts.start(this)
        AppPrefs.init(this); applySecureFlag(this)
        setContent { NovaTheme {
            if (AppPrefs.appLock && !AppLock.unlocked) LockScreen(this) else NovaRoot(this, openInbox)
        } }
    }
    override fun onStart() { super.onStart(); AppLock.onStart() }
    override fun onStop() { super.onStop(); AppLock.onStop()
    }
}

/** Picks the active server; switching rebuilds everything below for that server's keys and cache. */
@Composable fun NovaRoot(activity: Activity, openInbox: Boolean) {
    var profile by remember { Servers.prune(activity); mutableStateOf(Servers.active(activity)) }
    key(profile) {
        ServerRoot(activity, profile, openInbox) { id -> Servers.setActive(activity, id); SshSession.disconnect(); profile = id }
    }
}

@Composable fun ServerRoot(activity: Activity, profile: String, openInbox: Boolean, switchServer: (String) -> Unit) {
    remember { Cache.use(activity.applicationContext, profile) }
    val scope = rememberCoroutineScope()
    val pairing = remember { Pairing(activity, profile) }
    val app = remember { AppState(activity, pairing, scope, switchServer) }
    if (!app.paired) {
        // Adding another server: offer a way back to the ones already paired.
        val others = Servers.all(activity).filter { it != profile && Pairing(activity, it).paired }
        GlowBackground { PairScreen(app, onCancel = if (others.isEmpty()) null else ({
            Servers.remove(activity, profile); switchServer(others.first()) })) { app.paired = true } }
        LaunchedEffect(Unit) { CrashReporter.flush(activity, pairing, null) }
        return
    }
    AccentTheme(app.overview?.optJSONObject("server")?.optString("accent")) { ServerUi(app, openInbox) }
}

@Composable private fun ServerUi(app: AppState, openInbox: Boolean) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        if (openInbox) app.go(Route.Inbox)
        app.housekeeping()
        // Poll only while Nova is on screen; coming back refreshes immediately on fresh sockets.
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            app.api.wake()
            while (true) { app.refresh(); delay(if (app.reconnecting) 4_000 else 15_000) }
        }
    }
    // Predictive back: the page follows your thumb, the one underneath shows through.
    // On release the page finishes leaving (slides on and fades) while the one underneath settles to
    // full size; only then is the stack popped, with no transition, so nothing flashes or re-fades.
    val backP = remember { androidx.compose.animation.core.Animatable(0f) }
    val backExit = remember { androidx.compose.animation.core.Animatable(0f) }
    val backScope = rememberCoroutineScope()
    var backEdge by remember { mutableIntStateOf(androidx.activity.BackEventCompat.EDGE_LEFT) }
    androidx.activity.compose.PredictiveBackHandler(enabled = app.backTarget != null) { events ->
        try {
            events.collect { e -> backEdge = e.swipeEdge; backP.snapTo(e.progress) }
            val finish = androidx.compose.animation.core.tween<Float>(if (reduceMotion()) 0 else 200, easing = androidx.compose.animation.core.FastOutSlowInEasing)
            kotlinx.coroutines.coroutineScope {
                launch { backP.animateTo(1f, finish) }
                backExit.animateTo(1f, finish)
            }
            app.back(instant = true)
            backP.snapTo(0f); backExit.snapTo(0f)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // gesture cancelled: glide back into place
            backScope.launch { backExit.snapTo(0f); backP.animateTo(0f, androidx.compose.animation.core.spring(stiffness = 700f)) }
            throw e
        }
    }
    LaunchedEffect(openApprovals) { if (openApprovals) { openApprovals = false; app.go(Route.Approvals) } }

    val rootHaze = remember { dev.chrisbanes.haze.HazeState() }
    GlowBackground {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            app.wide = wide
            CompositionLocalProvider(LocalWide provides wide, LocalRootHaze provides rootHaze) {
                if (!wide) {
                    val p = backP.value; val x = backExit.value
                    // ease-out so the first part of the swipe already shows clearly
                    val e = 1f - (1f - p) * (1f - p)
                    val dirX = if (backEdge == androidx.activity.BackEventCompat.EDGE_LEFT) 1 else -1
                    Box(Modifier.fillMaxSize().hazeSource(rootHaze)) {
                    val under = app.backTarget
                    if (p > 0.001f && under != null) Box(Modifier.fillMaxSize().graphicsLayer {
                        // the page underneath comes forward from behind, sliding in slightly from the opposite side
                        val s = 0.9f + 0.1f * e; scaleX = s; scaleY = s
                        translationX = -dirX * 48.dp.toPx() * (1f - e)
                    }) {
                        GlowBackground { Screen(app, under) }
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f * (1f - e))))   // dim lifts as it comes forward
                    }
                    AnimatedContent(app.top, modifier = Modifier.fillMaxSize().graphicsLayer {
                            if (p > 0.001f) {
                                val s = 1f - 0.2f * e; scaleX = s; scaleY = s
                                translationX = dirX * 96.dp.toPx() * e
                                translationY = 12.dp.toPx() * e
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(44.dp * e); clip = true
                                shadowElevation = 24.dp.toPx() * e * (1f - x)
                                translationX += dirX * size.width * 0.35f * x       // released: it carries on out…
                                alpha = 1f - x                                      // …and fades away
                            } },
                        transitionSpec = { navTransition(app.navDir, reduceMotion()) }, label = "nav") { r ->
                        // each page is opaque, so slides don't show through; after a finished back gesture the
                        // page that left is hidden at once (it already animated away)
                        GlowBackground(Modifier.graphicsLayer { alpha = if (app.navDir == 2 && r != app.top) 0f else 1f }) { Screen(app, r) }
                    }
                    }
                    StatusBarScrim(Modifier.align(Alignment.TopCenter))
                    val tabs = navTabs(app)
                    val tabIndex = tabs.indexOfFirst { it.route == app.top }
                    if (tabIndex >= 0) FloatingNav(tabIndex, tabs.map { it.icon }, tabs.map { it.label }, { i -> app.tab(tabs[i].route) },
                        Modifier.align(Alignment.BottomCenter), onLongClick = { app.go(Route.EditTabs) })
                } else WideLayout(app, maxWidth)
            }
            CompositionLocalProvider(LocalRootHaze provides rootHaze) {        // so the toast is real frosted glass
                OneToastHost(app.snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (wide) 24.dp else 96.dp))
            }
        }
    }
}

/** Forward: the new page slides in from the right over a slight parallax; back: the reverse. */
fun navTransition(dir: Int, reduce: Boolean): androidx.compose.animation.ContentTransform {
    if (dir == 2) return androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
    if (reduce || dir == 0) return fadeIn(androidx.compose.animation.core.tween(160)) togetherWith fadeOut(androidx.compose.animation.core.tween(120))
    val spec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)
    return (androidx.compose.animation.slideInHorizontally(spec) { w -> if (dir > 0) w / 3 else -w / 6 } + fadeIn(androidx.compose.animation.core.tween(260))) togetherWith
        (androidx.compose.animation.slideOutHorizontally(spec) { w -> if (dir > 0) -w / 6 else w / 3 } + fadeOut(androidx.compose.animation.core.tween(180)))
}

/** Remembers which pane a touch started in (tablet list/detail). */
private fun Modifier.paneTouch(app: AppState, left: Boolean) = pointerInput(left) {
    awaitPointerEventScope { while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial); app.leftPane = left } }
}

/** Tablets & unfolded foldables: navigation rail + list/detail side by side (One UI tablet style). */
@Composable private fun WideLayout(app: AppState, width: androidx.compose.ui.unit.Dp) {
    val rail = buildList {
        add(Triple<Route, androidx.compose.ui.graphics.vector.ImageVector, String>(Route.Home, Icons.Rounded.Dns, "Home"))
        add(Triple(Route.Status, Icons.Rounded.MonitorHeart, "Status"))
        add(Triple(Route.Containers, Icons.Rounded.ViewInAr, "Containers"))
        if (app.has("store")) add(Triple(Route.Store, Icons.Rounded.Storefront, "Store"))
        if (app.has("ssh")) add(Triple(Route.Ssh, Icons.Rounded.Terminal, "Terminal"))
        add(Triple(Route.Dashboard, Icons.Rounded.Dashboard, "Dashboard"))
        add(Triple(Route.Menu, Icons.AutoMirrored.Rounded.List, "Menu"))
    }
    val root = app.stack.first()
    Row(Modifier.fillMaxSize()) {
        if (app.top != Route.Dashboard)        // the always-on dashboard gets the whole screen
            NavRail(rail.map { it.second to it.third }, rail.indexOfFirst { it.first == root }) { i -> app.tab(rail[i].first) }
        val parent = app.stack.getOrNull(app.stack.size - 2)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (parent != null && width >= 900.dp) Row(Modifier.fillMaxSize()) {
                // The list on the left stays put; picking something in it replaces the detail on the right.
                Box(Modifier.width(400.dp).fillMaxHeight().paneTouch(app, true)) {
                    CompositionLocalProvider(LocalWide provides false) { Screen(app, parent) }   // the list pane is phone-width
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(N.divider))
                Box(Modifier.weight(1f).fillMaxHeight().paneTouch(app, false)) {
                    AnimatedContent(app.top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "detail") { r -> Box(Modifier.fillMaxSize()) { Screen(app, r) } }
                }
            } else Box(Modifier.fillMaxSize().paneTouch(app, false), contentAlignment = Alignment.TopCenter) {
                val full = app.top == Route.Home || app.top == Route.Dashboard || app.top == Route.Status
                Box((if (full) Modifier.fillMaxSize() else Modifier.widthIn(max = 760.dp).fillMaxHeight()).then(LocalRootHaze.current?.let { Modifier.hazeSource(it) } ?: Modifier)) {
                    AnimatedContent(app.top, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { r -> Box(Modifier.fillMaxSize()) { Screen(app, r) } }
                }
            }
            StatusBarScrim(Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable fun Screen(app: AppState, r: Route) = CompositionLocalProvider(LocalRouteScroll provides app.scrollFor(r),
        LocalNavPad provides (if (!app.wide && navTabs(app).any { it.route == r }) 84.dp else 0.dp)) {
    when (r) {
        Route.Home -> HomeScreen(app)
        Route.Store -> StoreScreen(app)
        Route.Menu -> MenuScreen(app)
        Route.Lighting -> LightingScreen(app)
        Route.Schedules -> SchedulesScreen(app)
        is Route.EditSchedule -> EditScheduleScreen(app, r.index)
        Route.Containers -> ContainersScreen(app)
        is Route.Container -> ContainerScreen(app, r.name)
        is Route.Logs -> LogsScreen(app, r.name)
        is Route.Shell -> ShellScreen(app, r.name)
        is Route.StoreItem -> StoreItemScreen(app, r.id)
        Route.Hardware -> HardwareScreen(app)
        is Route.Drive -> DriveScreen(app, r.serial)
        Route.Inbox -> InboxScreen(app)
        Route.NotifySettings -> NotifySettingsScreen(app)
        Route.QuickPanel -> QuickPanelScreen(app)
        Route.EditQuick -> EditQuickScreen(app)
        Route.EditShortcuts -> EditShortcutsScreen(app)
        Route.EditHome -> EditHomeScreen(app)
        Route.EditTabs -> EditTabsScreen(app)
        Route.Status -> StatusScreen(app)
        Route.Ssh -> SshScreen(app)
        Route.SshTerm -> SshTermScreen(app)
        Route.Servers -> ServersScreen(app)
        Route.ServerSettings -> ServerSettingsScreen(app)
        Route.Dashboard -> DashboardScreen(app)
        Route.Approvals -> ApprovalsScreen(app)
        Route.Appearance -> AppearanceScreen(app)
        Route.SetupGuide -> Page("Setup guide", app::back) { SetupGuideBody() }
        Route.Settings -> SettingsScreen(app)
        Route.Devices -> DevicesScreen(app)
        Route.About -> AboutScreen(app)
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())
