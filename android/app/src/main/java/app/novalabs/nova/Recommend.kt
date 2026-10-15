package app.novalabs.nova

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/* Recommended actions: what's worth doing on the server now (worked out on the server, recommend.py) — a disk filling
 * up, space to free, updates, drive health, backups that skipped, old devices… Each one goes straight to its fix. */

/** Where a recommendation's fix lives (the server names web routes; these are the app's screens for them). */
fun routeForWeb(go: String): Route? = when {
    go == "freeup" -> Route.FreeUp; go == "status" -> Route.Status; go == "containers" -> Route.Containers; go == "updates" -> Route.Updates
    go == "backups" -> Route.Backups; go == "devices" -> Route.Devices; go == "settings" -> Route.Settings; go == "disks" -> Route.Disks
    go == "quick" -> Route.QuickPanel; go == "security" -> Route.Security; go == "files" -> Route.Hardware; go == "hardware" -> Route.Hardware
    go.startsWith("drive/") -> Route.Drive(go.removePrefix("drive/"))
    go.startsWith("access/") -> go.removePrefix("access/").split("/").let { p -> Route.Access(android.net.Uri.decode(p[0]), android.net.Uri.decode(p.getOrElse(1) { "" })) }
    else -> null
}

private fun recIcon(level: String) = when (level) { "urgent" -> Icons.Rounded.Error; "warn" -> Icons.Rounded.Warning; else -> Icons.Rounded.Info }
@Composable private fun recColor(level: String) = when (level) { "urgent" -> N.red; "warn" -> N.amber; else -> N.blue }

/** Home: the top three, and a link to the rest (nothing at all when there's nothing to do). */
@Composable fun HomeRecommended(app: AppState) {
    val live = live(app, "/api/v1/recommendations", 120_000)
    val l = live.value?.optJSONArray("recommendations").objs()
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    menu?.let { RecMenu(app, it, onHome = true, close = { menu = null }) { live.value = app.api.get("/api/v1/recommendations") } }
    if (l.isEmpty()) return
    SectionLabel("Recommended")
    Group {
        l.take(3).forEachIndexed { i, r -> if (i > 0) RowDivider()
            val go = r.optJSONObject("action")?.optString("go").orEmpty()
            Row1(r.optString("title"), r.optString("detail"), false, recIcon(r.optString("level")), recColor(r.optString("level")),
                onClick = routeForWeb(go)?.let { rt -> { app.go(rt) } }, onLongClick = { menu = r }) { if (routeForWeb(go) != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = N.sub) } }
        if (l.size > 3) { RowDivider(); Row1("See all ${l.size}", null, true, Icons.Rounded.List, N.blue, onClick = { app.go(Route.Recommended) }) }
    }
}

@Composable fun RecommendedScreen(app: AppState) {
    val live = live(app, "/api/v1/recommendations", 120_000)
    val r = live.value
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    menu?.let { RecMenu(app, it, onHome = false, close = { menu = null }) { live.value = app.api.get("/api/v1/recommendations") } }
    Page("Recommended", app::back, listOf(TopAction(Icons.Rounded.Refresh, "Look again") { app.act { live.value = app.api.get("/api/v1/recommendations?fresh=1") } })) {
        if (r == null) { Note("Looking at the server…", 14.sp); return@Page }
        val l = r.optJSONArray("recommendations").objs()
        if (l.isEmpty()) Note("Nothing to do — the server looks after itself right now.", 14.sp)
        l.forEach { x ->
            val act = x.optJSONObject("action"); val rt = routeForWeb(act?.optString("go").orEmpty())
            Group {
                Row1(x.optString("title"), x.optString("detail"), false, recIcon(x.optString("level")), recColor(x.optString("level")), onLongClick = { menu = x })
                if (app.isAdmin) Row(Modifier.padding(start = 66.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (rt != null) PrimaryButton(act?.optString("label").orEmpty(), Modifier.weight(1f)) { app.go(rt) }
                    PrimaryButton("Not now", Modifier.weight(1f), true, N.pill) { app.act {
                        app.api.post("/api/v1/recommendations/snooze", JSONObject().put("id", x.optString("id"))); live.value = app.api.get("/api/v1/recommendations") } }
                }
            }
        }
        if (r.optInt("snoozed") > 0) Note("${r.optInt("snoozed")} hidden for now (Not now hides one for a month).", 13.sp)
        Note("Nova works these out from what it already watches: space, memory, updates, drive health, backups, devices and more. Nothing changes until you tap a fix.", 13.sp, 8.dp)
    }
}

/** Hold a recommendation: hide it for a month, open its fix, or (on Home) hide the whole card. */
@Composable private fun RecMenu(app: AppState, r: JSONObject, onHome: Boolean, close: () -> Unit, reload: suspend () -> Unit) {
    val act = r.optJSONObject("action"); val rt = routeForWeb(act?.optString("go").orEmpty())
    OneDialog(close, r.optString("title"), null, listOf(DialogButton("Cancel") { close() })) {
        if (app.isAdmin) DialogChoice("Not now", "Hide this one for a month", false, action = true) { close()
            app.act("Hidden for a month") { app.api.post("/api/v1/recommendations/snooze", JSONObject().put("id", r.optString("id"))); reload() } }
        if (rt != null) DialogChoice(act?.optString("label")?.ifEmpty { null } ?: "Open", null, false, action = true) { close(); app.go(rt) }
        if (onHome) DialogChoice("Hide Recommended from Home", "Turn it back on in Edit Home", false, action = true) { close()
            AppPrefs.set("home_recs", false); app.toast("Recommended is hidden — Edit Home brings it back") }
    }
}
