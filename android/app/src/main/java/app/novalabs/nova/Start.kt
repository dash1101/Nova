package app.novalabs.nova

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

// The start page: a clock, one search box for Nova and the web, the server at a glance, your apps
// and your own bookmarks. Every section can be hidden (Settings → Appearance → Start page → edit).

/** Screens Nova can open on (Settings → Appearance → Open Nova on). */
val START_ROUTES: List<Pair<String, Pair<String, Route>>> = listOf(
    "home" to ("Home" to Route.Home), "start" to ("Start page" to Route.Start), "status" to ("Status" to Route.Status),
    "apps" to ("Apps" to Route.Apps), "inbox" to ("Inbox" to Route.Inbox), "containers" to ("Containers" to Route.Containers),
)

data class Engine(val id: String, val name: String, val url: String, val bang: String)
val ENGINES = listOf(
    Engine("google", "Google", "https://www.google.com/search?q=", "g"), Engine("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=", "d"),
    Engine("bing", "Bing", "https://www.bing.com/search?q=", "b"), Engine("brave", "Brave", "https://search.brave.com/search?q=", "br"),
    Engine("startpage", "Startpage", "https://www.startpage.com/do/search?q=", "sp"), Engine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=", "e"),
    Engine("youtube", "YouTube", "https://www.youtube.com/results?search_query=", "yt"), Engine("wikipedia", "Wikipedia", "https://en.wikipedia.org/w/index.php?search=", "w"),
    Engine("github", "GitHub", "https://github.com/search?q=", "gh"), Engine("reddit", "Reddit", "https://www.reddit.com/search/?q=", "r"),
)
private val START_SECTIONS = listOf("stats" to "Server at a glance", "apps" to "Your apps", "links" to "Bookmarks")

private fun startLinks(): List<Pair<String, String>> = runCatching {
    JSONArray(AppPrefs.startLinks).objs().map { it.optString("name") to it.optString("url") }.filter { it.second.startsWith("http") }
}.getOrDefault(emptyList())
private fun saveLinks(l: List<Pair<String, String>>) =
    AppPrefs.set("start_links", JSONArray(l.map { JSONObject().put("name", it.first).put("url", it.second) }).toString())

/** What typing [q] and pressing Go means: a web address, "yt cats" (a search on one engine), or a search. */
fun webTarget(q0: String): String {
    val q = q0.trim()
    if (Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(q)) return q
    if (Regex("^[\\w-]+(\\.[\\w-]+)+(:\\d+)?(/\\S*)?$").matches(q) && ' ' !in q) return "https://$q"
    val m = Regex("^(\\S+)\\s+(.+)$").find(q)
    val bang = m?.let { mm -> ENGINES.firstOrNull { it.bang == mm.groupValues[1].lowercase() } }
    val e = bang ?: ENGINES.firstOrNull { it.id == AppPrefs.startEngine } ?: ENGINES[1]
    return e.url + Uri.encode(if (bang != null) m!!.groupValues[2] else q)
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) { in 0..4 -> "Good night"; in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; else -> "Good evening" }

