package app.novalabs.nova

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

@Composable fun PairScreen(app: AppState, onCancel: (() -> Unit)? = null, onPaired: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }      // (url, pin) awaiting "it matches"
    var scanning by remember { mutableStateOf(false) }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scanning = true else error = "Camera permission is needed to scan — or type the code below." }

    fun pair(lan: String, c: String, pin: String = "") {
        busy = true; error = null; scanning = false
        scope.launch {
            try {
                val strong = app.pairing.keys.ensure()
                val r = NovaApi.pair(lan, c.trim().uppercase(), "${Build.MANUFACTURER} ${Build.MODEL}".trim(), app.pairing.keys.publicKeyPem(), pin)
                app.pairing.save(r.getString("device_id"), r.optString("lan_url").ifEmpty { lan }, r.optString("remote_url"),
                    r.optString("cf_client_id"), r.optString("cf_client_secret"), r.optString("lan_pin").ifEmpty { pin })
                app.pairing.label = r.optString("name")
                app.pairing.role = r.optString("role").ifEmpty { "admin" }
                runCatching {   // fingerprint-bound key for risky actions, registered while we're on home Wi-Fi
                    app.pairing.keys.ensureStepUp()
                    NovaApi(app.pairing).post("/api/v1/device/stepup-key", JSONObject().put("public_key", app.pairing.keys.stepUpPublicKeyPem()))
                    app.pairing.stepUpRegistered = true
                }
                android.widget.Toast.makeText(ctx, if (strong) "Paired · keys in StrongBox" else "Paired · keys in hardware keystore",
                    android.widget.Toast.LENGTH_LONG).show()
                Alerts.stop(ctx); Alerts.start(ctx)        // pick up the new server
                onPaired()
            } catch (e: Exception) { error = e.message; app.pairing.keys.wipe() } finally { busy = false }
        }
    }
    fun onScanned(raw: String) {
        runCatching { val o = JSONObject(raw); require(o.optInt("nova") == 1); pair(o.getString("lan"), o.getString("code"), o.optString("pin")) }
            .onFailure { scanning = false; error = "That isn't a Nova pairing code." }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
        Text("Nova", fontSize = 40.sp, fontWeight = FontWeight.Bold, color = N.text, modifier = Modifier.padding(start = 28.dp, top = 60.dp))
        Text(if (onCancel != null) "Add another server" else "Pair this phone with your server", color = N.sub, fontSize = 17.sp,
            modifier = Modifier.padding(start = 28.dp, top = 6.dp, bottom = 24.dp))
        if (scanning) {
            Box(Modifier.padding(horizontal = 24.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(32.dp))
                .border(3.dp, N.blue, RoundedCornerShape(32.dp))) {
                QrScanner(Modifier.fillMaxSize(), ::onScanned)
                Text("Point at the QR code on the server's screen", color = Color.White, fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(10.dp))
            }
            Box(Modifier.padding(start = 20.dp)) { PillButton("Cancel", color = N.text) { scanning = false } }
        } else Group {
            Row1("Scan pairing code", "Run  sudo nova-api pair  on the server, on home Wi-Fi", false, Icons.Rounded.QrCodeScanner,
                onClick = { if (ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
                            else camPerm.launch(Manifest.permission.CAMERA) })
        }
        SectionLabel("Or type the code")
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
        error?.let { Text(it, color = N.red, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp)) }
        confirm?.let { (url, pin) ->
            OneDialog({ confirm = null }, "Is this your server?",
                "Check that the code below matches the \"Certificate\" line shown by  sudo nova-api pair  on the server.",
                listOf(DialogButton("Cancel") { confirm = null }, DialogButton("It matches", N.blue) { confirm = null; pair(url, code, pin) })) {
                Text(pin.take(12).uppercase().chunked(4).joinToString(" "), color = N.text, fontSize = 28.sp, fontFamily = Mono,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        var guide by remember { mutableStateOf(false) }
        Box(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) { PillButton(if (guide) "Hide setup guide" else "New to Nova? How to set up your server") { guide = !guide } }
        androidx.compose.animation.AnimatedVisibility(guide) { Column { SetupGuideBody() } }
        if (onCancel != null) Box(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) { PillButton("Back to my servers", color = N.text, onClick = onCancel) }
        Text("On the server, run  sudo nova-api pair  and scan the code it shows (or type the address and code). Pairing only works on the server's home network.",
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
    Step(3, "Pair this phone", "On home Wi-Fi, show a pairing code on the server and scan it here. The code works once and expires in 10 minutes.",
        "sudo nova-api pair")
    Step(4, "Optional: use it away from home", "Without a VPN, put Nova behind Cloudflare Access (free) — the README's docs/REMOTE.md walks you through it, then run nova-setup again. With Tailscale, nova-setup turns it on for you.")
    Step(5, "Optional: other people, tablets, browsers", "Menu → Users & devices: invite phones as Admin or View only, and approve browsers (Nova web at https://<server>:8495). A spare tablet makes a nice always-on Dashboard.")
    Text("Security in a sentence: every request is signed by a key in this phone's secure chip, and anything risky also needs your fingerprint.",
        color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp))
}
