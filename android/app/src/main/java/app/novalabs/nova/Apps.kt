package app.novalabs.nova

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.http.SslError
import android.webkit.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

// Apps: the web apps on the server (Immich, Home Assistant, …) in a grid, opened inside Nova.
// Open apps stay alive (their page isn't reloaded) and sit in the navigation pill until you close them.

/** The apps open right now, in the order they were opened, with their pages kept alive. */
object AppSessions {
    class Session(val id: String, val app: JSONObject) { var web: WebView? = null; var icon by mutableStateOf<ImageBitmap?>(null); var title by mutableStateOf(app.optString("name")) }
    val open = mutableStateListOf<Session>()
    val icons = mutableStateMapOf<String, ImageBitmap?>()
    fun get(id: String) = open.firstOrNull { it.id == id }
    fun open(a: JSONObject): Session = get(a.optString("id")) ?: Session(a.optString("id"), a).also { s -> s.icon = icons[s.id]; open.add(s) }
    fun close(id: String) { get(id)?.let { s -> s.web?.apply { stopLoading(); loadUrl("about:blank"); destroy() }; open.remove(s) } }
    fun closeAll() { open.toList().forEach { close(it.id) } }
    private val idle = mutableMapOf<String, Session>()      // pinned apps that aren't open: just their icon in the dock
    /** What the dock shows: pinned apps first (open or not), then the other open apps. */
    fun dock(): List<Session> {
        val known = Cache["/api/v1/apps"]?.optJSONArray("apps").objs().associateBy { it.optString("id") }
        val pinned = AppPrefs.pinnedApps.mapNotNull { id -> get(id) ?: known[id]?.let { a -> idle.getOrPut(id) { Session(id, a).also { it.icon = icons[id] } }.also { it.icon = icons[id] ?: it.icon } } }
        return pinned + open.filter { it.id !in AppPrefs.pinnedApps }
    }
    fun isPinned(id: String) = id in AppPrefs.pinnedApps
    fun togglePin(id: String) = AppPrefs.set("pinned_apps", (if (isPinned(id)) AppPrefs.pinnedApps - id else (AppPrefs.pinnedApps + id).takeLast(4)).joinToString(","))
}

/** Tap on an app in the dock: switch to it, or open it if it's only pinned. */
fun dockTap(app: AppState, id: String) {
    if (AppSessions.get(id) != null) { app.tab(Route.AppFrame(id)); return }
    val a = Cache["/api/v1/apps"]?.optJSONArray("apps").objs().firstOrNull { it.optString("id") == id } ?: return
    if (appUrl(app, a) == null) { app.toast("Away from home this app needs its own link — hold it in Apps → Open it from anywhere"); return }
    AppSessions.open(a); app.tab(Route.AppFrame(id))
}

/** Where to open an app from here: your own link, or the server's address on this route + the app's port. */
fun appUrl(app: AppState, a: JSONObject): String? {
    val remote = app.api.via == "remote"
    if (remote && a.optString("remote_url").isNotEmpty()) return a.optString("remote_url")
    if (a.optString("url").isNotEmpty() && !remote) return a.optString("url")
    if (a.isNull("port") || a.optInt("port") == 0) return a.optString("url").ifEmpty { null }
    if (remote) return null                                                // app ports aren't on the Cloudflare tunnel
    val host = a.optString("host_ip").ifEmpty { android.net.Uri.parse(app.pairing.lanUrl).host ?: return null }
    return "${a.optString("scheme", "http")}://${if (':' in host) "[$host]" else host}:${a.optInt("port")}${a.optString("path", "/")}"
}

