package com.wapoc.app

import kotlinx.coroutines.flow.MutableStateFlow

/** The two home destinations. */
enum class Tab(val label: String) { MESSAGES("Messages"), CALLS("Calls") }

/** What a contact pick does: open a chat, or place a call. */
enum class Purpose { MESSAGE, CALL }

sealed interface Screen {
    data object Home : Screen
    data class Thread(val chat: String) : Screen
    data class Composer(val chat: String) : Screen
    data class Contacts(val purpose: Purpose) : Screen
    data class Number(val purpose: Purpose) : Screen
    data object Settings : Screen
    /** Choose voice or video before calling [chat]. */
    data class CallType(val chat: String) : Screen
    /** Confirm placing a voice or video call to [chat]. */
    data class CallConfirm(val chat: String, val video: Boolean) : Screen
}

/** Minimal back stack. Keeps [MessageStore.openChat] in sync so open threads stay quiet. */
object Nav {
    val stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val tab = MutableStateFlow(Tab.MESSAGES)

    fun push(s: Screen) {
        stack.value = stack.value + s
        sync()
    }

    /** Replaces the top screen (e.g. composer -> back to its thread is a plain pop). */
    fun replaceTop(s: Screen) {
        stack.value = stack.value.dropLast(1) + s
        sync()
    }

    fun pop(): Boolean {
        if (stack.value.size <= 1) return false
        stack.value = stack.value.dropLast(1)
        sync()
        return true
    }

    private fun sync() {
        val t = stack.value.lastOrNull { it is Screen.Thread || it is Screen.Composer }
        val chat = when (t) {
            is Screen.Thread -> t.chat
            is Screen.Composer -> t.chat
            else -> null
        }
        MessageStore.openChat.value = chat
        chat?.let { MessageStore.markRead(it) }
    }
}
