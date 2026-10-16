package app.novalabs.nova

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.*
import kotlinx.coroutines.launch

/**
 * Sounds and haptics, one sound pack per look (Aero's glassy chimes and clicks, Mica's soft taps, Breeze's plucks,
 * Glass's pings, Material's ticks, the Default look's droplets). Every sound is made on the phone from the same recipes
 * Nova web uses (assets/sounds.json, copied from server/web at build time), so the two sound alike. A pack is rendered
 * once into small WAV files in the cache and played with SoundPool, so a tap costs next to nothing.
 */
object Sounds {
    private const val SR = 44100
    private lateinit var app: Context
    private lateinit var sp: android.content.SharedPreferences
    private var pool: SoundPool? = null
    private var data: JSONObject? = null
    private val loaded = mutableMapOf<String, Map<String, Int>>()     // pack → event → SoundPool id
    private val ready = mutableSetOf<Int>()
    private var lastTap = 0; private var lastTapAt = 0L
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    var on by mutableStateOf(true); private set
    var volume by mutableStateOf(60); private set
    var pack by mutableStateOf("auto"); private set
    var alerts by mutableStateOf(true); private set
    var haptics by mutableStateOf(true); private set
    /** Kinds of sound turned off (Appearance → Sounds): taps, switches, pages, popups, tasks. */
    var off by mutableStateOf(setOf<String>()); private set
    val KINDS = linkedMapOf("taps" to ("Taps" to "Buttons, rows and links"), "switches" to ("Switches" to "On and off"), "pages" to ("Pages" to "Going into a page and back"),
        "popups" to ("Pop-ups" to "Dialogs opening and closing"), "tasks" to ("Finished tasks" to "Done, or didn't finish"))
    private val KIND = mapOf("tap" to "taps", "toggleOn" to "switches", "toggleOff" to "switches", "nav" to "pages", "back" to "pages", "slide" to "pages",
        "open" to "popups", "close" to "popups", "success" to "tasks", "error" to "tasks")
    fun setKind(k: String, on: Boolean) { off = if (on) off - k else off + k; sp.edit().putStringSet("off", off).apply() }

    val PACKS = linkedMapOf("default" to "Droplets", "material" to "Material", "aero" to "Aero", "fluent" to "Mica", "breeze" to "Breeze", "liquid" to "Glass")

    fun init(ctx: Context) {
        app = ctx.applicationContext
        sp = app.getSharedPreferences("nova_sound", Context.MODE_PRIVATE)
        on = sp.getBoolean("on", true); volume = sp.getInt("vol", 60); pack = sp.getString("pack", "auto")!!
        alerts = sp.getBoolean("alerts", true); haptics = sp.getBoolean("haptics", true); off = sp.getStringSet("off", emptySet())!!.toSet()
    }
    fun set(key: String, v: Any) {
        when (key) { "on" -> on = v as Boolean; "vol" -> volume = v as Int; "pack" -> pack = v as String; "alerts" -> alerts = v as Boolean; "haptics" -> haptics = v as Boolean }
        sp.edit().apply { when (v) { is Boolean -> putBoolean(key, v); is Int -> putInt(key, v); else -> putString(key, v.toString()) } }.apply()
    }

    /** The pack for the look in use ("Match the look"), or the one picked. */
    fun packId(): String = if (pack != "auto") pack else when { materialStyle() -> "material"; else -> presetStyle().ifEmpty { "default" } }

    private fun pool(): SoundPool = pool ?: SoundPool.Builder().setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build().also { p -> p.setOnLoadCompleteListener { _, id, st -> if (st == 0) ready.add(id) }; pool = p }

    private fun recipes(): JSONObject? = data ?: runCatching { JSONObject(app.assets.open("sounds.json").bufferedReader().readText()) }.getOrNull()?.also { data = it }

    /** Renders a pack's sounds to WAVs (once; kept in the cache) and loads them. Off the main thread. */
    private fun load(id: String): Map<String, Int>? {
        loaded[id]?.let { return it }
        val j = recipes() ?: return null
        val p = j.getJSONObject("packs").optJSONObject(id) ?: j.getJSONObject("packs").getJSONObject("default")
        val echo = p.optJSONArray("echo")
        val dir = File(app.cacheDir, "sounds").apply { mkdirs() }
        val evs = j.getJSONArray("events"); val out = mutableMapOf<String, Int>()
        for (k in 0 until evs.length()) {
            val ev = evs.getString(k); val voices = p.optJSONArray(ev) ?: continue
            val f = File(dir, "$id-$ev-${(voices.toString() + echo).hashCode().toUInt()}.wav")
            if (!f.exists()) writeWav(f, render(voices, echo))
            out[ev] = pool().load(f.path, 1)
        }
        return out.also { loaded[id] = it }
    }