@Composable fun AppIcon(app: AppState, a: JSONObject, size: androidx.compose.ui.unit.Dp) {
    val id = a.optString("id")
    LaunchedEffect(id) {
        if (!AppSessions.icons.containsKey(id)) AppSessions.icons[id] = runCatching {
            val b = app.api.download("/api/v1/apps/$id/icon"); BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
        }.getOrNull()
        AppSessions.get(id)?.icon = AppSessions.icons[id]
    }
    val bmp = AppSessions.icons[id]
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(if (bmp == null) N.blue.copy(alpha = 0.18f) else N.card), contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize().padding(size * 0.12f))
        else Text(a.optString("name").take(1).uppercase(), color = N.blue, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable fun AppsScreen(app: AppState) {
    val live = live(app, "/api/v1/apps", 30_000)
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    var addingWeb by remember { mutableStateOf(false) }
    var cmdEdit by remember { mutableStateOf<JSONObject?>(null) }
    var runAsk by remember { mutableStateOf<JSONObject?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    val all = live.value?.optJSONArray("apps").objs()
    val shown = all.filter { showHidden || !it.optBoolean("hidden") }
    Page("Apps", app::back, listOf(TopAction(Icons.Rounded.Add, "Add an app") { if (app.isAdmin) adding = true else app.toast("View-only access") })) {
        Text("The web apps on ${serverName(app)}. Open one and it stays in the navigation pill until you close it. Hold an app to rename or hide it.",
            color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        if (live.value == null) Text("Looking for apps…", color = N.sub, modifier = Modifier.padding(30.dp))
        else if (shown.isEmpty()) Group { Row1("No web apps found", "Install one from the Store, or add a link with +", false, Icons.Rounded.Apps, N.blue) }
        val cols = if (LocalWide.current) 6 else 4
        if (app.isAdmin) {
            SectionLabel("Built in")
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
                listOf(Triple("Files", Icons.Rounded.Folder, N.blue) to { app.go(Route.Files()) }, Triple("Terminal", Icons.Rounded.Terminal, N.green) to { app.go(if (SshSession.active) Route.SshTerm else Route.Ssh) })
                    .forEach { (t, go) -> Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { go() }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(t.third.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(t.second, null, tint = t.third, modifier = Modifier.size(30.dp)) }
                        Text(t.first, color = N.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) } }
                repeat(cols - 2) { Spacer(Modifier.weight(1f)) }
            }
            SectionLabel("On your server")
        }
        val cells: List<JSONObject?> = shown + if (app.isAdmin && live.value != null) listOf(null) else emptyList()     // null: the Add tile
        cells.chunked(cols).forEach { rowApps ->
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp)) {
                rowApps.forEach { a ->
                    if (a == null) Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).clickable { adding = true }.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).border(1.5.dp, N.sub.copy(alpha = 0.5f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Add, null, tint = N.blue, modifier = Modifier.size(28.dp)) }
                        Text("Add", color = N.text, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                    } else Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).combinedClickable(onLongClick = { if (app.isAdmin) editing = a }) { if (a.optString("kind") == "command") runAsk = a else openApp(app, a) }
                        .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box { AppIcon(app, a, 60.dp); if (AppSessions.get(a.optString("id")) != null) Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(N.green)) }
                        Text(a.optString("name"), color = if (a.optBoolean("hidden")) N.sub else N.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))
                    }
                }
                repeat(cols - rowApps.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (all.any { it.optBoolean("hidden") }) Group { SwitchRow("Show hidden apps", null, showHidden) { showHidden = it } }
        if (app.api.via == "remote") Text("You're away from home: apps open through their own remote link if you've set one (hold an app → Remote link). Others need your home network or Tailscale.",
            color = N.amber, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
    }
    editing?.let { a -> if (a.optString("kind") == "command") CommandAppDialog(app, a) { r -> editing = null; r?.let { live.value = it } }
        else AppEditDialog(app, a, onDone = { r -> editing = null; r?.let { live.value = it } }) }
    if (adding) AddAppChoice(onWeb = { adding = false; addingWeb = true }, onCommand = { adding = false; cmdEdit = JSONObject() }, onDismiss = { adding = false })
    if (addingWeb) AppEditDialog(app, null, onDone = { r -> addingWeb = false; r?.let { live.value = it } })
    cmdEdit?.let { a -> CommandAppDialog(app, a.takeIf { it.has("id") }) { r -> cmdEdit = null; r?.let { live.value = it } } }
    runAsk?.let { a ->
        fun run() = app.act { val r = app.api.post("/api/v1/apps/${a.optString("id")}/run"); app.go(Route.Task(r.optString("task"))) }
        if (!a.optBoolean("confirm")) { LaunchedEffect(a) { runAsk = null; run() } }
        else OneDialog({ runAsk = null }, "Run ${a.optString("name")}?", a.optString("command").take(200), listOf(DialogButton("Cancel") { runAsk = null }, DialogButton("Run", N.blue) { runAsk = null; run() }))
    }
}

