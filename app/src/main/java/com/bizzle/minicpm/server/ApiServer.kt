package com.bizzle.minicpm.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Base64
import android.util.Log
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.R
import com.bizzle.minicpm.core.*
import com.bizzle.minicpm.engine.EngineState
import com.bizzle.minicpm.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * OpenAI-compatible server bound to 127.0.0.1 only (never reachable from Wi-Fi). Lets Bizzy Voice, Kief and other
 * apps on this phone share the one loaded model: POST /v1/chat/completions (text + base64 images, streaming), GET /v1/models.
 */
class ApiServer(private val ctx: Context) {
    companion object { const val PORT = 8420; private const val TAG = "BizzleApi" }
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _requests = MutableStateFlow(0)
    val requests: StateFlow<Int> = _requests
    private val _lastLog = MutableStateFlow("")
    val lastLog: StateFlow<String> = _lastLog
    @Volatile private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)
    private val app get() = BizzleApp.instance

    @Synchronized fun start(): String? {
        if (socket != null) return null
        return try {
            val s = ServerSocket(PORT, 16, InetAddress.getByName("127.0.0.1"))
            socket = s; _running.value = true
            Thread({ acceptLoop(s) }, "bizzle-api").start()
            null
        } catch (e: Exception) { "Could not open port $PORT: ${e.message}" }
    }

    @Synchronized fun stop() { runCatching { socket?.close() }; socket = null; _running.value = false }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: SocketException) { break }
            pool.execute { handle(c) }
        }
    }

    private fun handle(c: Socket): Unit = c.use { sock ->
        sock.soTimeout = 120_000
        val out = sock.getOutputStream()
        try {
            val req = MiniHttp.read(BufferedInputStream(sock.getInputStream())) ?: return@use
            _requests.value++
            _lastLog.value = "${req.method} ${req.path}"
            if (req.method == "OPTIONS") { MiniHttp.writeEmpty(out, 204); return@use }
            val token = if (app.store.apiRequireToken) app.store.apiToken else ""
            when {
                req.path == "/health" || req.path == "/" -> MiniHttp.writeJson(out, 200, health())
                !MiniHttp.authorized(req, token) -> MiniHttp.writeJson(out, 401, OpenAi.error("Missing or wrong bearer token. Copy it from the app's Local API screen.", "authentication_error"))
                req.path == "/v1/models" && req.method == "GET" -> MiniHttp.writeJson(out, 200, OpenAi.models(modelIds(), System.currentTimeMillis() / 1000))
                req.path == "/v1/chat/completions" && req.method == "POST" -> chat(req, out)
                req.path.startsWith("/v1/") -> MiniHttp.writeJson(out, 404, OpenAi.error("Unknown endpoint ${req.path}"))
                else -> MiniHttp.writeJson(out, 404, OpenAi.error("Not found"))
            }
        } catch (e: MiniHttp.HttpError) {
            runCatching { MiniHttp.writeJson(out, e.status, OpenAi.error(e.message ?: "bad request")) }
        } catch (e: Exception) {
            Log.w(TAG, "request failed", e)
            runCatching { MiniHttp.writeJson(out, 500, OpenAi.error(e.message ?: "server error", "server_error")) }
        }
        Unit
    }

    private fun health(): String {
        val st = app.hub.state.value
        val model = (st as? EngineState.Ready)?.spec?.id
        return """{"status":"ok","app":"bizzle-minicpm-os","version":"4.20","loaded":${model != null},"model":${if (model == null) "null" else "\"$model\""}}"""
    }

    private fun modelIds(): List<String> {
        val loaded = app.hub.loadedSpec?.id
        val ready = ModelCatalog.all.filter { app.files.isReady(it) }.map { it.id }
        return (listOfNotNull(loaded) + ready).distinct().ifEmpty { listOf("none-downloaded") }
    }

    private fun chat(req: MiniHttp.Request, out: java.io.OutputStream) {
        val r = try { OpenAi.parseChatRequest(req.bodyText) } catch (e: OpenAi.BadRequest) { return MiniHttp.writeJson(out, 400, OpenAi.error(e.message ?: "bad request")) }
        // Auto-load: the requested model if downloaded, otherwise the last-used one.
        val hub = app.hub
        val wanted = ModelCatalog.byId(r.model)?.takeIf { app.files.isReady(it) }
        if (hub.loadedSpec == null || (wanted != null && hub.loadedSpec?.id != wanted.id)) {
            val spec = wanted ?: ModelCatalog.byId(app.store.selectedModelId)?.takeIf { app.files.isReady(it) }
                ?: return MiniHttp.writeJson(out, 503, OpenAi.error("No model is loaded or downloaded. Open Bizzle's Mini CPM OS and load a model.", "model_not_loaded"))
            runBlocking { hub.load(spec) }?.let { return MiniHttp.writeJson(out, 503, OpenAi.error(it, "model_not_loaded")) }
        }
        val tmp = mutableListOf<File>()
        try {
            val msgs = r.messages.map { m ->
                val imgs = m.images.map { ip ->
                    val (mime, b64) = OpenAi.parseDataUrl(ip.url) ?: throw OpenAi.BadRequest("Only base64 data: image URLs are supported (the server is offline).")
                    if (!mime.startsWith("image/")) throw OpenAi.BadRequest("Unsupported image type $mime")
                    val f = File(ctx.cacheDir, "api-${UUID.randomUUID()}.img")
                    f.writeBytes(Base64.decode(b64, Base64.DEFAULT)); tmp += f; f.absolutePath
                }
                ChatMsg(m.role, m.text, imgs)
            }
            val opts = hub.options(overrides = r)
            val sys = if (msgs.none { it.role == "system" }) app.store.ai.value.systemPrompt else null
            val prepared = hub.prepare(sys, msgs, opts)
            val id = "chatcmpl-" + UUID.randomUUID().toString().take(12)
            val model = hub.loadedSpec?.id ?: "bizzle"
            val created = System.currentTimeMillis() / 1000
            if (r.stream) {
                MiniHttp.writeSseHeaders(out)
                MiniHttp.writeSse(out, OpenAi.chunk(id, model, created, null, null, role = true))
                val raw = StringBuilder(); var think = false; var sent = 0
                val res = runBlocking {
                    hub.generate("api", prepared, opts, { think = it }) { piece ->
                        raw.append(piece)
                        val ans = ThinkParser.parse(raw.toString(), think).answer
                        if (ans.length > sent) {
                            val delta = ans.substring(sent); sent = ans.length
                            try { MiniHttp.writeSse(out, OpenAi.chunk(id, model, created, delta, null)); true } catch (e: Exception) { false }
                        } else true
                    }
                }
                if (res.error != null) runCatching { MiniHttp.writeSse(out, OpenAi.error(res.error!!, "server_error")) }
                runCatching {
                    MiniHttp.writeSse(out, OpenAi.chunk(id, model, created, null, if (res.finish == "length") "length" else "stop"))
                    MiniHttp.writeSse(out, "[DONE]")
                }
            } else {
                var think = false
                val res = runBlocking { hub.generate("api", prepared, opts, { think = it }) { true } }
                if (res.error != null) return MiniHttp.writeJson(out, 500, OpenAi.error(res.error!!, "server_error"))
                val split = ThinkParser.parse(res.text, res.startsInThink || think)
                MiniHttp.writeJson(out, 200, OpenAi.completion(id, model, created, split.answer, split.reasoning,
                    if (res.finish == "length") "length" else "stop", res.stats.promptTokens, res.stats.genTokens))
            }
        } catch (e: OpenAi.BadRequest) {
            MiniHttp.writeJson(out, 400, OpenAi.error(e.message ?: "bad request"))
        } finally { tmp.forEach { it.delete() } }
    }
}

