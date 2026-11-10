package app.novalabs.nova

import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Live camera + on-device ML Kit QR detection (bundled model: nothing to download). */
@Composable fun QrScanner(modifier: Modifier, onCode: (String) -> Unit) {
    val ctx = LocalContext.current; val owner = LocalLifecycleOwner.current
    val done = remember { AtomicBoolean(false) }
    val exec = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { exec.shutdown() } }
    AndroidView(factory = { c ->
        val view = PreviewView(c).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        val providerF = ProcessCameraProvider.getInstance(c)
        providerF.addListener({
            val provider = providerF.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(exec) { proxy ->
                val img = proxy.image
                if (img == null || done.get()) { proxy.close(); return@setAnalyzer }
                scanner.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        codes.firstOrNull()?.rawValue?.let { v -> if (done.compareAndSet(false, true)) ContextCompat.getMainExecutor(c).execute { onCode(v) } }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
        }, ContextCompat.getMainExecutor(c))
        view
    }, modifier = modifier)
}

/** What a pairing QR or pasted setup code holds. The home-network ones have just the first four; a code made for away from home
 *  (`sudo nova add --remote`) also has the server's Tailscale address and the key to its Cloudflare door. */
data class PairInfo(val lan: String, val pin: String, val remote: String, val tailnet: String, val cfId: String, val cfSecret: String, val code: String)

fun parsePairInfo(raw: String): PairInfo? = runCatching {
    var t = raw.trim()
    if (t.startsWith("nova1:", true)) t = String(android.util.Base64.decode(t.substring(6).trim(), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))
    val o = JSONObject(t); require(o.optInt("nova") == 1)
    val cf = o.optJSONObject("cf")
    PairInfo(o.optString("lan"), o.optString("pin"), o.optString("remote"), o.optString("tailnet"), cf?.optString("id").orEmpty(), cf?.optString("secret").orEmpty(), o.getString("code"))
}.getOrNull()

/** Signs in at Cloudflare in a web view (the same login as Nova web) and hands back the sign-in cookie. */
@Composable fun CloudflareLogin(host: String, onCookie: (String) -> Unit, onCancel: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel", tint = N.text) }
            Text("Sign in to $host", color = N.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
        AndroidView(factory = { c ->
            val cm = android.webkit.CookieManager.getInstance(); cm.setAcceptCookie(true); cm.removeAllCookies(null)       // a fresh sign-in, not whoever used this last
            android.webkit.WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(v: android.webkit.WebView, url: String?) {
                        val u = runCatching { java.net.URL(url) }.getOrNull() ?: return
                        // the page after signing in is the server's own "ping": it's only reachable once Cloudflare has let this session through
                        if (u.host.equals(host, true) && u.path.startsWith("/api/v1/ping")) {
                            val ck = cm.getCookie("https://$host").orEmpty().split(";").map { it.trim() }.firstOrNull { it.startsWith("CF_Authorization=") }
                            if (ck != null) onCookie(ck.substringAfter("="))
                        }
                    }
                }
                loadUrl("https://$host/api/v1/ping")
            }
        }, modifier = Modifier.fillMaxSize().weight(1f))
    }
}

