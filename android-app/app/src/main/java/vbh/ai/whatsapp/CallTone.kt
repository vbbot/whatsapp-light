package vbh.ai.whatsapp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays a generated 425 Hz ringback tone while an outgoing call rings, on the voice-call stream.
 * Incoming calls keep the system ringtone in [WaCore]; this tone is never mixed with it.
 */
object CallTone {
    private const val RATE = 8_000
    private const val HZ = 425.0
    private const val ON_MS = 1_000
    private const val OFF_MS = 2_000

    /** Quiet enough to sit under speech, since the phone's call volume still applies. */
    private const val AMPLITUDE = 0.25

    /** Fade the tone in and out, in samples, so its edges don't click. */
    private const val FADE = 80
    private const val CHUNK = RATE / 50 // 20 ms of 8 kHz mono s16le.

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile private var on = false

    /** Ringback belongs only to an outgoing call that is ringing. */
    fun ringbackWanted(call: CallUi?): Boolean = call != null && !call.incoming && call.phase == 2

    /** Starts or stops the tone to match [call]; safe to call for every state change. */
    @Synchronized
    fun follow(call: CallUi?) {
        if (ringbackWanted(call)) start() else stop()
    }

    @Synchronized
    private fun start() {
        if (on) return
        val pcm = pattern()
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t =
            AudioTrack
                .Builder()
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                ).setBufferSizeInBytes(maxOf(min, pcm.size))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            WaState.log("ringback: audio track unavailable")
            t.release()
            return
        }
        t.play()
        track = t
        on = true
        thread = Thread({ loop(t, pcm) }, "wa-ringback").also { it.start() }
    }

    /** Feeds the looped pattern; non-blocking writes keep a stop request from waiting on audio. */
    private fun loop(
        t: AudioTrack,
        pcm: ByteArray,
    ) {
        val chunks = (pcm.size + CHUNK - 1) / CHUNK
        var i = 0
        while (on) {
            val offset = (i % chunks) * CHUNK
            val n = t.write(pcm, offset, min(CHUNK, pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
            if (n > 0) {
                i++
            } else if (n < 0) {
                return // The track is gone; nothing left to play.
            } else {
                runCatching { Thread.sleep(20) } // Buffer full: wait for it to drain.
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!on && track == null) return
        on = false
        val t = track
        track = null
        // Pause first so a writer waiting for buffer space gives up instead of blocking teardown.
        t?.runCatching { pause() }
        runCatching { thread?.join(500) }
        thread = null
        t?.runCatching {
            flush()
            release()
        }
    }

    /** 1 s of 425 Hz, then 2 s of silence; the writer repeats it. */
    private fun pattern(): ByteArray {
        val sounding = RATE * ON_MS / 1000
        val silent = RATE * OFF_MS / 1000
        val out = ByteArray((sounding + silent) * 2)
        for (i in 0 until sounding) {
            val fade = minOf(i, sounding - 1 - i, FADE) / FADE.toDouble()
            val v = (sin(2 * PI * HZ * i / RATE) * AMPLITUDE * fade * Short.MAX_VALUE).toInt()
            out[2 * i] = (v and 0xFF).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
