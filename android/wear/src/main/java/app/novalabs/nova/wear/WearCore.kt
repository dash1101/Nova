package app.novalabs.nova.wear

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.util.concurrent.TimeUnit

/**
 * The watch's own identity: a P-256 key made in the watch's keystore. It never leaves the watch and
 * only signs while the watch is unlocked (on your wrist), which is why the server lets it approve.
 */
object WearKeys {
    private const val ALIAS = "nova_wear"
    private val ks get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensure() {
        if (ks.containsAlias(ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUnlockedDeviceRequired(true)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    fun publicPem(): String {
        ensure()
        val b64 = Base64.encodeToString(ks.getCertificate(ALIAS).publicKey.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    fun sign(data: ByteArray): String {
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(ALIAS, null) as PrivateKey); update(data) }.sign()
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    fun wipe() { runCatching { ks.deleteEntry(ALIAS) } }
}

/** Where the server is and who this watch is to it. Nothing secret: the key stays in the keystore. */
class WearStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("nova_wear", Context.MODE_PRIVATE)
    var url: String get() = p.getString("url", "")!!; set(v) = p.edit().putString("url", v).apply()
    var pin: String get() = p.getString("pin", "")!!; set(v) = p.edit().putString("pin", v).apply()
    var deviceId: String get() = p.getString("device", "")!!; set(v) = p.edit().putString("device", v).apply()
    var server: String get() = p.getString("server", "")!!; set(v) = p.edit().putString("server", v).apply()
    var lastOverview: String get() = p.getString("overview", "")!!; set(v) = p.edit().putString("overview", v).apply()
    val paired get() = deviceId.isNotEmpty() && url.isNotEmpty()
    fun clear() = p.edit().clear().apply()
}

class WearApiException(val code: Int, message: String) : Exception(message)

/** Signed requests to nova-api over HTTPS, trusting exactly the server's own certificate (pinned). */
class WearApi(private val store: WearStore) {
    private val rnd = SecureRandom()
    private var client: OkHttpClient? = null; private var clientPin = ""
    private fun http(): OkHttpClient {
        if (client == null || clientPin != store.pin) { client = pinned(store.pin).build(); clientPin = store.pin }
        return client!!
    }

    suspend fun get(path: String) = call("GET", path, null)
    suspend fun post(path: String, body: JSONObject = JSONObject()) = call("POST", path, body)

    suspend fun call(method: String, path: String, body: JSONObject?): JSONObject = withContext(Dispatchers.IO) {
        val bytes = (body?.toString() ?: "").toByteArray()
        val ts = System.currentTimeMillis().toString()
        val nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also { rnd.nextBytes(it) })
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val msg = "$method\n$path\n$ts\n$nonce\n$digest".toByteArray()
        val rb = Request.Builder().url(store.url.trimEnd('/') + path).header("X-Nova-Device", store.deviceId)
            .header("X-Nova-Time", ts).header("X-Nova-Nonce", nonce).header("X-Nova-Signature", WearKeys.sign(msg))
        if (method == "GET") rb.get() else rb.method(method, bytes.toRequestBody("application/json".toMediaType()))
        try {
            http().newCall(rb.build()).execute().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful) {
                    val err = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                    throw WearApiException(r.code, when {
                        r.code == 401 -> "This watch isn't paired anymore"
                        err.contains("phone isn't an admin") -> "Its phone isn't an admin anymore"
                        err.isNotEmpty() -> err; else -> "HTTP ${r.code}" })
                }
                runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            }
        } catch (e: java.io.IOException) { throw WearApiException(0, "Can't reach the server") }
    }

    companion object {
        fun pinned(pin: String): OkHttpClient.Builder {
            val b = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                    val got = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                    if (pin.isEmpty() || !got.equals(pin, true)) throw java.security.cert.CertificateException("Server certificate doesn't match")
                }
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            }
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            return b.sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }

        /** Ask to join: the server answers with a short code that an admin phone approves. Unsigned (we have no device id yet). */
        suspend fun request(url: String, pin: String, name: String): JSONObject = withContext(Dispatchers.IO) {
            val body = JSONObject().put("public_key", WearKeys.publicPem()).put("name", name).put("kind", "wear").toString()
            pinned(pin).build().newCall(Request.Builder().url(url.trimEnd('/') + "/api/v1/browser/request")
                .post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
                val t = r.body.string(); val o = runCatching { JSONObject(t) }.getOrDefault(JSONObject())
                if (!r.isSuccessful) throw WearApiException(r.code, o.optString("error").ifEmpty { "HTTP ${r.code}" }); o }
        }
        suspend fun requestState(url: String, pin: String, id: String): JSONObject = withContext(Dispatchers.IO) {
            pinned(pin).build().newCall(Request.Builder().url(url.trimEnd('/') + "/api/v1/browser/request/$id").build()).execute().use { r ->
                runCatching { JSONObject(r.body.string()) }.getOrDefault(JSONObject()) }
        }
    }
}

/** Finding Nova on the home network: the same "NOVA?" broadcast the phone app uses (UDP 8496). */
object WearDiscovery {
    suspend fun scan(ctx: Context, ms: Long = 2000): List<JSONObject> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, JSONObject>()
        val wifi = ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val lock = runCatching { wifi?.createMulticastLock("nova")?.apply { acquire() } }.getOrNull()
        try {
            DatagramSocket().use { s ->
                s.broadcast = true; s.soTimeout = 250
                val msg = "NOVA?".toByteArray()
                for (a in broadcasts(ctx)) runCatching { s.send(DatagramPacket(msg, msg.size, a, 8496)) }
                val end = System.currentTimeMillis() + ms; val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                    runCatching { JSONObject(String(p.data, 0, p.length)) }.getOrNull()?.takeIf { it.optInt("nova") == 1 && it.optString("lan_url").startsWith("https://") }
                        ?.let { found[it.optString("lan_url")] = it }
                }
            }
        } catch (_: Exception) {} finally { runCatching { lock?.release() } }
        found.values.toList()
    }
    private fun broadcasts(ctx: Context): List<InetAddress> {
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

fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
/** The certificate fingerprint as the phone and `sudo nova add` show it: the first 12 hex digits, in threes of four. */
fun shortPin(pin: String) = pin.take(12).uppercase().chunked(4).joinToString(" ")
