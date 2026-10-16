package app.novalabs.nova

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Android 7–12 differences, in one place (Nova runs on Android 7.0 and newer). */
object Compat {
    val Q get() = Build.VERSION.SDK_INT >= 29
    val R get() = Build.VERSION.SDK_INT >= 30
    val S get() = Build.VERSION.SDK_INT >= 31
    val P get() = Build.VERSION.SDK_INT >= 28

    /**
     * Confirm it's you, then [ok] (with the unlocked [sig], when one was given). Android 9+: the system prompt
     * (fingerprint, face or screen lock). Android 7–8: the fingerprint sensor, with a small dialog saying to touch it —
     * those versions have no system prompt for apps.
     */
    fun authenticate(a: Activity, title: String, subtitle: String, sig: java.security.Signature?, ok: (java.security.Signature?) -> Unit, fail: (String) -> Unit): android.os.CancellationSignal {
        val cancel = android.os.CancellationSignal()
        if (P) {
            val b = prompt(a, title, subtitle, crypto = sig != null).build()
            val cb = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) = ok(r.cryptoObject?.signature)
                override fun onAuthenticationError(code: Int, msg: CharSequence) = fail(msg.toString())
            }
            if (sig != null) b.authenticate(BiometricPrompt.CryptoObject(sig), cancel, a.mainExecutor, cb) else b.authenticate(cancel, a.mainExecutor, cb)
            return cancel
        }
        // Android 7–8: AndroidX's prompt, which drives the fingerprint sensor on versions without a system prompt
        val fa = a as? androidx.fragment.app.FragmentActivity ?: run { fail("Can't ask for a fingerprint here."); return cancel }
        val p = androidx.biometric.BiometricPrompt(fa, androidx.core.content.ContextCompat.getMainExecutor(a), object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: androidx.biometric.BiometricPrompt.AuthenticationResult) = ok(r.cryptoObject?.signature)
            override fun onAuthenticationError(code: Int, msg: CharSequence) = fail(msg.toString())
        })
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle(title).setSubtitle(subtitle).setNegativeButtonText("Cancel").build()
        if (sig != null) p.authenticate(info, androidx.biometric.BiometricPrompt.CryptoObject(sig)) else p.authenticate(info)
        cancel.setOnCancelListener { p.cancelAuthentication() }
        return cancel
    }

    /** A fingerprint/PIN prompt. Android 11+: fingerprint or screen lock. Android 10: the screen lock too,
     *  unless the prompt signs something (then fingerprint only). Android 9: fingerprint, with Cancel. */
    @androidx.annotation.RequiresApi(28)
    fun prompt(a: Activity, title: String, subtitle: String, crypto: Boolean): BiometricPrompt.Builder {
        val b = BiometricPrompt.Builder(a).setTitle(title).setSubtitle(subtitle)
        when {
            R -> b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            Q && !crypto -> @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            else -> b.setNegativeButton("Cancel", a.mainExecutor) { _, _ -> }        // (only reached on Android 9+)
        }
        return b
    }

    /** A key that needs you to confirm: every use ([seconds] = 0, with a prompt per signature), or for [seconds] after. */
    fun userAuth(b: KeyGenParameterSpec.Builder, seconds: Int): KeyGenParameterSpec.Builder {
        b.setUserAuthenticationRequired(true)
        if (R) b.setUserAuthenticationParameters(seconds, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
        else @Suppress("DEPRECATION") b.setUserAuthenticationValidityDurationSeconds(if (seconds == 0) -1 else seconds)
        return b
    }
}
