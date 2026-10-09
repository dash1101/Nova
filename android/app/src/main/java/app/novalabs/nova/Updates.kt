package app.novalabs.nova

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Update center: Nova, system packages and container images — check, then update what you pick. */
@Composable fun UpdatesScreen(app: AppState) {
    val live = live(app, "/api/v1/updates", 10_000)
    val tasks by live(app, "/api/v1/tasks", 3_000)
    val u = live.value
    val running = tasks?.optJSONArray("tasks").objs().firstOrNull { it.optString("state") == "running" && it.optString("kind") in listOf("updates-check", "apt-upgrade", "containers-update") }
    val apt = u?.optJSONArray("apt").objs(); val cs = u?.optJSONArray("containers").objs()
    val outdated = cs.filter { it.optString("status") == "update" }
    var pickedPk by remember(u) { mutableStateOf(apt.map { it.optString("name") }.toSet()) }
    var pickedCs by remember(u) { mutableStateOf(outdated.filter { it.optBoolean("updatable") }.map { it.optString("name") }.toSet()) }
    var confirmDocker by remember { mutableStateOf(false) }
    fun start(path: String, body: JSONObject, title: String) = app.act {
        val t = app.stepUp(title, "POST", path, body); app.go(Route.Task(t.optString("id")))
    }
    Page("Updates", app::back) {
        Group {
            if (running != null) TaskRow(app, running)
            else Row1("Check for updates", u?.optDouble("t")?.takeIf { it > 0 }?.let { "Last checked ${whenText(it)}" } ?: "Not checked yet",
                false, Icons.Rounded.Refresh, N.blue, enabled = app.isAdmin, onClick = {
                    app.act { val t = app.api.post("/api/v1/updates/check"); app.go(Route.Task(t.optString("id"))) } })
        }
        u?.optJSONObject("nova")?.let { n ->
            SectionLabel("Nova")
            Group { Row1(if (n.optBoolean("update")) "Nova ${n.optString("available")} is ready" else "Nova is up to date",
                "Installed: ${n.optString("installed")}", n.optBoolean("update"), Icons.Rounded.SystemUpdate, N.blue,
                onClick = { app.go(Route.Settings) }) }
        }
        if (u != null) {
            SectionLabel("System packages")
            Group {
                if (apt.isEmpty()) Row1("All packages are up to date", null, false, Icons.Rounded.CheckCircle, N.green)
                apt.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    val n = p.optString("name"); val on = n in pickedPk
                    Row1(n, "${p.optString("from")} → ${p.optString("to")}" + (if (p.optBoolean("security")) " · security" else "") +
                        when (p.optString("restarts")) { "docker" -> " · restarts Docker (every container)"; "server" -> " · needs a restart"; else -> "" },
                        p.optBoolean("security"), null, onClick = { pickedPk = if (on) pickedPk - n else pickedPk + n }) { OneRadio(on) }
                }
                if (apt.isNotEmpty()) { RowDivider()
                    Row1("Update ${if (pickedPk.size == apt.size) "all ${apt.size}" else "${pickedPk.size}"} package${if (pickedPk.size == 1) "" else "s"}", null, true,
                        Icons.Rounded.Download, N.green, enabled = pickedPk.isNotEmpty() && running == null && app.isAdmin, onClick = {
                            if (apt.any { it.optString("name") in pickedPk && it.optString("restarts") == "docker" }) confirmDocker = true
                            else start("/api/v1/updates/packages", JSONObject().put("packages", if (pickedPk.size == apt.size) "all" else JSONArray(pickedPk.toList())), "Update packages")
                        }) }
            }
            SectionLabel("Containers")
            Group {
                if (cs.isEmpty()) Row1("No containers", null, false, Icons.Rounded.ViewInAr, N.sub)
                cs.forEachIndexed { i, c ->
                    if (i > 0) RowDivider()
                    val n = c.optString("name"); val st = c.optString("status"); val on = n in pickedCs
                    Row1(n, c.optString("image") + when (st) { "update" -> " · newer image available"; "current" -> " · up to date"; else -> " · couldn't check" } +
                        if (st == "update" && !c.optBoolean("updatable")) " · not from a Compose file" else "",
                        st == "update", Icons.Rounded.ViewInAr, if (st == "update") N.amber else N.sub,
                        enabled = st == "update" && c.optBoolean("updatable"), onClick = { pickedCs = if (on) pickedCs - n else pickedCs + n }) {
                        if (st == "update" && c.optBoolean("updatable")) OneRadio(on)
                    }
                }
                if (outdated.isNotEmpty()) { RowDivider()
                    Row1("Update ${pickedCs.size} container${if (pickedCs.size == 1) "" else "s"}", "Pulls the newest image and restarts each one", true,
                        Icons.Rounded.Download, N.green, enabled = pickedCs.isNotEmpty() && running == null && app.isAdmin, onClick = {
                            start("/api/v1/updates/containers", JSONObject().put("containers", JSONArray(pickedCs.toList())), "Update containers") }) }
            }
            Text("Big apps (Immich, Nextcloud, Home Assistant…) sometimes change how they work between versions — check their release notes before a major update.",
                color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
        }
    }
    if (confirmDocker) OneDialog({ confirmDocker = false }, "This restarts Docker",
        "Updating Docker restarts it, so every container stops for a moment and starts again.",
        listOf(DialogButton("Cancel") { confirmDocker = false }, DialogButton("Update anyway", N.blue) {
            confirmDocker = false
            start("/api/v1/updates/packages", JSONObject().put("packages", if (pickedPk.size == apt.size) "all" else JSONArray(pickedPk.toList())), "Update packages") }))
}
