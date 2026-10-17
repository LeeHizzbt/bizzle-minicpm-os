package com.bizzle.minicpm.engine

import com.bizzle.minicpm.core.ChatMsg
import com.bizzle.minicpm.core.GenOptions
import com.bizzle.minicpm.core.GenStats

data class LoadOptions(val contextSize: Int, val threads: Int, val useGpu: Boolean, val detailVision: Boolean, val nativeLibDir: String, val cacheDir: String)

data class GenResult(val text: String, val stats: GenStats, val finish: String, val startsInThink: Boolean, val error: String? = null)

data class BenchResult(val promptTps: Double, val genTps: Double, val note: String)

/** Common surface for llama.cpp and LiteRT-LM so chat, Bizzy and the local API don't care which engine is loaded. */
interface InferenceEngine {
    val name: String
    val supportsVision: Boolean
    val backendLabel: String
    fun generate(messages: List<ChatMsg>, opts: GenOptions, onStart: (startsInThink: Boolean) -> Unit, onToken: (String) -> Boolean): GenResult
    fun cancel()
    fun bench(): BenchResult
    fun close()
}
