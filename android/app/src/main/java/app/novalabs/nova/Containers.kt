package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

fun localTime(utc: String): String = runCatching {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(f.parse(utc.take(19))!!)
}.getOrDefault(utc)

@Composable fun stateColor(state: String, health: String = "") = when {
    health == "unhealthy" -> N.amber; state == "running" -> N.green; state in listOf("restarting", "created") -> N.amber; else -> N.sub
}

@Composable fun ContainersScreen(app: AppState) {
    val data by live(app, "/api/v1/containers", 10_000)
    val list = data?.optJSONArray("containers")
    val items = list?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    Page("Containers", app::back, listOf(TopAction(Icons.Rounded.AddCircleOutline, "Install more") { app.tab(Route.Store) })) {
        Row(Modifier.padding(start = 30.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val up = items.count { it.optString("state") == "running" }
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (up == items.size) N.green else N.amber))
            Spacer(Modifier.width(8.dp))
            Text(if (list == null) "Loading…" else "$up of ${items.size} running", color = N.sub, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        // Stacks with several containers get their own card; one-container apps share an "Apps" card.
        val stacks = items.groupBy { it.optString("stack") }
        val singles = stacks.filter { it.value.size == 1 }.values.flatten()
        (stacks.filter { it.value.size > 1 }.toList() + (if (singles.isNotEmpty()) listOf("" to singles) else emptyList())).forEach { (stack, cs) ->
            SectionLabel(if (stack.isEmpty()) "Apps" else stack.replaceFirstChar { it.uppercase() } + if (cs.any { it.optBoolean("store") }) "  ·  from the store" else "")
            Group {
                cs.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    val st = c.optString("state"); val h = c.optString("health")
                    Row1(c.optString("name"), "${c.optString("image").substringAfterLast('/')} · ${if (h.isNotEmpty()) "$st, $h" else st}",
                        onClick = { app.go(Route.Container(c.optString("name"))) }) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(st, h)))
                    }
                }
            }
        }
        LinksCard(listOf("Install more from the App store" to { app.tab(Route.Store) }))
    }
}

