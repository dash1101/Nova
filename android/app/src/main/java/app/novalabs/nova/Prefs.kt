package app.novalabs.nova

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    var style by mutableStateOf("auto"); private set              // auto | default (One UI-like) | material (Material You Expressive)
    var reduceMotion by mutableStateOf(false); private set
    var appLock by mutableStateOf(false); private set
    var hideInRecents by mutableStateOf(false); private set
    var homeHero by mutableStateOf(true); private set
    var homeShortcuts by mutableStateOf(true); private set
    var homeStats by mutableStateOf(true); private set
    var homeChips by mutableStateOf(DEFAULT_SHORTCUTS); private set        // Home shortcut bar, in order
    var homeOrder by mutableStateOf(HOME_SECTIONS); private set            // Home sections, top to bottom
    var navTabs by mutableStateOf(DEFAULT_TABS); private set               // bottom bar, left to right
    var fanReverse by mutableStateOf(false); private set                   // the picture's wave runs the other way round
    var startRoute by mutableStateOf("home"); private set                  // the screen Nova opens on
    var startEngine by mutableStateOf("ddg"); private set                  // start page: web search engine
    var startHidden by mutableStateOf(listOf<String>()); private set       // start page: hidden sections
    var startLinks by mutableStateOf("[]"); private set                    // start page: bookmarks, JSON [{name,url}]
    var pinnedApps by mutableStateOf(listOf<String>()); private set        // apps pinned to the dock
    var favorites by mutableStateOf(listOf<String>()); private set         // Menu → Favorites
    var searchRecent by mutableStateOf(listOf<String>()); private set      // Search: recent queries (newest first)
    var searchPinned by mutableStateOf(listOf<String>()); private set      // Search: pinned queries
    var startCustom by mutableStateOf(""); private set                     // start page: your own search engine (https://…%s)
    var desktopApps by mutableStateOf(listOf<String>()); private set       // web apps shown as their desktop site
    var startSuggest by mutableStateOf(true); private set                  // start page: suggestions while typing
    var dismissedUpdate by mutableStateOf(""); private set                 // the update note you closed (shown again for a newer one)
    var shakeSense by mutableStateOf("normal"); private set               // how hard a shake has to be: low | normal | high
    var shakeLast by mutableStateOf("report"); private set                // what to go back to when shake is switched on again
    var shakeAction by mutableStateOf("report"); private set              // what a shake does: report | none | search | quick | lights | home | inbox

    var lite by mutableStateOf("auto"); private set                        // lighter effects: auto | on | off
    var accent by mutableStateOf(""); private set                          // this phone's own accent (#rrggbb), "" = the server's / the wallpaper's
    var tint by mutableStateOf(50); private set                            // Material You: how strongly the accent tints surfaces (0–100)
    var wallV by mutableStateOf(0L); private set                           // your own wallpaper (files/wallpaper.jpg): when it was set, 0 = none
    var wallBlur by mutableStateOf(16); private set                        // its blur (0–40)
    var wallDim by mutableStateOf(35); private set                         // and how much it's dimmed (0–90 %)
    /** Worked out once at start: an older or low-memory phone, where the live blur and 60 fps animations cost too much. */
    var slowDevice = false; private set

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.getSharedPreferences("nova_prefs", Context.MODE_PRIVATE)
        runCatching {
            val am = ctx.getSystemService(android.app.ActivityManager::class.java)
            val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            slowDevice = am.isLowRamDevice || mem.totalMem < 3L * 1024 * 1024 * 1024 || android.os.Build.VERSION.SDK_INT < 29 ||
                Runtime.getRuntime().availableProcessors() <= 4
        }
        lite = sp.getString("lite", "auto")!!
        accent = sp.getString("accent", "")!!; tint = sp.getString("tint", "50")!!.toIntOrNull() ?: 50
        wallV = sp.getString("wall_v", "0")!!.toLongOrNull() ?: 0L; wallBlur = sp.getString("wall_blur", "16")!!.toIntOrNull() ?: 16; wallDim = sp.getString("wall_dim", "35")!!.toIntOrNull() ?: 35
        theme = sp.getString("theme", "system")!!; style = sp.getString("style", "auto")!!; reduceMotion = sp.getBoolean("reduce_motion", false)
        appLock = sp.getBoolean("app_lock", false); hideInRecents = sp.getBoolean("hide_recents", false)
        homeHero = sp.getBoolean("home_hero", true); homeShortcuts = sp.getBoolean("home_shortcuts", true); homeStats = sp.getBoolean("home_stats", true)
        homeOrder = sp.getString("home_order", null)?.split(",")?.filter { it in HOME_SECTIONS }?.let { it + (HOME_SECTIONS - it.toSet()) } ?: HOME_SECTIONS
        fanReverse = sp.getBoolean("fan_reverse", false)
        startRoute = sp.getString("start_route", "home")!!; startEngine = sp.getString("start_engine", "ddg")!!
        startHidden = sp.getString("start_hidden", "")!!.split(",").filter { it.isNotBlank() }; startLinks = sp.getString("start_links", "[]")!!
        searchRecent = sp.getString("search_recent", "")!!.split("\n").filter { it.isNotBlank() }; searchPinned = sp.getString("search_pinned", "")!!.split("\n").filter { it.isNotBlank() }
        pinnedApps = sp.getString("pinned_apps", "")!!.split(",").filter { it.isNotBlank() }; favorites = sp.getString("favorites", "")!!.split(",").filter { it.isNotBlank() }
        navTabs = sp.getString("nav_tabs", null)?.split(",")?.filter { it.isNotBlank() } ?: DEFAULT_TABS
        homeChips = sp.getString("home_chips", null)?.let { s -> s.split(",").filter { it.isNotBlank() } } ?: DEFAULT_SHORTCUTS
        startCustom = sp.getString("start_custom", "")!!; startSuggest = sp.getBoolean("start_suggest", true)
        dismissedUpdate = sp.getString("dismissed_update", "")!!; shakeAction = sp.getString("shake_action", "report")!!
        desktopApps = sp.getString("desktop_apps", "")!!.split(",").filter { it.isNotBlank() }
        shakeSense = sp.getString("shake_sense", "normal")!!; shakeLast = sp.getString("shake_last", "report")!!
    }
    fun set(key: String, v: Any) {
        when (key) {
            "accent" -> accent = v as String; "tint" -> tint = (v as Number).toInt(); "wall_v" -> wallV = (v as Number).toLong()
            "wall_blur" -> wallBlur = (v as Number).toInt(); "wall_dim" -> wallDim = (v as Number).toInt()
            "lite" -> lite = v as String; "theme" -> theme = v as String; "style" -> style = v as String; "reduce_motion" -> reduceMotion = v as Boolean; "app_lock" -> appLock = v as Boolean
            "hide_recents" -> hideInRecents = v as Boolean; "home_hero" -> homeHero = v as Boolean
            "home_shortcuts" -> homeShortcuts = v as Boolean; "home_stats" -> homeStats = v as Boolean
            "home_order" -> homeOrder = (v as String).split(",")
            "fan_reverse" -> fanReverse = v as Boolean
            "start_route" -> startRoute = v as String; "start_engine" -> startEngine = v as String
            "start_hidden" -> startHidden = (v as String).split(",").filter { it.isNotBlank() }; "start_links" -> startLinks = v as String
            "search_recent" -> searchRecent = (v as String).split("\n").filter { it.isNotBlank() }; "search_pinned" -> searchPinned = (v as String).split("\n").filter { it.isNotBlank() }
            "pinned_apps" -> pinnedApps = (v as String).split(",").filter { it.isNotBlank() }; "favorites" -> favorites = (v as String).split(",").filter { it.isNotBlank() }
            "nav_tabs" -> navTabs = (v as String).split(",").filter { it.isNotBlank() }
            "home_chips" -> homeChips = (v as String).split(",").filter { it.isNotBlank() }
            "start_custom" -> startCustom = v as String; "start_suggest" -> startSuggest = v as Boolean
            "dismissed_update" -> dismissedUpdate = v as String; "shake_action" -> shakeAction = v as String
            "desktop_apps" -> desktopApps = (v as String).split(",").filter { it.isNotBlank() }
            "shake_sense" -> shakeSense = v as String; "shake_last" -> shakeLast = v as String
        }
        sp.edit().apply { if (v is Boolean) putBoolean(key, v) else putString(key, v.toString()) }.apply()
    }
}

