package app.novalabs.nova

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Nova's own releases, on GitHub. Used when this phone isn't connected to a server (signed out, or not paired yet), so an
 * out-of-date app can still update itself. Android only installs an APK that is signed by the same key as this app, and the
 * file is also checked against the release's published checksum first.
 */
object Releases {
    const val REPO = "dash1101/Nova"
    data class Release(val version: String, val notes: String, val apkUrl: String, val apkName: String, val sums: String)

    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private val DIGITS = Regex("\\d+")
    private fun numbers(v: String) = DIGITS.findAll(v.substringBefore('-')).map { it.value.toIntOrNull() ?: 0 }.toList()
    fun isNewer(tag: String): Boolean {
        val a = numbers(tag.removePrefix("v")); val b = numbers(APP_VERSION)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    /** The newest release (null: couldn't ask). */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "Nova app").build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching null
                val o = JSONObject(r.body.string()); val assets = o.getJSONArray("assets")
                val list = (0 until assets.length()).map { assets.getJSONObject(it) }
                val arm = android.os.Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
                val apk = list.firstOrNull { it.optString("name").endsWith(if (arm) "-arm64.apk" else "-universal.apk") }
                    ?: list.firstOrNull { it.optString("name").endsWith("-universal.apk") } ?: return@runCatching null
                val sums = list.firstOrNull { it.optString("name") == "SHA256SUMS" }?.optString("browser_download_url").orEmpty()
                Release(o.optString("tag_name").removePrefix("v"), o.optString("body").trim(), apk.optString("browser_download_url"), apk.optString("name"), sums)
            }
        }.getOrNull()
    }

    /** Downloads, checks the checksum, and hands it to Android's installer. [progress]: 0..100. */
    suspend fun install(ctx: android.content.Context, rel: Release, progress: (Int) -> Unit) = withContext(Dispatchers.IO) {
        if (rel.sums.isEmpty()) throw ApiException(0, "This release has no checksum list — not installing it.")
        val sums = http.newCall(Request.Builder().url(rel.sums).header("User-Agent", "Nova app").build()).execute().use { it.body.string() }
        val want = sums.lineSequence().map { it.trim().split(Rx.spaces) }.firstOrNull { it.size >= 2 && it[1].removePrefix("*") == rel.apkName }?.get(0)
            ?: throw ApiException(0, "The checksum list doesn't mention ${rel.apkName} — not installing it.")
        val md = MessageDigest.getInstance("SHA-256")
        val out = java.io.ByteArrayOutputStream()
        http.newCall(Request.Builder().url(rel.apkUrl).header("User-Agent", "Nova app").build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "Couldn't download the update (${r.code}).")
            val total = r.body.contentLength(); val buf = ByteArray(64 * 1024); var got = 0L
            r.body.byteStream().use { ins ->
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); md.update(buf, 0, n); got += n; if (total > 0) progress((got * 100 / total).toInt()) }
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        if (!sha.equals(want, true)) throw ApiException(0, "The update didn't match its checksum — not installing it.")
        Updater.installBytes(ctx, out.toByteArray())
    }
}

/**
 * Settings that work with no server: the pairing screen's gear button opens this. Updates (from Nova's public releases),
 * how the app looks, its lock, and help — everything else shows up once the phone is paired.
 * It borrows the app's normal page stack for the Appearance and FAQ pages, so they look and behave as they do inside.
 */
@Composable fun OfflineSettings(app: AppState, onClose: () -> Unit) {
    DisposableEffect(Unit) { onDispose { while (app.stack.size > 1) app.stack.removeAt(app.stack.lastIndex) } }
    BackHandler { if (app.stack.size > 1) app.back() else onClose() }
    val top = app.top
    if (top == Route.Home) OfflineSettingsMain(app, onClose) else Screen(app, top)
}

@Composable private fun OfflineSettingsMain(app: AppState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var rel by remember { mutableStateOf<Releases.Release?>(null) }
    var checked by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var pct by remember { mutableIntStateOf(-1) }
    var err by remember { mutableStateOf<String?>(null) }
    fun check() { if (checking) return; checking = true; err = null
        app.scope.launch { rel = Releases.latest(); checked = true; checking = false; if (rel == null) err = "Couldn't reach GitHub — check this ${DeviceForm.noun}'s internet connection." } }
    LaunchedEffect(Unit) { check() }
    val newer = rel?.let { Releases.isNewer(it.version) } == true
    Page("Settings", onClose) {
        Note("These work without a server. Everything else appears once this ${DeviceForm.noun} is paired.", 14.sp, 6.dp)
        SectionLabel("Update Nova")
        Group {
            Row1(if (newer) "Update to ${rel!!.version}" else if (checked && rel != null) "Nova is up to date" else "Check for updates",
                when { pct in 0..99 -> "Downloading… $pct%"; checking -> "Checking…"; err != null -> err; newer -> "Version ${rel!!.version} is ready — tap to install"
                       checked && rel != null -> "Version $APP_VERSION"; else -> "From Nova's public releases on GitHub — no server needed" },
                newer, Icons.Rounded.SystemUpdate, onClick = {
                    if (newer && pct < 0) {
                        if (android.os.Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
                            app.toast("Allow Nova to install updates, then tap again")
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${ctx.packageName}")))
                        } else app.scope.launch {
                            pct = 0
                            try { Releases.install(ctx, rel!!) { pct = it }; app.toast("Installing — follow Android's prompt") } catch (e: Exception) { err = e.message ?: "The update didn't work" } finally { pct = -1 }
                        }
                    } else check() })
            if (newer && rel!!.notes.isNotEmpty()) { RowDivider(); Row1("What's new", rel!!.notes.lineSequence().filter { it.isNotBlank() }.take(6).joinToString("\n") { it.trim().removePrefix("- ").removePrefix("* ").replace("**", "") }, false, Icons.Rounded.AutoAwesome) }
        }
        Note("Android installs it only if it's signed with the same key as this app, and Nova checks the file against the release's checksum first.", 13.sp, 4.dp)
        SectionLabel("This ${DeviceForm.noun}")
        Group {
            Row1("Appearance", "Style, light or dark, colors, animation, sounds", true, Icons.Rounded.Palette, onClick = { app.go(Route.Appearance) })
            RowDivider()
            SwitchRow("App lock", "Fingerprint or PIN to open Nova, and again after 5 minutes away", AppPrefs.appLock) { on ->
                if (on) AppLock.prompt(app.activity) { ok -> if (ok) AppPrefs.set("app_lock", true) } else AppPrefs.set("app_lock", false) }
            RowDivider()
            SwitchRow("Hide in recent apps", "Blank preview in the app switcher; screenshots are blocked", AppPrefs.hideInRecents) { AppPrefs.set("hide_recents", it); applySecureFlag(app.activity) }
        }
        SectionLabel("Help")
        Group {
            Row1("Questions and answers", "How Nova works, what's safe, how to undo things", true, Icons.Rounded.HelpOutline, onClick = { app.go(Route.Faq) })
            RowDivider()
            Row1("Privacy policy", "Nova collects nothing. The app talks only to your server", false, Icons.Rounded.Shield, N.green, onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://dash1101.github.io/Nova/privacy.html"))) } })
            RowDivider()
            Row1("About", "Nova $APP_VERSION · Android ${android.os.Build.VERSION.RELEASE}", false, Icons.Rounded.Info, N.sub)
        }
        Spacer(Modifier.height(30.dp))
    }
}
