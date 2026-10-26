package vbh.ai.whatsapp

import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * The Light Phone's scroll wheel, when the "Scroll wheel" setting is on navigate. Turning it
 * moves the highlight up or down the screen and pressing it activates what's highlighted, as the
 * d-pad's up, down and centre keys do.
 *
 * The Luma launcher normally takes the wheel for the whole phone (brightness, and its keymap on
 * press), so the app claims it from Luma while it's in front and gives it back when it leaves.
 */
object ScrollWheel {
    // LightOS's key codes for the wheel (LightDeviceKeys in light-keyboard).
    private const val TURN_UP = 317
    private const val TURN_DOWN = 318
    private const val PRESS = 319

    private const val LUMA = "com.vandam.luma"
    private const val ACTION_CLAIM = "com.vandam.luma.action.SCROLLWHEEL"

    /** Asks Luma to pass the wheel to this app while it's in front ([on]), or to take it back. */
    fun claim(
        context: Context,
        on: Boolean,
    ) {
        context.sendBroadcast(
            Intent(ACTION_CLAIM)
                .setPackage(LUMA)
                .putExtra("package", context.packageName)
                .putExtra("claim", on),
        )
    }

    /** Whether the wheel navigates on [screen]: not where the keyboard or a recording has the screen. */
    fun navigates(screen: Screen) =
        when (screen) {
            is Screen.Composer, is Screen.EditMessage, Screen.Search, is Screen.Number,
            is Screen.VoiceRecord, is Screen.MediaViewer,
            -> false

            else -> true
        }

    /** The d-pad key a wheel [event] stands for, or null when it isn't the wheel. */
    fun asDpad(event: KeyEvent): KeyEvent? {
        val code =
            when (event.keyCode) {
                TURN_UP -> KeyEvent.KEYCODE_DPAD_UP
                TURN_DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                PRESS -> KeyEvent.KEYCODE_DPAD_CENTER
                else -> return null
            }
        return KeyEvent(
            event.downTime,
            event.eventTime,
            event.action,
            code,
            event.repeatCount,
            event.metaState,
            event.deviceId,
            event.scanCode,
            event.flags,
            event.source,
        )
    }
}
