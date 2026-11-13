package app.novalabs.nova

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

/** The API contract (docs/API.md): the server's API level, and whether one side needs updating. */
object ApiCompat {
    /** The lowest API level this app needs. Raise it only when the app depends on something newer. */
    const val NEEDS_LEVEL = 0
    var serverLevel by androidx.compose.runtime.mutableIntStateOf(-1)          // -1 = not seen yet; 0 = a server from before levels
    var appTooOld by androidx.compose.runtime.mutableStateOf(false)
    val serverTooOld get() = serverLevel in 0 until NEEDS_LEVEL
}

class ApiException(val code: Int, message: String, val stepUpRequired: Boolean = false, val offline: Boolean = false,
                   val reason: String = "", val serverTime: Long = 0L) : Exception(message)

/** How far this device's clock is from each server's (ms, by profile). Requests are signed with the server's time, so a
 *  phone whose clock has drifted — or was set by hand — still works; the server tells us its time when it refuses one. */
object ServerClock {
    private val offsets = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun now(profile: String): Long = System.currentTimeMillis() + (offsets[profile] ?: 0L)
    fun learn(profile: String, serverTime: Long) {
        val off = serverTime - System.currentTimeMillis()
        // a few seconds is just the trip there and back; a request that sat on a sleeping phone isn't a wrong clock either
        if (kotlin.math.abs(off) > 20_000) offsets[profile] = off else offsets.remove(profile)
    }
    fun offMinutes(profile: String) = kotlin.math.abs(offsets[profile] ?: 0L) / 60_000
}

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
    // Tailscale: same certificate, a bit more patience (a relayed connection can take a few seconds)
    private var tailClientCache: OkHttpClient? = null; private var tailPinUsed: String? = null
    private val tailClient: OkHttpClient get() {
        val pin = pairing.lanPin
        if (tailClientCache == null || tailPinUsed != pin) { tailClientCache = pinned(OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS), pin).build(); tailPinUsed = pin }
        return tailClientCache!!
    }
    private val remoteClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    @Volatile var via: String = "—"; private set
    @Volatile private var preferred: String? = null

    private class Prepared(val method: String, val path: String, val bytes: ByteArray,
                           val ts: String, val nonce: String, val message: ByteArray)

    private fun prepare(method: String, path: String, body: JSONObject?): Prepared = prepareBytes(method, path, (body?.toString() ?: "").toByteArray())
    private fun prepareBytes(method: String, path: String, bytes: ByteArray): Prepared {
        val ts = ServerClock.now(pairing.profile).toString()
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
        val t0 = System.nanoTime(); var via = ""
        val once = { route(method) { name, base -> via = name; send(name, base, fixed ?: prepare(method, path, body), stepUpSig) } }
        val text = try {
            try { once() }
            catch (e: ApiException) {
                // Refused only for its timing: sign it again (with the server's clock, if ours is off) and send it once more.
                // A repeat ("replay") means the first copy arrived, so only a read is sent again — never an action.
                // (A step-up request can't be re-signed: its fingerprint signature covers that exact message.)
                val again = fixed == null && (e.reason == "clock" || (e.reason == "replay" && method == "GET"))
                if (!again) throw if (e.reason == "replay") ApiException(0, "Connection dropped — check whether it went through") else e
                if (e.reason == "clock") ServerClock.learn(pairing.profile, e.serverTime)
                try { once() } catch (e2: ApiException) {
                    if (e2.reason == "clock") throw ApiException(0, "This ${DeviceForm.noun}'s clock is ${ServerClock.offMinutes(pairing.profile).coerceAtLeast(1)} min off from the server's. " +
                        "Turn on automatic date and time in Android settings.", reason = "clock")
                    throw e2
                }
            }
        } catch (e: Exception) { DevLog.add(method, path, (e as? ApiException)?.code ?: -1, (System.nanoTime() - t0) / 1_000_000, via, e.message); throw e }
        DevLog.add(method, path, 200, (System.nanoTime() - t0) / 1_000_000, via, null)
        val obj = runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
        if (method == "GET" && Cache.isFor(pairing.profile)) Cache.put(path, obj)
        obj
    }

    /** After the app was in the background, pooled sockets are usually dead (NAT/Wi-Fi sleep). */
    // Closing TLS sockets does network I/O, so never on the main thread (crashed 0.2.5 off Wi-Fi).
    suspend fun wake() = withContext(Dispatchers.IO) {
        runCatching { lanClient.connectionPool.evictAll(); tailClient.connectionPool.evictAll(); remoteClient.connectionPool.evictAll() }
    }

    /** Risky action: fingerprint / PIN confirmation, then a second signature by the step-up key. */
    suspend fun stepUp(activity: Activity, title: String, method: String, path: String,
                       body: JSONObject? = null): JSONObject {
        if (!pairing.keys.hasStepUp() || !pairing.stepUpRegistered) {
            // No fingerprint key on this device (no fingerprint reader, no screen lock, or not set up yet): ask anyway, unsigned. An admin
            // device is told to wait — {"approval": …} — while another device, the server shell or an authenticator code approves it.
            try { return call(method, path, body, null, prepare(method, path, body)) }
            catch (e: ApiException) {
                if (e.stepUpRequired) throw ApiException(403, "Confirming risky actions needs a fingerprint or screen lock on this ${DeviceForm.noun} (or an admin device to approve). Add one in Android settings, then open Nova on home Wi-Fi.")
                throw e
            }
        }
        val pr = prepare(method, path, body)
        val sig = pairing.keys.stepUpSignature()
        val authed = suspendCancellableCoroutine { cont ->
            val cancel = Compat.authenticate(activity, title, "Nova · confirm it's you", sig, { cont.resume(it) },
                { m -> if (cont.isActive) cont.resumeWithException(ApiException(0, m)) })
            cont.invokeOnCancellation { cancel.cancel() }
        }
        val signer = authed ?: throw ApiException(0, "Confirmation failed")
        signer.update(pr.message)
        val s = android.util.Base64.encodeToString(signer.sign(), android.util.Base64.NO_WRAP)
        return call(method, path, body, s, pr)
    }

    /** POST raw bytes (file uploads and saves); the signature covers their SHA-256. */
    suspend fun postRaw(path: String, data: ByteArray): JSONObject = withContext(Dispatchers.IO) {
        val text = route("POST") { name, base -> send(name, base, prepareBytes("POST", path, data), null) }      // signed afresh per attempt
        runCatching { JSONObject(text) }.getOrElse { JSONObject().put("raw", text) }
    }

    /** Stream a download into [out], reporting (bytes so far, total). */
    suspend fun downloadTo(path: String, out: java.io.OutputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }) = withContext(Dispatchers.IO) {
        route("GET") { name, base ->
            client(name).newCall(build(name, base, prepare("GET", path, null), null)).execute().use { r ->
                if (!r.isSuccessful) throw ApiException(r.code, runCatching { JSONObject(r.body.string()).optString("error") }.getOrNull()?.ifEmpty { null } ?: "Download failed (${r.code})")
                val total = r.body.contentLength(); val src = r.body.byteStream(); val buf = ByteArray(256 * 1024); var n = 0L; var last = 0L
                while (true) { val k = src.read(buf); if (k < 0) break; out.write(buf, 0, k); n += k
                    if (System.currentTimeMillis() - last > 150) { last = System.currentTimeMillis(); onProgress(n, total) } }
                onProgress(n, total); ""
            }
        }
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
        (when { name == "home" && isTailscaleUrl(base) -> "Tailscale"; name == "home" -> "Home network"; name == "tailnet" -> "Tailscale"; else -> "Remote (Cloudflare)" }) +
            " · " + base.substringAfter("://").substringBefore("/")

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
            if (pairing.tailnetUrl.isNotEmpty() && pairing.tailnetUrl != pairing.lanUrl) add("tailnet" to pairing.tailnetUrl)
            if (pairing.remoteUrl.isNotEmpty()) add("remote" to pairing.remoteUrl)
        }.let { r -> preferred?.let { p -> r.sortedBy { if (it.first == p) 0 else 1 } } ?: r }      // whatever worked last goes first
        if (routes.isEmpty()) throw ApiException(0, "Not paired")
        // HTTPS only for Nova's own API (plain http is allowed app-wide only for the Apps frame)
        if (routes.any { !it.second.startsWith("https://") }) throw ApiException(0, "This pairing uses plain HTTP — open Nova on your home Wi-Fi to upgrade it, or pair again")
        var last: Exception = IOException("unreachable")
        val notes = mutableListOf<String>()
        for ((name, base) in routes) {
            for (attempt in 0..1) {
                try {
                    val r = block(name, base); via = name; preferred = if (name == "home") null else name; lastAttempts = emptyList(); return r
                } catch (e: ApiException) {
                    if (!e.offline) throw e
                    notes += "${routeName(name, base)}: ${e.message}"; last = IOException(e.message); break
                } catch (e: IOException) {
                    if (attempt == 1 || notSent(e)) notes += "${routeName(name, base)}: ${describe(e)}" +
                        (if (isTailscale(base) && e !is javax.net.ssl.SSLException) " — is Tailscale on and connected on this ${DeviceForm.noun}?" else "")
                    last = e
                    // A POST that may have arrived must not run twice (e.g. restart a container twice).
                    if (method != "GET" && !notSent(e)) throw ApiException(0, "Connection dropped — check whether it went through")
                    if (attempt == 0 && !notSent(e)) { client(name).connectionPool.evictAll(); continue }  // stale socket: retry fresh
                    break
                }
            }
        }
        if (notes.isNotEmpty() && vpnOn(pairing.appCtx) && !isTailscale(pairing.lanUrl))       // a full-tunnel VPN hides the home network
            notes += "A VPN is on: if it's Tailscale with an exit node, turn on Allow LAN access in Tailscale, or this ${DeviceForm.noun} can't reach the server at home"
        if (pairing.remoteUrl.isEmpty() && pairing.tailnetUrl.isEmpty() && !isTailscale(pairing.lanUrl))
            notes += "Away-from-home access isn't set up, so Nova only works on the home network (sudo nova setup → Away from home)"
        lastAttempts = notes
        throw ApiException(0, "Disconnected", offline = true)
    }

    // A "home" address that's really a Tailscale one (100.64.0.0/10 — a server set up with its Tailscale address) gets Tailscale's
    // patience: a relayed connection can take longer than the 1.5 s a real home network needs, and then it never connected at all.
    private fun client(route: String) = when (route) { "home" -> if (isTailscale(pairing.lanUrl)) tailClient else lanClient; "tailnet" -> tailClient; else -> remoteClient }
    private fun isTailscale(url: String) = isTailscaleUrl(url)

    private fun build(route: String, base: String, pr: Prepared, stepUpSig: String?, quick: Boolean = false): Request {
        val sig = if (quick) pairing.keys.signQuick(pr.message) else pairing.keys.sign(pr.message)
        val rb = Request.Builder().url(base.trimEnd('/') + pr.path)
            .header("X-Nova-Device", if (quick) pairing.quickApproverId else pairing.deviceId).header("X-Nova-Time", pr.ts)
            .header("X-Nova-Nonce", pr.nonce).header("X-Nova-Signature", sig)
        if (stepUpSig != null) rb.header("X-Nova-StepUp", stepUpSig)
        rb.header("X-Nova-Client", "android ${BuildConfig.VERSION_CODE}")      // the API contract: a server can tell a too-old app to update
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
            resp.header("X-Nova-Api-Level")?.toIntOrNull()?.let { ApiCompat.serverLevel = it }
            if (!resp.isSuccessful) {
                val obj = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                if (resp.code == 426) { ApiCompat.appTooOld = true; throw ApiException(426, obj.optString("error", "Update the app to keep using this server")) }
                if (resp.code == 401) when (obj.optString("reason")) {
                    "clock" -> throw ApiException(401, "Signed too early or too late", reason = "clock", serverTime = obj.optLong("server_time"))
                    "replay" -> throw ApiException(401, "Sent twice", reason = "replay")
                    else -> throw ApiException(401, "This ${DeviceForm.noun} isn't authorized anymore. Pair again.")
                }
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
                OkHttpClient.Builder().connectTimeout(if (isTailscaleUrl(url)) 8L else 4L, TimeUnit.SECONDS).sslSocketFactory(ctx.socketFactory, tm)
                    .hostnameVerifier { _, _ -> true }.build()
                    .newCall(Request.Builder().url(url.trimEnd('/') + "/api/v1/ping").build()).execute().close()
            } catch (e: IOException) { if (pin.isEmpty()) throw ApiException(0, if (isTailscaleUrl(url)) "Can't reach $url — check that Tailscale is running on this ${DeviceForm.noun}, signed in to the same account as the server."
                else "Can't reach $url — check the address and that you're on the server's network.") }
            pin
        }

        /** Pairing is unsigned, with the one-time code. At home it goes to the server's own encrypted address (the app pins its
         *  certificate); away from home it can go to the server's Tailscale address (same pinned certificate) or its Cloudflare
         *  address (an ordinary public certificate, with the access key the setup code carried). */
        suspend fun pair(lan: String, code: String, name: String, publicKeyPem: String, pin: String = "",
                         cfId: String = "", cfSecret: String = "", publicTls: Boolean = false, timeoutSec: Long = 4): JSONObject =
            withContext(Dispatchers.IO) {
                val body = JSONObject().put("code", code).put("name", name).put("public_key", publicKeyPem).put("form", DeviceForm.kind)
                val rb = Request.Builder().url(lan.trimEnd('/') + "/api/v1/pair")
                    .post(body.toString().toByteArray().toRequestBody("application/json".toMediaType()))
                if (cfId.isNotEmpty()) rb.header("CF-Access-Client-Id", cfId).header("CF-Access-Client-Secret", cfSecret)
                val req = rb.build()
                try {
                    if (lan.startsWith("https") && pin.isEmpty() && !publicTls) throw ApiException(0, "Missing the server's certificate — scan the QR code instead.")
                    val b = OkHttpClient.Builder().connectTimeout(timeoutSec, TimeUnit.SECONDS).readTimeout(maxOf(timeoutSec, 10), TimeUnit.SECONDS)
                        .followRedirects(false).followSslRedirects(false)          // a Cloudflare login page is a refusal here, not an answer
                    (if (publicTls) b else pinned(b, pin)).build().newCall(req).execute().use { r ->
                        val text = r.body.string(); val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                        if (!r.isSuccessful) throw ApiException(r.code, when {
                            publicTls && (r.code in 301..303 || (r.code in 401..403 && !text.trimStart().startsWith("{"))) -> "Cloudflare turned this away at the door — the setup code may be out of date. Make a new one with  sudo nova add --remote."
                            r.code == 401 -> "That code is wrong or expired. Run `sudo nova add` again."
                            r.code == 403 && publicTls -> "That code only works on the home network — make one for away from home:  sudo nova add --remote"
                            r.code == 403 -> o.optString("error").ifEmpty { "Pairing only works on your home Wi-Fi." }
                            r.code == 429 -> "Too many wrong tries — wait a few minutes."
                            else -> o.optString("error", "Pairing failed (${r.code})")
                        })
                        o
                    }
                } catch (e: IOException) {
                    throw ApiException(0, "Can't reach the server at $lan")
                }
            }

        private fun door(base: String, cookie: String, path: String) =
            Request.Builder().url(base.trimEnd('/') + path).header("Cookie", "CF_Authorization=$cookie").header("User-Agent", "Nova app")

        private suspend fun doorCall(rb: Request.Builder): JSONObject = withContext(Dispatchers.IO) {
            try {
                OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
                    .newCall(rb.build()).execute().use { r ->
                        val text = r.body.string(); val o = runCatching { JSONObject(text) }.getOrNull()
                        if (o == null || !r.isSuccessful) throw ApiException(r.code, when {
                            r.code in 301..303 || r.code == 401 || r.code == 403 && o == null -> "Cloudflare didn't accept the sign-in — try signing in again."
                            r.code == 429 -> "Too many tries — wait a few minutes."
                            else -> o?.optString("error")?.ifEmpty { null } ?: "The server answered ${r.code}."
                        })
                        o
                    }
            } catch (e: IOException) { throw ApiException(0, "Can't reach the server — check this ${DeviceForm.noun}'s internet connection.") }
        }

        /** Asks the server to let this phone or tablet in, after signing in at Cloudflare (no code needed): the answer is a short code
         *  to approve on an admin phone (or on the server with `sudo nova approve CODE`). */
        suspend fun requestAccess(base: String, cookie: String, name: String, publicKeyPem: String): JSONObject =
            doorCall(door(base, cookie, "/api/v1/browser/request").post(JSONObject().put("name", name).put("public_key", publicKeyPem).put("kind", "app").put("form", DeviceForm.kind)
                .toString().toByteArray().toRequestBody("application/json".toMediaType())))

        /** The request's state; once approved, the way in (handed over a single time). */
        suspend fun accessState(base: String, cookie: String, id: String): JSONObject = doorCall(door(base, cookie, "/api/v1/browser/request/$id").get())

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

/** A Tailscale address (100.64.0.0/10) in an https://host:port URL. */
fun isTailscaleUrl(url: String) = runCatching {
    val p = (java.net.URI(url).host ?: "").split(".").map { it.toInt() }; p.size == 4 && p[0] == 100 && p[1] in 64..127 }.getOrDefault(false)
