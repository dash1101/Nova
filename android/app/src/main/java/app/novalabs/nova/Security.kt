package app.novalabs.nova

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed keys:
 *  - DEVICE_KEY: EC P-256 signing key. Generated inside the secure element (StrongBox when the
 *    phone has one), never exportable. The server only ever sees its public half.
 *  - SECRET_KEY: AES-256-GCM key used to encrypt the few secrets stored in app storage
 *    (Cloudflare Access service token).
 */
class DeviceKey(profile: String) {
    // One set of keys per server. The first server keeps the original names (no re-pairing).
    private val sfx = if (profile.isEmpty()) "" else "-$profile"
    private val DEVICE_KEY = "nova-device-key$sfx"
    private val SECRET_KEY = "nova-secret-key$sfx"
    private val STEPUP_KEY = "nova-stepup-key$sfx"
    private val QUICK_KEY = "nova-quick-approve-key$sfx"
    private val ks: KeyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun ensure(): Boolean /* true = StrongBox */ {
        if (ks.containsAlias(DEVICE_KEY)) return isStrongBox()
        fun gen(strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(DEVICE_KEY, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(strongBox)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(spec) }.generateKeyPair()
        }
        return try { gen(true); true } catch (e: StrongBoxUnavailableException) { gen(false); false }
    }

    private fun isStrongBox(): Boolean = try {
        val k = ks.getKey(DEVICE_KEY, null) as PrivateKey
        val info = java.security.KeyFactory.getInstance(k.algorithm, "AndroidKeyStore")
            .getKeySpec(k, android.security.keystore.KeyInfo::class.java)
        info.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
    } catch (_: Exception) { false }

    fun publicKeyPem(): String {
        val der = ks.getCertificate(DEVICE_KEY).publicKey.encoded
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    fun sign(data: ByteArray): String {
        val key = ks.getKey(DEVICE_KEY, null) as PrivateKey
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign()
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    fun wipe() {
        for (a in listOf(DEVICE_KEY, SECRET_KEY, STEPUP_KEY, QUICK_KEY)) if (ks.containsAlias(a)) ks.deleteEntry(a)
    }

    // ── Step-up key: a second signing key that only works right after a fingerprint
    //    (or phone PIN) for THAT operation. The server demands it for risky actions.
    // ── Quick-approve key (opt-in): lets the Approve/Deny buttons on a notification — also on a watch —
    //    work without a fingerprint. The server registers it as an approver that can do nothing else.
    fun hasQuick() = ks.containsAlias(QUICK_KEY)
    fun ensureQuick(): String {
        if (!hasQuick()) {
            fun gen(sb: Boolean) = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(QUICK_KEY, KeyProperties.PURPOSE_SIGN).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).setIsStrongBoxBacked(sb).build()) }.generateKeyPair()
            try { gen(true) } catch (e: StrongBoxUnavailableException) { gen(false) }
        }
        val b64 = Base64.encodeToString(ks.getCertificate(QUICK_KEY).publicKey.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }
    fun signQuick(data: ByteArray): String {
        val key = ks.getKey(QUICK_KEY, null) as PrivateKey
        return Base64.encodeToString(Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(data) }.sign(), Base64.NO_WRAP)
    }
    fun dropQuick() { if (hasQuick()) ks.deleteEntry(QUICK_KEY) }

    fun hasStepUp() = ks.containsAlias(STEPUP_KEY)

    fun ensureStepUp() {
        if (hasStepUp()) return
        fun gen(strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(STEPUP_KEY, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0,       // 0 = a fresh confirmation for every signature
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .setInvalidatedByBiometricEnrollment(false)
                .setIsStrongBoxBacked(strongBox)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(spec) }.generateKeyPair()
        }
        try { gen(true) } catch (e: StrongBoxUnavailableException) { gen(false) }
    }

    fun stepUpPublicKeyPem(): String {
        val der = ks.getCertificate(STEPUP_KEY).publicKey.encoded
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP).chunked(64).joinToString("\n")
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    /** A Signature ready for BiometricPrompt's CryptoObject; usable once the user confirms. */
    fun stepUpSignature(): Signature =
        Signature.getInstance("SHA256withECDSA").apply { initSign(ks.getKey(STEPUP_KEY, null) as PrivateKey) }

    private fun secretKey(): SecretKey {
        (ks.getKey(SECRET_KEY, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(SECRET_KEY,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply { init(spec) }.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12)) }
        return String(c.doFinal(raw, 12, raw.size - 12))
    }
}

