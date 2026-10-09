package app.novalabs.nova

import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager

/**
 * What kind of device this is — phone, tablet or desktop — so the app says "this tablet" instead of
 * "this phone", and the server's device list shows the right icon. Decided from the hardware, not the
 * current window: a foldable (hinge sensor) stays a phone even when unfolded; Chromebooks and other
 * PC-class Android devices are desktops; anything else with a smallest width of 600 dp is a tablet.
 */
object DeviceForm {
    var kind = "phone"; private set
    val noun get() = when (kind) { "tablet" -> "tablet"; "desktop" -> "computer"; else -> "phone" }

    /** Smallest side of the whole screen in dp (not this window, which shrinks in split screen). */
    private fun screenDp(ctx: Context): Int {
        val b = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return (minOf(b.width(), b.height()) / ctx.resources.displayMetrics.density).toInt()
    }

    fun init(ctx: Context) {
        val pm = ctx.packageManager
        kind = when {
            pm.hasSystemFeature(PackageManager.FEATURE_PC) || pm.hasSystemFeature("org.chromium.arc") -> "desktop"
            pm.hasSystemFeature("android.hardware.sensor.hinge_angle") -> "phone"
            screenDp(ctx) >= 600 -> "tablet"
            else -> "phone"
        }
    }
}
