package app.novalabs.nova

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * This phone's permanent history of server events (one file per server, in app-private storage,
 * excluded from backups). Everything the phone sees is kept here — including what you archive
 * (swipe away) from the Inbox and what other devices clear — so nothing is lost when the server's
 * inbox is tidied. Capped at 10 000 events per server.
 */
object InboxArchive {
    private const val MAX = 10_000
    private val lock = Any()
    private fun file(ctx: Context, profile: String) = File(ctx.filesDir, if (profile.isEmpty()) "history.json" else "history_$profile.json")

    private fun load(ctx: Context, profile: String): JSONObject =
        runCatching { JSONObject(file(ctx, profile).readText()) }.getOrDefault(JSONObject().put("events", JSONArray()).put("archived", JSONArray()))
    private fun save(ctx: Context, profile: String, o: JSONObject) {
        val f = file(ctx, profile); val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); tmp.renameTo(f)
    }
    private fun key(t: Double) = "%.4f".format(Locale.US, t)

    /** Add events the phone has just seen (newest first, no duplicates). */
    fun merge(ctx: Context, profile: String, ev: JSONArray?) {
        if (ev == null || ev.length() == 0) return
        synchronized(lock) {
            val o = load(ctx, profile); val have = o.getJSONArray("events")
            val seen = HashSet<String>(); for (i in 0 until have.length()) seen += key(have.getJSONObject(i).optDouble("t"))
            val add = (0 until ev.length()).map { ev.getJSONObject(it) }.filter { key(it.optDouble("t")) !in seen }
            if (add.isEmpty()) return
            val all = (add + (0 until have.length()).map { have.getJSONObject(it) }).sortedByDescending { it.optDouble("t") }.take(MAX)
            o.put("events", JSONArray(all)); save(ctx, profile, o)
        }
    }
    /** Mark events as archived (swiped away / cleared from the Inbox). They stay in the history. */
    fun archive(ctx: Context, profile: String, events: List<JSONObject>) {
        synchronized(lock) {
            merge(ctx, profile, JSONArray(events))
            val o = load(ctx, profile); val arch = o.optJSONArray("archived") ?: JSONArray()
            val set = (0 until arch.length()).map { arch.getString(it) }.toMutableSet()
            events.forEach { set += key(it.optDouble("t")) }
            o.put("archived", JSONArray(set.toList().takeLast(MAX))); save(ctx, profile, o)
        }
    }
    fun all(ctx: Context, profile: String): Pair<List<JSONObject>, Set<String>> = synchronized(lock) {
        val o = load(ctx, profile); val e = o.getJSONArray("events"); val a = o.optJSONArray("archived") ?: JSONArray()
        (0 until e.length()).map { e.getJSONObject(it) } to (0 until a.length()).map { a.getString(it) }.toSet()
    }
    fun isArchived(set: Set<String>, e: JSONObject) = key(e.optDouble("t")) in set
    fun clear(ctx: Context, profile: String) = synchronized(lock) { file(ctx, profile).delete() }
}

