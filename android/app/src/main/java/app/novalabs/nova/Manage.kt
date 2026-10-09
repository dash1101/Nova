package app.novalabs.nova

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/** What to call the active server: the name set in the app, else its hostname, prettified. */
fun serverName(app: AppState): String {
    val s = app.overview?.optJSONObject("server")
    return s?.optString("display_name")?.ifEmpty { null }
        ?: s?.optString("name")?.split("-")?.joinToString("-") { w -> w.replaceFirstChar { it.uppercase() } }
        ?: app.pairing.label.ifEmpty { "Nova" }
}

fun labelOf(app: AppState, id: String) = Pairing(app.activity, id).let { p ->
    p.label.ifEmpty { p.lanUrl.substringAfter("//").substringBefore(":").ifEmpty { "Server" } } }

// ── Servers on this phone ─────────────────────────────────────────────────────────
/** "Phone", "Tablet", "Browser on a computer"… from what the device told the server. */
private fun formLabel(d: JSONObject): String {
    val f = d.optString("form").ifEmpty { if (d.optString("type") == "browser") "desktop" else "phone" }
    if (d.optString("type") == "watch") return "Approves from notifications"
    val n = when (f) { "tablet" -> "Tablet"; "desktop" -> "Computer"; else -> "Phone" }
    return if (d.optString("type") == "browser") "Browser · $n" else n
}

// ── Server location (sunrise & sunset) ──
/** "Near New York (from the time zone)" or "40.71, -74.01". */
fun locationText(loc: JSONObject?): String = when {
    loc == null -> "Not known — set it for sunrise/sunset schedules"
    loc.optString("source") == "timezone" -> "Near ${loc.optString("name")} (from the time zone)"
    loc.optString("name").isNotEmpty() -> loc.optString("name")
    else -> "%.2f, %.2f".format(loc.optDouble("lat"), loc.optDouble("lon"))
}

