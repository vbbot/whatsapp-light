package vbh.ai.whatsapp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** User preferences, persisted in SharedPreferences. */
object AppSettings {
    /** Send read receipts ("blue ticks") when a chat is opened. On by default, like WhatsApp. */
    val seenStatus = MutableStateFlow(true)
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs =
            context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE).also {
                seenStatus.value = it.getBoolean("seenStatus", true)
            }
    }

    fun setSeenStatus(on: Boolean) {
        seenStatus.value = on
        prefs?.edit()?.putBoolean("seenStatus", on)?.apply()
    }
}
