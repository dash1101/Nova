package app.novalabs.nova

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

private fun copy(ctx: Context, label: String, text: String) =
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))

/** The Terminal menu: connect to the server over SSH, pick the route, manage this phone's key. */
@Composable fun SshScreen(app: AppState) {
    val ctx = LocalContext.current
    val info by live(app, "/api/v1/ssh-hostkeys")
    val sshKey = remember { SshKey(app.pairing.profile) }
    var hasKey by remember { mutableStateOf(sshKey.exists()) }
    var route by remember { mutableStateOf(app.pairing.sshRoute) }      // auto | Home Wi-Fi | Tailscale
    var keyDialog by remember { mutableStateOf(false) }
    val hosts = info?.optJSONArray("hosts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    val user = info?.optString("user")?.ifEmpty { null } ?: "you"
    val keyState = live(app, "/api/v1/ssh/authorize")                  // {installed} — older servers: 404 → null
    val installed = keyState.value?.optBoolean("installed")
    var installing by remember { mutableStateOf(false) }
    fun install() {
        if (!app.isAdmin) { app.toast("Only admin phones can get SSH access"); return }
        installing = true
        app.act {
            try {
                sshKey.ensure(); hasKey = true
                app.stepUp("Let this phone log in over SSH", "POST", "/api/v1/ssh/authorize", JSONObject().put("key", sshKey.openSsh()))
                keyState.value = JSONObject().put("installed", true); app.toast("Done — this phone can log in now")
            } finally { installing = false }
        }
    }

    fun connect() {
        val pinned = info?.optJSONArray("keys")?.let { a -> (0 until a.length()).map { a.getString(it) } }
        if (pinned.isNullOrEmpty() || hosts.isEmpty()) { app.toast("Couldn't load the server's details yet — try again in a moment"); return }
        val pick = when (route) {
            "auto" -> if (app.api.via == "home") hosts.first() else hosts.getOrElse(1) { hosts.first() }
            else -> hosts.firstOrNull { it.optString("name") == route } ?: hosts.first()
        }
        app.act {
            sshKey.ensure(); hasKey = true
            SshSession.unlock(app.activity)
            SshSession.connect(pick.optString("name"), pick.optString("host"), pick.optInt("port", 22), user, pinned, sshKey)
            app.go(Route.SshTerm)
        }
    }

    Page("Terminal", app::back) {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFF0B0B0D)), contentAlignment = Alignment.Center) {
                Text(">_", color = Color(0xFF3ECF6E), fontFamily = Mono, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            Text("$user@${app.overview?.optJSONObject("server")?.optString("name") ?: "server"}", color = N.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("A real shell on the server, over SSH", color = N.sub, fontSize = 14.sp)
        }
        if (SshSession.active) Group {
            Row1("Return to the open session", "Connected via ${SshSession.hostName}", true, Icons.Rounded.Terminal, N.green,
                onClick = { app.go(Route.SshTerm) })
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
            if (installed == false) PrimaryButton(if (installing) "Adding the key…" else "Let this phone log in", Modifier.fillMaxWidth(), !installing) { install() }
            else PrimaryButton(if (SshSession.state == "connecting") "Connecting…" else "Connect", Modifier.fillMaxWidth(),
                SshSession.state != "connecting") { connect() }
        }
        // The usual first-time snag: the server doesn't know this phone's key yet. One tap (and your fingerprint) fixes it.
        if (installed == false) Text("The server doesn't know this phone's key yet. Nova adds it for you after your fingerprint.",
            color = N.sub, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 30.dp))
        SectionLabel("Connect through")
        Group {
            (listOf("auto" to ("Automatic" to "Home network when you're home, otherwise the next route")) +
                hosts.map { h -> h.optString("name") to (h.optString("name") to h.optString("host") + if (h.optString("host").startsWith("100.")) " · needs Tailscale on this phone" else "") }
            ).forEachIndexed { i, (k, v) ->
                if (i > 0) RowDivider()
                Row1(v.first, v.second, route == k, onClick = { route = k; app.pairing.sshRoute = k }) { OneRadio(route == k) }
            }
        }
        SectionLabel("Security")
        Group {
            ExpandRow("This phone's SSH key", when (installed) { true -> "On the server · in the secure chip · fingerprint to use"
                    false -> "Not on the server yet"; else -> if (hasKey) "In the secure chip · fingerprint to use" else "Created the first time you connect" },
                hasKey, Icons.Rounded.Key) {
                Detail("Nova adds this phone's key to $user's ~/.ssh/authorized_keys for you (fingerprint-confirmed), and removes it again if you remove this phone. Port and agent forwarding are off for it.")
                if (installed != true) PillButton(if (installing) "Adding…" else "Add this phone's key to the server") { if (!installing) install() }
                Detail("Or add it by hand, on the server or from a computer that can already log in:")
                if (!hasKey) PillButton("Create the key now") { runCatching { sshKey.ensure(); hasKey = true }.onFailure { app.toast("Set a screen lock on this phone first") } }
                else {
                    val cmd = "mkdir -p ~/.ssh && echo '${sshKey.openSsh()}' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
                    Text(cmd, color = N.text, fontFamily = Mono, fontSize = 12.sp, modifier = Modifier.clip(RoundedCornerShape(14.dp))
                        .background(N.pill).padding(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Copy command") { copy(ctx, "SSH key command", cmd); app.toast("Copied") }
                        PillButton("Copy key only") { copy(ctx, "SSH key", sshKey.openSsh()); app.toast("Copied") }
                    }
                    PillButton("Replace this key…", color = N.red) { keyDialog = true }
                }
            }
            RowDivider()
            ExpandRow("Server identity", "Pinned — checked on every connect", true, Icons.Rounded.VerifiedUser) {
                Detail("Nova gets the server's SSH host keys over its signed connection and refuses any server that doesn't present one of them, so a look-alike can't intercept your session.")
                info?.optJSONArray("keys")?.let { a -> for (i in 0 until a.length()) Detail(a.getString(i).take(48) + "…") }
            }
            RowDivider()
            ExpandRow("Away from home", "Turn on Tailscale", false, Icons.Rounded.Public) {
                Detail("SSH isn't open to the internet — only to your home network and Tailscale. That keeps the most powerful door to the server off the public internet. Everything else in Nova works anywhere through Cloudflare.")
            }
        }
    }
    if (keyDialog) OneDialog({ keyDialog = false }, "Replace this phone's SSH key?",
        "The old key stops working here. Remove it from ~/.ssh/authorized_keys on the server and add the new one.",
        listOf(DialogButton("Cancel") { keyDialog = false }, DialogButton("Replace", N.red) {
            keyDialog = false; SshSession.disconnect(); sshKey.delete(); runCatching { sshKey.ensure() }; hasKey = sshKey.exists(); app.toast("New key created") }))
}

