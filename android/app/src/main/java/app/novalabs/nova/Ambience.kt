package app.novalabs.nova

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.*

/**
 * Ambience (Appearance → Sounds & haptics, Beta): a soft, slowly changing pad while Nova is open — the same music as
 * Nova web's ambience.js, made live on the phone. Each look has its own chords and color (Aero bright and glassy, Mica
 * warm, Breeze open, Glass airy, Material clean, Default dreamy), with a breath of air and, for Aero and Glass, the odd
 * glint on top. It stops whenever Nova leaves the screen.
 */
object Ambience {
    private lateinit var sp: android.content.SharedPreferences
    var on by mutableStateOf(false); private set
    var volume by mutableStateOf(35); private set
    fun init(ctx: Context) { sp = ctx.getSharedPreferences("nova_sound", Context.MODE_PRIVATE); on = sp.getBoolean("amb", false); volume = sp.getInt("amb_vol", 35) }
    fun set(key: String, v: Any) {
        when (key) { "on" -> { on = v as Boolean; sp.edit().putBoolean("amb", on).apply() }; "vol" -> { volume = v as Int; sp.edit().putInt("amb_vol", volume).apply() } }
    }

    private class Song(val w1: Int, val w2: Int, val cut: Float, val glint: Boolean, val chords: List<IntArray>)
    // waves: 0 sine, 1 triangle
    private val SONGS = mapOf(
        "default" to Song(0, 1, 1300f, false, listOf(intArrayOf(48, 55, 64, 71, 74), intArrayOf(45, 52, 60, 67, 71), intArrayOf(41, 48, 57, 64, 67), intArrayOf(43, 50, 59, 64, 69))),
        "aero" to Song(1, 0, 2200f, true, listOf(intArrayOf(50, 57, 62, 66, 73), intArrayOf(47, 54, 62, 66, 71), intArrayOf(43, 50, 59, 62, 69), intArrayOf(45, 52, 61, 64, 69))),
        "fluent" to Song(0, 0, 1000f, false, listOf(intArrayOf(53, 60, 65, 69, 72), intArrayOf(50, 57, 62, 65, 69), intArrayOf(46, 53, 58, 62, 65), intArrayOf(48, 55, 60, 64, 67))),
        "breeze" to Song(1, 1, 1600f, false, listOf(intArrayOf(52, 59, 64, 66, 71), intArrayOf(49, 56, 61, 64, 68), intArrayOf(45, 52, 57, 59, 64), intArrayOf(47, 54, 59, 61, 66))),
        "liquid" to Song(0, 0, 1800f, true, listOf(intArrayOf(55, 62, 67, 71, 74), intArrayOf(52, 59, 64, 67, 71), intArrayOf(48, 55, 62, 64, 71), intArrayOf(50, 57, 62, 66, 69))),
        "material" to Song(0, 1, 1400f, false, listOf(intArrayOf(57, 64, 69, 73, 76), intArrayOf(54, 61, 66, 69, 73), intArrayOf(50, 57, 62, 66, 69), intArrayOf(52, 59, 64, 68, 71))),
    )
    private const val SR = 22050; private const val CHORD_S = 11f; private const val LEN = CHORD_S + 5f
    private fun hz(n: Int) = 440.0 * 2.0.pow((n - 69) / 12.0)

    @Volatile private var running = false
    @Volatile private var songId = "default"
    private var thread: Thread? = null

    fun start(song: String) {
        songId = song
        if (running) return
        running = true
        thread = Thread({ loop() }, "nova-ambience").apply { priority = Thread.MIN_PRIORITY; start() }
    }
    fun stop() { running = false; thread = null }

    private class Voice(f: DoubleArray, val w1: Int, val w2: Int, val start: Long, val cut: Float) {
        val n = f.size
        val inc = DoubleArray(n * 2) { f[it / 2] * 2.0.pow((if (it % 2 == 0) -6 else 7) / 1200.0) / SR }     // detuned pair per note
        val ph = DoubleArray(n * 2); var lp = 0.0; var k = 0.1
    }
    private class Glint(val f: Double, val start: Long) { var ph = 0.0 }