fun openApp(app: AppState, a: JSONObject) {
    if (appUrl(app, a) == null) { app.toast("Away from home this app needs its own link — hold it → Open it from anywhere"); return }
    AppSessions.open(a); app.go(Route.AppFrame(a.optString("id")))
}

@Composable private fun AppEditDialog(app: AppState, a: JSONObject?, onDone: (JSONObject?) -> Unit) {
    var anywhere by remember { mutableStateOf(false) }
    if (anywhere && a != null) { AppRemoteDialog(app, a) { anywhere = false }; return }
    var name by remember { mutableStateOf(a?.optString("name") ?: "") }
    var url by remember { mutableStateOf(a?.optString("url") ?: "") }
    var remote by remember { mutableStateOf(a?.optString("remote_url") ?: "") }
    var icon by remember { mutableStateOf(a?.optString("slug") ?: "") }
    fun save(extra: JSONObject.() -> Unit = {}) = app.act {
        val body = JSONObject().put("name", name.trim()).put("url", url.trim()).put("remote_url", remote.trim()).put("icon", icon.trim().lowercase()).apply { a?.let { put("id", it.optString("id")) }; extra() }
        val r = app.api.post("/api/v1/apps", body); AppSessions.icons.remove(r.optString("id")); onDone(r)
    }
    OneDialog({ onDone(null) }, if (a == null) "Add an app" else a.optString("name"),
        if (a == null) "Any web page on your network: a link like http://192.168.1.20:8096" else "Change how it shows in Apps. Leave the link empty to use the one Nova found.",
        listOfNotNull(
            if (a != null && a.optString("source") == "custom") DialogButton("Delete", N.red) { app.act { app.api.delete("/api/v1/apps/${a.optString("id")}"); onDone(app.api.get("/api/v1/apps")) } } else null,
            if (a != null && a.optString("source") != "custom") DialogButton(if (a.optBoolean("hidden")) "Show" else "Hide") { save { put("hidden", !a.optBoolean("hidden")) } } else null,
            if (a != null) DialogButton(if (AppSessions.isPinned(a.optString("id"))) "Unpin" else "Pin to dock") {
                AppSessions.togglePin(a.optString("id")); app.toast(if (AppSessions.isPinned(a.optString("id"))) "Pinned — it stays in the navigation pill" else "Unpinned"); onDone(null) } else null,
            DialogButton("Save", N.blue, enabled = name.isNotBlank() && (a != null || url.startsWith("http"))) { save() })) {
        Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OneTextField(name, { name = it.take(40) }, "Name", Modifier.fillMaxWidth())
            OneTextField(url, { url = it.trim().take(300) }, if (a == null) "Link (http://…)" else "Link at home (optional)", Modifier.fillMaxWidth())
            OneTextField(remote, { remote = it.trim().take(300) }, "Remote link, e.g. https://photos.example.com (optional)", Modifier.fillMaxWidth())
            OneTextField(icon, { icon = it.trim().lowercase().take(60) }, "Icon name from dashboard-icons, e.g. jellyfin", Modifier.fillMaxWidth())
            if (a != null) Text("Open it from anywhere…", color = N.blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { anywhere = true }.padding(vertical = 8.dp))
        }
    }
}

/** Opening an app away from home: its own address on your Cloudflare tunnel, behind Cloudflare Access.
 *  Shows what to type in Cloudflare, and checks that the remote link really asks for a login first. */
