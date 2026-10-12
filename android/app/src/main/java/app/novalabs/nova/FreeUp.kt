package app.novalabs.nova

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

fun sizeText(b: Long): String = when {
    b >= 1_000_000_000L -> String.format(java.util.Locale.getDefault(), "%.1f GB", b / 1e9)
    b >= 1_000_000L -> "${b / 1_000_000L} MB"
    b >= 1_000L -> "${b / 1_000L} KB"
    else -> "$b B"
}

/** Free up space (and memory): the server measures what it can safely remove; you pick, it removes (a background task). */
@Composable fun FreeUpScreen(app: AppState) {
    var d by remember { mutableStateOf<JSONObject?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<Set<String>?>(null) }
    var ram by remember { mutableStateOf(true) }
    var ask by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try { val r = app.api.get("/api/v1/freeup"); d = r
            val items = r.optJSONArray("items") ?: JSONArray()
            pick = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optBoolean("default") && (it.optLong("size") > 0 || it.optInt("count") > 0) }.map { it.optString("id") }.toSet()
        } catch (e: Exception) { err = e.message }
    }
    Page("Free up", app::back) {
        val r = d
        when {
            err != null -> Text(err!!, color = N.sub, modifier = Modifier.padding(30.dp))
            r == null -> Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = N.blue); Spacer(Modifier.height(12.dp)); Text("Measuring what can go…", color = N.sub) }
            else -> {
                val items = (0 until (r.optJSONArray("items")?.length() ?: 0)).map { r.getJSONArray("items").getJSONObject(it) }
                val p = pick ?: emptySet()
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(sizeText(r.optLong("free")), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = N.text)
                    Text("free on the system drive", color = N.sub)
                }
                Note("Nova measured what it can safely remove. Your files, your apps' data, containers and their volumes are never touched.", 13.sp, 6.dp)
                SectionLabel("Space")
                Group {
                    items.forEachIndexed { i, it ->
                        if (i > 0) RowDivider()
                        val id = it.optString("id"); val has = it.optLong("size") > 0 || it.optInt("count") > 0
                        val size = when { it.optBoolean("unknown") -> "${it.optInt("count")} ${if (id == "snapshots") "snapshot" else "package"}${if (it.optInt("count") == 1) "" else "s"}"
                                          it.optLong("size") > 0 -> sizeText(it.optLong("size")) + (it.optInt("count").takeIf { c -> c > 0 }?.let { c -> " · $c" } ?: "")
                                          else -> "Nothing to remove" }
                        Row1(it.optString("title"), "$size — ${it.optString("about")}", id in p,
                            if (it.optBoolean("default")) Icons.Rounded.Storage else Icons.Rounded.Warning, if (it.optBoolean("default")) N.blue else N.amber,
                            enabled = has && app.isAdmin, onClick = { pick = if (id in p) p - id else p + id }) { CheckMark(id in p) }
                    }
                }
                Note("Ticked ones are safe to remove any time. The amber ones are up to you: once they're gone, you can't roll back or restore from them.", 13.sp, 6.dp)
                val m = r.optJSONObject("memory")
                SectionLabel("Memory")
                Group {
                    Row1("Free memory too", m?.let { "${sizeText(it.optLong("available"))} of ${sizeText(it.optLong("total"))} available · ${sizeText(it.optLong("cached"))} is cache the system can let go of" },
                        ram, Icons.Rounded.Memory, enabled = app.isAdmin, onClick = { ram = !ram }) { CheckMark(ram) }
                }
                if (app.isAdmin) {
                    val total = items.filter { it.optString("id") in p }.sumOf { it.optLong("size") }
                    Spacer(Modifier.height(12.dp))
                    Group { Row1(if (p.isEmpty() && !ram) "Pick something first" else "Free up${if (total > 0) " " + sizeText(total) else ""}", "Asks for your fingerprint", true,
                        Icons.Rounded.CleaningServices, N.red, enabled = p.isNotEmpty() || ram, onClick = { ask = true }) }
                }
                if (ask) {
                    val risky = items.filter { it.optString("id") in p && !it.optBoolean("default") }.map { it.optString("title").lowercase() }
                    OneDialog({ ask = false }, "Free up now?", if (risky.isNotEmpty()) "This also removes: ${risky.joinToString(", ")}. That can't be undone." else "Only things that are safe to remove.",
                        listOf(DialogButton("Cancel") { ask = false }, DialogButton("Free up", N.red) { ask = false
                            app.act { val res = app.stepUp("Free up space", "POST", "/api/v1/freeup", JSONObject().put("items", JSONArray((p + if (ram) setOf("memory") else emptySet()).toList())))
                                res.optString("task").ifEmpty { res.optString("id") }.ifEmpty { null }?.let { app.go(Route.Task(it)) } } }))
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
