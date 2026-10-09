package app.novalabs.nova

import android.app.Activity
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SSH from the phone to the server, as a normal SSH client (the server's sshd, key-only):
 *  - this phone's SSH key is generated inside the secure chip and can't be copied out;
 *    each connect unlocks it for 30 s with your fingerprint;
 *  - the server's host keys are pinned from the signed Nova API, so a fake server is refused;
 *  - sshd is only on home Wi-Fi and Tailscale (not exposed to the internet).
 */
class SshKey(profile: String) {
    private val ALIAS = if (profile.isEmpty()) "nova_ssh" else "nova_ssh-$profile"
    private val ks get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun exists() = ks.containsAlias(ALIAS)

    fun ensure() {
        if (exists()) return
        val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        fun spec(strong: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(30, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setIsStrongBoxBacked(strong).build()
        try { g.initialize(spec(true)); g.generateKeyPair() }
        catch (e: Exception) { g.initialize(spec(false)); g.generateKeyPair() }      // no StrongBox: TEE
    }

    private val pub get() = ks.getCertificate(ALIAS).publicKey as ECPublicKey
    private val priv get() = ks.getKey(ALIAS, null) as PrivateKey

    /** SSH wire format: string "ecdsa-sha2-nistp256", string "nistp256", string Q (04||X||Y). */
    fun blob(): ByteArray {
        val w = pub.w
        val q = byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
        return sshStr("ecdsa-sha2-nistp256".toByteArray()) + sshStr("nistp256".toByteArray()) + sshStr(q)
    }

    fun openSsh(): String = "ecdsa-sha2-nistp256 " + Base64.getEncoder().encodeToString(blob()) + " nova-phone"

    /** DER (r,s) from the keystore -> SSH signature blob. */
    fun sign(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() }
        val (r, s) = derToRs(der)
        return sshStr("ecdsa-sha2-nistp256".toByteArray()) + sshStr(mpint(r) + mpint(s))
    }

    fun delete() { runCatching { ks.deleteEntry(ALIAS) } }

    private fun fixed(b: BigInteger): ByteArray { val a = b.toByteArray().dropWhile { it == 0.toByte() }.toByteArray(); return ByteArray(32 - a.size) + a }
    private fun mpint(b: BigInteger) = sshStr(b.toByteArray())
    private fun sshStr(b: ByteArray) = java.nio.ByteBuffer.allocate(4).putInt(b.size).array() + b
    private fun derToRs(der: ByteArray): Pair<BigInteger, BigInteger> {
        var i = 2; if (der[1].toInt() and 0x80 != 0) i += der[1].toInt() and 0x7f
        fun int(): BigInteger { require(der[i].toInt() == 2); val n = der[i + 1].toInt(); val v = BigInteger(1, der.copyOfRange(i + 2, i + 2 + n)); i += 2 + n; return v }
        return int() to int()
    }
}

/** Plain-text terminal: understands \r, \b, erase-line and clear-screen; drops other escapes. */
class TermBuffer(private val maxLines: Int = 3000) {
    private val lines = ArrayList<StringBuilder>().apply { add(StringBuilder()) }
    private var col = 0
    private var esc = 0                       // 0 none, 1 after ESC, 2 CSI, 3 OSC
    private val csi = StringBuilder()
    var version by mutableStateOf(0); private set

    @Synchronized fun feed(text: String) {
        for (ch in text) {
            when (esc) {
                1 -> { esc = when (ch) { '[' -> 2; ']' -> 3; else -> 0 }; csi.setLength(0); continue }
                2 -> { if (ch in '@'..'~') { csiDo(ch); esc = 0 } else csi.append(ch); continue }
                3 -> { if (ch == '\u0007') esc = 0 else if (ch == '\u001b') esc = 1; continue }
            }
            val cur = lines.last()
            when (ch) {
                '\u001b' -> esc = 1
                '\r' -> col = 0
                '\n' -> { lines.add(StringBuilder()); col = 0; if (lines.size > maxLines) lines.removeAt(0) }
                '\b' -> if (col > 0) col--
                '\u0007', '\u0000' -> {}
                '\t' -> { val n = 8 - col % 8; repeat(n) { put(cur, ' ') } }
                else -> put(cur, ch)
            }
        }
        version++
    }
    private fun put(cur: StringBuilder, ch: Char) { if (col < cur.length) cur.setCharAt(col, ch) else { while (cur.length < col) cur.append(' '); cur.append(ch) }; col++ }
    private fun csiDo(f: Char) {
        val cur = lines.last(); val n = csi.toString().trimStart('?').split(';').firstOrNull()?.toIntOrNull()
        when (f) {
            'K' -> when (n ?: 0) { 0 -> if (col < cur.length) cur.setLength(col); 1 -> for (i in 0 until minOf(col, cur.length)) cur.setCharAt(i, ' '); 2 -> cur.setLength(0) }
            'J' -> if ((n ?: 0) == 2 || n == 3) clear()
            'C' -> col += (n ?: 1)
            'D' -> col = maxOf(0, col - (n ?: 1))
            'G' -> col = maxOf(0, (n ?: 1) - 1)
            'P' -> repeat(n ?: 1) { if (col < cur.length) cur.deleteCharAt(col) }
            '@' -> repeat(n ?: 1) { if (col <= cur.length) cur.insert(col, ' ') }
        }
    }
    @Synchronized fun clear() { lines.clear(); lines.add(StringBuilder()); col = 0; version++ }
    @Synchronized fun text(): String = lines.joinToString("\n")
}

/** One SSH session at a time, kept while you move around the app. */
object SshSession {
    var state by mutableStateOf("idle"); private set      // idle | connecting | open | closed
    var error by mutableStateOf<String?>(null); private set
    var hostName by mutableStateOf(""); private set
    val term = TermBuffer()
    private var session: Session? = null
    private var out: OutputStream? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val active get() = state == "open" || state == "connecting"

    /** Fingerprint/PIN unlocks the SSH key for 30 s (no crypto object: time-bound key). */
    suspend fun unlock(activity: Activity) = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal(); cont.invokeOnCancellation { cancel.cancel() }
        BiometricPrompt.Builder(activity).setTitle("Open a terminal on the server").setSubtitle("Nova · confirm it's you")
            .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL).build()
            .authenticate(cancel, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { cont.resume(Unit) }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    if (cont.isActive) cont.resumeWithException(ApiException(0, msg.toString())) }
            })
    }

    fun connect(name: String, host: String, port: Int, user: String, pinned: List<String>, key: SshKey) {
        disconnect(); state = "connecting"; error = null; hostName = name; term.clear()
        term.feed("Connecting to $user@$host…\r\n")
        scope.launch {
            try {
                val jsch = JSch()
                jsch.addIdentity(PhoneIdentity(key), null)
                jsch.hostKeyRepository = PinnedHosts(pinned)
                val s = jsch.getSession(user, host, port)
                s.setConfig("PreferredAuthentications", "publickey")
                s.setConfig("server_host_key", "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256")
                s.setServerAliveInterval(20_000)
                s.connect(8000)
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("dumb"); ch.setPtySize(80, 40, 0, 0)
                val input: InputStream = ch.inputStream
                out = ch.outputStream
                ch.connect(8000)
                session = s; state = "open"
                val reader = input.reader(Charsets.UTF_8)        // keeps multi-byte characters whole across reads
                val buf = CharArray(4096)
                while (true) {
                    val n = reader.read(buf); if (n < 0) break
                    term.feed(String(buf, 0, n))
                }
                term.feed("\r\n[session ended]\r\n"); state = "closed"
            } catch (e: Exception) {
                val msg = when {
                    e.message.orEmpty().contains("Auth fail", true) -> "The server didn't accept this ${DeviceForm.noun}'s SSH key yet — add it first (see below)."
                    generateSequence<Throwable>(e) { it.cause }.any { it is android.security.keystore.UserNotAuthenticatedException } -> "Confirmation expired — try again."
                    e.message.orEmpty().contains("HostKey", true) || e.message.orEmpty().contains("reject", true) -> "The server's identity didn't match — refusing to connect."
                    e.message.orEmpty().contains("timeout", true) || e.message.orEmpty().contains("connect", true) -> "Can't reach the server here. Away from home, turn on Tailscale."
                    else -> e.message ?: "Couldn't connect"
                }
                error = msg; term.feed("\r\n$msg\r\n"); state = "closed"
            }
        }
    }

    fun send(text: String) { val o = out ?: return; scope.launch { runCatching { o.write(text.toByteArray()); o.flush() } } }

    fun disconnect() { runCatching { session?.disconnect() }; session = null; out = null; if (state != "idle") state = "closed" }

    private class PhoneIdentity(val key: SshKey) : Identity {
        override fun setPassphrase(passphrase: ByteArray?) = true
        override fun getPublicKeyBlob(): ByteArray = key.blob()
        override fun getSignature(data: ByteArray): ByteArray = key.sign(data)
        override fun getAlgName() = "ecdsa-sha2-nistp256"
        override fun getName() = "nova-phone"
        override fun isEncrypted() = false
        override fun clear() {}
    }

    /** Only the server's real host keys (fetched over the signed API) are accepted. */
    private class PinnedHosts(pinned: List<String>) : HostKeyRepository {
        private val blobs = pinned.mapNotNull { runCatching { Base64.getDecoder().decode(it.split(" ")[1]) }.getOrNull() }
        override fun check(host: String?, key: ByteArray?) =
            if (key != null && blobs.any { it.contentEquals(key) }) HostKeyRepository.OK else HostKeyRepository.CHANGED
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID() = "nova-pinned"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}
