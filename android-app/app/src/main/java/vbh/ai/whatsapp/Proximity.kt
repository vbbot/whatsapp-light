package vbh.ai.whatsapp

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Turns the screen off when the phone is held to the ear during a voice call, so a cheek can't
 * press the call buttons. Follows [WaState.call], so it also covers calls answered from the lock
 * screen.
 */
object Proximity {
    private var lock: PowerManager.WakeLock? = null

    fun init(context: Context) {
        if (lock != null) return
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        lock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "waPoc:proximity").apply { setReferenceCounted(false) }
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            WaState.call
                .map { it to wanted(it) }
                .distinctUntilChanged { a, b -> a.second == b.second }
                .collect { (call, on) -> set(on, ended = call == null) }
        }
    }

    /** On the earpiece in a voice call that's ringing out or answered; never on speaker or video. */
    fun wanted(c: CallUi?): Boolean = c != null && !c.video && !c.speaker && !(c.incoming && c.phase < 3)

    private fun set(
        on: Boolean,
        ended: Boolean,
    ) {
        val l = lock ?: return
        if (on) {
            l.acquire(CALL_MAX_MS)
        } else if (l.isHeld) {
            // After hanging up, wait until the phone leaves the ear so the screen doesn't light on the cheek.
            l.release(if (ended) PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY else 0)
        }
    }

    private const val CALL_MAX_MS = 4 * 60 * 60_000L
}
