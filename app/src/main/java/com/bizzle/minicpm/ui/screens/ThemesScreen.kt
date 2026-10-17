package com.bizzle.minicpm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.core.ChatMsg
import com.bizzle.minicpm.core.GenOptions
import com.bizzle.minicpm.core.ThemeSpec
import com.bizzle.minicpm.core.ThinkParser
import com.bizzle.minicpm.engine.EngineState
import com.bizzle.minicpm.ui.Nav
import com.bizzle.minicpm.ui.components.*
import com.bizzle.minicpm.ui.theme.LocalPalette
import kotlinx.coroutines.launch

@Composable
fun ThemesScreen(nav: Nav, onBizzy: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val current by app.store.theme.collectAsStateWithLifecycle()
    val custom by app.store.customThemes.collectAsStateWithLifecycle()
    val state by app.hub.state.collectAsStateWithLifecycle()
    var prompt by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    BizzleScaffold("AI Themes", onBack = { nav.back() }, onBizzy = onBizzy) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard("Make a theme with AI") {
                Body(if (state is EngineState.Ready) "Describe a vibe. The loaded model picks a color; contrast is checked so text stays readable." else "Describe a vibe. (No model loaded, so color words like \"ocean\" or #hex codes are used.)", alpha = 0.75f, size = 13)
                Spacer(Modifier.height(6.dp))
                NeonField(prompt, { prompt = it }, "e.g. late-night cyberpunk ocean", Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                NeonButton(if (working) "Mixing colors…" else "Generate theme", icon = Icons.Default.AutoAwesome, enabled = prompt.isNotBlank() && !working) {
                    working = true
                    scope.launch {
                        var t: ThemeSpec? = null
                        if (app.hub.state.value is EngineState.Ready) {
                            val sb = StringBuilder()
                            val r = app.hub.generate("theme", listOf(
                                ChatMsg("system", "You are a color designer. Reply with ONLY one hex color like #39FF14 that fits the user's vibe as a bright accent on a dark background."),
                                ChatMsg("user", prompt)), GenOptions(temperature = 0.6f, topP = 0.9f, topK = 40, maxTokens = 24, thinking = false, repeatPenalty = 1.0f), {}) { sb.append(it); true }
                            t = ThemeSpec.fromText(prompt, ThinkParser.parse(r.text, r.startsInThink).answer)
                        }
                        if (t == null) t = ThemeSpec.fromText(prompt, prompt)
                        working = false
                        if (t == null) toast(ctx, "Couldn't find a color. Try words like purple, ocean, gold or a #hex code.")
                        else { app.store.addCustomTheme(t); app.store.setTheme(t); toast(ctx, "Applied ${t.name}") }
                    }
                }
            }
            SectionCard("Presets") { ThemeGrid(ThemeSpec.presets, current) { app.store.setTheme(it) } }
            if (custom.isNotEmpty()) SectionCard("Your AI themes") { ThemeGrid(custom, current) { app.store.setTheme(it) } }
            Spacer(Modifier.height(70.dp))
        }
    }
}

@Composable
private fun ThemeGrid(list: List<ThemeSpec>, current: ThemeSpec, onPick: (ThemeSpec) -> Unit) {
    list.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
            row.forEach { t ->
                val sel = t == current
                Row(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(t.background)).border(if (sel) 2.5.dp else 1.dp, Color(t.primary), RoundedCornerShape(14.dp))
                    .clickable { onPick(t) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color(t.primary)))
                    Spacer(Modifier.width(8.dp))
                    Text((if (sel) "✓ " else "") + t.name, color = Color(t.onSurface), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
