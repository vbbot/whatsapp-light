package vbh.ai.whatsapp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import vbh.ai.whatsapp.walib.Client
import vbh.ai.whatsapp.walib.Speaker

/**
 * Bridges Android audio to walib: 16 kHz mono s16le in both directions.
 * Mic -> [Client.pushMic]; peer audio arrives via [Speaker.write] -> AudioTrack.
 */
class AudioEngine(context: Context) : Speaker {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var focus: AudioFocusRequest? = null
    @Volatile private var running = false
    @Volatile var muted = false

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(client: Client) {
        if (running) return
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            ).build().also { am.requestAudioFocus(it) }

        val fmtIn = AudioFormat.ENCODING_PCM_16BIT
        val minRec = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, fmtIn)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
            AudioFormat.CHANNEL_IN_MONO, fmtIn, maxOf(minRec * 2, CHUNK * 4)
        )
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true

        val minTrk = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, fmtIn)
        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(RATE).setEncoding(fmtIn)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(maxOf(minTrk * 4, CHUNK * 16))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        record = rec; track = trk; running = true
        trk.play(); rec.startRecording()
        thread = Thread({
            val buf = ByteArray(CHUNK)
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0 && !muted) client.pushMic(buf.copyOf(n))
            }
        }, "wa-mic").also { it.start() }
        WaState.log("audio started")
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        thread?.join(500); thread = null
        record?.runCatching { stop(); release() }; record = null
        track?.runCatching { stop(); release() }; track = null
        focus?.let { am.abandonAudioFocusRequest(it) }; focus = null
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
        WaState.log("audio stopped")
    }

    fun setSpeakerphone(on: Boolean) {
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = on
    }

    /** Called from Go goroutines with peer audio. */
    override fun write(pcm: ByteArray) {
        track?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    companion object {
        const val RATE = 16000
        const val CHUNK = 640 // 20 ms of 16 kHz mono s16le
    }
}
