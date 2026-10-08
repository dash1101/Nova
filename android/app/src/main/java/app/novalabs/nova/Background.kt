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

const val APP_VERSION = "0.4.1-alpha"

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

    /** A browser asked for something risky: open Nova's Approvals screen. */
    fun approval(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("approvals", "Approval requests", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A paired browser wants to do something that needs your fingerprint" })
        val open = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).putExtra("open", "approvals")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, "approvals").setSmallIcon(R.drawable.ic_notification).setColor(0xFF3E91FF.toInt())
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_RECOMMENDATION).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun post(ctx: Context, id: Int, level: String, title: String, text: String) {
        val ch = if (level == "resolved") "info" else level
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("open", "inbox"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, ch).setSmallIcon(R.drawable.ic_notification).setColor(0xFF3E91FF.toInt())
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