@OptIn(ExperimentalFoundationApi::class)
@Composable fun StartScreen(app: AppState) {
    val ctx = LocalContext.current
    fun browse(url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { app.toast("No browser to open that") } }
    var now by remember { mutableStateOf(java.time.LocalTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = java.time.LocalTime.now(); delay(10_000) } }
    var q by remember { mutableStateOf("") }
    val data = listOf("/api/v1/containers", "/api/v1/apps", "/api/v1/storage", "/api/v1/backups").associateWith { live(app, it).value }
    val hits = searchNova(app, q, data).take(5)
    var sugg by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(q) {
        if (q.isBlank()) { sugg = emptyList(); return@LaunchedEffect }
        delay(220)
        sugg = runCatching { app.api.get("/api/v1/suggest?q=" + Uri.encode(q.trim())).optJSONArray("suggestions").strs().take(5) }.getOrDefault(emptyList())
    }
    var editLink by remember { mutableStateOf<Int?>(null) }       // index, or -1 for a new one
    val hidden = AppPrefs.startHidden
    val engine = ENGINES.firstOrNull { it.id == AppPrefs.startEngine } ?: ENGINES[1]

    TabOrPage(app, "Start") {
        Box(Modifier.fillMaxWidth()) {
            IconButton({ app.go(Route.StartEdit) }, Modifier.align(Alignment.TopEnd).padding(end = 8.dp)) { Icon(Icons.Rounded.Edit, "Customize", tint = N.sub) }
            Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(now.format(java.time.format.DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm")),
                    color = N.text, fontSize = 64.sp, fontWeight = FontWeight.Light)
                Text(greeting(), color = N.sub, fontSize = 16.sp)
            }
        }
        // search
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp).clip(RoundedCornerShape(28.dp)).background(N.pill)
            .padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, tint = N.sub); Spacer(Modifier.width(12.dp))
            Box(Modifier.weight(1f)) {
                if (q.isEmpty()) Text("Search Nova or ${engine.name}", color = N.sub, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(q, { q = it.take(200) }, Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = TextStyle(color = N.text, fontSize = 17.sp), cursorBrush = SolidColor(N.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { if (q.isNotBlank()) browse(webTarget(q)) }))
            }
            if (q.isNotEmpty()) Icon(Icons.Rounded.Close, "Clear", tint = N.sub, modifier = Modifier.clickable { q = "" })
        }
        if (q.isNotBlank()) {
            Group {
                hits.forEachIndexed { i, h -> if (i > 0) RowDivider(); Row1(h.title, "${h.kind} · in Nova", false, h.icon, N.blue, onClick = { h.go(app) }) }
                if (hits.isNotEmpty()) RowDivider()
                Row1(q.trim(), "Search ${engine.name}", false, Icons.Rounded.TravelExplore, N.sub, onClick = { browse(webTarget(q)) })
                sugg.filter { !it.equals(q.trim(), true) }.forEach { s -> RowDivider(); Row1(s, null, false, Icons.Rounded.Search, N.sub, onClick = { browse(webTarget(s)) }) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ENGINES.filter { it.id != engine.id }.forEach { e ->
                    Text(e.name, color = N.text, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(N.pill)
                        .clickable { browse(e.url + Uri.encode(q.trim())) }.padding(horizontal = 14.dp, vertical = 9.dp))
                }
            }
            return@TabOrPage
        }
        if ("stats" !in hidden) { SectionLabel("Server at a glance"); HomeStats(app) }
        if ("apps" !in hidden) {
            val apps = live(app, "/api/v1/apps", 60_000).value?.optJSONArray("apps").objs().filter { !it.optBoolean("hidden") }
            if (apps.isNotEmpty()) {
                SectionLabel("Your apps")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    apps.forEach { a ->
                        Column(Modifier.width(72.dp).clip(RoundedCornerShape(16.dp)).clickable { openApp(app, a) }.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppIcon(app, a, 52.dp); Spacer(Modifier.height(6.dp))
                            Text(a.optString("name"), color = N.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if ("links" !in hidden) {
            val links = startLinks()
            SectionLabel("Bookmarks")
            Group {
                links.forEachIndexed { i, (n, u) ->
                    if (i > 0) RowDivider()
                    Box(Modifier.combinedClickable(onClick = { browse(u) }, onLongClick = { editLink = i })) {
                        Row1(n.ifEmpty { Uri.parse(u).host ?: u }, Uri.parse(u).host ?: u, true, Icons.Rounded.Link)
                    }
                }
                if (links.isNotEmpty()) RowDivider()
                Row1("Add a bookmark", if (links.isEmpty()) "Sites you open often · hold one to edit it" else null, true, Icons.Rounded.Add, onClick = { editLink = -1 })
            }
        }
    }
    editLink?.let { idx ->
        val links = startLinks(); val cur = links.getOrNull(idx)
        var name by remember(idx) { mutableStateOf(cur?.first ?: "") }
        var url by remember(idx) { mutableStateOf(cur?.second ?: "") }
        OneDialog({ editLink = null }, if (cur == null) "Add a bookmark" else "Edit bookmark", null,
            listOfNotNull(
                if (cur != null) DialogButton("Remove", N.red) { saveLinks(links.filterIndexed { i, _ -> i != idx }); editLink = null } else null,
                DialogButton("Save", N.blue, enabled = url.isNotBlank()) {
                    val u = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
                    if (Uri.parse(u).host.isNullOrBlank()) app.toast("That isn't a web address")
                    else { saveLinks(if (cur == null) links + (name.trim() to u) else links.mapIndexed { i, l -> if (i == idx) name.trim() to u else l }); editLink = null }
                })) {
            Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OneTextField(name, { name = it.take(40) }, "Name", Modifier.fillMaxWidth())
                OneTextField(url, { url = it.take(400).trim() }, "Address (https://…)", Modifier.fillMaxWidth())
            }
        }
    }
}

/** Start page → edit: sections, search engine. */
@Composable fun StartEditScreen(app: AppState) {
    Page("Customize start page", app::back) {
        SectionLabel("Show")
        Group {
            START_SECTIONS.forEachIndexed { i, (k, l) ->
                if (i > 0) RowDivider()
                SwitchRow(l, null, k !in AppPrefs.startHidden) { on ->
                    AppPrefs.set("start_hidden", (if (on) AppPrefs.startHidden - k else AppPrefs.startHidden + k).joinToString(","))
                }
            }
        }
        SectionLabel("Search the web with")
        Group {
            ENGINES.forEachIndexed { i, e ->
                if (i > 0) RowDivider()
                Row1(e.name, "Or type “${e.bang} …” to use it once", onClick = { AppPrefs.set("start_engine", e.id) }) { OneRadio(AppPrefs.startEngine == e.id) }
            }
        }
        Text("Typing a web address opens it. Suggestions come from DuckDuckGo, through your server.", color = N.sub, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        SectionLabel("Open Nova on")
        Group {
            START_ROUTES.forEachIndexed { i, (k, l) ->
                if (i > 0) RowDivider()
                Row1(l.first, null, onClick = { AppPrefs.set("start_route", k) }) { OneRadio(AppPrefs.startRoute == k) }
            }
        }
    }
}
