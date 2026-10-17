package com.bizzle.minicpm.core

/**
 * Splits model output into "reasoning" (inside <think>...</think>) and the visible answer.
 * Works on partial streams: call [parse] with the full text so far.
 * [startsInThink] = true when the prompt itself opened a <think> block (MiniCPM-V 4.7 / Qwen3.5 templates).
 */
object ThinkParser {
    data class Split(val reasoning: String, val answer: String, val thinking: Boolean)

    fun parse(raw: String, startsInThink: Boolean): Split {
        var text = raw
        var inThink = startsInThink
        val reasoning = StringBuilder()
        val answer = StringBuilder()
        while (text.isNotEmpty()) {
            if (inThink) {
                val end = text.indexOf("</think>")
                if (end < 0) { reasoning.append(text); text = ""; break }
                reasoning.append(text, 0, end); text = text.substring(end + 8); inThink = false
            } else {
                val start = text.indexOf("<think>")
                if (start < 0) { answer.append(text); text = ""; break }
                answer.append(text, 0, start); text = text.substring(start + 7); inThink = true
            }
        }
        // hide a half-typed tag at the very end of a stream
        val ans = stripPartialTag(answer.toString())
        return Split(reasoning.toString().trim(), ans.trimStart('\n', ' ').trimEnd(), inThink)
    }

    private fun stripPartialTag(s: String): String {
        for (tag in listOf("<think>", "</think>")) {
            for (n in tag.length - 1 downTo 1) if (s.endsWith(tag.substring(0, n))) return s.dropLast(n)
        }
        return s
    }
}
