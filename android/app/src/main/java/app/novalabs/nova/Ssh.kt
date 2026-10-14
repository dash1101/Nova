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
            .let { Compat.userAuth(it, 30) }
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

/**
 * A small terminal: colors and bold (SGR, 16/256/true color), auto-wrap at the width the screen fits, erase-line
 * and erase-screen, and cursor moves within the last screenful (enough for prompts, ls, git, apt, progress bars).
 * Full-screen programs (top, nano, less) get their own screen and the scrollback comes back when they quit.
 */
class TermBuffer(private val maxLines: Int = 3000) {
    private class Line { val t = StringBuilder(); var st = LongArray(16)
        fun set(i: Int, ch: Char, style: Long) { while (t.length < i) { t.append(' '); grow(); st[t.length - 1] = 0 }
            if (i < t.length) t.setCharAt(i, ch) else { t.append(ch); grow() }; st[i] = style }
        fun grow() { if (st.size < t.length) st = st.copyOf(maxOf(t.length, st.size * 2)) }
        fun cut(n: Int) { if (n < t.length) t.setLength(maxOf(0, n)) } }
    private var lines = ArrayList<Line>().apply { add(Line()) }
    private var saved: ArrayList<Line>? = null          // the scrollback while a full-screen program runs
    private var row = 0; private var col = 0
    private var style = 0L                              // bits 0-24 fg (bit 24 = set), 25-49 bg (bit 49 = set), 50 bold, 51 dim, 52 underline, 53 inverse
    private var esc = 0                                 // 0 none, 1 after ESC, 2 CSI, 3 OSC, 4 charset designator
    private val csi = StringBuilder()
    var cols = 80; var rows = 40
    var version by mutableStateOf(0); private set

