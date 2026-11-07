package app.novalabs.nova

import androidx.compose.runtime.*
import kotlinx.coroutines.delay

/** One shared heartbeat for the slow, always-on animations (the assistant's orb, the server picture's fan and lights).
 *  Each of them used to run its own timer, so the window repainted once for each — about twice as often as it needed to, on every page.
 *  Now they all move on the same tick, and it only ticks while one of them is on screen. */
object Ambient {
    var ms by mutableLongStateOf(System.currentTimeMillis())
    var calm by mutableIntStateOf(0)                      // on screen and fine with a slow tick (the orb, idle)
    var lively by mutableIntStateOf(0)                    // …wants a steady one (the fan and lights in the server picture)
    var quick by mutableIntStateOf(0)                     // …wants a smooth one (the orb while Nova is busy)
}

/** Call from a composable that animates on the heartbeat (0 calm, 1 lively, 2 quick); read [Ambient.ms] where it draws. */
@Composable fun UseAmbient(level: Int = 1) {
    DisposableEffect(level) {
        when (level) { 0 -> Ambient.calm++; 1 -> Ambient.lively++; else -> Ambient.quick++ }
        onDispose { when (level) { 0 -> Ambient.calm--; 1 -> Ambient.lively--; else -> Ambient.quick-- } }
    }
}

/** Beats while anything is using it (once, near the top of the app). */
@Composable fun AmbientTicker() {
    val level = if (Ambient.quick > 0) 2 else if (Ambient.lively > 0) 1 else if (Ambient.calm > 0) 0 else -1; val slow = lite()
    LaunchedEffect(level, slow) {
        if (level < 0) return@LaunchedEffect
        // as slow as whatever is on screen allows: just the idle orb ≈ 8 a second (20 on a fast device); the fan's lights ≈ 15 (30);
        // Nova working ≈ 30 (60). The whole window repaints on every beat, so every beat counts.
        val gap = when (level) { 2 -> if (slow) 33L else 12L; 1 -> if (slow) 66L else 30L; else -> if (slow) 125L else 50L }
        while (true) { withFrameNanos { Ambient.ms = System.currentTimeMillis() }; delay(gap) }
    }
}
