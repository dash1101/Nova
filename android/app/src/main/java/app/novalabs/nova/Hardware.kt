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
    return when {
        m.startsWith("CT") && "MX500" in m -> "Crucial MX500 $gb"
        m.startsWith("SanDisk") -> "SanDisk SSD $gb"
        m.startsWith("ST") && "LM" in m -> "Seagate laptop HDD $gb"
        m.startsWith("HFM") -> "SK hynix NVMe $gb"
        m.isEmpty() -> d.optString("name")
        else -> "$m $gb"
    }
}
private fun health(d: JSONObject): Pair<String, String> {
    val bad = (d.optInt("realloc", 0) > 0) || (d.optInt("uncorrect", 0) > 0) || (d.optInt("pending", 0) > 0)
    return when {
        d.has("smart_passed") && !d.optBoolean("smart_passed", true) -> "Failing" to "critical"
        bad -> "Worn — keep an eye on it" to "warning"
        d.optInt("crc", 0) > 50 -> "Healthy*" to "ok"
        else -> "Healthy" to "ok"
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
        drives.groupBy { it.optString("role") }.toSortedMap(compareBy { listOf("Photo pool", "Backup drive", "Cold storage", "Boot drive").indexOf(it).let { i -> if (i < 0) 9 else i } })
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
            Row1("Fan lighting", "Colour, effects, schedules", true, Icons.Rounded.Lightbulb, N.amber, onClick = { app.go(Route.Lighting) })
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
            Row1("Overall", if (d.optBoolean("smart_passed", true)) "Passed" else "FAILED"); RowDivider()
            Row1("Temperature", d.optInt("temp").takeIf { it > 0 }?.let { "$it°C" } ?: "—"); RowDivider()
            Row1("Reallocated sectors", d.optString("realloc", "—")); RowDivider()
            Row1("Unreadable (pending)", d.optString("pending", "—")); RowDivider()
            Row1("Uncorrectable errors", d.optString("uncorrect", "—")); RowDivider()
            Row1("Cable errors (all-time) *", "${d.optString("crc", "—")} · if this keeps rising, check the cable")
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
    }
    if (confirm) OneDialog({ confirm = false }, "Unmount ${driveName(d)}?",
        "Anything using it (shares, backups) loses access until it's mounted again. USB drives are powered down so you can unplug them.",
        listOf(DialogButton("Cancel") { confirm = false }, DialogButton("Unmount", N.amber) { confirm = false; app.act { busy = true
            try { val r = app.stepUp("Unmount ${driveName(d)}", "POST", "/api/v1/drives/$serial/unmount"); app.toast(r.optString("note", "Unmounted")); reload() }
            finally { busy = false } } }))
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
