package com.bizzle.minicpm.ui

import androidx.compose.runtime.mutableStateListOf

sealed class Screen(val title: String) {
    object Home : Screen("Home")
    data class Chat(val id: String, val sharedImage: String? = null, val sharedText: String? = null, val openCamera: Boolean = false) : Screen("Chat")
    object Models : Screen("Models")
    object AiSettings : Screen("Advanced AI Settings")
    object Themes : Screen("Themes")
    object Api : Screen("Local API")
    object Limits : Screen("Limits")
    object About : Screen("About")
    object History : Screen("History")
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    fun go(s: Screen) { if (stack.last() != s) stack.add(s) }
    fun back(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); return true }
    fun replace(s: Screen) { stack[stack.lastIndex] = s }
    fun home() { stack.clear(); stack.add(Screen.Home) }
}
