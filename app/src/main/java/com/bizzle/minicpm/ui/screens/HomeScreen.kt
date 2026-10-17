package com.bizzle.minicpm.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.R
import com.bizzle.minicpm.core.Bytes
import com.bizzle.minicpm.core.ModelCatalog
import com.bizzle.minicpm.data.DeviceStatus
import com.bizzle.minicpm.engine.EngineState
import com.bizzle.minicpm.ui.Nav
import com.bizzle.minicpm.ui.Screen
import com.bizzle.minicpm.ui.components.*
import com.bizzle.minicpm.ui.theme.LocalPalette
import com.bizzle.minicpm.ui.theme.Warn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(nav: Nav, onBizzy: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val state by app.hub.state.collectAsStateWithLifecycle()
    val apiOn by app.api.running.collectAsStateWithLifecycle()
    val dls by app.downloads.states.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    BizzleScaffold("Bizzle's Mini CPM v4.7 OS", onBack = null, onBizzy = onBizzy) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Brand hero
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(pal.surface).border(1.5.dp, pal.accent, RoundedCornerShape(20.dp)).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.ic_launcher_foreground), "Bizzle logo", Modifier.size(84.dp))
                Text("Bizzle's Mini CPM", color = pal.accent, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Text("v4.7 OS", color = pal.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Pill("v4.20"); Pill("100% OFFLINE"); Pill("ON-DEVICE") }
                Spacer(Modifier.height(6.dp))
                Body("Private AI that runs on your Pixel. No cloud, no account.", alpha = 0.75f, size = 13)
            }
            StatusCard(nav)
            // first run
            val def = ModelCatalog.default
            if (ModelCatalog.all.none { app.files.isReady(it) }) {
                SectionCard("Step 1: get the brain") {
                    Body("Download ${def.name} (${Bytes.human(def.requiredBytes)}, one time, Wi-Fi recommended). After that everything works offline.")
                    Spacer(Modifier.height(8.dp))
                    val d = dls[def.id]
                    if (d != null && d.status.name in listOf("RUNNING", "QUEUED", "VERIFYING")) {
                        LinearProgressIndicator(progress = { d.fraction }, Modifier.fillMaxWidth(), color = pal.accent, trackColor = pal.accent.copy(alpha = 0.2f))
                        Body("${Bytes.human(d.done)} / ${Bytes.human(d.total)} · ${d.message}", alpha = 0.7f, size = 12)
                    } else NeonButton("Download default model", icon = Icons.Default.Download) {
                        app.downloads.start(def)?.let { toast(ctx, it) }
                    }
                }
            } else if (state is EngineState.Idle) {
                val sel = ModelCatalog.byId(app.store.selectedModelId)?.takeIf { app.files.isReady(it) } ?: ModelCatalog.all.first { app.files.isReady(it) }
                SectionCard("Ready when you are") {
                    Body("${sel.name} is downloaded.")
                    Spacer(Modifier.height(8.dp))
                    NeonButton("Load ${sel.family}", icon = Icons.Default.PlayArrow) { scope.launch { app.hub.load(sel)?.let { toast(ctx, it) } } }
                }
            }
            val tiles = listOf(
                Triple("Chat", Icons.Default.Chat, { nav.go(Screen.Chat(app.store.newChatId())) }),
                Triple("Camera", Icons.Default.PhotoCamera, { nav.go(Screen.Chat(app.store.newChatId(), openCamera = true)) }),
                Triple("Models", Icons.Default.Memory, { nav.go(Screen.Models) }),
                Triple("Advanced AI Settings", Icons.Default.Tune, { nav.go(Screen.AiSettings) }),
                Triple("AI Themes", Icons.Default.Palette, { nav.go(Screen.Themes) }),
                Triple(if (apiOn) "Local API · ON" else "Local API", Icons.Default.Hub, { nav.go(Screen.Api) }),
                Triple("History", Icons.Default.History, { nav.go(Screen.History) }),
                Triple("Limits", Icons.Default.Info, { nav.go(Screen.Limits) }),
                Triple("About", Icons.Default.Gavel, { nav.go(Screen.About) }),
            )
            tiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, icon, action) -> HomeTile(label, icon, Modifier.weight(1f), action) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(70.dp))
        }
    }
}

@Composable
private fun HomeTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Column(modifier.height(96.dp).clip(RoundedCornerShape(16.dp)).background(pal.surface).border(1.dp, pal.accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        .clickable(onClick = onClick).padding(8.dp).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = pal.accent, modifier = Modifier.size(30.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = pal.text, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 14.sp, maxLines = 2)
    }
}

/** Loaded model + RAM + thermal chip, shown on Home and Models. */
@Composable
fun StatusCard(nav: Nav) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val state by app.hub.state.collectAsStateWithLifecycle()
    val busy by app.hub.busy.collectAsStateWithLifecycle()
    var snap by remember { mutableStateOf(DeviceStatus.snapshot(ctx)) }
    LaunchedEffect(Unit) { while (true) { snap = DeviceStatus.snapshot(ctx); delay(3000) } }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                when (val s = state) {
                    EngineState.Idle -> { Text("No model loaded", color = pal.text, fontWeight = FontWeight.Bold); Body("Tap Models to load one", alpha = 0.6f, size = 12) }
                    is EngineState.Loading -> { Text("Loading ${s.name}…", color = pal.accent, fontWeight = FontWeight.Bold); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = pal.accent) }
                    is EngineState.Ready -> { Text(s.spec.name, color = pal.accent, fontWeight = FontWeight.Bold); Body(s.backend + (if (s.vision) " · vision ✓" else " · text only") + (busy?.let { " · busy ($it)" } ?: ""), alpha = 0.7f, size = 12) }
                    is EngineState.Failed -> { Text("Load failed", color = Warn, fontWeight = FontWeight.Bold); Body(s.message, alpha = 0.8f, size = 12) }
                }
            }
            IconButton(onClick = { nav.go(Screen.Models) }) { Icon(Icons.Default.Memory, "Open Models", tint = pal.accent) }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("RAM ${Bytes.human(snap.availRamBytes)} free / ${Bytes.human(snap.totalRamBytes)}", if (snap.lowMemory) Warn else pal.accent)
            Pill("🌡 ${snap.thermalLabel}", if (snap.thermal >= 2) Warn else pal.accent)
        }
    }
}
