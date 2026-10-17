package com.bizzle.minicpm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.server.ApiServer
import com.bizzle.minicpm.server.ApiService
import com.bizzle.minicpm.ui.Nav
import com.bizzle.minicpm.ui.components.*
import com.bizzle.minicpm.ui.theme.LocalPalette

@Composable
fun ApiScreen(nav: Nav, onBizzy: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val running by app.api.running.collectAsStateWithLifecycle()
    val requests by app.api.requests.collectAsStateWithLifecycle()
    val lastLog by app.api.lastLog.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf(app.store.apiToken) }
    var requireToken by remember { mutableStateOf(app.store.apiRequireToken) }
    var showToken by remember { mutableStateOf(false) }
    val base = "http://127.0.0.1:${ApiServer.PORT}/v1"
    val curl = buildString {
        append("curl $base/chat/completions \\\n")
        if (requireToken) append("  -H \"Authorization: Bearer <token>\" \\\n")
        append("  -H \"Content-Type: application/json\" \\\n")
        append("  -d '{\"model\":\"local\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi!\"}],\"stream\":true}'")
    }
    BizzleScaffold("Local API", onBack = { nav.back() }, onBizzy = onBizzy) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard {
                SettingSwitch("Local AI API server", if (running) "ON · $requests requests served" else "Off. Turn on to let other apps on this phone use the model.", checked = running) {
                    if (it) ApiService.start(ctx) else ApiService.stop(ctx)
                }
                Body("OpenAI-compatible. Listens on 127.0.0.1 only, so Wi-Fi devices can't reach it. Also in Quick Settings as a tile.", alpha = 0.7f, size = 12)
            }
            SectionCard("Connect Bizzy Voice, Kief & others") {
                Body("Base URL", alpha = 0.6f, size = 12)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(base, color = pal.accent, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                    IconButton(onClick = { copyText(ctx, base, "URL copied") }) { Icon(Icons.Default.ContentCopy, "Copy URL", tint = pal.accent) }
                }
                Body("Model name: anything (\"local\" uses whatever is loaded), or a model id from /v1/models.", alpha = 0.6f, size = 12)
                Spacer(Modifier.height(8.dp))
                SettingSwitch("Require API key", "Recommended. Any app on the phone could otherwise use the model.", checked = requireToken) { requireToken = it; app.store.apiRequireToken = it }
                if (requireToken) {
                    Body("API key", alpha = 0.6f, size = 12)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (showToken) token else token.take(5) + "•".repeat(12), color = pal.accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { showToken = !showToken }) { Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Show key", tint = pal.accent) }
                        IconButton(onClick = { copyText(ctx, token, "API key copied") }) { Icon(Icons.Default.ContentCopy, "Copy API key", tint = pal.accent) }
                    }
                    TextButton(onClick = { token = app.store.rotateApiToken(); toast(ctx, "New key made. Update your other apps.") }) { Text("Make a new key", color = pal.accent) }
                }
            }
            SectionCard("Try it (Termux or adb)") {
                Text(curl, color = pal.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(pal.bg).horizontalScroll(rememberScrollState()).padding(10.dp))
                Row { Spacer(Modifier.weight(1f)); TextButton(onClick = { copyText(ctx, curl, "Example copied") }) { Text("Copy example", color = pal.accent) } }
                Body("Endpoints: GET /health · GET /v1/models · POST /v1/chat/completions (stream or not, images as base64 data URLs). One request runs at a time; others wait their turn.", alpha = 0.65f, size = 12)
                if (lastLog.isNotBlank()) Body("Last: $lastLog", alpha = 0.55f, size = 11)
            }
            Spacer(Modifier.height(70.dp))
        }
    }
}
