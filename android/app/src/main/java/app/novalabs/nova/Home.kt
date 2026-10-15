package app.novalabs.nova

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable fun HomeScreen(app: AppState) {
    val o = app.overview
    val st = o?.optJSONObject("status")
    val level = st?.optString("level") ?: "ok"
    val m = st?.optJSONObject("metrics")
    val cs = o?.optJSONObject("containers")
    var menu by remember { mutableStateOf(false) }

    val top: @Composable () -> Unit = {
        // Title row
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.gutter + 8.dp, end = 8.dp, top = if (LocalCompactHeight.current) 8.dp else 28.dp),
            verticalAlignment = Alignment.CenterVertically) {
            var switcher by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable { switcher = true },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(serverName(app), fontSize = if (LocalCompactHeight.current) 24.sp else 32.sp, fontWeight = FontWeight.Bold, color = N.text, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Rounded.KeyboardArrowDown, "Switch server", tint = N.sub)
                }
                val ctx = app.activity
                val others = Servers.all(ctx).filter { it != app.pairing.profile && Pairing(ctx, it).paired }
                OneMenu(switcher, { switcher = false }, others.map { id -> Pairing(ctx, id).label.ifEmpty { "Server" } to { app.switchServer(id) } } +
                    listOf("Add a server…" to { app.switchServer(Servers.create(ctx)) }, "Manage servers" to { app.go(Route.Servers) }))
            }
            IconButton({ app.go(Route.Search) }) { Icon(Icons.Rounded.Search, "Search", tint = N.text) }
            IconButton({ app.act { app.refresh() } }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = N.text) }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = N.text) }
                OneMenu(menu, { menu = false }, (if (navTabs(app).none { it.id == "menu" }) listOf("Menu" to { app.go(Route.Menu) }) else emptyList()) +
                    listOf("Edit Home" to { app.go(Route.EditHome) }, "Settings" to { app.go(Route.Settings) }, "About" to { app.go(Route.About) }))
            }
        }
        // Status line — like "100% | Fully charged"
        Row(Modifier.padding(start = Space.gutter, top = 2.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable { app.go(Route.Status) }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (app.reconnecting || app.error != null) Icons.Rounded.Sync else if (level == "ok") Icons.Rounded.CheckCircle else Icons.Rounded.Error, null,
                tint = if (app.reconnecting || app.error != null) N.sub else levelColor(level, N),
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            val head = when { app.error != null -> "Disconnected"; app.reconnecting -> "Reconnecting…"; st == null -> "Connecting…"
                level == "ok" -> "All systems normal"; else -> st.optInt("active_count").let { n -> "$n need${if (n == 1) "s" else ""} attention" } }
            Text(head, color = N.sub, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (cs != null) {
                Box(Modifier.padding(horizontal = 10.dp).size(4.dp).clip(androidx.compose.foundation.shape.CircleShape).background(N.sub.copy(alpha = 0.6f)))
                Text("${cs.optInt("running")}/${cs.optInt("total")} running" + (if (cs.optInt("stopped") > 0) " · ${cs.optInt("stopped")} stopped" else ""), color = N.sub, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // Found on open: a newer Nova app or server release
        val up = app.updates
        androidx.compose.animation.AnimatedVisibility(up != null && AppPrefs.dismissedUpdate != up.toString()) {
            Row(Modifier.padding(horizontal = Space.gutter, vertical = 4.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                .background(N.blue.copy(alpha = 0.14f)).clickable { app.go(Route.Settings) }.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = N.blue, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                Text(listOfNotNull(up?.optString("app")?.ifEmpty { null }?.let { "App $it" }, up?.optString("server")?.ifEmpty { null }?.let { "server $it" }).joinToString(" and ").replaceFirstChar { it.uppercase() } + " ready to install",
                    color = N.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                IconButton({ AppPrefs.set("dismissed_update", up.toString()) }) { Icon(Icons.Rounded.Close, "Not now", tint = N.sub) }
            }
        }
        // Something running in the background (installs, updates, backups…): a small note, tap → Inbox
        val tk = app.overview?.optJSONArray("tasks").objs()
        androidx.compose.animation.AnimatedVisibility(tk.isNotEmpty(), enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(),
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkVertically()) {
            Row(Modifier.padding(start = Space.gutter).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .clickable { app.go(Route.Inbox) }.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
                val deg by spin.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.LinearEasing)), label = "deg")
                Icon(Icons.Rounded.Sync, null, tint = N.blue, modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = -deg })
                Spacer(Modifier.width(8.dp))
                Text(if (tk.size == 1) "${tk[0].optString("title")} · ${tk[0].optDouble("pct", 0.0).toInt()}%" else "${tk.size} in progress",
                    color = N.blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(14.dp))
        // Banner (only when something is going on)
        val backupRunning = m?.optString("data_backup")?.contains("running") == true
        val backup = if (backupRunning) live(app, "/api/v1/backup", 5_000).value else null
        when {
            ApiCompat.appTooOld -> Banner("This app is too old for the server — tap to update it", N.amber) { app.go(Route.Updates) }
            ApiCompat.serverTooOld -> Banner("The server's Nova is older than this app needs — tap to update the server", N.amber) { app.go(Route.Updates) }
            app.notAuthorized -> Banner("This ${DeviceForm.noun} isn't authorized anymore — tap to pair again", N.red) { app.pairing.clear(); app.paired = false }
            app.error != null -> DisconnectedBanner(app)
            level != "ok" -> Banner(st?.optString("headline") ?: "", levelColor(level, N)) { app.go(Route.Inbox) }
            backupRunning -> Banner(backup?.let { backupLine(it) } ?: "Backing up…", N.blue) { app.go(Route.QuickPanel) }
        }
    }
    // Hero: the server with the live fan — tap the fan for Lighting, the drive lights for Storage, the power button for power
    var powerMenu by remember { mutableStateOf(false) }
    if (powerMenu) OneDialog({ powerMenu = false }, "Power", null, listOf(DialogButton("Cancel") { powerMenu = false })) {
        DialogChoice("Restart server", "Everything goes offline for about 2 minutes", false, action = true) { powerMenu = false
            app.act { app.stepUp("Restart the server", "POST", "/api/v1/power/reboot"); app.toast("The server will restart in a few seconds") } }
        DialogChoice("Shut down server", "You'll need to press the power button to turn it back on", false, action = true) { powerMenu = false
            app.act { app.stepUp("Shut down the server", "POST", "/api/v1/power/poweroff"); app.toast("The server will shut down in a few seconds") } }
    }
    val hero: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { h ->
        Box(Modifier.fillMaxWidth().height(h).pointerInput(Unit) {
            detectTapGestures { o ->
                when (heroHit(size.width.toFloat(), size.height.toFloat(), o.x, o.y)) {
                    "fan" -> if (app.has("lighting")) app.go(Route.Lighting)
                    "drives" -> app.go(Route.Hardware)
                    "power" -> if (app.isAdmin) powerMenu = true else app.toast("This ${DeviceForm.noun} has view-only access")
                }
            }
        }) {
            val drives = st?.optJSONObject("metrics")?.optJSONArray("drive_states")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ServerHero(app.fan, levelColor(level, N), drives, app.clockSkew, Modifier.fillMaxSize(), io = app.lastNow?.optJSONObject("disks"))
        }
    }
    val pills: @Composable () -> Unit = {
        // Your shortcuts (Settings → Appearance, or hold the bar to change them)
        val edit = { app.go(Route.EditShortcuts) }
        val list = homeShortcuts(app)
        if (list.isEmpty()) Text("Hold here to add shortcuts", color = N.sub, fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.gutter).bouncy(onLongClick = edit, onClick = edit).padding(14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        else PillBar(list.map { s -> PillItem(s.icon, if (list.size >= 5) s.short else s.label, if (s.id == "inbox") app.unread else 0, onLongClick = edit) { app.go(s.route) } })
    }
    // Sections can be hidden in Settings → Appearance & privacy.
    if (!LocalWide.current) Column(Modifier.fillMaxSize().verticalScroll(routeScroll())) {
        // One rhythm: header · (banner) · then your sections in your order, each block Space.gap apart.
        top(); Spacer(Modifier.height(Space.gap))
        AppPrefs.homeOrder.filter { sectionOn(it) }.forEach { id ->
            when (id) { "hero" -> hero(260.dp); "shortcuts" -> pills(); "recs" -> HomeRecommended(app); else -> HomeStats(app) }
            Spacer(Modifier.height(Space.gap))
        }
        Spacer(Modifier.height(118.dp))
    } else Row(Modifier.fillMaxSize()) {                       // tablet: server on the left, numbers on the right
        val compact = LocalCompactHeight.current                // a phone on its side: the server picture fits the height
        Column(Modifier.weight(1f).verticalScroll(routeScroll())) { top(); if (AppPrefs.homeHero) hero(if (compact) 230.dp else 440.dp); Spacer(Modifier.height(if (compact) 12.dp else 30.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = if (compact) 12.dp else 36.dp, end = 8.dp)) {
            AppPrefs.homeOrder.filter { it != "hero" && sectionOn(it) }.forEach { id ->
                when (id) { "shortcuts" -> pills(); "recs" -> HomeRecommended(app); else -> HomeStats(app) }; Spacer(Modifier.height(Space.gap)) }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable fun MenuScreen(app: AppState) {
    val m = app.overview?.optJSONObject("status")?.optJSONObject("metrics")
    val cs = app.overview?.optJSONObject("containers")
    val appsLive = live(app, "/api/v1/apps")
    val apps = appsLive.value?.optJSONArray("apps").objs().filter { !it.optBoolean("hidden") }
    var builtinMenu by remember { mutableStateOf<String?>(null) }
    builtinMenu?.let { id -> BuiltinMenu(app, id) { builtinMenu = null } }
    TabOrPage(app, "Menu") {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(smallCorner(28.dp))).background(N.pill)
            .clickable { app.go(Route.Search) }.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = N.sub); Spacer(Modifier.width(12.dp))
            Text("Search Nova", color = N.sub, fontSize = 17.sp)
        }
        // at a glance: four live tiles
        val disk = storageList(m).maxByOrNull { it.pct }
        val tiles = listOfNotNull(
            Quad("Containers", cs?.let { "${it.optInt("running")}/${it.optInt("total")} up" } ?: "—", Icons.Rounded.ViewInAr, N.blue) to { app.go(Route.Containers) },
            Quad("Storage", disk?.let { "${it.name} ${it.pct.toInt()}%" } ?: "—", Icons.Rounded.Storage, androidx.compose.ui.graphics.Color(0xFF3ECF6E)) to { app.go(Route.Hardware) },
            if (app.has("lighting")) Quad("Lighting", app.fan?.let { if (it.optBoolean("on")) "${it.optString("effect").replaceFirstChar { c -> c.uppercase() }} · ${it.optInt("brightness")}%" else "Off" } ?: "—",
                Icons.Rounded.Lightbulb, androidx.compose.ui.graphics.Color(0xFFFFB020)) to { app.go(Route.Lighting) }
            else Quad("Status", "Live graphs", Icons.Rounded.MonitorHeart, androidx.compose.ui.graphics.Color(0xFF3ECF6E)) to { app.go(Route.Status) },
            Quad("Inbox", if (app.unread > 0) "${app.unread} new" else "All caught up", Icons.Rounded.Notifications, androidx.compose.ui.graphics.Color(0xFFFF5A5A)) to { app.go(Route.Inbox) }) +
            // your favorites join the live tiles (Edit favorites below to add, remove or reorder them)
            AppPrefs.favorites.mapNotNull { f -> PLACES.firstOrNull { it.first == f } }.map { (t, _, ir) -> Quad(t, "Favorite", ir.first, N.amber) to { app.go(ir.second) } }
        Column(Modifier.padding(horizontal = Space.gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(if (LocalWide.current) 4 else 2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (q, go) -> Column(Modifier.weight(1f).glassCard(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).clickable { go() }.padding(16.dp)) {
                    Icon(q.icon, null, tint = q.color, modifier = Modifier.size(24.dp)); Spacer(Modifier.height(10.dp))
                    Text(q.title, color = N.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(q.sub, color = N.sub, fontSize = 13.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                } }
            } }
        }
        // your apps, right here
        SectionLabel("Apps")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            @Composable fun Cell(label: String, onClick: () -> Unit, onHold: (() -> Unit)? = null, icon: @Composable () -> Unit) =
                Column(Modifier.width(68.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).combinedClickable(onLongClick = onHold, onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    icon(); Text(label, color = N.text, fontSize = 12.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
            @Composable fun Glyph(i: androidx.compose.ui.graphics.vector.ImageVector, c: androidx.compose.ui.graphics.Color) =
                Box(Modifier.size(52.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(15.dp)).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Icon(i, null, tint = c) }
            if (app.isAdmin && app.has("files")) Cell("Files", { openBuiltin(app, "nova-files") }, { builtinMenu = "nova-files" }) { Glyph(Icons.Rounded.Folder, N.blue) }
            Cell("Terminal", { openBuiltin(app, "nova-term") }, { builtinMenu = "nova-term" }) { Glyph(Icons.Rounded.Terminal, N.green) }
            apps.take(10).forEach { a -> Cell(a.optString("name"), { dockTap(app, a.optString("id")) }) { AppIcon(app, a, 52.dp) } }
            Cell("All apps", { app.go(Route.Apps) }) { Glyph(Icons.Rounded.Apps, N.sub) }
            if (app.isAdmin) Cell("Add", { app.go(Route.Apps) }) { Glyph(Icons.Rounded.Add, N.blue) }
        }
        Text(if (AppPrefs.favorites.isEmpty()) "☆  Add favorites — any screen, as a tile here" else "Edit favorites", color = N.link, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = Space.gutter + 10.dp, top = 2.dp, bottom = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .clickable { app.go(Route.EditFavorites) }.padding(horizontal = 8.dp, vertical = 6.dp))
        SectionLabel("Server")
        Group {
            Row1("Status", "Live graphs, storage, backups", true, Icons.Rounded.MonitorHeart, androidx.compose.ui.graphics.Color(0xFF3ECF6E), onClick = { app.go(Route.Status) })
            RowDivider()
            Row1("Storage & hardware", "Drives, pools, set up drives", true, Icons.Rounded.Storage, androidx.compose.ui.graphics.Color(0xFF3ECF6E), onClick = { app.go(Route.Hardware) })
            RowDivider()
            if (app.isAdmin) { Row1("Free up", "Old images, logs, caches, trash — and memory", true, Icons.Rounded.CleaningServices, N.amber, onClick = { app.go(Route.FreeUp) }); RowDivider() }
            Row1("Backups", "What's backed up, restore files", true, Icons.Rounded.Backup, onClick = { app.go(Route.Backups) })
            RowDivider()
            Row1("Updates", "Packages, containers and Nova", true, Icons.Rounded.Update, androidx.compose.ui.graphics.Color(0xFF3ECF6E), onClick = { app.go(Route.Updates) })
            RowDivider()
            Row1("Diagnostics", "Speed, stress and network tests", true, Icons.Rounded.Speed, androidx.compose.ui.graphics.Color(0xFF64D2FF), onClick = { app.go(Route.Diagnostics) })
        }
        SectionLabel("Apps & tools")
        Group {
            Row1("App store", "Containers and programs", true, Icons.Rounded.Storefront, androidx.compose.ui.graphics.Color(0xFFBF5AF2), onClick = { app.tab(Route.Store) })
            RowDivider()
            Row1("Containers", cs?.let { "${it.optInt("running")} of ${it.optInt("total")} running" }, true, Icons.Rounded.ViewInAr, onClick = { app.go(Route.Containers) })
            if (app.has("lighting")) { RowDivider()
                Row1("Lighting", "Effects, colors, schedules, your lights", true, Icons.Rounded.Lightbulb, androidx.compose.ui.graphics.Color(0xFFFFB020), onClick = { app.go(Route.Lighting) }) }
            RowDivider()
            Row1("Quick panel", "Your shortcuts — tap ✎ to customize", true, Icons.Rounded.Widgets, onClick = { app.go(Route.QuickPanel) })
            RowDivider()
            Row1("Dashboard mode", "Always-on screen for a tablet or spare phone", true, Icons.Rounded.Dashboard, androidx.compose.ui.graphics.Color(0xFF64D2FF), onClick = { app.go(Route.Dashboard) })
        }
        SectionLabel("Alerts")
        Group {
            Row1("Inbox", if (app.unread > 0) "${app.unread} new" else "Alerts, logins and server events", true, Icons.Rounded.Notifications, androidx.compose.ui.graphics.Color(0xFFFF5A5A), onClick = { app.go(Route.Inbox) })
            RowDivider()
            Row1("Notifications", "Phone, Discord, logins", false, Icons.Rounded.NotificationsActive, onClick = { app.go(Route.NotifySettings) })
        }
        SectionLabel("You and this ${DeviceForm.noun}")
        Group {
            Row1("Security & privacy", "Approvals, authenticator codes, passkeys, app lock, recent activity", true, Icons.Rounded.Shield, N.green, onClick = { app.go(Route.Security) }); RowDivider()
            Row1("Users & devices", if (app.isAdmin) "Invite phones, approve browsers and watches, roles" else "Who can reach this server", true, Icons.Rounded.Group, onClick = { app.go(Route.Devices) })
            RowDivider()
            Row1("Appearance", "Look, theme, app icon, home layout", true, Icons.Rounded.Palette, onClick = { app.go(Route.Appearance) })
            RowDivider()
            Row1("Settings", "Labs, features, software update", true, Icons.Rounded.Settings, N.sub, onClick = { app.go(Route.Settings) })
            RowDivider()
            Row1("Report a problem", "Or shake your ${DeviceForm.noun}", false, Icons.Rounded.BugReport, N.sub, onClick = { app.go(Route.Report) })
            RowDivider()
            Row1("About Nova", "Version $APP_VERSION", false, Icons.Rounded.Info, N.sub, onClick = { app.go(Route.About) })
        }
        Spacer(Modifier.height(130.dp))
    }
}
private data class Quad(val title: String, val sub: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val color: androidx.compose.ui.graphics.Color)

/** Live numbers under the hero: CPU, memory, disk, services. Tap any for the full status page. */
@Composable fun HomeStats(app: AppState) {
    val stats by live(app, "/api/v1/stats?since=9e12", 3_000)        // just the current numbers, every 3 s
    val fresh = stats?.optJSONObject("now")?.takeIf { it.has("cpu") }
    LaunchedEffect(fresh) { if (fresh != null) app.lastNow = fresh }
    val now = fresh ?: app.lastNow ?: Cache["/api/v1/stats"]?.optJSONObject("now")?.takeIf { it.has("cpu") }
    val m = app.overview?.optJSONObject("status")?.optJSONObject("metrics")
    val cs = app.overview?.optJSONObject("containers")
    fun pctOf(s: String?) = s?.let { Regex("(\\d+)%").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
    fun freeOf(s: String?) = s?.let { Regex("\\(([^)]*free)\\)").find(it)?.groupValues?.get(1) }
    val disk = m?.optString("root_used")
    val cards = listOf(
        StatCard(Icons.Rounded.Memory, "CPU", now?.let { "%.0f%%".format(it.optDouble("cpu")) } ?: "—",
            now?.optDouble("temp")?.takeIf { !it.isNaN() }?.let { "%.0f°C".format(it) } ?: m?.optString("cpu_temp") ?: "", now?.optDouble("cpu")?.toFloat()?.div(100), N.blue),
        StatCard(Icons.Rounded.DeveloperBoard, "Memory", now?.let { "%.0f%%".format(it.optDouble("mem")) } ?: m?.optString("memory")?.substringBefore(" ") ?: "—",
            now?.let { "${it.optDouble("mem_used_gb")} / ${it.optDouble("mem_total_gb")} GB" } ?: "", (now?.optDouble("mem")?.toFloat() ?: pctOf(m?.optString("memory")))?.div(100), Color(0xFFBF5AF2)),
        StatCard(Icons.Rounded.Storage, "Disk", freeOf(disk) ?: "—", "system drive", pctOf(disk)?.div(100), N.green),
        StatCard(Icons.Rounded.ViewInAr, "Services", cs?.let { "${it.optInt("running")}/${it.optInt("total")}" } ?: "—",
            m?.optString("websites")?.takeIf { it.isNotEmpty() }?.let { "sites $it" } ?: "containers running",
            cs?.let { if (it.optInt("total") > 0) it.optInt("running").toFloat() / it.optInt("total") else null }, N.amber),
    )
    Column(Modifier.padding(horizontal = Space.gutter), verticalArrangement = Arrangement.spacedBy(Space.gap)) {
        cards.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.gap)) {
                row.forEach { c ->
                    Column(Modifier.weight(1f).bouncy { app.go(Route.Status) }.glassCard(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                        .padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(c.icon, null, tint = c.color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(c.label, color = N.sub, fontSize = 13.sp)
                        }
                        androidx.compose.animation.AnimatedContent(c.value, transitionSpec = {
                            if (reduceMotion()) androidx.compose.animation.fadeIn() togetherWith androidx.compose.animation.fadeOut()
                            else (androidx.compose.animation.slideInVertically { it / 2 } + androidx.compose.animation.fadeIn()) togetherWith
                                (androidx.compose.animation.slideOutVertically { -it / 2 } + androidx.compose.animation.fadeOut()) }, label = "num") { v ->
                            Text(v, color = N.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        }
                        Text(c.sub, color = N.sub, fontSize = 12.sp, maxLines = 1)
                        Spacer(Modifier.height(10.dp))
                        if (c.frac != null) ProgressBar(c.frac, c.color) else Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
private data class StatCard(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val value: String,
                            val sub: String, val frac: Float?, val color: Color)

/** A backup script's set names as shown ("home" → "Home"). */
private object SET_NAMES { operator fun get(k: String): String? = k.takeIf { it.isNotEmpty() }?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } }

/** "Photos · 42% · 12 min left" — or what's known when the script reports no numbers. */
fun backupLine(b: JSONObject): String {
    val p = b.optJSONObject("progress") ?: return "Backing up…"
    val set = SET_NAMES[p.optString("set")] ?: p.optString("set")
    return when (p.optString("phase")) {
        "copying" -> "Backing up $set · ${p.optInt("pct")}%" + (p.optString("eta").takeIf { it.isNotEmpty() && it != "0:00:00" }?.let { " · $it left" } ?: "")
        "checking" -> "Backing up $set · scanning files…"
        else -> p.optJSONArray("done")?.takeIf { it.length() > 0 }?.let { d -> "Backing up · ${(0 until d.length()).joinToString { SET_NAMES[d.getString(it)] ?: d.getString(it) }} done" }
            ?: "Full backup in progress"
    }
}

@Composable fun BackupProgress(b: JSONObject, inset: androidx.compose.ui.unit.Dp = 72.dp) {
    val p = b.optJSONObject("progress")
    Column(Modifier.fillMaxWidth().padding(start = inset, end = 22.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val copying = p?.optString("phase") == "copying"
        ProgressBar(if (copying) p!!.optInt("pct") / 100f else null)
        if (p != null && p.has("step")) {
            Text("Step ${p.optInt("step")} of ${p.optInt("steps")} · ${SET_NAMES[p.optString("set")] ?: p.optString("set")}" +
                (if (copying && p.optString("rate").isNotEmpty()) " · ${p.optString("rate")}" else ""), color = N.sub, fontSize = 13.sp)
        } else Text(b.optString("started_at").takeIf { it.isNotEmpty() }?.let { "Started ${it.drop(11)}" } ?: "Working…", color = N.sub, fontSize = 13.sp)
        p?.optLong("drive_used")?.takeIf { it > 0 }?.let { used ->
            Text("${bytesHuman(used)} on the backup drive so far (of ${bytesHuman(p.optLong("drive_total"))})", color = N.sub, fontSize = 13.sp) }
        p?.optJSONArray("done")?.takeIf { it.length() > 0 }?.let { d ->
            Text("Finished: " + (0 until d.length()).joinToString { SET_NAMES[d.getString(it)] ?: d.getString(it) }, color = N.green, fontSize = 13.sp) }
    }
}

