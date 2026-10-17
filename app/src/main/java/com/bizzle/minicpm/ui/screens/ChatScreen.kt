package com.bizzle.minicpm.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.core.MarkdownLite
import com.bizzle.minicpm.core.ModelCatalog
import com.bizzle.minicpm.core.OcrGuard
import com.bizzle.minicpm.data.StoredMsg
import com.bizzle.minicpm.engine.EngineState
import com.bizzle.minicpm.ui.*
import com.bizzle.minicpm.ui.components.*
import com.bizzle.minicpm.ui.theme.LocalPalette
import com.bizzle.minicpm.ui.theme.Warn
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ChatScreen(nav: Nav, screen: Screen.Chat, onBizzy: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = remember(screen.id) { ChatSession.get(screen.id) }
    val convo by session.convo.collectAsStateWithLifecycle()
    val streaming by session.streaming.collectAsStateWithLifecycle()
    val state by app.hub.state.collectAsStateWithLifecycle()
    var input by remember(screen.id) { mutableStateOf(screen.sharedText ?: "") }
    var attached by remember(screen.id) { mutableStateOf(listOfNotNull(screen.sharedImage)) }
    val list = rememberLazyListState()
    val count = convo.messages.size + (if (streaming != null) 1 else 0)
    LaunchedEffect(count, streaming?.text?.length) { if (count > 0) list.animateScrollToItem(count - 1) }

    var cameraFile by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) ImageUtil.import(ctx, Uri.fromFile(f))?.let { attached = listOf(it) } ?: toast(ctx, "Couldn't read the photo")
        f?.delete()
    }
    fun openCamera() {
        val f = ImageUtil.newCameraFile(ctx); cameraFile = f
        runCatching { camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) }.onFailure { toast(ctx, "No camera app available") }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) ImageUtil.import(ctx, uri)?.let { attached = listOf(it) } ?: toast(ctx, "Couldn't read that image")
    }
    LaunchedEffect(screen.id) { if (screen.openCamera && convo.messages.isEmpty()) openCamera() }

    val ready = state as? EngineState.Ready
    BizzleScaffold(convo.title.takeIf { convo.messages.isNotEmpty() } ?: "Chat", onBack = { nav.back() }, onBizzy = onBizzy, actions = {
        IconButton(onClick = { nav.go(Screen.AiSettings) }) { Icon(Icons.Default.Tune, "Advanced AI Settings", tint = pal.accent) }
        IconButton(onClick = { nav.replace(Screen.Chat(app.store.newChatId())) }) { Icon(Icons.Default.AddComment, "New chat", tint = pal.accent) }
    }, bottomBar = {
        Column(Modifier.fillMaxWidth().background(pal.bg).padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (attached.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                attached.forEach { Thumb(it, 64) }
                Spacer(Modifier.width(8.dp))
                Body(if (ready?.vision == false) "This model is text-only; the image will be ignored." else "Image attached", alpha = 0.7f, size = 12, modifier = Modifier.weight(1f))
                IconButton(onClick = { attached = emptyList() }) { Icon(Icons.Default.Close, "Remove image", tint = pal.accent) }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { openCamera() }) { Icon(Icons.Default.PhotoCamera, "Take a photo", tint = pal.accent) }
                IconButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Icon(Icons.Default.AddPhotoAlternate, "Upload a screenshot or photo", tint = pal.accent) }
                NeonField(input, { input = it }, if (ready != null) "Message ${ready.spec.family}" else "Load a model to chat", Modifier.weight(1f), singleLine = false, maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                if (streaming != null) IconButton(onClick = { session.stop() }) { Icon(Icons.Default.StopCircle, "Stop", tint = pal.accent, modifier = Modifier.size(34.dp)) }
                else IconButton(onClick = {
                    if (ready == null) toast(ctx, "Load a model first (Models screen)") else { session.send(input.trim(), attached); input = ""; attached = emptyList() }
                }, enabled = input.isNotBlank() || attached.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = pal.accent, modifier = Modifier.size(30.dp)) }
            }
        }
    }) {
        // model banner
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (val s = state) {
                is EngineState.Ready -> { Pill("● ${s.spec.name}"); Spacer(Modifier.width(6.dp)); Body(s.backend, alpha = 0.55f, size = 11, modifier = Modifier.weight(1f)) }
                is EngineState.Loading -> { CircularProgressIndicator(Modifier.size(16.dp), color = pal.accent, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Body("Loading ${s.name}…", size = 12) }
                else -> {
                    val sel = (ModelCatalog.byId(app.store.selectedModelId) ?: ModelCatalog.default).let { if (app.files.isReady(it)) it else ModelCatalog.all.firstOrNull { m -> app.files.isReady(m) } }
                    Body(if (sel == null) "No model downloaded yet." else "No model loaded.", alpha = 0.8f, size = 13, modifier = Modifier.weight(1f))
                    if (sel != null) NeonButton("Load ${sel.family}") { scope.launch { app.hub.load(sel)?.let { toast(ctx, it) } } }
                    else NeonButton("Get a model") { nav.go(Screen.Models) }
                }
            }
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (convo.messages.isEmpty() && streaming == null) item { EmptyChatHints { input = it } }
            itemsIndexed(convo.messages) { i, m ->
                val prevHadImage = i > 0 && convo.messages[i - 1].images.isNotEmpty()
                MessageBubble(m, isLast = i == convo.messages.lastIndex && streaming == null, afterImage = prevHadImage, onRegenerate = { session.regenerate() })
            }
            streaming?.let { s -> item { MessageBubble(s, isLast = false, afterImage = false, live = true, onRegenerate = {}) } }
            item { Spacer(Modifier.height(64.dp)) }
        }
    }
}