/** Where the server is. Saved in the server's settings; "Use the time zone" goes back to the automatic guess. */
@Composable fun ServerLocationDialog(app: AppState, loc: JSONObject?, onDone: (JSONObject?) -> Unit) {
    val typed = loc?.takeIf { it.optString("source") == "set" }
    var lat by remember { mutableStateOf(typed?.optDouble("lat")?.toString() ?: "") }
    var lon by remember { mutableStateOf(typed?.optDouble("lon")?.toString() ?: "") }
    var name by remember { mutableStateOf(typed?.optString("name") ?: "") }
    fun post(v: Any) = app.act {
        val r = app.api.post("/api/v1/settings", JSONObject().put("location", v))
        app.fan = app.api.get("/api/v1/fan"); onDone(r.optJSONObject("location")); app.toast("Saved")
    }
    val ok = lat.toDoubleOrNull()?.let { it in -90.0..90.0 } == true && lon.toDoubleOrNull()?.let { it in -180.0..180.0 } == true
    OneDialog({ onDone(loc) }, "Where is the server?",
        "Used to work out sunrise and sunset for light schedules. Nova starts from the server's time zone" +
            (loc?.takeIf { it.optString("source") == "timezone" }?.let { " (${it.optString("name")})" } ?: "") +
            "; for the exact times, type its latitude and longitude (from any map app — your town is close enough).",
        listOf(DialogButton("Use the time zone") { post(JSONObject.NULL) }, DialogButton("Save", N.blue, enabled = ok) {
            post(JSONObject().put("lat", lat.toDouble()).put("lon", lon.toDouble()).put("name", name.trim())) })) {
        Column(Modifier.padding(horizontal = 22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OneTextField(lat, { lat = it.take(12) }, "Latitude (e.g. 40.71)", Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OneTextField(lon, { lon = it.take(12) }, "Longitude (e.g. -74.01)", Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OneTextField(name, { name = it.take(40) }, "Name (optional, e.g. Home)", Modifier.fillMaxWidth())
        }
    }
}

// ── Server name + accent ──────────────────────────────────────────────────────────
private val ACCENTS = listOf("", "#3e91ff", "#5e5ce6", "#bf5af2", "#ff2d55", "#ff9500", "#ffcc00", "#34c759", "#00c7be")

@Composable fun ServerSettingsScreen(app: AppState) {
    val live = live(app, "/api/v1/settings")
    var st by live
    var name by remember(st?.optString("display_name")) { mutableStateOf(st?.optString("display_name") ?: "") }
    fun save(patch: JSONObject) {
        val before = st
        st = JSONObject(st?.toString() ?: "{}").also { m -> patch.keys().forEach { k -> m.put(k, patch.get(k)) } }
        // Overview carries name + accent for the rest of the app: update it straight away.
        app.overview = JSONObject(app.overview?.toString() ?: "{}").also { o ->
            val srv = o.optJSONObject("server") ?: JSONObject().also { o.put("server", it) }
            if (patch.has("display_name")) srv.put("display_name", patch.getString("display_name"))
            if (patch.has("accent")) srv.put("accent", patch.getString("accent"))
        }
        if (patch.has("display_name")) app.pairing.label = patch.getString("display_name").ifEmpty { st?.optString("hostname") ?: "" }
        app.act { try { st = app.api.post("/api/v1/settings", patch) } catch (e: Exception) { st = before; throw e } }
    }
    Page("Server", app::back) {
        SectionLabel("Name")
        Group {
            Column(Modifier.padding(18.dp)) {
                OneTextField(name, { name = it.take(40) }, st?.optString("hostname") ?: "Server name", Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Save") { save(JSONObject().put("display_name", name.trim())); app.toast("Saved") }
                    if ((st?.optString("display_name") ?: "").isNotEmpty())
                        PillButton("Use hostname", color = N.text) { name = ""; save(JSONObject().put("display_name", "")) }
                }
            }
        }
        Text("Shown at the top of Home and in the server switcher. The machine's hostname (${st?.optString("hostname") ?: "—"}) doesn't change.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        SectionLabel("Accent color")
        Group {
            Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                ACCENTS.forEach { h ->
                    val sel = (st?.optString("accent") ?: "") == h
                    val c = if (h.isEmpty()) N.sub else col(h)
                    Box(Modifier.size(32.dp).clip(CircleShape).background(if (h.isEmpty()) Color.Transparent else c)
                        .border(if (sel) 3.dp else 1.dp, if (sel) N.text else N.divider, CircleShape)
                        .clickable { save(JSONObject().put("accent", h)) }, contentAlignment = Alignment.Center) {
                        if (h.isEmpty()) Text("A", color = N.sub, fontSize = 13.sp)
                    }
                }
            }
        }
        Text("Gives each server its own color, so you always know which one you're controlling. \"A\" is the default blue.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        SectionLabel("Location")
        var locating by remember { mutableStateOf(false) }
        Group { Row1("Where the server is", locationText(st?.optJSONObject("location")), st?.optJSONObject("location")?.optString("source") == "set",
            Icons.Rounded.Place, N.amber, onClick = { if (app.isAdmin) locating = true }) }
        Text("For sunrise and sunset light schedules — worked out on the server, nothing is looked up online.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 4.dp))
        if (locating) ServerLocationDialog(app, st?.optJSONObject("location")) { l -> locating = false; st = JSONObject(st?.toString() ?: "{}").put("location", l ?: JSONObject.NULL) }
    }
}

// ── Paired devices, by user ───────────────────────────────────────────────────────
fun roleLabel(r: String) = if (r == "viewer") "View only" else "Admin"

/** QR code bitmap (ZXing) for invites. */
@Composable fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) {
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600,
            mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
        android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.RGB_565).apply {
            for (x in 0 until m.width) for (y in 0 until m.height) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }.asImageBitmap()
    }
    androidx.compose.foundation.Image(bmp, "Pairing QR code", modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)))
}

