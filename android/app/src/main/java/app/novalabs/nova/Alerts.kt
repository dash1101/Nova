package app.novalabs.nova

import android.os.Build
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
import org.json.JSONObject

/**
 * Alerts while Nova is closed.
 *
 * Android (Samsung especially) defers background jobs for hours, so the 15-minute worker alone
 * isn't enough. "Instant alerts" keeps one quiet connection per server open in a foreground
 * service: the server answers the moment a new event is written (about once a minute at most).
 * The worker stays as a backup for when instant alerts are off.
 */
object Alerts {
    /** When each server's instant-alert line last spoke (server profile → ms): the 15-minute check skips a server that's live. */
    val alive = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("nova_servers", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("instant_alerts", true)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean("instant_alerts", on).apply(); if (on) start(ctx) else stop(ctx) }

    fun start(ctx: Context) {
        if (!enabled(ctx) || Servers.all(ctx).none { Pairing(ctx, it).paired }) return
        runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, AlertService::class.java)) }
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
            val cat = e.optString("category")
            if (cat.startsWith("app-update:")) {           // a new app: only if this phone doesn't have it yet
                val code = cat.substringAfter(":").toIntOrNull() ?: 0
                if (code > androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)) && since > 0)
                    Notifier.post(ctx, 4242, "info", e.optString("title"), e.optString("detail"), open = "update")
                continue
            }
            if (cat == "update" && since > 0) {             // server update: always worth a nudge, whatever the level filter
                Notifier.post(ctx, 4243 xor pairing.profile.hashCode(), "info", if (many) "$name · ${e.optString("title")}" else e.optString("title"), e.optString("detail"), open = "update")
                continue
            }
            if (since <= 0 || !Notifier.atLeast(e.optString("level"), pairing.phoneNotifyLevel)) continue
            // "New device paired: <this phone>" right after this phone paired is about itself — no need to warn it (any other device still does)
            if (e.optString("title").startsWith("New device paired") && kotlin.math.abs(t * 1000 - pairing.pairedAt) < 120_000 &&
                e.optString("title").contains(android.os.Build.MODEL.filter { it.isLetterOrDigit() || it in " -_" }, ignoreCase = true)) continue
            if (pairing.quietNow() && e.optString("level") != "critical") continue          // quiet hours: it waits in the Inbox
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
    private val calls = java.util.concurrent.ConcurrentHashMap<String, okhttp3.Call>()   // each server's open line, to cut on a new network
    private var net: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A line left open on the old network can sit silent for minutes before it times out (4 min at home), so when the phone
        // switches networks (leaves home Wi-Fi, say) every line is cut and opened again at once on the new one.
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        var current: android.net.Network? = null
        net = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                val before = current; current = network
                if (before != null && before != network) calls.values.forEach { runCatching { it.cancel() } }
            }
        }.also { runCatching { cm.registerDefaultNetworkCallback(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("watch", "Instant alerts (silent)", NotificationManager.IMPORTANCE_MIN)
            .apply { description = "Keeps a quiet connection open so alerts arrive right away. You can hide this notification." })
        val open = PendingIntent.getActivity(this, 0, AppIcon.launch(this), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, "watch").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Watching your server").setContentText("Alerts arrive as they happen")
            .setOngoing(true).setSilent(true).setContentIntent(open).setPriority(NotificationCompat.PRIORITY_MIN).build()
        // If Android won't let us run in the foreground right now, step aside: the 15-minute worker covers it.
        try { if (Build.VERSION.SDK_INT >= 34) startForeground(41, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(41, n) }
        catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        for (id in Servers.all(this)) if (loops[id]?.isActive != true && Pairing(this, id).paired) loops[id] = scope.launch { watch(id) }
        return START_STICKY
    }

    private suspend fun watch(id: String) {
        var backoff = 5_000L
        val shown = mutableSetOf<String>()                 // approvals already notified (kept across reconnects)
        // One client for the whole watch, so each request reuses the open connection where it can
        // (the pinned LAN client follows pin changes on its own).
        val p = Pairing(applicationContext, id); val api = NovaApi(p)
        var pollUntil = 0L; var cutShort = 0               // long-poll instead of the open line until then (older Nova, or a proxy that cuts it)
        fun deliver(r: JSONObject) {
            Alerts.handle(applicationContext, p, r.optJSONArray("events"))
            if (r.has("tasks")) Notifier.tasks(applicationContext, id, r.optJSONArray("tasks"))
            r.optJSONArray("approvals")?.let { a -> for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                if (shown.add(o.optString("id"))) Notifier.approval(applicationContext, o.optString("id").hashCode(),
                    "Approve: ${o.optString("what")}?", "Requested by ${o.optString("device_name")}${o.optString("user").takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""}" +
                        if (p.quickApproverId.isNotEmpty()) "" else " — tap to review", approvalId = o.optString("id"), profile = id) } }
        }
        while (currentCoroutineContext().isActive) {
            if (!p.paired) return
            try {
                if (System.currentTimeMillis() >= pollUntil) {
                    // The open line: the server writes each alert the moment it happens and otherwise only a small heartbeat
                    // (80 s through Cloudflare, 4 min at home) — far fewer packets than asking again every 50 s, which
                    // phones count against the app.
                    var heard = false; var bye = ""; var call: okhttp3.Call? = null
                    try {
                        api.stream("/api/v1/events/stream?since=${p.lastEventSeen}", { call = it; calls[id] = it }) { line ->
                            Alerts.alive[id] = System.currentTimeMillis()
                            if (line == ":") heard = true
                            else if (line.startsWith("data:")) {
                                val o = runCatching { JSONObject(line.substring(5).trim()) }.getOrNull() ?: return@stream
                                when {
                                    o.has("beat") -> {}                                 // hello: how often it checks in
                                    o.has("reason") -> bye = o.optString("reason")
                                    else -> { heard = true; deliver(o) }
                                }
                            }
                        }
                    } catch (e: NovaApi.StreamEnded) { }                   // dropped (or cut for a new network): open it again
                    catch (e: ApiException) { if (e.code != 404) throw e; pollUntil = System.currentTimeMillis() + 6 * 3600_000L; continue }
                    finally { calls.remove(id) }
                    backoff = 5_000L
                    // A proxy that closes quiet answers sooner than the heartbeat (or holds the answer back until the end) would turn
                    // this into a loop that hears nothing: after two lines that ended without a single heartbeat or alert, ask the old
                    // way for six hours. (A line cut on purpose for a new network doesn't count.)
                    val cut = call?.isCanceled() == true
                    if (heard) cutShort = 0
                    else if (!cut && bye.isEmpty() && ++cutShort >= 2) { pollUntil = System.currentTimeMillis() + 6 * 3600_000L; cutShort = 0 }
                    if (bye == "replaced") delay(5 * 60_000L)              // another copy of this app has the line: don't fight over it
                    else if (!heard) delay(if (cut) 1_000L else 5_000L)    // (never spin)
                    continue
                }
                val r = api.get("/api/v1/events/wait?since=${p.lastEventSeen}&timeout=50&seen=${shown.joinToString(",")}")
                Alerts.alive[id] = System.currentTimeMillis()
                deliver(r)
                backoff = 5_000L
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                delay(backoff); backoff = (backoff * 2).coerceAtMost(5 * 60_000L)    // offline: back off, don't drain battery
            }
        }
    }

    override fun onDestroy() {
        net?.let { n -> runCatching { getSystemService(android.net.ConnectivityManager::class.java)?.unregisterNetworkCallback(n) } }
        calls.values.forEach { runCatching { it.cancel() } }
        scope.cancel(); super.onDestroy()
    }
}

/** Bring instant alerts back after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) { Alerts.start(ctx) }
}

