package vbh.ai.whatsapp

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The scroll wheel's press on a call: a short press answers a ringing call, and holding it
 * declines the call or, once it's on, hangs up. A short press on a call that's on does nothing,
 * so a brush can't end it.
 */
class CallPress {
    /** Waits out a press held on a call; it ends the call if the press is still down when it fires. */
    private var holding: Job? = null

    fun onKey(
        event: KeyEvent,
        call: CallUi,
        scope: CoroutineScope,
    ) {
        val ringing = call.incoming && call.phase < 3
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) return
                holding?.cancel()
                holding =
                    scope.launch {
                        delay(LONG_PRESS_MS)
                        if (ringing) WaCore.reject() else WaCore.hangup()
                    }
            }

            KeyEvent.ACTION_UP -> {
                val short = holding?.isActive == true
                holding?.cancel()
                holding = null
                if (short && ringing) {
                    if (call.video) CameraGate.withCamera { ok -> WaCore.answer(withCamera = ok) } else WaCore.answer()
                }
            }
        }
    }

    private companion object {
        /** A press held this long on a call ends it. */
        const val LONG_PRESS_MS = 600L
    }
}
