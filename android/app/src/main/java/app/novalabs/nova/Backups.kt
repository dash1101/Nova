package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Backups: the list, one backup (history, run, edit), the backup wizard, and browse & restore.

private val DAYS3 = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
fun whenText(t: Double): String {
    if (t <= 0) return "never"
    val d = System.currentTimeMillis() / 1000.0 - t
    return when {
        d < 0 -> SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date((t * 1000).toLong()))
        d < 90 -> "just now"; d < 3600 -> "${(d / 60).toInt()} min ago"; d < 86400 -> "${(d / 3600).toInt()} h ago"
        d < 7 * 86400 -> SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date((t * 1000).toLong()))
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date((t * 1000).toLong()))
    }
}
fun scheduleText(s: JSONObject?): String = when {
    s == null -> ""
    s.optBoolean("manual") -> "Only when you run it"
    s.optInt("every_hours") > 0 -> "Every ${s.optInt("every_hours")} h"
    else -> { val d = s.optJSONArray("days").let { a -> if (a == null) (0..6).toList() else List(a.length()) { a.getInt(it) } }
        (if (d.size == 7) "Daily" else if (d == listOf(0, 1, 2, 3, 4)) "Weekdays" else if (d == listOf(5, 6)) "Weekends" else d.joinToString(" ") { DAYS3[it] }) + " at ${s.optString("time")}" }
}
fun destText(d: JSONObject?): String = when (d?.optString("type")) {
    "local" -> d.optString("path"); "smb" -> "\\\\${d.optString("host")}\\${d.optString("share")}"; "nfs" -> "${d.optString("host")}:/${d.optString("share")}"; else -> "—"
}
private fun lastText(j: JSONObject): Pair<String, String> {
    val l = j.optJSONObject("last") ?: return "Not run yet" to "info"
    val ago = whenText(l.optDouble("t"))
    return when {
        l.optBoolean("ok") -> "Backed up $ago · ${l.optJSONObject("stats")?.optInt("transferred") ?: 0} files changed" to "ok"
        l.optBoolean("partial") -> "Partly backed up $ago — ${l.optJSONArray("skipped").strs().firstOrNull() ?: ""}" to "warning"
        else -> "Failed $ago — ${l.optString("error")}" to "critical"
    }
}

