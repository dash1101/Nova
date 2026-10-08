package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

private val SWATCHES = listOf("#ffffff", "#ff3b30", "#ff9500", "#ffcc00", "#34c759", "#00c7be",
    "#005aff", "#3e91ff", "#5e5ce6", "#bf5af2", "#ff2d55", "#ff6b9a")
private val EFFECTS = listOf(
    "static" to ("Static" to "One steady colour"), "pulse" to ("Pulse" to "Breathes in and out"),
    "blink" to ("Blink" to "Flashes on and off"), "cycle" to ("Colour cycle" to "Fades through colours"),
    "wave" to ("Wave" to "Colour chases around the ring"), "random" to ("Random" to "Surprise me"),
    "gradient" to ("Gradient" to "Blends two colours across the ring"))
private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")

fun hex(c: Color) = "#%06x".format(c.toArgb() and 0xFFFFFF)
fun col(h: String) = Color(android.graphics.Color.parseColor(h))

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SwatchRow(selected: String, enabled: Boolean, onPick: (String) -> Unit, onCustom: () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(14.dp), maxItemsInEachRow = 7) {
        SWATCHES.forEach { h ->
            val sel = h.equals(selected, true)
            Box(Modifier.size(40.dp).clip(CircleShape).background(col(h))
                .border(if (sel) 3.dp else 1.dp, if (sel) N.blue else N.divider, CircleShape)
                .clickable(enabled = enabled) { onPick(h) }, contentAlignment = Alignment.Center) {
                if (sel) Icon(Icons.Rounded.Check, null, tint = if (h == "#ffffff") Color.Black else Color.White, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.size(40.dp).clip(CircleShape)
            .background(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
            .clickable(enabled = enabled, onClick = onCustom), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Palette, "Custom", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable fun ColorPickerDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(col(initial).toArgb(), it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }; var s by remember { mutableFloatStateOf(hsv[1]) }
    val c = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, 1f)))
    OneDialog(onDismiss, "Custom colour", buttons = listOf(DialogButton("Cancel", onClick = onDismiss), DialogButton("Done", N.blue) { onPick(hex(c)) })) {
        Column(Modifier.padding(horizontal = 26.dp)) {
            Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(c))
            Spacer(Modifier.height(14.dp)); Text("Hue", color = N.sub)
            OneSlider(h, { h = it }, 0f..360f)
            Text("Saturation", color = N.sub)
            OneSlider(s, { s = it }, 0f..1f)
        }
    }
}

/** Same curve as the server (fusion2.period_ms): 1 = 10 s per cycle … 100 = 0.2 s. */
fun periodMs(speed: Int) = 10000.0 * Math.pow(0.02, (speed.coerceIn(1, 100) - 1) / 99.0)
fun speedLabel(speed: Int): String { val p = periodMs(speed) / 1000
    return if (p >= 1) "%.1f s".format(p) else "%.2f s".format(p) }

