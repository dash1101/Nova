package app.novalabs.nova

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject


private fun driveName(d: JSONObject): String {
    val m = d.optString("model"); val gb = (d.optLong("size") / 1_000_000_000L).let { if (it >= 1000) "${(it + 50) / 1000}TB" else "${((it + 5) / 10) * 10}GB" }
    // "Crucial SSD 500GB" instead of "CT500MX500SSD1 500GB": the maker from the model's prefix, plus the kind of drive
    val brand = listOf("CT" to "Crucial", "ST" to "Seagate", "WDC" to "WD", "WD" to "WD", "Samsung" to "Samsung", "SAMSUNG" to "Samsung",
        "SanDisk" to "SanDisk", "HFM" to "SK hynix", "HFS" to "SK hynix", "KINGSTON" to "Kingston", "TOSHIBA" to "Toshiba", "INTEL" to "Intel",
        "Micron" to "Micron", "HGST" to "HGST", "Hitachi" to "Hitachi").firstOrNull { m.startsWith(it.first) }?.second
    val kind = if (d.optString("bus") == "nvme" || d.optString("name").startsWith("nvme")) "NVMe" else if (d.optBoolean("ssd")) "SSD" else "HDD"
    return when { m.isEmpty() -> d.optString("name"); brand != null -> "$brand $kind $gb"; else -> "$m $gb" }
}
private fun health(d: JSONObject): Pair<String, String> {
    val bad = (d.optInt("realloc", 0) > 0) || (d.optInt("uncorrect", 0) > 0) || (d.optInt("pending", 0) > 0)
    val p = if (d.has("health") && !d.isNull("health")) " · ${d.optInt("health")}%" else ""
    return when {
        d.has("smart_passed") && !d.isNull("smart_passed") && !d.optBoolean("smart_passed", true) -> "Failing$p" to "critical"
        if (d.has("worn")) d.optBoolean("worn") else d.optInt("realloc", 0) > 9 || d.optInt("uncorrect", 0) > 3 -> "Worn — keep an eye on it$p" to "warning"
        bad -> "Mostly healthy$p" to "ok"
        d.optInt("crc", 0) > 50 -> "Healthy*$p" to "ok"
        else -> "Healthy$p" to "ok"
    }
}

@Composable fun HardwareScreen(app: AppState) {
    val hw by live(app, "/api/v1/hardware", 15_000)
    val drives = hw?.optJSONArray("drives")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    val temps = hw?.optJSONObject("temps")
    Page("Storage & hardware", app::back, listOf(TopAction(Icons.Rounded.Add, "Set up drives") { app.go(Route.StorageWizard()) })) {
        StorageOverview(app)
        if (temps != null) {
            SectionLabel("Temperatures")
            Group {
                Row1("CPU", temps.optString("cpu_temp", "—"), icon = Icons.Rounded.Memory, iconTint = N.blue); RowDivider()
                Row1("Boot NVMe", temps.optString("nvme_temp", "—"), icon = Icons.Rounded.SdStorage, iconTint = N.blue); RowDivider()
                Row1("Drives", temps.optString("drive_temps", "—"), icon = Icons.Rounded.Storage, iconTint = N.blue)
            }
        }
        drives.groupBy { it.optString("role") }.toSortedMap(compareBy<String> { if (it == "Boot drive") 1 else 0 }.thenBy { it })
            .forEach { (role, ds) ->
                SectionLabel(if (role == "Other" || role.isEmpty()) "Drives" else role)
                Group {
                    ds.forEachIndexed { i, d ->
                        if (i > 0) RowDivider()
                        val (h, lvl) = health(d)
                        val u = d.optJSONArray("usage")?.optJSONObject(0)
                        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
                            Row1Inline(driveName(d), "${bytesHuman(d.optLong("size"))} · ${if (d.optBoolean("ssd")) "SSD" else "HDD"} · ${d.optString("bus").uppercase()}${d.optInt("temp").takeIf { it > 0 }?.let { " · $it°C" } ?: ""}",
                                h, levelColor(lvl, N)) { app.go(Route.Drive(d.optString("serial"))) }
                            if (u != null) {
                                Spacer(Modifier.height(8.dp))
                                val frac = u.optLong("used").toFloat() / u.optLong("total").coerceAtLeast(1)
                                UsageBar(frac, if (frac > 0.95f) N.red else if (frac > 0.85f) N.amber else N.blue)
                                Text("${bytesHuman(u.optLong("free"))} free of ${bytesHuman(u.optLong("total"))}", color = N.sub, fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 4.dp))
                            } else if (d.optJSONArray("mounts")?.length() == 0) Text("Not mounted", color = N.amber, fontSize = 13.sp)
                        }
                    }
                }
            }
        SectionLabel("Fans")
        Group {
            ExpandRow("CPU & case fan speed", "Run by the motherboard", false, Icons.Rounded.Toys, N.sub) {
                Detail(hw?.optJSONObject("fans")?.optString("note")?.ifEmpty { null }
                    ?: "The fans follow the motherboard's own curve — set it in the BIOS (often under Smart Fan or Q-Fan).")
                Detail("Reading or setting fan speeds from Linux needs a driver for the board's fan chip; many boards work out of the box with lm-sensors.")
            }
            RowDivider()
            Row1("Fan lighting", "Color, effects, schedules", true, Icons.Rounded.Lightbulb, N.amber, onClick = { app.go(Route.Lighting) })
        }
        LinksCard(listOf("Quick panel (restart, shut down)" to { app.go(Route.QuickPanel) }))
    }
}

