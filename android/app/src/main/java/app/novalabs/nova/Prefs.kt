package app.novalabs.nova

import android.app.Activity
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
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
    var animSpeed by mutableStateOf(100); private set               // % of normal animation speed (25–300)
    var appLock by mutableStateOf(false); private set
    var hideInRecents by mutableStateOf(false); private set
    var homeHero by mutableStateOf(true); private set
    var homeShortcuts by mutableStateOf(true); private set
    var homeStats by mutableStateOf(true); private set
    var homeRecs by mutableStateOf(true); private set
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

    var iconTheme by mutableStateOf("style"); private set                  // in-app icons: style | tonal | tiles | circles | outline | glyph | mono
    var lite by mutableStateOf("auto"); private set                        // lighter effects: auto | on | off
    var accent by mutableStateOf(""); private set                          // this phone's own accent (#rrggbb), "" = the server's / the wallpaper's
    var tint by mutableStateOf(50); private set                            // Material You: how strongly the accent tints surfaces (0–100)
    var wallV by mutableStateOf(0L); private set                           // your own wallpaper (files/wallpaper.jpg): when it was set, 0 = none
    var wallBlur by mutableStateOf(16); private set                        // its blur (0–40)
    var wallDim by mutableStateOf(35); private set                         // and how much it's dimmed (0–90 %)
    var solid by mutableStateOf(false); private set                        // Reduce transparency: solid surfaces, no glass
    var lang by mutableStateOf("auto"); private set                        // Appearance → Language: auto (the phone's) or a code (I18n.kt)
    var glass by mutableStateOf(60); private set                           // Liquid Glass / Fluent: how clear the glass is (0–100)
    var round by mutableStateOf(100); private set                          // the old single roundness (before each look kept its own)
    private var roundV by mutableIntStateOf(0)
    /** Glass: Light splitting, 0–100 % (50 = the default split; 0 = none) */
    var prism by mutableIntStateOf(50); private set
    fun choosePrism(v: Int) { prism = v; sp.edit().putInt("prism", v).apply() }
    /** Default look: how see-through cards are (0–85 %) and how frosted (blur, 0–40 dp), as on the web. */
    var dTrans by mutableIntStateOf(0); private set
    var dFrost by mutableIntStateOf(26); private set
    /** Background behind the pages: "" each look's own, "glow" the Default look's glow on any look, "plain" just its color. */
    var lookBg by mutableStateOf(""); private set
    fun chooseLookBg(v: String) { lookBg = v; sp.edit().putString("lookBg", v).apply() }
    /** The soft tint at the top and bottom of the screen: on/off, and its color ("" = the page's). */
    var scrims by mutableStateOf(true); private set
    var scrimColor by mutableStateOf(""); private set
    fun chooseScrims(on: Boolean, color: String = scrimColor) { scrims = on; scrimColor = color; sp.edit().putBoolean("scrims", on).putString("scrimColor", color).apply() }
    /** Window roundness: dialogs (and windows) on top of the look's own corners, 0–300 %. */
    var winRound by mutableIntStateOf(100); private set
    fun chooseWinRound(v: Int) { winRound = v; sp.edit().putInt("winRound", v).apply() }
    /** Readability: auto | off | more (see readLevel()). */
    var readability by mutableStateOf("auto"); private set
    fun chooseReadability(v: String) { readability = v; sp.edit().putString("readability", v).apply() }
    /** Icons in your accent color: every in-app icon takes the accent (warnings stay red). */
    var iconAccent by mutableStateOf(false); private set
    fun chooseIconAccent(v: Boolean) { iconAccent = v; sp.edit().putBoolean("iconAccent", v).apply() }
    /** The colors picked last (newest first), shown on top of every color picker. */
    var recentColors by mutableStateOf(listOf<String>()); private set
    fun rememberColor(c: String) { recentColors = (listOf(c) + recentColors.filter { !it.equals(c, true) }).take(8); sp.edit().putString("recentColors", recentColors.joinToString(",")).apply() }
    fun chooseDefaultGlass(trans: Int = dTrans, frost: Int = dFrost) { dTrans = trans; dFrost = frost; sp.edit().putInt("dTrans", trans).putInt("dFrost", frost).apply() }
    /** Corner roundness for one look (25–200 % on the slider; see roundK()). */
    fun roundFor(style: String): Int { roundV; return sp.getString("round_$style", null)?.toIntOrNull() ?: round.coerceAtMost(200) }
    fun setRound(style: String, v: Int) { sp.edit().putString("round_$style", v.toString()).apply(); roundV++ }
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
        lite = sp.getString("lite", "auto")!!; iconTheme = sp.getString("icon_theme", "style")!!
        accent = sp.getString("accent", "")!!; tint = sp.getString("tint", "50")!!.toIntOrNull() ?: 50
        wallV = sp.getString("wall_v", "0")!!.toLongOrNull() ?: 0L; wallBlur = sp.getString("wall_blur", "16")!!.toIntOrNull() ?: 16; wallDim = sp.getString("wall_dim", "35")!!.toIntOrNull() ?: 35
        lang = sp.getString("lang", "auto")!!; I18n.load(ctx, lang)
        winRound = sp.getInt("winRound", 100); readability = sp.getString("readability", "auto")!!; iconAccent = sp.getBoolean("iconAccent", false)
        recentColors = sp.getString("recentColors", "")!!.split(",").filter { Regex("#[0-9a-fA-F]{6}").matches(it) }
        prism = sp.getInt("prism", 50); dTrans = sp.getInt("dTrans", 0); lookBg = sp.getString("lookBg", "")!!; scrims = sp.getBoolean("scrims", true); scrimColor = sp.getString("scrimColor", "")!!; dFrost = sp.getInt("dFrost", 26); solid = sp.getBoolean("solid", false); glass = sp.getString("glass", "60")!!.toIntOrNull() ?: 60; round = sp.getString("round", "100")!!.toIntOrNull() ?: 100
        theme = sp.getString("theme", "system")!!; style = sp.getString("style", "auto")!!; reduceMotion = sp.getBoolean("reduce_motion", false); animSpeed = (sp.getString("anim_speed", "100")!!.toIntOrNull() ?: 100).coerceIn(25, 300)
        appLock = sp.getBoolean("app_lock", false); hideInRecents = sp.getBoolean("hide_recents", false)
        homeHero = sp.getBoolean("home_hero", true); homeShortcuts = sp.getBoolean("home_shortcuts", true); homeStats = sp.getBoolean("home_stats", true); homeRecs = sp.getBoolean("home_recs", true)
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
            "lang" -> lang = v as String
            "solid" -> solid = v as Boolean; "glass" -> glass = (v as Number).toInt(); "round" -> round = (v as Number).toInt()
            "lite" -> lite = v as String; "icon_theme" -> iconTheme = v as String; "theme" -> theme = v as String; "style" -> style = v as String; "reduce_motion" -> reduceMotion = v as Boolean; "anim_speed" -> animSpeed = (v as Number).toInt(); "app_lock" -> appLock = v as Boolean
            "hide_recents" -> hideInRecents = v as Boolean; "home_hero" -> homeHero = v as Boolean
            "home_shortcuts" -> homeShortcuts = v as Boolean; "home_stats" -> homeStats = v as Boolean; "home_recs" -> homeRecs = v as Boolean
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
fun materialStyle(): Boolean = when (AppPrefs.style) { "material" -> true; "default", "liquid", "fluent", "breeze", "aero" -> false; else -> autoMaterial() }
/** Glass, Mica or Breeze (Appearance → Style; internally liquid / fluent / breeze), "" for Default / Material You. */
fun presetStyle(): String = AppPrefs.style.takeIf { it in setOf("liquid", "fluent", "breeze", "aero") } ?: ""
/** Reduce transparency (Appearance): solid surfaces everywhere, no blur or bending. */
fun solidGlass(): Boolean = AppPrefs.solid

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
        Compat.authenticate(a, "Unlock Nova", "Confirm it's you", null, { unlocked = true; onDone(true) }, { onDone(false) })
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

