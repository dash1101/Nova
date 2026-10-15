package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Search everything in Nova: screens and settings (by name and the words people use for them), plus
// what's on the server — containers, apps, drives, pools, backups.

data class Hit(val title: String, val sub: String, val icon: ImageVector, val kind: String, val go: (AppState) -> Unit)

/** Screens and settings, with the words someone might type for them. */
val PLACES: List<Triple<String, String, Pair<ImageVector, Route>>> = listOf(
    Triple("Security & privacy", "security privacy approvals 2fa two factor mfa authenticator totp passkey passkeys activity audit lock keys", Icons.Rounded.Shield to Route.Security),
    Triple("Authenticator codes", "2fa totp two factor authenticator google authenticator aegis code approve browser without phone", Icons.Rounded.Key to Route.Security),
    Triple("Passkeys", "passkey webauthn fingerprint face password manager add browser", Icons.Rounded.Key to Route.Security),
    Triple("App lock", "app lock fingerprint pin open nova biometric", Icons.Rounded.Lock to Route.Security),
    Triple("Hide in recent apps", "hide recents screenshots blocked privacy screen secure", Icons.Rounded.VisibilityOff to Route.Security),
    Triple("Recent security activity", "audit log logins pairings approvals refused", Icons.Rounded.Shield to Route.Security),
    Triple("Approvals", "approve browser requests waiting risky actions", Icons.Rounded.VerifiedUser to Route.Approvals),
    Triple("Recommended", "recommended actions suggestions tips to do fix health advice", Icons.Rounded.TaskAlt to Route.Recommended),
    Triple("Developer options", "developer debug frame rate fps limit glass quality requests device details", Icons.Rounded.Build to Route.Dev),
    Triple("Frame rate limit", "fps 60 90 120 refresh rate battery gpu", Icons.Rounded.Speed to Route.Dev),
    Triple("Glass quality", "glass quality gpu lens fringe performance battery", Icons.Rounded.Speed to Route.Dev),
    Triple("Edit Home", "home layout sections order server picture recommended shortcuts at a glance", Icons.Rounded.Edit to Route.EditHome),
    Triple("Choose shortcuts", "shortcuts home pills", Icons.Rounded.Edit to Route.EditShortcuts),
    Triple("Navigation pill", "tabs bottom bar navigation pill dock order", Icons.Rounded.Edit to Route.EditTabs),
    Triple("Edit quick panel", "quick panel tiles order", Icons.Rounded.Edit to Route.EditQuick),
    Triple("Favorites", "favorites pinned starred screens", Icons.Rounded.Star to Route.EditFavorites),
    Triple("Home Assistant light", "home assistant ha light govee strip status color", Icons.Rounded.Lightbulb to Route.HaLight),
    Triple("New container", "add container compose image custom docker", Icons.Rounded.AddCircle to Route.NewContainer),
    Triple("Backup script", "nova-backup script legacy edit", Icons.Rounded.Backup to Route.BackupScript),
    Triple("Glass clarity", "glass clarity transparency frosted liquid", Icons.Rounded.Palette to Route.Appearance),
    Triple("Corner roundness", "corner radius roundness rounded", Icons.Rounded.Palette to Route.Appearance),
    Triple("Icons", "icon style tiles glossy circles outline symbols monochrome", Icons.Rounded.Palette to Route.Appearance),
    Triple("Swap", "swap memory set up swap file", Icons.Rounded.SwapHoriz to Route.Status),
    Triple("Live graphs", "graphs cpu gpu memory swap disk io network temperature", Icons.Rounded.MonitorHeart to Route.Status),
    Triple("Home", "overview", Icons.Rounded.Dns to Route.Home),
    Triple("Start page", "start new tab homepage bookmarks web search engine google clock open on", Icons.Rounded.TravelExplore to Route.Start),
    Triple("Status", "graphs cpu memory ram temperature network live", Icons.Rounded.MonitorHeart to Route.Status),
    Triple("Containers", "docker compose logs shell restart stop", Icons.Rounded.ViewInAr to Route.Containers),
    Triple("Apps", "web apps open launch", Icons.Rounded.Apps to Route.Apps),
    Triple("App store", "install store programs", Icons.Rounded.Storefront to Route.Store),
    Triple("Files", "file manager files folders upload download edit text documents explorer", Icons.Rounded.Folder to Route.Files()),
    Triple("Find a program", "apt package install program software", Icons.Rounded.Search to Route.ProgramSearch),
    Triple("Storage & hardware", "drives disks pools raid smart mount unmount temperature fans", Icons.Rounded.Storage to Route.Hardware),
    Triple("Set up drives", "format raid mirror combine pool mergerfs mdadm new drive", Icons.Rounded.AddCircle to Route.StorageWizard()),
    Triple("Partition manager", "partitions disk management format resize label mount point fstab drive letter", Icons.Rounded.ViewDay to Route.Disks),
    Triple("Backups", "backup restore snapshot nas smb nfs schedule", Icons.Rounded.Backup to Route.Backups),
    Triple("Diagnostics", "speed test internet ping traceroute dns port cpu stress memory test disk speed", Icons.Rounded.Speed to Route.Diagnostics),
    Triple("Updates", "upgrade packages apt containers images nova update", Icons.Rounded.Update to Route.Updates),
    Triple("Inbox", "alerts notifications events history", Icons.Rounded.Notifications to Route.Inbox),
    Triple("Archive", "old events history export", Icons.Rounded.Inventory2 to Route.Archive),
    Triple("Notifications", "discord webhook alerts levels logins usb", Icons.Rounded.NotificationsActive to Route.NotifySettings),
    Triple("Lighting", "fan rgb led color color effect brightness wave", Icons.Rounded.Lightbulb to Route.Lighting),
    Triple("Your lights", "lights zones strip argb header add light second fan d_led 12v rgb setup", Icons.Rounded.Tune to Route.Lights),
    Triple("Light schedules", "schedule sunrise sunset fade timer", Icons.Rounded.Schedule to Route.Schedules),
    Triple("Quick panel", "shortcuts tiles restart shut down power free ram", Icons.Rounded.Widgets to Route.QuickPanel),
    Triple("Terminal", "ssh shell command line", Icons.Rounded.Terminal to Route.Ssh),
    Triple("Dashboard mode", "always on tablet wall display", Icons.Rounded.Dashboard to Route.Dashboard),
    Triple("Users & devices", "devices phones browsers users roles remove pair invite approve", Icons.Rounded.Group to Route.Devices),
    Triple("Settings", "software update fingerprint approve from notifications watch connection", Icons.Rounded.Settings to Route.Settings),
    Triple("Appearance", "theme dark light material you style reduce motion home layout navigation pill tabs app icon launcher icon outline glass black white", Icons.Rounded.Palette to Route.Appearance),
    Triple("Server", "name accent color location sunrise", Icons.Rounded.Dns to Route.ServerSettings),
    Triple("Servers", "multiple servers add switch", Icons.Rounded.Lan to Route.Servers),
    Triple("Setup guide", "help install how to", Icons.Rounded.Help to Route.SetupGuide),
    Triple("About", "version license", Icons.Rounded.Info to Route.About),
    Triple("Free up", "free space clean clear storage disk full delete old files cache logs trash memory ram", Icons.Rounded.CleaningServices to Route.FreeUp),
    Triple("Questions", "faq safe safety privacy data uninstall remove undo revert rollback trust", Icons.Rounded.Help to Route.Faq),
    Triple("Labs", "labs experimental beta features try new", Icons.Rounded.Science to Route.Labs),
    Triple("Automation", "automation automatic crash guard weekly updates cleanup wake on lan", Icons.Rounded.AutoMode to Route.Automation),
    Triple("Who can open it", "cloudflare guests share links public login access immich family album", Icons.Rounded.Group to Route.Labs),
    Triple("Cloudflare auto-setup", "cloudflare tunnel domain dns access remote from anywhere public address publish app labs", Icons.Rounded.Cloud to Route.Labs),
    Triple("Crash-loop guard", "crash loop restart restarting container keeps crashing stop guard automation", Icons.Rounded.Shield to Route.Automation),
    Triple("Weekly container updates", "automatic updates weekly schedule containers images auto update automation", Icons.Rounded.Update to Route.Automation),
    Triple("Clean up old images", "docker images prune free space disk cleanup old versions automation", Icons.Rounded.CleaningServices to Route.Automation),
    Triple("Wake-on-LAN", "wake on lan wol turn on computer pc magic packet automation", Icons.Rounded.PowerSettingsNew to Route.Automation),
    Triple("App icon", "app icon launcher icon home screen outline glass black white material you color", Icons.Rounded.Palette to Route.Appearance),
    Triple("Server terminal", "terminal shell bash console command line root sudo", Icons.Rounded.Terminal to Route.Ssh),
    Triple("Approve a browser or watch", "approve browser watch wear code pair computer", Icons.Rounded.Watch to Route.Devices),
    Triple("Add a server", "add server another server pair new server", Icons.Rounded.AddCircle to Route.Servers),
    Triple("Troubleshoot a container", "troubleshoot crash restart loop rollback previous version recreate container fix", Icons.Rounded.Build to Route.Containers),
    Triple("Report a problem", "report problem bug issue feedback broken shake", Icons.Rounded.BugReport to Route.Report),
    Triple("Plugins", "plugins add-ons extensions tools tweaks", Icons.Rounded.Extension to Route.Plugins),
    Triple("Features", "features turn off disable lighting control rgb software openrgb terminal file manager discovery", Icons.Rounded.Tune to Route.Features),
    Triple("Install Docker", "docker install containers engine compose", Icons.Rounded.ViewInAr to Route.ProgramSearch),
)