/** Pairing details for one server. Non-secret fields in plain prefs; the Cloudflare secret encrypted. */
class Pairing(ctx: Context, val profile: String = Servers.active(ctx)) {
    private val appCtx: Context = ctx.applicationContext
    private val p = ctx.getSharedPreferences(if (profile.isEmpty()) "nova" else "nova_$profile", Context.MODE_PRIVATE)
    val keys = DeviceKey(profile)
    val paired get() = deviceId.isNotEmpty()
    val deviceId get() = p.getString("device_id", "")!!
    val lanUrl get() = p.getString("lan_url", "")!!
    val remoteUrl get() = p.getString("remote_url", "")!!
    val cfClientId get() = p.getString("cf_id", "")!!
    val cfClientSecret get() = runCatching { keys.decrypt(p.getString("cf_secret", "")!!) }.getOrDefault("")
    /** sha256 of the server's LAN TLS certificate (hex) — the only certificate accepted on https LAN. */
    var lanPin: String
        get() = p.getString("lan_pin", "")!!
        set(v) { p.edit().putString("lan_pin", v).apply() }
    var label: String                       // what the server is called in the switcher
        get() = p.getString("label", "")!!
        set(v) { p.edit().putString("label", v).apply() }

    /** "admin" or "viewer" (view-only), and who this phone belongs to — set by the server. */
    var role: String
        get() = p.getString("role", "admin")!!
        set(v) { p.edit().putString("role", v).apply() }
    var user: String
        get() = p.getString("user", "")!!
        set(v) { p.edit().putString("user", v).apply() }

    /** Server id of this phone's quick-approve key ("" = off). */
    var quickApproverId: String
        get() = p.getString("quick_approver", "")!!
        set(v) { p.edit().putString("quick_approver", v).apply() }
    var stepUpRegistered: Boolean
        get() = p.getBoolean("stepup_registered", false)
        set(v) { p.edit().putBoolean("stepup_registered", v).apply() }
    var lastEventSeen: Double
        get() = p.getFloat("last_event", 0f).toDouble().let { if (it == 0.0) p.getString("last_event_s", "0")!!.toDouble() else it }
        set(v) { p.edit().putString("last_event_s", v.toString()).putFloat("last_event", 0f).apply() }
    var sshRoute: String
        get() = p.getString("ssh_route", "auto")!!
        set(v) { p.edit().putString("ssh_route", v).apply() }
    /** Quick panel tiles, in order (ids from QUICK_ACTIONS; "restart:<container>" for custom ones). */
    var quickActions: List<String>
        get() = p.getString("quick_actions", null)?.split(",")?.filter { it.isNotEmpty() } ?: DEFAULT_QUICK
        set(v) { p.edit().putString("quick_actions", v.joinToString(",")).apply() }
    var phoneNotifyLevel: String
        get() = p.getString("phone_notify", "warning")!!
        set(v) { p.edit().putString("phone_notify", v).apply() }

    fun updateRemote(remote: String, cfId: String, cfSecret: String) {
        p.edit().putString("remote_url", remote).putString("cf_id", cfId)
            .putString("cf_secret", keys.encrypt(cfSecret)).apply()
    }
    fun updateLan(url: String, pin: String) { p.edit().putString("lan_url", url).putString("lan_pin", pin).apply() }

    fun save(deviceId: String, lan: String, remote: String, cfId: String, cfSecret: String, pin: String = "") {
        p.edit().putString("device_id", deviceId).putString("lan_url", lan).putString("remote_url", remote)
            .putString("cf_id", cfId).putString("cf_secret", keys.encrypt(cfSecret)).putString("lan_pin", pin).apply()
    }

    fun clear() { p.edit().clear().apply(); keys.wipe(); SshKey(profile).delete(); Cache.drop(appCtx, profile) }
}

/**
 * The servers this phone is paired with. Each has its own keys, prefs and cache; one is active.
 * Profile "" is the first server (kept under the original names so existing pairings survive).
 */
object Servers {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_servers", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<String> {
        val saved = prefs(ctx).getString("ids", null)?.split(",") ?: listOf("")
        return saved.distinct()
    }
    fun active(ctx: Context): String = prefs(ctx).getString("active", null)?.takeIf { it in all(ctx) } ?: all(ctx).first()
    fun setActive(ctx: Context, id: String) { prefs(ctx).edit().putString("active", id).apply() }

    /** A fresh, empty profile to pair a new server into. */
    fun create(ctx: Context): String {
        val id = java.util.UUID.randomUUID().toString().take(8)
        prefs(ctx).edit().putString("ids", (all(ctx) + id).joinToString(",")).apply()
        return id
    }
    fun remove(ctx: Context, id: String) {
        Pairing(ctx, id).clear()
        val left = all(ctx) - id
        prefs(ctx).edit().putString("ids", left.ifEmpty { listOf("") }.joinToString(",")).apply()
        if (active(ctx) == id) setActive(ctx, left.firstOrNull() ?: "")
    }
    /** Drop half-made profiles (pairing cancelled), except the one in use. */
    fun prune(ctx: Context) {
        val keep = active(ctx)
        all(ctx).filter { it != keep && it.isNotEmpty() && !Pairing(ctx, it).paired }.forEach { remove(ctx, it) }
    }
}
