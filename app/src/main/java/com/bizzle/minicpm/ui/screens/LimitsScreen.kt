package com.bizzle.minicpm.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bizzle.minicpm.ui.Nav
import com.bizzle.minicpm.ui.components.*

val HONEST_LIMITS = listOf(
    "Small brain" to "MiniCPM-V 4.7 1B is a 1-billion-parameter model. It's great at describing images and quick help, but it makes mistakes, can invent facts, and is not a replacement for a big cloud AI.",
    "Reading codes" to "Serial numbers, Wi-Fi passwords and small text can come back with look-alike mix-ups (l/1, O/0, S/5, B/8). Always double-check. Detail vision helps.",
    "Speed" to "Text replies stream at phone speed. A photo takes several seconds to 'see' before the answer starts. Big models (Gemma 4 E4B) are slower and use more battery.",
    "Heat & battery" to "Long chats make the phone warm and drain battery. Android may slow the chip when it's hot (shown as the 🌡 chip).",
    "GPU toggle" to "llama.cpp runs on the CPU in this build. The GPU switch stays off unless a GPU backend is found. Gemma models can use the GPU through LiteRT-LM.",
    "AI chip (NPU)" to "The Gemma 4 E2B Tensor G5 model uses Google's AI chip through LiteRT-LM. It's experimental: it only works on Pixel 10 phones whose system includes the Tensor runtime, and was not tested on a real Pixel before release.",
    "Memory" to "One model is loaded at a time and shared by Chat, Bizzy and the local API. Loading a big model can close other apps in the background.",
    "Images" to "One image per message; older images in a chat are dropped from the AI's memory to save space (they stay visible). No video or audio yet.",
    "Context" to "Very long chats get trimmed from the oldest messages so they fit the context window.",
    "Offline" to "Everything runs on the phone after the model download. No web search, so it doesn't know recent news.",
    "Local API" to "Only apps on this phone can reach it (127.0.0.1). One request at a time. Images must be base64 data URLs, not web links.",
    "Downloads" to "Models come from Hugging Face (2–5 GB each). Use Wi-Fi. Downloads resume if interrupted and are checked with SHA-256.",
    "Not for" to "Medical, legal, money or safety decisions. Check anything important.",
)

@Composable
fun LimitsScreen(nav: Nav, onBizzy: () -> Unit) {
    BizzleScaffold("Limits (the honest list)", onBack = { nav.back() }, onBizzy = onBizzy) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HONEST_LIMITS.forEach { (t, d) -> SectionCard(t) { Body(d, alpha = 0.85f, size = 14) } }
            Spacer(Modifier.height(70.dp))
        }
    }
}