@Composable fun DevicesScreen(app: AppState) {
    val data = live(app, "/api/v1/devices")
    val list = data.value?.optJSONArray("devices")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    suspend fun load() { data.value = app.api.get("/api/v1/devices") }
    var menu by remember { mutableStateOf<JSONObject?>(null) }
    var access by remember { mutableStateOf<JSONObject?>(null) }
    var rename by remember { mutableStateOf<JSONObject?>(null) }
    var revoke by remember { mutableStateOf<JSONObject?>(null) }
    var removeAll by remember { mutableStateOf<Boolean?>(null) }       // true = keep this phone
    var inviting by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf<JSONObject?>(null) }
    var approveBrowser by remember { mutableStateOf(false) }
    Page("Users & devices", app::back, if (app.isAdmin) listOf(TopAction(Icons.Rounded.PersonAdd, "Invite a phone") { inviting = true }) else emptyList()) {
        if (!app.isAdmin) Text("This ${DeviceForm.noun} has view-only access, so it can see the devices but not change them.",
            color = N.amber, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        list.groupBy { it.optString("user").ifEmpty { "No name yet" } }.toSortedMap(compareBy { if (it == "No name yet") "~" else it.lowercase() })
            .forEach { (user, ds) ->
                SectionLabel(user)
                Group {
                    ds.forEachIndexed { i, d -> if (i > 0) RowDivider()
                        val me = d.optBoolean("current"); val viewer = d.optString("role") == "viewer"
                        Row1(d.optString("name") + if (me) "  ·  this ${DeviceForm.noun}" else "",
                            "${formLabel(d)} · ${roleLabel(d.optString("role"))} · last seen ${d.optString("last_seen", "never").let { if (it == "null") "never" else it }}" +
                                (d.optString("via").takeIf { it.isNotEmpty() && it != "null" }?.let { " via $it" } ?: "") +
                                if (d.optString("type") == "browser") " · risky actions approved on a phone" else if (!d.optBoolean("stepup")) " · no fingerprint key" else "",
                            false, when (d.optString("form").ifEmpty { if (d.optString("type") == "browser") "desktop" else "phone" }) {
                                "tablet" -> Icons.Rounded.TabletAndroid; "desktop" -> Icons.Rounded.Computer; "watch" -> Icons.Rounded.Watch; else -> Icons.Rounded.PhoneAndroid },
                            if (me) N.green else if (viewer) N.sub else N.blue, onClick = { if (app.isAdmin) menu = d }) {
                            if (app.isAdmin) Icon(Icons.Rounded.MoreVert, "Options", tint = N.sub)
                        }
                    }
                }
            }
        if (list.isEmpty()) Text("Loading…", color = N.sub, modifier = Modifier.padding(30.dp))
        if (app.isAdmin) {
            Group {
                Row1("Invite a phone", "Pick who it's for and whether it's an admin or view only", false, Icons.Rounded.PersonAdd, N.green,
                    onClick = { inviting = true })
                RowDivider()
                Row1("Approve a browser", "Use Nova from a computer — enter the code it shows", false, Icons.Rounded.Computer, N.blue,
                    onClick = { approveBrowser = true })
                RowDivider()
                Row1("Approvals", "Risky actions a browser is waiting on", true, Icons.Rounded.VerifiedUser, onClick = { app.go(Route.Approvals) })
            }
            SectionLabel("Remove access")
            Group {
                Row1("Remove all other devices", "Only this ${DeviceForm.noun} keeps access", false, Icons.Rounded.PhonelinkErase, N.amber,
                    enabled = list.size > 1, onClick = { removeAll = true })
                RowDivider()
                Row1("Remove every device", "Including this ${DeviceForm.noun} — you'll need to pair again on the server's network", false,
                    Icons.Rounded.DeleteForever, N.red, onClick = { removeAll = false })
            }
        }
        Text("Admins can do everything. View-only devices see the same screens but can't change anything — good for family members or a wall-mounted dashboard. Changes need your fingerprint.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp))
    }
    menu?.let { d ->
        OneDialog({ menu = null }, d.optString("name"), buttons = listOf(DialogButton("Close") { menu = null })) {
            DialogChoice("Who & access", "${d.optString("user").ifEmpty { "No name" }} · ${roleLabel(d.optString("role"))}", false) { menu = null; access = d }
            DialogChoice("Rename device", null, false) { menu = null; rename = d }
            DialogChoice(if (d.optBoolean("current")) "Remove this ${DeviceForm.noun}" else "Remove access", "It can never connect again", false) { menu = null; revoke = d }
        }
    }
    access?.let { d ->
        var user by remember(d) { mutableStateOf(d.optString("user")) }
        var role by remember(d) { mutableStateOf(d.optString("role").ifEmpty { "admin" }) }
        OneDialog({ access = null }, "Who & access", buttons = listOf(DialogButton("Cancel") { access = null }, DialogButton("Save", N.blue) {
            access = null; app.act("Saved") {
                app.stepUp("Change access for ${d.optString("name")}", "POST", "/api/v1/devices/${d.optString("id")}/access",
                    JSONObject().put("user", user).put("role", role))
                if (d.optBoolean("current")) { app.role = role; app.pairing.role = role }
                load() } })) {
            OneTextField(user, { user = it.take(40) }, "Person's name (e.g. Alex)", Modifier.fillMaxWidth().padding(horizontal = 22.dp))
            Spacer(Modifier.height(6.dp))
            DialogChoice("Admin", "Full control, including risky actions (with fingerprint)", role == "admin") { role = "admin" }
            DialogChoice("View only", "Sees everything, changes nothing", role == "viewer") { role = "viewer" }
        }
    }
    if (approveBrowser) ApproveBrowserDialog(app) { approveBrowser = false; app.act { load() } }
    rename?.let { d ->
        var v by remember(d) { mutableStateOf(d.optString("name")) }
        OneDialog({ rename = null }, "Rename device", buttons = listOf(DialogButton("Cancel") { rename = null }, DialogButton("Save", N.blue) {
            rename = null; app.act("Renamed") { app.api.post("/api/v1/devices/${d.optString("id")}/rename", JSONObject().put("name", v)); load() } })) {
            OneTextField(v, { v = it.take(40) }, "Name", Modifier.fillMaxWidth().padding(horizontal = 22.dp))
        }
    }
    if (inviting) {
        var user by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("viewer") }
        OneDialog({ inviting = false }, "Invite a phone", buttons = listOf(DialogButton("Cancel") { inviting = false }, DialogButton("Create code", N.blue) {
            inviting = false; app.act { invite = app.stepUp("Invite a phone", "POST", "/api/v1/devices/invite", JSONObject().put("user", user).put("role", role)) } })) {
            OneTextField(user, { user = it.take(40) }, "Who is it for? (e.g. Alex)", Modifier.fillMaxWidth().padding(horizontal = 22.dp))
            Spacer(Modifier.height(6.dp))
            DialogChoice("View only", "Sees everything, changes nothing", role == "viewer") { role = "viewer" }
            DialogChoice("Admin", "Full control", role == "admin") { role = "admin" }
        }
    }
    invite?.let { inv ->
        OneDialog({ invite = null; app.act { load() } }, "Scan with the new phone",
            "Open Nova on the other phone (on the server's network) and scan this. Single use, expires in 10 minutes.",
            listOf(DialogButton("Done", N.blue) { invite = null; app.act { load() } })) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(inv.optString("qr"), Modifier.size(240.dp)) }
            Text("Or type: ${inv.optString("address")} · code ${inv.optString("code")}", color = N.sub, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 8.dp))
            Text("${roleLabel(inv.optString("role"))}${inv.optString("user").takeIf { it.isNotEmpty() }?.let { " · for $it" } ?: ""}",
                color = N.text, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 26.dp))
        }
    }
    revoke?.let { d -> val me = d.optBoolean("current")
        OneDialog({ revoke = null }, if (me) "Remove this ${DeviceForm.noun}?" else "Remove ${d.optString("name")}?",
            if (me) "This ${DeviceForm.noun} loses access to the server and its keys are erased." else "It will never be able to connect again.",
            listOf(DialogButton("Cancel") { revoke = null }, DialogButton("Remove", N.red) { revoke = null
                app.act("Removed") { app.stepUp("Remove ${d.optString("name")}", "DELETE", "/api/v1/devices/${d.optString("id")}")
                    if (me) { Servers.remove(app.activity, app.pairing.profile); app.switchServer(Servers.active(app.activity)) } else load() } })) }
    removeAll?.let { keep ->
        OneDialog({ removeAll = null }, if (keep) "Remove all other devices?" else "Remove every device?",
            if (keep) "Every phone except this one loses access immediately." else "Every phone, this one included, loses access. To use Nova again you'll need to pair on the server's network.",
            listOf(DialogButton("Cancel") { removeAll = null }, DialogButton("Remove", N.red) { removeAll = null
                app.act { val r = app.stepUp(if (keep) "Remove all other devices" else "Remove every device", "POST", "/api/v1/devices/remove-all", JSONObject().put("keep_self", keep))
                    app.toast("Removed ${r.optJSONArray("revoked")?.length() ?: 0} device(s)")
                    if (!keep) { Servers.remove(app.activity, app.pairing.profile); app.switchServer(Servers.active(app.activity)) } else load() } })) }
}

