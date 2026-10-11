package app.novalabs.nova

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Android 9–11 differences, in one place (Nova runs on Android 9 and newer). */
object Compat {
    val Q get() = Build.VERSION.SDK_INT >= 29
    val R get() = Build.VERSION.SDK_INT >= 30
    val S get() = Build.VERSION.SDK_INT >= 31

    /** A fingerprint/PIN prompt. Android 11+: fingerprint or screen lock. Android 10: the screen lock too,
     *  unless the prompt signs something (then fingerprint only). Android 9: fingerprint, with Cancel. */
    fun prompt(a: Activity, title: String, subtitle: String, crypto: Boolean): BiometricPrompt.Builder {
        val b = BiometricPrompt.Builder(a).setTitle(title).setSubtitle(subtitle)
        when {
            R -> b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            Q && !crypto -> @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
            else -> b.setNegativeButton("Cancel", a.mainExecutor) { _, _ -> }
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
