package com.bizzle.minicpm.core

/** Splits a reply into plain-text and fenced-code segments so the UI can render code blocks with their own copy button. */
object MarkdownLite {
    sealed class Seg { data class Text(val text: String) : Seg(); data class Code(val lang: String, val code: String) : Seg() }

    fun split(md: String): List<Seg> {
        val out = mutableListOf<Seg>()
        var i = 0
        while (i < md.length) {
            val start = md.indexOf("```", i)
            if (start < 0) { add(out, md.substring(i)); break }
            add(out, md.substring(i, start))
            val nl = md.indexOf('\n', start + 3)
            if (nl < 0) { out += Seg.Code(md.substring(start + 3).trim(), ""); break }
            val lang = md.substring(start + 3, nl).trim()
            val end = md.indexOf("```", nl + 1)
            if (end < 0) { out += Seg.Code(lang, md.substring(nl + 1).trimEnd()); break }
            out += Seg.Code(lang, md.substring(nl + 1, end).trimEnd('\n'))
            i = end + 3
        }
        return out
    }

    private fun add(out: MutableList<Seg>, s: String) { if (s.isNotBlank()) out += Seg.Text(s.trim('\n')) }
}
