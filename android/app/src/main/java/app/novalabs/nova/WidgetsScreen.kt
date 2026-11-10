package app.novalabs.nova

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Menu → Home-screen widgets: each widget as it looks right now, and a button that puts it on the home screen. */
@Composable fun WidgetsScreen(app: AppState) {
    val ctx = LocalContext.current
    val mgr = remember { AppWidgetManager.getInstance(ctx) }
    val canPin = Build.VERSION.SDK_INT >= 26 && runCatching { mgr.isRequestPinAppWidgetSupported }.getOrDefault(false)
    var version by remember { mutableIntStateOf(0) }
    val sample = remember { ctx.getSharedPreferences("nova_dev", 0).getBoolean("widget_demo", false) }      // (for making the widget pictures)
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { Widgets.refresh(ctx.applicationContext) } }; version++ }     // fresh numbers in the previews
    Page("Home-screen widgets", app::back) {
        Note(if (canPin) "Tap Add under a widget and your home screen takes it from there. You can also hold an empty spot on the home screen, choose Widgets and find Nova."
             else "To add one: hold an empty spot on your home screen, choose Widgets, and find Nova.", 14.sp, 6.dp)
        Note("They show the server you're on in the app, refresh about every 15 minutes (and whenever you open Nova), and open the matching page when tapped.", 13.sp, 2.dp)
        Widgets.KINDS.forEach { k ->
            SectionLabel(k.name)
            Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(k.about, color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp))
                // the real thing, drawn by the same code the home screen uses (these are pictures, not buttons)
                Box(Modifier.size(k.w.dp, k.h.dp).clip(RoundedCornerShape(22.dp))) {
                    key(version, N.dark) {
                        AndroidView(factory = { c -> (object : FrameLayout(c) {
                            override fun onInterceptTouchEvent(ev: android.view.MotionEvent) = true     // nothing inside is tappable…
                            @android.annotation.SuppressLint("ClickableViewAccessibility")
                            override fun onTouchEvent(ev: android.view.MotionEvent) = false             // …and the page still scrolls over it
                        }).apply {
                            runCatching { addView(Widgets.build(c.applicationContext, k.cls, sample).apply(c.applicationContext, this),
                                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) } } },
                            modifier = Modifier.fillMaxSize())
                    }
                }
                val placed = remember(version) { mgr.getAppWidgetIds(ComponentName(ctx, k.cls)).size }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canPin) PillButton("Add to home screen") {
                        val ok = runCatching { mgr.requestPinAppWidget(ComponentName(ctx, k.cls), null, null) }.getOrDefault(false)
                        if (!ok) app.toast("Your home screen didn't take it — hold an empty spot there and choose Widgets instead") }
                    if (placed > 0) Text(if (placed == 1) "  On your home screen" else "  $placed on your home screen", color = N.green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Note("Quick actions run without opening the app: Back up now, Free RAM and the fan light are things any paired ${DeviceForm.noun} may do. Anything that needs your fingerprint stays in the app.", 13.sp, 10.dp)
    }
}