/** The terminal itself. */
@Composable fun SshTermScreen(app: AppState) {
    val term = SshSession.term
    val v = term.version
    val text = remember(v) { term.text() }
    var input by remember { mutableStateOf("") }
    val history = remember { mutableStateListOf<String>() }
    var hIndex by remember { mutableIntStateOf(-1) }
    val scroll = rememberScrollState()
    LaunchedEffect(v) { scroll.scrollTo(scroll.maxValue) }
    fun submit() { if (input.isNotEmpty()) history.add(input); hIndex = -1; SshSession.send(input + "\n"); input = "" }

    Column(Modifier.fillMaxSize().imePadding()) {
        FixedTopBar(when (SshSession.state) { "open" -> "Terminal · ${SshSession.hostName}"; "connecting" -> "Connecting…"; else -> "Terminal · disconnected" },
            app::back, listOf(if (SshSession.state == "open") TopAction(Icons.Rounded.LinkOff, "Disconnect") { SshSession.disconnect() }
                              else TopAction(Icons.Rounded.Refresh, "Reconnect") { app.back(); app.go(Route.Ssh) }))
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF0B0B0D))) {
            SelectionContainer {
                Text(text, color = Color(0xFFE6E6EA), fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp,
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp))
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Tab" to "\t", "Ctrl-C" to "\u0003", "Ctrl-D" to "\u0004", "Esc" to "\u001b", "Ctrl-L" to "\u000c",
                "↑" to "HIST_UP", "↓" to "HIST_DOWN", "|" to "|", "~" to "~", "/" to "/", "-" to "-",
                "top" to "top -bn1 | head -20\n", "df -h" to "df -h\n", "docker ps" to "docker ps\n").forEach { (label, k) ->
                Chip(label, mono = true) {
                    when (k) {
                        "HIST_UP" -> if (history.isNotEmpty()) { hIndex = if (hIndex < 0) history.lastIndex else maxOf(0, hIndex - 1); input = history[hIndex] }
                        "HIST_DOWN" -> if (hIndex >= 0) { hIndex++; input = if (hIndex > history.lastIndex) { hIndex = -1; "" } else history[hIndex] }
                        "|", "~", "/", "-" -> input += k
                        "\u000c" -> { term.clear(); SshSession.send(k) }
                        else -> { if (k == "\t") { SshSession.send(input + "\t"); input = "" } else SshSession.send(k) }
                    }
                }
            }
        }
        Row(Modifier.padding(start = 12.dp, end = 6.dp, bottom = 12.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
            OneTextField(input, { input = it }, if (SshSession.state == "open") "command" else "not connected", Modifier.weight(1f), mono = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }))
            IconButton({ submit() }) { Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = N.blue) }
        }
    }
}
