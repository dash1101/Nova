package app.novalabs.nova

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Last good answer for every GET, in memory and on disk (app-private storage, excluded from
 * backups). Screens draw from it instantly — no blank flash — and refresh in the background.
 */
object Cache {
    private val mem = ConcurrentHashMap<String, JSONObject>()
    private var file: File? = null
    private val io = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var dirty = false
    // remote-config carries the Cloudflare secret, which only lives Keystore-encrypted in Pairing.
    private val NEVER = listOf("/api/v1/shell/", "/api/v1/jobs", "/api/v1/app/", "/api/v1/remote-config", "/api/v1/events/wait")

    private var profile: String? = null
    private fun fileFor(ctx: Context, p: String) = File(ctx.filesDir, if (p.isEmpty()) "cache.json" else "cache_$p.json")

    /** Switch to a server's cache (each server has its own file). */
    @Synchronized fun use(ctx: Context, p: String) {
        if (profile == p && file != null) return
        if (dirty) flush()
        mem.clear(); profile = p; file = fileFor(ctx, p)
        runCatching {
            val o = JSONObject(file!!.readText())
            o.keys().forEach { k -> if (NEVER.none { k.startsWith(it) }) mem[k] = o.getJSONObject(k) }
            if (o.has("/api/v1/remote-config")) { dirty = true; io.schedule({ flush() }, 1, TimeUnit.SECONDS) }   // scrub 0.2.5's copy
        }
    }

    operator fun get(path: String): JSONObject? = mem[path]

    /** Only the server on screen writes to the cache (background checks of other servers don't). */
    fun isFor(p: String) = profile == p

    fun put(path: String, obj: JSONObject) {
        if (NEVER.any { path.startsWith(it) }) return
        if ("since=" in path && !path.endsWith("since=0")) return        // one-off "what's new" polls
        mem[path] = obj
        if (!dirty) { dirty = true; io.schedule({ flush() }, 2, TimeUnit.SECONDS) }
    }

    private fun flush() {
        dirty = false
        val f = file ?: return
        runCatching {
            val o = JSONObject(); mem.forEach { (k, v) -> o.put(k, v) }
            val s = o.toString()
            if (s.length < 3_000_000) { val tmp = File(f.path + ".tmp"); tmp.writeText(s); tmp.renameTo(f) }
        }
    }

    fun drop(ctx: Context, p: String) {
        runCatching { fileFor(ctx, p).delete() }
        if (p == profile) mem.clear()
    }
}

/**
 * Live data for a screen: starts with the cached copy, fetches on open and every [everyMs]
 * while the app is in front (pauses in the background, refreshes right away on return).
 */
@Composable fun live(app: AppState, path: String, everyMs: Long = 0): MutableState<JSONObject?> {
    val st = remember(path) { mutableStateOf(Cache[path]) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(path, everyMs) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                runCatching { app.api.get(path) }.onSuccess { st.value = it }
                if (everyMs <= 0) break
                delay(everyMs)
            }
        }
    }
    return st
}
