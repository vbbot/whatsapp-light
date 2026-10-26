package vbh.ai.whatsapp

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * One button for a call (the scroll wheel, an earphone's button): a short press answers a ringing
 * call, and holding it declines the call or, once it's on, hangs up. With [shortHangsUp], a short
 * press on a call that's on hangs up too, as earphones do.
 */
class CallPress(
    private val shortHangsUp: Boolean,
) {
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
                when {
                    !short -> {}

                    ringing -> {
                        if (call.video) CameraGate.withCamera { ok -> WaCore.answer(withCamera = ok) } else WaCore.answer()
                    }

                    shortHangsUp -> {
                        WaCore.hangup()
                    }
                }
            }
        }
    }

    private companion object {
        /** A press held this long on a call ends it. */
        const val LONG_PRESS_MS = 600L
    }
}

/**
 * Earphone buttons on a call. Calls aren't registered with Android's call system (see #77), so
 * a headset's button arrives as a media key (play/pause or the headset hook). It goes to the
 * media session of the app that last played sound, which while a call rings or is on is this one,
 * so the app holds an active session for as long as there's a call.
 */
object CallButtons {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val press = CallPress(shortHangsUp = true)

    private val BUTTONS =
        setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )

    fun init(context: Context) {
        scope.launch {
            WaState.call
                .map { it != null }
                .distinctUntilChanged()
                .collect { on -> if (on) open(context) else close() }
        }
    }

    private fun open(context: Context) {
        if (session != null) return
        session =
            MediaSession(context, "call").apply {
                setCallback(
                    object : MediaSession.Callback() {
                        override fun onMediaButtonEvent(intent: Intent): Boolean {
                            val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                            Log.d(TAG, "button ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}")
                            if (event.keyCode !in BUTTONS) return false
                            WaState.call.value?.let { press.onKey(event, it, scope) }
                            return true
                        }
                    },
                )
                // Playing, so the system sends this session the media buttons.
                setPlaybackState(
                    PlaybackState
                        .Builder()
                        .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build(),
                )
                isActive = true
            }
    }

    private fun close() {
        session?.release()
        session = null
    }

    private const val TAG = "CallButtons"
}
