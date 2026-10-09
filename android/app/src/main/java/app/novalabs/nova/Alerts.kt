package app.novalabs.nova

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray

/**
 * Alerts while Nova is closed.
 *
 * Android (Samsung especially) defers background jobs for hours, so the 15-minute worker alone
 * isn't enough. "Instant alerts" keeps one quiet connection per server open in a foreground
 * service: the server answers the moment a new event is written (about once a minute at most).
 * The worker stays as a backup for when instant alerts are off.
 */
object Alerts {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_servers", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("instant_alerts", true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("instant_alerts", on).apply(); if (on) start(ctx) else stop(ctx) }

    fun start(ctx: Context) {
        if (!enabled(ctx) || Servers.all(ctx).none { Pairing(ctx, it).paired }) return
        runCatching { ctx.startForegroundService(Intent(ctx, AlertService::class.java)) }
    }
    fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AlertService::class.java)) }

    /** Show events newer than what this phone has seen, at/above its chosen level. One place, so
     *  the service and the worker can't both notify the same event. */
    @Synchronized fun handle(ctx: Context, pairing: Pairing, ev: JSONArray?) {
        if (ev == null) return
        runCatching { InboxArchive.merge(ctx, pairing.profile, ev) }     // the phone's permanent history
        val since = pairing.lastEventSeen
        val many = Servers.all(ctx).count { Pairing(ctx, it).paired } > 1
        val name = pairing.label.ifEmpty { "Nova" }
        var newest = since
        // Old news (phone was offline, or Nova just updated) is summed up in one notification
        // instead of a burst of stale alerts; only fresh events get their own.
        val fresh = System.currentTimeMillis() / 1000.0 - 45 * 60
        var stale = 0
        for (i in (0 until ev.length()).reversed()) {
            val e = ev.getJSONObject(i); val t = e.optDouble("t")
            if (t <= since) continue
            if (t > newest) newest = t
            if (since <= 0 || !Notifier.atLeast(e.optString("level"), pairing.phoneNotifyLevel)) continue
            if (t < fresh) { stale++; continue }
            Notifier.post(ctx, (t * 1000).toLong().toInt() xor pairing.profile.hashCode(), e.optString("level"),
                if (many) "$name · ${e.optString("title")}" else e.optString("title"), e.optString("detail"))
        }
        if (stale > 0) Notifier.post(ctx, 7 xor pairing.profile.hashCode(), "info",
            "$stale earlier alert${if (stale > 1) "s" else ""}${if (many) " on $name" else ""}", "From while Nova wasn't watching — open the Inbox to see them.")
        if (newest > since) pairing.lastEventSeen = newest
    }
}

class AlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loops = mutableMapOf<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "Instant alerts (silent)", NotificationManager.IMPORTANCE_MIN)
            .apply { description = "Keeps a quiet connection open so alerts arrive right away. You can hide this notification." })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "watch").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watching your server").setContentText("Alerts arrive as they happen")
            .setOngoing(true).setSilent(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_MIN).build()
        // If Android won't let us run in the foreground right now, step aside: the 15-minute worker covers it.
        try { startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        for (id in Servers.all(this)) if (loops[id]?.isActive != true && Pairing(this, id).paired) loops[id] = scope.launch { watch(id) }
        return START_STICKY
    }

    private suspend fun watch(id: String) {
        var backoff = 5_000L
        val shown = mutableSetOf<String>()                 // approvals already notified
        while (currentCoroutineContext().isActive) {
            val p = Pairing(applicationContext, id)
            if (!p.paired) return
            try {
                val r = NovaApi(p).get("/api/v1/events/wait?since=${p.lastEventSeen}&timeout=50&seen=${shown.joinToString(",")}")
                Alerts.handle(applicationContext, p, r.optJSONArray("events"))
                r.optJSONArray("approvals")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                    if (shown.add(o.optString("id"))) Notifier.approval(applicationContext, o.optString("id").hashCode(),
                        "Approve: ${o.optString("what")}?", "Requested by ${o.optString("device_name")}${o.optString("user").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""} — tap to review") } }
                backoff = 5_000L
            } catch (e: Exception) {
                delay(backoff); backoff = (backoff * 2).coerceAtMost(5 * 60_000L)    // offline: back off, don't drain battery
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Bring instant alerts back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Alerts.start(ctx) }
}