/** A labeled slider inside a group (the value on the right). */
@Composable private fun TuneSlider(label: String, value: Int, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = 6.dp)) {
        Row { Text(label, color = N.sub, fontSize = 14.sp, modifier = Modifier.weight(1f)); Text("$value$unit", color = N.sub, fontSize = 14.sp) }
        OneSlider(value.toFloat(), { onChange(it.toInt()) }, range)
    }
}

private val STYLE_CHOICES = listOf("auto" to "Automatic", "default" to "Default", "material" to "Material You", "liquid" to "Glass", "fluent" to "Mica", "breeze" to "Breeze", "aero" to "Aero")
private fun styleDesc(k: String): String = when (k) {
    "auto" -> "${if (autoMaterial()) "Material You" else "Default"} — picked to suit this ${DeviceForm.noun}"
    "default" -> "Nova's own look: frosted glass and a soft glow"
    "material" -> "Material 3 Expressive: colors from your wallpaper, bolder shapes, springy motion"
    "liquid" -> "Clear glass that bends the light behind it at its edges" + if (android.os.Build.VERSION.SDK_INT < 33) " (the bending needs Android 13)" else ""
    "fluent" -> "A soft, tinted base with frosted menus and crisp, small corners"
    "breeze" -> "Flat and tidy: a thin frame, gentle corners, the accent as the focus color"
    else -> "Tinted frosted glass frames with a glossy shine and glowing buttons"
}
private val READ_CHOICES = listOf("auto" to ("Automatic" to "Stronger text only where the glass or the background makes it hard to read"),
    "off" to ("Off" to "Text exactly as the look draws it"), "more" to ("Always stronger" to "Bolder, higher-contrast text everywhere"))

