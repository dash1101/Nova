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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("App store", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = N.text,
            modifier = Modifier.statusBarsPadding().padding(start = 26.dp, top = 30.dp))
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
                                        val j = app.waitJob(app.stepUp("${if (inst) "Remove" else "Install"} ${p.optString("name")}", "POST",
                                            "/api/v1/programs/$pkg/${if (inst) "remove" else "install"}").getJSONObject("job"))
                                        app.toast(if (j.optString("state") == "done") "${p.optString("name")} ${if (inst) "removed" else "installed"}"
                                                  else "Failed: ${j.optJSONObject("result")?.optString("error")}")
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
                        job = "Downloading and starting…"
                        try {
                            val j = app.waitJob(app.stepUp("Install ${it.optString("name")}", "POST", "/api/v1/store/$id/install").getJSONObject("job"))
                            app.toast(if (j.optString("state") == "done") "${it.optString("name")} is ready" else "Failed: ${j.optJSONObject("result")?.optString("error")}")
                            reload()
                        } finally { job = null }
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
            try { val j = app.waitJob(app.stepUp("Uninstall ${it.optString("name")}", "POST", "/api/v1/store/$id/uninstall").getJSONObject("job"))
                  app.toast(if (j.optString("state") == "done") "Uninstalled" else "Failed: ${j.optJSONObject("result")?.optString("error")}"); reload()
            } finally { job = null } } }))
}
