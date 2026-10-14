package app.novalabs.nova

import kotlinx.coroutines.delay
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private fun storeIcon(name: String): ImageVector = when (name) {
    "monitor_heart" -> Icons.Rounded.MonitorHeart; "view_in_ar" -> Icons.Rounded.ViewInAr; "folder_open" -> Icons.Rounded.FolderOpen
    "movie" -> Icons.Rounded.Movie; "key" -> Icons.Rounded.Key; "sync" -> Icons.Rounded.Sync
    "article" -> Icons.AutoMirrored.Rounded.Article; "code" -> Icons.Rounded.Code; "terminal" -> Icons.Rounded.Terminal
    "build" -> Icons.Rounded.Build; "draw" -> Icons.Rounded.Draw; else -> Icons.Rounded.Apps
}
/** An app's icon: its own if we have one, else one for its category. */
private fun storeIcon(name: String, category: String): ImageVector = storeIcon(name).takeIf { it != Icons.Rounded.Apps } ?: when (category) {
    "Media" -> Icons.Rounded.Movie; "Photos" -> Icons.Rounded.PhotoLibrary; "Downloads" -> Icons.Rounded.Download; "Smart home" -> Icons.Rounded.Home
    "Home" -> Icons.Rounded.Kitchen; "Documents" -> Icons.Rounded.Description; "Files" -> Icons.Rounded.FolderOpen; "Utilities" -> Icons.Rounded.Build
    "Productivity" -> Icons.Rounded.EditNote; "Dashboards" -> Icons.Rounded.Dashboard; "AI" -> Icons.Rounded.AutoAwesome; "Privacy" -> Icons.Rounded.Shield
    "Monitoring" -> Icons.Rounded.MonitorHeart; "Notifications" -> Icons.Rounded.Notifications; "Network" -> Icons.Rounded.Lan; "Backup" -> Icons.Rounded.Backup
    "Security" -> Icons.Rounded.Key; "Development" -> Icons.Rounded.Code; "Management" -> Icons.Rounded.ViewInAr; else -> Icons.Rounded.Apps
}
private val CAT_COLORS = mapOf("Monitoring" to Color(0xFF3ECF6E), "Management" to Color(0xFF3E91FF), "Files" to Color(0xFFFFB020),
    "Media" to Color(0xFFFF5A8A), "Security" to Color(0xFFBF5AF2), "Development" to Color(0xFF00C7BE), "Utilities" to Color(0xFFFF9500),
    "Backup" to Color(0xFF5E5CE6), "Network" to Color(0xFF64D2FF), "Shell" to Color(0xFF8E8E93), "Services" to Color(0xFF3E91FF),
    "Power" to Color(0xFFFFCC00), "Photos" to Color(0xFFFF9F0A), "Downloads" to Color(0xFF30D158), "Smart home" to Color(0xFF64D2FF),
    "Home" to Color(0xFFFFB020), "Documents" to Color(0xFF8E8E93), "Productivity" to Color(0xFF5E5CE6), "Dashboards" to Color(0xFF3E91FF),
    "AI" to Color(0xFFBF5AF2), "Privacy" to Color(0xFF3ECF6E), "Notifications" to Color(0xFFFF453A), "Editor's choice" to Color(0xFF3E91FF))

private fun storeItem(id: String) = Cache["/api/v1/store"]?.optJSONArray("items")?.let { a ->
    (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("id") == id } }

@Composable private fun AppIcon(icon: ImageVector, color: Color, size: Int = 52) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3).dp)).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size((size * 0.52).dp))
    }
}

