package com.bizzle.minicpm.ui

import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.core.ChatMsg
import com.bizzle.minicpm.core.ThinkParser
import com.bizzle.minicpm.data.Conversation
import com.bizzle.minicpm.data.StoredMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One chat's live state. Lives outside the UI so a reply keeps streaming if you leave the screen. */
class ChatSession(initial: Conversation) {
    private val app get() = BizzleApp.instance
    private val _convo = MutableStateFlow(initial)
    val convo: StateFlow<Conversation> = _convo
    private val _streaming = MutableStateFlow<StoredMsg?>(null)
    val streaming: StateFlow<StoredMsg?> = _streaming
    val generating: Boolean get() = _streaming.value != null

    fun send(text: String, images: List<String>) {
        if (generating || (text.isBlank() && images.isEmpty())) return
        val prompt = text.ifBlank { "Describe this image in detail. Read any text exactly." }
        val user = StoredMsg("user", prompt, images = images)
        val c = _convo.value
        val title = if (c.messages.isEmpty()) prompt.take(48) else c.title
        _convo.value = c.copy(title = title, messages = c.messages + user, updated = System.currentTimeMillis())
        run()
    }

    fun regenerate() {
        if (generating) return
        val c = _convo.value
        if (c.messages.lastOrNull()?.role == "assistant") _convo.value = c.copy(messages = c.messages.dropLast(1))
        if (_convo.value.messages.lastOrNull()?.role == "user") run()
    }

    fun stop() { app.hub.cancel() }

    private fun run() {
        val hub = app.hub
        val opts = hub.options()
        val history = _convo.value.messages.filter { !it.error }.map { ChatMsg(it.role, it.text, it.images) }
        val msgs = hub.prepare(app.store.ai.value.systemPrompt, history, opts)
        _streaming.value = StoredMsg("assistant", "")
        scope.launch {
            val raw = StringBuilder()
            var think = false
            var last = 0L
            val res = hub.generate("chat", msgs, opts, { think = it }) { piece ->
                raw.append(piece)
                val now = System.currentTimeMillis()
                if (now - last > 50) {
                    last = now
                    val s = ThinkParser.parse(raw.toString(), think)
                    _streaming.value = StoredMsg("assistant", s.answer, reasoning = s.reasoning)
                }
                true
            }
            val split = ThinkParser.parse(res.text, res.startsInThink || think)
            val stats = buildString {
                if (res.stats.genTokens > 0) append("%.1f tok/s · %d tokens".format(res.stats.tokPerSec, res.stats.genTokens))
                if (res.stats.promptMs > 0) append(" · prompt %.1fs".format(res.stats.promptMs / 1000.0))
                if (res.finish == "cancelled") append(" · stopped")
                if (res.finish == "length") append(" · hit max tokens")
                app.hub.loadedSpec?.let { append(" · ").append(it.name) }
            }
            val msg = if (res.error != null) StoredMsg("assistant", "⚠️ ${res.error}", error = true)
                      else StoredMsg("assistant", split.answer.ifBlank { if (res.finish == "cancelled") "(stopped)" else "(no answer)" }, split.reasoning, stats = stats)
            val c = _convo.value
            _convo.value = c.copy(messages = c.messages + msg, updated = System.currentTimeMillis(), modelId = app.hub.loadedSpec?.id ?: c.modelId)
            _streaming.value = null
            app.store.saveChat(_convo.value)
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val sessions = HashMap<String, ChatSession>()
        fun get(id: String?): ChatSession {
            val app = BizzleApp.instance
            val key = id ?: app.store.newChatId()
            return sessions.getOrPut(key) { ChatSession(app.store.chat(key) ?: Conversation(key, "New chat", System.currentTimeMillis())) }
        }
        fun drop(id: String) { sessions.remove(id) }
    }
}