/** This phone can buzz (tablets and some phones have no vibration motor — then the switch would do nothing). */
private fun hasVibrator(ctx: Context): Boolean = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java).defaultVibrator.hasVibrator()
    else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator).hasVibrator()
}.getOrDefault(false)

/** Settings → Appearance & privacy: a few short groups — Look, Colors, Background, Icons and text, Motion and effects,
 *  Sounds and touch, Home — with the details folded into dropdowns and dialogs. Same layout as Nova web. */
@Composable fun AppearanceScreen(app: AppState) {
    var pickStyle by remember { mutableStateOf(false) }; var pickRead by remember { mutableStateOf(false) }; var pickOpen by remember { mutableStateOf(false) }
    Page("Appearance & privacy", app::back) {
        val ps = presetStyle(); val mat = materialStyle()
        SectionLabel("Look")
        Group {
            Row1("Style", "${STYLE_CHOICES.first { it.first == AppPrefs.style.takeIf { s -> STYLE_CHOICES.any { it.first == s } } ?: "auto" }.second} — ${styleDesc(AppPrefs.style)}", true, Icons.Rounded.Palette, onClick = { pickStyle = true })
            RowDivider()
            Text("Light or dark", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, top = 12.dp))
            Segmented(listOf("Automatic", "Light", "Dark"), listOf("system", "light", "dark").indexOf(AppPrefs.theme).coerceAtLeast(0)) { AppPrefs.set("theme", listOf("system", "light", "dark")[it]) }
            RowDivider()
            val rs = ps
            val sum = listOfNotNull(if (ps == "liquid") "Splitting ${AppPrefs.prism}%" else null, if (ps.isNotEmpty() && ps != "breeze") "Clarity ${AppPrefs.glass}%" else null,
                if (ps.isEmpty() && !mat) "See-through ${AppPrefs.dTrans}%" else null, "Corners ${AppPrefs.roundFor(rs)}%", "Windows ${AppPrefs.winRound}%").joinToString(" · ")
            ExpandRow("Shape and glass", sum, icon = Icons.Rounded.Tune) {
                Column(Modifier.padding(start = 0.dp)) {
                    if (ps == "liquid") TuneSlider("Light splitting", AppPrefs.prism, 0f..100f, "%") { AppPrefs.choosePrism(it) }
                    if (ps.isNotEmpty() && ps != "breeze") TuneSlider("Glass clarity", AppPrefs.glass, 0f..100f, "%") { AppPrefs.set("glass", it) }
                    if (ps.isEmpty() && !mat) {
                        TuneSlider("Transparency", AppPrefs.dTrans, 0f..85f, "%") { AppPrefs.chooseDefaultGlass(trans = it) }
                        TuneSlider("Frost", AppPrefs.dFrost, 0f..40f, " dp") { AppPrefs.chooseDefaultGlass(frost = it) }
                    }
                    TuneSlider("Corner roundness", AppPrefs.roundFor(rs), 25f..200f, "%") { AppPrefs.setRound(rs, it) }
                    TuneSlider("Window roundness", AppPrefs.winRound, 0f..300f, "%") { AppPrefs.chooseWinRound(it) }
                }
            }
        }
        if (ps == "liquid") Note("Glass is the most demanding look: on some phones it can make things warm, use more battery or stutter. Lighter effects (below) keeps the look with less work.", 13.sp, 4.dp)
        ColorsAndBackground()
        SectionLabel("Icons and text")
        Group {
            Row1("Icons", listOfNotNull(AppIcon.base.firstOrNull { it.id == AppIcon.current(androidx.compose.ui.platform.LocalContext.current) }?.label ?: "Your color", IconShape.NAMES[IconShape.id],
                ICON_THEMES.firstOrNull { it.first == AppPrefs.iconTheme }?.second, if (AppPrefs.iconAccent) "in your accent color" else null).joinToString(" · "), true, Icons.Rounded.Apps, onClick = { app.go(Route.Icons) })
            RowDivider()
            val r = READ_CHOICES.first { it.first == AppPrefs.readability }.second
            Row1("Readability", "${r.first} — ${r.second}", true, Icons.Rounded.TextFields, onClick = { pickRead = true })
            RowDivider()
            LanguageRow()
        }
        SectionLabel("Motion and effects")
        Group {
            SwitchRow("Reduce motion", "Simple fades instead of slides and bounces, and no back-gesture animation", AppPrefs.reduceMotion) { AppPrefs.set("reduce_motion", it) }
            RowDivider()
            var sp by remember { mutableFloatStateOf(AppPrefs.animSpeed.toFloat()) }
            SliderRow("Animation speed", sp, 25f..300f, sp.toInt().let { if (it == 100) "Normal (1×)" else "%s× %s".format((it / 100f).toString().removeSuffix(".0"), if (it > 100) "faster" else "speed") },
                steps = 10, onChange = { sp = it; AppPrefs.set("anim_speed", it.toInt()) }, onDone = {})
            RowDivider()
            SwitchRow("Reduce transparency", "Solid surfaces instead of glass — easier to read, lighter to draw", AppPrefs.solid) { AppPrefs.set("solid", it) }
            RowDivider()
            Text("Lighter effects — no blur or soft shadows, calmer animations${if (AppPrefs.slowDevice) " (Automatic: on for this phone)" else ""}", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 12.dp))
            Segmented(listOf("Automatic", "On", "Off"), listOf("auto", "on", "off").indexOf(AppPrefs.lite).let { if (it < 0) 0 else it }) { AppPrefs.set("lite", listOf("auto", "on", "off")[it]) }
        }
        EffectsCheck()
        SoundSettings()
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
            RowDivider()
            Row1("Open Nova on", START_ROUTES.firstOrNull { it.first == AppPrefs.startRoute }?.second?.first ?: "Home", true, Icons.Rounded.Home, onClick = { pickOpen = true })
        }
        Text("Each server also has its own name and accent color (Settings → Server).", color = N.sub, fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        SectionLabel("Privacy")
        Group { Row1("Security & privacy", "App lock, hiding Nova in recent apps, approvals, authenticator codes, passkeys", true, Icons.Rounded.Shield, N.green, onClick = { app.go(Route.Security) }) }
    }
    if (pickStyle) OneDialog({ pickStyle = false }, "Style") {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            STYLE_CHOICES.forEach { (k, l) -> DialogChoice(l, styleDesc(k), AppPrefs.style == k) { AppPrefs.set("style", k); pickStyle = false } }
        }
    }
    if (pickRead) OneDialog({ pickRead = false }, "Readability") {
        READ_CHOICES.forEach { (k, l) -> DialogChoice(l.first, l.second, AppPrefs.readability == k) { AppPrefs.chooseReadability(k); pickRead = false } }
    }
    if (pickOpen) OneDialog({ pickOpen = false }, "Open Nova on") {
        START_ROUTES.forEach { (k, l) -> DialogChoice(l.first, null, AppPrefs.startRoute == k) { AppPrefs.set("start_route", k); pickOpen = false } }
    }
}