fun searchNova(app: AppState, q0: String, data: Map<String, org.json.JSONObject?>): List<Hit> {
    val q = q0.trim().lowercase(); if (q.isEmpty()) return emptyList()
    val words = q.split(Regex("\\s+"))
    fun score(vararg fields: String): Int {
        val text = fields.joinToString(" ").lowercase(); val title = fields.first().lowercase()
        if (words.any { it !in text }) return 0
        return (if (title.startsWith(q)) 100 else 0) + (if (q in title) 50 else 0) + words.count { it in title } * 10 + 1
    }
    val hits = mutableListOf<Pair<Int, Hit>>()
    PLACES.forEach { (t, kw, ir) -> score(t, kw).takeIf { it > 0 }?.let { hits += it to Hit(t, "Screen", ir.first, "Screens & settings") { a -> a.go(ir.second) } } }
    data["/api/v1/containers"]?.optJSONArray("containers").objs().forEach { c ->
        val n = c.optString("name")
        score(n, c.optString("image"), c.optString("stack")).takeIf { it > 0 }?.let { hits += it to Hit(n, "${c.optString("state")} · ${c.optString("image")}", Icons.Rounded.ViewInAr, "Containers") { a -> a.go(Route.Container(n)) } }
    }
    data["/api/v1/apps"]?.optJSONArray("apps").objs().filter { !it.optBoolean("hidden") }.forEach { ap ->
        score(ap.optString("name"), ap.optString("image")).takeIf { it > 0 }?.let { hits += it to Hit(ap.optString("name"), "Open the app", Icons.Rounded.Apps, "Apps") { a -> openApp(a, ap) } }
    }
    data["/api/v1/storage"]?.let { st ->
        st.optJSONArray("drives").objs().forEach { d ->
            score(driveTitle(d), d.optString("model"), d.optJSONArray("mounts").strs().joinToString(" "), d.optString("serial")).takeIf { it > 0 }?.let {
                hits += it to Hit(driveTitle(d), d.optJSONArray("mounts").strs().joinToString().ifEmpty { "Not mounted" }, Icons.Rounded.Storage, "Drives") { a -> a.go(Route.Drive(d.optString("serial"))) } }
        }
        st.optJSONArray("pools").objs().forEach { p ->
            score(p.optString("name"), p.optString("mount"), poolKind(p.optString("type"))).takeIf { it > 0 }?.let {
                hits += it to Hit(p.optString("name"), "${poolKind(p.optString("type"))} · ${p.optString("mount")}", Icons.Rounded.Layers, "Drives") { a -> a.go(Route.Pool(p.optString("id"))) } }
        }
    }
    data["/api/v1/backups"]?.optJSONArray("jobs").objs().forEach { j ->
        score(j.optString("name"), j.optJSONArray("sources").strs().joinToString(" ")).takeIf { it > 0 }?.let {
            hits += it to Hit(j.optString("name"), "Backup · ${j.optJSONArray("sources").strs().joinToString()}", Icons.Rounded.Backup, "Backups") { a -> a.go(Route.Backup(j.optString("id"))) } }
    }
    return hits.sortedByDescending { it.first }.map { it.second }.take(40)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun SearchScreen(app: AppState) {
    var q by remember { mutableStateOf("") }
    val sources = listOf("/api/v1/containers", "/api/v1/apps", "/api/v1/storage", "/api/v1/backups")
    val data = sources.associateWith { live(app, it).value }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val hits = searchNova(app, q, data)
    fun keep(term: String) { val t = term.trim().take(80); if (t.length < 2) return
        AppPrefs.set("search_recent", (listOf(t) + AppPrefs.searchRecent.filter { !it.equals(t, true) }).take(12).joinToString("\n")) }
    fun pin(t: String) { AppPrefs.set("search_pinned", (if (t in AppPrefs.searchPinned) AppPrefs.searchPinned - t else (AppPrefs.searchPinned + t).takeLast(8)).joinToString("\n")) }
    Page("Search", app::back) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp).clip(RoundedCornerShape(28.dp)).background(N.pill)
            .padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = N.sub); Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Screens, settings, containers, apps, drives…", color = N.sub, fontSize = 17.sp)
                BasicTextField(q, { q = it.take(80) }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = N.text, fontSize = 17.sp), cursorBrush = SolidColor(N.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { hits.firstOrNull()?.let { keep(q); it.go(app) } }))
            }
            if (q.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = N.sub, modifier = Modifier.clickable { q = "" })
        }
        if (q.isBlank()) {
            if (AppPrefs.searchPinned.isNotEmpty()) {
                SectionLabel("Pinned")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppPrefs.searchPinned.forEach { t ->
                        Row(Modifier.clip(RoundedCornerShape(50)).background(N.pill).combinedClickable(onClick = { q = t }, onLongClick = { pin(t) }).padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.PushPin, null, tint = N.blue, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(t, color = N.text, fontSize = 14.sp) }
                    }
                }
            }
            val recent = AppPrefs.searchRecent.filter { it !in AppPrefs.searchPinned }
            if (recent.isNotEmpty()) {
                SectionLabel("Recent")
                Group {
                    recent.forEachIndexed { i, t ->
                        if (i > 0) RowDivider()
                        Row1(t, null, false, Icons.Rounded.History, N.sub, onClick = { q = t }) {
                            Row {
                                Icon(Icons.Rounded.PushPin, "Pin", tint = N.sub, modifier = Modifier.size(20.dp).clickable { pin(t) }); Spacer(Modifier.width(18.dp))
                                Icon(Icons.Rounded.Close, "Remove", tint = N.sub, modifier = Modifier.size(20.dp).clickable { AppPrefs.set("search_recent", (AppPrefs.searchRecent - t).joinToString("\n")) })
                            }
                        }
                    }
                    RowDivider()
                    Row1("Clear history", null, false, Icons.Rounded.DeleteSweep, N.red, onClick = { AppPrefs.set("search_recent", "") })
                }
            }
            Text("Try “dark mode”, “raid”, “speed test” or a container's name. Hold a pinned search to unpin it.", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
        }
        else if (hits.isEmpty()) Text("Nothing called “$q”.", color = N.sub, modifier = Modifier.padding(30.dp))
        hits.groupBy { it.kind }.forEach { (kind, list) ->
            SectionLabel(kind)
            Group { list.forEachIndexed { i, h -> if (i > 0) RowDivider(); Row1(h.title, h.sub, false, h.icon, N.blue, onClick = { keep(q); h.go(app) }) } }
        }
    }
}