@Composable fun LightingScreen(app: AppState) {
    LaunchedEffect(Unit) { runCatching { app.api.get("/api/v1/fan") }.onSuccess { if (app.fanInFlight == 0) app.fan = it } }
    val f = app.fan
    // Sliders hold their own value while dragging and re-sync whenever the server value changes.
    val fb = f?.optInt("brightness") ?: 50; val fs = f?.optInt("speed") ?: 50; val fl = f?.optInt("led_count") ?: 12
    var bright by remember(fb) { mutableFloatStateOf(fb.toFloat()) }
    var speed by remember(fs) { mutableFloatStateOf(fs.toFloat()) }
    var leds by remember(fl) { mutableFloatStateOf(fl.toFloat()) }
    var picker by remember { mutableStateOf<String?>(null) }   // "color" | "color2"
    fun set(patch: JSONObject) = app.changeFan(patch)
    val lit = f?.optBoolean("on") ?: true
    val on = lit && app.isAdmin            // everything below is read-only for view-only phones
    val eff = f?.optString("effect") ?: "static"
    val override = f?.optJSONObject("status_override")

    Page("Lighting", app::back) {
        FanHero(f, Modifier.fillMaxWidth().height(250.dp))
        if (override != null) Text("Showing server status right now — your setting comes back when it's resolved.",
            color = N.amber, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        if (!app.isAdmin) Text("View-only access — an admin can change the lighting.", color = N.sub, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        Group { SwitchRow("Fan light", if (lit) "On" else "Off", lit, enabled = app.isAdmin, subtitleBlue = lit) { set(JSONObject().put("on", it)) } }
        Group {
            SliderRow("Brightness", bright, 0f..100f, "${bright.toInt()}%", on, onChange = { bright = it }) {
                set(JSONObject().put("brightness", bright.toInt())) }
        }
        SectionLabel("Colour")
        Group {
            SwatchRow(f?.optString("color") ?: "", on, { set(JSONObject().put("color", it)) }) { picker = "color" }
            if (eff == "gradient") {
                RowDivider()
                Text("Blend into", color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(start = 22.dp, top = 12.dp))
                SwatchRow(f?.optString("color2") ?: "", on, { set(JSONObject().put("color2", it)) }) { picker = "color2" }
            }
        }
        SectionLabel("Effect")
        Group {
            EFFECTS.forEachIndexed { i, (key, v) ->
                if (i > 0) RowDivider()
                Row1(v.first, v.second, eff == key, enabled = on, onClick = { set(JSONObject().put("effect", key)) }) {
                    OneRadio(eff == key, on)
                }
            }
        }
        if (eff in listOf("pulse", "blink", "cycle", "wave", "random")) Group {
            SliderRow("Speed", speed, 1f..100f, "${speedLabel(speed.toInt())} per cycle", on, onChange = { speed = it }) {
                set(JSONObject().put("speed", speed.toInt())) }
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 12.dp)) {
                Text("Slower", color = N.sub, fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("Faster", color = N.sub, fontSize = 13.sp)
            }
            if (eff in listOf("cycle", "wave")) {
                RowDivider()
                val rb = f?.optBoolean("rainbow", true) ?: true
                SwitchRow("Rainbow", if (rb) "Uses every colour" else "Uses your colour only", rb, on) {
                    set(JSONObject().put("rainbow", it)) }
            }
        }
        if (eff in listOf("gradient", "wave")) Group {
            SliderRow("LEDs on the fan", leds, 4f..40f, "${leds.toInt()}", on, onChange = { leds = it }) {
                set(JSONObject().put("led_count", leds.toInt())) }
            Text("Match this to your fan so the ${if (eff == "wave") "wave" else "blend"} fits the ring exactly (most 120 mm fans have 8–18).",
                color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 16.dp))
        }
        SectionLabel("Automation")
        Group {
            val sl = f?.optBoolean("status_light") ?: false
            SwitchRow("Status light", "Turns amber for warnings and pulses red for critical alerts, then goes back to your colour",
                sl) { set(JSONObject().put("status_light", it)) }
            RowDivider()
            val n = f?.optJSONArray("schedules")?.length() ?: 0
            Row1("Schedules", if (n == 0) "Dim at night, turn off while you sleep…" else "$n schedule${if (n > 1) "s" else ""}", n > 0,
                onClick = { app.go(Route.Schedules) })
        }
        LinksCard(listOf("Notifications" to { app.go(Route.NotifySettings) }, "Storage & hardware" to { app.go(Route.Hardware) }))
    }
    picker?.let { which ->
        ColorPickerDialog(f?.optString(which) ?: "#3e91ff", { picker = null }) { picker = null; set(JSONObject().put(which, it)) }
    }
}

@Composable fun SchedulesScreen(app: AppState) {
    val sch = app.fan?.optJSONArray("schedules") ?: JSONArray()
    Page("Schedules", app::back, listOf(TopAction(Icons.Rounded.Add, "Add") { app.go(Route.EditSchedule(-1)) })) {
        if (sch.length() == 0) {
            Text("No schedules yet. Add one to dim the fan at night or switch it off while you sleep.", color = N.sub,
                fontSize = 15.sp, modifier = Modifier.padding(30.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PrimaryButton("Add schedule", Modifier.padding(20.dp)) { app.go(Route.EditSchedule(-1)) }
            }
        } else Group {
            for (i in 0 until sch.length()) {
                val s = sch.getJSONObject(i); if (i > 0) RowDivider()
                val days = s.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: (0..6).toList()
                SwitchRow("${s.optString("time")}  ${s.optString("name")}".trim(),
                    "${describe(s.optJSONObject("set"))} · ${if (days.size == 7) "Every day" else days.joinToString(" ") { listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")[it] }}",
                    s.optBoolean("enabled", true), subtitleBlue = true, onClick = { app.go(Route.EditSchedule(i)) }) { en ->
                    val all = JSONArray(sch.toString()); all.getJSONObject(i).put("enabled", en)
                    app.changeFan(JSONObject().put("schedules", all))
                }
            }
        }
    }
}

