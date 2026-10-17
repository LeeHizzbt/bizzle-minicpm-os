package com.bizzle.minicpm.engine

import com.bizzle.minicpm.core.ChatMsg
import com.bizzle.minicpm.core.Compute
import com.bizzle.minicpm.core.GenOptions
import com.bizzle.minicpm.core.GenStats
import com.google.ai.edge.litertlm.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Google LiteRT-LM engine (Apache-2.0): Gemma 4 on CPU, GPU, or the Tensor G5 TPU ("NPU"). */
class LiteRtEngine private constructor(private val engine: Engine, private val compute: Compute) : InferenceEngine {
    override val name = "Gemma 4 (LiteRT-LM)"
    override val supportsVision = true
    override val backendLabel = "LiteRT-LM · " + when (compute) { Compute.NPU -> "Tensor TPU (NPU)"; Compute.GPU -> "GPU"; Compute.CPU -> "CPU" }
    @Volatile private var current: Conversation? = null
    @Volatile private var cancelled = false

    companion object {
        fun load(path: String, compute: Compute, o: LoadOptions): LiteRtEngine {
            val backend: Backend = when (compute) {
                Compute.NPU -> Backend.NPU(o.nativeLibDir)
                Compute.GPU -> Backend.GPU()
                Compute.CPU -> Backend.CPU(threadCount = o.threads)
            }
            val vision: Backend = if (compute == Compute.NPU) Backend.NPU(o.nativeLibDir) else Backend.CPU(threadCount = o.threads)
            val cfg = EngineConfig(modelPath = path, backend = backend, visionBackend = vision, maxNumTokens = o.contextSize, maxNumImages = 1, cacheDir = o.cacheDir)
            val e = Engine(cfg)
            try { e.initialize() } catch (t: Throwable) {
                runCatching { e.close() }
                val hint = if (compute == Compute.NPU) " The AI-chip build only runs on a Pixel 10 (Tensor G5)." else ""
                throw IllegalStateException("LiteRT-LM could not start: ${t.message ?: t.javaClass.simpleName}.$hint")
            }
            return LiteRtEngine(e, compute)
        }

        private fun contentsOf(m: ChatMsg): Contents {
            val parts = mutableListOf<Content>()
            m.images.forEach { parts += Content.ImageFile(it) }
            if (m.text.isNotBlank() || parts.isEmpty()) parts += Content.Text(m.text.ifBlank { " " })
            return Contents.of(parts)
        }

        private fun textOf(msg: Message): String = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
    }

    override fun generate(messages: List<ChatMsg>, opts: GenOptions, onStart: (Boolean) -> Unit, onToken: (String) -> Boolean): GenResult {
        cancelled = false
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text }
        val convo = messages.filter { it.role != "system" }
        val last = convo.last()
        // earlier turns are text-only history (images are only sent with the newest message)
        val history = convo.dropLast(1).map { m -> if (m.role == "assistant") Message.model(m.text) else Message.user(m.text.ifBlank { "[image]" }) }
        val sampler = if (compute == Compute.NPU) null else SamplerConfig(topK = opts.topK, topP = opts.topP.toDouble(), temperature = opts.temperature.toDouble())
        val t0 = System.currentTimeMillis()
        val conversation = engine.createConversation(ConversationConfig(
            systemInstruction = if (system.isBlank()) null else Contents.of(system), initialMessages = history, samplerConfig = sampler))
        current = conversation
        val sb = StringBuilder()
        var firstAt = 0L
        var pieces = 0
        var error: Throwable? = null
        val done = CountDownLatch(1)
        onStart(false)
        try {
            conversation.sendMessageAsync(contentsOf(last), object : MessageCallback {
                override fun onMessage(message: Message) {
                    val p = textOf(message)
                    if (p.isEmpty()) return
                    if (firstAt == 0L) firstAt = System.currentTimeMillis()
                    sb.append(p); pieces++
                    if (!onToken(p) || pieces >= opts.maxTokens) { cancelled = true; runCatching { conversation.cancelProcess() } }
                }
                override fun onDone() { done.countDown() }
                override fun onError(throwable: Throwable) { if (!cancelled) error = throwable; done.countDown() }
            })
            while (!done.await(200, TimeUnit.MILLISECONDS)) { if (cancelled) runCatching { conversation.cancelProcess() } }
        } finally {
            current = null
            runCatching { conversation.close() }
        }
        val t2 = System.currentTimeMillis()
        if (firstAt == 0L) firstAt = t2
        val stats = GenStats(0, pieces, firstAt - t0, t2 - firstAt)
        error?.let { return GenResult(sb.toString(), stats, "error", false, it.message ?: "LiteRT-LM error") }
        return GenResult(sb.toString(), stats, if (cancelled) "cancelled" else "stop", false)
    }

    override fun cancel() { cancelled = true; current?.let { runCatching { it.cancelProcess() } } }

    override fun bench(): BenchResult {
        val prompt = "Write a short paragraph about the ocean."
        val r = generate(listOf(ChatMsg("user", prompt)), GenOptions(0.7f, 0.95f, 40, 64, false), {}, { true })
        return BenchResult(0.0, r.stats.tokPerSec, "LiteRT-LM streams text chunks; speed shown is chunks/sec (about tokens/sec). Time to first token ${r.stats.promptMs} ms.")
    }

    override fun close() { runCatching { engine.close() } }
}
