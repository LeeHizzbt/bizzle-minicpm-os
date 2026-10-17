package com.bizzle.minicpm.engine

/** JNI surface of libbizzle_llama.so (llama.cpp + libmtmd). */
object LlamaNative {
    interface Callback {
        fun onPrompt(promptTokens: Int, startsInThink: Boolean)
        /** Return false to stop generating. */
        fun onToken(piece: String): Boolean
    }

    @Volatile private var loaded = false
    var loadError: String? = null; private set

    @Synchronized fun ensureLoaded(nativeLibDir: String): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("bizzle_llama")
            nativeInit(nativeLibDir)
            loaded = true; true
        } catch (t: Throwable) { loadError = t.message ?: t.javaClass.simpleName; false }
    }

    external fun nativeInit(libDir: String)
    external fun nativeSystemInfo(): String
    external fun nativeHasGpu(): Boolean
    external fun nativeLastError(): String
    external fun nativeLoad(model: String, mmproj: String?, nCtx: Int, nThreads: Int, useGpu: Boolean, imageMaxTokens: Int): Long
    external fun nativeFree(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeInfo(handle: Long): String
    external fun nativeGenerate(handle: Long, messagesJson: String, images: Array<String>, temp: Float, topP: Float, topK: Int,
                                repeatPenalty: Float, maxTokens: Int, thinking: Boolean, cb: Callback): String
    external fun nativeBench(handle: Long, pp: Int, tg: Int): String
}
