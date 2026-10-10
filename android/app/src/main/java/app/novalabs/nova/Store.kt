package app.novalabs.nova

import android.content.Intent
import android.net.Uri
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
private val CAT_COLORS = mapOf("Monitoring" to Color(0xFF3ECF6E), "Management" to Color(0xFF3E91FF), "Files" to Color(0xFFFFB020),
    "Media" to Color(0xFFFF5A8A), "Security" to Color(0xFFBF5AF2), "Development" to Color(0xFF00C7BE), "Utilities" to Color(0xFFFF9500),
    "Backup" to Color(0xFF5E5CE6), "Network" to Color(0xFF64D2FF), "Shell" to Color(0xFF8E8E93), "Services" to Color(0xFF3E91FF),
    "Power" to Color(0xFFFFCC00))

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
    var busy by remember { mutableStateOf<String?>(null) }
    TabOrPage(app, "App store") {
        Text("Hand-picked for your server. Installs run on Nova and show up in Containers.", color = N.sub, fontSize = 15.sp,
            modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 8.dp, bottom = 12.dp))
        Segmented(listOf("Apps", "Programs"), tab) { tab = it }
        if (tab == 0) {
            val items = apps?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
            val installed = items.filter { it.optBoolean("installed") }
            if (installed.isNotEmpty()) { SectionLabel("Installed"); Group {
                installed.forEachIndexed { i, it -> if (i > 0) RowDivider(); StoreRow(app, it) } } }
            items.filterNot { it.optBoolean("installed") }.groupBy { it.optString("category") }.forEach { (cat, list) ->
                SectionLabel(cat); Group { list.forEachIndexed { i, it -> if (i > 0) RowDivider(); StoreRow(app, it) } }
            }
            if (apps == null) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp))
        } else {
            val items = progs?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
            if (app.isAdmin) Group { Row1("Find any program", "Search everything in your system's package manager (apt)", true, Icons.Rounded.Search, onClick = { app.go(Route.ProgramSearch) }) }
            items.groupBy { it.optString("category") }.forEach { (cat, list) ->
                SectionLabel(cat)
                Group {
                    list.forEachIndexed { i, p ->
                        if (i > 0) RowDivider()
                        val pkg = p.optString("pkg"); val inst = p.optBoolean("installed")
                        Row1(p.optString("name"), p.optString("description")) {
                            if (busy == pkg) OneSpinner()
                            else if ((inst && p.optBoolean("protected")) || !app.isAdmin) Text(if (inst) "Installed" else "", color = N.sub, fontSize = 14.sp)
                            else PillButton(if (inst) "Remove" else "Get", color = if (inst) N.red else N.blue) {
                                busy = pkg
                                app.act {
                                    try {
                                        val j = app.waitTask(app.stepUp("${if (inst) "Remove" else "Install"} ${p.optString("name")}", "POST",
                                            "/api/v1/programs/$pkg/${if (inst) "remove" else "install"}").optString("task"))
                                        app.toast(if (j.optString("state") == "done") "${p.optString("name")} ${if (inst) "removed" else "installed"}"
                                                  else "Failed: ${j.optString("error")}")
                                        progsLive.value = app.api.get("/api/v1/programs")
                                    } finally { busy = null }
                                }
                            }
                        }
                    }
                }
            }
            if (progs == null) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp))
        }
        Spacer(Modifier.height(130.dp))
    }
}

@Composable private fun StoreRow(app: AppState, it: JSONObject) {
    val color = CAT_COLORS[it.optString("category")] ?: N.blue
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(storeIcon(it.optString("icon")), color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(it.optString("name"), color = N.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(it.optString("description"), color = N.sub, fontSize = 13.sp, maxLines = 2)
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
            AppIcon(storeIcon(it.optString("icon")), color, 110)
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
        if (inst) LinksCard(listOf("Manage it in Containers" to { app.go(Route.Containers) }))
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
        Text("These come from your system's package manager (apt). Installing or removing asks for your fingerprint; Nova won't remove what the server needs to run.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
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
