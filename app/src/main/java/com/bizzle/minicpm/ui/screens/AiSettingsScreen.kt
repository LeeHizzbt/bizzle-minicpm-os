package com.bizzle.minicpm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.core.*
import com.bizzle.minicpm.engine.EngineState
import com.bizzle.minicpm.ui.Nav
import com.bizzle.minicpm.ui.components.*
import com.bizzle.minicpm.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsScreen(nav: Nav, onBizzy: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by app.store.ai.collectAsStateWithLifecycle()
    val state by app.hub.state.collectAsStateWithLifecycle()
    val loaded = (state as? EngineState.Ready)?.spec
    val gpu = remember { app.hub.gpuAvailable() }
    val cores = Runtime.getRuntime().availableProcessors()
    var loadedWith by remember { mutableStateOf(s) }
    LaunchedEffect(loaded?.id) { loadedWith = s }
    fun set(v: AiSettings) = app.store.setAi(v)
    val needsReload = loaded != null && (s.contextSize != loadedWith.contextSize || s.threads != loadedWith.threads || s.useGpu != loadedWith.useGpu || s.detailVision != loadedWith.detailVision)

    BizzleScaffold("Advanced AI Settings", onBack = { nav.back() }, onBizzy = onBizzy) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (needsReload) SectionCard {
                Body("Context, threads, GPU and detail vision apply when the model loads.", size = 13)
                Spacer(Modifier.height(6.dp))
                NeonButton("Reload ${loaded!!.family} now", icon = Icons.Default.Refresh) { scope.launch { app.hub.load(loaded)?.let { toast(ctx, it) }; loadedWith = app.store.ai.value } }
            }
            SectionCard("Quality vs speed (MiniCPM-V 4.7)") {
                val q8 = ModelCatalog.byId("minicpmv47-1b-q8")!!; val q4 = ModelCatalog.byId("minicpmv47-1b-q4km")!!
                Body("Q8_0 = best quality (default). Q4_K_M = faster and lighter, a little less accurate.", alpha = 0.75f, size = 13)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(q8 to "Q8_0 best", q4 to "Q4_K_M fast").forEach { (m, label) ->
                        val on = loaded?.id == m.id
                        if (on) NeonButton("✓ $label") {} else NeonOutlineButton(label) {
                            if (!app.files.isReady(m)) { toast(ctx, "Download ${m.name} in Models first"); nav.go(com.bizzle.minicpm.ui.Screen.Models) }
                            else scope.launch { app.hub.load(m)?.let { toast(ctx, it) } }
                        }
                    }
                }
            }
            SectionCard("Sampling") {
                LabeledSlider("Temperature", s.temperature, 0f..1.5f, format = { "%.2f".format(it) }, help = "Lower = focused and repeatable (good for reading text). Higher = more creative.") { set(s.copy(temperature = (it * 20).roundToInt() / 20f)) }
                LabeledSlider("Top-p", s.topP, 0.1f..1f, format = { "%.2f".format(it) }) { set(s.copy(topP = (it * 20).roundToInt() / 20f)) }
                LabeledSlider("Top-k", s.topK.toFloat(), 1f..100f, format = { it.roundToInt().toString() }) { set(s.copy(topK = it.roundToInt())) }
                LabeledSlider("Repeat penalty", s.repeatPenalty, 1f..1.3f, format = { "%.2f".format(it) }) { set(s.copy(repeatPenalty = (it * 100).roundToInt() / 100f)) }
                LabeledSlider("Max reply tokens", s.maxTokens.toFloat(), 64f..4096f, format = { it.roundToInt().toString() }) { set(s.copy(maxTokens = (it / 64).roundToInt() * 64)) }
                TextButton(onClick = { set(AiSettings(systemPrompt = s.systemPrompt)) }) { Text("Reset to defaults", color = pal.accent) }
            }
            SectionCard("Memory & CPU") {
                val ctxOptions = listOf(2048, 4096, 8192, 16384, 32768)
                LabeledSlider("Context window", ctxOptions.indexOf(s.contextSize).coerceAtLeast(0).toFloat(), 0f..(ctxOptions.size - 1).toFloat(), steps = ctxOptions.size - 2,
                    format = { "${ctxOptions[it.roundToInt()]} tokens" }, help = "Bigger remembers more of the chat but uses more RAM. An image uses about 700 tokens.") { set(s.copy(contextSize = ctxOptions[it.roundToInt()])) }
                LabeledSlider("CPU threads", s.threads.toFloat(), 0f..8f, steps = 7, format = { if (it.roundToInt() == 0) "Auto (${AiSettings.autoThreads(cores)})" else it.roundToInt().toString() },
                    help = "This phone has $cores cores. Auto leaves the little cores free so the phone stays smooth.") { set(s.copy(threads = it.roundToInt())) }
                SettingSwitch("Use GPU (llama.cpp)", if (gpu) "Experimental. Off is usually faster and cooler on Tensor." else "Not available in this build. llama.cpp runs on the CPU; Gemma models can use the GPU/AI chip through LiteRT-LM.",
                    checked = s.useGpu && gpu, enabled = gpu) { set(s.copy(useGpu = it)) }
                SettingSwitch("Detail vision (4× projector)", "Sharper reading of tiny text with MiniCPM-V. Needs the optional projector download, and is slower.", checked = s.detailVision) { set(s.copy(detailVision = it)) }
                SettingSwitch("Auto-unload in background", "Frees RAM 1 minute after you leave the app (stays loaded while the local API is on).", checked = s.autoUnload) { set(s.copy(autoUnload = it)) }
            }
            SectionCard("Thinking") {
                SettingSwitch("Think before answering", "MiniCPM-V 4.7 and Qwen3.5 can reason first. Slower, sometimes smarter. Shown in a collapsible box.", checked = s.thinking) { set(s.copy(thinking = it)) }
            }
            SectionCard("System prompt") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SystemPrompts.presets.forEach { (name, p) ->
                        FilterChip(selected = s.systemPrompt == p, onClick = { set(s.copy(systemPrompt = p)) }, label = { Text(name) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = pal.accent, selectedLabelColor = pal.onAccent, labelColor = pal.text))
                    }
                }
                Spacer(Modifier.height(8.dp))
                NeonField(s.systemPrompt, { set(s.copy(systemPrompt = it)) }, "System prompt", Modifier.fillMaxWidth(), singleLine = false, minLines = 3)
            }
            Spacer(Modifier.height(70.dp))
        }
    }
}
