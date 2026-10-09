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
import androidx.compose.material3.Text
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
    Triple("Home", "overview", Icons.Rounded.Dns to Route.Home),
    Triple("Start page", "start new tab homepage bookmarks web search engine google clock open on", Icons.Rounded.TravelExplore to Route.Start),
    Triple("Status", "graphs cpu memory ram temperature network live", Icons.Rounded.MonitorHeart to Route.Status),
    Triple("Containers", "docker compose logs shell restart stop", Icons.Rounded.ViewInAr to Route.Containers),
    Triple("Apps", "web apps open launch", Icons.Rounded.Apps to Route.Apps),
    Triple("App store", "install store programs", Icons.Rounded.Storefront to Route.Store),
    Triple("Storage & hardware", "drives disks pools raid smart mount unmount temperature fans", Icons.Rounded.Storage to Route.Hardware),
    Triple("Set up drives", "format raid mirror combine pool mergerfs mdadm new drive", Icons.Rounded.AddCircle to Route.StorageWizard()),
    Triple("Backups", "backup restore snapshot nas smb nfs schedule", Icons.Rounded.Backup to Route.Backups),
    Triple("Diagnostics", "speed test internet ping traceroute dns port cpu stress memory test disk speed", Icons.Rounded.Speed to Route.Diagnostics),
    Triple("Updates", "upgrade packages apt containers images nova update", Icons.Rounded.Update to Route.Updates),
    Triple("Inbox", "alerts notifications events history", Icons.Rounded.Notifications to Route.Inbox),
    Triple("Archive", "old events history export", Icons.Rounded.Inventory2 to Route.Archive),
    Triple("Notifications", "discord webhook alerts levels logins usb", Icons.Rounded.NotificationsActive to Route.NotifySettings),
    Triple("Lighting", "fan rgb led color color effect brightness wave", Icons.Rounded.Lightbulb to Route.Lighting),
    Triple("Light schedules", "schedule sunrise sunset fade timer", Icons.Rounded.Schedule to Route.Schedules),
    Triple("Quick panel", "shortcuts tiles restart shut down power free ram", Icons.Rounded.Widgets to Route.QuickPanel),
    Triple("Terminal", "ssh shell command line", Icons.Rounded.Terminal to Route.Ssh),
    Triple("Dashboard mode", "always on tablet wall display", Icons.Rounded.Dashboard to Route.Dashboard),
    Triple("Users & devices", "devices phones browsers users roles remove pair invite approve", Icons.Rounded.Group to Route.Devices),
    Triple("Settings", "software update fingerprint approve from notifications watch connection", Icons.Rounded.Settings to Route.Settings),
    Triple("Appearance", "theme dark light material you style reduce motion home layout navigation pill tabs", Icons.Rounded.Palette to Route.Appearance),
    Triple("Server", "name accent color location sunrise", Icons.Rounded.Dns to Route.ServerSettings),
    Triple("Servers", "multiple servers add switch", Icons.Rounded.Lan to Route.Servers),
    Triple("Setup guide", "help install how to", Icons.Rounded.Help to Route.SetupGuide),
    Triple("About", "version license", Icons.Rounded.Info to Route.About),
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
        Text("Starred screens appear at the top of Menu, in this order.", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
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