@Composable private fun AppRemoteDialog(app: AppState, a: JSONObject, onDone: () -> Unit) {
    val id = a.optString("id")
    var g by remember { mutableStateOf<JSONObject?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf(a.optString("remote_url")) }
    LaunchedEffect(tick) { g = runCatching { app.api.get("/api/v1/apps/$id/remote") }.getOrNull() }
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    @Composable fun copyRow(label: String, v: String) {
        if (v.isEmpty()) return
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(N.pill).clickable { clip.setText(androidx.compose.ui.text.AnnotatedString(v)); app.toast("Copied") }
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(label, color = N.sub, fontSize = 12.sp); Text(v, color = N.text, fontSize = 14.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
            Icon(Icons.Rounded.ContentCopy, "Copy", tint = N.blue, modifier = Modifier.size(18.dp))
        }
    }
    val chk = g?.optJSONObject("check")
    val host = g?.optString("remote_url")?.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it).host } ?: g?.optString("suggested").orEmpty()
    OneDialog(onDone, "${a.optString("name")} from anywhere", null, listOfNotNull(
        DialogButton("Close") { onDone() },
        if (g?.optString("remote_url")?.isNotEmpty() == true) DialogButton("Check again") { tick++ } else null,
        if (editing) DialogButton("Save and check", N.blue, enabled = link.startsWith("https://")) {
            app.act { app.api.post("/api/v1/apps", JSONObject().put("id", id).put("remote_url", link.trim())); Cache.put("/api/v1/apps", app.api.get("/api/v1/apps")); editing = false; tick++ } }
        else DialogButton(if (g?.optString("remote_url")?.isNotEmpty() == true) "Change link" else "Set the link", N.blue) { if (link.isEmpty() && host.isNotEmpty()) link = "https://$host"; editing = true })) {
        Column(Modifier.padding(horizontal = 22.dp).heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (chk != null) {
                val st = chk.optString("state"); val col = if (st == "protected") N.green else N.amber
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = 0.14f)).padding(12.dp)) {
                    Icon(if (st == "protected") Icons.Rounded.Shield else Icons.Rounded.Warning, null, tint = col, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
                    Text(chk.optString("message"), color = N.text, fontSize = 14.sp)
                }
            }
            if (editing) OneTextField(link, { link = it.trim().take(300) }, "https://photos.example.com", Modifier.fillMaxWidth())
            else if (g == null) Text("Loading…", color = N.sub)
            else {
                Text("Your server already has a Cloudflare tunnel. Give this app its own address on it, protected by the same Cloudflare login as Nova — nothing reaches the app until you've signed in.", color = N.sub, fontSize = 14.sp)
                Text("1. Cloudflare Zero Trust → Networks → Tunnels → your tunnel → Public hostnames → Add a public hostname.", color = N.text, fontSize = 14.sp)
                Text("2. Use this address:", color = N.text, fontSize = 14.sp); copyRow("Subdomain + domain", host)
                Text("3. Service:", color = N.text, fontSize = 14.sp); copyRow("Service", g?.optString("service").orEmpty())
                if (g?.optString("service")?.startsWith("https") == true) Text("It uses its own certificate: under TLS, turn on No TLS Verify.", color = N.sub, fontSize = 13.sp)
                Text("4. Access → Applications: add the same address to the application that protects Nova (or a wildcard like *.${g?.optString("domain")?.ifEmpty { "yourdomain.com" }}), with your Allow policy.", color = N.text, fontSize = 14.sp)
                Text("5. Set it as the app's remote link — Nova checks that Cloudflare asks for a login first.", color = N.text, fontSize = 14.sp)
            }
        }
    }
}

