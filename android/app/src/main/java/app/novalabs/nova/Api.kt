package app.novalabs.nova

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String, val stepUpRequired: Boolean = false, val offline: Boolean = false) : Exception(message)

/**
 * Talks to nova-api. Tries the home-LAN address first (fast, works without internet),
 * then the Cloudflare address. Every request is signed with the hardware device key:
 *   METHOD \n PATH \n TIME_MS \n NONCE \n sha256hex(BODY)    — must match server.py exactly.
 * Risky actions additionally carry X-Nova-StepUp: the same message signed by the
 * fingerprint-bound step-up key (see [stepUp]).
 */
class NovaApi(private val pairing: Pairing) {
    private val json = "application/json".toMediaType()
    private val rnd = SecureRandom()
    // Home network: https with the server's own certificate pinned (from pairing), or plain http on
    // older installs. The client is rebuilt if the pin changes (e.g. after upgrading to TLS).
    private var lanPinUsed: String? = null
    private var lanClientCache: OkHttpClient? = null
    private val lanClient: OkHttpClient get() {
        val pin = pairing.lanPin
        if (lanClientCache == null || lanPinUsed != pin) {
            lanClientCache = pinned(OkHttpClient.Builder().connectTimeout(1500, TimeUnit.MILLISECONDS).readTimeout(60, TimeUnit.SECONDS), pin).build()
            lanPinUsed = pin
        }
        return lanClientCache!!
    }
    private val remoteClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    @Volatile var via: String = "—"; private set
    @Volatile private var preferRemote = false

    private class Prepared(val method: String, val path: String, val bytes: ByteArray,
                           val ts: String, val nonce: String, val message: ByteArray)