    @Synchronized fun feed(text: String) {
        for (ch in text) {
            when (esc) {
                1 -> { esc = when (ch) { '[' -> 2; ']' -> 3; '(', ')' -> 4; else -> 0 }; csi.setLength(0)
                       if (ch == 'M') row = maxOf(0, row - 1); continue }
                2 -> { if (ch in '@'..'~') { csiDo(ch); esc = 0 } else csi.append(ch); continue }
                3 -> { if (ch == '\u0007') esc = 0 else if (ch == '\u001b') esc = 1; continue }
                4 -> { esc = 0; continue }
            }
            when (ch) {
                '\u001b' -> esc = 1
                '\r' -> col = 0
                '\n' -> newline()
                '\b' -> if (col > 0) col--
                '\u0007', '\u0000', '\u000e', '\u000f' -> {}
                '\t' -> col = minOf(cols - 1, (col / 8 + 1) * 8)
                else -> { if (col >= cols) { newline(); col = 0 }; lines[row].set(col, ch, style); col++ }
            }
        }
        version++
    }
    private fun newline() {
        row++
        if (row >= lines.size) { lines.add(Line()); if (lines.size > maxLines) { lines.removeAt(0); row-- } }
    }
    /** The first line of the current screen (cursor moves count from here). */
    private fun top() = maxOf(0, lines.size - rows)
    private fun goto(r: Int, c: Int) {
        while (lines.size < rows) lines.add(Line())
        row = (top() + r).coerceIn(0, lines.lastIndex); col = c.coerceIn(0, cols - 1)
    }
    private fun sgr(ps: List<Int>) {
        var i = 0
        fun color(base: Int): Long? = when (ps.getOrNull(i + 1)) {
            5 -> ps.getOrNull(i + 2)?.let { i += 2; xterm(it).toLong() }
            2 -> if (i + 4 < ps.size) { val v = (ps[i + 2] shl 16) or (ps[i + 3] shl 8) or ps[i + 4]; i += 4; v.toLong() } else null
            else -> null }
        if (ps.isEmpty()) { style = 0; return }
        while (i < ps.size) {
            val p = ps[i]
            style = when (p) {
                0 -> 0L
                1 -> style or (1L shl 50); 2 -> style or (1L shl 51); 4 -> style or (1L shl 52); 7 -> style or (1L shl 53)
                22 -> style and ((1L shl 50) or (1L shl 51)).inv(); 24 -> style and (1L shl 52).inv(); 27 -> style and (1L shl 53).inv()
                in 30..37 -> fg(xterm(p - 30)); in 90..97 -> fg(xterm(p - 90 + 8)); 39 -> style and 0x1FFFFFFL.inv()
                in 40..47 -> bg(xterm(p - 40)); in 100..107 -> bg(xterm(p - 100 + 8)); 49 -> style and (0x1FFFFFFL shl 25).inv()
                38 -> color(0)?.let { fg(it.toInt()) } ?: style
                48 -> color(0)?.let { bg(it.toInt()) } ?: style
                else -> style
            }
            i++
        }
    }
    private fun fg(rgb: Int) = (style and 0x1FFFFFFL.inv()) or (1L shl 24) or (rgb.toLong() and 0xFFFFFF)
    private fun bg(rgb: Int) = (style and (0x1FFFFFFL shl 25).inv()) or (1L shl 49) or ((rgb.toLong() and 0xFFFFFF) shl 25)
    private fun csiDo(f: Char) {
        val priv = csi.startsWith("?")
        val ps = csi.toString().trimStart('?', '>', '=').split(';').map { it.toIntOrNull() ?: 0 }.let { if (csi.isEmpty()) emptyList() else it }
        val n = ps.firstOrNull()?.takeIf { it > 0 }
        val cur = lines[row]
        when (f) {
            'm' -> if (!priv) sgr(ps)
            'K' -> when (ps.firstOrNull() ?: 0) { 0 -> cur.cut(col); 1 -> for (i in 0 until minOf(col + 1, cur.t.length)) cur.set(i, ' ', 0); 2 -> cur.cut(0) }
            'J' -> when (ps.firstOrNull() ?: 0) {
                0 -> { cur.cut(col); while (lines.lastIndex > row) lines.removeAt(lines.lastIndex) }
                2 -> { val t = top(); for (i in t until lines.size) lines[i].cut(0); goto(row - t, col) }
                3 -> clearLocked() }
            'H', 'f' -> goto((ps.getOrNull(0)?.takeIf { it > 0 } ?: 1) - 1, (ps.getOrNull(1)?.takeIf { it > 0 } ?: 1) - 1)
            'd' -> goto((n ?: 1) - 1, col)
            'A' -> row = maxOf(top(), row - (n ?: 1))
            'B' -> { repeat(n ?: 1) { if (row < lines.lastIndex) row++ } }
            'C' -> col = minOf(cols - 1, col + (n ?: 1))
            'D' -> col = maxOf(0, col - (n ?: 1))
            'G' -> col = ((n ?: 1) - 1).coerceIn(0, cols - 1)
            'P' -> repeat(n ?: 1) { if (col < cur.t.length) { cur.t.deleteCharAt(col); System.arraycopy(cur.st, col + 1, cur.st, col, cur.t.length - col) } }
            'X' -> for (i in col until minOf(col + (n ?: 1), cur.t.length)) cur.set(i, ' ', 0)
            '@' -> repeat(n ?: 1) { if (col <= cur.t.length) { cur.t.insert(col, ' '); cur.grow(); System.arraycopy(cur.st, col, cur.st, col + 1, cur.t.length - col - 1); cur.st[col] = 0 } }
            'h', 'l' -> if (priv && ps.any { it == 1049 || it == 47 || it == 1047 }) {
                if (f == 'h' && saved == null) { saved = lines; lines = ArrayList<Line>().apply { repeat(rows) { add(Line()) } }; row = 0; col = 0 }
                else if (f == 'l') saved?.let { lines = it; saved = null; row = lines.lastIndex; col = 0 } }
        }
    }
    private fun clearLocked() { lines = ArrayList<Line>().apply { add(Line()) }; row = 0; col = 0 }
    @Synchronized fun clear() { clearLocked(); saved = null; style = 0; version++ }
    @Synchronized fun resize(c: Int, r: Int) { cols = c.coerceIn(20, 400); rows = r.coerceIn(5, 200) }
    @Synchronized fun text(): String = lines.joinToString("\n") { it.t }

