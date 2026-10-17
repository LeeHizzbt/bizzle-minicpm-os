package com.bizzle.minicpm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.core.*
import com.bizzle.minicpm.download.DlState
import com.bizzle.minicpm.engine.EngineState
import com.bizzle.minicpm.ui.Nav
import com.bizzle.minicpm.ui.components.*
import com.bizzle.minicpm.ui.theme.LocalPalette
import com.bizzle.minicpm.ui.theme.Warn
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen(nav: Nav, onBizzy: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val dls by app.downloads.states.collectAsStateWithLifecycle()
    val state by app.hub.state.collectAsStateWithLifecycle()
    val bench by app.store.bench.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(dls.values.map { it.status }) { refresh++ }
    BizzleScaffold("Models", onBack = { nav.back() }, onBizzy = onBizzy) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(nav)
            key(refresh) {
                val used = app.files.usedBytes(); val free = app.files.freeBytes()
                SectionCard("Storage") {
                    LinearProgressIndicator(progress = { (used.toFloat() / (used + free).coerceAtLeast(1)).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = pal.accent, trackColor = pal.accent.copy(alpha = 0.2f))
                    Spacer(Modifier.height(4.dp))
                    Body("Models use ${Bytes.human(used)} · ${Bytes.human(free)} free on phone", alpha = 0.75f, size = 13)
                    if (app.downloads.isMetered()) Body("You're on mobile data. Big downloads are better on Wi-Fi.", alpha = 0.9f, size = 12)
                }
            }
            ModelCatalog.all.forEach { m -> key(m.id, refresh) { ModelCard(m, dls[m.id], state, bench.lastOrNull { it.modelId == m.id }) { refresh++ } } }
            SectionCard("About these models") {
                Body("All downloads come straight from Hugging Face (no account) and are checked with SHA-256. Models are never bundled in the app. MiniCPM-V 4.7 35B-A3B is not offered: it needs about 16.6 GB and has no Android runtime yet.", alpha = 0.75f, size = 12)
            }
            Spacer(Modifier.height(70.dp))
        }
    }
}

@Composable
private fun ModelCard(m: ModelSpec, dl: DlState?, state: EngineState, lastBench: com.bizzle.minicpm.data.BenchRecord?, onChanged: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready = app.files.isReady(m)
    val loaded = (state as? EngineState.Ready)?.spec?.id == m.id
    val loading = (state as? EngineState.Loading)?.modelId == m.id
    val active = dl != null && dl.status in setOf(DlState.Status.RUNNING, DlState.Status.QUEUED, DlState.Status.VERIFYING)
    var confirmDelete by remember { mutableStateOf(false) }
    var benchRunning by remember { mutableStateOf(false) }
    var benchText by remember { mutableStateOf<String?>(lastBench?.let { "Last benchmark: %.1f tok/s generate · %.0f tok/s prompt".format(it.genTps, it.promptTps) }) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name, color = pal.accent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Body("${m.quant} · ${Bytes.human(m.requiredBytes)} · ${if (m.engine == EngineKind.LLAMA_CPP) "llama.cpp" else "LiteRT-LM"} · ${if (m.vision) "vision" else "text"}", alpha = 0.65f, size = 12)
            }
            if (m.badge.isNotEmpty()) Pill(m.badge, if (m.badge.contains("EXPERIMENTAL")) Warn else pal.accent)
        }
        Spacer(Modifier.height(4.dp))
        Body(m.blurb, alpha = 0.85f, size = 13)
        Body("License: ${m.license} · needs ~${m.minRamGb} GB RAM", alpha = 0.55f, size = 11)
        if (dl != null && (active || dl.status == DlState.Status.PAUSED || dl.status == DlState.Status.FAILED)) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { dl.fraction }, Modifier.fillMaxWidth(), color = if (dl.status == DlState.Status.FAILED) Warn else pal.accent, trackColor = pal.accent.copy(alpha = 0.2f))
            Body("${Bytes.human(dl.done)} / ${Bytes.human(dl.total)}" + (if (dl.speed > 0) " · ${Bytes.human(dl.speed)}/s" else "") + " · ${dl.message.ifBlank { dl.status.name.lowercase() }}",
                alpha = 0.75f, size = 12)
        } else if (!ready && app.files.hasAny(m)) Body("Partly downloaded: ${Bytes.human(app.files.missingBytes(m))} left. Tap Resume.", alpha = 0.75f, size = 12)
        benchText?.let { Body(it, alpha = 0.8f, size = 12) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                active -> NeonOutlineButton("Pause", icon = Icons.Default.Pause) { app.downloads.pause(m.id) }
                !ready -> NeonButton(if (app.files.hasAny(m)) "Resume" else "Download", icon = Icons.Default.Download) { app.downloads.start(m)?.let { toast(ctx, it) } }
                loaded -> {
                    NeonOutlineButton("Unload", icon = Icons.Default.Eject) { scope.launch { app.hub.unload() } }
                    NeonButton(if (benchRunning) "Testing…" else "Benchmark", icon = Icons.Default.Speed, enabled = !benchRunning) {
                        benchRunning = true
                        scope.launch {
                            val r = app.hub.bench()
                            benchText = r?.let { if (it.genTps > 0) "Benchmark: %.1f tok/s generate · %.0f tok/s prompt (%s)".format(it.genTps, it.promptTps, it.note) else it.note } ?: "Benchmark failed"
                            benchRunning = false
                        }
                    }
                }
                loading -> { CircularProgressIndicator(Modifier.size(22.dp), color = pal.accent); Body("Loading…", size = 13) }
                else -> NeonButton("Load", icon = Icons.Default.PlayArrow, enabled = state !is EngineState.Loading) { scope.launch { app.hub.load(m)?.let { toast(ctx, it) } } }
            }
            Spacer(Modifier.weight(1f))
            if ((ready || app.files.hasAny(m)) && !active) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete ${m.name}", tint = pal.accent) }
        }
        val detail = m.detailMmproj
        if (detail != null && ready) {
            val have = app.files.isDone(detail)
            Body(if (have) "✓ Detail vision (4×) projector downloaded. Turn on \"Detail vision\" in Advanced AI Settings." else "Optional: detail vision (4×) projector for small text, ${Bytes.human(detail.sizeBytes)}.", alpha = 0.65f, size = 12)
            if (!have && !active) TextButton(onClick = { app.downloads.start(m, detail = true)?.let { toast(ctx, it) } }) { Text("Download detail vision", color = pal.accent) }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete ${m.name}?", "Frees ${Bytes.human(m.requiredBytes)}. Files shared with another downloaded model are kept. You can download it again later.",
        onConfirm = { scope.launch { if (loaded) app.hub.unload(); app.files.delete(m); onChanged() } }, onDismiss = { confirmDelete = false })
}
