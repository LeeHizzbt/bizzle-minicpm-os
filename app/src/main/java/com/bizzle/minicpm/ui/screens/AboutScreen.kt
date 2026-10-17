package com.bizzle.minicpm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.core.ModelCatalog
import com.bizzle.minicpm.data.DeviceStatus
import com.bizzle.minicpm.ui.Nav
import com.bizzle.minicpm.ui.components.*
import com.bizzle.minicpm.ui.theme.LocalPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AboutScreen(nav: Nav, onBizzy: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    var sys by remember { mutableStateOf("…") }
    LaunchedEffect(Unit) { sys = withContext(Dispatchers.Default) { app.hub.systemInfo() } }
    val snap = remember { DeviceStatus.snapshot(ctx) }
    BizzleScaffold("About", onBack = { nav.back() }, onBizzy = onBizzy) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Bizzle's Mini CPM v4.7 OS · v4.20") {
                Body("An offline AI hub for your Pixel. Chat, camera vision, model manager and a local API, all on-device. No accounts, no tracking, no cloud.", alpha = 0.85f)
            }
            SectionCard("Open-source credits") {
                listOf(
                    "llama.cpp / ggml (MIT) · github.com/ggml-org/llama.cpp, build b11714",
                    "LiteRT-LM (Apache-2.0) · github.com/google-ai-edge/LiteRT-LM",
                    "Jetpack Compose, Kotlin, kotlinx (Apache-2.0)",
                ).forEach { Body("• $it", alpha = 0.8f, size = 13) }
            }
            SectionCard("Model licenses") {
                ModelCatalog.all.forEach { Body("• ${it.name}: ${it.license}", alpha = 0.8f, size = 13) }
                Body("MiniCPM-V 4.7 by OpenBMB. Gemma 4 by Google. Qwen3.5 by Alibaba Qwen. LFM2.5 by Liquid AI (LFM Open License v1.0, free for personal use).", alpha = 0.6f, size = 12)
            }
            SectionCard("This device") {
                Body("SoC: ${snap.soc} · ${snap.cores} cores · RAM ${com.bizzle.minicpm.core.Bytes.human(snap.totalRamBytes)}", alpha = 0.8f, size = 13)
                Body("Tensor G5: ${if (DeviceStatus.isTensorG5()) "yes" else "no"} · GPU backend: ${if (app.hub.gpuAvailable()) "yes" else "no"}", alpha = 0.8f, size = 13)
                Spacer(Modifier.height(6.dp))
                Text(sys, color = pal.text.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                TextButton2("Copy system info") { copyText(ctx, "${snap.soc}\n$sys") }
            }
            Spacer(Modifier.height(70.dp))
        }
    }
}

@Composable
private fun TextButton2(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(text, color = LocalPalette.current.accent) }
}
