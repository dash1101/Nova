package app.novalabs.nova

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** App-wide preferences (not per server). Compose state, so changes apply instantly. */
object AppPrefs {
    private lateinit var sp: android.content.SharedPreferences
    var theme by mutableStateOf("system"); private set            // system | light | dark
    var reduceMotion by mutableStateOf(false); private set
    var appLock by mutableStateOf(false); private set
    var hideInRecents by mutableStateOf(false); private set
    var homeHero by mutableStateOf(true); private set
    var homeShortcuts by mutableStateOf(true); private set
    var homeStats by mutableStateOf(true); private set
    var homeChips by mutableStateOf(DEFAULT_SHORTCUTS); private set        // Home shortcut bar, in order
    var homeOrder by mutableStateOf(HOME_SECTIONS); private set            // Home sections, top to bottom
    var navTabs by mutableStateOf(DEFAULT_TABS); private set               // bottom bar, left to right

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        theme = sp.getString("theme", "system")!!; reduceMotion = sp.getBoolean("reduce_motion", false)
        appLock = sp.getBoolean("app_lock", false); hideInRecents = sp.getBoolean("hide_recents", false)
        homeHero = sp.getBoolean("home_hero", true); homeShortcuts = sp.getBoolean("home_shortcuts", true); homeStats = sp.getBoolean("home_stats", true)
        homeOrder = sp.getString("home_order", null)?.split(",")?.filter { it in HOME_SECTIONS }?.let { it + (HOME_SECTIONS - it.toSet()) } ?: HOME_SECTIONS
        navTabs = sp.getString("nav_tabs", null)?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_TABS
        homeChips = sp.getString("home_chips", null)?.let { s -> s.split(",").filter { it.isNotBlank() } } ?: DEFAULT_SHORTCUTS
    }
    fun set(key: String, v: Any) {
        when (key) {
            "theme" -> theme = v as String; "reduce_motion" -> reduceMotion = v as Boolean; "app_lock" -> appLock = v as Boolean
            "hide_recents" -> hideInRecents = v as Boolean; "home_hero" -> homeHero = v as Boolean
            "home_shortcuts" -> homeShortcuts = v as Boolean; "home_stats" -> homeStats = v as Boolean
            "home_order" -> homeOrder = (v as String).split(",")
            "nav_tabs" -> navTabs = (v as String).split(",").filter { it.isNotBlank() }
            "home_chips" -> homeChips = (v as String).split(",").filter { it.isNotBlank() }
        }
        sp.edit().apply { if (v is Boolean) putBoolean(key, v) else putString(key, v.toString()) }.apply()
    }
}

/** Honour both the app setting and Android's "remove animations". */
fun reduceMotion(): Boolean = AppPrefs.reduceMotion

/** FLAG_SECURE: hides Nova's contents in the recent-apps view and blocks screenshots. */
fun applySecureFlag(a: Activity) {
    if (AppPrefs.hideInRecents) a.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

/** App lock: fingerprint / PIN to open Nova, and again after 5 minutes in the background. */
object AppLock {
    var unlocked by mutableStateOf(false)
    private var leftAt = 0L
    fun onStop() { leftAt = System.currentTimeMillis() }
    fun onStart() { if (AppPrefs.appLock && leftAt > 0 && System.currentTimeMillis() - leftAt > 5 * 60_000) unlocked = false }
    fun prompt(a: Activity, onDone: (Boolean) -> Unit) {
        BiometricPrompt.Builder(a).setTitle("Unlock Nova").setSubtitle("Confirm it's you")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
            .authenticate(CancellationSignal(), a.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { unlocked = true; onDone(true) }
                override fun onAuthenticationError(code: Int, msg: CharSequence) { onDone(false) }
            })
    }
}

@Composable fun LockScreen(activity: Activity) {
    LaunchedEffect(Unit) { AppLock.prompt(activity) {} }
    GlowBackground {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Lock, null, tint = N.text, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(16.dp))
            Text("Nova is locked", color = N.text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Unlock") { AppLock.prompt(activity) {} }
        }
    }
}

/** Settings → Appearance & privacy. */
@Composable fun AppearanceScreen(app: AppState) {
    Page("Appearance & privacy", app::back) {
        SectionLabel("Theme")
        Group {
            listOf("system" to "Same as the phone", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (k, l) ->
                if (i > 0) RowDivider()
                Row1(l, null, onClick = { AppPrefs.set("theme", k) }) { OneRadio(AppPrefs.theme == k) }
            }
        }
        Group {
            SwitchRow("Reduce motion", "Simple fades instead of slides and bounces", AppPrefs.reduceMotion) { AppPrefs.set("reduce_motion", it) }
        }
        SectionLabel("Home")
        Group {
            Row1("Home layout", AppPrefs.homeOrder.filter { sectionOn(it) }.joinToString(" · ") { sectionName(it) }.ifEmpty { "Just the header" }, true,
                Icons.Rounded.Dashboard, onClick = { app.go(Route.EditHome) })
            RowDivider()
            Row1("Choose shortcuts", homeShortcuts(app).joinToString(", ") { it.label }.ifEmpty { "None" }, true,
                Icons.Rounded.Tune, onClick = { app.go(Route.EditShortcuts) })
            RowDivider()
            Row1("Bottom bar", navTabs(app).joinToString(", ") { it.label }, true,
                Icons.Rounded.ViewDay, onClick = { app.go(Route.EditTabs) })
        }
        Text("Each server also has its own name and accent colour (Settings → Server).", color = N.sub, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        SectionLabel("Privacy")
        Group {
            SwitchRow("App lock", "Fingerprint or PIN to open Nova, and again after 5 minutes away", AppPrefs.appLock) { on ->
                if (on) AppLock.prompt(app.activity) { ok -> if (ok) AppPrefs.set("app_lock", true) } else AppPrefs.set("app_lock", false) }
            RowDivider()
            SwitchRow("Hide in recent apps", "Blank preview in the app switcher; screenshots are blocked", AppPrefs.hideInRecents) {
                AppPrefs.set("hide_recents", it); applySecureFlag(app.activity) }
        }
    }
}