@Composable private fun Row1Inline(title: String, sub: String, badge: String, badgeColor: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = N.text, fontSize = 17.sp, maxLines = 1)
            Text(sub, color = N.sub, fontSize = 13.sp)
        }
        Text(badge, color = badgeColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onClick).padding(start = 8.dp))
    }
}

@Composable fun DriveScreen(app: AppState, serial: String) {
    val hwLive = live(app, "/api/v1/hardware")
    val hw = hwLive.value
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var repairAsk by remember { mutableStateOf(false) }
    var targets by remember { mutableStateOf<List<JSONObject>?>(null) }
    var cloneTo by remember { mutableStateOf<JSONObject?>(null) }
    suspend fun reload() { hwLive.value = app.api.get("/api/v1/hardware") }
    val d = hw?.optJSONArray("drives")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("serial") == serial } } ?: return
    val mounted = (d.optJSONArray("mounts")?.length() ?: 0) > 0
    val (h, lvl) = health(d)
    Page(driveName(d), app::back) {
        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (d.optBoolean("ssd")) Icons.Rounded.SdStorage else Icons.Rounded.Storage, null, tint = levelColor(lvl, N), modifier = Modifier.size(90.dp))
            Text(h, color = levelColor(lvl, N), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${d.optString("role")} · /dev/${d.optString("name")}", color = N.sub)
        }
        SectionLabel("Health (SMART)")
        Group {
            if (d.has("health") && !d.isNull("health")) { Row1("Estimated health", "${d.optInt("health")}%" +
                (if (d.has("life") && !d.isNull("life")) " · ${d.optInt("life")}% of its rated wear left" else "") + " — a guide from its SMART data, not a promise"); RowDivider() }
            val passed = if (!d.has("smart_passed") || d.isNull("smart_passed")) null else d.optBoolean("smart_passed", true)
            val probs = listOfNotNull(if (passed == false) "the drive reports it's failing" else null,
                d.optInt("realloc", 0).takeIf { it > 0 }?.let { "$it reallocated" }, d.optInt("pending", 0).takeIf { it > 0 }?.let { "$it unreadable" },
                d.optInt("uncorrect", 0).takeIf { it > 0 }?.let { "$it uncorrectable" })
            val sum = listOfNotNull(when (passed) { null -> "Not read yet"; false -> "FAILED"; else -> "Passed" }, d.optInt("temp").takeIf { it > 0 }?.let { "$it°C" },
                if (probs.isEmpty()) "no bad sectors" else probs.joinToString(", ")).joinToString(" · ")
            ExpandRow("Health checks", sum, icon = Icons.Rounded.Speed, iconTint = N.sub) {
                if (d.optLong("hours") > 0) DetailLine("Powered on", "%,d hours (%.1f years)".format(d.optLong("hours"), d.optLong("hours") / 8766.0))
                DetailLine("Overall", when (passed) { null -> "Not read yet — run a quick test"; false -> "FAILED"; else -> "Passed" })
                DetailLine("Temperature", d.optInt("temp").takeIf { it > 0 }?.let { "$it°C" } ?: "—")
                DetailLine("Reallocated sectors", d.optString("realloc", "—"))
                DetailLine("Unreadable (pending)", d.optString("pending", "—"))
                DetailLine("Uncorrectable errors", d.optString("uncorrect", "—"))
                DetailLine("Cable errors (all-time)", d.optString("crc", "—"))
                Detail("Cable errors never go back down — only worry if the number keeps rising.")
            }
        }
        DriveTests(app, serial)
        SectionLabel("Details")
        Group {
            Row1("Size", bytesHuman(d.optLong("size"))); RowDivider()
            Row1("Connection", d.optString("bus").uppercase()); RowDivider()
            Row1("Serial", d.optString("serial")); RowDivider()
            Row1("Mounted at", d.optJSONArray("mounts")?.let { a -> (0 until a.length()).joinToString { a.getString(it) } }?.ifEmpty { "Not mounted" } ?: "—")
        }
        Spacer(Modifier.height(10.dp))
        if (!app.isAdmin) Text("View-only access: an admin can mount or unmount drives.",
            color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp))
        else if (d.optBoolean("protected")) Text("This drive is in use by the server (${d.optString("role")}) and can't be unmounted from the app.",
            color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp))
        else Box(Modifier.fillMaxWidth().padding(22.dp)) {
            PrimaryButton(if (busy) "Working…" else if (mounted) "Safely unmount" else "Mount", Modifier.fillMaxWidth(), !busy,
                if (mounted) N.amber else N.blue) {
                if (mounted) confirm = true else app.act { busy = true
                    try { app.api.post("/api/v1/drives/$serial/mount"); app.toast("Mounted"); reload() } finally { busy = false } }
            }
        }
        if (app.isAdmin) Group {
            Row1("Partitions", if (d.optBoolean("protected")) "See how it's laid out (it's in use, so it can't be changed)" else "See and change how it's laid out — create, delete, format, label, resize",
                true, Icons.Rounded.ViewDay, onClick = { app.go(Route.Partitions(serial)) })
        }
        if (app.isAdmin && !d.optBoolean("protected")) {
            SectionLabel("Fix and copy")
            Group {
                Row1("Repair", "Checks every filesystem on it and fixes what it can, then runs the drive's quick self-test. It's unmounted for a few minutes while that happens.",
                    false, Icons.Rounded.Build, N.amber, onClick = { repairAsk = true }); RowDivider()
                Row1("Clone to another drive", "Copies everything onto a spare drive (which is erased). Bigger: an exact copy that grows to fill it. Smaller: the files are copied if they fit.",
                    false, Icons.Rounded.ContentCopy, N.blue, onClick = { app.act { val st = app.api.get("/api/v1/storage")
                        targets = st.optJSONArray("drives")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty().filter { it.optString("serial") != serial && it.optBoolean("erasable") } } })
            }
        }
    }
    if (repairAsk) OneDialog({ repairAsk = false }, "Repair ${driveName(d)}?",
        (if (mounted) "It's unmounted while it's checked — apps using it lose it for a few minutes, then it's mounted again. " else "") + "Nothing is erased.",
        listOf(DialogButton("Cancel") { repairAsk = false }, DialogButton("Repair", N.blue) { repairAsk = false; app.act {
            val r = app.stepUp("Repair ${driveName(d)}", "POST", "/api/v1/storage/task/repair",
                JSONObject().put("spec", JSONObject().put("drives", JSONArray(listOf(serial))).put("confirm", JSONArray(listOf(serial)))))
            app.go(Route.Task(r.optString("id"))) } }))
    targets?.let { ts ->
        OneDialog({ targets = null }, "Copy onto which drive?", if (ts.isEmpty()) "Plug in the drive to copy onto. It has to be unmounted and not part of a pool or array — Nova never offers the system drive." else null,
            listOf(DialogButton("Cancel") { targets = null })) {
            ts.forEach { t -> Row1("${t.optString("size_text")} ${t.optString("model").ifEmpty { t.optString("name") }}",
                if (t.optLong("size") >= d.optLong("size")) "Exact copy" + (if (t.optLong("size") > d.optLong("size") * 1.01) ", then it grows to use the extra space" else "")
                else "Smaller — the files are copied if they fit", false, Icons.Rounded.Storage, N.blue, onClick = { targets = null; cloneTo = t }) }
        }
    }
    cloneTo?.let { t ->
        val tn = "${t.optString("size_text")} ${t.optString("model").ifEmpty { t.optString("name") }}"
        OneDialog({ cloneTo = null }, "Erase $tn?", "Everything on it is replaced by a copy of ${driveName(d)}." + (if (mounted) " The drive you're copying is unmounted while it's copied, so nothing changes halfway." else "") + " This can't be undone.",
            listOf(DialogButton("Cancel") { cloneTo = null }, DialogButton("Erase and clone", N.red) { cloneTo = null; app.act {
                val r = app.stepUp("Clone ${driveName(d)} onto $tn", "POST", "/api/v1/storage/task/clone",
                    JSONObject().put("spec", JSONObject().put("drives", JSONArray(listOf(serial, t.optString("serial")))).put("confirm", JSONArray(listOf(t.optString("serial"))))))
                app.go(Route.Task(r.optString("id"))) } }))
    }
    var forceWhy by remember { mutableStateOf<String?>(null) }
    if (confirm) OneDialog({ confirm = false }, "Unmount ${driveName(d)}?",
        "Anything using it (shares, backups) loses access until it's mounted again. USB drives are powered down so you can unplug them.",
        listOf(DialogButton("Cancel") { confirm = false }, DialogButton("Unmount", N.amber) { confirm = false; app.act { busy = true
            try { val r = app.stepUp("Unmount ${driveName(d)}", "POST", "/api/v1/drives/$serial/unmount"); app.toast(r.optString("note", "Unmounted")); reload() }
            catch (e: ApiException) { if (e.code == 409) forceWhy = e.message else throw e }
            finally { busy = false } } }))
    // something's using it: say what, and offer to force it (stops those containers, detaches the rest)
    forceWhy?.let { why -> OneDialog({ forceWhy = null }, "Force unmount?",
        "$why. Force unmount stops those containers and detaches the drive; programs that had it open (a terminal, say) let go when they're closed. Start the containers again once it's back.",
        listOf(DialogButton("Cancel") { forceWhy = null }, DialogButton("Force unmount", N.red) { forceWhy = null; app.act { busy = true
            try { val r = app.stepUp("Force unmount ${driveName(d)}", "POST", "/api/v1/drives/$serial/unmount", JSONObject().put("force", true)); app.toast(r.optString("note", "Unmounted")); reload() }
            finally { busy = false } } })) }
}

