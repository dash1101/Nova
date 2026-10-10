package app.novalabs.nova

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// Files: the server's files as your normal account (never root). Open and edit text, upload,
// download, new files and folders, rename, move, copy, delete (to the server's Trash).

private const val CHUNK = 4 * 1024 * 1024
private fun enc(s: String) = Uri.encode(s)
private fun join(d: String, n: String) = (if (d == "/") "" else d) + "/" + n
private fun isText(e: JSONObject): Boolean { val m = e.optString("mime"); val n = e.optString("name")
    return m.startsWith("text/") || m.contains("json") || m.contains("xml") || Regex("\\.(md|conf|cfg|ini|log|env|toml|ya?ml|sh|py|js|ts|json|txt|service|properties)$", RegexOption.IGNORE_CASE).containsMatchIn(n) }
private fun iconOf(e: JSONObject): ImageVector = when {
    e.optBoolean("dir") -> Icons.Rounded.Folder
    e.optString("mime").startsWith("image/") -> Icons.Rounded.Image
    e.optString("mime").startsWith("video/") || e.optString("mime").startsWith("audio/") -> Icons.Rounded.PlayCircle
    isText(e) -> Icons.AutoMirrored.Rounded.Article
    else -> Icons.Rounded.Description
}
private fun size(b: Long): String = when { b < 1024 -> "$b B"; b < 1 shl 20 -> "%.1f KB".format(b / 1024.0); b < 1 shl 30 -> "%.1f MB".format(b / 1048576.0); else -> "%.1f GB".format(b / 1073741824.0) }
private fun whenOf(t: Long): String { val d = java.util.Date(t * 1000); val today = android.text.format.DateUtils.isToday(t * 1000)
    return java.text.SimpleDateFormat(if (today) "HH:mm" else "MMM d, yyyy", java.util.Locale.getDefault()).format(d) }

