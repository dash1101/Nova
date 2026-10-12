package app.novalabs.nova

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import kotlin.math.sqrt

/** What a shake can do (Settings → Appearance → Shake to…). */
val SHAKE_ACTIONS = listOf("report" to "Report a problem", "search" to "Search Nova", "quick" to "Open the quick panel",
    "inbox" to "Open the Inbox", "lights" to "Turn the lights on or off", "home" to "Go Home", "none" to "Do nothing")

/** Listens for a firm shake (two jolts within a second) while Nova is on screen, then does what you picked. */
@Composable fun ShakeListener(app: AppState) {
    val ctx = LocalContext.current; val owner = LocalLifecycleOwner.current
    val enabled = AppPrefs.shakeAction != "none"
    DisposableEffect(owner, enabled) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var first = 0L; var last = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (g < shakeThreshold()) return
                val now = System.currentTimeMillis()
                if (now - last < 2500) return                     // just did something: let it settle
                if (now - first > 1000) { first = now; return }   // the first jolt; a second within a second is a shake
                first = 0L; last = now; onShake(app)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        fun on() { if (enabled && acc != null) sm.registerListener(l, acc, SensorManager.SENSOR_DELAY_GAME) }   // fast enough to catch a jolt's peak
        fun off() = sm.unregisterListener(l)
        val obs = LifecycleEventObserver { _, ev -> when (ev) { Lifecycle.Event.ON_RESUME -> on(); Lifecycle.Event.ON_PAUSE -> off(); else -> {} } }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) on()
        onDispose { owner.lifecycle.removeObserver(obs); off() }
    }
}

/** How hard a jolt has to be (in g): a light shake is enough on "high", it takes a hard one on "low". */
private fun shakeThreshold() = when (AppPrefs.shakeSense) { "high" -> 1.8f; "low" -> 2.8f; else -> 2.3f }
private val SENSES = listOf("low" to "Hard shake", "normal" to "Normal", "high" to "Light shake")

/** Shake on/off, what it does and how hard you need to shake — in Appearance, and right on the screen a shake opens. */
@Composable fun ShakeSettings() {
    var pick by remember { mutableStateOf(false) }
    val on = AppPrefs.shakeAction != "none"
    Group {
        SwitchRow("Shake to…", if (on) SHAKE_ACTIONS.firstOrNull { it.first == AppPrefs.shakeAction }?.second ?: "Report a problem" else "Off",
            on, subtitleBlue = on, onClick = { pick = true }) {
            if (it) AppPrefs.set("shake_action", AppPrefs.shakeLast.takeIf { a -> a != "none" } ?: "report")
            else { AppPrefs.set("shake_last", AppPrefs.shakeAction); AppPrefs.set("shake_action", "none") }
        }
    }
    if (on) {
        Text("How hard to shake", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 30.dp, top = 12.dp))
        Segmented(SENSES.map { it.second }, SENSES.indexOfFirst { it.first == AppPrefs.shakeSense }.let { if (it < 0) 1 else it }) { AppPrefs.set("shake_sense", SENSES[it].first) }
    }
    if (pick) OneDialog({ pick = false }, "When you shake your ${DeviceForm.noun}") {
        SHAKE_ACTIONS.forEach { (k, l) -> DialogChoice(l, null, AppPrefs.shakeAction == k) {
            if (k == "none") AppPrefs.set("shake_last", AppPrefs.shakeAction)
            AppPrefs.set("shake_action", k); pick = false } } }
}

/** The report screen was opened by a shake (so its shake settings go first, in case that was an accident). */
private var shakeOpened = false

private fun onShake(app: AppState) {
    when (AppPrefs.shakeAction) {
        "report" -> { shakeOpened = true; app.go(Route.Report) }
        "search" -> app.go(Route.Search)
        "quick" -> app.go(Route.QuickPanel)
        "inbox" -> app.go(Route.Inbox)
        "home" -> app.tab(Route.Home)
        "lights" -> if (app.has("lighting")) { val on = app.fan?.optBoolean("on") != true; app.changeFan(JSONObject().put("on", on)); app.toast(if (on) "Lights on" else "Lights off") }
    }
}

/** Report a problem: what happened, plus the app's details — saved on your server; optionally opened as a GitHub issue. */
@Composable fun ReportScreen(app: AppState) {
    var text by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val details = remember { "Nova $APP_VERSION · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${DeviceForm.kind}" +
        " · via ${app.api.via}" + (app.api.lastAttempts.takeIf { it.isNotEmpty() }?.let { "\nLast connection attempts:\n" + it.joinToString("\n") } ?: "") }
    val byShake = remember { shakeOpened.also { shakeOpened = false } }
    Page("Report a problem", app::back) {
        if (byShake) {
            Text("Opened by shaking your ${DeviceForm.noun}. Didn't mean to? Turn it off or make it need a harder shake:",
                color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
            ShakeSettings()
            Spacer(Modifier.height(10.dp))
        }
        Note("What went wrong, or what would you like to see? Your report is saved on your own server (Nova keeps nothing anywhere else). If you'd like the developers to see it, open it as a GitHub issue too.", 14.sp, 6.dp)
        Group { Box(Modifier.padding(14.dp)) { OneTextField(text, { text = it.take(4000) }, "What happened? What did you expect?", Modifier.fillMaxWidth().heightIn(min = 160.dp), singleLine = false) } }
        SectionLabel("Included")
        Text(details, color = N.sub, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 30.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) { PillButton("Save on the server", enabled = text.isNotBlank()) {
                app.act("Saved — thanks") { app.api.post("/api/v1/crash", JSONObject().put("report", "USER REPORT\n$text\n\n$details").put("version", APP_VERSION)); app.back() } } }
            Box(Modifier.weight(1f)) { PillButton("Open on GitHub", enabled = text.isNotBlank(), color = N.text) {
                val body = android.net.Uri.encode("$text\n\n---\n$details".take(6000))
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/dash1101/Nova/issues/new?title=${android.net.Uri.encode(text.lineSequence().first().take(80))}&body=$body"))) } } }
        }
        if (!byShake) { SectionLabel("Shake"); ShakeSettings()
        Text("Turn shaking off, make it need a harder shake, or have it do something else. Also in Appearance.", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp)) }
    }
}