/** SMART self-tests: the drive checks itself (read-only, fine while it's in use). */
@Composable private fun DriveTests(app: AppState, serial: String) {
    val tl = live(app, "/api/v1/drives/$serial/test")
    val t = tl.value
    val running = t?.optBoolean("running") == true
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(running) { while (running) { delay(5_000); runCatching { tl.value = app.api.get("/api/v1/drives/$serial/test") } } }
    if (t == null || !t.optBoolean("supported", true)) return
    fun start(kind: String) {
        if (!app.isAdmin) { app.toast("This ${DeviceForm.noun} has view-only access"); return }
        busy = true
        app.act { try {
            val r = app.api.post("/api/v1/drives/$serial/test", JSONObject().put("type", kind))
            if (kind != "abort") app.toast(r.optInt("minutes").takeIf { it > 0 }?.let { "Test started · about ${if (it >= 120) "${it / 60} hours" else "$it min"}" } ?: "Test started")
            tl.value = app.api.get("/api/v1/drives/$serial/test")
        } finally { busy = false } }
    }
    SectionLabel("Health tests")
    Group {
        if (running) {
            val left = t.optInt("remaining_pct", -1).takeIf { it >= 0 }?.let { 100 - it } ?: t.optInt("progress_pct", 0)
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
                Text("Testing… $left%", color = N.text, fontSize = 17.sp)
                Spacer(Modifier.height(8.dp)); ProgressBar(left / 100f)
                Text("The drive keeps working normally while it tests itself.", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            RowDivider()
            Row1("Stop the test", null, icon = Icons.Rounded.Stop, iconTint = N.red, enabled = !busy, onClick = { start("abort") })
        } else {
            val sm = t.optInt("short_minutes", 2); val lm = t.optInt("long_minutes", 0)
            Row1("Quick test", "About ${sm.coerceAtLeast(1)} min · checks the electronics and a sample of the surface", true, Icons.Rounded.Speed,
                enabled = !busy, onClick = { start("short") })
            RowDivider()
            Row1("Full surface scan", (if (lm > 0) "About ${if (lm >= 120) "${lm / 60} hours" else "$lm min"} · " else "") + "reads every sector — best for second-hand drives",
                true, Icons.Rounded.Search, enabled = !busy, onClick = { start("long") })
        }
        val log = t.optJSONArray("log")
        if (log != null && log.length() > 0) {
            RowDivider()
            for (i in 0 until minOf(log.length(), 5)) {
                val e = log.getJSONObject(i); if (i > 0) RowDivider()
                val ok = e.optBoolean("passed", true) && !e.optString("result").contains("fail", true)
                Row1(e.optString("type").replace(" offline", "").ifEmpty { "Test" } + " · " + e.optString("result"),
                    e.optInt("hours").takeIf { it > 0 }?.let { "At $it power-on hours" + (t.optInt("power_on_hours").takeIf { n -> n > 0 }?.let { n -> " (now $n)" } ?: "") },
                    false, if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error, if (ok) N.green else N.red)
            }
        }
    }
}
