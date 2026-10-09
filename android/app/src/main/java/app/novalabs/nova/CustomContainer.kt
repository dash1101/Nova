package app.novalabs.nova

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

// Your own container: a simple form. The server checks every field and builds the compose file;
// risky options (privileged, host network, devices, system folders, the Docker socket) can't be set.

private class PortRow(host: String = "", cont: String = "", udp: Boolean = false) { var host by mutableStateOf(host); var cont by mutableStateOf(cont); var udp by mutableStateOf(udp) }
private class VolRow(host: String = "", cont: String = "", ro: Boolean = false) { var host by mutableStateOf(host); var cont by mutableStateOf(cont); var ro by mutableStateOf(ro) }

@Composable fun NewContainerScreen(app: AppState) {
    var name by remember { mutableStateOf("") }
    var image by remember { mutableStateOf("") }
    var env by remember { mutableStateOf("") }
    var restart by remember { mutableStateOf("unless-stopped") }
    val ports = remember { mutableStateListOf(PortRow()) }
    val vols = remember { mutableStateListOf<VolRow>() }
    var browseFor by remember { mutableStateOf<VolRow?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun spec() = JSONObject().put("name", name.trim().lowercase()).put("image", image.trim()).put("restart", restart)
        .put("ports", JSONArray(ports.filter { it.host.isNotBlank() && it.cont.isNotBlank() }.map {
            JSONObject().put("host", it.host.trim().toIntOrNull() ?: -1).put("container", it.cont.trim().toIntOrNull() ?: -1).put("proto", if (it.udp) "udp" else "tcp") }))
        .put("volumes", JSONArray(vols.filter { it.host.isNotBlank() && it.cont.isNotBlank() }.map { JSONObject().put("host", it.host.trim()).put("container", it.cont.trim()).put("ro", it.ro) }))
        .put("env", JSONObject().apply { env.lines().map { it.trim() }.filter { '=' in it }.forEach { put(it.substringBefore('=').trim(), it.substringAfter('=')) } })

    Page("New container", app::back) {
        Text("Run any image from Docker Hub or another registry. It gets its own folder in /opt, starts with the server, and shows up in Containers and Apps. " +
            "For safety it can't be given full control of the server (no privileged mode, host network, devices or system folders).",
            color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        SectionLabel("Container")
        Group { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OneTextField(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c in "-_" }.take(40) }, "Name, e.g. my-web", Modifier.fillMaxWidth())
            OneTextField(image, { image = it.trim().take(255) }, "Image, e.g. nginx:latest", Modifier.fillMaxWidth())
        } }
        SectionLabel("Ports · server → container")
        Group { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ports.forEach { p -> PortLine(p, ports) }
            Text("+ Add a port", color = N.blue, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { ports.add(PortRow()) }.padding(8.dp))
        } }
        SectionLabel("Folders · server → container")
        Group { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (vols.isEmpty()) Text("A name like config keeps it in the container's own folder; your files can be shared from /mnt, /srv, /media or /home.", color = N.sub, fontSize = 13.sp)
            vols.forEach { v -> VolLine(v, vols) { browseFor = v } }
            Text("+ Add a folder", color = N.blue, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { vols.add(VolRow()) }.padding(8.dp))
        } }
        SectionLabel("Settings · one per line, NAME=value")
        Group { Box(Modifier.padding(14.dp)) { OneTextField(env, { env = it.take(6000) }, "TZ=America/New_York", Modifier.fillMaxWidth(), mono = true, singleLine = false) } }
        SectionLabel("Restart")
        Group {
            listOf("unless-stopped" to "Unless I stop it", "always" to "Always", "on-failure" to "Only if it crashes", "no" to "Never").forEachIndexed { i, (k, l) ->
                if (i > 0) RowDivider(); Row1(l, null, onClick = { restart = k }) { OneRadio(restart == k) } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            PillButton("Preview") { app.act { preview = app.api.post("/api/v1/containers/custom/check", JSONObject().put("spec", spec())).optString("compose") } }
            PrimaryButton(if (busy) "Starting…" else "Add and start") {
                if (busy) return@PrimaryButton
                app.act {
                    busy = true
                    try {
                        app.api.post("/api/v1/containers/custom/check", JSONObject().put("spec", spec()))          // clear errors before the fingerprint
                        app.stepUp("Add container ${name.trim()}", "POST", "/api/v1/containers/custom", JSONObject().put("spec", spec()))
                        app.toast("Pulling the image and starting it…"); app.back()
                    } finally { busy = false }
                }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
    browseFor?.let { v -> FolderPicker(app, onDismiss = { browseFor = null }) { v.host = it; browseFor = null } }
    preview?.let { c -> OneDialog({ preview = null }, "What Nova will run", null, listOf(DialogButton("Close") { preview = null })) {
        Text(c, color = N.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp).heightIn(max = 420.dp)
            .verticalScrollSafe()) } }
}

@Composable private fun Modifier.verticalScrollSafe(): Modifier = this.verticalScroll(androidx.compose.foundation.rememberScrollState())

@Composable private fun PortLine(p: PortRow, all: SnapshotStateList<PortRow>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OneTextField(p.host, { p.host = it.filter(Char::isDigit).take(5) }, "8080", Modifier.weight(1f))
        Text("→", color = N.sub)
        OneTextField(p.cont, { p.cont = it.filter(Char::isDigit).take(5) }, "80", Modifier.weight(1f))
        Text(if (p.udp) "UDP" else "TCP", color = N.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { p.udp = !p.udp }.padding(6.dp))
        Icon(Icons.Rounded.Close, "Remove", tint = N.sub, modifier = Modifier.size(20.dp).clickable { all.remove(p) })
    }
}

@Composable private fun VolLine(v: VolRow, all: SnapshotStateList<VolRow>, browse: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OneTextField(v.host, { v.host = it.take(300) }, "/mnt/media, or config", Modifier.weight(1f))
            Icon(Icons.Rounded.FolderOpen, "Browse", tint = N.blue, modifier = Modifier.size(22.dp).clickable { browse() })
            Icon(Icons.Rounded.Close, "Remove", tint = N.sub, modifier = Modifier.size(20.dp).clickable { all.remove(v) })
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("→", color = N.sub)
            OneTextField(v.cont, { v.cont = it.take(300) }, "/data", Modifier.weight(1f))
            Text(if (v.ro) "Read-only" else "Read & write", color = N.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { v.ro = !v.ro }.padding(6.dp))
        }
    }
}
