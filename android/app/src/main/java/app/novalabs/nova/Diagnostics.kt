package app.novalabs.nova

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

// Diagnostics: internet / device / drive speed, CPU stress, memory test, quick network tools.

private val CYAN = Color(0xFF64D2FF)

@Composable fun DiagnosticsScreen(app: AppState) {
    val tasks by live(app, "/api/v1/tasks", 5_000)
    val st by live(app, "/api/v1/storage")
    val all = tasks?.optJSONArray("tasks").objs()
    fun last(kind: String) = all.firstOrNull { it.optString("kind") == kind && it.optString("state") == "done" }
    fun running(kind: String) = all.firstOrNull { it.optString("kind") == kind && it.optString("state") == "running" }
    fun start(kind: String, body: JSONObject = JSONObject()) {
        if (!app.isAdmin) { app.toast("This ${DeviceForm.noun} has view-only access"); return }
        app.act { val t = app.api.post("/api/v1/diag/$kind", body); app.go(Route.Task(t.optString("id"))) }
    }
    Page("Diagnostics", app::back) {
        Text("Tests run on the server. They're safe to run any time; the heavy ones (CPU, memory) slow other apps while they run.",
            color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))

        SectionHelp("Internet speed (server)", "net")
        Group {
            last("net-internet")?.optJSONObject("result")?.let { NetResult(it); Text("Last run ${whenText(last("net-internet")!!.optDouble("finished"))}",
                color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, bottom = 6.dp)) }
            RunRow(app, running("net-internet"), "Run the internet test", "About 30 seconds") { start("net-internet") }
        }

        SectionHelp("This ${DeviceForm.noun} ↔ server", "device-net")
        Group { DeviceSpeed(app) }

        SectionHelp("Drive speed", "disk")
        Group {
            val targets = (st?.optJSONArray("pools").objs().filter { it.optString("mount").isNotEmpty() }.map { it.optString("mount") to "${it.optString("name")} pool" } +
                st?.optJSONArray("drives").objs().flatMap { d -> d.optJSONArray("mounts").strs().map { it to driveTitle(d) } }).distinctBy { it.first }
            var pick by remember { mutableStateOf<String?>(null) }
            last("disk-speed")?.optJSONObject("result")?.let { DiskResult(it) }
            if (targets.isEmpty()) Row1("Loading drives…", enabled = false)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { (m, l) -> ChoiceChip("$l · $m", pick == m) { pick = m } }
            }
            RunRow(app, running("disk-speed"), "Test ${pick ?: "a drive"}", "About 40 seconds · writes a temporary file, deleted after", pick != null) { start("disk-speed", JSONObject().put("path", pick)) }
        }

        SectionHelp("CPU stress test", "cpu")
        Group {
            var secs by remember { mutableIntStateOf(60) }
            last("cpu-stress")?.optJSONObject("result")?.let { CpuResult(it) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 s", 60 to "1 min", 300 to "5 min", 600 to "10 min").forEach { (s, l) -> ChoiceChip(l, secs == s) { secs = s } }
            }
            RunRow(app, running("cpu-stress"), "Run every core flat out", "Watch temperature, clock speed and power live") { start("cpu-stress", JSONObject().put("seconds", secs)) }
        }

        SectionHelp("Memory test", "mem")
        Group {
            var pct by remember { mutableIntStateOf(50) }; var secs by remember { mutableIntStateOf(60) }
            last("mem-test")?.optJSONObject("result")?.let { MemResult(it) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 50, 75).forEach { p -> ChoiceChip("$p% of free", pct == p) { pct = p } }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60 to "1 min", 300 to "5 min", 900 to "15 min").forEach { (s, l) -> ChoiceChip(l, secs == s) { secs = s } }
            }
            RunRow(app, running("mem-test"), "Fill and check memory", "Your apps keep running — Nova leaves room for them") {
                start("mem-test", JSONObject().put("percent", pct).put("seconds", secs)) }
        }

        SectionHelp("Quick tools", "tools")
        Group { QuickTools(app) }

        SectionLabel("What's using the server")
        Group { TopProcesses(app) }
    }
}