@OptIn(ExperimentalFoundationApi::class)
@Composable fun FilesScreen(app: AppState, path: String) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var d by remember(path) { mutableStateOf<JSONObject?>(null) }
    var err by remember(path) { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var picked by remember(path) { mutableStateOf(setOf<String>()) }
    var menuFor by remember { mutableStateOf<JSONObject?>(null) }
    var ask by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }     // title, initial, done
    var newMenu by remember { mutableStateOf(false) }
    var moveFor by remember { mutableStateOf<Pair<Boolean, List<String>>?>(null) }              // copy?, paths
    var delFor by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var pendingDl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path, tick) { runCatching { d = app.api.get("/api/v1/files?path=${enc(path)}"); err = null }.onFailure { err = it.message } }
    fun reload() { picked = emptySet(); tick++ }
    fun op(body: JSONObject, ok: String? = null) = app.act { app.api.post("/api/v1/files", body); ok?.let { app.toast(it) }; reload() }

    val dl = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val p = pendingDl ?: return@rememberLauncherForActivityResult; pendingDl = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch { busy = "Downloading ${p.substringAfterLast('/')}" to 0f
            try { ctx.contentResolver.openOutputStream(uri)!!.use { o -> app.api.downloadTo("/api/v1/files/download?path=${enc(p)}", o) { n, t -> if (t > 0) busy = busy!!.first to n.toFloat() / t } }; app.toast("Downloaded") }
            catch (e: Exception) { app.toast(e.message ?: "Download failed") } finally { busy = null } }
    }
    val up = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val dir = d?.optString("path") ?: return@rememberLauncherForActivityResult
        scope.launch {
            for (u in uris) {
                val name = ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null } ?: continue
                val exists = d?.optJSONArray("items").objs().any { it.optString("name") == name.first }
                busy = "Uploading ${name.first}" to 0f
                try {
                    ctx.contentResolver.openInputStream(u)!!.use { inp ->
                        var off = 0L; val buf = ByteArray(CHUNK)
                        while (true) {
                            var n = 0; while (n < CHUNK) { val k = inp.read(buf, n, CHUNK - n); if (k < 0) break; n += k }
                            val last = off + n >= name.second || n < CHUNK
                            app.api.postRaw("/api/v1/files/upload?path=${enc(dir)}&name=${enc(name.first)}&offset=$off${if (last) "&last=1" else ""}${if (exists) "&replace=1" else ""}", buf.copyOf(n))
                            off += n; busy = busy!!.first to (off.toFloat() / name.second.coerceAtLeast(1))
                            if (last) break
                        }
                    }
                } catch (e: Exception) { app.toast("${name.first}: ${e.message}"); break }
            }
            busy = null; reload()
        }
    }

    val dd = d
    val title = when { dd == null -> "Files"; dd.optString("path") == dd.optString("home") -> "Home"; dd.optString("path") == "/" -> "Server"; else -> dd.optString("path").substringAfterLast('/') }
    Page(title, app::back, if (dd?.optBoolean("writable") == true) listOf(
            TopAction(Icons.Rounded.Add, "New") { newMenu = true },
            TopAction(Icons.Rounded.Upload, "Upload") { up.launch(arrayOf("*/*")) },
            TopAction(Icons.Rounded.Home, "Home folder") { app.go(Route.Files(dd.optString("home"))) })
        else listOf(TopAction(Icons.Rounded.Home, "Home folder") { dd?.let { app.go(Route.Files(it.optString("home"))) } })) {
        if (!app.isAdmin) { Text("Only admins can use the file manager.", color = N.sub, modifier = Modifier.padding(30.dp)); return@Page }
        err?.let { Text(it, color = N.sub, modifier = Modifier.padding(30.dp)) }
        if (dd == null) { if (err == null) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp)); return@Page }
        // where you are
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val parts = dd.optString("path").split('/').filter { it.isNotEmpty() }
            (listOf("/" to "Server") + parts.indices.map { i -> ("/" + parts.take(i + 1).joinToString("/")) to parts[i] }).forEachIndexed { i, (to, l) ->
                if (i > 0) Text("›", color = N.sub)
                val on = i == parts.size
                Text(l, color = if (on) N.blue else N.text, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) N.blue.copy(alpha = 0.16f) else N.pill).clickable { if (!on) app.go(Route.Files(to)) }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        busy?.let { (l, p) -> Column(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp).fillMaxWidth().glassCard(RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row { Text(l, color = N.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1); Text("${(p * 100).toInt()}%", color = N.sub) }
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = N.blue, trackColor = N.pill) } }
        androidx.compose.animation.AnimatedVisibility(picked.isNotEmpty()) {
            Row(Modifier.padding(horizontal = Space.gutter, vertical = 6.dp).fillMaxWidth().glassCard(RoundedCornerShape(26.dp)).padding(start = 18.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${picked.size} selected", color = N.text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val ps = picked.map { join(dd.optString("path"), it) }
                androidx.compose.material3.TextButton({ moveFor = false to ps }) { Text("Move", color = N.blue) }
                androidx.compose.material3.TextButton({ moveFor = true to ps }) { Text("Copy", color = N.blue) }
                androidx.compose.material3.TextButton({ delFor = ps }) { Text("Delete", color = N.red) }
            }
        }
        androidx.activity.compose.BackHandler(enabled = picked.isNotEmpty()) { picked = emptySet() }
        val items = dd.optJSONArray("items").objs().filter { showHidden || !it.optBoolean("hidden") }
        Group {
            if (dd.has("parent") && !dd.isNull("parent")) { Row1("Up one folder", dd.optString("parent"), false, Icons.AutoMirrored.Rounded.ArrowBack, N.sub, onClick = { app.go(Route.Files(dd.optString("parent"))) }); if (items.isNotEmpty()) RowDivider() }
            if (items.isEmpty()) Text(if (dd.optJSONArray("items")?.length() ?: 0 > 0) "Only hidden files here" else "This folder is empty", color = N.sub, modifier = Modifier.padding(22.dp))
            items.forEachIndexed { i, e ->
                if (i > 0) RowDivider()
                val n = e.optString("name"); val on = n in picked; val p = join(dd.optString("path"), n)
                val bg by androidx.compose.animation.animateColorAsState(if (on) N.blue.copy(alpha = 0.14f) else Color.Transparent, label = "pick")
                Row(Modifier.fillMaxWidth().background(bg).combinedClickable(
                        onClick = { if (picked.isNotEmpty()) picked = if (on) picked - n else picked + n
                            else if (e.optBoolean("dir")) app.go(Route.Files(p))
                            else if (isText(e) && e.optLong("size") <= 1_048_576) app.go(Route.FileEdit(p))
                            else { pendingDl = p; dl.launch(n) } },
                        onLongClick = { picked = if (on) picked - n else picked + n })
                    .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (picked.isNotEmpty()) { Box(Modifier.padding(end = 12.dp).size(22.dp).clip(CircleShape).background(if (on) N.blue else Color.Transparent)
                        .then(if (on) Modifier else Modifier.border(2.dp, N.sub, CircleShape)), contentAlignment = Alignment.Center) { if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(15.dp)) } }
                    val tint = if (e.optBoolean("dir")) N.blue else N.sub
                    Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Icon(iconOf(e), null, tint = tint, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(n, color = N.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text((if (e.optBoolean("dir")) "Folder" else size(e.optLong("size"))) + " · " + whenOf(e.optLong("mtime")) + if (e.optBoolean("writable")) "" else " · read-only", color = N.sub, fontSize = 13.sp)
                    }
                    androidx.compose.material3.IconButton({ menuFor = e }) { Icon(Icons.Rounded.MoreVert, "More", tint = N.sub) }
                }
            }
        }
        Text("${size(dd.optLong("free"))} free of ${size(dd.optLong("total"))} · deleted things go to the server's Trash (~/.local/share/Trash). Hold an item to select several.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        Group { SwitchRow("Show hidden files", null, showHidden) { showHidden = it } }
        Spacer(Modifier.height(60.dp))
    }

    // ── dialogs ──
    menuFor?.let { e ->
        val n = e.optString("name"); val p = join(dd!!.optString("path"), n)
        val opts = buildList {
            if (!e.optBoolean("dir")) add(Triple("Download", Icons.Rounded.Download) { pendingDl = p; dl.launch(n) })
            if (!e.optBoolean("dir") && isText(e) && e.optLong("size") <= 1_048_576) add(Triple("Open as text", Icons.Rounded.Edit) { app.go(Route.FileEdit(p)) })
            add(Triple("Rename", Icons.Rounded.DriveFileRenameOutline) { ask = Triple("Rename", n) { v -> if (v != n) op(JSONObject().put("op", "rename").put("path", p).put("name", v)) } })
            add(Triple("Move to…", Icons.Rounded.DriveFileMove) { moveFor = false to listOf(p) })
            add(Triple("Make a copy in…", Icons.Rounded.ContentCopy) { moveFor = true to listOf(p) })
            add(Triple("Delete", Icons.Rounded.Delete) { delFor = listOf(p) })
        }
        OneDialog({ menuFor = null }, n, "${if (e.optBoolean("dir")) "Folder" else size(e.optLong("size"))} · ${e.optString("mode")} · ${e.optString("owner")}", listOf(DialogButton("Close") { menuFor = null })) {
            Column { opts.forEach { (l, ic, go) -> Row1(l, null, false, ic, if (l == "Delete") N.red else N.blue, onClick = { menuFor = null; go() }) } }
        }
    }
    if (newMenu) OneDialog({ newMenu = false }, "New", null, listOf(DialogButton("Cancel") { newMenu = false },
        DialogButton("Text file") { newMenu = false; ask = Triple("New text file", "notes.txt") { v -> app.act {
            val r = app.api.post("/api/v1/files", JSONObject().put("op", "new").put("path", dd!!.optString("path")).put("name", v)); app.go(Route.FileEdit(r.optString("path"))) } } },
        DialogButton("Folder", N.blue) { newMenu = false; ask = Triple("New folder", "") { v -> op(JSONObject().put("op", "mkdir").put("path", dd!!.optString("path")).put("name", v)) } }))
    ask?.let { (t, init, done) ->
        var v by remember(t) { mutableStateOf(init) }
        OneDialog({ ask = null }, t, null, listOf(DialogButton("Cancel") { ask = null }, DialogButton("OK", N.blue, enabled = v.isNotBlank()) { ask = null; done(v.trim()) })) {
            Box(Modifier.padding(horizontal = 22.dp)) { OneTextField(v, { v = it.take(255) }, "Name", Modifier.fillMaxWidth()) }
        }
    }
    moveFor?.let { (copy, ps) -> FolderPicker(app, onDismiss = { moveFor = null }) { to -> moveFor = null
        app.act { for (p in ps) runCatching { app.api.post("/api/v1/files", JSONObject().put("op", if (copy) "copy" else "rename").put("path", p).put("to", to)) }.onFailure { app.toast("${p.substringAfterLast('/')}: ${it.message}") }
            app.toast(if (copy) "Copied" else "Moved to $to"); reload() } } }
    delFor?.let { ps -> OneDialog({ delFor = null }, "Delete ${if (ps.size == 1) ps[0].substringAfterLast('/') else "${ps.size} items"}?", "They go to the Trash on the server, so they can be put back.",
        listOf(DialogButton("Cancel") { delFor = null }, DialogButton("Delete", N.red) { delFor = null; op(JSONObject().put("op", "trash").put("paths", JSONArray(ps)), "Moved to the Trash") })) }
}

