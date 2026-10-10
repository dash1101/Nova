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
import org.json.JSONObject

/** A tile with the server's health at a glance: a colored dot, the headline and a few numbers. Tap → Nova. */
class StatusTile : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
        val store = WearStore(this@StatusTile)
        val ov = if (store.paired) runCatching { WearApi(store).get("/api/v1/overview").also { store.lastOverview = it.toString() } }.getOrNull()
            ?: runCatching { JSONObject(store.lastOverview) }.getOrNull() else null
        TileBuilders.Tile.Builder().setResourcesVersion("1").setFreshnessIntervalMillis(10 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(store, ov))).build()
    }

    override fun onTileResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future { ResourceBuilders.Resources.Builder().setVersion("1").build() }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 1) = LayoutElementBuilders.Text.Builder().setText(s).setMaxLines(lines)
        .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color))
            .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL).build()).build()

    private fun layout(store: WearStore, ov: JSONObject?): LayoutElementBuilders.LayoutElement {
        val st = ov?.optJSONObject("status"); val lvl = st?.optString("level") ?: "ok"; val m = st?.optJSONObject("metrics"); val cs = ov?.optJSONObject("containers")
        val color = when { ov == null -> 0xFF8E8E93.toInt(); lvl == "critical" -> 0xFFFF453A.toInt(); lvl == "warning" -> 0xFFFFB340.toInt(); else -> 0xFF30D158.toInt() }
        val head = when { !store.paired -> "Open Nova to pair"; ov == null -> "Can't reach the server"; lvl == "ok" -> "All systems normal"; else -> st?.optString("headline")?.ifEmpty { null } ?: "Needs attention" }
        val nums = listOfNotNull(m?.optString("cpu_temp")?.ifEmpty { null }, cs?.let { "${it.optInt("running")}/${it.optInt("total")} up" }).joinToString(" · ")
        val open = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName("app.novalabs.nova.wear.WearActivity").build()).build()).build()
        val dot = LayoutElementBuilders.Box.Builder().setWidth(dp(14f)).setHeight(dp(14f))
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setBackground(ModifiersBuilders.Background.Builder().setColor(argb(color))
                .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(7f)).build()).build()).build()).build()
        val col = LayoutElementBuilders.Column.Builder().setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(ov?.optJSONObject("server")?.optString("display_name")?.ifEmpty { null } ?: store.server.ifEmpty { "Nova" }, 14f, 0xFFB0B0B8.toInt()))
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(dot)
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(text(head, 16f, 0xFFFFFFFF.toInt(), bold = true, lines = 3))
        if (nums.isNotEmpty()) col.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build()).addContent(text(nums, 13f, 0xFFB0B0B8.toInt()))
        return LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(open).build()).addContent(col.build()).build()
    }
}