@Composable fun ContainerScreen(app: AppState, name: String) {
    val cs = live(app, "/api/v1/containers/$name", 8_000)
    var c by cs
    var job by remember { mutableStateOf<String?>(null) }
    var policyDialog by remember { mutableStateOf(false) }
    suspend fun load() { runCatching { c = app.api.get("/api/v1/containers/$name") } }
    val state = c?.optString("state") ?: ""
    val running = state == "running"

    fun action(a: String) = app.act {
        job = "${a.replaceFirstChar { it.uppercase() }}ing…"
        try {
            if (a == "start") app.api.post("/api/v1/containers/$name/start")
            else app.stepUp("${a.replaceFirstChar { it.uppercase() }} $name", "POST", "/api/v1/containers/$name/$a")
            app.toast("$name: $a done")
        } finally { job = null; load() }
    }

    Page(name, app::back) {
        // hero
        Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(120.dp).clip(RoundedCornerShape(36.dp)).background(N.card), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ViewInAr, null, tint = N.blue, modifier = Modifier.size(60.dp))
                Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(20.dp).clip(CircleShape)
                    .background(stateColor(state, c?.optString("health") ?: "")))
            }
            Spacer(Modifier.height(12.dp))
            Text(job ?: (state.replaceFirstChar { it.uppercase() } + (c?.optString("health")?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")),
                color = N.sub, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(c?.optString("image") ?: "", color = N.sub, fontSize = 13.sp)
        }
        if (app.isAdmin) PillBar(listOf(
            if (running) PillItem(Icons.Rounded.Stop, "Stop") { action("stop") } else PillItem(Icons.Rounded.PlayArrow, "Start") { action("start") },
            PillItem(Icons.Rounded.RestartAlt, "Restart") { action("restart") },
            PillItem(Icons.Rounded.SystemUpdate, "Update") {
                app.act { job = "Updating…"
                    val j = app.waitJob(app.api.post("/api/v1/containers/$name/update").getJSONObject("job")) { job = "Updating…" }
                    job = null; app.toast(if (j.optString("state") == "done") "$name is up to date" else "Update failed: ${j.optJSONObject("result")?.optString("error")}")
                    load() } },
            PillItem(Icons.Rounded.Terminal, "Shell") { if (running) app.go(Route.Shell(name)) else app.toast("Start it first") },
        ))
        Spacer(Modifier.height(10.dp))
        c?.let { c ->
            SectionLabel("Live")
            Group {
                Row1("CPU", c.optString("cpu").ifEmpty { "—" }); RowDivider()
                Row1("Memory", c.optString("mem").ifEmpty { "—" }); RowDivider()
                Row1("Network in / out", c.optString("net").ifEmpty { "—" }); RowDivider()
                Row1("Running since", "${localTime(c.optString("started"))} · restarted ${c.optInt("restarts")} times")
            }
            Group {
                Row1("Logs", "See what it's been saying", true, Icons.AutoMirrored.Rounded.Article, onClick = { app.go(Route.Logs(name)) })
                if (app.isAdmin) RowDivider()
                if (app.isAdmin) Row1("Terminal", "Run commands inside it", true, Icons.Rounded.Terminal, onClick = { if (running) app.go(Route.Shell(name)) })
            }
            SectionLabel("Configuration")
            Group {
                Row1("Restart policy", mapOf("unless-stopped" to "Always, unless you stop it", "always" to "Always",
                    "on-failure" to "Only if it crashes", "no" to "Never")[c.optString("restart_policy")] ?: c.optString("restart_policy"),
                    true, onClick = { if (app.isAdmin) policyDialog = true })
                RowDivider()
                Row1("Stack", "${c.optString("stack")} · ${c.optString("compose_dir")}")
                if (c.optBoolean("privileged")) { RowDivider(); Row1("Privileged", "Has full access to the host", false) { Icon(Icons.Rounded.Warning, null, tint = N.amber) } }
            }
            val ports = c.optJSONArray("ports")
            if (ports != null && ports.length() > 0) { SectionLabel("Ports"); Group {
                for (i in 0 until ports.length()) { if (i > 0) RowDivider(); Row1(ports.getString(i)) } } }
            val mounts = c.optJSONArray("mounts")
            if (mounts != null && mounts.length() > 0) { SectionLabel("Folders"); Group {
                for (i in 0 until mounts.length()) { val m = mounts.getJSONObject(i); if (i > 0) RowDivider()
                    Row1(m.optString("target"), m.optString("source") + if (!m.optBoolean("rw", true)) " · read-only" else "") } } }
            val env = c.optJSONArray("env")
            if (env != null && env.length() > 0) { SectionLabel("Environment"); Group {
                for (i in 0 until env.length()) { val e = env.getJSONObject(i); if (i > 0) RowDivider()
                    Row1(e.optString("key"), e.optString("value")) } } }
        }
    }
    if (policyDialog) {
        val opts = listOf("unless-stopped" to "Always, unless you stop it", "always" to "Always", "on-failure" to "Only if it crashes", "no" to "Never")
        OneDialog({ policyDialog = false }, "Restart automatically", buttons = listOf(DialogButton("Cancel") { policyDialog = false })) {
            opts.forEach { (k, label) -> DialogChoice(label, null, c?.optString("restart_policy") == k) { policyDialog = false
                val before = c; c = JSONObject(c.toString()).put("restart_policy", k)       // show it at once
                app.act("Saved") { try { app.stepUp("Change restart policy", "POST", "/api/v1/containers/$name/policy", JSONObject().put("policy", k)) }
                    catch (e: Exception) { c = before; throw e }; load() } } }
        }
    }
}

