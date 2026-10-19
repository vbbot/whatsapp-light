package vbh.ai.whatsapp

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/** Foreground service that keeps the process (and the WhatsApp connection) alive. */
class WaService : Service() {
    private var wake: PowerManager.WakeLock? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        WaCore.start(applicationContext)
        startForeground(1, WaCore.serviceNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (wake == null) {
            wake =
                (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waPoc:conn")
                    .also { it.acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wake?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
