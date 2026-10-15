package app.novalabs.nova

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

/* Security & privacy: everything about who and what can reach the server, in one place — this phone's keys and lock,
 * how risky actions are approved (this phone, authenticator codes, passkeys), devices, what Nova keeps private, and
 * the latest security activity. */
@Composable fun SecurityScreen(app: AppState) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pk = if (app.isAdmin) live(app, "/api/v1/passkeys", 60_000) else null
    val act = if (app.isAdmin) live(app, "/api/v1/security/activity", 60_000) else null
    var rmPk by remember { mutableStateOf<JSONObject?>(null) }
    var renPk by remember { mutableStateOf<JSONObject?>(null) }
    Page("Security & privacy", app::back) {
        SectionLabel("This ${DeviceForm.noun}")
        Group {
            Row1("Signing key", "Made in this ${DeviceForm.noun}'s secure chip; it never leaves it. Every request is signed with it.", false, Icons.Rounded.Key, N.green)
            RowDivider()
            SwitchRow("App lock", "Fingerprint or PIN to open Nova, and again after 5 minutes away", AppPrefs.appLock) { on ->
                if (on) AppLock.prompt(app.activity) { ok -> if (ok) AppPrefs.set("app_lock", true) } else AppPrefs.set("app_lock", false) }
            RowDivider()
            SwitchRow("Hide in recent apps", "Blank preview in the app switcher; screenshots are blocked", AppPrefs.hideInRecents) {
                AppPrefs.set("hide_recents", it); applySecureFlag(app.activity) }
        }
        SectionLabel("Approving risky actions")
        Group {
            Row1("This ${DeviceForm.noun}", "Restarts, installs, unmounting, shells and the like ask for your fingerprint here", false, Icons.Rounded.Fingerprint, N.blue)
            if (app.isAdmin) {
                RowDivider(); Row1("Approvals", "What a browser is waiting for you to approve", true, Icons.Rounded.VerifiedUser, onClick = { app.go(Route.Approvals) })
                RowDivider(); AuthenticatorCodes(app)
            }
        }
        if (app.isAdmin) {
            val keys = pk?.value?.optJSONArray("passkeys").objs()
            SectionLabel("Passkeys")
            Group {
                if (keys.isEmpty()) Row1("None yet", "Add one in Nova web, opened at its web address (Security & privacy → Passkeys): a passkey in your password manager can then add a browser or approve what it asks for, without this phone.", false, Icons.Rounded.Key, N.sub)
                keys.forEachIndexed { i, k -> if (i > 0) RowDivider()
                    Row1(k.optString("name").ifEmpty { "Passkey" }, "${k.optString("rp")} · added ${k.optString("created")}" + (k.optString("last_used").takeIf { it.isNotEmpty() && it != "null" }?.let { " · last used $it" } ?: ""),
                        false, Icons.Rounded.Key, N.blue) { Row { IconButton({ renPk = k }) { Icon(Icons.Rounded.Edit, "Rename", tint = N.sub) }; IconButton({ rmPk = k }) { Icon(Icons.Rounded.Delete, "Remove", tint = N.red) } } } }
            }
        }
        SectionLabel("Who can reach the server")
        Group {
            Row1("Users & devices", "Phones, browsers and watches — roles, remove any of them", true, Icons.Rounded.Group, onClick = { app.go(Route.Devices) })
            RowDivider(); Row1("Servers", "Other Nova servers on this ${DeviceForm.noun}", true, Icons.Rounded.Dns, onClick = { app.go(Route.Servers) })
        }
        SectionLabel("Privacy")
        Group {
            SwitchRow("Start page suggestions", "While you type, from DuckDuckGo, through your server (it never sees your phone)", AppPrefs.startSuggest) { AppPrefs.set("start_suggest", it) }
            RowDivider()
            Row1("Privacy policy", "Nova collects nothing. The app talks only to your server", false, Icons.Rounded.Shield, N.green, onClick = {
                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://dash1101.github.io/Nova/privacy.html"))) } })
        }
        if (app.isAdmin) {
            SectionLabel("Recent security activity")
            val ev = act?.value?.optJSONArray("events").objs()
            if (ev.isEmpty()) Note("Nothing yet.", 14.sp)
            else Group { ev.take(15).forEachIndexed { i, e -> if (i > 0) RowDivider()
                val bad = e.optInt("result") >= 400
                Row1(e.optString("why").ifEmpty { e.optString("path") }, "${e.optString("t")} · ${e.optString("device").ifEmpty { "—" }}" + (e.optString("via").takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                    false, if (bad) Icons.Rounded.Warning else Icons.Rounded.CheckCircle, if (bad) N.amber else N.green) } }
        }
    }
    renPk?.let { k -> RenameDialog("Rename this passkey", k.optString("name"), onDismiss = { renPk = null }) { n -> val id = k.optString("id"); renPk = null
        app.act("Renamed") { pk?.value = app.api.post("/api/v1/passkeys/rename", JSONObject().put("id", id).put("name", n)) } } }
    rmPk?.let { k -> OneDialog({ rmPk = null }, "Remove ${k.optString("name").ifEmpty { "this passkey" }}?", "It can't add browsers or approve requests any more. (It stays in your password manager — delete it there too.)",
        listOf(DialogButton("Cancel") { rmPk = null }, DialogButton("Remove", N.red) { val id = k.optString("id"); rmPk = null
            app.act { app.stepUp("Remove a passkey", "POST", "/api/v1/passkeys/remove", JSONObject().put("id", id)); pk?.value = app.api.get("/api/v1/passkeys") } })) }
}