// ── Browser requests: approve risky actions that a paired browser asked for ──────
@Composable fun ApprovalsScreen(app: AppState) {
    val data = live(app, "/api/v1/approvals", 4_000)
    val list = data.value?.optJSONArray("approvals")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    val pending = list.filter { it.optString("state") == "pending" }
    Page("Approvals", app::back) {
        Text("When a browser you've paired as an admin wants to do something risky, it waits here for your fingerprint.",
            color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
        if (pending.isEmpty()) Group { Row1("Nothing waiting", "Requests show up here and as a notification", false, Icons.Rounded.CheckCircle, N.green) }
        else { SectionLabel("Waiting for you"); Group { pending.forEachIndexed { i, a -> if (i > 0) RowDivider()
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp)) {
                Text(a.optString("what"), color = N.text, fontSize = 17.sp)
                Text("From ${a.optString("device_name")}${a.optString("user").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""}", color = N.sub, fontSize = 14.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Approve") { app.act("Approved") {
                        val r = app.stepUp(a.optString("what"), "POST", "/api/v1/approvals/${a.optString("id")}/approve")
                        if (!r.optBoolean("ok", true)) app.toast(r.optJSONObject("result")?.optString("error") ?: "It didn't work")
                        data.value = app.api.get("/api/v1/approvals") } }
                    PillButton("Decline", color = N.red) { app.act("Declined") { app.api.post("/api/v1/approvals/${a.optString("id")}/deny"); data.value = app.api.get("/api/v1/approvals") } }
                }
            } } } }
        val done = list.filter { it.optString("state") != "pending" }
        if (done.isNotEmpty()) { SectionLabel("Recent"); Group { done.forEachIndexed { i, a -> if (i > 0) RowDivider()
            Row1(a.optString("what"), "${a.optString("device_name")} · ${a.optString("state")}", false) } } }
    }
}