    /** One sound from its recipe — the same steps as sounds.js render() in Nova web. */
    fun render(voices: JSONArray, echo: JSONArray?): FloatArray {
        var len = 0.0
        for (i in 0 until voices.length()) { val v = voices.getJSONObject(i); len = max(len, v.optDouble("t", 0.0) + v.optDouble("a", 0.0) + v.optDouble("d", .1)) }
        val tail = echo?.let { it.getDouble(0) * 5 } ?: 0.0
        val n = ceil((len + tail + .02) * SR).toInt(); val out = FloatArray(n)
        var seed = 22222L
        fun rnd(): Double { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return seed.toDouble() / 0x3fffffff - 1 }
        for (vi in 0 until voices.length()) {
            val v = voices.getJSONObject(vi)
            val s0 = floor(v.optDouble("t", 0.0) * SR).toInt(); val a = v.optDouble("a", .001).takeIf { it > 0 } ?: .001; val d = v.optDouble("d", .1)
            val total = ceil((a + d) * SR).toInt(); val g = v.optDouble("g", .1); val w = v.optString("w", "sine")
            val f = v.optDouble("f", 440.0); val f2 = v.optDouble("f2", f)
            val kLp = if (v.has("lp")) 1 - exp(-2 * PI * v.getDouble("lp") / SR) else 0.0
            val kHp = if (v.has("hp")) 1 - exp(-2 * PI * v.getDouble("hp") / SR) else 0.0
            var ph = 0.0; var lp = 0.0; var hpLow = 0.0
            var i = 0
            while (i < total && s0 + i < n) {
                val t = i.toDouble() / SR; val env = if (t < a) t / a else exp(ln(.001) * (t - a) / d)
                var x: Double
                if (w == "noise") {
                    x = rnd()
                    if (kLp > 0) { lp += kLp * (x - lp); x = lp }
                    if (kHp > 0) { hpLow += kHp * (x - hpLow); x -= hpLow }
                } else {
                    val fr = f * (f2 / f).pow(min(1.0, t / (a + d)))
                    ph += fr / SR; ph -= floor(ph)
                    x = when (w) { "triangle" -> 1 - 4 * abs(ph - .5); "square" -> if (ph < .5) 1.0 else -1.0; else -> sin(2 * PI * ph) }
                }
                out[s0 + i] += (x * env * g).toFloat(); i++
            }
        }
        if (echo != null) { val dl = (echo.getDouble(0) * SR).roundToInt(); val fb = echo.getDouble(1).toFloat(); for (i in dl until n) out[i] += out[i - dl] * fb }
        for (i in 0 until n) out[i] = (tanh(out[i] * 1.2) / 1.2).toFloat()
        return out
    }

