package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val SWATCHES = listOf("#ffffff", "#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#00c7be",
    "#005aff", "#3e91ff", "#5e5ce6", "#bf5af2", "#ff2d55", "#ff6b9a")
private val EFFECTS = listOf(
    "static" to ("Static" to "One steady color"), "pulse" to ("Pulse" to "Breathes in and out"),
    "blink" to ("Blink" to "Flashes on and off"), "cycle" to ("Color cycle" to "Fades through colors"),
    "wave" to ("Wave" to "Colors chase around the ring"), "comet" to ("Comet" to "A bright head with a fading tail"),
    "scanner" to ("Scanner" to "A light sweeping back and forth"), "twinkle" to ("Twinkle" to "LEDs fade in and out at random"),
    "fire" to ("Fire" to "A flickering flame"), "breathe" to ("Breathe" to "Slow breaths, one color after another"),
    "random" to ("Random" to "Surprise me"), "gradient" to ("Gradient" to "Blends two colors across the ring"))
private val ANIMATED = setOf("pulse", "blink", "cycle", "wave", "random", "comet", "scanner", "twinkle", "fire", "breathe")
/** Ready-made palettes (inspired by WLED's): tap one to use it. */
val PALETTES = listOf(
    "Ocean" to listOf("#001a66", "#0050ff", "#00c7be", "#80f0ff"), "Lava" to listOf("#200000", "#ff2000", "#ff8000", "#ffd060"),
    "Forest" to listOf("#003300", "#20a040", "#80d000", "#004020"), "Sunset" to listOf("#ff5e3a", "#ff2a68", "#bf5af2", "#5e5ce6"),
    "Party" to listOf("#ff2d55", "#ffcc00", "#34c759", "#3e91ff", "#bf5af2"), "Aurora" to listOf("#00ff88", "#00c7be", "#5e5ce6", "#bf5af2"),
    "Ice" to listOf("#ffffff", "#80d8ff", "#3e91ff", "#0040a0"), "Candy" to listOf("#ff6b9a", "#ffffff", "#bf5af2", "#80d8ff"),
    "Fire" to listOf("#200000", "#ff1800", "#ff6000", "#ffb000", "#fff0a0"))
private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAYN = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

fun hex(c: Color) = "#%06x".format(c.toArgb() and 0xFFFFFF)
fun col(h: String) = runCatching { Color(android.graphics.Color.parseColor(h)) }.getOrDefault(Color(0xFF3E91FF))

