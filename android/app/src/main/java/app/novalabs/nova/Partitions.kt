package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/* Partitions: a drive's partition table, edited the way KDE Partition Manager does it. Pick a partition or free space,
 * queue up changes, see the drive as it will be, then apply them together (with your fingerprint). The server checks
 * every change against the real drive (Partitions.py); this screen only collects them. */

private const val MIB = 1L shl 20
private val FS_COLOR = mapOf("ext4" to Color(0xFF3E91FF), "xfs" to Color(0xFFBF5AF2), "btrfs" to Color(0xFF30B0C7), "exfat" to Color(0xFFFF9F0A),
    "vfat" to Color(0xFF34C759), "swap" to Color(0xFFFF453A), "ntfs" to Color(0xFFFFD60A))
private val FS_NEW = listOf("ext4" to ("ext4" to "Linux's everyday filesystem — the safe choice"), "xfs" to ("XFS" to "Good for very large files; can grow but never shrink"),
    "btrfs" to ("btrfs" to "Snapshots and checksums"), "exfat" to ("exFAT" to "Readable by Windows and Macs too"), "vfat" to ("FAT32" to "For an EFI system partition, or old devices"),
    "swap" to ("Swap" to "Extra memory on disk"), "none" to ("Leave it empty" to "Just the partition, no filesystem"))
private val FLAG_NAMES = linkedMapOf("none" to "Normal data partition", "esp" to "EFI system partition", "bios_grub" to "BIOS boot", "raid" to "RAID member", "lvm" to "LVM member")


