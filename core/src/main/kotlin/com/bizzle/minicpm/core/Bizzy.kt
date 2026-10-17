package com.bizzle.minicpm.core

/** Bizzy pop-up helper logic that works with or without a loaded model. */
object Bizzy {
    sealed class Command {
        data class Remember(val fact: String) : Command()
        data class Forget(val what: String) : Command()
        object ListMemory : Command()
        object None : Command()
    }

    fun parse(input: String): Command {
        val t = input.trim()
        val low = t.lowercase()
        Regex("^(?:bizzy[, ]+)?(?:please )?remember(?: that)?[: ]+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return Command.Remember(it.groupValues[1].trim().trimEnd('.').take(300))
        }
        Regex("^(?:bizzy[, ]+)?forget(?: about)?[: ]+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            return Command.Forget(it.groupValues[1].trim().trimEnd('.'))
        }
        if (low.matches(Regex("^(?:bizzy[, ]+)?what do you (?:remember|know about me)\\??$"))) return Command.ListMemory
        return Command.None
    }

    fun systemPrompt(base: String, memory: List<String>, screen: String): String = buildString {
        append(base.trim())
        append("\nYou are the Bizzy pop-up helper inside the app. The user is on the \"").append(screen).append("\" screen.")
        append(" App facts: models download in Models; Advanced AI Settings change temperature, context, threads and quant; ")
        append("the local API server lets other apps use the loaded model at http://127.0.0.1:8420/v1.")
        if (memory.isNotEmpty()) {
            append("\nThings the user asked you to remember:\n")
            memory.takeLast(30).forEach { append("- ").append(it).append('\n') }
        }
    }

    /** Offline, no-model tips for each screen. */
    fun tip(screen: String, modelLoaded: Boolean, apiOn: Boolean): String = when {
        !modelLoaded && screen != "Models" -> "No model is loaded yet. Open Models, download MiniCPM-V 4.7 1B (Q8_0 is the default), then tap Load."
        screen == "Models" -> "Q8_0 is the best-quality MiniCPM-V; Q4_K_M is faster and lighter. Gemma 4 E2B NPU runs on the Pixel's AI chip (experimental). Run Benchmark to see tokens/sec on your phone."
        screen == "Chat" -> "Tap the camera or photo button to ask about a picture or screenshot. Every reply has a copy button. Long-press nothing, just tap Stop to end a reply instantly."
        screen == "Advanced AI Settings" -> "Lower temperature = more exact answers. 4096 context is a good balance; bigger contexts use more RAM. Threads = Auto is best on Tensor G5."
        screen == "Local API" -> if (apiOn) "Server is on. Other apps can call http://127.0.0.1:8420/v1/chat/completions with the token shown here." else "Turn the server on so Bizzy Voice, Kief and other apps can share the loaded model."
        screen == "Themes" -> "Pick a preset or describe a vibe and let the AI make a theme."
        else -> "Ask me anything. Say \"remember …\" and I'll keep it on this phone."
    }
}