/** Phones whose own UI is One UI- or iOS-like get Nova's default look; everyone else (Pixel, Motorola,
 *  Nothing, Sony, OnePlus…) gets Material You Expressive, so Nova matches the rest of the phone. */
private val DEFAULT_LOOK_MAKERS = setOf("samsung", "xiaomi", "redmi", "poco", "huawei", "honor", "vivo", "iqoo", "oppo", "realme", "meizu")
fun autoMaterial(): Boolean = android.os.Build.MANUFACTURER.lowercase() !in DEFAULT_LOOK_MAKERS && android.os.Build.BRAND.lowercase() !in DEFAULT_LOOK_MAKERS
fun materialStyle(): Boolean = when (AppPrefs.style) { "material" -> true; "default" -> false; else -> autoMaterial() }

/** Honor both the app setting and Android's "remove animations". */
fun reduceMotion(): Boolean = AppPrefs.reduceMotion

/** Lighter effects: solid surfaces instead of live blur, fewer shadows, slower-ticking animations. */
fun lite(): Boolean = AppPrefs.lite == "on" || (AppPrefs.lite == "auto" && AppPrefs.slowDevice)

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
        Compat.prompt(a, "Unlock Nova", "Confirm it's you", crypto = false).build()
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
        SectionLabel("Style")
        Group {
            val autoName = if (autoMaterial()) "Material You" else "Default"
            listOf("auto" to ("Automatic" to "$autoName — picked to suit this ${DeviceForm.noun}"),
                   "default" to ("Default" to "Nova's own look: frosted glass and a soft glow"),
                   "material" to ("Material You" to "Google's Material 3 Expressive: colors from your wallpaper, bolder shapes, springy motion"))
                .forEachIndexed { i, (k, l) ->
                    if (i > 0) RowDivider()
                    Row1(l.first, l.second, onClick = { AppPrefs.set("style", k) }) { OneRadio(AppPrefs.style == k) }
                }
        }
        SectionLabel("Theme")
        Group {
            val look = if (materialStyle()) "Material" else "Default"
            listOf("system" to "Same as the ${DeviceForm.noun}", "light" to "$look light", "dark" to "$look dark").forEachIndexed { i, (k, l) ->
                if (i > 0) RowDivider()
                Row1(l, null, onClick = { AppPrefs.set("theme", k) }) { OneRadio(AppPrefs.theme == k) }
            }
        }
        Group {
            SwitchRow("Reduce motion", "Simple fades instead of slides and bounces, and no back-gesture animation", AppPrefs.reduceMotion) { AppPrefs.set("reduce_motion", it) }
        }
        ColorsAndWallpaper()
        SectionLabel("Lighter effects")
        Segmented(listOf("Automatic", "On", "Off"), listOf("auto", "on", "off").indexOf(AppPrefs.lite).let { if (it < 0) 0 else it }) { AppPrefs.set("lite", listOf("auto", "on", "off")[it]) }
        Note("No frosted-glass blur or soft shadows, and calmer animations — much smoother on older phones. Automatic turns it on for this phone ${if (AppPrefs.slowDevice) "(it's on now)" else "only if it's an older or low-memory one (it's off now)"}.", 13.sp, 4.dp)
        AppIconPicker(app)
        SectionLabel("Shake")
        ShakeSettings()
        SectionLabel("Home")
        Group {
            Row1("Home layout", AppPrefs.homeOrder.filter { sectionOn(it) }.joinToString(" · ") { sectionName(it) }.ifEmpty { "Just the header" }, true,
                Icons.Rounded.Dashboard, onClick = { app.go(Route.EditHome) })
            RowDivider()
            Row1("Choose shortcuts", homeShortcuts(app).joinToString(", ") { it.label }.ifEmpty { "None" }, true,
                Icons.Rounded.Tune, onClick = { app.go(Route.EditShortcuts) })
            RowDivider()
            Row1("Navigation pill", navTabs(app).joinToString(", ") { it.label } + " · bottom, or left in landscape", true,
                Icons.Rounded.ViewDay, onClick = { app.go(Route.EditTabs) })
            RowDivider()
            Row1("Start page", "Search, server stats, apps and bookmarks", true, Icons.Rounded.TravelExplore, onClick = { app.go(Route.Start) })
        }
        SectionLabel("Open Nova on")
        Group {
            START_ROUTES.forEachIndexed { i, (k, l) ->
                if (i > 0) RowDivider()
                Row1(l.first, null, onClick = { AppPrefs.set("start_route", k) }) { OneRadio(AppPrefs.startRoute == k) }
            }
        }
        Text("Each server also has its own name and accent color (Settings → Server).", color = N.sub, fontSize = 13.sp,
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


private val MY_ACCENTS = listOf("#3e91ff", "#5e5ce6", "#bf5af2", "#ff2d55", "#ff9500", "#34c759", "#00c7be")

/** Appearance → Colors (your own accent, how strongly it tints) and Wallpaper (a picture, blurred and dimmed). */
@Composable private fun ColorsAndWallpaper() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {             // shrink it (longest side 2048 px) and keep a private copy in the app
            val src = ctx.contentResolver.openInputStream(uri)!!.use { android.graphics.BitmapFactory.decodeStream(it) }
            val k = minOf(1f, 2048f / maxOf(src.width, src.height))
            val b = android.graphics.Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true)
            java.io.File(ctx.filesDir, "wallpaper.jpg").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 86, it) }
            AppPrefs.set("wall_v", System.currentTimeMillis())
        }.onFailure { android.widget.Toast.makeText(ctx, "That picture couldn't be opened", android.widget.Toast.LENGTH_SHORT).show() }
    }
    SectionLabel("Colors")
    Group {
        val cur = AppPrefs.accent
        Row1("Accent color", if (cur.isEmpty()) (if (materialStyle()) "From your wallpaper" else "The server's color") + " — tap to pick your own" else "This ${DeviceForm.noun}'s own color",
            cur.isNotEmpty(), onClick = { picking = true }) {
            Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(cur.takeIf { it.isNotEmpty() }?.let { Color(android.graphics.Color.parseColor(it)) } ?: N.blue))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            MY_ACCENTS.forEach { h ->
                val on = cur.equals(h, true)
                Box(Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(android.graphics.Color.parseColor(h)))
                    .then(if (on) Modifier.border(3.dp, N.text, androidx.compose.foundation.shape.CircleShape) else Modifier)
                    .clickable { AppPrefs.set("accent", if (on) "" else h) })
            }
        }
        if (cur.isNotEmpty()) { RowDivider(); Row1(if (materialStyle()) "Use the wallpaper's colors" else "Use the server's color", null, true, Icons.Rounded.Refresh, onClick = { AppPrefs.set("accent", "") }) }
        RowDivider()
        Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
            Row { Text("Color strength${if (materialStyle()) "" else " (Material You)"}", color = N.sub, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text("${AppPrefs.tint}%", color = N.sub, fontSize = 14.sp) }
            OneSlider(AppPrefs.tint.toFloat(), { AppPrefs.set("tint", it.toInt()) }, 0f..100f)
        }
    }
    SectionLabel("Wallpaper")
    Group {
        if (AppPrefs.wallV > 0L) {
            Column(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Row { Text("Blur", color = N.sub, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text("${AppPrefs.wallBlur}", color = N.sub, fontSize = 14.sp) }
                OneSlider(AppPrefs.wallBlur.toFloat(), { AppPrefs.set("wall_blur", it.toInt()) }, 0f..40f)
                Row { Text("Dim", color = N.sub, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text("${AppPrefs.wallDim}%", color = N.sub, fontSize = 14.sp) }
                OneSlider(AppPrefs.wallDim.toFloat(), { AppPrefs.set("wall_dim", it.toInt()) }, 0f..90f)
            }
            RowDivider()
            Row1("Change the picture", null, true, Icons.Rounded.Image, onClick = { pick.launch("image/*") }); RowDivider()
            Row1("Remove the wallpaper", null, false, Icons.Rounded.Delete, N.red, onClick = { java.io.File(ctx.filesDir, "wallpaper.jpg").delete(); AppPrefs.set("wall_v", 0L) })
        } else Row1("Choose a picture", "Behind every screen, blurred and dimmed as you like. Kept on this ${DeviceForm.noun} only.", true, Icons.Rounded.Image, onClick = { pick.launch("image/*") })
    }
    if (picking) ColorPickerDialog(AppPrefs.accent.ifEmpty { "#3e91ff" }, { picking = false }) { AppPrefs.set("accent", it); picking = false }
}