    private fun prepare(method: String, path: String, body: JSONObject?): Prepared {
        val bytes = (body?.toString() ?: "").toByteArray()
        val ts = System.currentTimeMillis().toString()
        val nonce = ByteArray(18).also { rnd.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return Prepared(method, path, bytes, ts, nonce, "$method\n$path\n$ts\n$nonce\n$digest".toByteArray())
    }

    /** Approve or deny a waiting request with the quick-approve key (notification / watch buttons). */
    suspend fun quickDecide(approvalId: String, approve: Boolean): JSONObject = withContext(Dispatchers.IO) {
        val pr = prepare("POST", "/api/v1/approvals/$approvalId/${if (approve) "approve" else "deny"}", null)
        val text = route("POST") { name, base ->
            client(name).newCall(build(name, base, pr, null, quick = true)).execute().use { r ->
                val t = r.body.string(); if (!r.isSuccessful) throw ApiException(r.code, runCatching { JSONObject(t).optString("error") }.getOrNull()?.ifEmpty { null } ?: "HTTP ${r.code}"); t }
        }
        runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    suspend fun get(path: String) = call("GET", path, null)
    suspend fun post(path: String, body: JSONObject = JSONObject()) = call("POST", path, body)
    suspend fun delete(path: String) = call("DELETE", path, null)

    suspend fun call(method: String, path: String, body: JSONObject?, stepUpSig: String? = null,
                     prepared: Any? = null): JSONObject = withContext(Dispatchers.IO) {
        // Each attempt is signed afresh (new time + nonce), except step-up calls whose second
        // signature is bound to one message by the fingerprint prompt.
        val fixed = prepared as? Prepared
        val text = route(method) { name, base -> send(name, base, fixed ?: prepare(method, path, body), stepUpSig) }
        val obj = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
        if (method == "GET" && Cache.isFor(pairing.profile)) Cache.put(path, obj)
        obj
    }

    /** After the app was in the background, pooled sockets are usually dead (NAT/Wi-Fi sleep). */
    // Closing TLS sockets does network I/O, so never on the main thread (crashed 0.2.5 off Wi-Fi).
    suspend fun wake() = withContext(Dispatchers.IO) {
        runCatching { lanClient.connectionPool.evictAll(); remoteClient.connectionPool.evictAll() }
    }

    /** Risky action: fingerprint / PIN confirmation, then a second signature by the step-up key. */
    suspend fun stepUp(activity: Activity, title: String, method: String, path: String,
                       body: JSONObject? = null): JSONObject {
        if (!pairing.keys.hasStepUp() || !pairing.stepUpRegistered)
            throw ApiException(403, "Fingerprint confirmation isn't set up yet — open Settings → This ${DeviceForm.noun} on home Wi-Fi.")
        val pr = prepare(method, path, body)
        val sig = pairing.keys.stepUpSignature()
        val authed = suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title).setSubtitle("Nova · confirm it's you")
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                .build()
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            prompt.authenticate(BiometricPrompt.CryptoObject(sig), cancel, activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                        cont.resume(r.cryptoObject.signature)
                    }
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        if (cont.isActive) cont.resumeWithException(ApiException(0, msg.toString()))
                    }
                })
        }
        val signer = authed ?: throw ApiException(0, "Confirmation failed")
        signer.update(pr.message)
        val s = android.util.Base64.encodeToString(signer.sign(), android.util.Base64.NO_WRAP)
        return call(method, path, body, s, pr)
    }

    /** Raw bytes (the update APK). */
    suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        var out = ByteArray(0)
        route("GET") { name, base ->
            client(name).newCall(build(name, base, prepare("GET", path, null), null)).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, "Download failed (${r.code})")
                out = r.body.bytes(); ""
            }
        }
        out
    }

    /** Speed test: stream a download, reporting (bytes so far, ms since the first byte); returns the same at the end. */
    suspend fun speedDown(path: String, onProgress: (Long, Long) -> Unit): Pair<Long, Long> = withContext(Dispatchers.IO) {
        var res = 0L to 0L
        route("GET") { name, base ->
            client(name).newCall(build(name, base, prepare("GET", path, null), null)).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, "Speed test failed (${r.code})")
                val src = r.body.byteStream(); val buf = ByteArray(64 * 1024)
                var n = 0L; val t0 = System.nanoTime(); var last = 0L
                while (true) {
                    val k = src.read(buf); if (k < 0) break
                    n += k; val ms = (System.nanoTime() - t0) / 1_000_000
                    if (ms - last > 200) { last = ms; onProgress(n, ms) }
                }
                res = n to (System.nanoTime() - t0) / 1_000_000; ""
            }
        }
        res
    }

    /** The request certainly never reached the server, so sending it again is always safe. */
    private fun notSent(e: IOException) = e is java.net.ConnectException || e is java.net.UnknownHostException ||
        e is java.net.UnknownServiceException ||           // plain http refused by the security policy (old pairings)
        e is java.net.NoRouteToHostException || (e is java.net.SocketTimeoutException && e.message.orEmpty().contains("connect", true))

    /** What each route said on the last failed attempt — shown when you tap "Disconnected". */
    @Volatile var lastAttempts: List<String> = emptyList(); private set

    private fun routeName(name: String, base: String) =
        (if (name == "home") "Home network" else "Remote (Cloudflare)") + " · " + base.substringAfter("://").substringBefore("/")

    private fun describe(e: IOException): String = when (e) {
        is java.net.UnknownHostException -> "no internet, or the name can't be found"
        is java.net.ConnectException, is java.net.NoRouteToHostException -> "nothing answered (not on that network, or the server is off)"
        is java.net.SocketTimeoutException -> "timed out"
        is javax.net.ssl.SSLException -> "secure connection failed (${e.message?.take(60)})"
        else -> e.message?.take(80) ?: "connection failed"
    }

    private fun cloudflareSays(code: Int) = when (code) {
        530, 523, 521, 1033 -> "Cloudflare is up, but your server isn't connected to it (off, restarting or no internet) — $code"
        522, 524, 504 -> "Cloudflare reached the server but it didn't answer in time — $code"
        else -> "Cloudflare couldn't get an answer from the server — $code"
    }

    private inline fun route(method: String, block: (String, String) -> String): String {
        val routes = buildList {
            if (pairing.lanUrl.isNotEmpty()) add("home" to pairing.lanUrl)
            if (pairing.remoteUrl.isNotEmpty()) add("remote" to pairing.remoteUrl)
        }.let { if (preferRemote) it.reversed() else it }
        if (routes.isEmpty()) throw ApiException(0, "Not paired")
        // HTTPS only for Nova's own API (plain http is allowed app-wide only for the Apps frame)
        if (routes.any { !it.second.startsWith("https://") }) throw ApiException(0, "This pairing uses plain HTTP — open Nova on your home Wi-Fi to upgrade it, or pair again")
        var last: Exception = IOException("unreachable")
        val notes = mutableListOf<String>()
        for ((name, base) in routes) {
            for (attempt in 0..1) {
                try {
                    val r = block(name, base); via = name; preferRemote = name == "remote"; lastAttempts = emptyList(); return r
                } catch (e: ApiException) {
                    if (!e.offline) throw e
                    notes += "${routeName(name, base)}: ${e.message}"; last = IOException(e.message); break
                } catch (e: IOException) {
                    if (attempt == 1 || notSent(e)) notes += "${routeName(name, base)}: ${describe(e)}"
                    last = e
                    // A POST that may have arrived must not run twice (e.g. restart a container twice).
                    if (method != "GET" && !notSent(e)) throw ApiException(0, "Connection dropped — check whether it went through")
                    if (attempt == 0 && !notSent(e)) { client(name).connectionPool.evictAll(); continue }  // stale socket: retry fresh
                    break
                }
            }
        }
        if (pairing.remoteUrl.isEmpty()) notes += "Remote access isn't set up, so Nova only works on the home network"
        lastAttempts = notes
        throw ApiException(0, "Disconnected", offline = true)
    }

    private fun client(route: String) = if (route == "home") lanClient else remoteClient

    private fun build(route: String, base: String, pr: Prepared, stepUpSig: String?, quick: Boolean = false): Request {
        val sig = if (quick) pairing.keys.signQuick(pr.message) else pairing.keys.sign(pr.message)
        val rb = Request.Builder().url(base.trimEnd('/') + pr.path)
            .header("X-Nova-Device", if (quick) pairing.quickApproverId else pairing.deviceId).header("X-Nova-Time", pr.ts)
            .header("X-Nova-Nonce", pr.nonce).header("X-Nova-Signature", sig)
        if (stepUpSig != null) rb.header("X-Nova-StepUp", stepUpSig)
        if (route == "remote" && pairing.cfClientId.isNotEmpty()) {
            rb.header("CF-Access-Client-Id", pairing.cfClientId)
            rb.header("CF-Access-Client-Secret", pairing.cfClientSecret)
        }
        if (pr.method == "GET") rb.get() else rb.method(pr.method, pr.bytes.toRequestBody(json))
        return rb.build()
    }

    private fun send(route: String, base: String, pr: Prepared, stepUpSig: String?): String {
        client(route).newCall(build(route, base, pr, stepUpSig)).execute().use { resp ->
            val text = resp.body.string()
            if (!resp.isSuccessful) {
                val obj = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                if (resp.code == 401) throw ApiException(401, "This ${DeviceForm.noun} isn't authorized anymore. Pair again.")
                if (obj.optString("error") == "view_only")
                    throw ApiException(403, "This ${DeviceForm.noun} has view-only access — ask an admin to change it.")
                if (obj.optString("error") == "stepup_required")
                    throw ApiException(403, obj.optString("message", "Needs fingerprint confirmation"), stepUpRequired = true)
                if (route == "remote" && !obj.has("error") && (resp.code in 520..530 || resp.code in 502..504))
                    throw ApiException(resp.code, cloudflareSays(resp.code), offline = true)
                throw ApiException(resp.code, obj.optString("error", "HTTP ${resp.code}"))
            }
            return text
        }
    }

    companion object {
        /** Trust exactly one certificate (by SHA-256 of its DER); the pin replaces CA + hostname checks. */
        fun pinned(b: OkHttpClient.Builder, pin: String): OkHttpClient.Builder {
            if (pin.isEmpty()) return b
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                    val got = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                    if (!got.equals(pin, true)) throw java.security.cert.CertificateException("Server certificate doesn't match this pairing")
                }
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            }
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            return b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }

        /** For typed-in pairing: read the certificate a server presents so the user can compare it. */
        suspend fun probePin(url: String): String = withContext(Dispatchers.IO) {
            var pin = ""
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                    pin = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) } }
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            }
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            try {
                OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).sslSocketFactory(ctx.socketFactory, tm)
                    .hostnameVerifier { _, _ -> true }.build()
                    .newCall(Request.Builder().url(url.trimEnd('/') + "/api/v1/ping").build()).execute().close()
            } catch (e: IOException) { if (pin.isEmpty()) throw ApiException(0, "Can't reach $url — check the address and that you're on the server's network.") }
            pin
        }

        /** Pairing happens on the home LAN only, unsigned, with the one-time code. */
        suspend fun pair(lan: String, code: String, name: String, publicKeyPem: String, pin: String = ""): JSONObject =
            withContext(Dispatchers.IO) {
                val body = JSONObject().put("code", code).put("name", name).put("public_key", publicKeyPem).put("form", DeviceForm.kind)
                val req = Request.Builder().url(lan.trimEnd('/') + "/api/v1/pair")
                    .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType())).build()
                try {
                    if (lan.startsWith("https") && pin.isEmpty()) throw ApiException(0, "Missing the server's certificate — scan the QR code instead.")
                    pinned(OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS), pin).build().newCall(req).execute().use { r ->
                        val o = runCatching { JSONObject(r.body.string()) }.getOrDefault(JSONObject())
                        if (!r.isSuccessful) throw ApiException(r.code, when (r.code) {
                            401 -> "That code is wrong or expired. Run `sudo nova add` again."
                            403 -> "Pairing only works on your home Wi-Fi."
                            else -> o.optString("error", "Pairing failed (${r.code})")
                        })
                        o
                    }
                } catch (e: IOException) {
                    throw ApiException(0, "Can't reach the server at $lan — are you on home Wi-Fi?")
                }
            }

        /** Crash reports from before pairing go to the LAN address unsigned (server accepts LAN only). */
        fun sendAnonymousCrash(lan: String, report: String, version: String) = runCatching {
            val body = JSONObject().put("report", report).put("version", version).toString()
            OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).build().newCall(
                Request.Builder().url(lan.trimEnd('/') + "/api/v1/crash")
                    .post(body.toByteArray().toRequestBody("application/json".toMediaType())).build()
            ).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