@Composable fun PairScreen(app: AppState, onCancel: (() -> Unit)? = null, onPaired: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(PairHint.address ?: "") }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }      // (url, pin) awaiting "it matches"
    var scanning by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf("") }                                  // a pasted setup code (away from home)
    var awayHost by remember { mutableStateOf(PairHint.awayHost ?: "") }
    var askHost by remember { mutableStateOf(false) }
    var loginHost by remember { mutableStateOf<String?>(null) }                   // showing the Cloudflare sign-in
    var waiting by remember { mutableStateOf<JSONObject?>(null) }                 // the request awaiting approval: id, code, who
    var waitingBase by remember { mutableStateOf("") }; var waitingCookie by remember { mutableStateOf("") }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else error = "Camera permission is needed to scan — or type the code below." }

    /** The server said yes: remember it, set up the fingerprint key, and go in. */
    suspend fun complete(r: JSONObject, lan: String, pin: String, reached: String = "") {
        val strong = app.pairing.keys.ensure()
        val home = r.optString("lan_url").ifEmpty { lan }
        app.pairing.save(r.getString("device_id"), home, r.optString("remote_url"),
            r.optString("cf_client_id"), r.optString("cf_client_secret"), r.optString("lan_pin").ifEmpty { pin })
        // Paired at an address other than the server's home one (its Tailscale address, say): keep it as a route too. The server's
        // own home address may not be reachable from here at all, and without this the app only ever tried that one — "Disconnected".
        if (reached.isNotEmpty() && android.net.Uri.parse(reached).host != android.net.Uri.parse(home).host) app.pairing.tailnetUrl = reached
        app.pairing.label = r.optString("name"); PairHint.address = null; PairHint.name = null
        app.pairing.role = r.optString("role").ifEmpty { "admin" }
        runCatching {   // fingerprint-bound key for risky actions: registered on home Wi-Fi, or right now when we just paired from away
            app.pairing.keys.ensureStepUp()
            NovaApi(app.pairing).post("/api/v1/device/stepup-key", JSONObject().put("public_key", app.pairing.keys.stepUpPublicKeyPem()))
            app.pairing.stepUpRegistered = true
        }
        android.widget.Toast.makeText(ctx, if (strong) "Paired · keys in StrongBox" else "Paired · keys in hardware keystore", android.widget.Toast.LENGTH_LONG).show()
        Alerts.stop(ctx); Alerts.start(ctx)        // pick up the new server
        onPaired()
    }

    /** Home network first (quickly), then the server's Tailscale address, then its Cloudflare address with the key the code carried. */
    fun pairInfo(info: PairInfo) {
        busy = true; error = null; scanning = false
        scope.launch {
            try {
                app.pairing.keys.ensure()
                val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim(); val pem = app.pairing.keys.publicKeyPem()
                val away = info.tailnet.isNotEmpty() || info.cfId.isNotEmpty()
                val tries = buildList {
                    if (info.lan.isNotEmpty()) add(Triple("home", info.lan, if (away) 3L else 4L))
                    if (info.tailnet.isNotEmpty()) add(Triple("tailnet", info.tailnet, 6L))
                    if (info.remote.isNotEmpty() && away) add(Triple("remote", info.remote, 20L))
                }
                var r: JSONObject? = null; var usedLan = info.lan; var reached = ""
                for ((route, url, t) in tries) {
                    try { r = NovaApi.pair(url, info.code.trim().uppercase(), name, pem, if (route == "remote") "" else info.pin, if (route == "remote") info.cfId else "",
                            if (route == "remote") info.cfSecret else "", publicTls = route == "remote", timeoutSec = t); if (route != "remote") reached = url; break }
                    catch (e: ApiException) { if (e.code != 0) throw e }                  // anything but "couldn't reach it" is a real answer: stop
                }
                if (r == null) throw ApiException(0, if (away) "Couldn't reach the server from here (home network, Tailscale or Cloudflare). Check this ${DeviceForm.noun}'s internet connection and that the code is still fresh."
                    else "Can't reach the server at ${info.lan} — are you on home Wi-Fi? Away from home, make a code with  sudo nova add --remote.")
                complete(r, usedLan, info.pin, reached)
            } catch (e: Exception) { error = e.message; app.pairing.keys.wipe() } finally { busy = false }
        }
    }
    fun onScanned(raw: String) { val i = parsePairInfo(raw); if (i == null) { scanning = false; error = "That isn't a Nova pairing code." } else pairInfo(i) }

    /** No code: sign in at Cloudflare, ask to be let in, and wait for approval from an admin phone (or the server). */
    fun startSignIn(host: String) {
        val h = host.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
        if (!Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}").matches(h)) { error = "An address like nova.example.com"; return }
        awayHost = h; PairHint.awayHost = h; error = null; loginHost = h
    }
    fun onCookie(host: String, cookie: String) {
        loginHost = null; busy = true; error = null
        scope.launch {
            try {
                app.pairing.keys.ensure(); val base = "https://$host"
                val r = NovaApi.requestAccess(base, cookie, "${Build.MANUFACTURER} ${Build.MODEL}".trim(), app.pairing.keys.publicKeyPem())
                waitingBase = base; waitingCookie = cookie; waiting = r
            } catch (e: Exception) { error = e.message; app.pairing.keys.wipe() } finally { busy = false }
        }
    }
    // while a request waits: ask every 2 seconds whether it's been approved
    waiting?.let { w ->
        LaunchedEffect(w.optString("id")) {
            val until = System.currentTimeMillis() + 10 * 60_000L
            while (System.currentTimeMillis() < until) {
                kotlinx.coroutines.delay(2000)
                val st = runCatching { NovaApi.accessState(waitingBase, waitingCookie, w.optString("id")) }.getOrNull() ?: continue
                if (st.optString("state") == "approved") { waiting = null; runCatching { complete(st, "", st.optString("lan_pin")) }.onFailure { error = it.message; app.pairing.keys.wipe() }; return@LaunchedEffect }
                if (st.optString("state") == "expired") break
            }
            waiting = null; if (error == null) error = "Nobody approved it in time. Start again when someone can approve it."; app.pairing.keys.wipe()
        }
    }

    if (settings) { OfflineSettings(app) { settings = false }; return }
    loginHost?.let { h -> CloudflareLogin(h, { c -> onCookie(h, c) }, { loginHost = null }); return }
    if (onCancel != null) androidx.activity.compose.BackHandler(onBack = onCancel)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onCancel != null) Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onCancel), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to my servers", tint = N.text) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(48.dp).clip(CircleShape).clickable { settings = true }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Settings, "Settings", tint = N.text) }
        }
        Text("Nova", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = N.text, modifier = Modifier.padding(start = 28.dp, top = if (onCancel != null) 0.dp else 36.dp))
        Text(if (onCancel != null) "Add another server" else "Pair this ${DeviceForm.noun} with your server", color = N.sub, fontSize = 17.sp,
            modifier = Modifier.padding(start = 28.dp, top = 6.dp, bottom = 24.dp))
        if (waiting != null) {
            val w = waiting!!
            SectionLabel("Waiting for approval")
            Group { Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(w.optString("code"), color = N.text, fontSize = 40.sp, fontFamily = Mono, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Approve this on an admin phone: Nova → Users & devices → approve a browser or watch → enter this code.\nOr on the server:  sudo nova approve ${w.optString("code")}",
                    color = N.sub, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (w.optString("who").isNotEmpty()) Text("Signed in as ${w.optString("who")}", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(12.dp)); OneSpinner(24.dp)
            } }
            Box(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) { PillButton("Cancel", color = N.text) { waiting = null; app.pairing.keys.wipe() } }
            error?.let { Text(it, color = N.red, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) }
            return@Column
        }
        if (scanning) {
            Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(32.dp))
                .border(3.dp, N.blue, RoundedCornerShape(32.dp))) {
                QrScanner(Modifier.fillMaxSize(), ::onScanned)
                Text("Point at the QR code on the server's screen", color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(10.dp))
            }
            Box(Modifier.padding(start = 20.dp)) { PillButton("Cancel", color = N.text) { scanning = false } }
        } else Group {
            Row1("Scan pairing code", "Run  sudo nova add  on the server — or  sudo nova add --remote  when you're away from home", false, Icons.Rounded.QrCodeScanner,
                onClick = { if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                            else camPerm.launch(Manifest.permission.CAMERA) })
        }
        if (!scanning) DiscoveredServers(app) { srv -> address = srv.optString("lan_url").substringAfter("//"); error = null
            android.widget.Toast.makeText(ctx, "Now type the code shown by  sudo nova add  on ${srv.optString("name")}", android.widget.Toast.LENGTH_LONG).show() }
        SectionLabel(if (address.isNotEmpty() && PairHint.name != null) "Pair with ${PairHint.name}" else "Or type the code")
        Group {
            Column(Modifier.padding(20.dp)) {
                OneTextField(address, { address = it.trim().take(80) }, "Server address (e.g. 192.168.1.20)", Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                Spacer(Modifier.height(10.dp))
                OneTextField(code, { code = it.filter { c -> c.isLetterOrDigit() }.take(12) }, "Pairing code", Modifier.fillMaxWidth(), mono = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (busy) "Pairing…" else "Pair", Modifier.fillMaxWidth(), !busy && code.length >= 8 && address.isNotEmpty()) {
                    val a = address.removeSuffix("/").removePrefix("https://").removePrefix("http://")
                    val url = "https://" + (if (":" in a) a else "$a:8495")
                    busy = true; error = null
                    scope.launch { try { confirm = url to NovaApi.probePin(url) } catch (e: Exception) { error = e.message } finally { busy = false } } }
            }
        }
        if (!scanning) {
            SectionLabel("Away from home?")
            Group {
                Row1("Sign in with Cloudflare", "No code needed: sign in with your Cloudflare login, then approve this ${DeviceForm.noun} from an admin phone or the server. Works on any Android.",
                    false, Icons.Rounded.Cloud, onClick = { askHost = true })
                RowDivider()
                Column(Modifier.padding(20.dp)) {
                    Text("Or paste a setup code (from  sudo nova add --remote  or Invite a phone → away from home)", color = N.sub, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OneTextField(setup, { setup = it.trim().take(1500) }, "nova1:…", Modifier.fillMaxWidth(), mono = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) { PillButton("Paste", color = N.text) {
                            ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()?.let { setup = it.take(1500) } } }
                        Box(Modifier.weight(1f)) { PillButton(if (busy) "Pairing…" else "Pair", enabled = !busy && setup.isNotEmpty()) {
                            val i = parsePairInfo(setup); if (i == null) error = "That isn't a Nova setup code." else pairInfo(i) } }
                    }
                }
            }
        }
        error?.let { Text(it, color = N.red, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) }
        confirm?.let { (url, pin) ->
            OneDialog({ confirm = null }, "Is this your server?",
                "Check that the code below matches the \"Certificate\" line shown by  sudo nova add  on the server.",
                listOf(DialogButton("Cancel") { confirm = null }, DialogButton("It matches", N.blue) { confirm = null; pairInfo(PairInfo(url, pin, "", "", "", "", code)) })) {
                Text(pin.take(12).uppercase().chunked(4).joinToString(" "), color = N.text, fontSize = 28.sp, fontFamily = Mono,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        if (askHost) OneDialog({ askHost = false }, "Your server's web address",
            "The address you open Nova web at from anywhere (the Cloudflare one), like nova.example.com. You'll sign in there with the same login as in a browser — one-time email code works; Google sign-in doesn't work inside an app, so use a setup code then.",
            listOf(DialogButton("Cancel") { askHost = false }, DialogButton("Sign in", N.blue, awayHost.contains('.')) { askHost = false; startSignIn(awayHost) })) {
            OneTextField(awayHost, { awayHost = it.trim().lowercase().take(120) }, "nova.example.com", Modifier.fillMaxWidth().padding(horizontal = 22.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
        }
        var guide by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) { PillButton(if (guide) "Hide setup guide" else "New to Nova? How to set up your server") { guide = !guide } }
        androidx.compose.animation.AnimatedVisibility(guide) { Column { SetupGuideBody() } }
        if (onCancel != null) Box(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) { PillButton("Back to my servers", color = N.text, onClick = onCancel) }
        Text("At home: run  sudo nova add  on the server and scan the code it shows (or type the address and code). Away from home: use  sudo nova add --remote,  or sign in with Cloudflare above.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
    }
}

/** Step-by-step setup, shown from the pairing screen and from Settings. */
@Composable fun SetupGuideBody() {
    @Composable fun Step(n: Int, title: String, body: String, cmd: String? = null) {
        Group {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(androidx.compose.foundation.shape.CircleShape).background(N.blue), contentAlignment = Alignment.Center) {
                        Text("$n", color = Color.White, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp)); Text(title, color = N.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp)); Text(body, color = N.sub, fontSize = 15.sp)
                if (cmd != null) { Spacer(Modifier.height(10.dp))
                    Text(cmd, color = N.text, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp)).background(N.pill).padding(12.dp)) }
            }
        }
    }
    Text("Nova has two parts: this app, and a small server package on the Linux machine you want to control (Debian or Ubuntu).",
        color = N.sub, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
    Step(1, "Install the server package", "On the server, download the Nova package (nova-server_….deb) and install it — or build it from the source code.",
        "sudo apt install ./nova-server_*.deb\n# or from source:\ngit clone <repo> && cd nova && sudo ./server/install.sh")
    Step(2, "Run the setup wizard", "It finds your home network, asks a few questions (press Enter for the suggested answers) and starts Nova.",
        "sudo nova-setup")
    Step(3, "Pair this ${DeviceForm.noun}", "On home Wi-Fi, show a pairing code on the server and scan it here. The code works once and expires in 10 minutes.",
        "sudo nova add")
    Step(4, "Optional: use it away from home", "Without a VPN, put Nova behind Cloudflare Access (free) — the README's docs/REMOTE.md walks you through it, then run nova-setup again. With Tailscale, nova-setup turns it on for you.")
    Step(5, "Optional: other people, tablets, browsers", "Menu → Users & devices: invite phones as Admin or View only, and approve browsers (Nova web at https://<server>:8495). A spare tablet makes a nice always-on Dashboard.")
    Text("Security in a sentence: every request is signed by a key in this ${DeviceForm.noun}'s secure chip, and anything risky also needs your fingerprint.",
        color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp))
}
