package app.novalabs.nova

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Pages a widget can open in the app. */
val WIDGET_PAGES = setOf("status", "storage", "lighting", "inbox")

/**
 * Home-screen widgets: five of them, all for the server you're on in the app.
 *  - Status: health and containers · Stats: CPU, memory, temperature, disk · Quick actions: back up, free memory, fan light, refresh
 *  - Inbox: the newest alerts · Storage: how full each drive is
 * They draw from the last snapshot (saved on the phone), refresh every 15 minutes in the background, whenever the app itself refreshes,
 * and when you tap Refresh. The actions are ones the server lets a paired device do without a fingerprint; anything riskier stays in the app.
 */
object Widgets {
    private const val PREFS = "nova_widgets"
    private val PROVIDERS = listOf(StatusWidget::class.java, StatsWidget::class.java, ActionsWidget::class.java, InboxWidget::class.java, StorageWidget::class.java)
    const val ACTION_REFRESH = "app.novalabs.nova.widget.REFRESH"
    const val ACTION_RUN = "app.novalabs.nova.widget.RUN"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun snap(ctx: Context, k: String) = sp(ctx).getString(k, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun anyPlaced(ctx: Context): Boolean { val m = AppWidgetManager.getInstance(ctx)
        return PROVIDERS.any { m.getAppWidgetIds(ComponentName(ctx, it)).isNotEmpty() } }

    /** Fetches a fresh snapshot from the server and redraws every widget. */
    suspend fun refresh(ctx: Context): Boolean {
        val pairing = Pairing(ctx)
        if (!pairing.paired) { render(ctx); return true }
        val ok = runCatching {
            val api = NovaApi(pairing)
            val o = api.get("/api/v1/overview")
            val now = runCatching { api.get("/api/v1/stats?since=9e12").optJSONObject("now") }.getOrNull()
            val ev = runCatching { api.get("/api/v1/events?since=0").optJSONArray("events") }.getOrNull()
            sp(ctx).edit().putString("overview", o.toString()).putString("now", now?.toString()).putString("events", JSONObject().put("events", ev ?: org.json.JSONArray()).toString())
                .putLong("at", System.currentTimeMillis()).putBoolean("offline", false).putString("server", pairing.label).apply()
        }.isSuccess
        if (!ok) sp(ctx).edit().putBoolean("offline", true).apply()
        render(ctx); return ok
    }

    /** The app just refreshed: the widgets take the new numbers too (at most once a minute — redrawing them isn't free). */
    fun fromApp(ctx: Context, pairing: Pairing, overview: JSONObject) {
        if (pairing.profile != Servers.active(ctx) || !anyPlaced(ctx)) return
        if (System.currentTimeMillis() - sp(ctx).getLong("at", 0) < 60_000) return
        sp(ctx).edit().putString("overview", overview.toString()).putLong("at", System.currentTimeMillis()).putBoolean("offline", false).putString("server", pairing.label).apply()
        render(ctx)
    }

    fun render(ctx: Context) {
        val m = AppWidgetManager.getInstance(ctx)
        for (p in PROVIDERS) { val ids = m.getAppWidgetIds(ComponentName(ctx, p)); if (ids.isEmpty()) continue
            val views = build(ctx, p); ids.forEach { m.updateAppWidget(it, views) } }
    }

    fun schedule(ctx: Context) {
        val work = PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("nova-widgets", ExistingPeriodicWorkPolicy.KEEP, work)
    }
    fun refreshSoon(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniqueWork("nova-widgets-now", ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<WidgetWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    fun unscheduleIfNone(ctx: Context) { if (!anyPlaced(ctx)) WorkManager.getInstance(ctx).cancelUniqueWork("nova-widgets") }

    // ── drawing ─────────────────────────────────────────────────────────────────────
    private fun color(ctx: Context, id: Int) = ctx.getColor(id)
    private fun levelColor(ctx: Context, level: String) = color(ctx, when (level) { "critical" -> R.color.w_red; "warning" -> R.color.w_amber; "ok", "resolved" -> R.color.w_green; else -> R.color.w_blue })
    private fun open(ctx: Context, page: String?, req: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (page != null) i.putExtra("open", page)
        return PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    private fun broadcast(ctx: Context, cls: Class<*>, action: String, what: String, req: Int) =
        PendingIntent.getBroadcast(ctx, req, Intent(ctx, cls).setAction(action).putExtra("do", what), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun header(ctx: Context, v: RemoteViews, level: String) {
        val s = sp(ctx); val at = s.getLong("at", 0)
        v.setTextViewText(R.id.w_name, s.getString("server", null)?.ifEmpty { null } ?: "Nova")
        v.setInt(R.id.w_dot, "setColorFilter", if (s.getBoolean("offline", false)) color(ctx, R.color.w_sub) else levelColor(ctx, level))
        val mins = ((System.currentTimeMillis() - at) / 60_000).toInt()
        v.setTextViewText(R.id.w_age, when { at == 0L -> ""; s.getBoolean("offline", false) -> "offline"; mins < 1 -> "now"; mins < 60 -> "${mins}m"; else -> "${mins / 60}h" })
    }

    private fun build(ctx: Context, p: Class<*>): RemoteViews {
        val o = snap(ctx, "overview"); val st = o?.optJSONObject("status"); val level = st?.optString("level") ?: "ok"
        val m = st?.optJSONObject("metrics"); val cs = o?.optJSONObject("containers"); val now = snap(ctx, "now")
        val paired = Pairing(ctx).paired
        return when (p) {
            StatusWidget::class.java -> RemoteViews(ctx.packageName, R.layout.widget_status).apply {
                header(ctx, this, level)
                setTextViewText(R.id.st_head, when { !paired -> "Not paired"; o == null -> "Connecting…"; level == "ok" -> "All good"
                    else -> st?.optString("headline")?.ifEmpty { null } ?: "${st?.optInt("active_count")} need attention" })
                setTextViewText(R.id.st_sub, cs?.let { "${it.optInt("running")}/${it.optInt("total")} containers running" } ?: "")
                setOnClickPendingIntent(R.id.w_root, open(ctx, null, 1))
            }
            StatsWidget::class.java -> RemoteViews(ctx.packageName, R.layout.widget_stats).apply {
                header(ctx, this, level)
                fun put(k: Int, sub: Int, b: Int, value: String, subText: String, pct: Double?) {
                    setTextViewText(k, value); setTextViewText(sub, subText); setProgressBar(b, 100, (pct ?: 0.0).toInt().coerceIn(0, 100), false) }
                val cpu = now?.optDouble("cpu")?.takeIf { !it.isNaN() }; val mem = now?.optDouble("mem")?.takeIf { !it.isNaN() }
                val temp = now?.optDouble("temp")?.takeIf { !it.isNaN() }
                put(R.id.cpu_v, R.id.cpu_s, R.id.cpu_b, cpu?.let { "%.0f%%".format(it) } ?: "—", now?.optDouble("load")?.takeIf { !it.isNaN() }?.let { "load $it" } ?: "", cpu)
                put(R.id.mem_v, R.id.mem_s, R.id.mem_b, mem?.let { "%.0f%%".format(it) } ?: "—", now?.let { "${it.optDouble("mem_used_gb")} / ${it.optDouble("mem_total_gb")} GB" } ?: "", mem)
                put(R.id.tmp_v, R.id.tmp_s, R.id.tmp_b, temp?.let { "%.0f°C".format(it) } ?: m?.optString("cpu_temp")?.ifEmpty { null } ?: "—", "CPU", temp)    // (a 100 °C bar)
                val disk = storageList(m).firstOrNull()
                put(R.id.dsk_v, R.id.dsk_s, R.id.dsk_b, disk?.let { "${it.pct.toInt()}%" } ?: "—", disk?.let { "${it.free} · ${it.name}" } ?: "", disk?.pct?.toDouble())
                setOnClickPendingIntent(R.id.w_root, open(ctx, "status", 2))
            }
            ActionsWidget::class.java -> RemoteViews(ctx.packageName, R.layout.widget_actions).apply {
                val fanOn = o?.optJSONObject("fan")?.optBoolean("on")
                setTextViewText(R.id.a_light_t, when (fanOn) { true -> "Light off"; false -> "Light on"; null -> "Light" })
                setOnClickPendingIntent(R.id.a_backup, broadcast(ctx, ActionsWidget::class.java, ACTION_RUN, "backup", 11))
                setOnClickPendingIntent(R.id.a_ram, broadcast(ctx, ActionsWidget::class.java, ACTION_RUN, "freeram", 12))
                setOnClickPendingIntent(R.id.a_light, broadcast(ctx, ActionsWidget::class.java, ACTION_RUN, "light", 13))
                setOnClickPendingIntent(R.id.a_refresh, broadcast(ctx, ActionsWidget::class.java, ACTION_REFRESH, "", 14))
            }
            InboxWidget::class.java -> RemoteViews(ctx.packageName, R.layout.widget_inbox).apply {
                header(ctx, this, level)
                val evs = snap(ctx, "events")?.optJSONArray("events").objs().take(3)
                val seen = Pairing(ctx).lastEventSeen
                val newOnes = snap(ctx, "events")?.optJSONArray("events").objs().count { it.optDouble("t") > seen && it.optString("level") in listOf("warning", "critical") }
                if (newOnes > 0) setTextViewText(R.id.w_age, "$newOnes new")
                setViewVisibility(R.id.in_empty, if (evs.isEmpty()) View.VISIBLE else View.GONE)
                val hm = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                listOf(Triple(R.id.ev0, R.id.ev0_t, R.id.ev0_w) to R.id.ev0_d, Triple(R.id.ev1, R.id.ev1_t, R.id.ev1_w) to R.id.ev1_d,
                       Triple(R.id.ev2, R.id.ev2_t, R.id.ev2_w) to R.id.ev2_d).forEachIndexed { i, (ids, dot) ->
                    val e = evs.getOrNull(i)
                    setViewVisibility(ids.first, if (e == null) View.GONE else View.VISIBLE)
                    if (e != null) { setTextViewText(ids.second, e.optString("title").replace(Rx.titleLead, ""))
                        setTextViewText(ids.third, hm.format(java.util.Date((e.optDouble("t") * 1000).toLong())))
                        setInt(dot, "setColorFilter", levelColor(ctx, e.optString("level"))) }
                }
                setOnClickPendingIntent(R.id.w_root, open(ctx, "inbox", 4))
            }
            else -> RemoteViews(ctx.packageName, R.layout.widget_storage).apply {
                header(ctx, this, level)
                val drives = storageList(m)
                listOf(R.id.dr0, R.id.dr1, R.id.dr2, R.id.dr3).forEachIndexed { i, row ->
                    val d = drives.getOrNull(i)
                    setViewVisibility(row, if (d == null) View.GONE else View.VISIBLE)
                    if (d != null) {
                        setTextViewText(listOf(R.id.dr0_n, R.id.dr1_n, R.id.dr2_n, R.id.dr3_n)[i], d.name)
                        setTextViewText(listOf(R.id.dr0_f, R.id.dr1_f, R.id.dr2_f, R.id.dr3_f)[i], "${d.pct.toInt()}% · ${d.free}")
                        setProgressBar(listOf(R.id.dr0_b, R.id.dr1_b, R.id.dr2_b, R.id.dr3_b)[i], 100, d.pct.toInt().coerceIn(0, 100), false)
                    }
                }
                setOnClickPendingIntent(R.id.w_root, open(ctx, "storage", 5))
            }
        }
    }

    /** A quick action from the widget: runs right away, says how it went, then refreshes. */
    fun run(ctx: Context, what: String, done: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val pairing = Pairing(ctx)
            val msg = if (!pairing.paired) "Open Nova and pair it first" else try {
                val api = NovaApi(pairing)
                when (what) {
                    "backup" -> { api.post("/api/v1/actions/backup"); "Backup started" }
                    "freeram" -> { api.post("/api/v1/actions/free-ram"); "Freed memory" }
                    "light" -> { val on = api.get("/api/v1/fan").optBoolean("on"); api.post("/api/v1/fan", JSONObject().put("on", !on)); if (on) "Fan light off" else "Fan light on" }
                    else -> "Refreshed"
                }
            } catch (e: ApiException) { if (e.code == 403) "This ${DeviceForm.noun} has view-only access" else e.message ?: "Couldn't reach the server" }
              catch (e: Exception) { "Couldn't reach the server" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() }
            runCatching { refresh(ctx) }
            done()
        }
    }
}

class WidgetWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = if (Widgets.refresh(applicationContext)) Result.success() else Result.retry()
}

/** What every Nova widget does: draw from the snapshot at once, then ask for fresh numbers; tap actions come back here. */
abstract class NovaWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, m: AppWidgetManager, ids: IntArray) { Widgets.render(ctx); Widgets.schedule(ctx); Widgets.refreshSoon(ctx) }
    override fun onEnabled(ctx: Context) { Widgets.schedule(ctx); Widgets.refreshSoon(ctx) }
    override fun onDisabled(ctx: Context) { Widgets.unscheduleIfNone(ctx) }
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Widgets.ACTION_REFRESH -> { val r = goAsync(); Widgets.run(ctx, "refresh") { r.finish() } }
            Widgets.ACTION_RUN -> { val r = goAsync(); Widgets.run(ctx, intent.getStringExtra("do") ?: "") { r.finish() } }
            else -> super.onReceive(ctx, intent)
        }
    }
}
class StatusWidget : NovaWidget()
class StatsWidget : NovaWidget()
class ActionsWidget : NovaWidget()
class InboxWidget : NovaWidget()
class StorageWidget : NovaWidget()
