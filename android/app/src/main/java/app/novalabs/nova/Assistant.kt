package app.novalabs.nova

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The assistant (Labs): ask in plain words, see the plan, then Nova does it one step at a time — same as Nova web.
 *  The server only plans; each step is an ordinary call this phone signs (risky ones with your fingerprint). Stop halts
 *  before the next step; something the server already started (an install, freeing space) finishes on its own. */
object Assistant {
    var on by mutableStateOf<Boolean?>(null)                // Labs → Nova assistant (null: not known yet)
    var pending by mutableStateOf("")                        // a request handed over by Search
    suspend fun refresh(app: AppState) { on = runCatching { app.api.get("/api/v1/labs").optJSONObject("labs")?.optBoolean("assistant") == true }.getOrNull() ?: on }
    private val STARTS = Regex("^(?:hey|please|can you|could you|turn|set|apply|make|free|clean|disable|enable|lower|raise|dim|install|schedule|restart|reboot|stop|start|run|back ?up|open|show|switch|erase|format|wipe|change)\\b", RegexOption.IGNORE_CASE)
    fun looksLikeRequest(q: String) = q.trim().split(Regex("\\s+")).size >= 3 && STARTS.containsMatchIn(q.trim())
    /** The only calls a plan may make (checked here before anything is signed). */
    private val ALLOWED = listOf("POST" to Regex("^/api/v1/fan$"), "POST" to Regex("^/api/v1/actions/(free-ram|backup)$"), "POST" to Regex("^/api/v1/store/[a-z0-9-]{1,40}/install$"),
        "POST" to Regex("^/api/v1/containers/[A-Za-z0-9][A-Za-z0-9_.-]{0,62}/(start|stop|restart)$"), "POST" to Regex("^/api/v1/power/(schedule|reboot|poweroff)$"), "POST" to Regex("^/api/v1/freeup$"))
    fun allowed(c: JSONObject?) = c != null && ALLOWED.any { (m, re) -> m == c.optString("method") && re.matches(c.optString("path")) }
    /** Steps that need the fingerprint (the server says so too; asking first saves a round trip). */
    fun needsStepUp(path: String) = Regex("^/api/v1/(power/|store/|freeup$|containers/.+/(stop|restart)$)").containsMatchIn(path)
}

/** The orb: soft lights in the accent turning round each other and a bright core — faster while Nova thinks. */
@Composable fun AssistantOrb(thinking: Boolean, size: androidx.compose.ui.unit.Dp = 96.dp) {
    val acc = N.blue; val still = reduceMotion()
    val tr = rememberInfiniteTransition(label = "orb")
    val spin by tr.animateFloat(0f, 360f, infiniteRepeatable(tween(if (thinking) 2400 else 9000, easing = LinearEasing)), label = "spin")
    val br by tr.animateFloat(.9f, 1.15f, infiniteRepeatable(tween(if (thinking) 550 else 1600), RepeatMode.Reverse), label = "breathe")
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2; val c = center
        val a = if (still) 0f else spin; val b = if (still) 1f else br
        drawCircle(Brush.radialGradient(listOf(acc.copy(alpha = .35f), Color.Transparent), c, r), r)
        listOf(acc to 0f, lerp(acc, Color(0xFFFF4FD8), .5f) to 120f, lerp(acc, Color(0xFF3EE6FF), .45f) to 240f).forEachIndexed { i, (col, off) ->
            val ang = Math.toRadians((a * (if (i == 1) -1.3f else 1f + i * .2f) + off).toDouble())
            val p = Offset(c.x + (r * .28f * kotlin.math.cos(ang)).toFloat(), c.y + (r * .28f * kotlin.math.sin(ang)).toFloat())
            drawCircle(Brush.radialGradient(listOf(col.copy(alpha = .85f), Color.Transparent), p, r * .62f), r * .62f, p, blendMode = BlendMode.Screen)
        }
        drawCircle(Brush.radialGradient(listOf(Color.White, lerp(acc, Color.White, .6f), Color.Transparent), c, r * .34f * b), r * .34f * b)
    }
}

