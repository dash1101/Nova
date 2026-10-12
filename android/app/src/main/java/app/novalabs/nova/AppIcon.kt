package app.novalabs.nova

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/** The app's icon on the home screen: one launcher entry (MainActivity, or one of its aliases) is on at a time. */
object AppIcon {
    data class Choice(val id: String, val label: String, val cls: String, val res: Int)
    val all = listOf(
        Choice("default", "Default", ".MainActivity", R.mipmap.ic_launcher),
        Choice("material", "Material You", ".IconMaterial", R.mipmap.ic_launcher_material),
        Choice("dark", "Dark", ".IconDark", R.mipmap.ic_launcher_dark),
        Choice("outline", "Outline", ".IconOutline", R.mipmap.ic_launcher_outline),
        Choice("outline_light", "Outline light", ".IconOutlineLight", R.mipmap.ic_launcher_outline_light),
        Choice("bw", "Black on white", ".IconBw", R.mipmap.ic_launcher_bw),
        Choice("wb", "White on black", ".IconWb", R.mipmap.ic_launcher_wb),
        Choice("glass", "Glass", ".IconGlass", R.mipmap.ic_launcher_glass),
        Choice("blue", "Blue", ".IconBlue", R.mipmap.ic_launcher_blue),
        Choice("green", "Green", ".IconGreen", R.mipmap.ic_launcher_green),
        Choice("orange", "Orange", ".IconOrange", R.mipmap.ic_launcher_orange),
        Choice("red", "Red", ".IconRed", R.mipmap.ic_launcher_red),
        Choice("pink", "Pink", ".IconPink", R.mipmap.ic_launcher_pink),
    )
    private fun comp(ctx: Context, cls: String) = ComponentName(ctx.packageName, ctx.packageName + cls)

    /** An intent that opens Nova through whichever launcher entry is on (a disabled MainActivity can't be started by name). */
    fun launch(ctx: Context): android.content.Intent {
        val c = all.firstOrNull { it.id == current(ctx) } ?: all[0]
        // just the component (no MAIN/LAUNCHER): a launcher-style intent would only bring Nova forward and drop the extras
        return android.content.Intent().setComponent(comp(ctx, c.cls))
    }

