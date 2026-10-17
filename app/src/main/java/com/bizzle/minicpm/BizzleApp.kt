package com.bizzle.minicpm

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bizzle.minicpm.data.ModelFiles
import com.bizzle.minicpm.data.Store
import com.bizzle.minicpm.download.Downloads
import com.bizzle.minicpm.engine.Hub
import com.bizzle.minicpm.server.ApiServer

class BizzleApp : Application() {
    lateinit var store: Store; private set
    lateinit var files: ModelFiles; private set
    lateinit var hub: Hub; private set
    lateinit var downloads: Downloads; private set
    lateinit var api: ApiServer; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        files = ModelFiles(this)
        hub = Hub(this, store, files)
        downloads = Downloads(this, files)
        api = ApiServer(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOAD, "Model downloads", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_API, "Local AI API server", NotificationManager.IMPORTANCE_LOW))
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { hub.onBackground(api.running.value) }
            override fun onStart(owner: LifecycleOwner) { hub.onForeground() }
        })
    }

    companion object {
        const val CH_DOWNLOAD = "downloads"
        const val CH_API = "api"
        lateinit var instance: BizzleApp; private set
    }
}
