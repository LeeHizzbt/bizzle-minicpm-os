package com.bizzle.minicpm.download

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.PowerManager
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.R
import com.bizzle.minicpm.core.Bytes
import com.bizzle.minicpm.core.Downloader
import com.bizzle.minicpm.core.FileRole
import com.bizzle.minicpm.core.ModelCatalog
import com.bizzle.minicpm.core.ModelFile
import com.bizzle.minicpm.core.ModelSpec
import com.bizzle.minicpm.data.ModelFiles
import com.bizzle.minicpm.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.LinkedBlockingDeque

data class DlState(val modelId: String, val done: Long, val total: Long, val speed: Long, val status: Status, val message: String = "") {
    enum class Status { QUEUED, RUNNING, VERIFYING, DONE, PAUSED, FAILED }
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/** Download queue shared by the UI and the foreground service. */
class Downloads(private val ctx: Context, private val files: ModelFiles) {
    private val _states = MutableStateFlow<Map<String, DlState>>(emptyMap())
    val states: StateFlow<Map<String, DlState>> = _states
    internal val queue = LinkedBlockingDeque<Pair<String, Boolean>>() // modelId, includeDetailProjector
    @Volatile internal var current: Downloader? = null
    @Volatile internal var currentId: String? = null

    internal fun update(s: DlState) { _states.value = _states.value + (s.modelId to s) }

    fun isMetered(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    fun isOnline(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun filesFor(m: ModelSpec, detail: Boolean): List<ModelFile> = m.files.filter { it.role != FileRole.MMPROJ_DETAIL || detail }

    /** Returns an error message, or null when queued. */
    fun start(m: ModelSpec, detail: Boolean = false): String? {
        if (!isOnline()) return "You're offline. Connect to Wi-Fi to download models (the app works offline after that)."
        val missing = filesFor(m, detail).filter { !files.isDone(it) }.sumOf { it.sizeBytes - files.partialBytes(it) }
        val free = files.freeBytes()
        if (missing + 300L * 1024 * 1024 > free) return "Not enough storage: need ${Bytes.human(missing)} plus some headroom, ${Bytes.human(free)} free."
        if (queue.any { it.first == m.id } || currentId == m.id) return null
        queue.add(m.id to detail)
        update(DlState(m.id, 0, filesFor(m, detail).sumOf { it.sizeBytes }, 0, DlState.Status.QUEUED))
        ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
        return null
    }

    fun pause(id: String) {
        queue.removeIf { it.first == id }
        if (currentId == id) current?.cancelled = true
        else _states.value[id]?.let { update(it.copy(status = DlState.Status.PAUSED, message = "Paused")) }
    }
}

class DownloadService : Service() {
    private val app get() = BizzleApp.instance
    @Volatile private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7, notif("Preparing download…", 0, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (worker?.isAlive != true) worker = Thread({ runQueue() }, "bizzle-download").also { it.start() }
        return START_NOT_STICKY
    }

    private fun runQueue() {
        val dl = app.downloads
        val wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bizzle:download")
        wl.acquire(6 * 60 * 60 * 1000L)
        try {
            while (true) {
                val (id, detail) = dl.queue.poll() ?: break
                val m = ModelCatalog.byId(id) ?: continue
                val list = dl.filesFor(m, detail)
                val total = list.sumOf { it.sizeBytes }
                val d = Downloader()
                dl.current = d; dl.currentId = id
                try {
                    var base = 0L
                    for (f in list) {
                        if (app.files.isDone(f)) { base += f.sizeBytes; continue }
                        d.download(f.url, app.files.file(f), f.sizeBytes, f.sha256) { done, _, speed ->
                            val st = if (done >= f.sizeBytes) DlState.Status.VERIFYING else DlState.Status.RUNNING
                            dl.update(DlState(id, base + done, total, speed, st, if (st == DlState.Status.VERIFYING) "Checking SHA-256…" else f.name))
                            notify(m.name, base + done, total)
                        }
                        base += f.sizeBytes
                    }
                    dl.update(DlState(id, total, total, 0, DlState.Status.DONE, "Ready to load"))
                } catch (e: Downloader.DownloadException) {
                    val paused = d.cancelled
                    val done = list.sumOf { if (app.files.isDone(it)) it.sizeBytes else app.files.partialBytes(it) }
                    dl.update(DlState(id, done, total, 0, if (paused) DlState.Status.PAUSED else DlState.Status.FAILED, e.message ?: "Failed"))
                } catch (e: Exception) {
                    dl.update(DlState(id, 0, total, 0, DlState.Status.FAILED, e.message ?: "Failed"))
                } finally { dl.current = null; dl.currentId = null }
            }
        } finally {
            if (wl.isHeld) wl.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private var lastNotify = 0L
    private fun notify(name: String, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java).notify(7, notif("Downloading $name · ${Bytes.human(done)} / ${Bytes.human(total)}", done, total))
    }

    private fun notif(text: String, done: Long, total: Long): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, BizzleApp.CH_DOWNLOAD).setSmallIcon(R.drawable.ic_stat_bizzle)
            .setContentTitle("Bizzle model download").setContentText(text).setContentIntent(pi).setOngoing(true)
            .apply { if (total > 0) setProgress(1000, (done * 1000 / total).toInt(), false) }.build()
    }
}
