package app.novalabs.nova

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Finding Nova servers on the home network: a broadcast "NOVA?" on UDP 8496; each server answers itself. */
object Discovery {
    suspend fun scan(ctx: Context, ms: Long = 1500): List<JSONObject> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, JSONObject>()
        runCatching {
            DatagramSocket().use { s ->
                s.broadcast = true; s.soTimeout = 250
                val msg = "NOVA?".toByteArray()
                for (addr in broadcastAddresses(ctx)) runCatching { s.send(DatagramPacket(msg, msg.size, addr, 8496)) }
                val end = System.currentTimeMillis() + ms; val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                    runCatching { JSONObject(String(p.data, 0, p.length)) }.getOrNull()?.takeIf { it.optInt("nova") == 1 }?.let {
                        found[it.optString("lan_url").ifEmpty { p.address.hostAddress ?: "" }] = it.put("from", p.address.hostAddress) }
                }
            }
        }
        found.values.toList()
    }
    private fun broadcastAddresses(ctx: Context): List<InetAddress> {
        val out = mutableListOf(InetAddress.getByName("255.255.255.255"))
        runCatching {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.forEach { la ->
                val a = la.address
                if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                    val ip = java.nio.ByteBuffer.wrap(a.address).int; val mask = if (la.prefixLength == 0) 0 else -1 shl (32 - la.prefixLength)
                    out += InetAddress.getByAddress(java.nio.ByteBuffer.allocate(4).putInt(ip or mask.inv()).array())
                }
            }
        }
        return out.distinct()
    }
}

/** Address picked from "Found on this network", for the pairing screen to fill in. */
object PairHint { var address: String? = null; var name: String? = null }

/** Servers already paired on this phone, matched by their home address. */
fun pairedLanUrls(ctx: Context) = Servers.all(ctx).map { Pairing(ctx, it) }.filter { it.paired }.map { it.lanUrl.trimEnd('/') }.toSet()

@Composable fun DiscoveredServers(app: AppState, onPick: (JSONObject) -> Unit) {
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { scanning = true; list = Discovery.scan(app.activity); scanning = false }
    val paired = remember(list) { pairedLanUrls(app.activity) }
    val fresh = list?.filter { it.optString("lan_url").trimEnd('/') !in paired } ?: emptyList()
    SectionLabel(if (scanning) "Looking on this network…" else "Found on this network")
    Group {
        fresh.forEachIndexed { i, s -> if (i > 0) RowDivider()
            Row1(s.optString("name").ifEmpty { s.optString("host") }, s.optString("lan_url").substringAfter("//") + " · Nova ${s.optString("api")}", true,
                Icons.Rounded.Dns, s.optString("accent").takeIf { it.length == 7 }?.let { col(it) } ?: N.blue, onClick = { onPick(s) }) {
                Icon(Icons.Rounded.ChevronRight, null, tint = N.sub) }
        }
        if (fresh.isNotEmpty()) RowDivider()
        Row1(if (scanning) "Searching…" else if (fresh.isEmpty()) "No other Nova servers found" else "Search again",
            if (!scanning && fresh.isEmpty()) "Servers need Nova 0.4.4 or newer, on the same network (and udp 8496 open)" else null, false,
            Icons.Rounded.Refresh, N.sub, enabled = !scanning, onClick = { round++ })
    }
}

// ── All your servers at a glance ─────────────────────────────────────────────────
private class ServerLive(val id: String) { var overview by mutableStateOf<JSONObject?>(null); var error by mutableStateOf<String?>(null) }

@Composable fun ServersScreen(app: AppState) {
    val ctx = app.activity
    var ids by remember { mutableStateOf(Servers.all(ctx).filter { Pairing(ctx, it).paired }) }
    val live = remember(ids) { ids.associateWith { ServerLive(it) } }
    var remove by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ids) {
        while (true) {
            coroutineScope {
                ids.map { id -> async {
                    val l = live[id] ?: return@async
                    if (id == app.pairing.profile) { l.overview = app.overview; l.error = app.error; return@async }
                    runCatching { NovaApi(Pairing(ctx, id)).get("/api/v1/overview") }
                        .onSuccess { l.overview = it; l.error = null }.onFailure { l.error = it.message ?: "Can't reach it" }
                } }.awaitAll()
            }
            delay(15_000)
        }
    }
    Page("Servers", app::back) {
        Text("Every server paired with this phone, live. Tap one to switch to it — each has its own keys in this phone's secure chip, and instant alerts cover all of them.",
            color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        Group {
            ids.forEachIndexed { i, id -> if (i > 0) RowDivider()
                val p = Pairing(ctx, id); val active = id == app.pairing.profile; val l = live[id]
                val o = l?.overview; val st = o?.optJSONObject("status"); val lvl = st?.optString("level") ?: "ok"
                val cs = o?.optJSONObject("containers")
                val name = if (active) serverName(app) else (o?.optJSONObject("server")?.let { it.optString("display_name").ifEmpty { it.optString("name") } } ?: labelOf(app, id))
                val sub = when {
                    l?.error != null && o == null -> "Disconnected"
                    o == null -> "Checking…"
                    else -> listOfNotNull(if (lvl == "ok") "All systems normal" else st?.optString("headline"),
                        cs?.let { "${it.optInt("running")}/${it.optInt("total")} running" },
                        st?.optJSONObject("metrics")?.optString("cpu_temp")?.ifEmpty { null }).joinToString(" · ")
                }
                Row1(name + if (active) "  ·  in use" else "", sub, lvl != "ok" || l?.error != null, Icons.Rounded.Dns,
                    if (l?.error != null && o == null) N.sub else levelColor(lvl, N), onClick = { if (!active) app.switchServer(id) }) {
                    if (!active) Icon(Icons.Rounded.RemoveCircleOutline, "Remove", tint = N.red,
                        modifier = Modifier.clip(CircleShape).clickable { remove = id }.padding(6.dp))
                    else OneRadio(true)
                }
                if (p.remoteUrl.isEmpty() && l?.error != null) Text("Away from home this one needs remote access set up (docs/REMOTE.md).",
                    color = N.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 76.dp, bottom = 10.dp))
            }
            RowDivider()
            Row1("Add a server", "Pair this phone with another machine running Nova", false, Icons.Rounded.AddCircle, N.green,
                onClick = { PairHint.address = null; app.switchServer(Servers.create(ctx)) })
        }
        DiscoveredServers(app) { s ->
            PairHint.address = s.optString("lan_url").substringAfter("//"); PairHint.name = s.optString("name")
            app.switchServer(Servers.create(ctx))
        }
        LinksCard(listOf("How to install Nova on a server" to { app.go(Route.SetupGuide) }))
    }
    remove?.let { id ->
        OneDialog({ remove = null }, "Remove ${labelOf(app, id)} from this phone?",
            "Its keys are erased from this phone. To stop the server trusting them too, remove this phone in that server's Users & devices first.",
            listOf(DialogButton("Cancel") { remove = null }, DialogButton("Remove", N.red) {
                Servers.remove(ctx, id); ids = ids - id; remove = null; app.toast("Removed") }))
    }
}