private val MY_ACCENTS = listOf("#3e91ff", "#5e5ce6", "#bf5af2", "#ff2d55", "#ff9500", "#34c759", "#00c7be")
private val BGS = listOf("" to "This look's own", "glow" to "Default's glow", "vivid" to "Glass's vivid glow", "soft" to "Mica's soft wash", "aero" to "Aero's sky", "plain" to "Plain")

/** A small gallery: [items] (key to label), each drawn by [paint] as a whole screen shrunk down. */
@Composable private fun Gallery(items: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, paint: androidx.compose.ui.graphics.drawscope.DrawScope.(String) -> Unit) {
    val t = N
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val ratio = cfg.screenWidthDp.toFloat() / cfg.screenHeightDp.coerceAtLeast(1)
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { (k, l) ->
            val on = selected == k
            Column(Modifier.weight(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { onPick(k) }, horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().aspectRatio(ratio).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(t.bg)
                    .border(if (on) 3.dp else 1.dp, if (on) t.blue else t.divider, androidx.compose.foundation.shape.RoundedCornerShape(10.dp))) { paint(k) }
                Text(l, color = if (on) t.blue else N.text, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp), maxLines = 2)
            } }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) } } }
    }
}

/** Appearance → Colors (your own accent, Material You's color strength) and Background (a picture, every look's built-in
 *  background, the living wallpaper, the top and bottom fades). */