@OptIn(ExperimentalLayoutApi::class)
/** [noChange]: put a "No change" (❌) choice first, picked as "" — for schedules that leave the color alone. */
@Composable fun SwatchRow(selected: String, enabled: Boolean, onPick: (String) -> Unit, noChange: Boolean = false, onCustom: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(14.dp), maxItemsInEachRow = 7) {
        if (noChange) {
            val sel = selected.isEmpty()
            Box(Modifier.size(40.dp).clip(CircleShape).background(N.card)
                .border(if (sel) 3.dp else 1.dp, if (sel) N.blue else N.divider, CircleShape)
                .clickable(enabled = enabled) { onPick("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Block, "No change", tint = if (sel) N.blue else N.sub, modifier = Modifier.size(22.dp))
            }
        }
        SWATCHES.forEach { h ->
            val sel = h.equals(selected, true)
            Box(Modifier.size(40.dp).clip(CircleShape).background(col(h))
                .border(if (sel) 3.dp else 1.dp, if (sel) N.blue else N.divider, CircleShape)
                .clickable(enabled = enabled) { onPick(h) }, contentAlignment = Alignment.Center) {
                if (sel) Icon(Icons.Rounded.Check, null, tint = if (h == "#ffffff") Color.Black else Color.White, modifier = Modifier.size(20.dp))
            }
        }
        val custom = selected.isNotEmpty() && SWATCHES.none { it.equals(selected, true) }
        Box(Modifier.size(40.dp).clip(CircleShape)
            .background(if (custom) Brush.linearGradient(listOf(col(selected), col(selected))) else Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
            .border(if (custom) 3.dp else 0.dp, N.blue, CircleShape)
            .clickable(enabled = enabled, onClick = onCustom), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Palette, "Custom color", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

/** Custom color: hue / saturation / brightness, or exact R G B values, or a hex code. */
@Composable fun ColorPickerDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val start = col(initial)
    var r by remember { mutableIntStateOf((start.red * 255).toInt()) }
    var g by remember { mutableIntStateOf((start.green * 255).toInt()) }
    var b by remember { mutableIntStateOf((start.blue * 255).toInt()) }
    val hsv = FloatArray(3).also { android.graphics.Color.RGBToHSV(r, g, b, it) }
    var hueMemo by remember { mutableFloatStateOf(hsv[0]) }       // keeps the hue when saturation/brightness hit 0
    fun setHsv(h: Float, s: Float, v: Float) { hueMemo = h
        val c = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)); r = (c shr 16) and 255; g = (c shr 8) and 255; b = c and 255 }
    val cur = Color(r, g, b); val hexNow = "#%02x%02x%02x".format(r, g, b)
    var hexText by remember(hexNow) { mutableStateOf(hexNow) }
    OneDialog(onDismiss, "Custom color", buttons = listOf(DialogButton("Cancel", onClick = onDismiss), DialogButton("Done", N.blue) { onPick(hexNow) })) {
        Column(Modifier.padding(horizontal = 26.dp)) {
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(cur))
            Spacer(Modifier.height(10.dp))
            val sat = if (hsv[1] == 0f && hsv[2] == 0f) 0f else hsv[1]; val hue = if (hsv[1] == 0f) hueMemo else hsv[0]
            Text("Hue", color = N.sub, fontSize = 13.sp); OneSlider(hue, { setHsv(it, sat.coerceAtLeast(0.01f), hsv[2].coerceAtLeast(0.05f)) }, 0f..360f)
            Text("Saturation", color = N.sub, fontSize = 13.sp); OneSlider(sat, { setHsv(hue, it, hsv[2]) }, 0f..1f)
            Text("Brightness", color = N.sub, fontSize = 13.sp); OneSlider(hsv[2], { setHsv(hue, sat, it) }, 0f..1f)
            Spacer(Modifier.height(6.dp))
            listOf(Triple("R", r, Color(0xFFFF453A)), Triple("G", g, Color(0xFF32D74B)), Triple("B", b, Color(0xFF0A84FF))).forEach { (label, v, tint) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = tint, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                    OneSlider(v.toFloat(), { x -> when (label) { "R" -> r = x.toInt(); "G" -> g = x.toInt(); else -> b = x.toInt() } }, 0f..255f, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    var txt by remember(v) { mutableStateOf("$v") }
                    OneTextField(txt, { s -> txt = s.filter { it.isDigit() }.take(3); txt.toIntOrNull()?.coerceIn(0, 255)?.let { n ->
                        when (label) { "R" -> r = n; "G" -> g = n; else -> b = n } } }, "0", Modifier.width(78.dp), mono = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
            Spacer(Modifier.height(8.dp))
            OneTextField(hexText, { s -> hexText = s.take(7); val h = if (s.startsWith("#")) s else "#$s"
                if (Regex("#[0-9a-fA-F]{6}").matches(h)) { val c = col(h); r = (c.red * 255).toInt(); g = (c.green * 255).toInt(); b = (c.blue * 255).toInt() } },
                "#rrggbb", Modifier.fillMaxWidth(), mono = true)
        }
    }
}

/** Same curve as the server (fusion2.period_ms): 1 = 10 s per cycle … 100 = 0.2 s. */
fun periodMs(speed: Int) = 10000.0 * Math.pow(0.02, (speed.coerceIn(1, 100) - 1) / 99.0)
fun speedLabel(speed: Int): String { val p = periodMs(speed) / 1000
    return if (p >= 1) "%.1f s".format(p) else "%.2f s".format(p) }

private fun JSONArray?.strings() = if (this == null) emptyList() else List(length()) { getString(it) }
private fun lookOf(f: JSONObject?): JSONObject = JSONObject().also { o ->
    listOf("on", "effect", "color", "color2", "brightness", "speed", "rainbow", "palette").forEach { k -> f?.opt(k)?.let { o.put(k, it) } } }

@OptIn(ExperimentalLayoutApi::class)
/** Lighting: the main light (app.fan, the one Presets/Schedules/Home use) or one of the others, picked with the chips on top. */
@Composable fun LightingScreen(app: AppState) {
    LaunchedEffect(Unit) { runCatching { app.api.get("/api/v1/fan") }.onSuccess { if (app.fanInFlight == 0) app.fan = it } }
    LaunchedEffect(Unit) { runCatching { app.api.get("/api/v1/lights") }.onSuccess { app.lights = it } }       // older servers: just the one light
    val zones = app.lights?.optJSONArray("zones").objs()
    val zone = zones.firstOrNull { it.optString("id") == app.lightCur && app.lightCur != "main" }
    val main = zone == null
    val kindOf = { t: String -> app.lights?.optJSONArray("targets").objs().firstOrNull { it.optString("id") == t }?.optString("kind") ?: "argb" }
    val rgbOnly = kindOf((zone ?: zones.firstOrNull())?.optString("target") ?: "") == "rgb"
    val shape = (zone ?: zones.firstOrNull())?.optString("shape")?.ifEmpty { null } ?: "fan"
    val mainName = zones.firstOrNull()?.optString("name")?.ifEmpty { null } ?: "Fan"
    val f = zone ?: app.fan
    val fb = f?.optInt("brightness") ?: 50; val fs = f?.optInt("speed") ?: 50; val fl = f?.optInt("led_count") ?: 12
    var bright by remember(fb) { mutableFloatStateOf(fb.toFloat()) }
    var speed by remember(fs) { mutableFloatStateOf(fs.toFloat()) }
    var leds by remember(fl) { mutableFloatStateOf(fl.toFloat()) }
    var picker by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }      // initial color + where it goes
    var presetMenu by remember { mutableStateOf<JSONObject?>(null) }
    var naming by remember { mutableStateOf<JSONObject?>(null) }      // preset being saved/renamed
    fun set(patch: JSONObject) {
        if (main) { app.changeFan(patch); return }
        val id = zone!!.optString("id")
        val before = app.lights
        app.lights = JSONObject(before.toString()).also { L -> val arr = L.getJSONArray("zones")      // shows the change right away
            for (i in 0 until arr.length()) { val z = arr.getJSONObject(i); if (z.optString("id") == id) patch.keys().forEach { k -> z.put(k, patch.get(k)) } } }
        app.act { try {
            if (patch.has("led_count")) app.lights = app.api.post("/api/v1/lights/$id/setup", JSONObject().put("led_count", patch.getInt("led_count")))
            val look = JSONObject(patch.toString()).apply { remove("led_count") }
            if (look.length() > 0) app.lights = app.api.post("/api/v1/lights/$id", look)
        } catch (e: Exception) { app.lights = before; throw e } }
    }
    val lit = f?.optBoolean("on") ?: true
    val on = lit && app.isAdmin
    val eff = f?.optString("effect") ?: "static"
    val override = f?.optJSONObject("status_override")
    val palette = f?.optJSONArray("palette").strings()
    val rainbow = f?.optBoolean("rainbow", true) ?: true
    val presets = f?.optJSONArray("presets")?.let { a -> List(a.length()) { a.getJSONObject(it) } } ?: emptyList()

    Page("Lighting", app::back) {
        if (zones.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            zones.forEach { z -> val sel = z.optString("id") == (if (main) "main" else app.lightCur)
                Box(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(if (sel) N.blue else N.pill).clickable { app.lightCur = z.optString("id") }.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    Text(z.optString("name"), color = if (sel) Color.White else N.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) } }
            if (app.isAdmin) Row(Modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(N.pill).clickable { app.go(Route.Lights) }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = N.blue, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Your lights", color = N.blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
        if (main && shape == "fan") FanHero(f, app.clockSkew, Modifier.fillMaxWidth().height(250.dp))
        if (!main) Text("Presets, schedules and the status light are for $mainName for now.", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        if (main && override != null) Text("Showing server status right now — your setting comes back when it's resolved.",
            color = N.amber, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        if (!app.isAdmin) Text("View-only access — an admin can change the lighting.", color = N.sub, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        Group { SwitchRow(if (main && shape == "fan" && zones.size < 2) "Fan light" else (f?.optString("name")?.ifEmpty { null } ?: mainName), if (lit) "On" else "Off", lit, enabled = app.isAdmin, subtitleBlue = lit) { set(JSONObject().put("on", it)) } }
        Group {
            SliderRow("Brightness", bright, 0f..100f, "${bright.toInt()}%", on, onChange = { bright = it }) {
                set(JSONObject().put("brightness", bright.toInt())) }
        }
        // ── presets ──
        if (main) {
        SectionLabel("Presets")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            presets.forEach { p ->
                val s = p.optJSONObject("set"); val c = s?.optString("color")?.ifEmpty { null } ?: "#3e91ff"
                val pal = s?.optJSONArray("palette").strings()
                Row(Modifier.height(48.dp).glassCard(RoundedCornerShape(24.dp), 3.dp)
                    .combinedClickable(enabled = app.isAdmin, onLongClick = { presetMenu = p }) {
                        set(lookOf(s).also { it.put("on", s?.optBoolean("on", true) ?: true) }); app.toast("${p.optString("name")} on") }
                    .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(when {
                        s?.optBoolean("rainbow") == true && s.optString("effect") in LightFx.SOFTWARE + "cycle" -> Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
                        pal.size >= 2 -> Brush.sweepGradient(pal.map { col(it) } + col(pal[0]))
                        else -> Brush.linearGradient(listOf(col(c), col(c))) }))
                    Spacer(Modifier.width(8.dp)); Text(p.optString("name"), color = N.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            if (app.isAdmin) Row(Modifier.height(48.dp).glassCard(RoundedCornerShape(24.dp), 3.dp)
                .clickable { naming = JSONObject().put("name", "").put("new", true) }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Add, null, tint = N.blue, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                Text("Save current", color = N.blue, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (presets.isEmpty()) Text("Save the look you have now to switch back to it in one tap — or to use it in a schedule. Hold a preset to change it.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        }
        if (rgbOnly) Text("A 12 V RGB header lights every LED in the same color, so the effects that move along the LEDs aren't offered.", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        // ── effect ──
        SectionLabel("Effect")
        Group {
            val rgbFx = app.lights?.optJSONArray("rgb_effects").strings()
            EFFECTS.filter { !rgbOnly || it.first in rgbFx }.forEachIndexed { i, (key, v) ->
                if (i > 0) RowDivider()
                Row1(v.first, v.second, eff == key, enabled = on, onClick = { set(JSONObject().put("effect", key)) }) { OneRadio(eff == key, on) }
            }
        }
        // ── colors (depend on the effect) ──
        SectionLabel("Color")
        Group {
            val software = eff in LightFx.SOFTWARE || eff == "cycle"
            val palDefault = { JSONArray(palette.takeIf { it.size >= 2 } ?: listOf(f?.optString("color")?.ifEmpty { null } ?: "#3e91ff", f?.optString("color2")?.ifEmpty { null } ?: "#bf5af2")) }
            if (!software && eff in LightFx.PALETTE_FX) {
                // Static, pulse, flash, gradient: your color(s), or a palette (Nova then draws the effect itself)
                val pmode = if (palette.size >= 2) 1 else 0
                Segmented(listOf(if (eff == "gradient") "Two colors" else "One color", "Palette"), pmode) { m ->
                    set(if (m == 0) JSONObject().put("palette", JSONArray()) else JSONObject().put("rainbow", false).put("palette", palDefault()))
                }
                if (pmode == 1) PaletteEditor(palette, on, onChange = { set(JSONObject().put("palette", JSONArray(it)).put("rainbow", false)) },
                    onEdit = { i, cur, put -> picker = cur to put })
                else {
                    SwatchRow(f?.optString("color") ?: "", on, { set(JSONObject().put("color", it)) }) { picker = (f?.optString("color") ?: "#3e91ff") to { c -> set(JSONObject().put("color", c)) } }
                    if (eff == "gradient") {
                        RowDivider()
                        Text("Blend into", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 22.dp, top = 12.dp))
                        SwatchRow(f?.optString("color2") ?: "", on, { set(JSONObject().put("color2", it)) }) { picker = (f?.optString("color2") ?: "#bf5af2") to { c -> set(JSONObject().put("color2", c)) } }
                    }
                }
            } else if (software) {
                val mode = if (palette.size >= 2 && !(eff != "fire" && rainbow)) 2 else if (rainbow) 0 else 1
                Segmented(if (eff == "fire") listOf("Flame", "One color", "Palette") else listOf("Rainbow", "One color", "Palette"), mode) { m ->
                    when (m) {
                        0 -> set(if (eff == "fire") JSONObject().put("palette", JSONArray()).put("rainbow", true) else JSONObject().put("rainbow", true))
                        1 -> set(JSONObject().put("rainbow", false).put("palette", JSONArray()))
                        else -> set(JSONObject().put("rainbow", false).put("palette", JSONArray(palette.takeIf { it.size >= 2 }
                            ?: listOf(f?.optString("color")?.ifEmpty { null } ?: "#3e91ff", f?.optString("color2")?.ifEmpty { null } ?: "#bf5af2"))))
                    }
                }
                if (mode == 2) PaletteEditor(palette, on, onChange = { set(JSONObject().put("palette", JSONArray(it)).put("rainbow", false)) },
                    onEdit = { i, cur, put -> picker = cur to put })
                if (mode == 1)
                    SwatchRow(f?.optString("color") ?: "", on, { set(JSONObject().put("color", it)) }) { picker = (f?.optString("color") ?: "#3e91ff") to { c -> set(JSONObject().put("color", c)) } }
                if (mode == 0 && eff == "fire") Text("A warm flame (dark red → orange → yellow). Pick One color for a flame in your color, or Palette for your own.", color = N.sub, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp))
            } else {
                SwatchRow(f?.optString("color") ?: "", on, { set(JSONObject().put("color", it)) }) { picker = (f?.optString("color") ?: "#3e91ff") to { c -> set(JSONObject().put("color", c)) } }
                if (eff == "gradient") {
                    RowDivider()
                    Text("Blend into", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 22.dp, top = 12.dp))
                    SwatchRow(f?.optString("color2") ?: "", on, { set(JSONObject().put("color2", it)) }) { picker = (f?.optString("color2") ?: "#bf5af2") to { c -> set(JSONObject().put("color2", c)) } }
                }
                if (eff == "cycle") { RowDivider()
                    SwitchRow("Rainbow", if (rainbow) "Uses every color" else "Uses your color only", rainbow, on) { set(JSONObject().put("rainbow", it)) } }
            }
        }
        if (eff in ANIMATED) Group {
            SliderRow("Speed", speed, 1f..100f, "${speedLabel(speed.toInt())} per cycle", on, onChange = { speed = it }) {
                set(JSONObject().put("speed", speed.toInt())) }
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 12.dp)) {
                Text("Slower", color = N.sub, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("Faster", color = N.sub, fontSize = 13.sp)
            }
        }
        if (!rgbOnly && (eff == "gradient" || LightFx.isSoftware(eff, palette, rainbow))) Group {
            SliderRow(when (shape) { "strip" -> "LEDs on the strip"; "fan" -> "LEDs on the fan"; else -> "LEDs" }, leds, 4f..(if (shape == "strip") 120f else 40f), "${leds.toInt()}", on, onChange = { leds = it }) {
                set(JSONObject().put("led_count", leds.toInt())) }
            Text(if (shape == "fan") "Match this to your fan so the effect fits the ring exactly (most 120 mm fans have 8–18)." else "Count the LEDs (or check the box) so effects fit the whole length.",
                color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 16.dp))
            if (shape == "fan") { RowDivider()
            SwitchRow("Picture spins the other way", "If the effect in the app goes round the opposite way to your fan", AppPrefs.fanReverse) {
                AppPrefs.set("fan_reverse", it) } }
        }
        if (main) SectionLabel("Automation")
        if (main) Group {
            val sl = f?.optBoolean("status_light") ?: false
            SwitchRow("Status light", "Turns amber for warnings and pulses red for critical alerts, then goes back to your color",
                sl) { set(JSONObject().put("status_light", it)) }
            RowDivider()
            val n = f?.optJSONArray("schedules")?.length() ?: 0
            Row1("Schedules", if (f?.optBoolean("schedules_paused") == true) "Paused" else if (n == 0) "Wake up gently, dim at sunset, off while you sleep…" else "$n schedule${if (n > 1) "s" else ""}",
                n > 0, onClick = { app.go(Route.Schedules) })
        }
        LinksCard(listOf("Your lights" to { app.go(Route.Lights) }, "Notifications" to { app.go(Route.NotifySettings) }, "Storage & hardware" to { app.go(Route.Hardware) }))
    }
    picker?.let { (initial, put) -> ColorPickerDialog(initial, { picker = null }) { picker = null; put(it) } }
    presetMenu?.let { p ->
        OneDialog({ presetMenu = null }, p.optString("name"), buttons = listOf(DialogButton("Close") { presetMenu = null })) {
            DialogChoice("Update with the current look", null, false) { presetMenu = null
                savePresets(app, presets.map { if (it.optString("id") == p.optString("id")) JSONObject(it.toString()).put("set", lookOf(f)) else it }); app.toast("Updated") }
            DialogChoice("Rename", null, false) { presetMenu = null; naming = JSONObject(p.toString()) }
            DialogChoice("Delete", null, false) { presetMenu = null; savePresets(app, presets.filter { it.optString("id") != p.optString("id") }) }
        }
    }
    naming?.let { p ->
        var name by remember(p) { mutableStateOf(p.optString("name")) }
        OneDialog({ naming = null }, if (p.optBoolean("new")) "Save as a preset" else "Rename preset",
            buttons = listOf(DialogButton("Cancel") { naming = null }, DialogButton("Save", N.blue, enabled = name.isNotBlank()) {
                naming = null
                if (p.optBoolean("new")) savePresets(app, presets + JSONObject().put("name", name.trim()).put("set", lookOf(f)))
                else savePresets(app, presets.map { if (it.optString("id") == p.optString("id")) JSONObject(it.toString()).put("name", name.trim()) else it })
            })) {
            OneTextField(name, { name = it.take(30) }, "e.g. Movie night", Modifier.fillMaxWidth().padding(horizontal = 22.dp))
        }
    }
}

private fun savePresets(app: AppState, list: List<JSONObject>) = app.changeFan(JSONObject().put("presets", JSONArray(list)))

/** A palette: up to 8 colors you can tap to change, plus ready-made ones. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PaletteEditor(palette: List<String>, enabled: Boolean, onChange: (List<String>) -> Unit, onEdit: (Int, String, (String) -> Unit) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            palette.forEachIndexed { i, h ->
                Box(Modifier.size(44.dp).clip(CircleShape).background(col(h)).border(1.dp, N.divider, CircleShape)
                    .combinedClickable(enabled = enabled, onLongClick = { if (palette.size > 2) onChange(palette.filterIndexed { j, _ -> j != i }) }) {
                        onEdit(i, h) { c -> onChange(palette.mapIndexed { j, x -> if (j == i) c else x }) } })
            }
            if (palette.size < 8) Box(Modifier.size(44.dp).clip(CircleShape).border(1.5.dp, N.sub, CircleShape)
                .clickable(enabled = enabled) { onChange(palette + palette.last()) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, "Add a color", tint = N.sub) }
        }
        Text("Tap a color to change it, hold to remove it.", color = N.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PALETTES.forEach { (name, cols) ->
                Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled) { onChange(cols) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(width = 64.dp, height = 22.dp).clip(RoundedCornerShape(11.dp)).background(Brush.horizontalGradient(cols.map { col(it) })))
                    Text(name, color = N.sub, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════ schedules ═══════════════════════════════════════

fun describe(set: JSONObject?): String {
    if (set == null) return ""
    if (set.has("on") && !set.optBoolean("on")) return "Turn off"
    val parts = mutableListOf<String>()
    if (set.optBoolean("on", false)) parts += "Turn on"
    if (set.has("brightness")) parts += if (parts.isEmpty()) "Brightness ${set.optInt("brightness")}%" else "${set.optInt("brightness")}%"
    if (set.has("effect")) parts += (EFFECTS.firstOrNull { it.first == set.optString("effect") }?.second?.first ?: set.optString("effect"))
    if (set.has("color") || set.has("palette")) parts += "color"
    return parts.joinToString(", ").ifEmpty { "No change" }
}
private fun whenText(trig: String, time: String, offset: Int): String {
    fun off(m: Int) = if (m == 0) "" else " ${if (m > 0) "+" else "−"}${kotlin.math.abs(m)} min"
    return when (trig) { "sunrise" -> "Sunrise" + off(offset); "sunset" -> "Sunset" + off(offset); else -> time }
}
private fun actionText(s: JSONObject, presets: List<JSONObject>): String =
    s.optString("preset").takeIf { it.isNotEmpty() }?.let { id -> presets.firstOrNull { it.optString("id") == id }?.optString("name")?.let { "Preset “$it”" } ?: "A deleted preset" }
        ?: describe(s.optJSONObject("set"))

@Composable fun SchedulesScreen(app: AppState) {
    val f = app.fan
    val sch = f?.optJSONArray("schedules") ?: JSONArray()
    val presets = f?.optJSONArray("presets")?.let { a -> List(a.length()) { a.getJSONObject(it) } } ?: emptyList()
    val paused = f?.optBoolean("schedules_paused") == true
    val sun = f?.optJSONObject("sun"); val starts = f?.optJSONObject("starts_today")
    var menu by remember { mutableStateOf<Int?>(null) }
    var locating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { app.api.get("/api/v1/fan") }.onSuccess { if (app.fanInFlight == 0) app.fan = it } }
    fun putAll(all: JSONArray) = app.changeFan(JSONObject().put("schedules", all))
    Page("Schedules", app::back, listOf(TopAction(Icons.Rounded.Add, "Add") { app.go(Route.EditSchedule(-1)) })) {
        Group {
            SwitchRow("Pause all schedules", if (paused) "Nothing runs until you turn this off" else "Schedules run as set", paused, app.isAdmin) {
                app.changeFan(JSONObject().put("schedules_paused", it)) }
            RowDivider()
            Row1("Location for sunrise & sunset", locationText(f?.optJSONObject("location")) +
                (sun?.let { "\nToday: sunrise ${it.optString("sunrise")} · sunset ${it.optString("sunset")}" } ?: ""),
                sun != null, Icons.Rounded.WbTwilight, N.amber, onClick = { if (app.isAdmin) locating = true })
        }
        if (sch.length() == 0) {
            Text("No schedules yet. Some ideas: wake up to a slow sunrise, dim to a warm glow at sunset, turn off while you sleep and back on in the morning.",
                color = N.sub, fontSize = 15.sp, modifier = Modifier.padding(30.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PrimaryButton("Add schedule", Modifier.padding(20.dp)) { app.go(Route.EditSchedule(-1)) } }
        } else Group {
            for (i in 0 until sch.length()) {
                val s = sch.getJSONObject(i); if (i > 0) RowDivider()
                val days = s.optJSONArray("days")?.let { a -> List(a.length()) { a.getInt(it) } } ?: (0..6).toList()
                val trig = s.optString("trigger", "time"); val u = s.optJSONObject("until")
                val title = whenText(trig, s.optString("time"), s.optInt("offset")) +
                    (if (trig != "time") starts?.optString(s.optString("id"))?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "" else "") +
                    (u?.let { " – " + whenText(it.optString("trigger", "time"), it.optString("time"), it.optInt("offset")) } ?: "") +
                    s.optString("name").takeIf { it.isNotEmpty() }?.let { "  ·  $it" }.orEmpty()
                val sub = listOfNotNull(actionText(s, presets), s.optInt("fade").takeIf { it > 0 }?.let { "fades over $it min" }, u?.let { "then back" },
                    if (days.size == 7) "every day" else if (days == listOf(0, 1, 2, 3, 4)) "weekdays" else if (days == listOf(5, 6)) "weekends" else days.joinToString(" ") { DAYN[it] },
                    if (s.optBoolean("if_on")) "only while on" else null, if (s.optBoolean("skip_next")) "skipping next time" else null).joinToString(" · ")
                Row1(title, sub, s.optBoolean("enabled", true) && !paused, onClick = { menu = i }) {
                    OneSwitch(s.optBoolean("enabled", true), { en -> val all = JSONArray(sch.toString()); all.getJSONObject(i).put("enabled", en); putAll(all) }, app.isAdmin)
                }
            }
        }
        Text("Fades change the light gradually, a step each minute. With an end time, the light goes back to how it was when the schedule started.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
    }
    menu?.let { i ->
        val s = sch.optJSONObject(i) ?: return@let
        OneDialog({ menu = null }, whenText(s.optString("trigger", "time"), s.optString("time"), s.optInt("offset")), actionText(s, presets),
            buttons = listOf(DialogButton("Close") { menu = null })) {
            DialogChoice("Edit", null, false) { menu = null; app.go(Route.EditSchedule(i)) }
            DialogChoice(if (s.optBoolean("skip_next")) "Don't skip next time" else "Skip next time", "Runs again after that", false) { menu = null
                val all = JSONArray(sch.toString()); all.getJSONObject(i).put("skip_next", !s.optBoolean("skip_next")); putAll(all) }
            DialogChoice("Run it now", "Apply what it does right away", false) { menu = null
                val set = s.optString("preset").takeIf { it.isNotEmpty() }?.let { id -> presets.firstOrNull { it.optString("id") == id }?.optJSONObject("set") } ?: s.optJSONObject("set")
                set?.let { app.changeFan(JSONObject(it.toString())) } }
            DialogChoice("Duplicate", null, false) { menu = null
                val all = JSONArray(sch.toString()); all.put(JSONObject(s.toString()).also { it.remove("id") }); putAll(all) }
            DialogChoice("Delete", null, false) { menu = null; val all = JSONArray(sch.toString()); all.remove(i); putAll(all) }
        }
    }
    if (locating) ServerLocationDialog(app, f?.optJSONObject("location")) { locating = false }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun EditScheduleScreen(app: AppState, index: Int) {
    val f = app.fan
    val existing = f?.optJSONArray("schedules")?.optJSONObject(index)
    val presets = f?.optJSONArray("presets")?.let { a -> List(a.length()) { a.getJSONObject(it) } } ?: emptyList()
    val set0 = existing?.optJSONObject("set")
    var name by remember { mutableStateOf(existing?.optString("name") ?: "") }
    var trig by remember { mutableStateOf(existing?.optString("trigger")?.ifEmpty { null } ?: "time") }
    var hour by remember { mutableIntStateOf(existing?.optString("time")?.take(2)?.toIntOrNull() ?: 22) }
    var minute by remember { mutableIntStateOf(existing?.optString("time")?.takeLast(2)?.toIntOrNull() ?: 0) }
    var offset by remember { mutableFloatStateOf((existing?.optInt("offset") ?: 0).toFloat()) }
    var days by remember { mutableStateOf(existing?.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: (0..6).toSet()) }
    var action by remember { mutableIntStateOf(when { existing?.optString("preset")?.isNotEmpty() == true -> 2; set0?.has("on") == true && !set0.optBoolean("on") -> 0; else -> 1 }) }
    var preset by remember { mutableStateOf(existing?.optString("preset")?.ifEmpty { null } ?: presets.firstOrNull()?.optString("id")) }
    var bright by remember { mutableFloatStateOf((set0?.optInt("brightness", 30) ?: 30).toFloat()) }
    var changeBright by remember { mutableStateOf(set0 == null || set0.has("brightness")) }
    var turnOn by remember { mutableStateOf(existing == null || !existing.optBoolean("if_on")) }
    var setColor by remember { mutableStateOf(set0?.optString("color")?.ifEmpty { null }) }
    var effect by remember { mutableStateOf(set0?.optString("effect")?.ifEmpty { null }) }
    var fade by remember { mutableIntStateOf(existing?.optInt("fade") ?: 0) }
    val u0 = existing?.optJSONObject("until")
    var hasEnd by remember { mutableStateOf(u0 != null) }
    var endTrig by remember { mutableStateOf(u0?.optString("trigger")?.ifEmpty { null } ?: "time") }
    var endHour by remember { mutableIntStateOf(u0?.optString("time")?.take(2)?.toIntOrNull() ?: 7) }
    var endMinute by remember { mutableIntStateOf(u0?.optString("time")?.takeLast(2)?.toIntOrNull() ?: 0) }
    var endOffset by remember { mutableFloatStateOf((u0?.optInt("offset") ?: 0).toFloat()) }
    var picker by remember { mutableStateOf(false) }
    val sunKnown = f?.optJSONObject("sun") != null

    fun save() {
        val all = JSONArray((f?.optJSONArray("schedules") ?: JSONArray()).toString())
        val set = JSONObject()
        when (action) {
            0 -> set.put("on", false)
            1 -> { if (turnOn) set.put("on", true); if (changeBright) set.put("brightness", bright.toInt())
                   setColor?.let { set.put("color", it) }; effect?.let { set.put("effect", it) } }
        }
        val s = JSONObject().put("time", "%02d:%02d".format(hour, minute)).put("days", JSONArray(days.sorted())).put("enabled", true)
            .put("name", name.trim()).put("trigger", trig).put("offset", offset.toInt()).put("fade", fade).put("set", set)
            .put("preset", if (action == 2) preset ?: "" else "").put("if_on", action == 1 && !turnOn)
        if (hasEnd) s.put("until", JSONObject().put("trigger", endTrig).put("time", "%02d:%02d".format(endHour, endMinute)).put("offset", endOffset.toInt()))
        if (index >= 0) { s.put("id", existing?.optString("id")); s.put("skip_next", existing?.optBoolean("skip_next") ?: false); all.put(index, s) } else all.put(s)
        app.changeFan(JSONObject().put("schedules", all)); app.back(); app.toast("Saved")
    }
    @Composable fun TriggerPicker(t: String, onT: (String) -> Unit, h: Int, m: Int, onHm: (Int, Int) -> Unit, off: Float, onOff: (Float) -> Unit) {
        Segmented(listOf("Time", "Sunrise", "Sunset"), listOf("time", "sunrise", "sunset").indexOf(t)) { onT(listOf("time", "sunrise", "sunset")[it]) }
        if (t == "time") Box(Modifier.padding(vertical = 8.dp)) { TimeWheels(h, m) { a, b -> onHm(a, b) } }
        else Group {
            SliderRow("Offset", off, -120f..120f, when { off.toInt() == 0 -> "At ${t}"; off < 0 -> "${-off.toInt()} min before"; else -> "${off.toInt()} min after" },
                steps = 47, onChange = onOff) {}
            if (!sunKnown) Text("Set the server's location first (Schedules → Location).", color = N.amber, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, bottom = 12.dp))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Page(if (index >= 0) "Edit schedule" else "New schedule", app::back, bottom = 120.dp) {
            SectionLabel("Starts")
            TriggerPicker(trig, { trig = it }, hour, minute, { a, b -> hour = a; minute = b }, offset) { offset = (Math.round(it / 5) * 5).toFloat() }
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                DAYS.forEachIndexed { i, d ->
                    val sel = i in days
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (sel) N.blue else N.card)
                        .clickable { days = if (sel) days - i else days + i }, contentAlignment = Alignment.Center) {
                        Text(d, color = if (sel) Color.White else N.sub, fontWeight = FontWeight.Bold)
                    }
                }
            }
            SectionLabel("Does")
            Segmented(listOf("Turn off", "Set the light", "A preset"), action) { action = it }
            when (action) {
                1 -> {
                    Group {
                        SwitchRow("Change the brightness", if (changeBright) "To ${bright.toInt()}%" else "No change", changeBright) { changeBright = it }
                        if (changeBright) SliderRow("Brightness", bright, 0f..100f, "${bright.toInt()}%", onChange = { bright = it }) {}
                    }
                    SectionLabel("Color")
                    Group { SwatchRow(setColor ?: "", true, { setColor = if (it.isEmpty() || setColor == it) null else it }, noChange = true) { picker = true } }
                    SectionLabel("Effect")
                    FlowRow(Modifier.padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (listOf(null to "No change") + EFFECTS.map { it.first to it.second.first }).forEach { (k, l) ->
                            val sel = effect == k
                            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(if (sel) N.blue else N.card).clickable { effect = k }
                                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (k == null) { Icon(Icons.Rounded.Block, null, tint = if (sel) Color.White else N.sub, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
                                Text(l, color = if (sel) Color.White else N.text, fontSize = 14.sp)
                            }
                        }
                    }
                    Group { SwitchRow("Turn the light on if it's off", if (turnOn) "Always runs" else "Only runs while the light is on — handy for dimming", turnOn) { turnOn = it } }
                }
                2 -> Group {
                    if (presets.isEmpty()) Text("No presets yet — save one on the Lighting page first.", color = N.sub, modifier = Modifier.padding(22.dp))
                    presets.forEachIndexed { i, p -> if (i > 0) RowDivider()
                        Row1(p.optString("name"), describe(p.optJSONObject("set")), onClick = { preset = p.optString("id") }) { OneRadio(preset == p.optString("id")) } }
                }
            }
            SectionLabel("Fade")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 10, 15, 30, 45, 60, 90, 120).forEach { m ->
                    val sel = fade == m
                    Text(if (m == 0) "Instant" else "$m min", color = if (sel) Color.White else N.text, fontSize = 14.sp, modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (sel) N.blue else N.card).clickable { fade = m }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Text(if (fade == 0) "Changes straight away." else "Glides there over $fade minutes — a slow sunrise or a gentle fade to sleep.",
                color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
            SectionLabel("Ends")
            Group { SwitchRow("Put the light back afterwards", if (hasEnd) "At the end time it returns to how it was" else "Stays like this", hasEnd) { hasEnd = it } }
            if (hasEnd) TriggerPicker(endTrig, { endTrig = it }, endHour, endMinute, { a, b -> endHour = a; endMinute = b }, endOffset) { endOffset = (Math.round(it / 5) * 5).toFloat() }
            SectionLabel("Name (optional)")
            Group { Box(Modifier.padding(16.dp)) { OneTextField(name, { name = it.take(30) }, "e.g. Wake up", Modifier.fillMaxWidth()) } }
            if (index >= 0) Group { Row1("Delete schedule", null, icon = Icons.Rounded.Delete, iconTint = N.red, onClick = {
                val all = JSONArray((f?.optJSONArray("schedules") ?: JSONArray()).toString()); all.remove(index)
                app.changeFan(JSONObject().put("schedules", all)); app.back(); app.toast("Deleted") }) }
        }
        val changes = action != 1 || turnOn || changeBright || setColor != null || effect != null
        CancelSavePill(app::back, ::save, days.isNotEmpty() && (action != 2 || preset != null) && changes, modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (picker) ColorPickerDialog(setColor ?: "#3e91ff", { picker = false }) { setColor = it; picker = false }
}

// ═══════════════════════════════ your lights ═════════════════════════════════════

private val SHAPE_NAMES = mapOf("fan" to "Fan", "strip" to "LED strip", "other" to "Something else")
private val SHAPE_CHOICES = listOf("fan" to "A fan (a ring of LEDs)", "strip" to "An LED strip", "other" to "Something else")

/** Lighting → Your lights: what's plugged in where, each with its own look. */
@Composable fun LightsScreen(app: AppState) {
    LaunchedEffect(Unit) { runCatching { app.api.get("/api/v1/lights") }.onSuccess { app.lights = it } }
    val L = app.lights
    val zones = L?.optJSONArray("zones").objs(); val targets = L?.optJSONArray("targets").objs()
    fun tgt(id: String) = targets.firstOrNull { it.optString("id") == id }
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    var step by remember { mutableStateOf<String?>(null) }           // adding: "where" → "what" → "name"
    var draft by remember { mutableStateOf(JSONObject()) }
    var edit by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }   // field being changed + the light
    fun post(path: String, body: JSONObject, ok: String? = null) = app.act(ok) { app.lights = app.api.post(path, body) }
    Page("Your lights", app::back) {
        Text("Each light plugged into the server, with its own effect and color. Add one for every fan, strip or header you light up.",
            color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        if (L == null) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp))
        else Group {
            zones.forEachIndexed { i, z -> if (i > 0) RowDivider()
                val t = tgt(z.optString("target"))
                Row1(z.optString("name"), listOfNotNull(SHAPE_NAMES[z.optString("shape")] ?: "Light", t?.optString("label"),
                        if (t?.optBoolean("tested") == false) "untested" else null, if (t?.optString("kind") != "rgb") "${z.optInt("led_count")} LEDs" else null, if (i == 0) "main" else null).joinToString(" · "),
                    icon = when (z.optString("shape")) { "strip" -> Icons.Rounded.LinearScale; "fan" -> Icons.Rounded.Toys; else -> Icons.Rounded.Lightbulb },
                    onClick = { if (app.isAdmin) menu = z else { app.lightCur = z.optString("id"); app.go(Route.Lighting) } })
            }
            if (app.isAdmin && targets.any { it.isNull("used_by") || it.optString("used_by").isEmpty() }) { RowDivider()
                Row1("Add a light", null, false, Icons.Rounded.Add, onClick = { draft = JSONObject(); step = "where" }) }
        }
        Text("“Untested” headers follow the controller's documented protocol but haven't been tried on real hardware yet — if one doesn't light up, tell us which board you have. The main light is the one Presets, Schedules, the status light and the Home picture use.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
    }
    // adding a light, step by step
    when (step) {
        "where" -> OneDialog({ step = null }, "Where is it plugged in?", buttons = listOf(DialogButton("Cancel") { step = null })) {
            targets.filter { it.isNull("used_by") || it.optString("used_by").isEmpty() }.forEach { t ->
                DialogChoice(t.optString("label"), listOfNotNull(if (!t.optBoolean("tested")) "Untested" else null, if (t.optString("kind") == "rgb") "One color at a time" else null).joinToString(" · ").ifEmpty { null }, false) {
                    draft = JSONObject(draft.toString()).put("target", t.optString("id")); step = "what" } } }
        "what" -> OneDialog({ step = null }, "What is it?", buttons = listOf(DialogButton("Cancel") { step = null })) {
            SHAPE_CHOICES.forEach { (k, l) -> DialogChoice(l, null, false) { draft = JSONObject(draft.toString()).put("shape", k)
                .put("name", when (k) { "fan" -> "Fan ${zones.size + 1}"; "strip" -> "LED strip"; else -> "Light" }).put("led_count", if (k == "strip") 30 else 12); step = "name" } } }
        "name" -> { var name by remember { mutableStateOf(draft.optString("name")) }; var n by remember { mutableStateOf(draft.optInt("led_count").toString()) }
            val rgb = tgt(draft.optString("target"))?.optString("kind") == "rgb"
            OneDialog({ step = null }, "Name it", buttons = listOf(DialogButton("Cancel") { step = null }, DialogButton("Add", N.blue, enabled = name.isNotBlank()) {
                step = null; post("/api/v1/lights", JSONObject(draft.toString()).put("name", name.trim()).put("led_count", (n.toIntOrNull() ?: 12).coerceIn(1, 120)), "${name.trim()} added") })) {
                Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OneTextField(name, { name = it.take(30) }, "e.g. Case strip, Rear fan", Modifier.fillMaxWidth())
                    if (!rgb) OneTextField(n, { n = it.filter { c -> c.isDigit() }.take(3) }, "How many LEDs", Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
            } }
    }
    menu?.let { z -> val id = z.optString("id"); val rgb = tgt(z.optString("target"))?.optString("kind") == "rgb"
        OneDialog({ menu = null }, z.optString("name"), buttons = listOf(DialogButton("Close") { menu = null })) {
            DialogChoice("Change its look", null, false) { menu = null; app.lightCur = id; app.go(Route.Lighting) }
            DialogChoice("Rename", null, false) { menu = null; edit = "name" to z }
            DialogChoice("Where it's plugged in", tgt(z.optString("target"))?.optString("label"), false) { menu = null; edit = "target" to z }
            DialogChoice("What it is", SHAPE_NAMES[z.optString("shape")], false) { menu = null; edit = "shape" to z }
            if (!rgb) DialogChoice("Number of LEDs", "${z.optInt("led_count")}", false) { menu = null; edit = "leds" to z }
            if (id != "main") DialogChoice("Remove", null, false) { menu = null; edit = "rm" to z }
        } }
    edit?.let { (k, z) -> val id = z.optString("id"); val path = "/api/v1/lights/$id/setup"
        when (k) {
            "name", "leds" -> { var v by remember(k, id) { mutableStateOf(if (k == "name") z.optString("name") else z.optInt("led_count").toString()) }
                OneDialog({ edit = null }, if (k == "name") "Rename" else "How many LEDs?", buttons = listOf(DialogButton("Cancel") { edit = null }, DialogButton("Save", N.blue, enabled = v.isNotBlank()) {
                    edit = null; post(path, if (k == "name") JSONObject().put("name", v.trim()) else JSONObject().put("led_count", (v.toIntOrNull() ?: 12).coerceIn(1, 120))) })) {
                    OneTextField(v, { v = if (k == "name") it.take(30) else it.filter { c -> c.isDigit() }.take(3) }, if (k == "name") "Name" else "1–120", Modifier.fillMaxWidth().padding(horizontal = 22.dp)) } }
            "target" -> OneDialog({ edit = null }, "Where is it plugged in?", buttons = listOf(DialogButton("Cancel") { edit = null })) {
                targets.filter { it.optString("used_by").isEmpty() || it.isNull("used_by") || it.optString("id") == z.optString("target") }.forEach { t ->
                    DialogChoice(t.optString("label"), if (!t.optBoolean("tested")) "Untested" else null, t.optString("id") == z.optString("target")) {
                        edit = null; if (t.optString("id") != z.optString("target")) post(path, JSONObject().put("target", t.optString("id"))) } } }
            "shape" -> OneDialog({ edit = null }, "What is it?", buttons = listOf(DialogButton("Cancel") { edit = null })) {
                SHAPE_CHOICES.forEach { (s, l) -> DialogChoice(l, null, s == z.optString("shape")) { edit = null; post(path, JSONObject().put("shape", s)) } } }
            else -> OneDialog({ edit = null }, "Remove ${z.optString("name")}?", "It's switched off and leaves the list. Nothing on the server changes otherwise.",
                listOf(DialogButton("Cancel") { edit = null }, DialogButton("Remove", N.red) { edit = null; if (app.lightCur == id) app.lightCur = "main"
                    app.act { app.lights = app.api.delete("/api/v1/lights/$id") } }))
        } }
}