@Composable fun StoreScreen(app: AppState) {
    var tab by remember { mutableIntStateOf(0) }
    val appsLive = live(app, "/api/v1/store"); val progsLive = live(app, "/api/v1/programs")
    val apps = appsLive.value?.optJSONArray("items"); val progs = progsLive.value?.optJSONArray("items")
    val docker = appsLive.value?.optBoolean("docker", true) ?: true
    var busy by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf<String?>(null) }           // a category picked from the chips (null: everything)
    var hub by remember { mutableStateOf<List<JSONObject>?>(null) }; var hubBusy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<JSONObject>?>(null) }      // the whole apt catalog, for the Programs search
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(q, tab, round) {
        hub = null
        if (tab == 1 && q.trim().length >= 2) { delay(350); found = runCatching { app.api.get("/api/v1/programs/search?q=${android.net.Uri.encode(q.trim())}").optJSONArray("items").objs() }.getOrNull() }
        else found = null
    }
    fun programAction(p: JSONObject, reload: () -> Unit) {
        val pkg = p.optString("pkg"); val inst = p.optBoolean("installed"); busy = pkg
        app.act { try {
            val j = app.waitTask(app.stepUp("${if (inst) "Remove" else "Install"} ${p.optString("name").ifEmpty { pkg }}", "POST", "/api/v1/programs/$pkg/${if (inst) "remove" else "install"}").optString("task"))
            app.toast(if (j.optString("state") == "done") "${p.optString("name").ifEmpty { pkg }} ${if (inst) "removed" else "installed"}" else "Failed: ${j.optString("error")}")
            reload()
        } finally { busy = null } }
    }
    @Composable fun ProgramRow(p: JSONObject, reload: () -> Unit) {
        val pkg = p.optString("pkg"); val inst = p.optBoolean("installed")
        Row1(p.optString("name").ifEmpty { pkg }, p.optString("description")) {
            if (busy == pkg) OneSpinner()
            else if ((inst && (p.optBoolean("protected") || p.optBoolean("essential"))) || !app.isAdmin) Text(if (inst) "Installed" else "", color = N.sub, fontSize = 14.sp)
            else PillButton(if (inst) "Remove" else "Get", color = if (inst) N.red else N.blue) { programAction(p, reload) }
        }
    }
    TabOrPage(app, "App store") {
        Text(when (tab) { 0 -> "Containers run in Docker, each in its own box with its own folder in /opt."; 1 -> "Programs install straight onto the server with its package manager (apt), no Docker needed."; else -> "Plugins add apps and one-tap tools to Nova." },
            color = N.sub, fontSize = 15.sp, modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 8.dp, bottom = 12.dp))
        Segmented(listOf("Containers", "Programs", "Plugins"), tab) { tab = it; q = "" }
        Box(Modifier.padding(horizontal = Space.gutter, vertical = 8.dp)) {
            OneTextField(q, { q = it.take(60) }, listOf("Search containers", "Search every program", "Search plugins")[tab], Modifier.fillMaxWidth()) }
        if (tab == 2) PluginsTab(app, q.trim().lowercase())
        else if (tab == 0) {
            val items = apps.objs()
            if (!docker) Group {
                Row1("Docker isn't installed", "Containers need it. Install Docker and Compose from your package manager — Nova sets them up to start with the server.", false, Icons.Rounded.ViewInAr, N.amber)
                if (app.isAdmin) { RowDivider(); Row1("Install Docker", null, false, Icons.Rounded.Download, onClick = { programAction(JSONObject().put("pkg", "docker.io").put("name", "Docker"), { appsLive.value = null; app.act { appsLive.value = app.api.get("/api/v1/store") } }) }) {
                    if (busy == "docker.io") OneSpinner() } }
            }
            val ql = q.trim().lowercase()
            if (ql.isEmpty() && items.isNotEmpty()) {
                // categories at a glance: tap one to see just those
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf<String?>(null) + items.map { it.optString("category") }.distinct().sorted()).forEach { c ->
                        val on = cat == c
                        Text(c ?: "All", color = if (on) N.bg else N.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) N.text else N.pill).clickable { cat = c }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }
            if (ql.isEmpty() && cat != null) {
                SectionLabel(cat!!); Group { items.filter { it.optString("category") == cat }.forEachIndexed { i, it -> if (i > 0) RowDivider(); StoreRow(app, it) } }
            } else if (ql.isEmpty()) {
                val installed = items.filter { it.optBoolean("installed") }
                val featured = items.filter { it.optBoolean("featured") && !it.optBoolean("installed") }
                if (installed.isNotEmpty()) { SectionLabel("Installed"); Group { installed.forEachIndexed { i, it -> if (i > 0) RowDivider(); StoreRow(app, it) } } }
                if (featured.isNotEmpty()) { SectionLabel("Editor's choice"); Group { featured.forEachIndexed { i, it -> if (i > 0) RowDivider(); StoreRow(app, it) } } }
                items.filterNot { it.optBoolean("installed") || it.optBoolean("featured") }.groupBy { it.optString("category") }.toSortedMap().forEach { (cat, list) ->
                    SectionLabel(cat); Group { list.forEachIndexed { i, it -> if (i > 0) RowDivider(); StoreRow(app, it) } }
                }
                if (apps == null) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp))
            } else {
                val m = items.filter { listOf("name", "description", "category", "image").any { k -> it.optString(k).lowercase().contains(ql) } }
                if (m.isNotEmpty()) { SectionLabel("In the store"); Group { m.forEachIndexed { i, it -> if (i > 0) RowDivider(); StoreRow(app, it) } } }
                SectionLabel("Docker Hub")
                Group {
                    val h = hub
                    if (h == null) Row1(if (hubBusy) "Searching Docker Hub…" else "Search Docker Hub for “${q.trim()}”", "Any public image — you set its ports and folders", true, Icons.Rounded.TravelExplore,
                        enabled = app.isAdmin && !hubBusy, onClick = { hubBusy = true; app.act { try { hub = app.api.get("/api/v1/store/hub?q=${android.net.Uri.encode(q.trim())}").optJSONArray("results").objs() } finally { hubBusy = false } } })
                    else if (h.isEmpty()) Row1("Nothing on Docker Hub for “${q.trim()}”")
                    else h.forEachIndexed { i, r -> if (i > 0) RowDivider()
                        Row1(r.optString("image") + if (r.optBoolean("official")) "  ·  official" else "", listOfNotNull(r.optString("description").ifEmpty { null }, "★ ${r.optInt("stars")}").joinToString(" · "), false, Icons.Rounded.ViewInAr,
                            onClick = { ContainerHint.image = r.optString("image"); app.go(Route.NewContainer) }) }
                }
            }
        } else {
            val ql = q.trim()
            if (ql.length >= 2) {
                val f = found
                SectionLabel("Programs")
                Group {
                    if (f == null) Row1("Searching…")
                    else if (f.isEmpty()) Row1("Nothing called “$ql”")
                    else f.forEachIndexed { i, p -> if (i > 0) RowDivider(); ProgramRow(p) { round++ } }
                }
            } else {
                val items = progs.objs()
                if (app.isAdmin) Text("Type to search everything your package manager offers. These are our picks:", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
                items.groupBy { it.optString("category") }.forEach { (cat, list) ->
                    SectionLabel(cat)
                    Group { list.forEachIndexed { i, p -> if (i > 0) RowDivider(); ProgramRow(p) { app.act { progsLive.value = app.api.get("/api/v1/programs") } } } }
                }
                if (progs == null) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp))
            }
        }
        Spacer(Modifier.height(130.dp))
    }
}

