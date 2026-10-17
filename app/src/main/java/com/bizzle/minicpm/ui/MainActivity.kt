package com.bizzle.minicpm.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bizzle.minicpm.BizzleApp
import com.bizzle.minicpm.ui.components.BizzyPopup
import com.bizzle.minicpm.ui.screens.*
import com.bizzle.minicpm.ui.theme.BizzleTheme

class MainActivity : ComponentActivity() {
    private val nav = Nav()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as BizzleApp
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        if (app.store.apiEnabled && !app.api.running.value) runCatching { com.bizzle.minicpm.server.ApiService.start(this) }
        handleShare(intent)
        setContent {
            val theme by app.store.theme.collectAsStateWithLifecycle()
            var bizzyOpen by remember { mutableStateOf(false) }
            BizzleTheme(theme) {
                BackHandler(enabled = nav.stack.size > 1) { nav.back() }
                val openBizzy = { bizzyOpen = true }
                when (val s = nav.current) {
                    Screen.Home -> HomeScreen(nav, openBizzy)
                    is Screen.Chat -> ChatScreen(nav, s, openBizzy)
                    Screen.Models -> ModelsScreen(nav, openBizzy)
                    Screen.AiSettings -> AiSettingsScreen(nav, openBizzy)
                    Screen.Themes -> ThemesScreen(nav, openBizzy)
                    Screen.Api -> ApiScreen(nav, openBizzy)
                    Screen.Limits -> LimitsScreen(nav, openBizzy)
                    Screen.About -> AboutScreen(nav, openBizzy)
                    Screen.History -> HistoryScreen(nav, openBizzy)
                }
                if (bizzyOpen) BizzyPopup(nav.current.title, nav) { bizzyOpen = false }
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleShare(intent) }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val app = application as BizzleApp
        val id = app.store.newChatId()
        if (intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
            val path = uri?.let { ImageUtil.import(this, it) }
            nav.go(Screen.Chat(id, sharedImage = path, sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)))
        } else {
            nav.go(Screen.Chat(id, sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)))
        }
        intent.action = null
    }
}