/** A plain text editor for files up to 1 MB. */
@Composable fun FileEditScreen(app: AppState, path: String) {
    var text by remember { mutableStateOf<String?>(null) }
    var mtime by remember { mutableLongStateOf(0L) }
    var dirty by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(path) { runCatching { val r = app.api.post("/api/v1/files", JSONObject().put("op", "read").put("path", path)); text = r.optString("text"); mtime = r.optLong("mtime") }.onFailure { err = it.message } }
    Page(path.substringAfterLast('/'), app::back, if (text != null) listOf(TopAction(Icons.Rounded.Save, "Save") {
        app.act { val r = app.api.postRaw("/api/v1/files/save?path=${enc(path)}&mtime=$mtime", text!!.toByteArray())
            if (r.has("error")) throw ApiException(400, r.optString("error")); mtime = r.optLong("mtime"); dirty = false; app.toast("Saved") } }) else emptyList()) {
        Text(path + if (dirty) " · not saved yet" else "", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        err?.let { Text(it, color = N.sub, modifier = Modifier.padding(30.dp)) }
        text?.let { t ->
            BasicTextField(t, { text = it; dirty = true }, Modifier.padding(horizontal = Space.gutter, vertical = 6.dp).fillMaxWidth().heightIn(min = 400.dp)
                .clip(RoundedCornerShape(20.dp)).background(N.card).padding(16.dp),
                textStyle = TextStyle(color = N.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp), cursorBrush = SolidColor(N.blue))
        }
        Spacer(Modifier.height(80.dp))
    }
}
