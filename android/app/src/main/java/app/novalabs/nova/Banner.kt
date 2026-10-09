package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


/** New alert while Nova is open: slides in at the top; tap for the Inbox, swipe or × to dismiss. */
@Composable fun AlertBanner(app: AppState, modifier: Modifier = Modifier) {
    val e = app.banner
    var shown by remember { mutableStateOf<org.json.JSONObject?>(null) }
    if (e != null) shown = e
    LaunchedEffect(app.bannerAt) {
        if (e == null) return@LaunchedEffect
        kotlinx.coroutines.delay(if (e.optString("level") in listOf("warning", "critical")) 12_000 else 6_000); app.banner = null
    }
    androidx.compose.animation.AnimatedVisibility(e != null, modifier.statusBarsPadding().padding(top = 8.dp, start = 12.dp, end = 12.dp).widthIn(max = 560.dp),
        enter = if (reduceMotion()) androidx.compose.animation.fadeIn() else androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn(),
        exit = if (reduceMotion()) androidx.compose.animation.fadeOut() else androidx.compose.animation.slideOutVertically { -it } + androidx.compose.animation.fadeOut()) {
        val b = shown ?: return@AnimatedVisibility
        Row(Modifier.fillMaxWidth().glassCard(androidx.compose.foundation.shape.RoundedCornerShape(if (N.material) 20.dp else 24.dp))
            .clickable { app.banner = null; app.go(Route.Inbox) }
            .pointerInput(Unit) { detectVerticalDragGestures { _, dy -> if (dy < -8) app.banner = null } }
            .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp).size(9.dp).clip(CircleShape).background(levelColor(b.optString("level"), N)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.optString("title").replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), ""), color = N.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                val sub = listOf(b.optString("detail"), if (app.bannerMore > 0) "and ${app.bannerMore} more" else "").filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, color = N.sub, fontSize = 13.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.Close, "Dismiss", tint = N.sub, modifier = Modifier.size(20.dp).clickable { app.banner = null })
        }
    }
}