@Composable
private fun EmptyChatHints(onPick: (String) -> Unit) {
    val pal = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ask anything. It all stays on your phone.", color = pal.accent, fontWeight = FontWeight.Bold)
        listOf("What's in this screenshot and what should I tap?", "Read the serial number in this photo", "Explain this error message simply", "Write a short caption for this picture")
            .forEach { s -> Text("› $s", color = pal.text.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.clickable { onPick(s) }.padding(vertical = 4.dp)) }
    }
}

@Composable
fun Thumb(path: String, sizeDp: Int) {
    val bmp = remember(path) { runCatching { BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull() }
    if (bmp != null) Image(bmp, "Attached image", Modifier.size(sizeDp.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
}

@Composable
fun MessageBubble(m: StoredMsg, isLast: Boolean, afterImage: Boolean, live: Boolean = false, onRegenerate: () -> Unit) {
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val mine = m.role == "user"
    var showThinking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(if (mine) 0.85f else 0.97f).clip(RoundedCornerShape(16.dp))
            .background(if (mine) pal.accent.copy(alpha = 0.15f) else pal.surface)
            .border(1.dp, if (m.error) Warn else pal.accent.copy(alpha = if (mine) 0.55f else 0.28f), RoundedCornerShape(16.dp)).padding(12.dp)) {
            if (m.images.isNotEmpty()) { Row { m.images.forEach { Thumb(it, 140) } }; Spacer(Modifier.height(6.dp)) }
            if (m.reasoning.isNotBlank()) {
                Text(if (showThinking) "▾ Thinking" else "▸ Thinking (tap to show)", color = pal.accent.copy(alpha = 0.8f), fontSize = 12.sp,
                    modifier = Modifier.clickable { showThinking = !showThinking }.padding(bottom = 4.dp))
                if (showThinking || (live && m.text.isEmpty())) Text(m.reasoning, color = pal.text.copy(alpha = 0.55f), fontSize = 12.sp, lineHeight = 16.sp)
            }
            if (live && m.text.isEmpty() && m.reasoning.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = pal.accent, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Body("Reading your message…", alpha = 0.7f, size = 13)
            }
            SelectionContainer {
                Column {
                    MarkdownLite.split(m.text).forEach { seg ->
                        when (seg) {
                            is MarkdownLite.Seg.Text -> Text(seg.text, color = pal.text, fontSize = 15.sp, lineHeight = 22.sp)
                            is MarkdownLite.Seg.Code -> Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(pal.bg).border(1.dp, pal.accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                                    Text(seg.lang.ifBlank { "code" }, color = pal.accent, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { copyText(ctx, seg.code, "Code copied") }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, "Copy code", tint = pal.accent, modifier = Modifier.size(16.dp)) }
                                }
                                Text(seg.code, color = pal.text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.horizontalScroll(rememberScrollState()).padding(10.dp))
                            }
                        }
                    }
                }
            }
            if (!mine && !live && afterImage) {
                val risky = OcrGuard.riskyTokens(m.text)
                if (risky.isNotEmpty()) Text("⚠️ Double-check codes: ${risky.take(3).joinToString()} (small models can mix up l/1, O/0, S/5, B/8)", color = Warn, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 6.dp))
            }
            if (!mine && !live) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(m.stats, color = pal.text.copy(alpha = 0.45f), fontSize = 11.sp, modifier = Modifier.weight(1f), lineHeight = 14.sp)
                if (isLast) IconButton(onClick = onRegenerate, modifier = Modifier.size(34.dp)) { Icon(Icons.Default.Refresh, "Regenerate reply", tint = pal.accent, modifier = Modifier.size(18.dp)) }
                IconButton(onClick = { copyText(ctx, m.text, "Reply copied") }, modifier = Modifier.size(34.dp).semantics { contentDescription = "Copy reply" }) {
                    Icon(Icons.Default.ContentCopy, null, tint = pal.accent, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
