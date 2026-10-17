package com.bizzle.minicpm.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.data.Conversation
import com.bizzle.minicpm.ui.ChatSession
import com.bizzle.minicpm.ui.Nav
import com.bizzle.minicpm.ui.Screen
import com.bizzle.minicpm.ui.components.*
import com.bizzle.minicpm.ui.theme.LocalPalette
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(nav: Nav, onBizzy: () -> Unit) {
    val app = BizzleApp.instance
    val pal = LocalPalette.current
    val chats by app.store.chats.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<Conversation?>(null) }
    BizzleScaffold("History", onBack = { nav.back() }, onBizzy = onBizzy) {
        if (chats.isEmpty()) Body("No chats yet. They're saved only on this phone.", Modifier.padding(16.dp), alpha = 0.7f)
        LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chats, key = { it.id }) { c ->
                SectionCard(modifier = Modifier.clickable { nav.go(Screen.Chat(c.id)) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, color = pal.accent, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Body("${c.messages.size} messages · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(c.updated))}", alpha = 0.6f, size = 12)
                            c.messages.lastOrNull()?.let { Body(it.text.take(90), alpha = 0.75f, size = 13) }
                        }
                        IconButton(onClick = { toDelete = c }) { Icon(Icons.Default.Delete, "Delete chat", tint = pal.accent) }
                    }
                }
            }
            item { Spacer(Modifier.height(70.dp)) }
        }
    }
    toDelete?.let { c -> ConfirmDialog("Delete this chat?", c.title, onConfirm = { app.store.deleteChat(c.id); ChatSession.drop(c.id) }, onDismiss = { toDelete = null }) }
}
