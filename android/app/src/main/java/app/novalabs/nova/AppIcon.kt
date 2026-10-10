package app.novalabs.nova

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
        return android.content.Intent(android.content.Intent.ACTION_MAIN).setComponent(comp(ctx, c.cls)).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
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
    Text("Material You follows your wallpaper's colors. With themed icons on (Android 13+), every choice turns into your phone's themed style.",
        color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
    ask?.let { c -> OneDialog({ ask = null }, "Use the ${c.label} icon?",
        "Your home screen switches to it in a moment. If Nova was on your home screen, you may need to add it there again.",
        listOf(DialogButton("Cancel") { ask = null }, DialogButton("Change", N.blue) { ask = null; AppIcon.set(ctx, c.id); cur = c.id; app.toast("App icon changed") })) }
}