/** Menu → Favorites: any screen in Nova, starred. */
/** A page of your favorites, for the navigation pill: big tiles, two to a row. */
@Composable fun FavoritesScreen(app: AppState) {
    val favs = AppPrefs.favorites.mapNotNull { f -> PLACES.firstOrNull { it.first == f } }
    TabOrPage(app, "Favorites") {
        if (favs.isEmpty()) Text("Star the screens you use most — in Search, or with Edit below — and they show up here.", color = N.sub, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 10.dp))
        Column(Modifier.padding(horizontal = Space.gutter, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            favs.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (t, _, ir) -> Column(Modifier.weight(1f).glassCard(androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).clickable { app.go(ir.second) }.padding(18.dp)) {
                    Icon(ir.first, null, tint = N.amber, modifier = Modifier.size(28.dp)); Spacer(Modifier.height(12.dp))
                    Text(t, color = N.text, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            } }
        }
        LinksCard(listOf("Edit favorites" to { app.go(Route.EditFavorites) }))
        Spacer(Modifier.height(130.dp))
    }
}

@Composable fun FavoritesGroup(app: AppState) {
    val favs = AppPrefs.favorites.mapNotNull { f -> PLACES.firstOrNull { it.first == f } }
    SectionLabel("Favorites")
    Group {
        favs.forEachIndexed { i, (t, _, ir) -> if (i > 0) RowDivider(); Row1(t, null, false, ir.first, N.amber, onClick = { app.go(ir.second) }) }
        if (favs.isNotEmpty()) RowDivider()
        Row1(if (favs.isEmpty()) "Add favorites" else "Edit favorites", if (favs.isEmpty()) "Star the screens you use most — they show up here" else null, true,
            if (favs.isEmpty()) Icons.Rounded.StarOutline else Icons.Rounded.Edit, N.sub, onClick = { app.go(Route.EditFavorites) })
    }
}

@Composable fun EditFavoritesScreen(app: AppState) {
    Page("Favorites", app::back) {
        Text("Starred screens are tiles in Menu, after the live ones, in this order.", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        Group {
            PLACES.forEachIndexed { i, (t, _, ir) ->
                if (i > 0) RowDivider()
                val on = t in AppPrefs.favorites
                Row1(t, null, false, ir.first, if (on) N.amber else N.sub, onClick = {
                    AppPrefs.set("favorites", (if (on) AppPrefs.favorites - t else AppPrefs.favorites + t).joinToString(","))
                }) { Icon(if (on) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (on) "Remove from favorites" else "Add to favorites", tint = if (on) N.amber else N.sub) }
            }
        }
    }
}
