package vbh.ai.whatsapp

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/**
 * Decodes one incoming H.264 stream (the peer's, or one group call participant's) into a
 * surface. A decoder can only start on a keyframe, so the frames since the last one are kept:
 * a surface that turns up mid-stream (a new tile) starts from them instead of waiting for the
 * next keyframe.
 */
class RemoteDecoder(
    val name: String,
    /** Fired (from the codec thread) the first time a frame is drawn after a (re)start. */
    private val onFirstShown: () -> Unit = {},
    /** The decoded frame size, when the decoder learns it. */
    private val onSize: (Int, Int) -> Unit = { _, _ -> },
) {
    private val lock = Object()
    private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private var decodeThread: Thread? = null

    @Volatile private var decoding = false
    private var pts = 0L
    private var shownSinceStart = 0

    /** The last keyframe and the frames after it, for a surface that turns up mid-stream. */
    private val sinceKeyframe = ArrayList<ByteArray>()

    // Counters for the log: what went in and out, so a bad call can be diagnosed.
    var framesIn = 0
        private set
    var framesDropped = 0
        private set
    var framesShown = 0
        private set

    /** When the last frame arrived (elapsed ms), 0 before any. */
    @Volatile var lastFrameAt = 0L
        private set

    /** Where the video is drawn; null when its view goes away. A new surface replays from the last keyframe. */
    fun setSurface(s: Surface?) {
        synchronized(lock) {
            releaseLocked()
            surface = s
            if (s != null) sinceKeyframe.toList().forEach { decodeLocked(it, s) }
        }
    }

    /**
     * Takes one access unit. Returns false if it couldn't be drawn because there's no surface
     * (it's still kept for one that turns up).
     */
    fun frame(accessUnit: ByteArray): Boolean {
        synchronized(lock) {
            framesIn++
            lastFrameAt = android.os.SystemClock.elapsedRealtime()
            val types = VideoEngine.nalTypes(accessUnit)
            if (VideoEngine.IDR in types || VideoEngine.SPS in types) {
                sinceKeyframe.clear()
                sinceKeyframe += accessUnit
            } else if (sinceKeyframe.isNotEmpty() && sinceKeyframe.size < KEPT_FRAMES) {
                sinceKeyframe += accessUnit
            } else {
                sinceKeyframe.clear()
            }
            val s = surface
            if (s == null) {
                framesDropped++
                return false
            }
            decodeLocked(accessUnit, s)
            if (framesIn % 150 == 0) WaState.log("video in ($name): received $framesIn, dropped $framesDropped, shown $framesShown")
            return true
        }
    }

    private fun decodeLocked(
        accessUnit: ByteArray,
        s: Surface,
    ) {
        if (decoder == null) {
            val types = VideoEngine.nalTypes(accessUnit)
            if (VideoEngine.SPS !in types && VideoEngine.IDR !in types) return
            startLocked(s)
        }
        val codec = decoder ?: return
        try {
            val idx = codec.dequeueInputBuffer(20_000)
            if (idx < 0) {
                framesDropped++
                return
            } // decoder busy: drop rather than build latency
            codec.getInputBuffer(idx)?.apply {
                clear()
                put(accessUnit)
            }
            codec.queueInputBuffer(idx, 0, accessUnit.size, pts, 0)
            pts += 1_000_000L / VideoEngine.FPS
        } catch (e: Exception) {
            WaState.log("video ($name): decode error ${e.message}")
            releaseLocked() // restart on the next keyframe
        }
    }

    private fun startLocked(s: Surface) {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, VideoEngine.WIDTH, VideoEngine.HEIGHT), s, null, 0)
        codec.start()
        decoder = codec
        decoding = true
        pts = 0
        shownSinceStart = 0
        decodeThread =
            Thread({
                val info = MediaCodec.BufferInfo()
                while (decoding) {
                    val idx =
                        try {
                            codec.dequeueOutputBuffer(info, 10_000)
                        } catch (_: Exception) {
                            break
                        }
                    when {
                        idx >= 0 -> {
                            runCatching {
                                codec.releaseOutputBuffer(idx, true)
                                framesShown++
                                if (shownSinceStart++ == 0) {
                                    WaState.log("video in ($name): first frame shown")
                                    onFirstShown()
                                }
                            }
                        }

                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            runCatching {
                                val f = codec.outputFormat
                                onSize(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT))
                            }
                        }
                    }
                }
            }, "wa-decode").also { it.start() }
        WaState.log("video ($name): decoder started")
    }

    private fun releaseLocked() {
        decoding = false
        decodeThread?.join(300)
        decodeThread = null
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        decoder = null
    }

    /** Stops decoding and forgets the stream; logs what went through it. */
    fun release() =
        synchronized(lock) {
            releaseLocked()
            sinceKeyframe.clear()
            if (framesIn > 0) WaState.log("video in ($name): total received $framesIn, dropped $framesDropped, shown $framesShown")
            framesIn = 0
            framesDropped = 0
            framesShown = 0
            lastFrameAt = 0L
        }

    private companion object {
        /** About 6 s at 15 fps; past that, wait for the next keyframe instead. */
        const val KEPT_FRAMES = 90
    }
}
