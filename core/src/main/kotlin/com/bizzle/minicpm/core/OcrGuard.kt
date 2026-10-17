package com.bizzle.minicpm.core

/**
 * Small vision models confuse look-alike characters in serials and codes (l/1/I, O/0, S/5, B/8).
 * Flags tokens in a reply that look like codes AND contain those characters, so the UI can show a caution.
 */
object OcrGuard {
    private val confusable = setOf('l', '1', 'I', 'O', '0', 'S', '5', 'B', '8', 'Z', '2')
    private val token = Regex("[A-Za-z0-9-]{5,}")

    fun riskyTokens(reply: String): List<String> = token.findAll(reply).map { it.value }.filter { t ->
        val hasDigit = t.any { it.isDigit() }
        val hasLetter = t.any { it.isLetter() }
        hasDigit && hasLetter && t.count { it in confusable } >= 1 && t.any { it.isUpperCase() || it.isDigit() }
    }.distinct().toList()
}