@Composable fun BackupsScreen(app: AppState) {
    val bl by live(app, "/api/v1/backups", 5_000)
    val legacyStatus by live(app, "/api/v1/backup", 15_000)
    val jobs = bl?.optJSONArray("jobs").objs()
    val legacy = bl?.optJSONObject("legacy")
    Page("Backups", app::back, listOf(TopAction(Icons.Rounded.Add, "New backup") { if (app.isAdmin) app.go(Route.BackupWizard(null)) else app.toast("View-only access") })) {
        Row(Modifier.padding(horizontal = 30.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Copies of your folders on another drive or a NAS, made on a schedule.", color = N.sub, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp)); HelpButton("backup")
        }
        if (legacy != null) {
            SectionLabel("Your backup script")
            Group {
                val ls = legacyStatus
                val sets = legacy.optJSONArray("sets").objs().joinToString { it.optString("name") }
                Row1("nova-backup", (if (ls?.optBoolean("running") == true) backupLine(ls) else "Last run ${ls?.optString("time")?.ifEmpty { null } ?: "—"}") +
                    " · $sets → ${legacy.optString("dest")}", ls?.optBoolean("running") == true, Icons.Rounded.Description, N.blue)
                RowDivider()
                Row1("Back up now", "Runs your script (it also runs nightly)", false, Icons.Rounded.PlayArrow, N.green, enabled = app.isAdmin,
                    onClick = { app.act("Backup started") { app.api.post("/api/v1/actions/backup") } })
            }
            Text("This one is a script on the server (${legacy.optString("script")}), so Nova shows it but doesn't edit it. Backups you add below run alongside it.",
                color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        }
        SectionLabel(if (legacy != null) "Nova backups" else "Your backups")
        Group {
            if (bl == null) Row1("Loading…", enabled = false)
            else if (jobs.isEmpty()) Row1("Set up your first backup", "Pick what to protect and where to keep the copies — a drive or a NAS", true, Icons.Rounded.AddCircle, N.green,
                onClick = { if (app.isAdmin) app.go(Route.BackupWizard(null)) })
            jobs.forEachIndexed { i, j ->
                if (i > 0) RowDivider()
                val (txt, lvl) = lastText(j)
                Row1(j.optString("name"), (if (j.optBoolean("running")) "Running now…" else txt) + "\n${scheduleText(j.optJSONObject("schedule"))} → ${destText(j.optJSONObject("dest"))}",
                    j.optBoolean("running"), Icons.Rounded.Backup, if (!j.optBoolean("enabled", true)) N.sub else levelColor(lvl, N),
                    onClick = { app.go(Route.Backup(j.optString("id"))) })
            }
        }
    }
}

@Composable fun BackupScreen(app: AppState, id: String) {
    val bl by live(app, "/api/v1/backups", 4_000)
    val j = bl?.optJSONArray("jobs").objs().firstOrNull { it.optString("id") == id }
    var del by remember { mutableStateOf(false) }
    if (j == null) { Page("Backup", app::back) { Text(if (bl == null) "Loading…" else "This backup is gone.", color = N.sub, modifier = Modifier.padding(30.dp)) }; return }
    val (txt, lvl) = lastText(j)
    Page(j.optString("name"), app::back) {
        Group {
            Row1(if (j.optBoolean("running")) "Running now…" else txt, j.optDouble("next", 0.0).takeIf { it > 0 }?.let { "Next: ${whenText(it)}" } ?: scheduleText(j.optJSONObject("schedule")),
                j.optBoolean("running"), if (lvl == "ok") Icons.Rounded.CheckCircle else Icons.Rounded.Info, levelColor(lvl, N))
        }
        if (app.isAdmin) Group {
            Row1("Back up now", null, false, Icons.Rounded.PlayArrow, N.green, enabled = !j.optBoolean("running"), onClick = {
                app.act { val t = app.api.post("/api/v1/backups/$id/run"); app.go(Route.Task(t.optString("id"))) } })
            RowDivider()
            Row1("Browse & restore", "Get back a file or folder from any snapshot", false, Icons.Rounded.Restore, N.blue, onClick = { app.go(Route.BackupBrowse(id)) }) { HelpButton("restore") }
            RowDivider()
            Row1("Edit", null, false, Icons.Rounded.Edit, N.blue, onClick = { app.go(Route.BackupWizard(id)) })
        }
        SectionLabel("What and where")
        Group {
            Row1("Folders", j.optJSONArray("sources").strs().joinToString("\n")); RowDivider()
            Row1("Copies go to", destText(j.optJSONObject("dest")) + (j.optJSONObject("last")?.optString("mode")?.takeIf { it == "mirror" }?.let { " · one copy + versions" } ?: "")); RowDivider()
            val k = j.optJSONObject("keep")
            Row1("Kept", listOfNotNull(k?.optInt("hourly")?.takeIf { it > 0 }?.let { "$it hourly" }, "${k?.optInt("daily")} daily", "${k?.optInt("weekly")} weekly", "${k?.optInt("monthly")} monthly").joinToString(" · ")) { HelpButton("keep") }
            RowDivider()
            Row1("Missing-drive protection", if (j.optInt("guard_pct") > 0) "Skips a folder that loses over ${j.optInt("guard_pct")}% of its files" else "Off") { HelpButton("guard") }
        }
        val hist = j.optJSONArray("history").objs()
        if (hist.isNotEmpty()) {
            SectionLabel("History")
            Group {
                hist.take(15).forEachIndexed { i, h ->
                    if (i > 0) RowDivider()
                    val s = h.optJSONObject("stats")
                    Row1(whenText(h.optDouble("t")) + " · " + (if (h.optBoolean("ok")) "OK" else if (h.optBoolean("partial")) "Partly" else "Failed"),
                        if (h.has("error")) h.optString("error") else "${s?.optInt("transferred") ?: 0} files changed · ${bytesHuman(s?.optLong("sent") ?: 0)} copied · ${h.optInt("duration")} s",
                        false, null)
                }
            }
        }
        if (app.isAdmin) Group { Row1("Delete this backup", "Stops it running; the copies already made stay on the drive", false, Icons.Rounded.DeleteOutline, N.red, onClick = { del = true }) }
    }
    if (del) OneDialog({ del = false }, "Delete ${j.optString("name")}?", "It won't run again. The snapshots already on ${destText(j.optJSONObject("dest"))} are kept — delete them there if you don't need them.",
        listOf(DialogButton("Cancel") { del = false }, DialogButton("Delete", N.red) { del = false; app.act { app.api.delete("/api/v1/backups/$id"); app.back() } }))
}

// ── the backup wizard ───────────────────────────────────────────────────────────────
@Composable fun BackupWizardScreen(app: AppState, r: Route.BackupWizard) {
    val sug by live(app, "/api/v1/backups/suggest")
    val bl by live(app, "/api/v1/backups")
    val existing = r.id?.let { id -> bl?.optJSONArray("jobs").objs().firstOrNull { it.optString("id") == id } }
    var loaded by remember { mutableStateOf(r.id == null) }
    var step by remember { mutableIntStateOf(0) }
    var sources by remember { mutableStateOf(r.sources.toSet()) }
    var custom by remember { mutableStateOf("") }
    var destType by remember { mutableStateOf("local") }
    var destPath by remember { mutableStateOf(r.dest ?: "") }
    var host by remember { mutableStateOf("") }; var share by remember { mutableStateOf("") }; var subdir by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    var tested by remember { mutableStateOf<String?>(null) }; var testing by remember { mutableStateOf(false) }
    var sched by remember { mutableIntStateOf(0) }                       // 0 daily, 1 every N h, 2 manual
    var hour by remember { mutableIntStateOf(3) }; var minute by remember { mutableIntStateOf(30) }
    var days by remember { mutableStateOf((0..6).toSet()) }
    var every by remember { mutableIntStateOf(6) }
    var daily by remember { mutableFloatStateOf(14f) }; var weekly by remember { mutableFloatStateOf(8f) }; var monthly by remember { mutableFloatStateOf(12f) }
    var name by remember { mutableStateOf("") }
    var guard by remember { mutableStateOf(true) }
    var always by remember { mutableStateOf(false) }
    LaunchedEffect(existing) {
        if (existing != null && !loaded) {
            loaded = true
            sources = existing.optJSONArray("sources").strs().toSet(); name = existing.optString("name")
            val d = existing.optJSONObject("dest")!!; destType = d.optString("type")
            destPath = d.optString("path"); host = d.optString("host"); share = d.optString("share"); subdir = d.optString("subdir"); user = d.optString("user")
            val s = existing.optJSONObject("schedule")!!
            sched = if (s.optBoolean("manual")) 2 else if (s.optInt("every_hours") > 0) 1 else 0
            every = s.optInt("every_hours", 6).takeIf { it > 0 } ?: 6
            s.optString("time").takeIf { it.length == 5 }?.let { hour = it.take(2).toInt(); minute = it.takeLast(2).toInt() }
            s.optJSONArray("days")?.let { a -> days = List(a.length()) { a.getInt(it) }.toSet() }
            existing.optJSONObject("keep")?.let { daily = it.optInt("daily").toFloat(); weekly = it.optInt("weekly").toFloat(); monthly = it.optInt("monthly").toFloat() }
            guard = existing.optInt("guard_pct", 20) > 0; always = existing.optString("notify") == "always"
        }
    }
    val srcList = sug?.optJSONArray("sources").objs()
    val dests = sug?.optJSONArray("destinations").objs()
    fun job(): JSONObject {
        val dest = if (destType == "local") JSONObject().put("type", "local").put("path", destPath)
            else JSONObject().put("type", destType).put("host", host.trim()).put("share", share.trim().trim('/')).put("subdir", subdir.trim())
                .apply { if (destType == "smb") { put("user", user.trim()); if (pass.isNotEmpty() || existing == null) put("password", pass) } }
        val schedule = when (sched) { 2 -> JSONObject().put("manual", true); 1 -> JSONObject().put("every_hours", every)
            else -> JSONObject().put("time", "%02d:%02d".format(hour, minute)).put("days", JSONArray(days.sorted())) }
        return JSONObject().put("name", name.trim()).put("sources", JSONArray(sources.toList())).put("dest", dest).put("schedule", schedule)
            .put("keep", JSONObject().put("daily", daily.toInt()).put("weekly", weekly.toInt()).put("monthly", monthly.toInt()))
            .put("guard_pct", if (guard) 20 else 0).put("notify", if (always) "always" else "failures").apply { existing?.let { put("id", it.optString("id")) } }
    }
    LaunchedEffect(step) {
        if (step == 4 && name.isEmpty()) name = (srcList.firstOrNull { it.optString("path") in sources }?.optString("label") ?: sources.firstOrNull()?.substringAfterLast('/') ?: "Backup")
            .replaceFirstChar { it.uppercase() }.filter { it.isLetterOrDigit() || it in " -_" }.take(40)
    }
    val steps = listOf("What", "Where", "When", "Keep", "Name")
    val ok = when (step) {
        0 -> sources.isNotEmpty()
        1 -> if (destType == "local") destPath.isNotEmpty() else host.isNotBlank() && share.isNotBlank()
        2 -> sched != 0 || days.isNotEmpty()
        4 -> name.isNotBlank()
        else -> true
    }
    Box(Modifier.fillMaxSize()) {
        Page(if (existing != null) "Edit backup" else "New backup", { if (step > 0) step-- else app.back() }, bottom = 120.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 6.dp)) {
                steps.forEachIndexed { i, _ ->
                    Box(Modifier.weight(1f).padding(end = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(if (i <= step) N.blue else N.divider))
                }
            }
            Text("Step ${step + 1} of 5 · ${steps[step]}", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp))
            when (step) {
                0 -> {
                    SectionHelp("What to back up", "backup")
                    Group {
                        if (sug == null) Row1("Looking at your server…", enabled = false)
                        srcList.forEachIndexed { i, s ->
                            if (i > 0) RowDivider()
                            val p = s.optString("path"); val on = p in sources
                            Row1(s.optString("label"), "${s.optString("why")}${if (!s.isNull("size")) " · ${bytesHuman(s.optLong("size"))}" else ""}\n$p", on,
                                Icons.Rounded.Folder, N.blue, onClick = { sources = if (on) sources - p else sources + p }) { OneRadio(on) }
                        }
                        (sources - srcList.map { it.optString("path") }.toSet()).forEach { p ->
                            RowDivider(); Row1(p, "Added by you", true, Icons.Rounded.Folder, N.blue, onClick = { sources = sources - p }) { OneRadio(true) }
                        }
                    }
                    Group {
                        Column(Modifier.padding(16.dp)) {
                            OneTextField(custom, { custom = it.take(300) }, "Another folder, e.g. /srv/music", Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            PillButton("Add folder", custom.startsWith("/")) { sources = sources + custom.trimEnd('/').ifEmpty { "/" }; custom = "" }
                        }
                    }
                }
                1 -> {
                    SectionHelp("Where the copies go", "nas")
                    Segmented(listOf("A drive", "NAS (SMB)", "NAS (NFS)"), listOf("local", "smb", "nfs").indexOf(destType)) { destType = listOf("local", "smb", "nfs")[it]; tested = null }
                    if (destType == "local") Group {
                        if (dests.isEmpty()) Row1("No other drive found", "Set one up first: Storage & hardware → Set up drives → A backup drive", enabled = false,
                            onClick = { app.go(Route.StorageWizard("backup")) })
                        dests.forEachIndexed { i, d ->
                            if (i > 0) RowDivider()
                            val p = d.optString("path"); val on = destPath == p
                            Row1(d.optString("label"), "$p · ${bytesHuman(d.optLong("free"))} free" + if (d.optBoolean("backup")) " · backup drive" else "", on,
                                Icons.Rounded.Storage, if (d.optBoolean("backup")) N.green else N.blue, onClick = { destPath = p }) { OneRadio(on) }
                        }
                        RowDivider()
                        Row1("Set up a new backup drive", "Erase a spare drive and use it for backups", false, Icons.Rounded.AddCircle, N.green, onClick = { app.go(Route.StorageWizard("backup")) })
                    } else Group {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OneTextField(host, { host = it.trim().take(120); tested = null }, "NAS address (e.g. 192.168.1.20 or nas.local)", Modifier.fillMaxWidth())
                            OneTextField(share, { share = it.take(120); tested = null }, if (destType == "smb") "Share name (e.g. backups)" else "Export path (e.g. volume1/backups)", Modifier.fillMaxWidth())
                            OneTextField(subdir, { subdir = it.take(120) }, "Folder inside it (optional)", Modifier.fillMaxWidth())
                            if (destType == "smb") {
                                OneTextField(user, { user = it.take(64); tested = null }, "User name", Modifier.fillMaxWidth())
                                OneTextField(pass, { pass = it.take(128); tested = null }, if (existing != null) "Password (leave empty to keep it)" else "Password", Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), visualTransformation = PasswordVisualTransformation())
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PillButton(if (testing) "Testing…" else "Test connection", !testing && host.isNotBlank() && share.isNotBlank()) {
                                    testing = true; tested = null
                                    app.scope.launchCatching({ tested = "✗ " + it }) {
                                        val res = app.api.post("/api/v1/backups/test", job().put("name", "test").put("sources", JSONArray(sources.toList().ifEmpty { listOf("/etc") })))
                                        tested = "✓ Connected · ${bytesHuman(res.optLong("free"))} free" + if (!res.optBoolean("hardlinks")) " · no snapshots on this share (one copy + versions)" else ""
                                    }.invokeOnCompletion { testing = false }
                                }
                            }
                            tested?.let { Text(it, color = if (it.startsWith("✓")) N.green else N.red, fontSize = 14.sp) }
                        }
                    }
                }
                2 -> {
                    SectionLabel("When")
                    Segmented(listOf("Daily", "Every few hours", "Only manually"), sched) { sched = it }
                    when (sched) {
                        0 -> {
                            Box(Modifier.padding(vertical = 8.dp)) { TimeWheels(hour, minute) { a, b -> hour = a; minute = b } }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d -> DayDot(d, i in days) { days = if (i in days) days - i else days + i } }
                            }
                            Text("If the server is off at that time, it runs as soon as it's back on.", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 10.dp))
                        }
                        1 -> Group { listOf(1, 2, 3, 4, 6, 8, 12).forEachIndexed { i, h -> if (i > 0) RowDivider(); Row1("Every $h hour${if (h > 1) "s" else ""}", null, every == h, onClick = { every = h }) { OneRadio(every == h) } } }
                        else -> Text("It runs only when you tap Back up now.", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(30.dp))
                    }
                }
                3 -> {
                    SectionHelp("How long to keep old copies", "keep")
                    Group {
                        SliderRow("Daily copies", daily, 1f..60f, "${daily.toInt()} days", steps = 58, onChange = { daily = it }) {}
                        SliderRow("Weekly copies", weekly, 0f..52f, "${weekly.toInt()} weeks", steps = 51, onChange = { weekly = it }) {}
                        SliderRow("Monthly copies", monthly, 0f..60f, "${monthly.toInt()} months", steps = 59, onChange = { monthly = it }) {}
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Smart (14 · 8 · 12)", color = N.text) { daily = 14f; weekly = 8f; monthly = 12f }
                        PillButton("Just a week", color = N.text) { daily = 7f; weekly = 0f; monthly = 0f }
                        PillButton("A long history", color = N.text) { daily = 30f; weekly = 26f; monthly = 36f }
                    }
                    Group {
                        SwitchRow("Missing-drive protection", "Don't back up a folder that suddenly lost most of its files", guard) { guard = it }
                        RowDivider()
                        SwitchRow("Tell me after every backup", if (always) "A notification each time" else "Only when something goes wrong", always) { always = it }
                    }
                }
                else -> {
                    SectionLabel("Name")
                    Group { Box(Modifier.padding(16.dp)) { OneTextField(name, { name = it.filter { c -> c.isLetterOrDigit() || c in " -_" }.take(40) }, "e.g. Photos", Modifier.fillMaxWidth()) } }
                    SectionLabel("Summary")
                    Group {
                        Row1("Backs up", sources.joinToString("\n")); RowDivider()
                        Row1("To", if (destType == "local") destPath else destText(job().optJSONObject("dest"))); RowDivider()
                        Row1("When", scheduleText(job().optJSONObject("schedule"))); RowDivider()
                        Row1("Keeps", "${daily.toInt()} daily · ${weekly.toInt()} weekly · ${monthly.toInt()} monthly")
                    }
                }
            }
        }
        CancelSavePill({ if (step > 0) step-- else app.back() }, {
            if (step < 4) step++ else app.act {
                val res = app.api.post("/api/v1/backups", job())
                val id = res.optJSONObject("job")?.optString("id") ?: ""
                app.back()
                if (existing == null && id.isNotEmpty()) { val t = app.api.post("/api/v1/backups/$id/run"); app.go(Route.Task(t.optString("id"))); app.toast("Saved — first backup started") }
                else app.toast("Saved")
            }
        }, ok, saveLabel = if (step < 4) "Next" else if (existing != null) "Save" else "Save and back up now", cancelLabel = if (step > 0) "Back" else "Cancel",
            modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable private fun DayDot(d: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) N.blue else N.card).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(d, color = if (on) Color.White else N.sub, fontWeight = FontWeight.Bold)
    }
}