@Composable private fun ColorsAndBackground() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var picking by remember { mutableStateOf(false) }; var pickingLive by remember { mutableStateOf(false) }; var scrimPick by remember { mutableStateOf(false) }
    var bgGallery by remember { mutableStateOf(false) }; var sceneGallery by remember { mutableStateOf(false) }
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
    val hexColor = { h: String -> runCatching { Color(android.graphics.Color.parseColor(h)) }.getOrNull() }
    SectionLabel("Colors")
    Group {
        val cur = AppPrefs.accent
        Row1("Accent color", if (cur.isEmpty()) (if (materialStyle()) "From your wallpaper" else "The server's color") + " — tap to pick your own" else "This ${DeviceForm.noun}'s own color",
            cur.isNotEmpty(), onClick = { picking = true }) {
            Box(Modifier.size(32.dp).clip(androidx.compose.foundation.shape.CircleShape).background(cur.takeIf { it.isNotEmpty() }?.let { hexColor(it) } ?: N.blue))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            MY_ACCENTS.forEach { h ->
                val on = cur.equals(h, true)
                Box(Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(android.graphics.Color.parseColor(h)))
                    .then(if (on) Modifier.border(3.dp, N.text, androidx.compose.foundation.shape.CircleShape) else Modifier)
                    .clickable(onClickLabel = "Accent $h") { AppPrefs.set("accent", if (on) "" else h) })
            }
        }
        if (cur.isNotEmpty()) { RowDivider(); Row1(if (materialStyle()) "Use the wallpaper's colors" else "Use the server's color", null, true, Icons.Rounded.Refresh, onClick = { AppPrefs.set("accent", "") }) }
        if (materialStyle()) { RowDivider(); TuneSlider("Color strength", AppPrefs.tint, 0f..100f, "%") { AppPrefs.set("tint", it) } }
    }
    SectionLabel("Background")
    Group {
        if (AppPrefs.wallV > 0L) {
            TuneSlider("Picture blur", AppPrefs.wallBlur, 0f..40f, "") { AppPrefs.set("wall_blur", it) }
            TuneSlider("Picture dim", AppPrefs.wallDim, 0f..90f, "%") { AppPrefs.set("wall_dim", it) }
            RowDivider()
            Row1("Change the picture", null, true, Icons.Rounded.Image, onClick = { pick.launch("image/*") }); RowDivider()
            Row1("Remove the picture", null, false, Icons.Rounded.Delete, N.red, onClick = { java.io.File(ctx.filesDir, "wallpaper.jpg").delete(); AppPrefs.set("wall_v", 0L) })
        } else {
            Row1("Your own picture", "Behind every screen, blurred and dimmed as you like. Kept on this ${DeviceForm.noun} only.", true, Icons.Rounded.Image, onClick = { pick.launch("image/*") })
            RowDivider()
            Row1("Built-in background", BGS.firstOrNull { it.first == AppPrefs.lookBg }?.second ?: "This look's own", true, Icons.Rounded.Palette, onClick = { bgGallery = true })
        }
        RowDivider()
        SwitchRow("Living wallpaper · Beta", if (AppPrefs.wallV > 0L) "An animated wallpaper in your accent color — your picture shows instead while it's set"
            else "An animated wallpaper in your accent color: Aero's ribbons of light, Mica's bloom, Breeze's waves and more", Living.on) { Living.set(it) }
        if (Living.on) {
            RowDivider()
            Row1("Scene", if (Living.scene == "auto") "Match the look (${Living.SCENES[Living.sceneFor(N)]})" else Living.SCENES[Living.scene] ?: "", true, Icons.Rounded.Image, onClick = { sceneGallery = true })
            RowDivider()
            Row1("Wallpaper color", if (Living.accent.isEmpty()) "Same as the accent color" else "Its own color", Living.accent.isNotEmpty(), Icons.Rounded.Palette, onClick = { pickingLive = true }) {
                Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Living.accent.takeIf { it.length == 7 }?.let { hexColor(it) } ?: N.blue)) }
            RowDivider()
            ExpandRow("Motion and tint", "Speed ${Living.speed}% · Tint ${Living.tint}%", icon = Icons.Rounded.Tune) {
                Column {
                    TuneSlider("Speed", Living.speed, 10f..300f, "%") { Living.chooseSpeed(it) }
                    TuneSlider("Tint", Living.tint, 0f..80f, "%") { Living.chooseTint(it) }
                }
            }
        }
        RowDivider()
        SwitchRow("Top and bottom fades", "The soft tint at the top and bottom of the screen, so the page fades out under the bars", AppPrefs.scrims) { AppPrefs.chooseScrims(it) }
        if (AppPrefs.scrims) { RowDivider()
            Row1("Fade color", if (AppPrefs.scrimColor.isEmpty()) "The page's color" else "Your own color", AppPrefs.scrimColor.isNotEmpty(), Icons.Rounded.Gradient, onClick = { scrimPick = true }) {
                Box(Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(AppPrefs.scrimColor.takeIf { it.length == 7 }?.let { hexColor(it) } ?: N.bg)
                    .border(1.dp, N.divider, androidx.compose.foundation.shape.CircleShape)) } }
    }
    if (picking) ColorPickerDialog(AppPrefs.accent.ifEmpty { "#3e91ff" }, { picking = false }, title = "Accent color") { AppPrefs.set("accent", it); picking = false }
    if (pickingLive) { val was = Living.accent
        ColorPickerDialog(Living.accent.ifEmpty { "#" + Integer.toHexString(N.blue.toArgb()).takeLast(6) }, { pickingLive = false }, title = "Wallpaper color", reset = "Same as the accent color",
            onReset = { Living.chooseAccent(""); pickingLive = false }, onLive = { Living.chooseAccent(it ?: was) }) { Living.chooseAccent(it); pickingLive = false } }
    // straight to the picker — your recent colors and the page's own color are on top
    if (scrimPick) { val was = AppPrefs.scrimColor
        ColorPickerDialog(AppPrefs.scrimColor.ifEmpty { "#000000" }, { scrimPick = false }, title = "Fade color", reset = "The page's color",
            onReset = { AppPrefs.chooseScrims(true, ""); scrimPick = false }, onLive = { AppPrefs.chooseScrims(true, it ?: was) }) { AppPrefs.chooseScrims(true, it); scrimPick = false } }
    if (bgGallery) OneDialog({ bgGallery = false }, "Built-in background", "Every look's background — pick one for any look.", listOf(DialogButton("Close") { bgGallery = false })) {
        val t = N
        Gallery(BGS, AppPrefs.lookBg, { AppPrefs.chooseLookBg(it) }) { k ->
            val kind = if (k.isEmpty()) (if (t.material || t.glow == Color.Transparent) "plain" else "own") else k
            if (kind != "plain") drawWall(t, kind, androidx.compose.ui.geometry.Offset.Zero, size.width, size.height)
        }
    }
    if (sceneGallery) OneDialog({ sceneGallery = false }, "Scene", "Every look's living wallpaper — pick one for any look.", listOf(DialogButton("Close") { sceneGallery = false })) {
        val t = N
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            Gallery(listOf("auto" to "Match the look") + Living.SCENES.toList(), Living.scene, { Living.chooseScene(it) }) { k ->
                drawLiving(t, androidx.compose.ui.geometry.Offset.Zero, size.width, size.height, 20f, k)
            }
        }
    }
}