@Composable private fun RunRow(app: AppState, running: JSONObject?, title: String, sub: String, enabled: Boolean = true, onRun: () -> Unit) {
    if (running != null) TaskRow(app, running)
    else Row1(title, sub, false, Icons.Rounded.PlayArrow, N.green, enabled = enabled && app.isAdmin, onClick = onRun)
}

@Composable fun ChoiceChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(text, color = if (on) Color.White else N.text, fontSize = 14.sp, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(16.dp))
        .background(if (on) N.blue else N.pill).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable private fun Big(value: String, unit: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Text(" $unit", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
        }
        Text(label, color = N.sub, fontSize = 13.sp)
    }
}
private fun f1(d: Double) = if (d >= 100) "%.0f".format(d) else "%.1f".format(d)

@Composable fun NetResult(r: JSONObject) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Big(f1(r.optDouble("download_mbps")), "Mbps", "↓ Download", N.green)
        Big(f1(r.optDouble("upload_mbps")), "Mbps", "↑ Upload", N.blue)
    }
    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Big(f1(r.optDouble("ping_ms")), "ms", "Ping", N.text); Big(f1(r.optDouble("jitter_ms")), "ms", "Jitter", N.text)
        Big(if (r.isNull("loss_pct")) "—" else f1(r.optDouble("loss_pct")), "%", "Loss", if (r.optDouble("loss_pct", 0.0) > 1) N.amber else N.text)
    }
    Text("Server: ${r.optString("server")}" + (r.optString("download_from").takeIf { it.isNotEmpty() }?.let { " · download from $it" } ?: ""),
        color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp))
}

@Composable fun DiskResult(r: JSONObject) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
        Text(r.optString("path"), color = N.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val sr = r.optJSONObject("seq_read"); val sw = r.optJSONObject("seq_write"); val rr = r.optJSONObject("rand_read"); val rw = r.optJSONObject("rand_write")
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Big(f1(sr?.optDouble("mbps") ?: 0.0), "MB/s", "Read", N.green); Big(f1(sw?.optDouble("mbps") ?: 0.0), "MB/s", "Write", N.blue)
        }
        DetailLine("Random reads (4K)", "${rr?.optInt("iops")} IOPS · ${f1(rr?.optDouble("mbps") ?: 0.0)} MB/s")
        DetailLine("Random writes (4K)", "${rw?.optInt("iops")} IOPS · ${f1(rw?.optDouble("mbps") ?: 0.0)} MB/s")
        val seq = sr?.optDouble("mbps") ?: 0.0
        Text(when { seq > 1500 -> "NVMe-class speed."; seq > 350 -> "SATA SSD-class speed."; seq > 80 -> "Hard-drive-class speed — fine for photos, video and backups."
            else -> "Slow — a USB 2 link, a struggling drive, or it's busy." } + r.optString("note").takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty(),
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable fun CpuResult(r: JSONObject) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        val t = r.optDouble("max_temp", 0.0)
        Big(f1(t), "°C", "Hottest", if (t >= 92) N.red else if (t >= 80) N.amber else N.green)
        Big(r.optInt("avg_mhz").toString(), "MHz", "Average clock", N.text)
        if (!r.isNull("avg_watts")) Big(f1(r.optDouble("avg_watts")), "W", "CPU power", N.blue)
    }
    Text(r.optString("verdict"), color = N.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
}

@Composable fun MemResult(r: JSONObject) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        Big(f1(r.optLong("tested_bytes") / 1e9), "GB", "Tested", N.text)
        Big(r.optInt("errors").toString(), "", "Errors", if (r.optInt("errors") > 0) N.red else N.green)
    }
    Text(r.optString("verdict"), color = if (r.optBoolean("ok")) N.text else N.red, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
}

