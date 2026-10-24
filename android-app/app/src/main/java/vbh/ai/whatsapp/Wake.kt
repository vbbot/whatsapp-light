package vbh.ai.whatsapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * Keeps the CPU awake only while there's work, so the phone can sleep between messages. Incoming
 * data on the WhatsApp socket wakes the phone briefly; each event then holds the CPU for a few
 * seconds ([hold]), and a call holds it for its whole length ([inCall]).
 */
object Wake {
    private var work: PowerManager.WakeLock? = null
    private var call: PowerManager.WakeLock? = null
    private var until = 0L

    fun init(context: Context) {
        if (work != null) return
        val pm = context.getSystemService(PowerManager::class.java)
        work = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waPoc:work").apply { setReferenceCounted(false) }
        call = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waPoc:call").apply { setReferenceCounted(false) }
    }

    /** Keeps the CPU awake for at least [ms] from now (extends, never shortens, the current hold). */
    @Synchronized
    fun hold(ms: Long) {
        val w = work ?: return
        val now = SystemClock.elapsedRealtime()
        if (w.isHeld && now + ms <= until) return
        until = now + ms
        w.acquire(ms)
    }

    /** Holds the CPU for a call's whole length (audio and video need it), with a long safety timeout. */
    @Synchronized
    fun inCall(active: Boolean) {
        val c = call ?: return
        if (active) {
            c.acquire(CALL_MAX_MS)
        } else if (c.isHeld) {
            c.release()
        }
    }

    private const val CALL_MAX_MS = 4 * 60 * 60_000L
}

/**
 * Wakes the app every few minutes to check the connection: whatsmeow's own keepalive timer stops
 * while the CPU sleeps, so without this the server could drop the socket unnoticed.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Wake.init(context)
        Wake.hold(KEEPALIVE_HOLD_MS)
        schedule(context)
        thread(name = "wa-keepalive") { WaCore.keepAlive() }
    }

    companion object {
        /** Between checks. Android may stretch this while the phone is idle (Doze). */
        const val INTERVAL_MS = 5 * 60_000L

        /** Long enough to ping, or to reconnect and receive what queued up meanwhile. */
        private const val KEEPALIVE_HOLD_MS = 20_000L

        fun schedule(context: Context) {
            val am = context.getSystemService(AlarmManager::class.java)
            val pi =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, KeepAliveReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val at = SystemClock.elapsedRealtime() + INTERVAL_MS
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }
    }
}
