package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Task manager: the server's processes, like top — sort by CPU, memory or name, search, and tap one for its details,
 *  where you can end it (or force-stop it) with your fingerprint. The server refuses the system's own core, Nova and
 *  SSH. Refreshes every few seconds while it's open. */
@Composable fun TaskManagerScreen(app: AppState) {
    var data by remember { mutableStateOf<JSONObject?>(null) }; var err by remember { mutableStateOf("") }
    var sort by remember { mutableIntStateOf(0) }; var q by remember { mutableStateOf("") }; var kernel by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<JSONObject?>(null) }; var kill by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) { while (true) { runCatching { app.api.get("/api/v1/processes") }.onSuccess { data = it; err = "" }.onFailure { err = it.message ?: "Couldn't load" }; delay(3000) } }
    fun signal(p: JSONObject, force: Boolean) = scope.launch {
        try { app.stepUp("${if (force) "Force stop" else "End"} ${p.optString("name")}", "POST", "/api/v1/processes/${p.optInt("pid")}/signal", JSONObject().put("sig", if (force) "kill" else "term").put("name", p.optString("name")))
            app.toast("${if (force) "Force-stopped" else "Ended"} ${p.optString("name")}"); tick++
        } catch (e: Exception) { app.toast(e.message ?: "That didn't work") }
    }
    Page("Task manager", app::back) {
        val d = data ?: run { Note(err.ifEmpty { "Loading…" }, 14.sp, 8.dp); return@Page }
        val all = d.optJSONArray("processes").objs()
        val cpus = d.optInt("cpus", 1).coerceAtLeast(1); val m = d.optJSONObject("memory")
        val total = all.sumOf { it.optDouble("cpu") } / cpus
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            @Composable fun Tile(v: String, l: String) = Column(Modifier.weight(1f).glassCard(RoundedCornerShape(18.dp)).padding(14.dp)) {
                Text(v, color = N.text, fontSize = 22.sp, fontWeight = FontWeight.Bold); Text(l, color = N.sub, fontSize = 12.sp, maxLines = 2) }
            Tile("%.0f%%".format(total), "CPU · load %.2f".format(d.optJSONArray("load")?.optDouble(0) ?: 0.0))
            Tile(m?.let { "%.0f%%".format(it.optDouble("used") / it.optDouble("total").coerceAtLeast(1.0) * 100) } ?: "—", "Memory · ${m?.let { tmBytes(it.optLong("used")) } ?: ""}")
            Tile("${d.optInt("count")}", "Processes")
        }
        Box(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp)) { OneTextField(q, { q = it.take(60) }, "Search by name, user or PID", Modifier.fillMaxWidth()) }
        Segmented(listOf("CPU", "Memory", "Name"), sort) { sort = it }
        var list = all.filter { kernel || !it.optBoolean("kernel") }
        if (q.isNotBlank()) { val k = q.trim().lowercase(); list = list.filter { it.optString("name").lowercase().contains(k) || it.optString("cmd").lowercase().contains(k) || it.optString("user").lowercase().contains(k) || it.optInt("pid").toString() == k } }
        list = when (sort) { 1 -> list.sortedByDescending { it.optLong("mem") }; 2 -> list.sortedBy { it.optString("name").lowercase() }; else -> list.sortedWith(compareByDescending<JSONObject> { it.optDouble("cpu") }.thenByDescending { it.optLong("mem") }) }
        Group {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp)) {
                Text("NAME", color = N.sub, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("CPU", color = N.sub, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                Text("MEMORY", color = N.sub, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(80.dp)) }
            list.take(150).forEach { p -> RowDivider()
                Row(Modifier.fillMaxWidth().clickable { open = p }.padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(p.optString("name"), color = N.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.optString("user")} · ${p.optInt("pid")}", color = N.sub, fontSize = 13.sp, maxLines = 1) }
                    val c = p.optDouble("cpu")
                    Text("%.1f%%".format(c), color = if (c >= 50) N.amber else N.text, fontSize = 15.sp, fontWeight = if (c >= 50) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                    Text(tmBytes(p.optLong("mem")), color = N.text, fontSize = 15.sp, textAlign = TextAlign.End, modifier = Modifier.width(80.dp))
                } }
            if (list.size > 150) { RowDivider(); Row1("…and ${list.size - 150} more — search to find one", null) }
            if (list.isEmpty()) Row1("No process matches", null)
        }
        Text(if (kernel) "Hide kernel threads" else "Show kernel threads", color = N.link, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = Space.gutter + 6.dp, top = 6.dp).clip(RoundedCornerShape(10.dp)).clickable { kernel = !kernel }.padding(horizontal = 8.dp, vertical = 6.dp))
    }
    open?.let { p ->
        val buttons = if (p.optBoolean("kernel")) listOf(DialogButton("Close") { open = null })
            else listOf(DialogButton("Close") { open = null }, DialogButton("End process") { open = null; signal(p, false) }, DialogButton("Force stop", N.red) { open = null; kill = p })
        OneDialog({ open = null }, p.optString("name"), null, buttons) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DetailLine("PID", "${p.optInt("pid")}" + if (p.optInt("ppid") > 0) " (started by ${p.optInt("ppid")})" else "")
                DetailLine("User", p.optString("user")); DetailLine("CPU", "%.1f%%".format(p.optDouble("cpu"))); DetailLine("Memory", tmBytes(p.optLong("mem")))
                DetailLine("Threads", "${p.optInt("threads")}"); DetailLine("Running for", tmDur(p.optLong("up_s")))
                if (p.optString("cmd").isNotEmpty()) Text(p.optString("cmd"), color = N.sub, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                Text(if (p.optBoolean("kernel")) "Part of the Linux kernel — it can't be stopped." else "End asks it to close (it can save its work first); Force stop ends it at once. Needs your fingerprint. A service may start again by itself.",
                    color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    kill?.let { p -> OneDialog({ kill = null }, "Force stop ${p.optString("name")}?", "It ends at once, without saving anything. If it's part of something bigger, that can stop working too.",
        listOf(DialogButton("Cancel") { kill = null }, DialogButton("Force stop", N.red) { kill = null; signal(p, true) })) }
}

private fun tmBytes(b: Long): String { if (b <= 0) return "0 B"; val u = listOf("B", "KB", "MB", "GB", "TB"); var v = b.toDouble(); var i = 0
    while (v >= 1000 && i < u.size - 1) { v /= 1000; i++ }; return (if (v >= 100 || i == 0) "%.0f" else "%.1f").format(v) + " " + u[i] }
private fun tmDur(s: Long) = when { s >= 86400 -> "${s / 86400} d ${s % 86400 / 3600} h"; s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min"; s >= 60 -> "${s / 60} min"; else -> "$s s" }
