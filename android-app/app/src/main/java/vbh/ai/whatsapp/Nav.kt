package vbh.ai.whatsapp

import kotlinx.coroutines.flow.MutableStateFlow

/** The two home destinations. */
enum class Tab(
    val label: String,
) {
    MESSAGES("Messages"),
    CALLS("Calls"),
}

/** What a contact pick does: open a chat, or place a call. */
enum class Purpose { MESSAGE, CALL }

sealed interface Screen {
    data object Home : Screen

    data class Thread(
        val chat: String,
    ) : Screen

    data class Composer(
        val chat: String,
    ) : Screen

    data class Contacts(
        val purpose: Purpose,
    ) : Screen

    data class Number(
        val purpose: Purpose,
    ) : Screen

    data object Settings : Screen

    data object Permissions : Screen

    /** Confirms logging out (Settings). */
    data object LogOut : Screen

    /** Confirms deleting one chat (from its options). */
    data class DeleteChat(
        val chat: String,
    ) : Screen

    /** Mute, archive and the other actions for one chat (long-press it, or tap its name in the thread). */
    data class ChatOptions(
        val chat: String,
    ) : Screen

    /** The archived chats (pull down at the top of the chat list). */
    data object Archived : Screen

    /** A message's photo (or a video's thumbnail) full screen; [path] is what it showed when opened. */
    data class MediaViewer(
        val id: String,
        val path: String,
        val backTo: String,
    ) : Screen

    /** Choose voice or video before calling [chat]. */
    data class CallType(
        val chat: String,
    ) : Screen

    /** Confirm placing a voice or video call to [chat]. */
    data class CallConfirm(
        val chat: String,
        val video: Boolean,
    ) : Screen
}

/** Identifies a back-stack entry, for keeping its screen's saved state: position and destination. */
fun entryKey(
    index: Int,
    screen: Screen,
) = "$index/$screen"

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

    /** Removes every screen about [chat] (its thread, composer, options), e.g. once it's deleted. */
    fun dropChat(chat: String) {
        val kept =
            stack.value.filterNot {
                when (it) {
                    is Screen.Thread -> it.chat == chat
                    is Screen.Composer -> it.chat == chat
                    is Screen.ChatOptions -> it.chat == chat
                    is Screen.DeleteChat -> it.chat == chat
                    else -> false
                }
            }
        stack.value = kept.ifEmpty { listOf(Screen.Home) }
        sync()
    }

    /** Back to the chat list, as after logging out. */
    fun reset() {
        stack.value = listOf(Screen.Home)
        tab.value = Tab.MESSAGES
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
        val chat =
            when (t) {
                is Screen.Thread -> t.chat
                is Screen.Composer -> t.chat
                else -> null
            }
        MessageStore.openChat.value = chat
        chat?.let { MessageStore.markRead(it) }
    }
}