    /** The screen with its colors, for Compose. */
    @Synchronized fun styled(fgDefault: androidx.compose.ui.graphics.Color, bgDefault: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.AnnotatedString =
        androidx.compose.ui.text.buildAnnotatedString {
            lines.forEachIndexed { li, ln ->
                if (li > 0) append('\n')
                var i = 0
                while (i < ln.t.length) {
                    val s = ln.st[i]; var j = i + 1
                    while (j < ln.t.length && ln.st[j] == s) j++
                    if (s == 0L) append(ln.t, i, j)
                    else { val start = length; append(ln.t, i, j); addStyle(span(s, fgDefault, bgDefault), start, length) }
                    i = j
                }
            }
        }
    private fun span(s: Long, fd: androidx.compose.ui.graphics.Color, bd: androidx.compose.ui.graphics.Color): androidx.compose.ui.text.SpanStyle {
        fun c(v: Long) = androidx.compose.ui.graphics.Color(0xFF000000.toInt() or (v and 0xFFFFFF).toInt())
        var f = if (s and (1L shl 24) != 0L) c(s) else fd
        var b = if (s and (1L shl 49) != 0L) c(s shr 25) else androidx.compose.ui.graphics.Color.Unspecified
        if (s and (1L shl 53) != 0L) { val t = f; f = if (b == androidx.compose.ui.graphics.Color.Unspecified) bd else b; b = t }
        if (s and (1L shl 51) != 0L) f = f.copy(alpha = .6f)
        return androidx.compose.ui.text.SpanStyle(color = f, background = b,
            fontWeight = if (s and (1L shl 50) != 0L) androidx.compose.ui.text.font.FontWeight.Bold else null,
            textDecoration = if (s and (1L shl 52) != 0L) androidx.compose.ui.text.style.TextDecoration.Underline else null)
    }
    companion object {
        private val BASE = intArrayOf(0x1d1f21, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xc8ccd4,
                                      0x5c6370, 0xff7b86, 0xb5e890, 0xffd68a, 0x8cc8ff, 0xe09cf5, 0x7fd6e0, 0xffffff)
        /** xterm's 256 colors: the 16 above (a readable dark-theme set), a 6×6×6 cube, then 24 greys. */
        fun xterm(n: Int): Int = when {
            n < 16 -> BASE[n.coerceAtLeast(0)]
            n < 232 -> { val k = n - 16; val v = { x: Int -> if (x == 0) 0 else 55 + x * 40 }; (v(k / 36) shl 16) or (v(k / 6 % 6) shl 8) or v(k % 6) }
            n < 256 -> { val g = 8 + (n - 232) * 10; (g shl 16) or (g shl 8) or g }
            else -> 0xc8ccd4
        }
    }
}

/** One SSH session at a time, kept while you move around the app. */
object SshSession {
    var state by mutableStateOf("idle"); private set      // idle | connecting | open | closed
    var error by mutableStateOf<String?>(null); private set
    var hostName by mutableStateOf(""); private set
    val term = TermBuffer()
    private var session: Session? = null
    private var out: OutputStream? = null
    private var channel: ChannelShell? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val active get() = state == "open" || state == "connecting"

    /** Fingerprint/PIN unlocks the SSH key for 30 s (no crypto object: time-bound key). */
    suspend fun unlock(activity: Activity) = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal(); cont.invokeOnCancellation { cancel.cancel() }
        Compat.prompt(activity, "Open a terminal on the server", "Nova · confirm it's you", crypto = false).build()
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
                ch.setPtyType("xterm-256color"); ch.setPtySize(term.cols, term.rows, 0, 0); channel = ch
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

    fun disconnect() { runCatching { session?.disconnect() }; session = null; out = null; channel = null; if (state != "idle") state = "closed" }

    /** The terminal's size follows the screen (rotate, fold, keyboard), so lines wrap where you see them wrap. */
    fun resize(cols: Int, rows: Int) {
        if (cols == term.cols && rows == term.rows) return
        term.resize(cols, rows); val ch = channel ?: return
        scope.launch { runCatching { ch.setPtySize(term.cols, term.rows, 0, 0) } }
    }

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