@Composable fun LogsScreen(app: AppState, name: String) {
    var lines by remember { mutableStateOf(Cache["/api/v1/containers/$name/logs?lines=400"]?.optJSONArray("lines")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()) }
    var follow by remember { mutableStateOf(true) }
    val list = rememberLazyListState()
    LaunchedEffect(follow) {
        while (true) {
            runCatching { val a = app.api.get("/api/v1/containers/$name/logs?lines=400").optJSONArray("lines")
                lines = (0 until (a?.length() ?: 0)).map { a!!.getString(it) } }
            if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex)
            if (!follow) break; delay(4000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        FixedTopBar("Logs · $name", app::back, listOf(TopAction(if (follow) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Follow") { follow = !follow }))
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0B0B0D))
            .padding(12.dp), state = list) {
            items(lines) { l ->
                val ts = l.substringBefore(' ').let { if (it.length > 19) it.substring(11, 19) else "" }
                val msg = if (ts.isNotEmpty()) l.substringAfter(' ') else l
                val color = when { Regex("(?i)error|fatal|panic|exception").containsMatchIn(msg) -> Color(0xFFFF6B6B)
                    Regex("(?i)warn").containsMatchIn(msg) -> Color(0xFFFFC14D); else -> Color(0xFFD7D7DB) }
                Row { Text(ts, color = Color(0xFF6E6E76), fontFamily = Mono, fontSize = 11.sp); Spacer(Modifier.width(8.dp))
                    Text(msg, color = color, fontFamily = Mono, fontSize = 11.sp) }
            }
        }
    }
}

@Composable fun ShellScreen(app: AppState, name: String) {
    var sid by remember { mutableStateOf<String?>(null) }
    var output by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    var alive by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    val scroll = rememberScrollState()
    LaunchedEffect(name) {
        try { sid = app.stepUp("Open a shell in $name", "POST", "/api/v1/containers/$name/shell").getString("session") }
        catch (e: Exception) { app.toast(e.message ?: "Couldn't open a shell"); app.back(); return@LaunchedEffect }
        while (alive) {
            runCatching { val r = app.api.get("/api/v1/shell/$sid?offset=$offset")
                val d = r.optString("data"); if (d.isNotEmpty()) output = (output + d.replace(Regex("\u001B\\[[0-9;?]*[A-Za-z]"), "")).takeLast(200_000)
                offset = r.optInt("offset"); alive = r.optBoolean("alive", true) }
            delay(350)
        }
    }
    DisposableEffect(Unit) { onDispose { sid?.let { s -> app.scope.launchSafe { app.api.delete("/api/v1/shell/$s") } } } }
    LaunchedEffect(output) { scroll.animateScrollTo(scroll.maxValue) }
    fun send(text: String) { val s = sid ?: return; app.act { app.api.post("/api/v1/shell/$s", JSONObject().put("input", text)) } }

    Column(Modifier.fillMaxSize().imePadding()) {
        FixedTopBar("Terminal · $name", app::back)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0B0B0D))) {
            Text(if (sid == null) "Connecting…" else output.ifEmpty { "Connected. Type a command below." } + if (!alive) "\n[session ended]" else "",
                color = Color(0xFFE6E6EA), fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Ctrl-C" to "\u0003", "Tab" to "\t", "↑" to "UP", "Ctrl-D" to "\u0004", "clear" to "CLEAR", "ls" to "ls -la\n", "df -h" to "df -h\n").forEach { (label, v) ->
                Chip(label, mono = true) { when (v) { "UP" -> history.lastOrNull()?.let { input = it }; "CLEAR" -> output = ""; else -> send(v) } }
            }
        }
        Row(Modifier.padding(start = 12.dp, end = 6.dp, bottom = 12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            OneTextField(input, { input = it }, "command", Modifier.weight(1f), mono = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { history.add(input); send(input + "\n"); input = "" }))
            IconButton({ history.add(input); send(input + "\n"); input = "" }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = N.blue) }
        }
    }
}

fun kotlinx.coroutines.CoroutineScope.launchSafe(block: suspend () -> Unit) =
    launch { runCatching { block() } }
