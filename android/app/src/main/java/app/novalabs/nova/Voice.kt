package app.novalabs.nova

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume

/** Talking to Nova: the mic records 16 kHz mono speech for the server's own speech model (nothing goes to an outside
 *  service), or — when the server has no voice — the phone's speech recognizer does it (offline if it can). Replies can be
 *  read out by the phone's own voice. */
object Voice {
    @Volatile var stopNow = false

    /** Records until you pause (1.4 s of quiet after speaking), 30 s at most, or 8 s of nothing. null if nothing was heard. */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun record(onLevel: (Float) -> Unit): ByteArray? = withContext(Dispatchers.IO) {
        stopNow = false
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(3200)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2)
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("The microphone isn't available") }
        val out = ByteArrayOutputStream(); val buf = ShortArray(1600)
        var heard = false; var quiet = 0.0; var total = 0.0
        try {
            rec.startRecording()
            while (!stopNow) {
                val n = rec.read(buf, 0, buf.size); if (n <= 0) continue
                var sum = 0.0; for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
                val rms = kotlin.math.sqrt(sum / n); onLevel((rms * 8).coerceIn(0.0, 1.0).toFloat())
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN); for (i in 0 until n) bb.putShort(buf[i]); out.write(bb.array())
                val sec = n / rate.toDouble(); total += sec
                if (rms > .02) { heard = true; quiet = 0.0 } else quiet += sec
                if ((heard && quiet > 1.4) || total > 30 || (!heard && total > 8)) break
            }
        } finally { runCatching { rec.stop() }; rec.release(); onLevel(0f) }
        if (heard) wav(out.toByteArray(), rate) else null
    }

    fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size)
        return h.array() + pcm
    }

    /** The phone's own speech recognizer (offline when it has the language). null if nothing was understood. */
    suspend fun recognize(ctx: Context, lang: String): String? = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) throw IllegalStateException("This phone has no speech recognizer — download voice for the server below")
        suspendCancellableCoroutine { cont ->
            val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
            fun done(t: String?) { runCatching { sr.destroy() }; if (cont.isActive) cont.resume(t) }
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle?) = done(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onError(e: Int) = done(null)
                override fun onReadyForSpeech(p: Bundle?) {}; override fun onBeginningOfSpeech() {}; override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onPartialResults(b: Bundle?) {}; override fun onEvent(t: Int, b: Bundle?) {}
            })
            sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang).putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true))
            cont.invokeOnCancellation { runCatching { sr.cancel(); sr.destroy() } }
        }
    }

    private var tts: TextToSpeech? = null; private var ttsReady = false; private var queued: String? = null
    fun speak(ctx: Context, text: String) {
        if (text.isBlank()) return
        val t = tts
        if (t == null) { queued = text; tts = TextToSpeech(ctx.applicationContext) { ok -> ttsReady = ok == TextToSpeech.SUCCESS; queued?.let { q -> queued = null; if (ttsReady) tts?.speak(q, TextToSpeech.QUEUE_FLUSH, null, "nova") } }; return }
        if (ttsReady) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova") else queued = text
    }
    fun hush() { runCatching { tts?.stop() } }
}