@Composable private fun StoreRow(app: AppState, it: JSONObject) {
    val color = CAT_COLORS[it.optString("category")] ?: N.blue
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(storeIcon(it.optString("icon"), it.optString("category")), color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(it.optString("name"), color = N.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            ExpandableSub(it.optString("description"), N.sub, lines = 2, size = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        PillButton(if (it.optBoolean("installed")) "Open" else "Get") { app.go(Route.StoreItem(it.optString("id"))) }
    }
}

@Composable fun StoreItemScreen(app: AppState, id: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var item by remember { mutableStateOf(storeItem(id)) }
    var job by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    suspend fun reload() { app.api.get("/api/v1/store"); item = storeItem(id) }
    var pct by remember { mutableStateOf<Float?>(null) }
    /** Follow an install/uninstall to the end (it keeps going on the server if you leave). */
    suspend fun follow(tid: String, okMsg: String) {
        if (tid.isEmpty()) return
        val t = app.waitTask(tid) { t -> job = t.optString("step").ifEmpty { "Working…" }; pct = (t.optDouble("pct", 0.0) / 100).toFloat() }
        pct = null
        app.toast(if (t.optString("state") == "done") okMsg else "Failed: ${t.optString("error")}"); reload()
    }
    LaunchedEffect(id) {                       // came back while it's still installing: pick it up again
        runCatching { app.runningTask(listOf("store-install", "store-uninstall"), id) }.getOrNull()?.let { t ->
            job = t.optString("step"); runCatching { follow(t.optString("id"), if (t.optString("kind") == "store-install") "Ready" else "Uninstalled") }; job = null }
    }
    val it = item ?: return
    val inst = it.optBoolean("installed")
    val host = app.pairing.lanUrl.substringAfter("//").substringBefore("/").substringBefore(":").ifEmpty { "localhost" }
    val url = "http://$host:${it.optInt("port")}${it.optString("path", "/")}"
    val color = CAT_COLORS[it.optString("category")] ?: N.blue
    Page(it.optString("name"), app::back) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AppIcon(storeIcon(it.optString("icon"), it.optString("category")), color, 110)
            Spacer(Modifier.height(14.dp))
            Text(it.optString("category"), color = color, fontWeight = FontWeight.SemiBold)
            Text(job ?: if (inst) "Installed · ${it.optString("state")}" else "Not installed", color = N.sub)
        }
        pct?.let { p ->
            Column(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp).fillMaxWidth().glassCard(androidx.compose.foundation.shape.RoundedCornerShape(22.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Text(job ?: "Working…", color = N.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1); Text("${(p * 100).toInt()}%", color = N.sub) }
                androidx.compose.material3.LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp)), color = N.blue, trackColor = N.pill)
                Text("You can leave this page — it keeps going, and the Inbox shows its progress.", color = N.sub, fontSize = 12.sp)
            }
        }
        if (!app.isAdmin) Text("View-only access: an admin can install or remove apps.", color = N.sub, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (inst && !app.isAdmin) PrimaryButton("Open", Modifier.weight(1f)) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            else if (!app.isAdmin) {}
            else if (inst) {
                PrimaryButton("Open", Modifier.weight(1f)) { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                PrimaryButton("Uninstall", Modifier.weight(1f), job == null, N.pill) { confirmRemove = true }
            } else {
                PrimaryButton(if (job != null) "Installing…" else "Install", Modifier.weight(1f), job == null && it.optBoolean("port_free", true)) {
                    app.act {
                        job = "Starting…"
                        try { follow(app.stepUp("Install ${it.optString("name")}", "POST", "/api/v1/store/$id/install").optString("task"), "${it.optString("name")} is ready") }
                        finally { job = null }
                    }
                }
            }
        }
        if (!inst && !it.optBoolean("port_free", true)) Text("Port ${it.optInt("port")} is already in use on the server.",
            color = N.amber, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
        SectionLabel("About")
        Group {
            Text(it.optString("description"), color = N.text, fontSize = 16.sp, modifier = Modifier.padding(22.dp))
            it.optString("notes").takeIf { n -> n.isNotEmpty() }?.let { n -> RowDivider()
                Row1("Good to know", n, false, Icons.Rounded.Info, N.amber) }
        }
        SectionLabel("Details")
        Group {
            Row1("Address", url, true); RowDivider()
            Row1("Image", it.optString("image")); RowDivider()
            Row1("Data", "/opt/$id/data on the server")
        }
        if (inst) LinksCard(listOf("Manage it — logs, restart, troubleshoot, roll back" to { app.go(Route.Container(id)) }, "Check for updates" to { app.go(Route.Updates) }))
    }
    if (confirmRemove) OneDialog({ confirmRemove = false }, "Uninstall ${it.optString("name")}?",
        "It stops and is removed. Its data is kept on the server (in /opt/.nova-uninstalled) in case you want it back.",
        listOf(DialogButton("Cancel") { confirmRemove = false }, DialogButton("Uninstall", N.red) { confirmRemove = false; app.act { job = "Uninstalling…"
            try { follow(app.stepUp("Uninstall ${it.optString("name")}", "POST", "/api/v1/store/$id/uninstall").optString("task"), "Uninstalled") }
            finally { job = null } } }))
}


