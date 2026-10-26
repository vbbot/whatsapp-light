package vbh.ai.whatsapp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** User preferences, persisted in SharedPreferences. */
object AppSettings {
    /** Send read receipts ("blue ticks") when a chat is opened. On by default, like WhatsApp. */
    val seenStatus = MutableStateFlow(true)

    /** The first-run permissions screen has been seen; afterwards it opens only from Settings. */
    val permissionsDone = MutableStateFlow(false)

    /** What the scroll wheel does in the app: LightOS's brightness (the default), or moving through the screen. */
    val wheelNavigates = MutableStateFlow(false)
    private var prefs: android.content.SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs =
            context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE).also {
                seenStatus.value = it.getBoolean("seenStatus", true)
                permissionsDone.value = it.getBoolean("permissionsDone", false)
                wheelNavigates.value = it.getBoolean("wheelNavigates", false)
            }
    }

    fun setSeenStatus(on: Boolean) {
        seenStatus.value = on
        prefs?.edit()?.putBoolean("seenStatus", on)?.apply()
    }

    fun setWheelNavigates(on: Boolean) {
        wheelNavigates.value = on
        prefs?.edit()?.putBoolean("wheelNavigates", on)?.apply()
    }

    fun setPermissionsDone() {
        permissionsDone.value = true
        prefs?.edit()?.putBoolean("permissionsDone", true)?.apply()
    }

    /** Whether the system has asked for [permission] before (so a silent refusal means "don't ask again"). */
    fun askedFor(permission: String) = prefs?.getBoolean("asked:$permission", false) == true

    fun markAsked(permission: String) {
        prefs?.edit()?.putBoolean("asked:$permission", true)?.apply()
    }
}
