package app.novalabs.nova.wear

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.*
import androidx.wear.input.RemoteInputIntentHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private val GREEN = Color(0xFF30D158); private val AMBER = Color(0xFFFFB340); private val RED = Color(0xFFFF453A)
fun levelColor(l: String) = when (l) { "critical" -> RED; "warning" -> AMBER; else -> GREEN }

class WearActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WearStore(this)
        val given = intent.getStringExtra("address")      // an address handed over at launch (adb, or a helper app) — the same as typing it; approval still needed
        setContent {
            MaterialTheme {
                var paired by remember { mutableStateOf(store.paired) }
                AppScaffold {
                    if (paired) Glance(store) { store.clear(); WearKeys.wipe(); paired = false }
                    else Setup(store, given) { paired = true }
                }
            }
        }
    }
}

// ── joining a server: find it, show a code, wait for the phone ──────────────────────
@Composable private fun Setup(store: WearStore, given: String? = null, onDone: () -> Unit) {
    val ctx = LocalContext.current; val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<JSONObject>?>(null) }
    var scanRound by remember { mutableIntStateOf(0) }
    var waiting by remember { mutableStateOf<JSONObject?>(null) }       // {url, pin, id, code, name}
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scanRound) { found = null; found = WearDiscovery.scan(ctx) }
    fun ask(url: String, pin: String, name: String) = scope.launch {
        err = null
        try {
            val r = WearApi.request(url, pin, android.os.Build.MODEL.ifBlank { "Watch" })
            waiting = JSONObject().put("url", url).put("pin", pin).put("id", r.optString("id")).put("code", r.optString("code")).put("name", name)
        } catch (e: Exception) { err = e.message ?: "Couldn't ask the server" }
    }
    fun typedAddress(text: String) = scope.launch {
        val url = if (text.startsWith("https://")) text else "https://${text.removePrefix("http://")}${if (":" in text.removePrefix("http://")) "" else ":8495"}"
        try { val pin = probePin(url); ask(url, pin, text) } catch (e: Exception) { err = e.message }
    }
    // A typed address (for Tailscale, or if the broadcast doesn't reach the watch)
    val typed = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val text = res.data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence("addr")?.toString() }?.trim().orEmpty()
        if (text.isNotEmpty()) typedAddress(text)
    }
    LaunchedEffect(given) { given?.trim()?.takeIf { it.isNotEmpty() }?.let { typedAddress(it) } }
    LaunchedEffect(waiting) {
        val w = waiting ?: return@LaunchedEffect
        while (true) {
            delay(2500)
            val st = runCatching { WearApi.requestState(w.getString("url"), w.getString("pin"), w.getString("id")) }.getOrNull() ?: continue
            when (st.optString("state")) {
                "approved" -> { store.url = w.getString("url"); store.pin = w.getString("pin"); store.deviceId = st.optString("device_id")
                    store.server = st.optString("server").ifEmpty { w.optString("name") }; onDone(); return@LaunchedEffect }
                "expired", "" -> if (st.optString("state") == "expired") { waiting = null; err = "The code expired — try again"; return@LaunchedEffect }
            }
        }
    }
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) {
        ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val w = waiting
            if (w != null) {
                item { ListHeader { Text("Approve on your phone") } }
                item { Text(w.optString("code").chunked(3).joinToString(" "), fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
                item { Text("Nova → Users & devices → Approve a browser or watch, and type this code.", fontSize = 13.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp)) }
                item { Text("Certificate ${shortPin(w.optString("pin"))}", fontSize = 12.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp)) }
                item { Text("It should match the one your phone shows for this server.", fontSize = 11.sp, textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp)) }
                item { Button(onClick = { waiting = null }, modifier = Modifier.padding(top = 8.dp)) { Text("Cancel") } }
            } else {
                item { ListHeader { Text("Nova") } }
                item { Text("Pick your server to pair this watch.", fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
                val f = found
                if (f == null) item { CircularProgressIndicator(modifier = Modifier.size(32.dp).padding(8.dp)) }
                else f.forEach { s -> item {
                    Button(onClick = { ask(s.getString("lan_url"), s.optString("pin"), s.optString("name")) }, modifier = Modifier.fillMaxWidth(),
                        secondaryLabel = { Text(s.optString("lan_url").removePrefix("https://"), maxLines = 1, overflow = TextOverflow.Ellipsis) }) {
                        Text(s.optString("name"), maxLines = 1, overflow = TextOverflow.Ellipsis) } } }
                if (f != null && f.isEmpty()) item { Text("No server found on this Wi-Fi.", fontSize = 13.sp, textAlign = TextAlign.Center) }
                item { FilledTonalButton(onClick = { scanRound++ }, modifier = Modifier.fillMaxWidth()) { Text("Look again") } }
                item { FilledTonalButton(onClick = {
                    val ri = RemoteInput.Builder("addr").setLabel("Server address").build()
                    typed.launch(RemoteInputIntentHelper.createActionRemoteInputIntent().also { RemoteInputIntentHelper.putRemoteInputsExtra(it, listOf(ri)) })
                }, modifier = Modifier.fillMaxWidth()) { Text("Type an address") } }
            }
            err?.let { e -> item { Text(e, color = RED, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp)) } }
        }
    }
}