/** Appearance → Language: the phone's own, or one of Nova's. Translations are made by machine. */
@Composable private fun LanguageRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var pick by remember { mutableStateOf(false) }
    Row1("Language", (if (AppPrefs.lang == "auto") "Same as this ${DeviceForm.noun} (${I18n.LANGS.first { it.first == I18n.pick("auto") }.second})"
            else I18n.LANGS.firstOrNull { it.first == AppPrefs.lang }?.second ?: AppPrefs.lang) + " · translated by machine", true, Icons.Rounded.Translate, onClick = { pick = true })
    if (pick) OneDialog({ pick = false }, "Language", "Nova's own words are translated by machine, so some may read oddly — tell us on GitHub. Messages written by the server stay in English for now.") {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
            (listOf("auto" to "Same as this ${DeviceForm.noun}") + I18n.LANGS).forEach { (k, n) ->
                DialogChoice(n, selected = AppPrefs.lang == k) { pick = false
                    if (k != AppPrefs.lang) { AppPrefs.set("lang", k); I18n.load(ctx, k); (ctx as? android.app.Activity)?.recreate() } }
            }
        }
    }
}


/** Can this phone draw the chosen look? Live blur needs Android 12; Battery Saver and "remove animations" also change things.
 *  Shown only when something's missing, with what to do about it. */