/** Admin: let a browser in by the code it shows. */
@Composable fun ApproveBrowserDialog(app: AppState, onDone: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var user by remember { mutableStateOf(app.pairing.user) }
    var role by remember { mutableStateOf("admin") }
    OneDialog(onDone, "Approve a browser", buttons = listOf(DialogButton("Cancel", onClick = onDone), DialogButton("Approve", N.blue, code.length == 6) {
        app.act("Browser added") { app.stepUp("Add a browser", "POST", "/api/v1/browser/approve",
            JSONObject().put("code", code.uppercase()).put("user", user).put("role", role)); onDone() } })) {
        Text("Open Nova in the browser (https://your-server:8495 at home, or your Cloudflare address) and enter the 6-letter code it shows.",
            color = N.sub, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 26.dp))
        Spacer(Modifier.height(10.dp))
        OneTextField(code, { code = it.filter { c -> c.isLetterOrDigit() }.take(6) }, "Code", Modifier.fillMaxWidth().padding(horizontal = 22.dp), mono = true)
        Spacer(Modifier.height(8.dp))
        OneTextField(user, { user = it.take(40) }, "Whose browser? (e.g. Dash)", Modifier.fillMaxWidth().padding(horizontal = 22.dp))
        DialogChoice("Admin", "Risky actions still need your phone's fingerprint", role == "admin") { role = "admin" }
        DialogChoice("View only", "Sees everything, changes nothing", role == "viewer") { role = "viewer" }
    }
}