/** The box you type into, with a beam of light running round its edge. */
@Composable private fun BeamBox(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp * roundK())
    Box(Modifier.padding(horizontal = Space.gutter, vertical = 8.dp).fillMaxWidth().clip(shape).background(N.card).border(1.dp, N.divider, shape)
        .lumenBeam(shape, lerp(N.blue, Color.White, .3f), 3500, 2.dp)) { content() }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun AssistantScreen(app: AppState) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(Assistant.pending.also { Assistant.pending = "" }) }
    var plan by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }; var stop by remember { mutableStateOf(false) }
    var cur by remember { mutableIntStateOf(-1) }; var summary by remember { mutableStateOf("") }; var err by remember { mutableStateOf("") }
    val results = remember { mutableStateMapOf<String, Pair<Boolean, String>>() }
    val choice = remember { mutableStateMapOf<String, Int>() }
    LaunchedEffect(Unit) { Assistant.refresh(app) }
    fun steps(): List<JSONObject> = plan?.optJSONArray("steps").objs().map { s ->
        if (s.optString("kind") == "ask" && choice[s.optString("id")] != null) JSONObject(s.optJSONArray("options")!!.getJSONObject(choice[s.optString("id")]!!).getJSONObject("step").toString())
            .put("id", s.optString("id")).put("asked", true) else s }
    fun planIt(t: String) { val q = t.trim(); if (q.isEmpty() || busy) return
        text = q; busy = true; plan = null; results.clear(); choice.clear(); summary = ""; err = ""; cur = -1
        scope.launch { try { plan = app.api.post("/api/v1/assistant/plan", JSONObject().put("text", q)) } catch (e: Exception) { err = e.message ?: "That didn't work" }; busy = false } }
    LaunchedEffect(Unit) { if (text.isNotBlank()) planIt(text) }

    data class Done(val ok: Boolean, val msg: String, val freed: Long = 0, val ramMb: Long = 0, val later: Boolean = false)
    suspend fun runStep(s: JSONObject): Done {
        val kind = s.optString("kind"); val done = s.optString("done")
        when (kind) {
            "ui" -> { when (s.optString("pref")) { "theme" -> AppPrefs.set("theme", s.optString("value")); "style" -> AppPrefs.set("style", s.optString("value")); "reduce_motion" -> AppPrefs.set("reduce_motion", s.optBoolean("value")) }
                return Done(true, done) }
            "nav", "partition" -> return Done(true, "Opening it at the end", later = true)
        }
        val c = s.optJSONObject("call"); if (!Assistant.allowed(c)) return Done(false, "Not something the assistant may do")
        val path = c!!.optString("path"); val body = c.optJSONObject("body") ?: JSONObject()
        suspend fun send(p: String, b: JSONObject) = if (Assistant.needsStepUp(p)) app.stepUp(s.optString("say"), "POST", p, b) else app.api.post(p, b)
        if (kind == "freeup") {
            val scan = app.api.get("/api/v1/freeup")
            val items = scan.optJSONArray("items").objs().filter { it.optBoolean("default") }.map { it.optString("id") }
            if (items.isEmpty()) return Done(true, "There was nothing safe to remove")
            val r = send(path, JSONObject().put("items", JSONArray(items)))
            val t = r.optString("task").takeIf { it.isNotEmpty() }?.let { app.waitTask(it) } ?: r
            if (t.optString("state") == "failed") return Done(false, t.optString("error", "It didn't work"))
            val freed = t.optJSONObject("result")?.optLong("freed") ?: 0L
            return Done(true, done.replace("{freed}", fmtBytes(freed)), freed = freed)
        }
        val r = send(path, body)
        r.optString("task").takeIf { it.isNotEmpty() }?.let { val t = app.waitTask(it); if (t.optString("state") == "failed") return Done(false, t.optString("error", "It didn't work")) }
        val mb = r.optLong("freed_mb", -1)
        return Done(true, done.replace("{freed_mb}", if (mb >= 0) "$mb" else "some"), ramMb = mb.coerceAtLeast(0))
    }
    fun run() = scope.launch {
        val list = steps(); if (list.any { it.optString("kind") == "ask" }) return@launch
        busy = true; stop = false
        var freed = 0L; var ram = 0L; var ok = 0; var bad = 0; var last: JSONObject? = null
        for ((i, s) in list.withIndex()) {
            if (stop) break
            cur = i
            val r = try { runStep(s) } catch (e: Exception) { Done(false, e.message ?: "It didn't work") }
            results[s.optString("id")] = r.ok to r.msg
            if (r.ok) ok++ else bad++
            if (r.later) last = s; freed += r.freed; ram += r.ramMb
        }
        cur = -1; busy = false
        val left = list.size - results.size
        val extra = listOfNotNull(if (freed > 0) "freed ${fmtBytes(freed)} of storage" else null, if (ram > 0) "$ram MB of RAM" else null).joinToString(" and ")
        summary = if (stop && left > 0) "Stopped — $ok done, $left not started." else if (bad > 0) "$ok done, $bad didn't work — see below." else "All done!" + if (extra.isNotEmpty()) " I $extra while I was at it." else ""
        val go = last
        if (go != null && !stop) when (go.optString("kind")) {
            "partition" -> { PartitionStage.set(go); app.go(Route.Partitions(go.optString("serial"))) }
            else -> when (go.optString("route")) {
                "container-new" -> { ContainerHint.image = go.optString("arg"); app.go(Route.NewContainer) }
                "updates" -> app.go(Route.Updates); "containers" -> app.go(Route.Containers); "store" -> app.go(Route.Store); "inbox" -> app.go(Route.Inbox)
                "lighting" -> app.go(Route.Lighting); "hardware" -> app.go(Route.Hardware); "settings" -> app.go(Route.Settings); "appearance" -> app.go(Route.Appearance)
                "security" -> app.go(Route.Security); "status" -> app.go(Route.Status); "power" -> app.go(Route.QuickPanel); "apps" -> app.go(Route.Apps); "freeup" -> app.go(Route.FreeUp)
                else -> {}
            }
        }
    }

    Page("Assistant", app::back) {
        if (Assistant.on == false) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AssistantOrb(false); Spacer(Modifier.height(10.dp))
                Text("The assistant is in Labs", color = N.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Turn on “Nova assistant” in Settings → Labs, then ask Nova in plain words — here or from Search.", color = N.sub, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
            }
            Group { Row1("Open Labs", null, true, Icons.Rounded.Science, onClick = { app.go(Route.Labs) }) }
            return@Page
        }
        val list = steps(); val waiting = list.any { it.optString("kind") == "ask" }
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AssistantOrb(busy)
            Text(if (busy) (if (cur >= 0) "On it…" else "Thinking…") else if (plan != null) (if (summary.isNotEmpty()) "Done" else "Here's my plan") else "What can I do?",
                color = N.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            val reply = plan?.optString("reply").orEmpty()
            if (reply.isNotEmpty() && summary.isEmpty()) Text(reply, color = N.text, fontSize = 16.sp, lineHeight = 22.sp,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                    .background(lerp(N.card, N.blue, .12f)).border(1.dp, N.blue.copy(alpha = .25f), RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)).padding(horizontal = 16.dp, vertical = 12.dp))
            Text(summary.ifEmpty { if (plan != null) (if (plan?.optJSONArray("steps")?.length() ?: 0 > 0) "Check it, answer anything I asked, then tap Do it. Nothing has changed yet." else "") else "Say it in plain words — several things at once is fine, or ask about your server." },
                color = N.sub, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        }
        BeamBox {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("e.g. Set the lights to fire, free up some space and restart Jellyfin", color = N.sub, fontSize = 16.sp)
                    BasicTextField(text, { text = it.take(500) }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = N.text, fontSize = 16.sp), cursorBrush = SolidColor(N.blue),
                        maxLines = 5, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { planIt(text) }))
                }
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (busy) N.sub else N.blue).clickable(enabled = !busy, onClickLabel = "Plan it") { planIt(text) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowUpward, "Plan it", tint = N.onBlue) }
            }
        }
        if (err.isNotEmpty()) Note(err, 14.sp, 4.dp, N.red)
        val p = plan
        if (p == null) { Note("Runs on your server — nothing is sent anywhere. Risky steps still need your fingerprint, and everything goes in the action log.", 13.sp, 6.dp); BrainSettings(app); return@Page }
        if (list.isEmpty() && p.optString("reply").isNotEmpty()) return@Page
        SectionLabel("Plan · ${list.size} step${if (list.size == 1) "" else "s"}")
        Group {
            if (list.isEmpty()) Row1("Nothing I can do from that.", null)
            list.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                val id = s.optString("id"); val res = results[id]
                Row(Modifier.fillMaxWidth().background(if (i == cur) N.blue.copy(alpha = .08f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(when { res?.first == true -> N.green; res?.first == false -> N.red; else -> Color.Transparent })
                        .then(if (res == null && i != cur) Modifier.border(1.5.dp, N.divider, CircleShape) else Modifier), contentAlignment = Alignment.Center) {
                        when { res?.first == true -> Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            res?.first == false -> Icon(Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            i == cur -> CircularProgressIndicator(Modifier.size(22.dp), color = N.blue, strokeWidth = 2.5.dp)
                            else -> Text("${i + 1}", color = N.sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.optString("say"), color = if (res != null) N.sub else N.text, fontSize = 16.sp)
                        if (s.optString("kind") == "ask") {
                            Text(s.optString("ask"), color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                s.optJSONArray("options").objs().forEachIndexed { k, o ->
                                    Column(Modifier.clip(RoundedCornerShape(14.dp * roundK())).background(N.blue.copy(alpha = .14f)).clickable { choice[id] = k }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                        Text(o.optString("label"), color = N.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        if (o.optString("sub").isNotEmpty()) Text(o.optString("sub"), color = N.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
                                    }
                                }
                            }
                        } else if (res != null) Text(res.second, color = if (res.first) N.sub else N.red, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
                        else if (s.optBoolean("danger")) Text("⚠ Erases data — you'll confirm it", color = N.red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        else if (Assistant.needsStepUp(s.optJSONObject("call")?.optString("path") ?: "")) Text("Needs your fingerprint", color = N.amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
                        if (s.optBoolean("asked") && !busy && res == null) Text("Change", color = N.link, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp).clickable { choice.remove(id) })
                    }
                }
            }
        }
        p.optJSONArray("unknown").let { u -> if (u != null && u.length() > 0) Note("I didn't understand: " + (0 until u.length()).joinToString(", ") { "“${u.getString(it)}”" } + ". Try saying it another way, or search for it.", 13.sp, 4.dp) }
        p.optJSONArray("notes").let { n -> if (n != null) for (k in 0 until n.length()) Note(n.getString(k), 13.sp, 2.dp) }
        if (busy) list.drop(cur + 1).firstOrNull { results[it.optString("id")] == null }?.let { nx ->
            Row(Modifier.padding(horizontal = Space.gutter, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp * roundK())).background(N.blue.copy(alpha = .1f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, tint = N.blue, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Next: ${nx.optString("say")}", color = N.text, fontSize = 14.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                busy -> PrimaryButton(if (stop) "Stopping after this step…" else "Stop", Modifier.weight(1f), enabled = !stop, color = N.pill) { stop = true }
                summary.isNotEmpty() -> PrimaryButton("Ask something else", Modifier.weight(1f)) { plan = null; summary = ""; text = ""; results.clear() }
                else -> { PrimaryButton("Cancel", Modifier.weight(1f), color = N.pill) { plan = null }
                    PrimaryButton(if (waiting) "Answer the question first" else "Do it", Modifier.weight(2f), enabled = list.isNotEmpty() && !waiting) { run() } }
            }
        }
        if (busy) Note("Stop halts before the next step. Something the server has already started (an install, freeing space) finishes on its own.", 13.sp, 2.dp)
    }
}

/** A plan the assistant made for a drive: waits in the Partition manager to be checked and applied as usual. */
object PartitionStage {
    var staged: JSONObject? = null; private set
    fun set(step: JSONObject) { staged = step }
    fun take(serial: String): JSONObject? = staged?.takeIf { it.optString("serial") == serial }.also { if (it != null) staged = null }
}

private fun fmtBytes(b: Long): String { if (b <= 0) return "0 B"; val u = listOf("B", "KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = 0
    while (v >= 1000 && i < u.size - 1) { v /= 1000; i++ }; return (if (v >= 100 || i == 0) "%.0f" else "%.1f").format(v) + " " + u[i] }

/** The assistant's brain: a small language model on the server (Smart or Light) — download it, see it, remove it. */
@Composable private fun BrainSettings(app: AppState) {
    var br by remember { mutableStateOf<JSONObject?>(null) }; var task by remember { mutableStateOf<JSONObject?>(null) }
    var pick by remember { mutableStateOf(false) }; var rm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { br = runCatching { app.api.get("/api/v1/assistant/brain") }.getOrNull() }
    val b = br ?: return
    val choices = b.optJSONObject("choices") ?: JSONObject(); val cur = b.optString("model").takeIf { it.isNotEmpty() && it != "null" }
    fun get(k: String) = scope.launch {
        try { val r = app.stepUp("Download the assistant's brain", "POST", "/api/v1/assistant/brain", JSONObject().put("model", k))
            task = JSONObject().put("pct", 0).put("step", "Starting the download…")
            val t = app.waitTask(r.optString("task")) { task = it }
            task = null; app.toast(if (t.optString("state") == "done") "The brain is ready" else t.optString("error", "That didn't work"))
            br = runCatching { app.api.get("/api/v1/assistant/brain") }.getOrNull()
        } catch (e: Exception) { task = null; app.toast(e.message ?: "That didn't work") }
    }
    val t = task
    if (t != null) { SectionLabel("Nova's brain"); Group { Column(Modifier.padding(18.dp)) { Text(t.optString("step", "Downloading…"), color = N.sub, fontSize = 14.sp); Spacer(Modifier.height(8.dp)); LookBar(t.optInt("pct") / 100f, N.blue) } }; return }
    if (cur != null) {
        val c = choices.optJSONObject(cur)
        SectionLabel("Nova's brain")
        Group {
            Row1("${c?.optString("name") ?: cur} · ${if (b.optBoolean("ready")) "ready" else if (b.optBoolean("running")) "starting…" else "stopped"}", (c?.optString("about") ?: "") + " On this server only.", false, Icons.Rounded.AutoAwesome)
            RowDivider(); Row1("Switch brain", null, onClick = { pick = true })
            RowDivider(); Row1("Remove it", "Frees %.1f GB — the assistant keeps working with its rules".format((c?.optLong("size") ?: 0L) / 1e9), false, Icons.Rounded.Delete, N.red, onClick = { rm = true })
        }
    } else {
        SectionLabel("Make it smarter")
        Group {
            choices.keys().asSequence().toList().forEachIndexed { i, k -> val v = choices.getJSONObject(k)
                if (i > 0) RowDivider()
                Row1("${v.optString("name")} brain${if (b.optString("recommended") == k) " · recommended" else ""}",
                    "${v.optString("about")} %.1f GB download, needs about ${v.optInt("ram_gb")} GB of memory.".format(v.optLong("size") / 1e9), true, if (k == "smart") Icons.Rounded.AutoAwesome else Icons.Rounded.Speed, onClick = { get(k) })
            }
        }
        Note("A small language model that runs on your server, so the assistant understands what its rules don't and answers questions about your server. It's downloaded once from its makers (checked against a fingerprint of the file), runs on its own without access to your files or the internet, and never sends anything anywhere.", 13.sp, 4.dp)
    }
    if (pick) OneDialog({ pick = false }, "Brain") { choices.keys().forEach { k -> val v = choices.getJSONObject(k); DialogChoice(v.optString("name"), v.optString("about"), cur == k) { pick = false; if (k != cur) get(k) } } }
    if (rm) OneDialog({ rm = false }, "Remove the brain?", "Its model is deleted from the server. The assistant keeps working with its rules, and you can download it again any time.",
        listOf(DialogButton("Cancel") { rm = false }, DialogButton("Remove", N.red) { rm = false; scope.launch { runCatching { app.api.delete("/api/v1/assistant/brain") }; br = runCatching { app.api.get("/api/v1/assistant/brain") }.getOrNull() } }))
}