/** One open app: a rounded frame below a bar with its name, the server's status and the controls. */
@SuppressLint("SetJavaScriptEnabled")
@Composable fun AppFrameScreen(app: AppState, id: String) {
    val s = AppSessions.get(id)
    if (s == null) { LaunchedEffect(Unit) { app.back() }; return }
    val url = remember(id) { appUrl(app, s.app) }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var sslAsk by remember { mutableStateOf<SslErrorHandler?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val status = app.overview?.optJSONObject("status")?.optString("level") ?: "ok"
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = if (LocalWide.current) 12.dp else 96.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", 1f, { if (s.web?.canGoBack() == true) s.web?.goBack() else app.back() })
            Spacer(Modifier.width(8.dp))
            AppIcon(app, s.app, 30.dp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, color = N.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(levelColor(status, N))); Spacer(Modifier.width(5.dp))
                    Text("${serverName(app)} · ${if (status == "ok") "all good" else status}", color = N.sub, fontSize = 12.sp, maxLines = 1)
                }
            }
            CircleButton(Icons.Rounded.Refresh, "Reload", 1f, { failed = null; s.web?.reload() })
            Spacer(Modifier.width(6.dp))
            CircleButton(Icons.Rounded.Close, "Close", 1f, { AppSessions.close(id); app.back() })
        }
        if (progress in 1..99) ProgressBar(progress / 100f)
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp).clip(RoundedCornerShape(24.dp)).background(N.card)) {
            if (url == null) Text("This app isn't reachable from here.", color = N.sub, modifier = Modifier.align(Alignment.Center))
            else AndroidView(factory = {
                s.web?.also { (it.parent as? android.view.ViewGroup)?.removeView(it) } ?: WebView(ctx).apply {
                    settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                    settings.mediaPlaybackRequiresUserGesture = true; settings.setSupportZoom(true); settings.builtInZoomControls = true; settings.displayZoomControls = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    CookieManager.getInstance().setAcceptCookie(true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(v: WebView, p: Int) { progress = p }
                        override fun onReceivedTitle(v: WebView, t: String?) { if (!t.isNullOrBlank() && !t.startsWith("http")) s.title = t }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun doUpdateVisitedHistory(v: WebView, u: String?, r: Boolean) { canBack = v.canGoBack() }
                        override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
                            // Self-signed certificates are common on home servers: ask, but only for the server's own address.
                            val host = android.net.Uri.parse(e.url).host
                            if (host != null && host == android.net.Uri.parse(url).host) sslAsk = h else h.cancel()
                        }
                        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) { if (r.isForMainFrame) failed = e.description?.toString() }
                    }
                    loadUrl(url); s.web = this
                }
            }, modifier = Modifier.fillMaxSize())
            failed?.let { Column(Modifier.align(Alignment.Center).padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Couldn't open ${s.app.optString("name")}", color = N.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("$it\n$url", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(12.dp)); PillButton("Try again") { failed = null; s.web?.reload() }
            } }
        }
    }
    androidx.activity.compose.BackHandler(enabled = canBack) { s.web?.goBack() }
    sslAsk?.let { h ->
        OneDialog({ h.cancel(); sslAsk = null }, "Open with the server's own certificate?",
            "${s.app.optString("name")} uses a certificate your phone doesn't know (common for apps on a home server). Only continue if this is your server.",
            listOf(DialogButton("Cancel") { h.cancel(); sslAsk = null }, DialogButton("Open", N.blue) { h.proceed(); sslAsk = null }))
    }
}


@Composable private fun AddAppChoice(onWeb: () -> Unit, onCommand: () -> Unit, onDismiss: () -> Unit) =
    OneDialog(onDismiss, "Add an app", "A web page on your network opens like any other app. A command or script runs on the server with one tap and shows you its output.",
        listOf(DialogButton("Cancel", onClick = onDismiss), DialogButton("Command or script", onClick = onCommand), DialogButton("Web page", N.blue, onClick = onWeb)))

/** A command app: what it runs (as your normal account on the server). Saving asks for your fingerprint. */
@Composable private fun CommandAppDialog(app: AppState, a: JSONObject?, onDone: (JSONObject?) -> Unit) {
    var name by remember { mutableStateOf(a?.optString("name") ?: "") }
    var cmd by remember { mutableStateOf(a?.optString("command") ?: "") }
    var confirm by remember { mutableStateOf(a?.optBoolean("confirm") ?: false) }
    var timeout by remember { mutableStateOf((a?.optInt("timeout", 600) ?: 600).toString()) }
    OneDialog({ onDone(null) }, a?.optString("name") ?: "New command app", "It runs on the server as your normal account, and you see its output.", listOfNotNull(
        if (a != null) DialogButton("Delete", N.red) { app.act { app.api.delete("/api/v1/apps/${a.optString("id")}"); onDone(app.api.get("/api/v1/apps")) } } else null,
        DialogButton("Cancel") { onDone(null) },
        DialogButton("Save", N.blue, enabled = name.isNotBlank() && cmd.isNotBlank()) { app.act {
            val r = app.stepUp("Save the command app ${name.trim()}", "POST", "/api/v1/apps/command", JSONObject().put("name", name.trim()).put("command", cmd)
                .put("confirm", confirm).put("timeout", timeout.toIntOrNull() ?: 600).apply { a?.let { put("id", it.optString("id")) } })
            onDone(JSONObject().put("apps", r.optJSONArray("apps"))) } })) {
        Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OneTextField(name, { name = it.take(40) }, "Name, e.g. Clean up Docker", Modifier.fillMaxWidth())
            OneTextField(cmd, { cmd = it.take(8000) }, "docker system prune -f", Modifier.fillMaxWidth(), mono = true, singleLine = false)
            SwitchRow("Ask before running", null, confirm) { confirm = it }
            OneTextField(timeout, { timeout = it.filter(Char::isDigit).take(4) }, "Time limit (seconds)", Modifier.fillMaxWidth())
        }
    }
}