@Composable fun PartitionsScreen(app: AppState, serial: String) {
    var lay by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    val ops = remember { mutableStateListOf<JSONObject>() }
    var planned by remember { mutableStateOf<JSONObject?>(null) }
    var sel by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serial) { try { lay = app.api.get("/api/v1/drives/$serial/partitions") } catch (e: Exception) { err = e.message } }
    fun add(op: JSONObject) = app.act {
        ops.add(op)
        try { planned = app.api.post("/api/v1/drives/$serial/partitions/plan", JSONObject().put("ops", JSONArray(ops.toList()))); sel = null }
        catch (e: Exception) { ops.removeAt(ops.lastIndex); app.toast(e.message ?: "That change isn't possible") }
    }
    fun undo() = app.act {
        if (ops.isNotEmpty()) ops.removeAt(ops.lastIndex)
        planned = if (ops.isEmpty()) null else app.api.post("/api/v1/drives/$serial/partitions/plan", JSONObject().put("ops", JSONArray(ops.toList())))
    }
    Page("Partitions", app::back) {
        val l = lay
        if (l == null) { Note(err ?: "Reading the drive…", 14.sp); return@Page }
        val v = planned?.optJSONObject("result") ?: l
        val ss = v.optLong("sector", 512); val total = v.optLong("sectors", 1).coerceAtLeast(1)
        val parts = v.optJSONArray("partitions").objs(); val gaps = v.optJSONArray("gaps").objs()
        data class Item(val key: String, val start: Long, val end: Long, val p: JSONObject?)
        val items = (parts.map { Item("p" + it.optInt("n"), it.optLong("start"), it.optLong("end"), it) } +
            gaps.mapIndexed { i, g -> Item("g$i", g.optLong("start"), g.optLong("end"), null) }).sortedBy { it.start }
        val editable = l.optBoolean("editable") && app.isAdmin
        Text("${l.optString("model").ifEmpty { l.optString("name") }} · ${l.optString("size_text")} · " +
            when (l.optString("table")) { "gpt" -> "GPT"; "dos" -> "MBR"; else -> "no partition table" }, color = N.sub, fontSize = 15.sp,
            modifier = Modifier.padding(start = 30.dp, end = 22.dp, bottom = 6.dp))
        if (planned != null) Note("Showing the drive as it will be after your changes.", 13.sp, 0.dp)
        // the drive, to scale (a small partition keeps a visible sliver); free space is lighter
        Row(Modifier.fillMaxWidth().height(54.dp).padding(horizontal = Space.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            if (items.isEmpty()) Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(smallCorner(10.dp))).background(N.pill), contentAlignment = Alignment.Center) {
                Text(if (v.optString("table").isEmpty() || v.isNull("table")) "No partition table" else "Free space", color = N.sub, fontSize = 12.sp) }
            items.forEach { it ->
                val frac = ((it.end - it.start + 1).toFloat() / total).coerceAtLeast(0.025f)
                val c = it.p?.let { p -> FS_COLOR[p.optString("fs")] ?: N.sub }
                val sh = RoundedCornerShape(smallCorner(10.dp))
                Box(Modifier.weight(frac).fillMaxHeight().clip(sh).background(c?.copy(alpha = .85f) ?: N.pill)
                    .then(if (sel == it.key) Modifier.border(2.dp, N.text, sh) else Modifier)
                    .clickable(enabled = editable) { sel = if (sel == it.key) null else it.key }, contentAlignment = Alignment.Center) {
                    Text(it.p?.let { p -> p.optString("label").ifEmpty { p.optString("fs").ifEmpty { "#" + p.optInt("n") } } } ?: "free",
                        color = if (c != null) Color.White else N.sub, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 3.dp))
                }
            }
        }
        val busy = l.optJSONArray("busy")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (busy.isNotEmpty()) Note("Read only: " + busy.joinToString("; ") + ".", 13.sp)
        else if (!app.isAdmin) Note("View-only access: an admin can change partitions.", 13.sp)
        SectionLabel("Partitions")
        if (items.isEmpty()) Note("Nothing on it yet.", 14.sp)
        else Group {
            items.forEachIndexed { i, it -> if (i > 0) RowDivider()
                val p = it.p
                if (p == null) Row1("Free space", bytesHuman(gaps[it.key.drop(1).toInt()].optLong("size")), false, Icons.Rounded.AddCircleOutline, N.sub,
                    onClick = if (editable) ({ sel = it.key }) else null) { if (sel == it.key) Icon(Icons.Rounded.CheckCircle, null, tint = N.blue) }
                else {
                    val used = if (!p.isNull("used") && p.has("used")) " · ${bytesHuman(p.optLong("used"))} used" else ""
                    val bits = listOfNotNull(p.optString("fs").ifEmpty { "no filesystem" }, bytesHuman(p.optLong("size")) + used,
                        FLAG_NAMES[p.optString("flag")]?.takeIf { p.optString("flag") != "none" && p.optString("flag").isNotEmpty() },
                        p.optJSONArray("mounts")?.takeIf { m -> m.length() > 0 }?.let { m -> "mounted at " + (0 until m.length()).joinToString(", ") { m.getString(it) } },
                        "new".takeIf { p.optBoolean("new") })
                    Row1("${p.optInt("n")} · ${p.optString("label").ifEmpty { p.optString("node").removePrefix("/dev/").ifEmpty { "partition" } }}", bits.joinToString(" · "), false,
                        if (p.optString("fs") == "swap") Icons.Rounded.Memory else Icons.Rounded.Storage, FS_COLOR[p.optString("fs")] ?: N.sub,
                        onClick = if (app.isAdmin) ({ sel = it.key }) else null) { if (sel == it.key) Icon(Icons.Rounded.CheckCircle, null, tint = N.blue) }
                }
            }
        }
        val selP = sel?.takeIf { it.startsWith("p") }?.let { k -> parts.firstOrNull { "p" + it.optInt("n") == k } }
        var mountFor by remember { mutableStateOf<JSONObject?>(null) }
        val canMount = app.isAdmin && selP != null && selP.optString("fs").isNotEmpty() && selP.optString("fs") != "swap" && !selP.optBoolean("new") &&
            busy.none { b -> listOf("system drive", "pool", "array", "LVM", "RAID", "swap").any { b.contains(it) } }
        if (canMount && selP != null) {
            SectionLabel("Mount point")
            Group { val ms = selP.optJSONArray("mounts")?.let { m -> (0 until m.length()).map { m.getString(it) } }.orEmpty()
                Row1(if (ms.isNotEmpty()) "Change where it's mounted" else "Mount it", if (ms.isNotEmpty()) "Now at " + ms.joinToString(", ") else "Not mounted — pick a folder like /mnt/name",
                    false, Icons.Rounded.Folder, N.blue, onClick = { mountFor = JSONObject(selP.toString()).put("name", selP.optString("node").removePrefix("/dev/")) }) }
        }
        mountFor?.let { MountPointDialog(app, serial, it) { mountFor = null } }
        val selG = sel?.takeIf { it.startsWith("g") }?.let { gaps.getOrNull(it.drop(1).toInt()) }
        if (editable) {
            SectionLabel("Change")
            Group {
                val hasTable = !v.isNull("table") && v.optString("table").isNotEmpty()
                when {
                    !hasTable -> Row1("New partition table", "GPT (recommended) or MBR — the drive starts empty", false, Icons.Rounded.Add, N.blue, onClick = { dialog = "table" })
                    selG != null -> Row1("Create a partition here", "Up to ${bytesHuman(selG.optLong("size"))}", false, Icons.Rounded.AddCircle, N.green, onClick = { dialog = "create" })
                    selP != null -> {
                        Row1("Format", "A new, empty filesystem — what's on it now is erased", false, Icons.Rounded.Storage, N.amber, onClick = { dialog = "format" }); RowDivider()
                        if (selP.optString("fs").isNotEmpty()) { Row1("Label", selP.optString("label").ifEmpty { null }?.let { "Now “$it”" } ?: "Give it a name", false, Icons.Rounded.Edit, N.blue, onClick = { dialog = "label" }); RowDivider() }
                        if (!selP.optBoolean("new")) { Row1("Resize", if (selP.optString("fs") == "xfs") "XFS can grow into free space right after it, never shrink"
                            else "Grow into the free space right after it, or shrink (ext4) — the start stays put", false, Icons.Rounded.Expand, N.blue, onClick = { dialog = "resize" }); RowDivider() }
                        Row1("Flag", FLAG_NAMES[selP.optString("flag").ifEmpty { "none" }], false, Icons.Rounded.Flag, N.blue, onClick = { dialog = "flag" }); RowDivider()
                        Row1("Delete", "Its files are gone (until something new is made there, Restore can bring it back)", false, Icons.Rounded.Delete, N.red, onClick = { dialog = "delete" })
                    }
                    else -> Row1("Pick a partition or free space", "Tap it in the bar or the list to see what you can do")
                }
                if (hasTable) { RowDivider(); Row1("New partition table", "Erases the whole drive and starts again", false, Icons.Rounded.Add, N.red, onClick = { dialog = "table" }) }
            }
        }
        if (ops.isNotEmpty()) {
            SectionLabel("Waiting to apply · ${ops.size}")
            val steps = planned?.optJSONArray("steps")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            Group { steps.forEachIndexed { i, s -> if (i > 0) RowDivider()
                Row1(s, if (i == steps.lastIndex) "Tap to undo" else null, false, if (i == steps.lastIndex) Icons.Rounded.Undo else Icons.Rounded.Schedule, N.amber,
                    onClick = if (i == steps.lastIndex) ({ undo() }) else null) } }
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton("Clear", Modifier.weight(1f), true, N.pill) { ops.clear(); planned = null }
                PrimaryButton("Apply changes (${ops.size})", Modifier.weight(2f), l.optBoolean("editable")) { dialog = "apply" }
            }
        }
        val backs = l.optJSONArray("backups")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        if (backs.isNotEmpty() && editable) {
            SectionLabel("Undo")
            Group { Row1("Restore the previous table", "Saved before the last change (${backs[0].substringAfterLast("--").removeSuffix(".sfdisk")}) — filesystems come back if nothing new was written over them",
                false, Icons.Rounded.History, N.blue, onClick = { dialog = "restore" }) }
        }
        Note("Nova doesn't move partitions (where one starts stays the same): it means copying every byte, and an interruption halfway loses it. Changes are applied together, after you confirm with your fingerprint; the old table is saved first.", 13.sp, 8.dp)

        // ── the dialogs that collect each change ──
        var fs by remember(dialog) { mutableStateOf("ext4") }; var text by remember(dialog) { mutableStateOf("") }; var size by remember(dialog) { mutableStateOf("") }
        val close = { dialog = null }
        when (dialog) {
            "table" -> OneDialog(close, "New partition table", "Everything on the drive is erased, once you apply.", listOf(DialogButton("Cancel", onClick = close))) {
                DialogChoice("GPT", "Recommended — any size, up to 128 partitions", false, action = true) { close(); add(JSONObject().put("op", "table").put("type", "gpt")) }
                DialogChoice("MBR", "For old computers — up to 4 partitions, 2 TB", false, action = true) { close(); add(JSONObject().put("op", "table").put("type", "dos")) } }
            "create", "format" -> {
                val maxMib = selG?.let { (it.optLong("end") - it.optLong("start") + 1) * ss / MIB } ?: 0
                if (size.isEmpty() && dialog == "create") size = maxMib.toString()
                OneDialog(close, if (dialog == "create") "Create a partition" else "Format partition ${selP?.optInt("n")}", null,
                    listOf(DialogButton("Cancel", onClick = close), DialogButton(if (dialog == "create") "Add" else "Format", N.blue) {
                        close()
                        if (dialog == "create" && selG != null) add(JSONObject().put("op", "create").put("start_mib", selG.optLong("start") * ss / MIB)
                            .put("size_mib", (size.toLongOrNull() ?: 0).coerceIn(1, maxMib)).put("fs", fs).put("label", text.trim()))
                        else if (selP != null) add(JSONObject().put("op", "format").put("n", selP.optInt("n")).put("fs", fs).put("label", text.trim()))
                    })) {
                    FS_NEW.filter { dialog == "create" || it.first != "none" }.forEach { (k, l2) -> DialogChoice(l2.first, l2.second, fs == k) { fs = k } }
                    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
                        if (dialog == "create") { Text("Size in MiB (up to $maxMib)", color = N.sub, fontSize = 13.sp)
                            OneTextField(size, { size = it.filter(Char::isDigit).take(12) }, maxMib.toString(), Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                        if (fs != "none") { Text("Label (optional)", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                            OneTextField(text, { text = it.take(16) }, if (fs == "vfat") "EFI" else "data", Modifier.fillMaxWidth()) }
                    }
                }
            }
            "label" -> OneDialog(close, "Label partition ${selP?.optInt("n")}", null, listOf(DialogButton("Cancel", onClick = close),
                DialogButton("Save", N.blue) { close(); selP?.let { add(JSONObject().put("op", "label").put("n", it.optInt("n")).put("label", text.trim())) } })) {
                if (text.isEmpty()) text = selP?.optString("label").orEmpty()
                OneTextField(text, { text = it.take(16) }, "data", Modifier.fillMaxWidth().padding(horizontal = 22.dp)) }
            "flag" -> OneDialog(close, "Partition ${selP?.optInt("n")} is…", null, listOf(DialogButton("Cancel", onClick = close))) {
                FLAG_NAMES.filter { it.key != "bios_grub" || v.optString("table") == "gpt" }.forEach { (k, name) ->
                    DialogChoice(name, null, (selP?.optString("flag")?.ifEmpty { "none" } ?: "none") == k) { close(); selP?.let { add(JSONObject().put("op", "flag").put("n", it.optInt("n")).put("flag", k)) } } } }
            "resize" -> selP?.let { p ->
                val next = (parts.filter { it.optLong("start") > p.optLong("start") }.map { it.optLong("start") } + (v.optLong("last") + 1)).min()
                val maxMib = (next - p.optLong("start")) * ss / MIB; val cur = (p.optLong("end") - p.optLong("start") + 1) * ss / MIB
                if (size.isEmpty()) size = cur.toString()
                OneDialog(close, "Resize partition ${p.optInt("n")}", "Now $cur MiB; up to $maxMib MiB (the free space right after it).", listOf(DialogButton("Cancel", onClick = close),
                    DialogButton("Resize", N.blue) { close(); add(JSONObject().put("op", "resize").put("n", p.optInt("n")).put("size_mib", size.toLongOrNull() ?: 0)) })) {
                    OneTextField(size, { size = it.filter(Char::isDigit).take(12) }, cur.toString(), Modifier.fillMaxWidth().padding(horizontal = 22.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
            }
            "delete" -> selP?.let { p -> OneDialog(close, "Delete partition ${p.optInt("n")}?", "${p.optString("label").ifEmpty { p.optString("fs").ifEmpty { "It" } }} (${bytesHuman(p.optLong("size"))}) goes, once you apply.",
                listOf(DialogButton("Cancel", onClick = close), DialogButton("Delete", N.red) { close(); add(JSONObject().put("op", "delete").put("n", p.optInt("n"))) })) }
            "apply", "restore" -> OneDialog(close, if (dialog == "apply") "Apply changes (${ops.size})?" else "Restore the previous table?",
                (if (dialog == "apply") "The old table is saved first. " else "The current table is saved first. Filesystems on the old partitions come back if nothing new was written over them. ") +
                    "Type the drive's serial (${l.optString("serial")}) to confirm.",
                listOf(DialogButton("Cancel", onClick = close), DialogButton(if (dialog == "apply") "Apply" else "Restore", N.red) {
                    val typed = text.trim(); val what = dialog; close()
                    if (typed != l.optString("serial")) { app.toast("That isn't this drive's serial"); return@DialogButton }
                    app.act {
                        val r = if (what == "apply") app.stepUp("Change the partitions on ${l.optString("model").ifEmpty { l.optString("name") }}", "POST", "/api/v1/storage/task/partition-apply",
                                JSONObject().put("spec", JSONObject().put("serial", typed).put("confirm", JSONArray(listOf(typed)))
                                    .put("fingerprint", planned?.optString("fingerprint")?.ifEmpty { null } ?: l.optString("fingerprint")).put("ops", JSONArray(ops.toList()))))
                            else app.stepUp("Restore a partition table", "POST", "/api/v1/storage/task/partition-restore",
                                JSONObject().put("spec", JSONObject().put("serial", typed).put("confirm", JSONArray(listOf(typed))).put("backup", backs.firstOrNull())))
                        app.go(Route.Task(r.optString("id")))
                    } })) {
                OneTextField(text, { text = it.take(64) }, l.optString("serial"), Modifier.fillMaxWidth().padding(horizontal = 22.dp), mono = true) }
        }
    }
}


// ── the partition manager: every drive at a glance (like Windows Disk Management); each opens its editor ──
@Composable fun DisksScreen(app: AppState) {
    val live = live(app, "/api/v1/storage", 30_000)
    val st = live.value
    var mp by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    Page("Partition manager", app::back) {
        if (st == null) { Note("Reading the drives…", 14.sp); return@Page }
        val issues = st.optJSONArray("suggestions").objs().filter { Regex("^(dup|stale|hidden):").containsMatchIn(it.optString("id")) }
        if (issues.isNotEmpty()) { SectionLabel("Needs fixing"); issues.forEach { SuggestionCard(app, it) } }
        SectionLabel("Drives")
        st.optJSONArray("drives").objs().forEach { d ->
            val parts = d.optJSONArray("partitions").objs(); val size = d.optLong("size").coerceAtLeast(1)
            Group {
                Row1("${d.optString("model").ifEmpty { d.optString("name") }} · ${d.optString("size_text")}",
                    listOfNotNull("/dev/" + d.optString("name"), "system drive".takeIf { d.optBoolean("system") }, "in a pool".takeIf { !d.isNull("pool") && d.optString("pool").isNotEmpty() },
                        d.optJSONArray("warnings")?.optString(0)?.ifEmpty { null }).joinToString(" · "), false, Icons.Rounded.Storage, N.blue,
                    onClick = { app.go(Route.Partitions(d.optString("serial"))) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = N.sub) }
                Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    val used = parts.sumOf { it.optLong("size") }; val sh = RoundedCornerShape(smallCorner(8.dp))
                    parts.forEach { p -> Box(Modifier.weight((p.optLong("size").toFloat() / size).coerceAtLeast(.03f)).fillMaxHeight().clip(sh).background((FS_COLOR[p.optString("fstype")] ?: N.sub).copy(alpha = .85f)),
                        contentAlignment = Alignment.Center) { Text(p.optString("label").ifEmpty { p.optString("fstype") }, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                    if ((size - used).toFloat() / size > .01f) Box(Modifier.weight(((size - used).toFloat() / size).coerceAtLeast(.03f)).fillMaxHeight().clip(sh).background(N.pill), contentAlignment = Alignment.Center) {
                        Text("free", color = N.sub, fontSize = 11.sp) }
                }
                parts.forEach { p -> RowDivider()
                    val mounts = p.optJSONArray("mounts")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                    val can = app.isAdmin && !d.optBoolean("system") && (d.isNull("pool") || d.optString("pool").isEmpty()) && p.optString("fstype").isNotEmpty() && !p.optBoolean("swap")
                    Row1("${p.optString("name")} · ${p.optString("label").ifEmpty { p.optString("fstype").ifEmpty { "no filesystem" } }}",
                        listOfNotNull(bytesHuman(p.optLong("size")), p.optJSONObject("usage")?.let { "${bytesHuman(it.optLong("free"))} free" },
                            if (p.optBoolean("swap")) "swap in use" else if (mounts.isNotEmpty()) "at " + mounts.joinToString(", ") else if (p.optString("fstype").isNotEmpty()) "not mounted" else null).joinToString(" · "),
                        false, Icons.Rounded.Folder, FS_COLOR[p.optString("fstype")] ?: N.sub, onClick = if (can) ({ mp = d.optString("serial") to p }) else null)
                }
            }
        }
        Note("Tap a drive to see its partitions and change them (create, delete, format, label, flags, grow, shrink). Tap a partition here to change where it's mounted. Nova doesn't move partitions, and never changes the system drive.", 13.sp, 8.dp)
    }
    mp?.let { (serial, p) -> MountPointDialog(app, serial, p) { mp = null; app.act { live.value = app.api.get("/api/v1/storage") } } }
}

/** Change where a partition is mounted: one place, kept after a restart; files saved into that folder while the drive
 *  wasn't there are moved onto it. Like "Change drive letter and paths" in Windows. */
@Composable fun MountPointDialog(app: AppState, serial: String, p: JSONObject, done: () -> Unit) {
    val mounts = p.optJSONArray("mounts")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    val name = p.optString("name").ifEmpty { p.optString("node").removePrefix("/dev/") }
    var to by remember { mutableStateOf(mounts.firstOrNull() ?: "/mnt/" + p.optString("label").ifEmpty { name }.lowercase().replace(Regex("[^a-z0-9_.-]+"), "_").trim('_').ifEmpty { "drive" }) }
    val ok = Regex("/(mnt|srv|media|data)/[A-Za-z0-9][A-Za-z0-9_.-]{0,40}").matches(to)
    OneDialog(done, "Where ${p.optString("label").ifEmpty { name }} is mounted", (if (mounts.isNotEmpty()) "Now at ${mounts.joinToString(", ")}. " else "Not mounted now. ") +
        "It's mounted at the folder you pick only, and comes back there after a restart. Files saved into that folder while the drive wasn't there are moved onto it.",
        listOf(DialogButton("Cancel", onClick = done), DialogButton("Mount here", N.blue, enabled = ok) { val target = to.trim(); done()
            app.act { val t = app.stepUp("Mount $name at $target", "POST", "/api/v1/storage/task/mount-set",
                JSONObject().put("spec", JSONObject().put("serial", serial).put("part", name).put("mount", target).put("merge", true)))
                app.go(Route.Task(t.optString("id"))) } })) {
        OneTextField(to, { to = it.take(46) }, "/mnt/name", Modifier.fillMaxWidth().padding(horizontal = 22.dp), mono = true)
        if (!ok) Text("Use a folder like /mnt/name", color = N.red, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp))
    }
}