    private fun writeWav(f: File, s: FloatArray) {
        val data = java.nio.ByteBuffer.allocate(44 + s.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + s.size * 2).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(SR).putInt(SR * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(s.size * 2)
        for (x in s) data.putShort((x.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        f.writeBytes(data.array())
    }

    /** Get this look's pack ready (call when the app starts or the look changes), so the first tap isn't silent. */
    fun warm() { if (on && ::app.isInitialized) worker.execute { runCatching { load(packId()) } } }

    /** Plays one event: tap, toggleOn/Off, nav, back, open, close, info, warning, critical, success, error, startup. */
    fun play(ev: String, force: Boolean = false, packOverride: String? = null) {
        if (!::app.isInitialized || (!force && !on)) return
        if (!force && ev in setOf("info", "warning", "critical") && !alerts) return
        if (!force && KIND[ev] in off) return
        val id = packOverride ?: packId()
        worker.execute {
            runCatching {
                val ids = load(id) ?: return@runCatching; val s = ids[ev] ?: return@runCatching
                // a page change or a switch already says the press happened: the tap under it stops
                val now = System.currentTimeMillis()
                if (ev != "tap" && now - lastTapAt < 150) pool().stop(lastTap)
                var tries = 0; while (s !in ready && tries++ < 40) Thread.sleep(10)        // first use: wait for it to load
                val v = volume / 100f
                val stream = pool().play(s, v, v, 1, 0, 1f)
                if (stream == 0) android.util.Log.w("NovaSound", "couldn't play $ev ($id)") else if (BuildConfig.DEBUG) android.util.Log.d("NovaSound", "played $ev ($id)")
                if (ev == "tap") { lastTap = stream; lastTapAt = now }
            }
        }
    }

    /** A short buzz to go with it (Vibration setting). */
    /** The app's window, for the system's own touch feedback (crisper than a raw buzz, and it follows the phone's settings). */
    var view: java.lang.ref.WeakReference<android.view.View>? = null
    fun buzz(ev: String) {
        if (!::app.isInitialized || !haptics) return
        val v = view?.get()
        if (v != null && ev in setOf("tap", "nav", "back", "slide", "toggleOn", "toggleOff", "open")) {
            val c = android.view.HapticFeedbackConstants::class.java
            val kind = when (ev) {
                "toggleOn" -> if (Build.VERSION.SDK_INT >= 34) c.getField("TOGGLE_ON").getInt(null) else android.view.HapticFeedbackConstants.CONTEXT_CLICK
                "toggleOff" -> if (Build.VERSION.SDK_INT >= 34) c.getField("TOGGLE_OFF").getInt(null) else android.view.HapticFeedbackConstants.CONTEXT_CLICK
                "nav", "back", "slide" -> android.view.HapticFeedbackConstants.CLOCK_TICK
                "open" -> android.view.HapticFeedbackConstants.CONTEXT_CLICK
                else -> android.view.HapticFeedbackConstants.KEYBOARD_TAP
            }
            v.post { v.performHapticFeedback(kind) }
            return
        }
        val vib = (if (Build.VERSION.SDK_INT >= 31) app.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator else @Suppress("DEPRECATION") app.getSystemService(Vibrator::class.java)) ?: return
        if (!vib.hasVibrator()) return
        // taps follow the phone's own "touch feedback" setting; alerts always buzz (when Vibration is on)
        if (ev in setOf("tap", "nav", "back", "toggleOn", "toggleOff", "open") &&
            android.provider.Settings.System.getInt(app.contentResolver, android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return
        if (Build.VERSION.SDK_INT < 26) {          // Android 7: plain buzzes
            val ms = when (ev) { "tap", "nav", "back" -> 8L; "toggleOn", "toggleOff", "open" -> 14L; "critical" -> 220L; "warning", "error" -> 90L; "info", "success" -> 40L; else -> return }
            @Suppress("DEPRECATION") runCatching { vib.vibrate(ms) }; return
        }
        val effect = when (ev) {
            "tap", "nav", "back" -> if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK) else VibrationEffect.createOneShot(6, 60)
            "toggleOn", "toggleOff", "open" -> if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK) else VibrationEffect.createOneShot(12, 120)
            "success" -> VibrationEffect.createWaveform(longArrayOf(0, 10, 40, 16), intArrayOf(0, 120, 0, 180), -1)
            "info" -> VibrationEffect.createWaveform(longArrayOf(0, 18, 50, 18), intArrayOf(0, 120, 0, 120), -1)
            "warning", "error" -> VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 30), intArrayOf(0, 200, 0, 200), -1)
            "critical" -> VibrationEffect.createWaveform(longArrayOf(0, 60, 50, 60, 50, 140), intArrayOf(0, 255, 0, 255, 0, 255), -1)
            else -> return
        }
        runCatching { vib.vibrate(effect) }
    }
    fun feel(ev: String) { play(ev); buzz(ev) }
}

/**
 * Every tap in the app makes its sound and buzz: wrapped around the press effect (the ripple) that every clickable,
 * switch row and list item already uses — so it doesn't matter whether you tap the switch itself or the row around it.
 */
class SoundIndication(private val inner: androidx.compose.foundation.IndicationNodeFactory) : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        object : androidx.compose.ui.node.DelegatingNode() {
            init { delegate(inner.create(interactionSource)) }
            override fun onAttach() {
                coroutineScope.launch { interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.PressInteraction.Release) Sounds.feel("tap") } }
            }
        }
    override fun equals(other: Any?) = other is SoundIndication && other.inner == inner
    override fun hashCode() = inner.hashCode()
}