/** Inbox → Archive: everything this phone has seen from this server, archived or not. */
@Composable fun ArchiveScreen(app: AppState) {
    var filter by remember { mutableIntStateOf(0) }
    var data by remember { mutableStateOf(InboxArchive.all(app.activity, app.pairing.profile)) }
    var clear by remember { mutableStateOf(false) }
    // The server keeps the archive for every device; this phone's own copy fills in when offline.
    var server by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var more by remember { mutableStateOf(false) }; var loading by remember { mutableStateOf(true) }
    var serverOk by remember { mutableStateOf(true) }
    suspend fun page(before: Double?) {
        loading = true
        runCatching { app.api.get("/api/v1/archive?limit=300" + (before?.let { "&before=$it" } ?: "")) }
            .onSuccess { r -> val a = r.optJSONArray("events"); server = server + (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }; more = r.optBoolean("more") }
            .onFailure { serverOk = false }
        loading = false
    }
    LaunchedEffect(Unit) { page(null) }
    val events = remember(server, data) {
        val seen = HashSet<Long>()
        (server + data.first).sortedByDescending { it.optDouble("t") }.filter { seen.add(Math.round(it.optDouble("t") * 10000)) }
    }
    val archivedSet = remember(server, data) { data.second + server.filter { it.optBoolean("archived") }.map { "%.4f".format(java.util.Locale.US, it.optDouble("t")) } }
    val archived = archivedSet
    val shown = events.filter { e -> when (filter) {
        1 -> InboxArchive.isArchived(archived, e); 2 -> e.optString("level") in listOf("warning", "critical"); 3 -> e.optString("category") == "login"; else -> true } }
    val day = SimpleDateFormat("EEEE, MMM d, yyyy", Locale.getDefault()); val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    fun export() {
        val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val csv = buildString {
            append("time,level,title,detail,category,archived\n")
            events.forEach { e -> fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
                append(listOf(f.format(Date((e.optDouble("t") * 1000).toLong())), e.optString("level"), q(e.optString("title")), q(e.optString("detail")),
                    e.optString("category"), if (InboxArchive.isArchived(archived, e)) "yes" else "").joinToString(",")).append('\n') }
        }
        app.activity.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(android.content.Intent.EXTRA_SUBJECT, "Nova history — ${serverName(app)}"); putExtra(android.content.Intent.EXTRA_TEXT, csv)
        }, "Export history"))
    }
    Page("Archive", app::back, listOf(TopAction(Icons.Rounded.IosShare, "Export") { export() },
            TopAction(Icons.Rounded.DeleteForever, "Erase this ${DeviceForm.noun}'s copy") { clear = true })) {
        Text(if (serverOk) "Everything archived from the Inbox or too old for it, kept on ${serverName(app)} for every device. This ${DeviceForm.noun}'s own copy fills in when it's offline."
             else "Can't reach the server's archive right now — showing this ${DeviceForm.noun}'s own copy.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        Segmented(listOf("All", "Archived", "Issues", "Logins"), filter) { filter = it }
        if (shown.isEmpty()) Text(if (events.isEmpty()) "Nothing yet — events appear here as the phone receives them." else "Nothing matches.",
            color = N.sub, modifier = Modifier.padding(30.dp))
        shown.take(1500).groupBy { day.format(Date((it.optDouble("t") * 1000).toLong())) }.forEach { (d, list) ->
            SectionLabel(d)
            Group {
                list.forEachIndexed { i, e ->
                    if (i > 0) RowDivider()
                    val arch = InboxArchive.isArchived(archived, e)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(levelColor(e.optString("level"), N).copy(alpha = if (arch) 0.5f else 1f)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.optString("title").replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), ""), color = if (arch) N.sub else N.text, fontSize = 16.sp)
                            e.optString("detail").takeIf { it.isNotEmpty() }?.let { Text(it, color = N.sub, fontSize = 13.sp, maxLines = 4) }
                            if (arch) Text("Archived", color = N.sub, fontSize = 12.sp)
                        }
                        Text(hm.format(Date((e.optDouble("t") * 1000).toLong())), color = N.sub, fontSize = 13.sp)
                    }
                }
            }
        }
        if (shown.size > 1500) Text("Showing the newest 1500.", color = N.sub, fontSize = 12.sp, modifier = Modifier.padding(30.dp))
        val scope = rememberCoroutineScope()
        if (more || loading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (loading) OneSpinner() else PillButton("Load older") { scope.launch { page(server.lastOrNull()?.optDouble("t")) } } }
    }
    if (clear) OneDialog({ clear = false }, "Erase this ${DeviceForm.noun}'s copy?",
        "This ${DeviceForm.noun}'s own copy of the history is deleted. The server keeps its archive for every device.",
        listOf(DialogButton("Cancel") { clear = false }, DialogButton("Erase", N.red) { clear = false
            InboxArchive.clear(app.activity, app.pairing.profile); data = InboxArchive.all(app.activity, app.pairing.profile); app.toast("Erased") }))
}