/** Keeps the process alive (with a visible notification) while the local API is on. */
class ApiService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = BizzleApp.instance
        if (intent?.action == "stop") { app.api.stop(); app.store.apiEnabled = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ApiService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, BizzleApp.CH_API).setSmallIcon(R.drawable.ic_stat_bizzle)
            .setContentTitle("Local AI API on").setContentText("http://127.0.0.1:${ApiServer.PORT}/v1 · this phone only")
            .setContentIntent(pi).setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat_bizzle), "Stop", stop).build()).build()
        startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val err = app.api.start()
        if (err != null) { app.store.apiEnabled = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } else app.store.apiEnabled = true
        return START_STICKY
    }
    override fun onDestroy() { BizzleApp.instance.api.stop(); super.onDestroy() }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, ApiService::class.java))
        fun stop(ctx: Context) { BizzleApp.instance.api.stop(); BizzleApp.instance.store.apiEnabled = false; ctx.stopService(Intent(ctx, ApiService::class.java)) }
    }
}

class ApiTileService : TileService() {
    override fun onStartListening() { refresh() }
    override fun onClick() {
        val app = BizzleApp.instance
        if (app.api.running.value) ApiService.stop(this) else ApiService.start(this)
        qsTile?.let { it.state = if (!app.api.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; it.updateTile() }
    }
    private fun refresh() { qsTile?.let { it.state = if (BizzleApp.instance.api.running.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE; it.updateTile() } }
}
