package com.bizzle.minicpm.engine

import com.bizzle.minicpm.core.ChatMsg
import com.bizzle.minicpm.core.GenOptions
import com.bizzle.minicpm.core.GenStats
import kotlinx.serialization.json.*

class LlamaEngine private constructor(private val handle: Long, info: JsonObject, private val gpu: Boolean) : InferenceEngine {
    override val name: String = info["desc"]?.jsonPrimitive?.content ?: "llama.cpp model"
    override val supportsVision: Boolean = info["vision"]?.jsonPrimitive?.booleanOrNull == true
    override val backendLabel: String = "llama.cpp · " + (if (gpu) "GPU" else "CPU") + " · ${info["threads"]?.jsonPrimitive?.int ?: 0} threads"
    @Volatile private var closed = false

    companion object {
        const val MEDIA_MARKER = "<__media__>"

        fun load(modelPath: String, mmprojPath: String?, o: LoadOptions): LlamaEngine {
            if (!LlamaNative.ensureLoaded(o.nativeLibDir)) throw IllegalStateException("Native engine failed to load: ${LlamaNative.loadError}")
            val gpu = o.useGpu && LlamaNative.nativeHasGpu()
            val h = LlamaNative.nativeLoad(modelPath, mmprojPath, o.contextSize, o.threads, gpu, 0)
            if (h == 0L) throw IllegalStateException(LlamaNative.nativeLastError().ifBlank { "Model failed to load" })
            val info = Json.parseToJsonElement(LlamaNative.nativeInfo(h)).jsonObject
            return LlamaEngine(h, info, gpu)
        }
    }

    override fun generate(messages: List<ChatMsg>, opts: GenOptions, onStart: (Boolean) -> Unit, onToken: (String) -> Boolean): GenResult {
        check(!closed) { "engine closed" }
        val images = mutableListOf<String>()
        val arr = buildJsonArray {
            messages.forEach { m ->
                val usable = if (supportsVision) m.images else emptyList()
                images += usable
                val content = (usable.joinToString("") { "$MEDIA_MARKER\n" }) + m.text
                addJsonObject { put("role", m.role); put("content", content) }
            }
        }
        val sb = StringBuilder()
        var startsInThink = false
        val res = LlamaNative.nativeGenerate(handle, arr.toString(), images.toTypedArray(), opts.temperature, opts.topP, opts.topK,
            opts.repeatPenalty, opts.maxTokens, opts.thinking, object : LlamaNative.Callback {
                override fun onPrompt(promptTokens: Int, startsInThink: Boolean) { onStart(startsInThink) }
                override fun onToken(piece: String): Boolean { sb.append(piece); return onToken(piece) }
            })
        val j = Json.parseToJsonElement(res).jsonObject
        val err = j["error"]?.jsonPrimitive?.contentOrNull
        if (err != null) return GenResult(sb.toString(), GenStats(0, 0, 0, 0), if (err == "cancelled") "cancelled" else "error", false, if (err == "cancelled") null else err)
        startsInThink = j["starts_in_think"]?.jsonPrimitive?.booleanOrNull == true
        val stats = GenStats(j["prompt_tokens"]!!.jsonPrimitive.int, j["gen_tokens"]!!.jsonPrimitive.int,
            j["prompt_ms"]!!.jsonPrimitive.long, j["gen_ms"]!!.jsonPrimitive.long)
        return GenResult(sb.toString(), stats, j["finish"]!!.jsonPrimitive.content, startsInThink)
    }

    override fun cancel() { if (!closed) LlamaNative.nativeCancel(handle) }

    override fun bench(): BenchResult {
        val j = Json.parseToJsonElement(LlamaNative.nativeBench(handle, 128, 64)).jsonObject
        j["error"]?.let { return BenchResult(0.0, 0.0, it.jsonPrimitive.content) }
        return BenchResult(j["pp_tps"]!!.jsonPrimitive.double, j["tg_tps"]!!.jsonPrimitive.double, "prompt 128 tok · generate 64 tok")
    }

    override fun close() { if (!closed) { closed = true; LlamaNative.nativeFree(handle) } }
}
