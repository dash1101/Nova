package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

// Storage map, suggestions, pools, the drive setup wizard, and the background-task screen.

fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
fun JSONObject.u(): JSONObject? = optJSONObject("usage")

fun poolKind(t: String) = when (t) {
    "combine" -> "Combined drives"; "raid0" -> "Stripe (RAID 0)"; "raid1" -> "Mirror (RAID 1)"; "raid5" -> "Parity (RAID 5)"
    "raid6" -> "Double parity (RAID 6)"; "raid10" -> "Mirror + stripe (RAID 10)"; else -> t.uppercase()
}
private fun levelIcon(l: String) = when (l) { "critical" -> Icons.Rounded.Error; "warning" -> Icons.Rounded.Warning; else -> Icons.Rounded.Lightbulb }
fun driveTitle(d: JSONObject) = "${d.optString("size_text")} ${d.optString("model").ifEmpty { d.optString("name") }}".trim()

/** The top of Storage & hardware: what's running, suggestions, pools, and the ways in. */
@Composable fun StorageOverview(app: AppState) {
    val st by live(app, "/api/v1/storage", 20_000)
    val tasks by live(app, "/api/v1/tasks", 4_000)
    val running = tasks?.optJSONArray("tasks").objs().filter { it.optString("state") == "running" }
    if (running.isNotEmpty()) {
        SectionLabel("Working on it")
        Group { running.forEachIndexed { i, t -> if (i > 0) RowDivider(); TaskRow(app, t) } }
    }
    val sug = st?.optJSONArray("suggestions").objs()
    if (sug.isNotEmpty()) {
        SectionHelp("Suggested", "suggestions")
        sug.forEach { s -> SuggestionCard(app, s) }
    }
    val pools = st?.optJSONArray("pools").objs()
    SectionHelp("Pools", "pool")
    Group {
        pools.forEachIndexed { i, p -> if (i > 0) RowDivider(); PoolRow(app, p) }
        if (pools.isNotEmpty()) RowDivider()
        Row1("Set up drives", "Combine drives, make a RAID, or a backup drive — a step-by-step guide", true, Icons.Rounded.AddCircle, N.green,
            onClick = { if (app.isAdmin) app.go(Route.StorageWizard()) else app.toast("This ${DeviceForm.noun} has view-only access") })
    }
    Group {
        val legacy = st?.optJSONObject("legacy_backup"); val nj = st?.optJSONArray("jobs")?.length() ?: 0
        Row1("Backups", listOfNotNull(legacy?.let { "your backup script" }, if (nj > 0) "$nj Nova backup${if (nj > 1) "s" else ""}" else null)
            .joinToString(" + ").ifEmpty { "Nothing backed up yet — set one up" }, true, Icons.Rounded.Backup, N.blue, onClick = { app.go(Route.Backups) })
        RowDivider()
        Row1("Diagnostics", "Internet & drive speed, CPU and memory tests, ping and more", false, Icons.Rounded.Speed, Color(0xFF64D2FF),
            onClick = { app.go(Route.Diagnostics) })
    }
}