/** Any apt package: search, then install or remove (fingerprint) with apt's own progress. */
@Composable fun ProgramSearchScreen(app: AppState) {
    var q by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
    val busy = remember { mutableStateMapOf<String, Float>() }
    var ask by remember { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(q) {
        items = null; if (q.trim().length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        items = runCatching { app.api.get("/api/v1/programs/search?q=" + android.net.Uri.encode(q.trim())).optJSONArray("items").objs() }.getOrElse { app.toast(it.message ?: "Search failed"); emptyList() }
    }
    Page("Find a program", app::back) {
        Box(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) { OneTextField(q, { q = it.take(60) }, "Search, e.g. htop or “disk usage”", Modifier.fillMaxWidth()) }
        Note("These come from your system's package manager (apt). Installing or removing asks for your fingerprint; Nova won't remove what the server needs to run.", 13.sp, 6.dp)
        val l = items
        when {
            q.trim().length < 2 -> {}
            l == null -> Text("Searching…", color = N.sub, modifier = Modifier.padding(30.dp))
            l.isEmpty() -> Text("Nothing called “${q.trim()}”.", color = N.sub, modifier = Modifier.padding(30.dp))
            else -> Group {
                l.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    val pkg = p.optString("pkg"); val inst = p.optBoolean("installed")
                    Row1(pkg, p.optString("description")) {
                        val b = busy[pkg]
                        if (b != null) Text("${b.toInt()}%", color = N.sub, fontSize = 14.sp)
                        else if (inst && p.optBoolean("essential")) Text("Needed", color = N.sub, fontSize = 13.sp)
                        else PillButton(if (inst) "Remove" else "Install", color = if (inst) N.red else N.blue) { ask = p }
                    }
                }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
    ask?.let { p ->
        val pkg = p.optString("pkg"); val inst = p.optBoolean("installed")
        OneDialog({ ask = null }, "${if (inst) "Remove" else "Install"} $pkg?", p.optString("description"), listOf(DialogButton("Cancel") { ask = null },
            DialogButton(if (inst) "Remove" else "Install", if (inst) N.red else N.blue) { ask = null
                app.act {
                    busy[pkg] = 0f
                    try {
                        val t = app.waitTask(app.stepUp("${if (inst) "Remove" else "Install"} $pkg", "POST", "/api/v1/programs/$pkg/${if (inst) "remove" else "install"}").optString("task")) { busy[pkg] = it.optDouble("pct", 0.0).toFloat() }
                        if (t.optString("state") == "done") { p.put("installed", !inst); items = items?.toList(); app.toast("$pkg ${if (inst) "removed" else "installed"}") }
                        else app.toast(t.optString("error").ifEmpty { "It didn't work" })
                    } finally { busy.remove(pkg) }
                } }))
    }
}


/** Store → Plugins: the community list (reviewed or not), what's installed, and installing from a link or a file. */
@Composable private fun PluginsTab(app: AppState, ql: String) {
    var cat by remember { mutableStateOf<JSONObject?>(null) }
    var mine by remember { mutableStateOf<List<JSONObject>?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<Triple<JSONObject, JSONObject, Boolean>?>(null) }     // (source, preview, reviewed)
    var askUrl by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<JSONObject?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(round) {
        cat = runCatching { app.api.get("/api/v1/plugins/catalog") }.getOrElse { JSONObject().put("error", it.message) }
        mine = runCatching { app.api.get("/api/v1/plugins").optJSONArray("plugins").objs() }.getOrNull() ?: emptyList()
    }
    fun look(src: JSONObject, reviewed: Boolean) = app.act { preview = Triple(src, app.api.post("/api/v1/plugins/preview", src), reviewed) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) app.act {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@act
            if (bytes.size > 256 * 1024) { app.toast("That's too big for a plugin (256 KB at most)"); return@act }
            look(JSONObject().put("zip", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)), false)
        }
    }
    Group { Row1("Plugins come from other people", "A plugin's tools run commands on your server as your normal account (never as root). Install only ones you trust, and read what each tool runs before you say yes.",
        false, Icons.Rounded.Warning, N.amber) }
    val installed = mine.orEmpty()
    if (installed.isNotEmpty()) {
        SectionLabel("Installed")
        Group { installed.forEachIndexed { i, p -> if (i > 0) RowDivider()
            Row1("${p.optString("name")} ${p.optString("version")}", listOf(p.optString("description"), if (p.optBoolean("on", true)) "" else "switched off").filter { it.isNotEmpty() }.joinToString(" · "),
                icon = Icons.Rounded.Widgets) { if (app.isAdmin) PillButton("Remove", color = N.red) { remove = p } } } }
    }
    SectionLabel("Community plugins")
    Group {
        val c = cat; val list = c?.optJSONArray("plugins").objs().filter { ql.isEmpty() || listOf("name", "description", "author").any { k -> it.optString(k).lowercase().contains(ql) } }
        val have = installed.map { it.optString("id") }.toSet()
        when {
            c == null -> Row1("Loading…")
            list.isEmpty() && c.optString("error").isNotEmpty() -> Row1("Couldn't load the list", c.optString("error"), false, Icons.Rounded.Warning, N.amber)
            list.isEmpty() -> Row1(if (ql.isNotEmpty()) "No plugin matches “$ql”" else "None listed yet")
            else -> list.forEachIndexed { i, p -> if (i > 0) RowDivider()
                val rev = p.optBoolean("reviewed")
                Row1(p.optString("name"), listOf(p.optString("description"), p.optString("author").takeIf { it.isNotEmpty() }?.let { "by $it" } ?: "",
                        if (rev) "✓ Reviewed by Nova" else "Not reviewed — listed as-is").filter { it.isNotEmpty() }.joinToString(" · "),
                    icon = if (rev) Icons.Rounded.Shield else Icons.Rounded.Widgets, iconTint = if (rev) N.green else N.amber) {
                    if (p.optString("id") in have) Text("Installed", color = N.sub, fontSize = 14.sp)
                    else if (app.isAdmin) PillButton("Get") { look(JSONObject().put("url", p.optString("url")), rev) }
                } }
        }
    }
    if (app.isAdmin) {
        SectionLabel("Your own")
        Group {
            Row1("Install from a link", "An https:// link to a plugin.json or a .zip", true, Icons.Rounded.Link, onClick = { askUrl = true }); RowDivider()
            Row1("Install from a file", "A .zip or plugin.json on this ${DeviceForm.noun}", true, Icons.Rounded.Upload, onClick = { pick.launch("*/*") })
        }
    }
    Note("Make one: a folder with a plugin.json — see docs/PLUGINS.md. Share it by adding it to plugins/index.json on GitHub.", 13.sp, 4.dp)
    if (askUrl) { var u by remember { mutableStateOf("") }
        OneDialog({ askUrl = false }, "Install from a link", null, listOf(DialogButton("Cancel") { askUrl = false },
            DialogButton("Look at it", N.blue, enabled = u.startsWith("https://")) { askUrl = false; look(JSONObject().put("url", u.trim()), false) })) {
            OneTextField(u, { u = it.trim().take(1000) }, "https://…/plugin.json", Modifier.fillMaxWidth().padding(horizontal = 22.dp), mono = true) } }
    preview?.let { (src, pv, rev) -> val p = pv.optJSONObject("plugin") ?: JSONObject(); val upd = pv.optBoolean("installed")
        OneDialog({ preview = null }, "${if (upd) "Update" else "Install"} ${p.optString("name")} ${p.optString("version")}?",
            listOf(p.optString("author").takeIf { it.isNotEmpty() }?.let { "By $it." } ?: "", if (rev) "Reviewed by the Nova project." else "Not reviewed by anyone at Nova — check what it runs.",
                if (upd) "Replaces version ${pv.optString("installed_version").ifEmpty { "?" }}." else "").filter { it.isNotEmpty() }.joinToString(" "),
            listOf(DialogButton("Cancel") { preview = null }, DialogButton(if (upd) "Update" else "Install", N.blue) { preview = null
                app.act { app.stepUp("Install the plugin ${p.optString("name")} — its tools run as your normal account", "POST", "/api/v1/plugins/install",
                    JSONObject(src.toString()).put("sha256", pv.optString("sha256"))); app.toast("${p.optString("name")} installed — its apps and tools are in Apps"); round++ } })) {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.optString("description").isNotEmpty()) Text(p.optString("description"), color = N.sub, fontSize = 14.sp)
                p.optJSONArray("apps").objs().forEach { a -> Text("App: ${a.optString("name")} — ${a.optString("url").ifEmpty { ":" + a.optInt("port") + a.optString("path") }}", color = N.text, fontSize = 14.sp) }
                p.optJSONArray("commands").objs().forEach { c ->
                    Text("Tool: ${c.optString("name")}${if (c.optBoolean("confirm")) " (asks first)" else ""}", color = N.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(c.optString("command"), color = N.text, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(N.pill).padding(10.dp))
                }
            }
        } }
    remove?.let { p -> OneDialog({ remove = null }, "Remove ${p.optString("name")}?", "Its apps and tools leave Nova. You can install it again any time.",
        listOf(DialogButton("Cancel") { remove = null }, DialogButton("Remove", N.red) { remove = null
            app.act { app.stepUp("Remove the plugin ${p.optString("name")}", "POST", "/api/v1/plugins/remove", JSONObject().put("id", p.optString("id"))); app.toast("Removed"); round++ } })) }
}
