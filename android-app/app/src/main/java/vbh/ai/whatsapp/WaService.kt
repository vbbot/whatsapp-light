package vbh.ai.whatsapp

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Foreground service that keeps the process (and the WhatsApp connection) alive. It runs as a
 * messaging service, and also as a microphone service once the microphone is allowed, so calls
 * keep their audio with the screen off. Started again after the microphone is granted.
 */
class WaService : Service() {
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        WaCore.start(applicationContext)
        val messaging = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        try {
            startForeground(
                1,
                WaCore.serviceNotification(),
                if (mic) messaging or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else messaging,
            )
        } catch (e: SecurityException) {
            // A restart from the background can't claim the microphone; messaging still runs.
            WaState.log("foreground service without microphone: ${e.message}")
            startForeground(1, WaCore.serviceNotification(), messaging)
        }
        // No wake lock held here: the CPU sleeps between events (see Wake), and an alarm
        // pings WhatsApp every 45 s while the phone sleeps (see KeepAliveReceiver).
        KeepAliveReceiver.schedule(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