/** launch, with errors going to [onError] instead of a toast. */
fun kotlinx.coroutines.CoroutineScope.launchCatching(onError: (String) -> Unit, block: suspend () -> Unit) =
    launch { try { block() } catch (e: Exception) { onError(e.message ?: "failed") } }

// ── browse & restore ────────────────────────────────────────────────────────────────
@Composable fun BackupBrowseScreen(app: AppState, r: Route.BackupBrowse) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(r) {
        data = null; err = null
        try {
            data = if (r.snap.isEmpty()) app.api.get("/api/v1/backups/${r.id}/snapshots")
                   else app.api.get("/api/v1/backups/${r.id}/browse?snap=${r.snap}&path=${java.net.URLEncoder.encode(r.path.ifEmpty { "/" }, "UTF-8")}")
        } catch (e: Exception) { err = e.message }
    }
    val title = if (r.snap.isEmpty()) "Pick a snapshot" else r.path.ifEmpty { "/" }.substringAfterLast('/').ifEmpty { r.snap }
    Page(title, app::back, if (r.snap.isNotEmpty() && r.path.length > 1 && app.isAdmin) listOf(TopAction(Icons.Rounded.Restore, "Restore this folder") { pick = r.path }) else emptyList()) {
        if (r.snap.isNotEmpty()) Text("Snapshot ${r.snap.replace('_', ' ')} · ${r.path.ifEmpty { "/" }}", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp))
        Group {
            when {
                err != null -> Row1("Couldn't open the backup", err, false, Icons.Rounded.Error, N.red)
                data == null -> Row1("Opening…", enabled = false)
                r.snap.isEmpty() -> data!!.optJSONArray("snapshots").objs().forEachIndexed { i, s ->
                    if (i > 0) RowDivider()
                    Row1(if (s.optString("id") == "current") "Latest copy" else whenText(s.optDouble("t")), s.optString("id").replace('_', ' '), i == 0, Icons.Rounded.History, N.blue,
                        onClick = { app.go(Route.BackupBrowse(r.id, s.optString("id"), "")) })
                }
                else -> {
                    val items = data!!.optJSONArray("items").objs()
                    if (items.isEmpty()) Row1("Empty folder", enabled = false)
                    items.forEachIndexed { i, it ->
                        if (i > 0) RowDivider()
                        val p = (data!!.optString("path").trimEnd('/')) + "/" + it.optString("name")
                        Row1(it.optString("name"), if (it.optBoolean("dir")) "Folder" else "${bytesHuman(it.optLong("size"))} · ${whenText(it.optDouble("mtime"))}", false,
                            if (it.optBoolean("dir")) Icons.Rounded.Folder else Icons.Rounded.Description, N.blue,
                            onClick = { if (it.optBoolean("dir")) app.go(Route.BackupBrowse(r.id, r.snap, p)) else if (app.isAdmin) pick = p }) {
                            if (app.isAdmin) androidx.compose.material3.Icon(Icons.Rounded.Restore, "Restore", tint = N.sub, modifier = Modifier.clickable { pick = p })
                        }
                    }
                }
            }
        }
    }
    pick?.let { p ->
        OneDialog({ pick = null }, "Restore ${p.substringAfterLast('/')}?", "From the snapshot of ${r.snap.replace('_', ' ')}.") {
            DialogChoice("Put it back where it was", "Files already there are left alone — nothing newer is overwritten", false) {
                pick = null; app.act { val t = app.stepUp("Restore ${p.substringAfterLast('/')}", "POST", "/api/v1/backups/${r.id}/restore",
                    JSONObject().put("snapshot", r.snap).put("path", p).put("to", "original")); app.go(Route.Task(t.optString("id"))) } }
            DialogChoice("Next to the original", "In a restored-${r.snap.take(10)} folder beside it, to compare first", false) {
                pick = null; app.act { val t = app.stepUp("Restore ${p.substringAfterLast('/')}", "POST", "/api/v1/backups/${r.id}/restore",
                    JSONObject().put("snapshot", r.snap).put("path", p).put("to", "beside")); app.go(Route.Task(t.optString("id"))) } }
            DialogChoice("Cancel", null, false) { pick = null }
        }
    }
}
