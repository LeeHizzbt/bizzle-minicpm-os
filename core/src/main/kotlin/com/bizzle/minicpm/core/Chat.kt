package com.bizzle.minicpm.core

import kotlinx.serialization.Serializable

/** One message sent to an engine. [images] are local file paths (JPEG/PNG). */
@Serializable
data class ChatMsg(val role: String, val text: String, val images: List<String> = emptyList())

/** Generation options for a single request (merged from settings and API overrides). */
data class GenOptions(
    val temperature: Float,
    val topP: Float,
    val topK: Int,
    val maxTokens: Int,
    val thinking: Boolean,
    val repeatPenalty: Float = 1.05f,
)

data class GenStats(val promptTokens: Int, val genTokens: Int, val promptMs: Long, val genMs: Long) {
    val tokPerSec: Double get() = if (genMs <= 0) 0.0 else genTokens * 1000.0 / genMs
    val promptTokPerSec: Double get() = if (promptMs <= 0) 0.0 else promptTokens * 1000.0 / promptMs
}

object History {
    /**
     * Keeps images only on the most recent [keepImages] image-bearing user turns: re-encoding old
     * photos on every turn is slow on a phone, and the text of earlier answers already describes them.
     */
    fun trimImages(msgs: List<ChatMsg>, keepImages: Int = 1): List<ChatMsg> {
        var kept = 0
        return msgs.asReversed().map { m ->
            if (m.images.isEmpty()) m
            else if (kept < keepImages) { kept++; m }
            else m.copy(images = emptyList(), text = if (m.text.isBlank()) "[earlier image]" else m.text)
        }.asReversed()
    }

    /** Rough token estimate (~4 chars/token) used to drop the oldest turns before the context fills. */
    fun fitToContext(msgs: List<ChatMsg>, ctx: Int, reserve: Int, imageTokens: Int = 700): List<ChatMsg> {
        fun cost(m: ChatMsg) = m.text.length / 4 + 8 + m.images.size * imageTokens
        val budget = (ctx - reserve).coerceAtLeast(256)
        val system = msgs.takeWhile { it.role == "system" }
        val rest = msgs.drop(system.size).toMutableList()
        var total = system.sumOf { cost(it) } + rest.sumOf { cost(it) }
        while (rest.size > 1 && total > budget) { total -= cost(rest.removeAt(0)) }
        // never start with an assistant turn
        while (rest.size > 1 && rest.first().role == "assistant") rest.removeAt(0)
        return system + rest
    }
}
