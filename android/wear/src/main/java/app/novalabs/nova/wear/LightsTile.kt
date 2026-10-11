package app.novalabs.nova.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future

/** A tile for the server's lights: one big button, tap to switch them on or off. */
class LightsTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
        val store = WearStore(this@LightsTile)
        val on = if (store.paired) runCatching { WearApi(store).get("/api/v1/fan").optBoolean("on") }.getOrNull() else null
        TileBuilders.Tile.Builder().setResourcesVersion("1").setFreshnessIntervalMillis(15 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(store, on))).build()
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future { ResourceBuilders.Resources.Builder().setVersion("1").build() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = LayoutElementBuilders.Text.Builder().setText(s).setMaxLines(2)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
            .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build()).build()

    private fun layout(store: WearStore, on: Boolean?): LayoutElementBuilders.LayoutElement {
        // tapping opens Nova just long enough to switch them (it signs the request with the watch's key)
        val act = ActionBuilders.LaunchAction.Builder().setAndroidActivity(ActionBuilders.AndroidActivity.Builder().setPackageName(packageName)
            .setClassName("app.novalabs.nova.wear.WearActivity")
            .addKeyToExtraMapping("action", ActionBuilders.AndroidStringExtra.Builder().setValue(if (store.paired) "lights" else "").build()).build()).build()
        val click = ModifiersBuilders.Clickable.Builder().setId("lights").setOnClick(act).build()
        val color = when (on) { true -> 0xFFFFB340.toInt(); false -> 0xFF3A3A3F.toInt(); null -> 0xFF2A2A2E.toInt() }
        val button = LayoutElementBuilders.Box.Builder().setWidth(dp(86f)).setHeight(dp(86f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER).setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(click).setBackground(ModifiersBuilders.Background.Builder().setColor(argb(color))
                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(43f)).build()).build()).build())
            .addContent(text(when (on) { true -> "On"; false -> "Off"; null -> "—" }, 20f, if (on == true) 0xFF1B1200.toInt() else 0xFFFFFFFF.toInt(), bold = true)).build()
        val col = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text("Lights", 14f, 0xFFB0B0B8.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(button)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
            .addContent(text(when { !store.paired -> "Open Nova to pair"; on == null -> "Can't reach the server"; else -> "Tap to turn ${if (on) "off" else "on"}" }, 12f, 0xFFB0B0B8.toInt()))
        return LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand()).addContent(col.build()).build()
    }
}
