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
    private var ping: PowerManager.WakeLock? = null
    private var until = 0L

    fun init(context: Context) {
        if (work != null) return
        val pm = context.getSystemService(PowerManager::class.java)
        work = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waPoc:work").apply { setReferenceCounted(false) }
        call = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waPoc:call").apply { setReferenceCounted(false) }
        ping = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waPoc:ping").apply { setReferenceCounted(false) }
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

    /** Runs [block] (a keepalive ping) awake, letting go as soon as it returns, or after [maxMs]. */
    fun whilePinging(
        maxMs: Long,
        block: () -> Unit,
    ) {
        val p = ping
        p?.acquire(maxMs)
        try {
            block()
        } finally {
            if (p?.isHeld == true) p.release()
        }
    }

    private const val CALL_MAX_MS = 4 * 60 * 60_000L
}

/**
 * Wakes the app to ping WhatsApp while the phone sleeps (whatsmeow's own keepalive timer stops with
 * the CPU). Without pings the server drops an idle socket after about a minute, and reconnecting
 * costs far more than a ping. The same approach as Signal and Molly without push: an exact
 * allow-while-idle alarm, a short ping, and a fresh connection when a ping goes unanswered.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        Wake.init(context)
        schedule(context)
        // The system keeps the CPU up only while onReceive runs; hold it until the ping is answered.
        val done = goAsync()
        thread(name = "wa-keepalive") {
            try {
                Wake.whilePinging(PING_MAX_MS) { WaCore.keepAlive() }
            } finally {
                done.finish()
            }
        }
    }

    companion object {
        /**
         * Between pings: under the shortest idle time after which the server was seen closing the
         * socket (about a minute). Android may stretch this while the phone is in deep Doze.
         */
        const val INTERVAL_MS = 45_000L

        /** A ping is answered in well under a second; this only caps a stuck one (walib waits 10 s). */
        private const val PING_MAX_MS = 12_000L

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
