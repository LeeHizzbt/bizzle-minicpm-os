package com.bizzle.minicpm.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.R
import com.bizzle.minicpm.core.Bizzy
import com.bizzle.minicpm.core.ChatMsg
import com.bizzle.minicpm.core.ThinkParser
import com.bizzle.minicpm.data.StoredMsg
import com.bizzle.minicpm.engine.EngineState
import com.bizzle.minicpm.ui.Nav
import com.bizzle.minicpm.ui.Screen
import com.bizzle.minicpm.ui.theme.LocalPalette
import kotlinx.coroutines.launch

/** Bizzy pop-up helper: offline tips + chat with the loaded model, with on-device memory ("remember …"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BizzyPopup(screen: String, nav: Nav, onClose: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val chat by app.store.bizzyChat.collectAsStateWithLifecycle()
    val memory by app.store.memory.collectAsStateWithLifecycle()
    val state by app.hub.state.collectAsStateWithLifecycle()
    val apiOn by app.api.running.collectAsStateWithLifecycle()
    val loaded = state is EngineState.Ready
    var input by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf<String?>(null) }
    var showMemory by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(chat.size, streaming) { val n = chat.size + 1 + (if (streaming != null) 1 else 0); if (n > 0) list.animateScrollToItem(n - 1) }

    fun send() {
        val text = input.trim(); if (text.isEmpty() || streaming != null) return
        input = ""
        val withUser = chat + StoredMsg("user", text)
        app.store.setBizzyChat(withUser)
        when (val cmd = Bizzy.parse(text)) {
            is Bizzy.Command.Remember -> { app.store.remember(cmd.fact); app.store.setBizzyChat(withUser + StoredMsg("assistant", "Got it, I'll remember: \"${cmd.fact}\". It stays on this phone.")) }
            is Bizzy.Command.Forget -> { val n = app.store.forget(cmd.what); app.store.setBizzyChat(withUser + StoredMsg("assistant", if (n > 0) "Forgot $n thing(s) about \"${cmd.what}\"." else "I didn't have anything saved about \"${cmd.what}\".")) }
            Bizzy.Command.ListMemory -> app.store.setBizzyChat(withUser + StoredMsg("assistant", if (app.store.memory.value.isEmpty()) "Nothing saved yet. Say \"remember …\"." else app.store.memory.value.joinToString("\n") { "• $it" }))
            Bizzy.Command.None -> {
                if (!loaded) { app.store.setBizzyChat(withUser + StoredMsg("assistant", Bizzy.tip(screen, false, apiOn))); return }
                streaming = ""
                scope.launch {
                    val hub = app.hub
                    val opts = hub.options().copy(maxTokens = 512, thinking = false)
                    val sys = Bizzy.systemPrompt(app.store.ai.value.systemPrompt, app.store.memory.value, screen)
                    val msgs = hub.prepare(sys, withUser.takeLast(10).map { ChatMsg(it.role, it.text) }, opts)
                    val raw = StringBuilder(); var think = false
                    val r = hub.generate("bizzy", msgs, opts, { think = it }) { p -> raw.append(p); streaming = ThinkParser.parse(raw.toString(), think).answer; true }
                    val answer = if (r.error != null) "⚠️ ${r.error}" else ThinkParser.parse(r.text, r.startsInThink || think).answer
                    app.store.setBizzyChat(withUser + StoredMsg("assistant", answer, error = r.error != null))
                    streaming = null
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.bg, contentColor = pal.text) {
        Column(Modifier.fillMaxHeight(0.88f).padding(horizontal = 14.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.bizzy_avatar), null, Modifier.size(40.dp).clip(CircleShape).border(1.5.dp, pal.accent, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Bizzy", color = pal.accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(if (loaded) "Offline AI · ${(state as EngineState.Ready).spec.name}" else "Offline tips (load a model for full AI)", color = pal.text.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = { showMemory = !showMemory }) { Icon(Icons.Default.Psychology, "Bizzy memory", tint = pal.accent) }
                IconButton(onClick = { onClose(); nav.go(Screen.AiSettings) }) { Icon(Icons.Default.Tune, "Advanced AI Settings", tint = pal.accent) }
            }
            if (showMemory) SectionCard("Bizzy's memory (on this phone only)") {
                if (memory.isEmpty()) Body("Empty. Say \"remember my Pixel has 512 GB\".", alpha = 0.7f)
                memory.forEachIndexed { i, m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Body("• $m", Modifier.weight(1f))
                        IconButton(onClick = { app.store.forgetAt(i) }) { Icon(Icons.Default.Close, "Forget $m", tint = pal.accent) }
                    }
                }
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(pal.surface).border(1.dp, pal.accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                        Text("💡 Tip for $screen", color = pal.accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Body(Bizzy.tip(screen, loaded, apiOn), size = 13)
                    }
                }
                items(chat) { m -> MiniBubble(m.role == "user", m.text) { copyText(ctx, m.text) } }
                streaming?.let { s -> item { MiniBubble(false, s.ifEmpty { "…" }) {} } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                NeonField(input, { input = it }, "Ask Bizzy or say \"remember …\"", Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
                if (streaming != null) IconButton(onClick = { app.hub.cancel() }) { Icon(Icons.Default.Stop, "Stop Bizzy", tint = pal.accent) }
                else IconButton(onClick = { send() }) { Icon(Icons.AutoMirrored.Filled.Send, "Send to Bizzy", tint = pal.accent) }
            }
            if (chat.isNotEmpty()) TextButton(onClick = { app.store.setBizzyChat(emptyList()) }) { Text("Clear Bizzy chat", color = pal.text.copy(alpha = 0.6f), fontSize = 12.sp) }
        }
    }
}

@Composable
private fun MiniBubble(mine: Boolean, text: String, onCopy: () -> Unit) {
    val pal = LocalPalette.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.86f).clip(RoundedCornerShape(14.dp)).background(if (mine) pal.accent.copy(alpha = 0.16f) else pal.surface)
            .border(1.dp, pal.accent.copy(alpha = if (mine) 0.5f else 0.25f), RoundedCornerShape(14.dp)).padding(10.dp)) {
            Text(text, color = pal.text, fontSize = 14.sp, lineHeight = 20.sp)
            if (!mine) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onCopy, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.ContentCopy, "Copy Bizzy reply", tint = pal.accent, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}
