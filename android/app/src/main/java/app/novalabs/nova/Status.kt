package app.novalabs.nova

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray

/** Small line chart with a soft fill, auto-scaled (or fixed to [max]). */
@Composable fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier, max: Float? = null) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val top = max ?: (values.max() * 1.15f).coerceAtLeast(1f)
        val dx = size.width / (values.size - 1)
        fun y(v: Float) = size.height - (v / top).coerceIn(0f, 1f) * size.height
        val line = Path().apply { values.forEachIndexed { i, v -> if (i == 0) moveTo(0f, y(v)) else lineTo(i * dx, y(v)) } }
        val fill = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 3.5.dp.toPx(), Offset((values.size - 1) * dx, y(values.last())))
    }
}

@Composable private fun MetricCard(title: String, value: String, sub: String, color: Color, values: List<Float>, max: Float?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(N.card).padding(16.dp)) {
        Text(title, color = N.sub, fontSize = 13.sp)
        Text(value, color = N.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(sub, color = N.sub, fontSize = 12.sp, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Sparkline(values, color, Modifier.fillMaxWidth().height(44.dp), max)
    }
}

private fun pct(s: String?) = s?.let { Regex("(\\d+)%").find(it)?.groupValues?.get(1)?.toFloatOrNull() }
fun rate(b: Double): String = when { b >= 1e6 -> "%.1f MB/s".format(b / 1e6); b >= 1e3 -> "%.0f kB/s".format(b / 1e3); else -> "%.0f B/s".format(b) }
private fun uptime(s: Long) = "${s / 86400}d ${s % 86400 / 3600}h ${s % 3600 / 60}m"

@Composable fun StatusScreen(app: AppState) {
    val stats by live(app, "/api/v1/stats", 15_000)
    val o = app.overview
    val st = o?.optJSONObject("status")
    val m = st?.optJSONObject("metrics")
    val level = st?.optString("level") ?: "ok"
    val now = stats?.optJSONObject("now")?.takeIf { it.has("cpu") }
    val hist = stats?.optJSONArray("history") ?: JSONArray()
    fun series(k: String) = (0 until hist.length()).map { hist.getJSONObject(it).optDouble(k, 0.0).toFloat() }
    val backupRunning = m?.optString("data_backup")?.contains("running") == true
    val backup = if (backupRunning) live(app, "/api/v1/backup", 5_000).value else null

    Page("Server status", app::back) {
        // Health header
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(84.dp).clip(CircleShape).background(levelColor(level, N).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(if (level == "ok") Icons.Rounded.CheckCircle else Icons.Rounded.Error, null, tint = levelColor(level, N), modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(10.dp))
            Text(if (level == "ok") "All systems normal" else st?.optString("headline") ?: "", color = N.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Updated ${st?.optString("updated_local")?.let { Regex("\\d{1,2}:\\d{2}").find(it)?.value } ?: "—"} · up ${now?.optLong("uptime_s")?.let { uptime(it) } ?: m?.optString("uptime") ?: "—"}",
                color = N.sub, fontSize = 14.sp)
        }
        // Active alerts
        val active = st?.optJSONArray("active")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }?.filter { it.optString("level") != "ok" } ?: emptyList()
        if (active.isNotEmpty()) {
            SectionLabel("Needs attention")
            Group { active.forEachIndexed { i, a -> if (i > 0) RowDivider()
                Row1(a.optString("title").replace(Regex("^[^\\p{L}\\p{N}]+\\s*"), ""), listOf(a.optString("detail"), a.optString("since").takeIf { it.isNotEmpty() }?.let { "since $it" })
                    .filterNotNull().filter { it.isNotEmpty() }.joinToString(" · "), false, Icons.Rounded.Warning, levelColor(a.optString("level"), N)) } }
        }
        // Live graphs
        SectionLabel("Last hour")
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("CPU", now?.let { "%.0f%%".format(it.optDouble("cpu")) } ?: "—", now?.let { "load ${it.optDouble("load")} · ${it.optInt("cores")} cores" } ?: "",
                    N.blue, series("cpu"), 100f, Modifier.weight(1f))
                MetricCard("Memory", now?.let { "%.0f%%".format(it.optDouble("mem")) } ?: "—", now?.let { "${it.optDouble("mem_used_gb")} of ${it.optDouble("mem_total_gb")} GB" } ?: "",
                    Color(0xFFBF5AF2), series("mem"), 100f, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricCard("CPU temperature", now?.optDouble("temp")?.takeIf { !it.isNaN() }?.let { "%.0f°C".format(it) } ?: m?.optString("cpu_temp") ?: "—",
                    now?.optDouble("nvme_temp")?.takeIf { !it.isNaN() }?.let { "NVMe %.0f°C".format(it) } ?: "", N.amber, series("temp"), null, Modifier.weight(1f))
                MetricCard("Network", now?.let { "↓ ${rate(it.optDouble("rx"))}" } ?: "—", now?.let { "↑ ${rate(it.optDouble("tx"))}" } ?: "",
                    N.green, series("rx").zip(series("tx")) { a, b -> a + b }, null, Modifier.weight(1f))
            }
            if (hist.length() < 3) Text("Graphs fill in over the next few minutes.", color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        // Storage
        SectionLabel("Storage")
        Group {
            listOf("Photos" to "photo_pool_used", "System drive" to "root_used", "Cold storage" to "cold_storage_used", "Backup drive" to "backup_drive_used")
                .filter { m?.has(it.second) == true }.forEachIndexed { i, (label, k) ->
                    if (i > 0) RowDivider()
                    val v = m!!.optString(k); val p = pct(v) ?: 0f
                    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
                        Row { Text(label, color = N.text, fontSize = 16.sp, modifier = Modifier.weight(1f)); Text(v, color = N.sub, fontSize = 14.sp) }
                        Spacer(Modifier.height(8.dp))
                        UsageBar(p / 100f, if (p > 95) N.red else if (p > 85) N.amber else N.blue)
                    }
                }
            RowDivider()
            Row1("Drives", "${m?.optString("drives") ?: "—"} · ${m?.optString("drive_temps") ?: ""}", true, Icons.Rounded.Storage, onClick = { app.go(Route.Hardware) })
        }
        // Backups
        SectionLabel("Backups")
        Group {
            Row1("Data backup", if (backupRunning) backup?.let { backupLine(it) } ?: "Running now" else m?.optString("data_backup"), backupRunning, Icons.Rounded.Backup)
            if (backupRunning && backup != null) BackupProgress(backup)
            RowDivider(); Row1("Backup sets", m?.optString("backup_sets"))
            RowDivider(); Row1("Photo check", m?.optString("backup_verify")?.replace("✗", "")?.trim())
            RowDivider(); Row1("Server settings backup", m?.optString("config_backup"))
            RowDivider(); Row1("Photo database backup", m?.optString("immich_db_backup"))
        }
        // Services
        SectionLabel("Services")
        Group {
            Row1("Containers", m?.optString("containers"), true, Icons.Rounded.ViewInAr, onClick = { app.go(Route.Containers) })
            RowDivider(); Row1("Websites", m?.optString("websites"), false, Icons.Rounded.Language)
            RowDivider(); Row1("Swap", m?.optString("swap") ?: now?.let { "%.0f%% used".format(it.optDouble("swap")) }, false, Icons.Rounded.SwapHoriz)
        }
    }
}