    private fun loop() {
        val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = runCatching {
            AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(max(min, 8192)).build()
        }.getOrNull() ?: run { running = false; return }
        track.play()
        val chunk = 1024; val buf = ShortArray(chunk)
        var n = 0L; var nextChord = 0L; var step = 0; var gain = 0.0
        val voices = ArrayList<Voice>(); val glints = ArrayList<Glint>()
        var seed = 22222L; var airLo = 0.0; var airHi = 0.0
        val rnd = java.util.Random()
        while (running) {
            val s = SONGS[songId] ?: SONGS["default"]!!
            val target = volume / 100.0 * .16
            for (v in voices) {                                              // the filter moves slowly: set it once per chunk
                val t = (n - v.start).toDouble() / SR
                val cut = v.cut * (if (t < LEN * .45) .5 + .5 * t / (LEN * .45) else (1 - .55 * (t - LEN * .45) / (LEN * .55)).coerceAtLeast(.3))
                v.k = 1 - exp(-2 * PI * cut / SR)
            }
            val center = 1400 + 700 * sin(2 * PI * .05 * n / SR)
            val kLo = 1 - exp(-2 * PI * (center * 1.6) / SR); val kHi = 1 - exp(-2 * PI * (center * .6) / SR)
            for (i in 0 until chunk) {
                if (n >= nextChord) {                                       // the next chord fades in over the last
                    val notes = s.chords[step++ % s.chords.size]
                    voices.add(Voice(DoubleArray(notes.size) { hz(notes[it]) }, s.w1, s.w2, n, s.cut))
                    if (s.glint) repeat(3) { glints.add(Glint(hz(notes[1 + rnd.nextInt(4)] + 24), n + ((2 + rnd.nextDouble() * (CHORD_S - 3)) * SR).toLong())) }
                    nextChord = n + (CHORD_S * SR).toLong()
                }
                var x = 0.0
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next(); val t = (n - v.start).toDouble() / SR
                    if (t > LEN) { it.remove(); continue }
                    val env = when { t < 4.5 -> t / 4.5; t > LEN - 5 -> (LEN - t) / 5; else -> 1.0 }
                    var sum = 0.0
                    for (idx in 0 until v.n * 2) {
                        var p = v.ph[idx] + v.inc[idx]; if (p >= 1) p -= 1; v.ph[idx] = p
                        val j = idx % 2
                        val w = if ((if (j == 0) v.w1 else v.w2) == 1) 1 - 4 * abs(p - .5) else sin(2 * PI * p)
                        sum += w * (if (j == 0) .5 else .35) * (if (idx < 2) 1.3 else 1.0)
                    }
                    v.lp += v.k * (sum / v.n - v.lp)               // the filter opens toward the middle of the chord and closes again
                    x += v.lp * env
                }
                val gi = glints.iterator()
                while (gi.hasNext()) {
                    val g = gi.next(); val t = (n - g.start).toDouble() / SR
                    if (t < 0) continue; if (t > 4) { gi.remove(); continue }
                    g.ph = (g.ph + g.f / SR) % 1.0
                    x += sin(2 * PI * g.ph) * .05 * (if (t < .4) t / .4 else exp(ln(.01) * (t - .4) / 3.6))
                }
                // a breath of air: soft noise in a gently moving band
                seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
                val noise = seed.toDouble() / 0x3fffffff - 1
                airLo += kLo * (noise - airLo); airHi += kHi * (airLo - airHi)
                x += (airLo - airHi) * .05
                gain += (target - gain) * .00005                               // fades in and follows the volume smoothly
                buf[i] = (tanh(x * gain * 4) / 4 * 32767 * 1.0).toInt().coerceIn(-32768, 32767).toShort()
                n++
            }
            track.write(buf, 0, chunk)
        }
        runCatching { track.pause(); track.flush(); track.release() }
    }
}

/** Plays the ambience while it's on and Nova is on screen, in the look in use. Put once near the root. */
@Composable fun AmbienceTicker() {
    val t = N
    val song = if (t.material) "material" else t.preset.ifEmpty { "default" }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var visible by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_START) visible = true; if (e == Lifecycle.Event.ON_STOP) visible = false }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Ambience.on, visible, song) { if (Ambience.on && visible) Ambience.start(song) else Ambience.stop() }
}
