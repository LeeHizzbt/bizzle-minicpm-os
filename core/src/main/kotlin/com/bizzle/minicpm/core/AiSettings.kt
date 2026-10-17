package com.bizzle.minicpm.core

import kotlinx.serialization.Serializable

@Serializable
data class AiSettings(
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val contextSize: Int = 4096,
    val threads: Int = 0, // 0 = auto
    val maxTokens: Int = 1024,
    val systemPrompt: String = SystemPrompts.presets.first().second,
    val thinking: Boolean = false,
    val useGpu: Boolean = false,
    val detailVision: Boolean = false,
    val autoUnload: Boolean = false,
    val repeatPenalty: Float = 1.05f,
) {
    fun sanitized(): AiSettings = copy(
        temperature = temperature.coerceIn(0f, 2f),
        topP = topP.coerceIn(0.05f, 1f),
        topK = topK.coerceIn(1, 200),
        contextSize = contextSize.coerceIn(1024, 32768).let { (it / 512) * 512 },
        threads = threads.coerceIn(0, 8),
        maxTokens = maxTokens.coerceIn(16, 8192),
        repeatPenalty = repeatPenalty.coerceIn(1f, 1.5f),
        systemPrompt = systemPrompt.take(4000),
    )

    companion object {
        /** Auto thread count: leave the little cores for the UI. Tensor G5 = 1 big + 5 mid + 2 little. */
        fun autoThreads(cores: Int): Int = when {
            cores >= 8 -> 6
            cores >= 6 -> 4
            else -> (cores - 1).coerceAtLeast(1)
        }
    }
}

object SystemPrompts {
    val presets: List<Pair<String, String>> = listOf(
        "Bizzy (default)" to "You are Bizzy, a friendly, sharp assistant running fully offline on the user's Pixel phone inside Bizzle's Mini CPM v4.7 OS. Be concise and clear. When reading text from images, copy numbers and codes carefully.",
        "Plain assistant" to "You are a helpful assistant.",
        "Screenshot helper" to "You help the user understand screenshots and photos. Describe what matters, read any text exactly, and give step-by-step help.",
        "Code helper" to "You are a careful programming assistant. Answer with short explanations and complete code blocks.",
        "Short answers" to "Answer in one or two short sentences.",
    )
}