@Composable fun EffectsCheck() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val pm = remember { ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager }
    val saver = pm.isPowerSaveMode
    val glassy = !N.material && N.preset != "breeze" && !AppPrefs.solid
    val issues = buildList {
        if (!view.isHardwareAccelerated) add("This screen isn't drawn by the graphics chip, so effects are turned down.")
        if (glassy && android.os.Build.VERSION.SDK_INT < 31) add("Live blur needs Android 12 or newer — on this phone glass shows as a tinted, see-through surface. Turn on Reduce transparency for solid ones.")
        if (saver) add("Battery Saver is on, so Android may slow animations and the live graphs. Turn it off in Settings → Battery for the full effects.")
    }
    if (issues.isEmpty()) return
    Group {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("Some effects can't run fully here", color = N.amber, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            issues.forEach { Text(it, color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            if (saver) Text("Open battery settings", color = N.blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp).clickable { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        }
    }
}


val ICON_THEMES = listOf("style" to "Matches the style", "tonal" to "Tonal", "tiles" to "Tiles", "circles" to "Circles", "outline" to "Outline", "glyph" to "Symbols only", "mono" to "Monochrome", "glossy" to "Glossy")
/** The in-app icon styles, each with a preview (Appearance → Icons → Icon style). */
@Composable fun IconThemeList() {
    val samples = listOf(androidx.compose.material.icons.Icons.Rounded.MonitorHeart to N.green, androidx.compose.material.icons.Icons.Rounded.Backup to N.blue,
        androidx.compose.material.icons.Icons.Rounded.CleaningServices to N.amber)
    Column {
        ICON_THEMES.forEach { (k, l) ->
            Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clickable { AppPrefs.set("icon_theme", k) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(l, fontSize = 16.sp, color = N.text, modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { samples.forEach { (ic, c) -> RowIcon(ic, c, k) } }
                Spacer(Modifier.width(12.dp)); OneRadio(AppPrefs.iconTheme == k)
            }
        }
    }
}

/** Appearance → Sounds & haptics: a sound pack for each look (or one you pick), volume, alert chimes, vibration. */
@Composable fun SoundSettings() {
    SectionLabel("Sounds and touch")
    var pick by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val canBuzz = remember { hasVibrator(ctx) }
    Group {
        SwitchRow("Sounds", "Taps, switches, pages and alerts — each look has its own sound pack", Sounds.on) { Sounds.set("on", it); if (it) { Sounds.warm(); Sounds.play("toggleOn", force = true) } }
        if (Sounds.on) {
            RowDivider()
            ExpandRow("Sound options", "${if (Sounds.pack == "auto") "Match the look (${Sounds.PACKS[Sounds.packId()]})" else Sounds.PACKS[Sounds.pack] ?: "Match the look"} · ${Sounds.volume}%${if (Sounds.alerts) " · alert chimes" else ""}", icon = Icons.Rounded.Tune) {
                Column {
                    Row1("Sound pack", if (Sounds.pack == "auto") "Match the look (${Sounds.PACKS[Sounds.packId()]})" else Sounds.PACKS[Sounds.pack] ?: "Match the look", subtitleBlue = true, onClick = { pick = true })
                    var v by remember { mutableFloatStateOf(Sounds.volume.toFloat()) }
                    SliderRow("Volume", v, 0f..100f, "${v.toInt()}%", steps = 19, onChange = { v = it; Sounds.set("vol", it.toInt()) }, onDone = { Sounds.play("tap") })
                    SwitchRow("Alert sounds", "A chime when something needs you — a more urgent one when it's serious", Sounds.alerts) { Sounds.set("alerts", it); if (it) Sounds.play("info") }
                    var kindsOpen by remember { mutableStateOf(false) }
                    Row1("Which sounds", Sounds.KINDS.filter { it.key !in Sounds.off }.values.joinToString(", ") { it.first }.ifEmpty { "None" }, onClick = { kindsOpen = !kindsOpen }) {
                        Icon(if (kindsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = N.sub) }
                    androidx.compose.animation.AnimatedVisibility(kindsOpen) {
                        Column { Sounds.KINDS.forEach { (k, ld) -> SwitchRow(ld.first, ld.second, k !in Sounds.off) { Sounds.setKind(k, it) } } }
                    }
                }
            }
        }
        RowDivider()
        SwitchRow("Ambience · Beta", "Soft, slowly changing music while Nova is open, in the mood of the look you picked", Ambience.on) { Ambience.set("on", it) }
        if (Ambience.on) {
            var pickTrack by remember { mutableStateOf(false) }
            RowDivider()
            Row1("Track", if (Ambience.track == "auto") "Match the look" else Ambience.TRACKS[Ambience.track] ?: "", true, onClick = { pickTrack = true })
            if (pickTrack) OneDialog({ pickTrack = false }, "Ambience") {
                (listOf("auto" to "Match the look") + Ambience.TRACKS.toList()).forEach { (k, n) -> DialogChoice(n, selected = Ambience.track == k) { Ambience.set("track", k); pickTrack = false } }
            }
            RowDivider()
            var av by remember { mutableFloatStateOf(Ambience.volume.toFloat()) }
            SliderRow("Ambience volume", av, 0f..100f, "${av.toInt()}%", steps = 19, onChange = { av = it; Ambience.set("vol", it.toInt()) }, onDone = {})
        }
        if (canBuzz) { RowDivider()
            SwitchRow("Vibration", "A light buzz with taps and switches (when the phone's touch feedback is on) and with alerts", Sounds.haptics) { Sounds.set("haptics", it); Sounds.buzz("success") } }
    }
    if (pick) OneDialog({ pick = false }, "Sound pack") {
        (listOf("auto" to "Match the look") + Sounds.PACKS.toList()).forEach { (k, n) ->
            DialogChoice(n, selected = Sounds.pack == k) { Sounds.set("pack", k); pick = false; Sounds.play("info", force = true) }     // a taste of it
        }
    }
}
