package vbh.ai.whatsapp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The Answer and Decline buttons on the incoming call notification. Watches and other apps that
 * forward notification buttons press these, so they answer and decline calls the same way.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val callID = intent.getStringExtra(EXTRA_CALL_ID)
        if (callID == null || WaState.call.value?.id != callID) return
        WaState.log("call notification: ${intent.action}")
        when (intent.action) {
            ANSWER -> {
                WaCore.answer()
                // Show the call; allowed from the background while the app may draw over other apps.
                runCatching {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }

            DECLINE -> {
                WaCore.reject()
            }

            HANG_UP -> {
                WaCore.hangup()
            }
        }
    }

    companion object {
        const val ANSWER = "vbh.ai.whatsapp.ANSWER_CALL"
        const val DECLINE = "vbh.ai.whatsapp.DECLINE_CALL"
        const val HANG_UP = "vbh.ai.whatsapp.HANG_UP_CALL"
        private const val EXTRA_CALL_ID = "callID"

        fun pending(
            context: Context,
            action: String,
            callID: String,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                action.hashCode(),
                Intent(context, CallActionReceiver::class.java).setAction(action).putExtra(EXTRA_CALL_ID, callID),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
