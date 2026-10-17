package com.bizzle.minicpm.engine

import android.content.Context
import com.bizzle.minicpm.core.*
import com.bizzle.minicpm.data.BenchRecord
import com.bizzle.minicpm.data.DeviceStatus
import com.bizzle.minicpm.data.ModelFiles
import com.bizzle.minicpm.data.Store
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors

sealed class EngineState {
    object Idle : EngineState()
    data class Loading(val modelId: String, val name: String) : EngineState()
    data class Ready(val spec: ModelSpec, val backend: String, val vision: Boolean) : EngineState()
    data class Failed(val message: String) : EngineState()
}

/** Owns the one loaded model. Chat, Bizzy and the local API all share it; requests run one at a time. */
class Hub(private val ctx: Context, private val store: Store, private val files: ModelFiles) {
    private val engineThread = Executors.newSingleThreadExecutor { r -> Thread(r, "bizzle-engine").apply { priority = Thread.MAX_PRIORITY } }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: InferenceEngine? = null

    private val _state = MutableStateFlow<EngineState>(EngineState.Idle)
    val state: StateFlow<EngineState> = _state
    private val _busy = MutableStateFlow<String?>(null) // who is generating: "chat", "bizzy", "api", "bench"
    val busy: StateFlow<String?> = _busy

    val loadedSpec: ModelSpec? get() = (state.value as? EngineState.Ready)?.spec

    fun threadsFor(s: AiSettings) = if (s.threads > 0) s.threads else AiSettings.autoThreads(Runtime.getRuntime().availableProcessors())

    /** Returns null on success or a friendly error. */
    suspend fun load(spec: ModelSpec): String? = mutex.withLock {
        if (!files.isReady(spec)) return "Download ${spec.name} first."
        val snap = DeviceStatus.snapshot(ctx)
        val need = spec.requiredBytes * (if (spec.engine == EngineKind.LITERT_LM) 0.7 else 1.0) + 350L * 1024 * 1024
        if (snap.availRamBytes < need && snap.totalRamBytes < need * 1.6) {
            return "Not enough free memory to load ${spec.name} safely (needs about ${Bytes.human(need.toLong())}, ${Bytes.human(snap.availRamBytes)} free). Close other apps or pick a smaller model."
        }
        if (spec.compute == Compute.NPU && !DeviceStatus.isTensorG5()) {
            return "This build of Gemma 4 only runs on the Tensor G5 AI chip (Pixel 10 series). This device reports ${snap.soc}. Use Gemma 4 E2B LiteRT CPU/GPU instead."
        }
        _state.value = EngineState.Loading(spec.id, spec.name)
        withContext(engineThread) {
            engine?.close(); engine = null
            val s = store.ai.value
            val o = LoadOptions(s.contextSize, threadsFor(s), s.useGpu, s.detailVision, ctx.applicationInfo.nativeLibraryDir, ctx.cacheDir.absolutePath)
            try {
                val e = when (spec.engine) {
                    EngineKind.LLAMA_CPP -> {
                        val mm = (if (s.detailVision && spec.detailMmproj != null && files.isDone(spec.detailMmproj!!)) spec.detailMmproj else spec.mmproj)
                        LlamaEngine.load(files.file(spec.main).absolutePath, mm?.let { files.file(it).absolutePath }, o)
                    }
                    EngineKind.LITERT_LM -> LiteRtEngine.load(files.file(spec.main).absolutePath,
                        if (spec.compute == Compute.CPU && s.useGpu) Compute.GPU else spec.compute, o)
                }
                engine = e
                store.selectedModelId = spec.id
                _state.value = EngineState.Ready(spec, e.backendLabel, e.supportsVision)
                null
            } catch (t: Throwable) {
                _state.value = EngineState.Failed(t.message ?: t.javaClass.simpleName)
                t.message ?: "Load failed"
            }
        }
    }

    suspend fun unload() = mutex.withLock {
        withContext(engineThread) { engine?.close(); engine = null }
        _state.value = EngineState.Idle
    }

    fun cancel() { engine?.cancel() }

    fun options(s: AiSettings = store.ai.value, overrides: OpenAi.Request? = null) = GenOptions(
        temperature = overrides?.temperature ?: s.temperature,
        topP = overrides?.topP ?: s.topP,
        topK = s.topK,
        maxTokens = (overrides?.maxTokens ?: s.maxTokens).coerceIn(1, 8192),
        thinking = overrides?.thinking ?: s.thinking,
        repeatPenalty = s.repeatPenalty,
    )

    /** Fits a conversation into the context window and keeps only the newest image. */
    fun prepare(system: String?, msgs: List<ChatMsg>, opts: GenOptions): List<ChatMsg> {
        val all = (if (system.isNullOrBlank()) emptyList() else listOf(ChatMsg("system", system))) + msgs
        val trimmed = History.trimImages(all, 1)
        return History.fitToContext(trimmed, store.ai.value.contextSize, opts.maxTokens.coerceAtMost(store.ai.value.contextSize / 2) + 64)
    }

    suspend fun generate(who: String, msgs: List<ChatMsg>, opts: GenOptions, onStart: (Boolean) -> Unit, onToken: (String) -> Boolean): GenResult =
        mutex.withLock {
            val e = engine ?: return GenResult("", GenStats(0, 0, 0, 0), "error", false, "No model loaded. Open Models and tap Load.")
            _busy.value = who
            try {
                withContext(engineThread) {
                    try { e.generate(msgs, opts, onStart, onToken) }
                    catch (t: Throwable) { GenResult("", GenStats(0, 0, 0, 0), "error", false, t.message ?: "Engine error") }
                }
            } finally { _busy.value = null }
        }

    suspend fun bench(): BenchResult? = mutex.withLock {
        val e = engine ?: return null
        val spec = loadedSpec ?: return null
        _busy.value = "bench"
        try {
            val r = withContext(engineThread) { e.bench() }
            store.addBench(BenchRecord(spec.id, r.promptTps, r.genTps, System.currentTimeMillis(), e.backendLabel))
            r
        } finally { _busy.value = null }
    }

    fun systemInfo(): String = if (LlamaNative.ensureLoaded(ctx.applicationInfo.nativeLibraryDir)) LlamaNative.nativeSystemInfo() else "native: ${LlamaNative.loadError}"
    fun gpuAvailable(): Boolean = LlamaNative.ensureLoaded(ctx.applicationInfo.nativeLibraryDir) && LlamaNative.nativeHasGpu()

    // ---- auto-unload when the app is in the background (unless the API server is on) ----
    private var unloadJob: Job? = null
    fun onBackground(apiOn: Boolean) {
        if (!store.ai.value.autoUnload || apiOn) return
        unloadJob?.cancel()
        unloadJob = scope.launch { delay(60_000); if (busy.value == null) unload() }
    }
    fun onForeground() { unloadJob?.cancel() }
}
