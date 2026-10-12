package app.novalabs.nova.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import org.json.JSONObject

/** A watch-face complication: your server's health in a word ("OK", "Warn", "Alert"), or its CPU as a ring. Tap → Nova. */
class StatusComplication : SuspendingComplicationDataSourceService() {
    private fun text(s: String) = PlainComplicationText.Builder(s).build()

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, "OK", "All systems normal", 23f)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val store = WearStore(this)
        val ov = if (store.paired) runCatching { WearApi(store).get("/api/v1/overview").also { store.lastOverview = it.toString() } }.getOrNull()
            ?: runCatching { JSONObject(store.lastOverview) }.getOrNull() else null
        val st = ov?.optJSONObject("status"); val lvl = st?.optString("level") ?: ""
        val word = when { !store.paired -> "Pair"; ov == null -> "—"; lvl == "critical" -> "Alert"; lvl == "warning" -> "Warn"; else -> "OK" }
        val desc = when { !store.paired -> "Open Nova to pair"; ov == null -> "Can't reach the server"; lvl == "ok" -> "All systems normal"; else -> st?.optString("headline") ?: "Needs attention" }
        val cpu = st?.optJSONObject("metrics")?.optString("cpu")?.filter { it.isDigit() || it == '.' }?.toFloatOrNull() ?: 0f
        return data(request.complicationType, word, desc, cpu)
    }

    private fun data(type: ComplicationType, word: String, desc: String, cpu: Float): ComplicationData? {
        val tap = PendingIntent.getActivity(this, 0, Intent(this, WearActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text(word), text(desc)).setTitle(text("Nova")).setTapAction(tap).build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(cpu.coerceIn(0f, 100f), 0f, 100f, text("CPU ${cpu.toInt()}% · $desc"))
                .setText(text(word)).setTapAction(tap).build()
            else -> null
        }
    }
}
