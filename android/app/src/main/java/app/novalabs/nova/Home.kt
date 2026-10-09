package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.gutter + 8.dp, end = 8.dp, top = 28.dp),
            verticalAlignment = Alignment.CenterVertically) {
            var switcher by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).clickable { switcher = true },
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(serverName(app), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = N.text, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Rounded.KeyboardArrowDown, "Switch server", tint = N.sub)
                }
                val ctx = app.activity
                val others = Servers.all(ctx).filter { it != app.pairing.profile && Pairing(ctx, it).paired }
                OneMenu(switcher, { switcher = false }, others.map { id -> Pairing(ctx, id).label.ifEmpty { "Server" } to { app.switchServer(id) } } +
                    listOf("Add a server…" to { app.switchServer(Servers.create(ctx)) }, "Manage servers" to { app.go(Route.Servers) }))
            }
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
                Text("${cs.optInt("running")}/${cs.optInt("total")} running", color = N.sub, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        // Banner (only when something is going on)
        val backupRunning = m?.optString("data_backup")?.contains("running") == true
        val backup = if (backupRunning) live(app, "/api/v1/backup", 5_000).value else null
        when {
            app.notAuthorized -> Banner("This ${DeviceForm.noun} isn't authorized anymore — tap to pair again", N.red) { app.pairing.clear(); app.paired = false }
            app.error != null -> DisconnectedBanner(app)
            level != "ok" -> Banner(st?.optString("headline") ?: "", levelColor(level, N)) { app.go(Route.Inbox) }
            backupRunning -> Banner(backup?.let { backupLine(it) } ?: "Backing up…", N.blue) { app.go(Route.QuickPanel) }
        }
    }
    // Hero: the server with the live fan (tap it for Lighting)
    val hero: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { h ->
        Box(Modifier.fillMaxWidth().height(h).then(if (app.has("lighting"))
            Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { app.go(Route.Lighting) }
            else Modifier)) {
            val drives = st?.optJSONObject("metrics")?.optJSONArray("drive_states")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            ServerHero(app.fan, levelColor(level, N), drives, app.clockSkew, Modifier.fillMaxSize())
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
            when (id) { "hero" -> hero(260.dp); "shortcuts" -> pills(); else -> HomeStats(app) }
            Spacer(Modifier.height(Space.gap))
        }
        Spacer(Modifier.height(118.dp))
    } else Row(Modifier.fillMaxSize()) {                       // tablet: server on the left, numbers on the right
        Column(Modifier.weight(1f).verticalScroll(routeScroll())) { top(); if (AppPrefs.homeHero) hero(440.dp); Spacer(Modifier.height(30.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).statusBarsPadding().padding(top = 36.dp, end = 8.dp)) {
            AppPrefs.homeOrder.filter { it != "hero" && sectionOn(it) }.forEach { id ->
                if (id == "shortcuts") pills() else HomeStats(app); Spacer(Modifier.height(Space.gap)) }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable fun MenuScreen(app: AppState) {
    val m = app.overview?.optJSONObject("status")?.optJSONObject("metrics")
    val cs = app.overview?.optJSONObject("containers")
    Column(Modifier.fillMaxSize().verticalScroll(routeScroll())) {
        Text("Menu", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = N.text,
            modifier = Modifier.statusBarsPadding().padding(start = Space.gutter + 8.dp, top = 28.dp, bottom = 16.dp))
        Group {
            Row1("Containers", cs?.let { "${it.optInt("running")} of ${it.optInt("total")} running" }, true,
                Icons.Rounded.ViewInAr, onClick = { app.go(Route.Containers) })
            if (app.has("lighting")) RowDivider()
            if (app.has("lighting")) Row1("Lighting", app.fan?.let { if (it.optBoolean("on")) "${it.optString("effect").replaceFirstChar { c -> c.uppercase() }} · ${it.optInt("brightness")}%" else "Off" },
                true, Icons.Rounded.Lightbulb, androidx.compose.ui.graphics.Color(0xFFFFB020), onClick = { app.go(Route.Lighting) })
        }
        Group {
            Row1("Storage & hardware", m?.optString("photo_pool_used")?.let { "Photos $it" }, true, Icons.Rounded.Storage,
                androidx.compose.ui.graphics.Color(0xFF3ECF6E), onClick = { app.go(Route.Hardware) })
            RowDivider()
            Row1("Quick panel", "Your shortcuts — tap ✎ to customise", true, Icons.Rounded.Widgets, onClick = { app.go(Route.QuickPanel) })
            RowDivider()
            Row1("Server status", "Live graphs, storage, backups", true, Icons.Rounded.MonitorHeart, androidx.compose.ui.graphics.Color(0xFF3ECF6E),
                onClick = { app.go(Route.Status) })
            RowDivider()
            Row1("Dashboard mode", "Always-on screen for a tablet or spare phone", true, Icons.Rounded.Dashboard,
                androidx.compose.ui.graphics.Color(0xFF64D2FF), onClick = { app.go(Route.Dashboard) })
            RowDivider()
            Row1("Terminal", if (SshSession.active) "Connected" else "SSH into the server", SshSession.active, Icons.Rounded.Terminal,
                androidx.compose.ui.graphics.Color(0xFF8E8E93), onClick = { app.go(if (SshSession.active) Route.SshTerm else Route.Ssh) })
        }
        Group {
            Row1("Inbox", if (app.unread > 0) "${app.unread} new" else "Alerts, logins and server events", true, Icons.Rounded.Notifications,
                androidx.compose.ui.graphics.Color(0xFFFF5A5A), onClick = { app.go(Route.Inbox) })
            RowDivider()
            Row1("Notifications", "Phone, Discord, logins", false, Icons.Rounded.NotificationsActive,
                onClick = { app.go(Route.NotifySettings) })
        }
        Group {
            Row1("App store", "Install apps & programs", true, Icons.Rounded.Storefront,
                androidx.compose.ui.graphics.Color(0xFFBF5AF2), onClick = { app.tab(Route.Store) })
        }
        Group {
            Row1("Users & devices", if (app.isAdmin) "Invite phones, approve browsers, roles" else "Who can reach this server", true,
                Icons.Rounded.Group, onClick = { app.go(Route.Devices) })
            RowDivider()
            Row1("Settings", null, icon = Icons.Rounded.Settings, iconTint = N.sub, onClick = { app.go(Route.Settings) })
            RowDivider()
            Row1("About Nova", "Version $APP_VERSION", false, Icons.Rounded.Info, N.sub, onClick = { app.go(Route.About) })
        }
        Spacer(Modifier.height(130.dp))
    }
}

/** Live numbers under the hero: CPU, memory, disk, services. Tap any for the full status page. */
@Composable private fun HomeStats(app: AppState) {
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

private val SET_NAMES = mapOf("immich" to "Photos", "home" to "Home folder", "minecraft" to "Minecraft",
    "cold" to "Cold storage", "gaming" to "Games")

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