@Composable fun TaskRow(app: AppState, t: JSONObject) {
    Column(Modifier.fillMaxWidth().clickable { app.go(Route.Task(t.optString("id"))) }.padding(horizontal = 22.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.optString("title"), color = N.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("${t.optDouble("pct", 0.0).toInt()}%", color = N.link, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp)); ProgressBar(t.optDouble("pct", 0.0).toFloat() / 100f)
        Text(t.optString("note").ifEmpty { t.optString("step") }, color = N.sub, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable private fun SuggestionCard(app: AppState, s: JSONObject) {
    val lvl = s.optString("level"); val c = levelColor(lvl, N)
    Group {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(levelIcon(lvl), null, tint = c, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.optString("title"), color = N.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(s.optString("detail"), color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        val acts = s.optJSONArray("actions").objs()
        if (acts.isNotEmpty()) FlowRow(Modifier.padding(start = 66.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            acts.forEach { a -> PillButton(a.optString("label")) { suggestionAction(app, a) } }
        } else Spacer(Modifier.height(14.dp))
    }
}

fun suggestionAction(app: AppState, a: JSONObject) {
    if (!app.isAdmin) { app.toast("This ${DeviceForm.noun} has view-only access"); return }
    when (a.optString("action")) {
        "wizard" -> app.go(Route.StorageWizard(a.optString("goal"), a.optJSONArray("drives").strs(), a.optString("pool").ifEmpty { null }))
        "backup-wizard" -> app.go(Route.BackupWizard(null, a.optJSONArray("sources").strs()))
        "task" -> app.act {
            val t = app.api.post("/api/v1/storage/task/${a.optString("kind")}", JSONObject().put("spec", a.optJSONObject("spec") ?: JSONObject()))
            app.go(Route.Task(t.optString("id")))
        }
    }
}

@Composable private fun PoolRow(app: AppState, p: JSONObject) {
    val u = p.u(); val deg = p.optBoolean("degraded"); val sync = if (p.isNull("sync")) null else p.optDouble("sync")
    Column(Modifier.fillMaxWidth().clickable { app.go(Route.Pool(p.optString("id"))) }.padding(horizontal = 22.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.optString("name").replaceFirstChar { it.uppercase() }, color = N.text, fontSize = 17.sp)
                Text("${poolKind(p.optString("type"))} · ${p.optJSONArray("members")?.length() ?: 0} drives · ${p.optString("mount")}", color = N.sub, fontSize = 13.sp)
            }
            Text(when { deg -> "Missing a drive"; sync != null -> "Building ${sync.toInt()}%"; p.optInt("redundancy") > 0 -> "Protected"; else -> "OK" },
                color = when { deg -> N.red; sync != null -> N.amber; else -> N.green }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (u != null) {
            Spacer(Modifier.height(8.dp))
            val frac = u.optLong("used").toFloat() / u.optLong("total").coerceAtLeast(1)
            UsageBar(frac, if (frac > 0.95f) N.red else if (frac > 0.85f) N.amber else N.blue)
            Text("${bytesHuman(u.optLong("free"))} free of ${bytesHuman(u.optLong("total"))}" +
                (p.optJSONArray("used_by").strs().takeIf { it.isNotEmpty() }?.let { " · used by ${it.take(3).joinToString()}" } ?: ""),
                color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** One pool: members, health, and what you can do with it. */
@Composable fun PoolScreen(app: AppState, id: String) {
    val stLive = live(app, "/api/v1/storage", 10_000)
    val st = stLive.value
    val p = st?.optJSONArray("pools").objs().firstOrNull { it.optString("id") == id }
    var remove by remember { mutableStateOf(false) }
    var erase by remember { mutableStateOf(false) }
    if (p == null) { Page("Pool", app::back) { Text(if (st == null) "Loading…" else "This pool is gone.", color = N.sub, modifier = Modifier.padding(30.dp)) }; return }
    val t = p.optString("type"); val combine = t == "combine"
    Page(p.optString("name").replaceFirstChar { it.uppercase() }, app::back) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(if (combine) Icons.Rounded.Layers else Icons.Rounded.Shield, null, tint = N.blue, modifier = Modifier.size(72.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(poolKind(t), color = N.text, fontSize = 18.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); HelpButton(if (combine) "combine" else t)
            }
            Text(p.optString("mount").ifEmpty { "Not mounted" }, color = N.sub)
        }
        p.u()?.let { u ->
            Group {
                Column(Modifier.padding(20.dp)) {
                    val frac = u.optLong("used").toFloat() / u.optLong("total").coerceAtLeast(1)
                    UsageBar(frac, if (frac > 0.95f) N.red else if (frac > 0.85f) N.amber else N.blue)
                    Text("${bytesHuman(u.optLong("used"))} used · ${bytesHuman(u.optLong("free"))} free of ${bytesHuman(u.optLong("total"))}", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        SectionLabel("Safety")
        Group {
            val red = p.optInt("redundancy")
            Row1(when {
                p.optBoolean("degraded") -> "Missing a drive — replace it now"
                red == 0 && combine -> "If a drive fails, its files are lost"
                red == 0 -> "If any drive fails, everything is lost"
                else -> "Survives $red drive${if (red > 1) "s" else ""} failing"
            }, if (!p.isNull("sync")) "Building redundancy: ${p.optDouble("sync").toInt()}% (usable meanwhile)" else "RAID and pools aren't backups — keep a copy elsewhere too",
                red > 0 && !p.optBoolean("degraded"), if (red > 0) Icons.Rounded.VerifiedUser else Icons.Rounded.Warning, if (red > 0) N.green else N.amber) { HelpButton("raid-not-backup") }
            p.optJSONArray("used_by").strs().takeIf { it.isNotEmpty() }?.let { RowDivider(); Row1("Used by", it.joinToString(), false, Icons.Rounded.Apps, N.blue) }
        }
        SectionLabel("Drives in it")
        Group {
            val members = p.optJSONArray("members").strs()
            members.forEachIndexed { i, m ->
                if (i > 0) RowDivider()
                val d = st?.optJSONArray("drives").objs().firstOrNull { dr -> m in dr.optJSONArray("mounts").strs() || dr.optJSONArray("partitions").objs().any { pt -> pt.optString("name") == m } }
                val pu = d?.optJSONArray("partitions").objs().firstOrNull { pt -> m in pt.optJSONArray("mounts").strs() || pt.optString("name") == m }?.u()
                Row1(d?.let { driveTitle(it) } ?: m, listOfNotNull(m, pu?.let { "${bytesHuman(it.optLong("free"))} free" }, if (m in p.optJSONArray("failed").strs()) "FAILED" else null).joinToString(" · "),
                    false, if (d?.optBoolean("ssd") == true) Icons.Rounded.SdStorage else Icons.Rounded.Storage, N.blue,
                    onClick = d?.let { { app.go(Route.Drive(it.optString("serial"))) } })
            }
        }
        if (app.isAdmin) {
            Group {
                Row1(if (combine) "Add a drive" else "Add or replace a drive", if (combine) "Makes the pool bigger" else "A spare, or a replacement for a failed drive",
                    true, Icons.Rounded.AddCircle, N.green, onClick = { app.go(Route.StorageWizard("grow", emptyList(), id)) })
                RowDivider()
                Row1("Remove this pool", if (combine) "The drives keep their files and stay mounted on their own" else "Stops the array (you can also erase its drives)",
                    false, Icons.Rounded.DeleteOutline, N.red, onClick = { remove = true })
            }
        }
    }
    if (remove) OneDialog({ remove = false }, "Remove ${p.optString("name")}?",
        if (combine) "The combined folder ${p.optString("mount")} goes away. Each drive keeps its files and stays mounted on its own. Apps using ${p.optString("mount")} must be stopped first."
        else "The array is stopped and ${p.optString("mount")} unmounted. Apps using it must be stopped first.",
        listOf(DialogButton("Cancel") { remove = false }, DialogButton("Remove", N.red) {
            remove = false
            app.act {
                val r = app.stepUp("Remove ${p.optString("name")}", "POST", "/api/v1/storage/task/pool-remove",
                    JSONObject().put("spec", JSONObject().put("pool", id).put("confirm", JSONArray(listOf(id))).put("erase", erase)))
                app.back(); app.go(Route.Task(r.optString("id")))
            }
        })) {
        if (!combine) Box(Modifier.padding(horizontal = 8.dp)) { SwitchRow("Also erase the drives", "Wipes the RAID data so they can be reused", erase) { erase = it } }
    }
}

// ── the setup wizard ──────────────────────────────────────────────────────────────────
private data class Goal(val id: String, val title: String, val sub: String, val icon: ImageVector, val help: String)
private val GOALS = listOf(
    Goal("combine", "One big drive", "Add drives together into one large folder. Mix any sizes, add more later.", Icons.Rounded.Layers, "combine"),
    Goal("safe", "Safe if a drive fails", "Mirror or parity (RAID) — keeps working when a drive dies.", Icons.Rounded.Shield, "raid1"),
    Goal("backup", "A backup drive", "One drive just for backups of your other folders.", Icons.Rounded.Backup, "backup"),
    Goal("single", "Just one drive", "Set up a drive as a normal folder.", Icons.Rounded.Storage, "mount"),
    Goal("fast", "As fast as possible", "Stripe drives together (RAID 0). No protection at all.", Icons.Rounded.Bolt, "raid0"),
    Goal("grow", "Add to a pool", "Make a pool bigger, or replace a failed drive in an array.", Icons.Rounded.AddCircle, "pool"),
)
private fun usable(level: String, sizes: List<Long>): Long {
    if (sizes.isEmpty()) return 0
    val mn = sizes.min(); val n = sizes.size
    return when (level) { "combine" -> sizes.sum(); "raid0" -> mn * n; "raid1" -> mn; "raid5" -> mn * (n - 1); "raid6" -> mn * (n - 2); "raid10" -> mn * (n / 2); else -> sizes.first() }
}
private fun survives(level: String, n: Int) = when (level) { "raid1" -> n - 1; "raid5", "raid10" -> 1; "raid6" -> 2; else -> 0 }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun StorageWizardScreen(app: AppState, r: Route.StorageWizard) {
    val st by live(app, "/api/v1/storage")
    var goal by remember { mutableStateOf(r.goal) }
    var step by remember { mutableIntStateOf(if (r.goal == null) 0 else 1) }
    var picked by remember { mutableStateOf(r.drives.toSet()) }
    var keep by remember { mutableStateOf(setOf<String>()) }            // combine: drives added with their files
    var level by remember { mutableStateOf("raid1") }
    var pool by remember { mutableStateOf(r.pool) }
    var name by remember { mutableStateOf("") }
    var fs by remember { mutableStateOf("ext4") }
    var policy by remember { mutableStateOf("mfs") }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val drives = st?.optJSONArray("drives").objs()
    val pools = st?.optJSONArray("pools").objs()
    val free = drives.filter { it.optBoolean("erasable") }
    val dataDrives = drives.filter { it.optJSONArray("roles").strs() == listOf("data") && it.optJSONArray("mounts").length() > 0 }
    val g = GOALS.firstOrNull { it.id == goal }
    val poolObj = pools.firstOrNull { it.optString("id") == pool }
    val effLevel = when (goal) { "combine" -> "combine"; "fast" -> "raid0"; "safe" -> level; "grow" -> if (poolObj?.optString("type") == "combine") "combine" else "raid1"; else -> "single" }
    val chosen = drives.filter { it.optString("serial") in picked }
    val keepDrives = drives.filter { it.optString("serial") in keep }
    val n = chosen.size + keepDrives.size
    val oneDrive = goal in listOf("single", "backup", "grow")
    LaunchedEffect(goal, st) {
        if (name.isEmpty() && st != null) {
            val base = when (goal) { "backup" -> "backup"; "single" -> "storage"; "combine" -> "pool"; "fast" -> "scratch"; else -> "array" }
            val taken = (drives.flatMap { it.optJSONArray("mounts").strs() } + pools.map { it.optString("mount") }).toSet()
            name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { "/mnt/$it" !in taken }
        }
    }
    val steps = if (goal == "grow") listOf("Goal", "Pool & drive", "Confirm") else listOf("Goal", "Drives", "Details", "Confirm")
    val last = steps.lastIndex
    fun canNext() = when (steps[step]) {
        "Goal" -> goal != null
        "Drives" -> when (goal) { "single", "backup" -> chosen.size == 1; "combine" -> n >= 2; "fast" -> chosen.size >= 2
            else -> chosen.size >= mapOf("raid1" to 2, "raid5" to 3, "raid6" to 4, "raid10" to 4)[level]!! && (level != "raid10" || chosen.size % 2 == 0) }
        "Pool & drive" -> poolObj != null && (chosen.size == 1 || (keepDrives.size == 1 && effLevel == "combine"))
        "Details" -> Regex("[a-z0-9][a-z0-9_-]{0,23}").matches(name)
        else -> true
    }
    val erasing = chosen.isNotEmpty()
    fun submit() {
        val (kind, spec) = when (goal) {
            "single", "backup" -> "format" to JSONObject().put("name", name).put("fs", fs)
            "combine" -> "combine" to JSONObject().put("name", name).put("fs", fs).put("policy", policy).put("keep", JSONArray(keep.toList()))
            "grow" -> "pool-add" to JSONObject().put("pool", pool).apply { if (keep.isNotEmpty()) put("keep", keep.first()) }
            else -> "raid" to JSONObject().put("name", name).put("fs", fs).put("level", effLevel)
        }
        spec.put("drives", JSONArray(picked.toList())).put("confirm", JSONArray(picked.toList()))
        busy = true
        app.act {
            try {
                val body = JSONObject().put("spec", spec)
                val t = if (erasing) app.stepUp("Erase ${chosen.size} drive${if (chosen.size > 1) "s" else ""} and set up", "POST", "/api/v1/storage/task/$kind", body)
                        else app.api.post("/api/v1/storage/task/$kind", body)
                app.back(); app.go(Route.Task(t.optString("id"), then = if (goal == "backup") "backup:/mnt/$name" else null))
            } finally { busy = false }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Page(if (step == 0) "Set up drives" else g?.title ?: "Set up drives", { if (step > 0 && r.goal == null) step-- else app.back() }, bottom = 120.dp) {
            StepDots(steps, step)
            when (steps[step]) {
                "Goal" -> {
                    Text("What would you like?", color = N.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
                    GOALS.forEach { gg ->
                        val disabled = gg.id == "grow" && pools.isEmpty()
                        Group {
                            Row1(gg.title, if (disabled) "No pools yet" else gg.sub, goal == gg.id, gg.icon, if (gg.id == "fast") N.amber else N.blue, enabled = !disabled,
                                onClick = { goal = gg.id; picked = emptySet(); keep = emptySet(); name = ""; step = 1 }) { HelpButton(gg.help) }
                        }
                    }
                    Text("Nova only offers drives that are safe to set up: never the system drive, or anything that's mounted or already in a pool.",
                        color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 10.dp))
                }
                "Drives" -> {
                    if (goal == "safe") {
                        SectionHelp("Kind of protection", level)
                        Group {
                            listOf("raid1" to "Mirror — 2+ drives, same copy on each", "raid5" to "Parity — 3+ drives, lose one drive's space",
                                "raid6" to "Double parity — 4+ drives, survives two failing", "raid10" to "Mirror + stripe — 4, 6, 8… drives, fast").forEachIndexed { i, (k, l) ->
                                if (i > 0) RowDivider()
                                Row1(poolKind(k), l, level == k, onClick = { level = k }) { OneRadio(level == k) }
                            }
                        }
                    }
                    SectionHelp(if (oneDrive) "Pick a drive" else "Pick the drives", "erase")
                    Group {
                        if (free.isEmpty() && (goal != "combine" || dataDrives.isEmpty())) Row1("No free drives", "Plug one in, or unmount a drive you no longer use (tap it in Storage & hardware).", enabled = false)
                        free.forEachIndexed { i, d ->
                            if (i > 0) RowDivider()
                            val s = d.optString("serial"); val on = s in picked
                            Row1(driveTitle(d), listOfNotNull(if (d.optBoolean("ssd")) "SSD" else "HDD", d.optString("bus").uppercase().ifEmpty { null },
                                if (d.optBoolean("has_data")) "has old files — will be erased" else "empty").joinToString(" · ") +
                                d.optJSONArray("warnings").strs().firstOrNull()?.let { "\n⚠ $it" }.orEmpty(), on,
                                if (d.optBoolean("ssd")) Icons.Rounded.SdStorage else Icons.Rounded.Storage, if (on) N.red else N.blue,
                                onClick = { picked = if (on) picked - s else if (oneDrive) setOf(s) else picked + s; if (!on) keep = keep - s }) { OneRadio(on) }
                        }
                    }
                    if (goal == "combine" && dataDrives.isNotEmpty()) {
                        SectionHelp("Or add drives with their files", "combine")
                        Group {
                            dataDrives.forEachIndexed { i, d ->
                                if (i > 0) RowDivider()
                                val s = d.optString("serial"); val on = s in keep
                                Row1(driveTitle(d), "${d.optJSONArray("mounts").strs().joinToString()} · kept as it is, nothing erased", on, Icons.Rounded.FolderCopy, N.green,
                                    onClick = { keep = if (on) keep - s else keep + s; picked = picked - s }) { OneRadio(on) }
                            }
                        }
                    }
                    if (n > 0 && !oneDrive) CapacityCard(effLevel, (chosen + keepDrives).map { it.optLong("size") }, chosen + keepDrives)
                }
                "Pool & drive" -> {
                    SectionLabel("Which pool")
                    Group {
                        pools.forEachIndexed { i, p ->
                            if (i > 0) RowDivider()
                            Row1(p.optString("name"), "${poolKind(p.optString("type"))} · ${p.optString("mount")}", pool == p.optString("id"),
                                onClick = { pool = p.optString("id"); picked = emptySet(); keep = emptySet() }) { OneRadio(pool == p.optString("id")) }
                        }
                    }
                    if (poolObj != null) {
                        SectionHelp(if (effLevel == "combine") "Drive to add" else "Spare or replacement drive", "erase")
                        Group {
                            free.forEachIndexed { i, d ->
                                if (i > 0) RowDivider()
                                val s = d.optString("serial"); val on = s in picked
                                Row1(driveTitle(d), if (d.optBoolean("has_data")) "Has old files — will be erased" else "Empty — will be set up", on,
                                    Icons.Rounded.Storage, if (on) N.red else N.blue, onClick = { picked = if (on) emptySet() else setOf(s); keep = emptySet() }) { OneRadio(on) }
                            }
                            if (effLevel == "combine") dataDrives.forEach { d ->
                                RowDivider(); val s = d.optString("serial"); val on = s in keep
                                Row1(driveTitle(d), "${d.optJSONArray("mounts").strs().joinToString()} — add it with its files", on, Icons.Rounded.FolderCopy, N.green,
                                    onClick = { keep = if (on) emptySet() else setOf(s); picked = emptySet() }) { OneRadio(on) }
                            }
                            if (free.isEmpty() && (effLevel != "combine" || dataDrives.isEmpty())) Row1("No free drives", "Plug one in first.", enabled = false)
                        }
                    }
                }
                "Details" -> {
                    SectionHelp("Name", "mount")
                    Group {
                        Column(Modifier.padding(16.dp)) {
                            OneTextField(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c in "-_" }.take(24) }, "e.g. photos", Modifier.fillMaxWidth())
                            Text("Appears as /mnt/${name.ifEmpty { "…" }}", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, start = 6.dp))
                        }
                    }
                    SectionHelp("Filesystem", "fs")
                    Group {
                        val opts = listOf("ext4" to "Recommended — reliable, works everywhere", "xfs" to "Very large drives and big files",
                            "btrfs" to "Checksums + compression — nice for backups") + if (oneDrive) listOf("exfat" to "Also opens on Windows and Mac") else emptyList()
                        opts.forEachIndexed { i, (k, l) -> if (i > 0) RowDivider(); Row1(if (k == "exfat") "exFAT" else k.uppercase().replace("EXT4", "ext4").replace("BTRFS", "Btrfs"), l, fs == k, onClick = { fs = k }) { OneRadio(fs == k) } }
                    }
                    if (goal == "combine") {
                        SectionHelp("Where new files go", "policy")
                        Group {
                            listOf("mfs" to "Most free space (recommended)", "epmfs" to "Keep folders together", "lfs" to "Fill one drive at a time", "pfrd" to "Random, weighted by free space")
                                .forEachIndexed { i, (k, l) -> if (i > 0) RowDivider(); Row1(l, null, policy == k, onClick = { policy = k }) { OneRadio(policy == k) } }
                        }
                    }
                }
                "Confirm" -> {
                    SectionLabel("Summary")
                    Group {
                        Row1(g?.title ?: "", when (goal) { "grow" -> "Into ${poolObj?.optString("name")}"; "safe" -> poolKind(effLevel); else -> null }, true, g?.icon, N.blue)
                        if (goal != "grow") { RowDivider(); Row1("Folder", "/mnt/$name · ${fs.replace("exfat", "exFAT")}") }
                        if (!oneDrive) { RowDivider(); Row1("Space", "${bytesHuman(usable(effLevel, (chosen + keepDrives).map { it.optLong("size") }))} usable · " +
                            survives(effLevel, n).let { s -> if (s == 0) "no drive may fail" else "survives $s failing" }) }
                        if (keepDrives.isNotEmpty()) { RowDivider(); Row1("Kept with their files", keepDrives.joinToString { driveTitle(it) }, false, Icons.Rounded.FolderCopy, N.green) }
                    }
                    if (erasing) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 10.dp).clip(RoundedCornerShape(26.dp))
                            .background(N.red.copy(alpha = 0.12f)).border(1.dp, N.red.copy(alpha = 0.5f), RoundedCornerShape(26.dp)).padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Warning, null, tint = N.red); Spacer(Modifier.width(10.dp))
                                Text("These drives will be erased", color = N.red, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f)); HelpButton("erase")
                            }
                            chosen.forEach { d -> Text("• ${driveTitle(d)} (${d.optString("serial")})" + if (d.optBoolean("has_data")) " — has files on it" else "", color = N.text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp)) }
                            Text("Everything on them is deleted for good. Type ERASE to continue.", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                            OneTextField(typed, { typed = it.take(10) }, "ERASE", Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
        val ready = canNext() && (steps[step] != "Confirm" || !erasing || typed.trim().uppercase() == "ERASE") && !busy
        CancelSavePill({ if (step > 0 && (r.goal == null || step > 1)) step-- else app.back() }, { if (step < last) step++ else submit() }, ready,
            saveLabel = if (step < last) "Next" else if (erasing) "Erase and set up" else "Set up", cancelLabel = if (step > 0 && (r.goal == null || step > 1)) "Back" else "Cancel",
            modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable private fun StepDots(steps: List<String>, at: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { i, s ->
            Box(Modifier.height(6.dp).weight(1f).clip(RoundedCornerShape(3.dp)).background(if (i <= at) N.blue else N.divider))
            if (i < steps.lastIndex) Spacer(Modifier.width(6.dp))
        }
    }
    Text("Step ${at + 1} of ${steps.size} · ${steps[at]}", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp))
}

@Composable private fun CapacityCard(level: String, sizes: List<Long>, ds: List<JSONObject>) {
    val use = usable(level, sizes); val total = sizes.sum(); val s = survives(level, sizes.size)
    Group {
        Column(Modifier.padding(20.dp)) {
            Text("${bytesHuman(use)} usable", color = N.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("from ${sizes.size} drives, ${bytesHuman(total)} in total", color = N.sub, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp)); UsageBar(use.toFloat() / total.coerceAtLeast(1), N.green)
            Spacer(Modifier.height(10.dp))
            Text(when {
                level == "combine" -> "If a drive fails, only the files on it are lost (the rest keep working). Back it up."
                s == 0 -> "If any one drive fails, everything on it is lost."
                else -> "Keeps working if $s drive${if (s > 1) "s" else ""} fail${if (s == 1) "s" else ""}."
            }, color = if (s == 0) N.amber else N.green, fontSize = 14.sp)
            if (level != "combine" && sizes.distinct().size > 1)
                Text("Drives differ in size: RAID uses only ${bytesHuman(sizes.min())} of each (the smallest).", color = N.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            if (level != "combine" && ds.any { it.optString("bus") == "usb" })
                Text("A USB drive can drop out of an array — SATA is safer.", color = N.amber, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            if (level != "combine" && ds.map { it.optBoolean("ssd") }.distinct().size > 1)
                Text("Mixing SSDs and hard drives makes the array as slow as the hard drives.", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// ── one background task: progress, log, result ───────────────────────────────────────
@Composable fun TaskScreen(app: AppState, r: Route.Task) {
    var t by remember { mutableStateOf<JSONObject?>(null) }
    var stopping by remember { mutableStateOf(false) }
    LaunchedEffect(r.id) {
        while (true) {
            runCatching { app.api.get("/api/v1/tasks/${r.id}") }.onSuccess { t = it }
            if (t != null && t!!.optString("state") != "running") break
            delay(1000)
        }
    }
    val x = t
    val state = x?.optString("state") ?: "running"
    Page(x?.optString("title") ?: "Working…", app::back) {
        Group {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (state) {
                        "running" -> OneSpinner(28.dp)
                        "done" -> Icon(Icons.Rounded.CheckCircle, null, tint = N.green, modifier = Modifier.size(30.dp))
                        else -> Icon(if (x?.optBoolean("refused") == true) Icons.Rounded.Block else Icons.Rounded.Error, null, tint = if (state == "stopped") N.sub else N.red, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(when (state) { "running" -> x?.optString("step") ?: "Starting…"; "done" -> "Done"; "stopped" -> "Stopped"
                        else -> if (x?.optBoolean("refused") == true) "Not started" else "Didn't finish" }, color = N.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                if (state == "running") {
                    Spacer(Modifier.height(14.dp)); ProgressBar((x?.optDouble("pct", 0.0) ?: 0.0).toFloat() / 100f)
                    x?.optString("note")?.takeIf { it.isNotEmpty() }?.let { Text(it, color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
                x?.optString("error")?.takeIf { it.isNotEmpty() }?.let { Text(it, color = if (state == "stopped") N.sub else N.red, fontSize = 15.sp, modifier = Modifier.padding(top = 12.dp)) }
            }
        }
        val kind = x?.optString("kind").orEmpty()
        x?.optJSONArray("samples")?.takeIf { it.length() > 1 }?.let { StressChart(it.objs()) }
        if (state == "done") x?.optJSONObject("result")?.let { res -> DiagResult(kind, res) }
        if (kind == "backup-run" && state == "failed" && x?.optString("error")?.contains("down from") == true && app.isAdmin) {
            Group { Row1("I deleted those files on purpose", "Back up anyway — just this once", true, Icons.Rounded.Backup, N.amber, onClick = {
                app.act { val tt = app.api.post("/api/v1/backups/${x.optString("key")}/run", JSONObject().put("force", true)); app.back(); app.go(Route.Task(tt.optString("id"))) } }) }
        }
        if (state == "done" && r.then?.startsWith("backup:") == true) {
            Group { Row1("Now choose what to back up", "Your new drive is ready at ${r.then.removePrefix("backup:")}", true, Icons.Rounded.Backup, N.blue,
                onClick = { app.back(); app.go(Route.BackupWizard(null, emptyList(), r.then.removePrefix("backup:"))) }) }
        }
        val log = x?.optJSONArray("log").strs()
        if (log.isNotEmpty()) {
            val cmd = x?.optString("kind") == "run-command"
            SectionLabel(if (cmd) "Output" else "What happened")
            Group { Column(Modifier.padding(18.dp)) { (if (cmd) log.takeLast(200).map { it.replace(Regex("^\\d\\d:\\d\\d:\\d\\d "), "") } else log.takeLast(40)).forEach {
                Text(it, color = if (cmd) N.text else N.sub, fontSize = 13.sp, fontFamily = Mono) } } }
            if (cmd && x?.optString("state") != "running") Group { Row1("Run it again", null, true, Icons.Rounded.PlayArrow, onClick = {
                app.act { val t2 = app.api.post("/api/v1/apps/${x?.optString("key")}/run"); app.back(); app.go(Route.Task(t2.optString("task"))) } }) }
        }
        if (state == "running" && kind !in listOf("format", "combine", "raid", "pool-remove", "pool-add") && app.isAdmin) {
            Box(Modifier.fillMaxWidth().padding(22.dp)) {
                PrimaryButton(if (stopping) "Stopping…" else "Stop", Modifier.fillMaxWidth(), !stopping, color = N.card) {
                    stopping = true; app.act { app.api.post("/api/v1/tasks/${r.id}/stop"); stopping = false }
                }
            }
        }
        if (state == "running") Text("You can leave this page — it keeps going on the server, and shows under Storage & hardware.", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
    }
}
