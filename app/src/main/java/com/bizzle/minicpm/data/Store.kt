package com.bizzle.minicpm.data

import android.content.Context
import com.bizzle.minicpm.core.AiSettings
import com.bizzle.minicpm.core.ThemeSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.UUID

@Serializable
data class StoredMsg(
    val role: String,
    val text: String,
    val reasoning: String = "",
    val images: List<String> = emptyList(),
    val stats: String = "",
    val error: Boolean = false,
)

@Serializable
data class Conversation(val id: String, val title: String, val updated: Long, val modelId: String = "", val messages: List<StoredMsg> = emptyList())

@Serializable
data class BenchRecord(val modelId: String, val promptTps: Double, val genTps: Double, val at: Long, val backend: String)

/** All app state that lives on the phone. Nothing here ever leaves the device. */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("bizzle", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val chatDir = File(ctx.filesDir, "chats").apply { mkdirs() }

    private val _ai = MutableStateFlow(load("ai") { AiSettings() })
    val ai: StateFlow<AiSettings> = _ai
    fun setAi(s: AiSettings) { val v = s.sanitized(); _ai.value = v; save("ai", v) }

    private val _theme = MutableStateFlow(load("theme") { ThemeSpec.NEON })
    val theme: StateFlow<ThemeSpec> = _theme
    fun setTheme(t: ThemeSpec) { _theme.value = t; save("theme", t) }
    private val _customThemes = MutableStateFlow(load<List<ThemeSpec>>("customThemes") { emptyList() })
    val customThemes: StateFlow<List<ThemeSpec>> = _customThemes
    fun addCustomTheme(t: ThemeSpec) { val v = (listOf(t) + _customThemes.value.filter { it.name != t.name }).take(12); _customThemes.value = v; save("customThemes", v) }

    private val _memory = MutableStateFlow(load<List<String>>("memory") { emptyList() })
    val memory: StateFlow<List<String>> = _memory
    fun remember(fact: String) { val v = (_memory.value.filter { !it.equals(fact, true) } + fact).takeLast(50); _memory.value = v; save("memory", v) }
    fun forget(what: String): Int { val before = _memory.value.size; val v = _memory.value.filterNot { it.contains(what, true) }; _memory.value = v; save("memory", v); return before - v.size }
    fun forgetAt(i: Int) { val v = _memory.value.toMutableList().also { if (i in it.indices) it.removeAt(i) }; _memory.value = v; save("memory", v) }

    private val _bizzyChat = MutableStateFlow(load<List<StoredMsg>>("bizzyChat") { emptyList() })
    val bizzyChat: StateFlow<List<StoredMsg>> = _bizzyChat
    fun setBizzyChat(v: List<StoredMsg>) { val t = v.takeLast(40); _bizzyChat.value = t; save("bizzyChat", t) }

    private val _bench = MutableStateFlow(load<List<BenchRecord>>("bench") { emptyList() })
    val bench: StateFlow<List<BenchRecord>> = _bench
    fun addBench(b: BenchRecord) { val v = (_bench.value + b).takeLast(30); _bench.value = v; save("bench", v) }

    var selectedModelId: String?
        get() = prefs.getString("selectedModel", null)
        set(v) { prefs.edit().putString("selectedModel", v).apply() }

    var apiEnabled: Boolean
        get() = prefs.getBoolean("apiEnabled", false)
        set(v) { prefs.edit().putBoolean("apiEnabled", v).apply() }

    var apiRequireToken: Boolean
        get() = prefs.getBoolean("apiRequireToken", true)
        set(v) { prefs.edit().putBoolean("apiRequireToken", v).apply() }

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(v) { prefs.edit().putBoolean("onboarded", v).apply() }

    /** Random per-install token for the localhost API. Shown only inside the app. */
    val apiToken: String
        get() = prefs.getString("apiToken", null) ?: newToken().also { prefs.edit().putString("apiToken", it).apply() }
    fun rotateApiToken(): String = newToken().also { prefs.edit().putString("apiToken", it).apply() }
    private fun newToken(): String { val b = ByteArray(18); SecureRandom().nextBytes(b); return "bz-" + b.joinToString("") { "%02x".format(it) } }

    // ---- chats ----
    private val _chats = MutableStateFlow(listChats())
    val chats: StateFlow<List<Conversation>> = _chats
    private fun listChats(): List<Conversation> = chatDir.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { runCatching { json.decodeFromString<Conversation>(it.readText()) }.getOrNull() }
        .sortedByDescending { it.updated }
    fun newChatId(): String = UUID.randomUUID().toString()
    fun chat(id: String): Conversation? = _chats.value.firstOrNull { it.id == id }
    fun saveChat(c: Conversation) {
        File(chatDir, "${c.id}.json").writeText(json.encodeToString(c))
        _chats.value = (listOf(c) + _chats.value.filter { it.id != c.id }).sortedByDescending { it.updated }
    }
    fun deleteChat(id: String) {
        chat(id)?.messages?.flatMap { it.images }?.forEach { File(it).delete() }
        File(chatDir, "$id.json").delete(); _chats.value = _chats.value.filter { it.id != id }
    }

    private inline fun <reified T> load(key: String, default: () -> T): T =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString<T>(it) }.getOrNull() } ?: default()
    private inline fun <reified T> save(key: String, v: T) { prefs.edit().putString(key, json.encodeToString(v)).apply() }
}