fun describe(set: JSONObject?): String {
    if (set == null) return ""
    if (set.has("on") && !set.optBoolean("on")) return "Turn off"
    val parts = mutableListOf<String>()
    if (set.optBoolean("on", false)) parts += "Turn on"
    if (set.has("brightness")) parts += "Brightness ${set.optInt("brightness")}%"
    if (set.has("effect")) parts += set.optString("effect").replaceFirstChar { it.uppercase() }
    if (set.has("color")) parts += "colour"
    return parts.joinToString(", ").ifEmpty { "No change" }
}

@Composable fun EditScheduleScreen(app: AppState, index: Int) {
    val existing = app.fan?.optJSONArray("schedules")?.optJSONObject(index)
    val set0 = existing?.optJSONObject("set")
    var hour by remember { mutableIntStateOf(existing?.optString("time")?.take(2)?.toIntOrNull() ?: 22) }
    var minute by remember { mutableIntStateOf(existing?.optString("time")?.takeLast(2)?.toIntOrNull() ?: 0) }
    var days by remember { mutableStateOf(existing?.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: (0..6).toSet()) }
    var turnOff by remember { mutableStateOf(set0?.has("on") == true && !set0.optBoolean("on")) }
    var bright by remember { mutableFloatStateOf((set0?.optInt("brightness", 5) ?: 5).toFloat()) }
    var setColor by remember { mutableStateOf(set0?.optString("color")?.ifEmpty { null }) }
    var picker by remember { mutableStateOf(false) }

    fun save() {
        val all = JSONArray((app.fan?.optJSONArray("schedules") ?: JSONArray()).toString())
        val set = JSONObject()
        if (turnOff) set.put("on", false) else { set.put("on", true).put("brightness", bright.toInt()); setColor?.let { set.put("color", it) } }
        val s = JSONObject().put("time", "%02d:%02d".format(hour, minute)).put("days", JSONArray(days.sorted()))
            .put("enabled", true).put("set", set)
        if (index >= 0) { s.put("id", existing?.optString("id")); all.put(index, s) } else all.put(s)
        app.changeFan(JSONObject().put("schedules", all)); app.back(); app.toast("Saved")
    }
    fun delete() {
        val all = JSONArray((app.fan?.optJSONArray("schedules") ?: JSONArray()).toString()); all.remove(index)
        app.changeFan(JSONObject().put("schedules", all)); app.back(); app.toast("Deleted")
    }

    Box(Modifier.fillMaxSize()) {
        Page(if (index >= 0) "Edit schedule" else "New schedule", app::back, bottom = 120.dp) {
            Box(Modifier.padding(vertical = 8.dp)) { TimeWheels(hour, minute) { h, m -> hour = h; minute = m } }
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                DAYS.forEachIndexed { i, d ->
                    val sel = i in days
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (sel) N.blue else N.card)
                        .clickable { days = if (sel) days - i else days + i }, contentAlignment = Alignment.Center) {
                        Text(d, color = if (sel) Color.White else N.sub, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Group { SwitchRow("Turn the light off", null, turnOff) { turnOff = it } }
            if (!turnOff) {
                Group { SliderRow("Brightness", bright, 0f..100f, "${bright.toInt()}%", onChange = { bright = it }) {} }
                SectionLabel("Colour (optional)")
                Group {
                    SwatchRow(setColor ?: "", true, { setColor = if (setColor == it) null else it }) { picker = true }
                }
            }
            if (index >= 0) Group { Row1("Delete schedule", null, icon = Icons.Rounded.Delete, iconTint = N.red, onClick = ::delete) }
        }
        CancelSavePill(app::back, ::save, days.isNotEmpty(), modifier = Modifier.align(Alignment.BottomCenter))
    }
    if (picker) ColorPickerDialog(setColor ?: "#3e91ff", { picker = false }) { setColor = it; picker = false }
}
