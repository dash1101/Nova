package app.novalabs.nova

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

val APP_VERSION: String = BuildConfig.VERSION_NAME

class NovaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        AppPrefs.init(this)
        Notifier.createChannels(this)
        val work = PeriodicWorkRequestBuilder<InboxWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("nova-inbox", ExistingPeriodicWorkPolicy.KEEP, work)
    }
}

/** Saves uncaught crashes to a file and sends them to the server on the next launch. */
object CrashReporter {
    private fun file(ctx: Context) = File(ctx.filesDir, "last-crash.txt")
    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                file(ctx).writeText("Nova $APP_VERSION · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n" +
                        "thread: ${t.name}\n\n" + e.stackTraceToString())
            }
            prev?.uncaughtException(t, e)
        }
    }
    suspend fun flush(ctx: Context, pairing: Pairing, api: NovaApi?) {
        val f = file(ctx); if (!f.exists()) return
        val report = f.readText().take(60000)
        val ok = if (api != null && pairing.paired)
            runCatching { api.post("/api/v1/crash", JSONObject().put("report", report).put("version", APP_VERSION)) }.isSuccess
        else false        // not paired yet: keep it and send after pairing
        if (ok) f.delete()
    }
}

object Notifier {
    private val levels = listOf("info", "warning", "critical")
    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("critical", "Critical alerts", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A drive dropped out, a service is down…" })
        nm.createNotificationChannel(NotificationChannel("warning", "Warnings", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("info", "Info & logins", NotificationManager.IMPORTANCE_LOW))
    }
    fun atLeast(level: String, min: String) =
        levels.indexOf(if (level == "resolved") "info" else level) >= levels.indexOf(min)

    /** Something running on the server (install, backup, update…): a quiet notification with its progress,
     *  replaced by a short "done"/"failed" one when it ends. */
    private val progressShown = mutableSetOf<String>()
    fun tasks(ctx: Context, profile: String, list: org.json.JSONArray?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("progress", "Progress", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Installs, backups and updates while they run" })
        val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java).putExtra("open", "inbox")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val now = mutableSetOf<String>()
        for (i in 0 until (list?.length() ?: 0)) {
            val t = list!!.getJSONObject(i); val key = profile + t.optString("id"); val nid = key.hashCode()
            val running = t.optString("state") == "running"
            if (!running && key !in progressShown) continue                  // ended before we saw it: the Inbox has it
            now += key
            val b = NotificationCompat.Builder(ctx, "progress").setSmallIcon(R.drawable.ic_notification).setColor(0xFF6E56CF.toInt())
                .setContentTitle(t.optString("title")).setContentIntent(open).setSilent(true).setOnlyAlertOnce(true)
            if (running) b.setOngoing(true).setProgress(100, t.optDouble("pct", 0.0).toInt(), t.optDouble("pct", 0.0) <= 0.0)
                .setContentText(t.optString("note").ifEmpty { t.optString("step") })
            else { b.setContentText(when (t.optString("state")) { "done" -> "Done"; "stopped" -> "Stopped"; else -> "Failed: ${t.optString("error").take(120)}" }).setAutoCancel(true).setTimeoutAfter(60_000) }
            runCatching { nm.notify(nid, b.build()) }
            if (running) progressShown += key else progressShown -= key
        }
        // anything we were showing that the server stopped reporting: clear it
        progressShown.filter { it.startsWith(profile) && it !in now }.forEach { nm.cancel(it.hashCode()); progressShown -= it }
    }

    /** A browser asked for something risky: open Nova's Approvals screen. */
    fun approval(ctx: Context, id: Int, title: String, text: String, approvalId: String = "", profile: String = "") {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("approvals", "Approval requests", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A paired browser wants to do something that needs your fingerprint" })
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).putExtra("open", "approvals")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, "approvals").setSmallIcon(R.drawable.ic_notification).setColor(0xFF6E56CF.toInt())
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .apply {
                // Opt-in quick approve: Approve / Deny right on the notification (and on a paired watch)
                if (approvalId.isNotEmpty() && Pairing(ctx, profile).quickApproverId.isNotEmpty()) {
                    fun act(ok: Boolean) = PendingIntent.getBroadcast(ctx, (approvalId + ok).hashCode(),
                        Intent(ctx, ApprovalReceiver::class.java).putExtra("approval", approvalId).putExtra("profile", profile).putExtra("ok", ok).putExtra("nid", id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    addAction(0, "Approve", act(true)); addAction(0, "Deny", act(false))
                    extend(NotificationCompat.WearableExtender().addAction(NotificationCompat.Action(0, "Approve", act(true))).addAction(NotificationCompat.Action(0, "Deny", act(false))))
                }
            }.build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    /** Replace an approval notification with the outcome. */
    fun approvalDone(ctx: Context, id: Int, text: String) {
        val n = NotificationCompat.Builder(ctx, "approvals").setSmallIcon(R.drawable.ic_notification).setColor(0xFF6E56CF.toInt())
            .setContentTitle(text).setAutoCancel(true).setTimeoutAfter(15_000).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun post(ctx: Context, id: Int, level: String, title: String, text: String, open: String = "inbox") {
        val ch = if (level == "resolved") "info" else level
        val open = PendingIntent.getActivity(ctx, if (open == "inbox") 0 else 9, Intent(ctx, MainActivity::class.java).putExtra("open", open),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, ch).setSmallIcon(R.drawable.ic_notification).setColor(0xFF6E56CF.toInt())
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setGroup("nova").build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}

/** Every ~15 min: fetch new server events and show the ones at/above the phone's chosen level. */
class InboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        var retry = false
        for (id in Servers.all(applicationContext)) {           // every paired server
            val pairing = Pairing(applicationContext, id)
            if (pairing.paired && !check(pairing)) retry = true
        }
        return if (retry) Result.retry() else Result.success()
    }

    private suspend fun check(pairing: Pairing): Boolean = try {
        val ev = NovaApi(pairing).get("/api/v1/events?since=${pairing.lastEventSeen}").optJSONArray("events")
        Alerts.handle(applicationContext, pairing, ev); true
    } catch (e: Exception) { false }
}

/** Quick Settings tile: fan light on/off. */
class FanTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onStartListening() {
        val pairing = Pairing(this); if (!pairing.paired) { qsTile?.state = Tile.STATE_UNAVAILABLE; qsTile?.updateTile(); return }
        scope.launch {
            runCatching { NovaApi(pairing).get("/api/v1/fan") }.onSuccess { f ->
                qsTile?.state = if (f.optBoolean("on")) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                qsTile?.subtitle = if (f.optBoolean("on")) "${f.optInt("brightness")}%" else "Off"
                qsTile?.updateTile()
            }
        }
    }
    override fun onClick() {
        val pairing = Pairing(this); if (!pairing.paired) return
        val turnOn = qsTile?.state != Tile.STATE_ACTIVE
        qsTile?.state = if (turnOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; qsTile?.updateTile()
        scope.launch { runCatching { NovaApi(pairing).post("/api/v1/fan", JSONObject().put("on", turnOn)) } }
    }
}

/** In-app updater: download from the server, verify, hand to the system installer. */
object Updater {
    suspend fun latest(api: NovaApi): JSONObject? =
        runCatching { api.get("/api/v1/app/latest") }.getOrNull()?.takeIf { it.optInt("version_code", 0) > 0 }

    fun isNewer(meta: JSONObject, ctx: Context): Boolean {
        val mine = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
        return meta.optLong("version_code") > mine
    }

    suspend fun install(ctx: Context, api: NovaApi, meta: JSONObject) {
        val bytes = api.download("/api/v1/app/apk")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (sha != meta.optString("sha256")) throw ApiException(0, "Update file didn't match its checksum — not installing.")
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(ctx.packageName) }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("nova.apk", 0, bytes.size.toLong()).use { it.write(bytes); s.fsync(it) }
            val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(cb.intentSender)
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** The Approve / Deny buttons on an approval notification (on the phone or a paired watch). */
class ApprovalReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("approval") ?: return
        val ok = intent.getBooleanExtra("ok", false); val nid = intent.getIntExtra("nid", 0)
        val p = Pairing(ctx, intent.getStringExtra("profile") ?: "")
        if (p.quickApproverId.isEmpty()) return
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val r = NovaApi(p).quickDecide(id, ok)
                Notifier.approvalDone(ctx, nid, if (!ok) "Denied" else if (r.optBoolean("ok", true)) "Approved ✓" else "Approved, but it failed: ${r.optJSONObject("result")?.optString("error") ?: ""}")
            } catch (e: Exception) { Notifier.approvalDone(ctx, nid, "Couldn't ${if (ok) "approve" else "deny"}: ${e.message}") }
            finally { pending.finish() }
        }
    }
}
