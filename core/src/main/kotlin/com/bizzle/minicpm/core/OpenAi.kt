package com.bizzle.minicpm.core

import kotlinx.serialization.json.*

/** Minimal OpenAI-compatible request/response mapping for the local API server. */
object OpenAi {
    data class ImagePart(val url: String)
    data class Msg(val role: String, val text: String, val images: List<ImagePart>)
    data class Request(
        val model: String?, val messages: List<Msg>, val stream: Boolean,
        val temperature: Float?, val topP: Float?, val maxTokens: Int?, val thinking: Boolean?,
    )

    class BadRequest(msg: String) : Exception(msg)

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun parseChatRequest(body: String): Request {
        val root = try { json.parseToJsonElement(body).jsonObject } catch (e: Exception) { throw BadRequest("Body is not valid JSON") }
        val arr = root["messages"] as? JsonArray ?: throw BadRequest("'messages' array is required")
        if (arr.isEmpty()) throw BadRequest("'messages' must not be empty")
        val msgs = arr.map { el ->
            val o = el as? JsonObject ?: throw BadRequest("each message must be an object")
            val role = o["role"]?.jsonPrimitive?.contentOrNull ?: throw BadRequest("message.role is required")
            if (role !in setOf("system", "user", "assistant", "developer", "tool")) throw BadRequest("unsupported role '$role'")
            val texts = mutableListOf<String>(); val images = mutableListOf<ImagePart>()
            when (val c = o["content"]) {
                null, JsonNull -> {}
                is JsonPrimitive -> texts += c.content
                is JsonArray -> c.forEach { p ->
                    val po = p.jsonObject
                    when (po["type"]?.jsonPrimitive?.contentOrNull) {
                        "text" -> texts += po["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        "image_url" -> {
                            val iu = po["image_url"]
                            val url = when (iu) { is JsonPrimitive -> iu.content; is JsonObject -> iu["url"]?.jsonPrimitive?.contentOrNull; else -> null }
                                ?: throw BadRequest("image_url.url is required")
                            images += ImagePart(url)
                        }
                        else -> throw BadRequest("unsupported content part type")
                    }
                }
                else -> throw BadRequest("unsupported content")
            }
            Msg(if (role == "developer") "system" else role, texts.joinToString("\n"), images)
        }
        if (msgs.none { it.role == "user" }) throw BadRequest("at least one user message is required")
        fun f(k: String) = root[k]?.jsonPrimitive?.floatOrNull
        val maxTok = root["max_completion_tokens"]?.jsonPrimitive?.intOrNull ?: root["max_tokens"]?.jsonPrimitive?.intOrNull
        val thinking = root["enable_thinking"]?.jsonPrimitive?.booleanOrNull
            ?: (root["chat_template_kwargs"] as? JsonObject)?.get("enable_thinking")?.jsonPrimitive?.booleanOrNull
        return Request(root["model"]?.jsonPrimitive?.contentOrNull, msgs, root["stream"]?.jsonPrimitive?.booleanOrNull ?: false,
            f("temperature"), f("top_p"), maxTok, thinking)
    }

    fun completion(id: String, model: String, created: Long, content: String, reasoning: String?, finish: String, promptTokens: Int, completionTokens: Int): String =
        buildJsonObject {
            put("id", id); put("object", "chat.completion"); put("created", created); put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant"); put("content", content)
                        if (!reasoning.isNullOrBlank()) put("reasoning_content", reasoning)
                    }
                    put("finish_reason", finish)
                }
            }
            putJsonObject("usage") {
                put("prompt_tokens", promptTokens); put("completion_tokens", completionTokens); put("total_tokens", promptTokens + completionTokens)
            }
        }.toString()

    fun chunk(id: String, model: String, created: Long, delta: String?, finish: String?, role: Boolean = false): String =
        buildJsonObject {
            put("id", id); put("object", "chat.completion.chunk"); put("created", created); put("model", model)
            putJsonArray("choices") {
                addJsonObject {
                    put("index", 0)
                    putJsonObject("delta") {
                        if (role) put("role", "assistant")
                        if (delta != null) put("content", delta)
                    }
                    if (finish != null) put("finish_reason", finish) else put("finish_reason", JsonNull)
                }
            }
        }.toString()

    fun models(ids: List<String>, created: Long): String = buildJsonObject {
        put("object", "list")
        putJsonArray("data") { ids.forEach { addJsonObject { put("id", it); put("object", "model"); put("created", created); put("owned_by", "bizzle-on-device") } } }
    }.toString()

    fun error(message: String, type: String = "invalid_request_error"): String = buildJsonObject {
        putJsonObject("error") { put("message", message); put("type", type) }
    }.toString()

    /** Splits a data URL into (mime, base64 payload). Returns null if not a base64 data URL. */
    fun parseDataUrl(url: String): Pair<String, String>? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        if (!meta.endsWith(";base64")) return null
        return meta.removeSuffix(";base64") to url.substring(comma + 1)
    }
}
