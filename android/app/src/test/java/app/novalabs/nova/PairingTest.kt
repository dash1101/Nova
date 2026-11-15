package app.novalabs.nova

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Getting in: reading what `sudo nova add` shows (a QR or a setup code), and signing requests the way the server checks them. */
class PairingTest {
    private val pin = "ab".repeat(32)

    // ── the pairing code ──
    @Test fun homeQr() {                                   // exactly what `sudo nova add` puts in the QR
        val p = parsePairInfo("""{"nova":1,"lan":"https://192.168.1.20:8495","pin":"$pin","remote":"","code":"K7M2Q9XA4T"}""")
        assertNotNull(p); p!!
        assertEquals("https://192.168.1.20:8495", p.lan); assertEquals(pin, p.pin); assertEquals("K7M2Q9XA4T", p.code)
        assertEquals("", p.remote); assertEquals("", p.tailnet); assertEquals("", p.cfId); assertEquals("", p.cfSecret)
    }
    private fun setupCode(json: String) = "nova1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    private val away = """{"nova":1,"lan":"https://192.168.1.20:8495","pin":"$pin","remote":"https://nova.example.com","code":"K7M2Q9XA4T","tailnet":"https://100.101.102.103:8495","cf":{"id":"abc.access","secret":"s3cr3t"}}"""

    @Test fun awayFromHomeSetupCode() {                    // `sudo nova add --remote`: the same, packed for pasting, with the ways in from outside
        val p = parsePairInfo(setupCode(away)); assertNotNull(p); p!!
        assertEquals("https://nova.example.com", p.remote); assertEquals("https://100.101.102.103:8495", p.tailnet)
        assertEquals("abc.access", p.cfId); assertEquals("s3cr3t", p.cfSecret); assertEquals("K7M2Q9XA4T", p.code); assertEquals(pin, p.pin)
    }
    @Test fun setupCodeHoweverItWasPasted() {              // spaces around it, capital prefix, broken over lines by a chat app, padded
        val code = setupCode(away)
        assertEquals("K7M2Q9XA4T", parsePairInfo("  $code \n")?.code)
        assertEquals("K7M2Q9XA4T", parsePairInfo("NOVA1:" + code.substring(6))?.code)
        assertEquals("K7M2Q9XA4T", parsePairInfo(code.chunked(40).joinToString("\n"))?.code)
        assertEquals("K7M2Q9XA4T", parsePairInfo(code.chunked(64).joinToString("\r\n  "))?.code)
        assertEquals("K7M2Q9XA4T", parsePairInfo("$code==")?.code)
    }
    @Test fun notAPairingCode() {                          // any other QR, a newer format, a code with nothing to pair with: no crash, no pairing
        assertNull(parsePairInfo("")); assertNull(parsePairInfo("https://example.com")); assertNull(parsePairInfo("WIFI:S:home;T:WPA;P:pw;;"))
        assertNull(parsePairInfo("""{"nova":2,"lan":"https://192.168.1.20:8495","code":"K7M2Q9XA4T"}"""))
        assertNull(parsePairInfo("""{"nova":1,"lan":"https://192.168.1.20:8495","pin":"$pin"}"""))
        assertNull(parsePairInfo("nova1:")); assertNull(parsePairInfo("nova1:!!!not-base64!!!")); assertNull(parsePairInfo("nova1:" + "A".repeat(50)))
        assertNull(parsePairInfo("[1,2,3]")); assertNull(parsePairInfo("{"))
    }

    // ── addresses ──
    @Test fun addressesAsStored() {
        assertEquals("https://nova.example.com", normUrl("nova.example.com"))            // some servers handed it out bare
        assertEquals("https://nova.example.com", normUrl(" https://nova.example.com/ "))
        assertEquals("http://192.168.1.20:8095", normUrl("http://192.168.1.20:8095"))    // an old plain-http setup stays as it is
        assertEquals("", normUrl("")); assertEquals("", normUrl("   "))
    }
    @Test fun tailscaleAddresses() {                       // 100.64.0.0/10 only: they get more patience, and the pinned certificate
        assertTrue(isTailscaleUrl("https://100.64.0.1:8495")); assertTrue(isTailscaleUrl("https://100.101.102.103:8495")); assertTrue(isTailscaleUrl("https://100.127.255.254:8495"))
        assertFalse(isTailscaleUrl("https://100.63.0.1:8495")); assertFalse(isTailscaleUrl("https://100.128.0.1:8495"))
        assertFalse(isTailscaleUrl("https://192.168.1.20:8495")); assertFalse(isTailscaleUrl("https://nova.example.com"))
        assertFalse(isTailscaleUrl("https://server.tail1234.ts.net:8495")); assertFalse(isTailscaleUrl("")); assertFalse(isTailscaleUrl("not a url"))
    }

    // ── which release is newer ──
    @Test fun versionsInOrder() {
        val order = listOf("0.9.26-alpha", "0.10.3-alpha", "1.0.0-alpha", "1.0.0-beta.1", "1.0.0-beta.2", "1.0.0-beta.10", "1.0.0-rc.1", "1.0.0", "1.0.1-alpha", "1.0.1", "1.1.0", "2.0.0")
        for (i in order.indices) for (j in order.indices)
            assertEquals("${order[i]} newer than ${order[j]}", i > j, versionNewer(order[i], order[j]))
        assertTrue(versionNewer("v1.0.0", "1.0.0-beta.3")); assertFalse(versionNewer("v1.0.0", "1.0.0")); assertFalse(versionNewer("", "1.0.0")); assertFalse(versionNewer("junk", "0.1.0"))
    }

    // ── signing ──
    private val vectors by lazy {                          // made with the server's own rule (packaging/signing_vectors.json)
        val f = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "packaging/signing_vectors.json") }.first { it.exists() }
        JSONObject(f.readText())
    }
    @Test fun signsTheSameBytesTheServerChecks() {
        val reqs = vectors.getJSONArray("requests"); assertTrue(reqs.length() >= 5)
        for (i in 0 until reqs.length()) {
            val r = reqs.getJSONObject(i)
            val msg = signedMessage(r.getString("method"), r.getString("path"), r.getString("time"), r.getString("nonce"), r.getString("body").toByteArray())
            assertArrayEquals("${r.getString("method")} ${r.getString("path")}", r.getString("message").toByteArray(), msg)
        }
    }
    @Test fun aSignatureOverThoseBytesChecksOut() {        // …and a P-256 signature made over them elsewhere verifies here: same bytes, same encoding
        val der = Base64.getDecoder().decode(vectors.getString("public_key").lines().filter { !it.startsWith("-----") }.joinToString(""))
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
        val reqs = vectors.getJSONArray("requests")
        for (i in 0 until reqs.length()) {
            val r = reqs.getJSONObject(i)
            val msg = signedMessage(r.getString("method"), r.getString("path"), r.getString("time"), r.getString("nonce"), r.getString("body").toByteArray())
            fun ok(m: ByteArray) = Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(m); verify(Base64.getDecoder().decode(r.getString("signature"))) }
            assertTrue(r.getString("path"), ok(msg))
            assertFalse("a changed body must not verify", ok(signedMessage(r.getString("method"), r.getString("path"), r.getString("time"), r.getString("nonce"), (r.getString("body") + " ").toByteArray())))
            assertFalse("another path must not verify", ok(signedMessage(r.getString("method"), r.getString("path") + "x", r.getString("time"), r.getString("nonce"), r.getString("body").toByteArray())))
        }
    }
    @Test fun anEmptyBodyIsTheHashOfNothing() {
        assertEquals("GET\n/api/v1/ping\n1\nn\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", String(signedMessage("GET", "/api/v1/ping", "1", "n", ByteArray(0))))
    }
}