/** Result card on the task screen, by kind. */
@Composable fun DiagResult(kind: String, r: JSONObject) {
    when (kind) {
        "net-internet" -> Group { NetResult(r); Spacer(Modifier.height(12.dp)) }
        "disk-speed" -> Group { DiskResult(r) }
        "cpu-stress" -> Group { CpuResult(r); Spacer(Modifier.height(8.dp)) }
        "mem-test" -> Group { MemResult(r); Spacer(Modifier.height(8.dp)) }
        "backup-run" -> r.optJSONObject("stats")?.let { s -> Group { Row1("${s.optInt("transferred")} files changed", "${bytesHuman(s.optLong("sent"))} copied · ${s.optInt("files")} files in the backup · ${r.optInt("duration")} s", true, Icons.Rounded.CheckCircle, N.green) } }
        "restore" -> Group { Row1("Restored", r.optString("target"), true, Icons.Rounded.CheckCircle, N.green) }
        "format", "combine", "raid" -> Group { Row1("Ready", r.optString("mount"), true, Icons.Rounded.CheckCircle, N.green) }
    }
}

/** Live chart for the stress tests: temperature, clock and power, each on its own scale. */
@Composable fun StressChart(samples: List<JSONObject>) {
    val series = listOf(Triple("temp", "°C", N.red), Triple("mhz", "MHz", N.green), Triple("watts", "W", N.blue)).filter { (k, _, _) -> samples.any { !it.isNull(k) && it.has(k) } }
    Group {
        Column(Modifier.padding(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                series.forEach { (k, u, c) ->
                    val v = samples.lastOrNull { it.has(k) && !it.isNull(k) }?.optDouble(k)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c)); Spacer(Modifier.width(6.dp))
                        Text("${v?.let { if (k == "mhz") it.toInt().toString() else f1(it) } ?: "—"} $u", color = N.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                series.forEach { (k, _, c) ->
                    val vals = samples.map { if (it.has(k) && !it.isNull(k)) it.optDouble(k).toFloat() else Float.NaN }
                    val ok = vals.filter { !it.isNaN() }; if (ok.size < 2) return@forEach
                    val lo = if (k == "temp") minOf(30f, ok.min()) else 0f; val hi = maxOf(ok.max(), lo + 1f) * 1.05f
                    val p = Path(); var started = false
                    vals.forEachIndexed { i, v ->
                        if (v.isNaN()) return@forEachIndexed
                        val x = size.width * i / (vals.size - 1).coerceAtLeast(1); val y = size.height * (1 - (v - lo) / (hi - lo))
                        if (!started) { p.moveTo(x, y); started = true } else p.lineTo(x, y)
                    }
                    drawPath(p, c, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                }
                drawLine(Color.Gray.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            Text("${samples.size} s", color = N.sub, fontSize = 12.sp, modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable private fun DeviceSpeed(app: AppState) {
    var res by remember { mutableStateOf<Triple<Double, Double, String>?>(null) }
    var live by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    res?.let { (mbps, ping, via) ->
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Big(f1(mbps), "Mbps", "↓ To this ${DeviceForm.noun}", N.green); Big(f1(ping), "ms", "Response time", N.text)
        }
        Text("Over ${if (via == "home") "the home network" else "the internet (remote)"}", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp))
    }
    if (running) Row1("Testing…", live, true, Icons.Rounded.Speed, CYAN)
    else Row1("Test this ${DeviceForm.noun}'s connection", "Downloads 25 MB from the server", false, Icons.Rounded.PlayArrow, N.green, onClick = {
        running = true; live = "Measuring response time…"
        app.act {
            try {
                val times = (1..8).map { val t = System.nanoTime(); app.api.get("/api/v1/ping"); (System.nanoTime() - t) / 1e6 }
                val ping = times.sorted()[times.size / 2]
                val (n, ms) = app.api.speedDown("/api/v1/diag/blob?mb=25") { b, t -> live = "${bytesHuman(b)} · ${f1(b * 8.0 / 1e6 / (t / 1000.0).coerceAtLeast(0.01))} Mbps" }
                res = Triple(n * 8.0 / 1e6 / (ms / 1000.0).coerceAtLeast(0.01), ping, app.api.via)
            } finally { running = false }
        }
    })
}

@Composable private fun QuickTools(app: AppState) {
    var host by remember { mutableStateOf("1.1.1.1") }
    var port by remember { mutableStateOf("443") }
    var out by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run(tool: String) {
        busy = true; out = "…"
        app.act {
            try {
                val q = "host=" + java.net.URLEncoder.encode(host.trim(), "UTF-8") + if (tool == "port") "&port=${port.trim()}" else ""
                val r = app.api.get("/api/v1/diag/$tool?$q")
                out = when (tool) {
                    "ping" -> if (r.optDouble("loss_pct") >= 100) "No reply from ${r.optString("host")}. ${r.optString("error")}"
                        else "${r.optString("host")}: avg ${f1(r.optDouble("avg"))} ms (min ${f1(r.optDouble("min"))}, max ${f1(r.optDouble("max"))}) · jitter ${f1(r.optDouble("jitter", 0.0))} ms · ${f1(r.optDouble("loss_pct"))}% lost"
                    "trace" -> r.optJSONArray("hops").objs().joinToString("\n") { h -> "${h.optInt("hop")}. ${h.optString("host")}${if (!h.isNull("ms")) "  ${f1(h.optDouble("ms"))} ms" else ""}" }.ifEmpty { "No route found" }
                    "dns" -> if (r.optString("error").isNotEmpty()) "Couldn't resolve: ${r.optString("error")}" else "${r.optString("name")} → ${r.optJSONArray("addresses").strs().joinToString()}  (${f1(r.optDouble("ms"))} ms)"
                    else -> if (r.optBoolean("open")) "${r.optString("host")}:${r.optInt("port")} is reachable (${f1(r.optDouble("ms"))} ms)" else "${r.optString("host")}:${r.optInt("port")} — ${r.optString("error")}"
                }
            } catch (e: Exception) { out = e.message } finally { busy = false }
        }
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OneTextField(host, { host = it.trim().take(253) }, "Host or IP", Modifier.weight(1f))
            OneTextField(port, { port = it.filter(Char::isDigit).take(5) }, "Port", Modifier.width(90.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ping" to "Ping", "trace" to "Traceroute", "dns" to "DNS lookup", "port" to "Check port").forEach { (k, l) -> PillButton(l, !busy && host.isNotBlank()) { run(k) } }
        }
        out?.let { Text(it, color = N.text, fontSize = 14.sp, fontFamily = Mono) }
    }
}

@Composable private fun TopProcesses(app: AppState) {
    ExpandRow("Top processes", "Sorted by CPU use", false, Icons.Rounded.Memory, N.blue) {
        val top by live(app, "/api/v1/diag/top", 5_000)
        val t = top
        if (t == null) Detail("Loading…")
        else {
            val m = t.optJSONObject("mem")
            Detail("Load ${t.optJSONArray("load")?.optDouble(0)?.let { f1(it) }} on ${t.optInt("cpus")} threads · memory ${bytesHuman(m?.optLong("used") ?: 0)} of ${bytesHuman(m?.optLong("total") ?: 0)}")
            t.optJSONArray("procs").objs().take(15).forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(p.optString("name"), color = N.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${f1(p.optDouble("cpu"))}%", color = N.blue, fontSize = 14.sp, modifier = Modifier.width(64.dp))
                    Text(bytesHuman(p.optLong("rss")), color = N.sub, fontSize = 14.sp, modifier = Modifier.width(76.dp))
                }
            }
        }
    }
}