    fun current(ctx: Context): String {
        val pm = ctx.packageManager
        return all.drop(1).firstOrNull { pm.getComponentEnabledSetting(comp(ctx, it.cls)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }?.id ?: "default"
    }

    /** Switch: the new entry goes on before the old one goes off, so there's always one to open Nova with. */
    fun set(ctx: Context, id: String) {
        val pm = ctx.packageManager; val want = all.firstOrNull { it.id == id } ?: all[0]
        fun state(c: Choice, on: Boolean) = pm.setComponentEnabledSetting(comp(ctx, c.cls),
            if (on) (if (c.id == "default") PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            else (if (c.id == "default") PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT),
            PackageManager.DONT_KILL_APP)
        state(want, true)
        all.filter { it.id != want.id }.forEach { state(it, false) }
    }
}

/** Settings → Appearance → App icon: a grid of previews. */
@Composable fun AppIconPicker(app: AppState) {
    val ctx = LocalContext.current
    var cur by remember { mutableStateOf(AppIcon.current(ctx)) }
    var ask by remember { mutableStateOf<AppIcon.Choice?>(null) }
    SectionLabel("App icon")
    Group {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AppIcon.all.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c ->
                        val bmp = remember(c.res) { ctx.getDrawable(c.res)!!.toBitmap(168, 168).asImageBitmap() }
                        val on = cur == c.id
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { if (!on) ask = c }.padding(vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Image(bmp, c.label, Modifier.size(58.dp).clip(RoundedCornerShape(18.dp))
                                .then(if (on) Modifier.border(3.dp, N.blue, RoundedCornerShape(18.dp)) else Modifier))
                            Text(c.label, color = if (on) N.blue else N.sub, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2,
                                modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    var custom by remember { mutableStateOf(false) }
    Group { Row1("Your own colors", "Pick the background and the symbol's color — added to your home screen as a Nova icon", true, Icons.Rounded.Palette, onClick = { custom = true }) }
    if (custom) CustomIconDialog(app) { custom = false }
    Note("Material You follows your wallpaper's colors. With themed icons on (Android 13+), every choice turns into your phone's themed style.", 13.sp, 4.dp)
    ask?.let { c -> OneDialog({ ask = null }, "Use the ${c.label} icon?",
        "Your home screen switches to it in a moment. If Nova was on your home screen, you may need to add it there again.",
        listOf(DialogButton("Cancel") { ask = null }, DialogButton("Change", N.blue) { ask = null; AppIcon.set(ctx, c.id); cur = c.id; app.toast("App icon changed") })) }
}

/** Nova's symbol in your colors, as an adaptive icon bitmap (the launcher masks it into its own shape). */
fun customIconBitmap(ctx: Context, bg: Int, fg: Int, px: Int = 432): android.graphics.Bitmap {
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp); c.drawColor(bg)
    ctx.getDrawable(R.drawable.ic_launcher_monochrome)!!.mutate().apply { setBounds(0, 0, px, px); setTint(fg) }.draw(c)
    return bmp
}

private val ICON_SWATCHES = listOf(0xFF0A0A0C, 0xFF1C2541, 0xFF0B3D91, 0xFF2F80ED, 0xFF00A86B, 0xFF6FCF97, 0xFFFFB340, 0xFFFF6B35,
    0xFFE63946, 0xFFFF4FA3, 0xFF9B5DE5, 0xFFF2F2F7, 0xFFFFFFFF).map { it.toInt() }

/** Settings → App icon → Your own colors: background + symbol color, previewed, then pinned to the home screen. */
@Composable fun CustomIconDialog(app: AppState, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val sp = remember { ctx.getSharedPreferences("nova_icon", Context.MODE_PRIVATE) }
    var bg by remember { mutableIntStateOf(sp.getInt("bg", 0xFF1C2541.toInt())) }
    var fg by remember { mutableIntStateOf(sp.getInt("fg", 0xFFFFFFFF.toInt())) }
    val preview = remember(bg, fg) { customIconBitmap(ctx, bg, fg, 216).asImageBitmap() }
    @Composable fun Swatches(label: String, cur: Int, pick: (Int) -> Unit) {
        Text(label, color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 22.dp, top = 8.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ICON_SWATCHES.forEach { c ->
                Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(c))
                    .border(if (c == cur) 3.dp else 1.dp, if (c == cur) N.blue else N.divider, androidx.compose.foundation.shape.CircleShape).clickable { pick(c) })
            }
        }
        var hex by remember(cur) { mutableStateOf("#%06X".format(cur and 0xFFFFFF)) }
        OneTextField(hex, { v -> hex = v.take(7); Regex("#?([0-9a-fA-F]{6})").matchEntire(v.trim())?.let { pick(0xFF000000.toInt() or it.groupValues[1].toInt(16)) } },
            "#RRGGBB", Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp), mono = true)
    }
    OneDialog(onDone, "Your own colors", "Android only lets an app switch between icons it ships with, so this adds a Nova icon in your colors to your home screen (you can remove the old one).",
        listOf(DialogButton("Cancel", onClick = onDone), DialogButton("Add to home screen", N.blue) {
            sp.edit().putInt("bg", bg).putInt("fg", fg).apply()
            val ok = androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(ctx) &&
                androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(ctx, androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, "nova-custom-${bg}-${fg}")
                    .setShortLabel("Nova").setIcon(androidx.core.graphics.drawable.IconCompat.createWithAdaptiveBitmap(customIconBitmap(ctx, bg, fg)))
                    .setIntent(android.content.Intent(android.content.Intent.ACTION_MAIN).setComponent(android.content.ComponentName(ctx.packageName, ctx.packageName + ".OpenNova"))).build(), null)
            app.toast(if (ok) "Confirm on the next screen to add it" else "Your home screen app doesn't allow adding icons")
            onDone() })) {
        Column {
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Image(preview, "Preview", Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)))
            }
            Swatches("Background", bg) { bg = it }
            Swatches("Symbol", fg) { fg = it }
        }
    }
}