private suspend fun probePin(url: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    var pin = ""
    val tm = object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
            pin = java.security.MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) } }
        override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
    }
    val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), java.security.SecureRandom()) }
    try {
        okhttp3.OkHttpClient.Builder().connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
            .newCall(okhttp3.Request.Builder().url(url.trimEnd('/') + "/api/v1/ping").build()).execute().close()
    } catch (e: java.io.IOException) { if (pin.isEmpty()) throw WearApiException(0, "Can't reach $url") }
    pin
}

// ── the glance: status, anything waiting for approval, recent events ──────────────────
@Composable private fun Glance(store: WearStore, onUnpair: () -> Unit) {
    val api = remember { WearApi(store) }; val scope = rememberCoroutineScope()
    var ov by remember { mutableStateOf(runCatching { JSONObject(store.lastOverview) }.getOrNull()) }
    var approvals by remember { mutableStateOf(listOf<JSONObject>()) }
    var events by remember { mutableStateOf(listOf<JSONObject>()) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var confirmUnpair by remember { mutableStateOf(false) }
    suspend fun refresh() {
        try {
            val o = api.get("/api/v1/overview"); ov = o; store.lastOverview = o.toString()
            approvals = api.get("/api/v1/approvals").optJSONArray("approvals").objs().filter { it.optString("state") == "pending" }
            events = api.get("/api/v1/events").optJSONArray("events").objs().take(12)
            err = null
        } catch (e: WearApiException) { err = e.message; if (e.code == 401) onUnpair() }
    }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { refresh(); delay(if (approvals.isNotEmpty()) 5_000 else 20_000) } } }
    fun decide(a: JSONObject, ok: Boolean) = scope.launch {
        busy = a.optString("id")
        try { api.post("/api/v1/approvals/${a.optString("id")}/${if (ok) "approve" else "deny"}"); refresh() } catch (e: WearApiException) { err = e.message }
        busy = null
    }
    val list = rememberScalingLazyListState()
    ScreenScaffold(scrollState = list) {
        ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            val st = ov?.optJSONObject("status"); val lvl = st?.optString("level") ?: "ok"
            val m = st?.optJSONObject("metrics"); val cs = ov?.optJSONObject("containers")
            item { ListHeader { Text(ov?.optJSONObject("server")?.optString("display_name")?.ifEmpty { null } ?: store.server.ifEmpty { "Nova" }, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            item {
                Card(onClick = { scope.launch { refresh() } }, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (err != null) Color.Gray else levelColor(lvl)))
                        Spacer(Modifier.width(8.dp))
                        Text(when { err != null -> err!!; st == null -> "Connecting…"; lvl == "ok" -> "All systems normal"; else -> st.optString("headline").ifEmpty { "Needs attention" } },
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    val bits = listOfNotNull(m?.optString("cpu_temp")?.ifEmpty { null }?.let { "CPU $it" }, m?.optString("memory")?.ifEmpty { null }?.let { "RAM ${it.split(" ").first()}" },
                        cs?.let { "${it.optInt("running")}/${it.optInt("total")} running" })
                    if (bits.isNotEmpty()) Text(bits.joinToString(" · "), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    val tk = ov?.optJSONArray("tasks").objs()
                    if (tk.isNotEmpty()) Text(if (tk.size == 1) "${tk[0].optString("title")} · ${tk[0].optDouble("pct", 0.0).toInt()}%" else "${tk.size} in progress",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (approvals.isNotEmpty()) {
                item { ListHeader { Text("Waiting for you") } }
                approvals.forEach { a -> item {
                    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        Text(a.optString("what"), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("From ${a.optString("device_name")}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { decide(a, false) }, enabled = busy == null, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.filledTonalButtonColors()) { Text("Deny") }
                            Button(onClick = { decide(a, true) }, enabled = busy == null, modifier = Modifier.weight(1f)) { Text("Approve") }
                        }
                    }
                } }
            }
            if (events.isNotEmpty()) {
                item { ListHeader { Text("Recent") } }
                val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
                events.forEach { e -> item {
                    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(levelColor(e.optString("level"))))
                            Spacer(Modifier.width(6.dp))
                            Text(hm.format(Date((e.optDouble("t") * 1000).toLong())), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(e.optString("title").replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), ""), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                } }
            }
            item { FilledTonalButton(onClick = { confirmUnpair = true }, modifier = Modifier.padding(top = 10.dp)) { Text("Unpair") } }
        }
    }
    if (confirmUnpair) AlertDialog(visible = true, onDismissRequest = { confirmUnpair = false }, title = { Text("Unpair this watch?") },
        text = { Text("It forgets the server; pair again any time.") },
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = { confirmUnpair = false
            scope.launch { runCatching { api.call("DELETE", "/api/v1/devices/${store.deviceId}", null) }; onUnpair() } }) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = { confirmUnpair = false }) })
}
