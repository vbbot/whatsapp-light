package vbh.ai.whatsapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.update
import vbh.ai.whatsapp.walib.VideoRenderer

/**
 * Video for calls. meowcaller only carries H.264, so this does the rest:
 * - send: front camera -> ImageReader -> hardware H.264 encoder (constrained baseline,
 *   ~640x480, 15 fps, SPS/PPS before every keyframe) -> [send] as Annex-B access units;
 * - receive: Annex-B access units from Go ([frame], or [participantFrame] per group call
 *   participant) -> hardware decoder ([RemoteDecoder]) -> that stream's surface.
 * Matches what meowcaller's reference browser client sends.
 */
class VideoEngine(
    private val context: Context,
) : VideoRenderer {
    /** Fired (from a codec thread) the first time a frame of theirs is drawn after a (re)start. */
    @Volatile var onRemoteFrameShown: (() -> Unit)? = null

    /** Fired when their video arrives but no video view exists yet (e.g. still on the audio screen). */
    @Volatile var onRemoteVideoWithoutView: (() -> Unit)? = null

    // ------------------------------------------------------------------ send side
    //
    // Camera frames reach the encoder through an ImageReader (CPU copy), not an encoder input
    // surface. Measured on the LP3: the camera driver holds ~32 frames (2.2 s) on a stream it
    // knows feeds a video encoder, while preview-type streams (ImageReader, TextureView) are
    // live. Copying 640x480 YUV at 15 fps is cheap, and acquireLatestImage() drops stale frames.

    /** Where encoded frames go (set per call), with each frame's duration in microseconds. */
    @Volatile private var send: ((ByteArray, Long) -> Unit)? = null
    private var lastPtsUs = -1L

    private var cameraThread: HandlerThread? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var encoder: MediaCodec? = null
    private var drainThread: Thread? = null

    @Volatile private var encoding = false

    @Volatile private var codecConfig: ByteArray? = null

    @Volatile private var loggedKeyframe = false

    @Volatile private var loggedNoInputImage = false

    // Stats for the log.
    private var framesCaptured = 0L
    private var framesSkipped = 0L
    private var latencySumUs = 0L
    private var latencyMaxUs = 0L
    private var latencyCount = 0

    /** Size actually captured and encoded; landscape, as the sensor delivers it. */
    var captureSize = Size(WIDTH, HEIGHT)
        private set

    /** Which camera the next [startCamera] opens. Front by default, as in WhatsApp. */
    @Volatile var useFrontCamera = true

    /** Quarter turns clockwise to draw our raw frames upright (current camera, portrait). */
    var sensorQuarterTurns = 3
        private set

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startCamera(
        preview: Surface?,
        send: (ByteArray, Long) -> Unit,
    ) {
        stopCamera()
        this.send = send
        lastPtsUs = -1L
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wanted = if (useFrontCamera) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id =
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
            } ?: cm.cameraIdList.firstOrNull() ?: run {
                WaState.log("video: no camera")
                return
            }
        val chars = cm.getCameraCharacteristics(id)
        sensorQuarterTurns = ((chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270) / 90) % 4
        captureSize = chooseSize(chars)
        val fps = chooseFps(chars)

        try {
            startEncoder(captureSize)
        } catch (e: Exception) {
            WaState.log("video: encoder failed: ${e.message}")
            WaState.error.value = "Camera video isn't available on this phone"
            return
        }
        val thread = HandlerThread("wa-camera").also { it.start() }
        cameraThread = thread
        val handler = Handler(thread.looper)

        val r = ImageReader.newInstance(captureSize.width, captureSize.height, ImageFormat.YUV_420_888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = runCatching { rd.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                feed(img)
            } finally {
                img.close()
            }
        }, handler)
        reader = r

        cm.openCamera(
            id,
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    val targets = listOfNotNull(r.surface, preview)
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(
                        targets,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                val req =
                                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        targets.forEach { addTarget(it) }
                                        fps?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                                        set(
                                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
                                        )
                                    }
                                runCatching { s.setRepeatingRequest(req.build(), null, handler) }
                                    .onFailure { WaState.log("video: capture failed: ${it.message}") }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                WaState.log("video: camera session failed")
                            }
                        },
                        handler,
                    )
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                }

                override fun onError(
                    device: CameraDevice,
                    error: Int,
                ) {
                    WaState.log("video: camera error $error")
                    device.close()
                }
            },
            handler,
        )
        WaState.log(
            "video: ${if (useFrontCamera) "front" else "rear"} camera ${captureSize.width}x${captureSize.height} @ $fps, rotate ${sensorQuarterTurns * 90}",
        )
    }

    /** Copies one camera frame into the encoder; skipped if the encoder has no free input. */
    private fun feed(img: Image) {
        val codec = encoder ?: return
        if (!encoding) return
        framesCaptured++
        val idx =
            try {
                codec.dequeueInputBuffer(0)
            } catch (_: Exception) {
                return
            }
        if (idx < 0) {
            framesSkipped++
            return
        }
        val dst =
            try {
                codec.getInputImage(idx)
            } catch (_: Exception) {
                null
            }
        if (dst == null) {
            if (!loggedNoInputImage) {
                loggedNoInputImage = true
                WaState.log("video: encoder gives no input image")
            }
            codec.queueInputBuffer(idx, 0, 0, 0, 0)
            return
        }
        val w = img.width
        val h = img.height
        copyPlane(img.planes[0], dst.planes[0], w, h)
        copyPlane(img.planes[1], dst.planes[1], w / 2, h / 2)
        copyPlane(img.planes[2], dst.planes[2], w / 2, h / 2)
        // Camera timestamps are on the boot clock (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME here).
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, img.timestamp / 1000, 0)
    }

    /** Copies a w x h plane between two images whose row/pixel strides may differ. */
    private fun copyPlane(
        src: Image.Plane,
        dst: Image.Plane,
        w: Int,
        h: Int,
    ) {
        val s = src.buffer
        val d = dst.buffer
        val sRow = src.rowStride
        val dRow = dst.rowStride
        val sPix = src.pixelStride
        val dPix = dst.pixelStride
        if (sPix == 1 && dPix == 1) {
            val row = ByteArray(w)
            for (y in 0 until h) {
                s.position(y * sRow)
                s.get(row, 0, w)
                d.position(y * dRow)
                d.put(row, 0, w)
            }
        } else {
            for (y in 0 until h) {
                val sBase = y * sRow
                val dBase = y * dRow
                for (x in 0 until w) d.put(dBase + x * dPix, s.get(sBase + x * sPix))
            }
        }
    }

    @Synchronized
    fun stopCamera() {
        encoding = false
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
        cameraThread?.quitSafely()
        cameraThread?.join(300)
        cameraThread = null
        runCatching { reader?.close() }
        reader = null
        drainThread?.join(300)
        drainThread = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        codecConfig = null
        loggedKeyframe = false
        loggedNoInputImage = false
        framesCaptured = 0
        framesSkipped = 0
        latencySumUs = 0
        latencyMaxUs = 0
        latencyCount = 0
        send = null
    }

    /** Next frame becomes an IDR (the peer lost sync, or just joined). */
    fun requestKeyframe() {
        runCatching {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    private fun startEncoder(size: Size) {
        // Optional keys, most expendable first: if the encoder rejects a configuration, the
        // first remaining option is dropped and we try again. Base keys are always set.
        val optional = mutableListOf("lowlatency", "prepend", "profile")

        fun format(opts: List<String>) =
            MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                // Keyframes every 2 s; the peer also asks for one (PLI) whenever it needs it.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                if ("lowlatency" in opts) {
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                    setInteger("vendor.qti-ext-enc-low-latency.enable", 1)
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
                }
                if ("prepend" in opts) setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                if ("profile" in opts) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                }
            }
        val codec = MediaCodec.createEncoderByType(MIME)
        while (true) {
            try {
                codec.configure(format(optional), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                WaState.log(
                    "video: encoder ${codec.name} ${BITRATE / 1000} kbps, options $optional",
                )
                break
            } catch (e: Exception) {
                codec.reset()
                if (optional.isEmpty()) {
                    codec.release()
                    throw IllegalStateException("no H.264 encoder configuration accepted")
                }
                optional.removeAt(0)
            }
        }
        codec.start()
        encoder = codec
        encoding = true
        drainThread = Thread({ drain(codec) }, "wa-encode").also { it.start() }
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (encoding) {
            val idx =
                try {
                    codec.dequeueOutputBuffer(info, 10_000)
                } catch (_: Exception) {
                    break
                }
            if (idx < 0) continue
            val buf = codec.getOutputBuffer(idx)
            val bytes = ByteArray(info.size)
            buf?.position(info.offset)
            buf?.get(bytes)
            codec.releaseOutputBuffer(idx, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                codecConfig = bytes
                continue
            }
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            // Every IDR must carry SPS/PPS; add them if this encoder didn't prepend them.
            val cfg = codecConfig
            val au = if (key && cfg != null && SPS !in nalTypes(bytes)) cfg + bytes else bytes
            if (key && !loggedKeyframe) {
                loggedKeyframe = true
                WaState.log("video: first keyframe ${au.size} bytes, NAL types ${nalTypes(au).sorted()}")
                WaState.log("video: keyframe head hex ${au.take(64).joinToString("") { "%02x".format(it) }}")
            }
            val pts = info.presentationTimeUs
            // Sensor-to-encoded latency (pts is the camera's boot-clock timestamp).
            val latency = android.os.SystemClock.elapsedRealtimeNanos() / 1000 - pts
            if (latency in 0..5_000_000) {
                latencySumUs += latency
                latencyCount++
                if (latency > latencyMaxUs) latencyMaxUs = latency
                if (latencyCount == 75) {
                    WaState.log(
                        "video out: camera-to-send latency avg ${latencySumUs / latencyCount / 1000} ms, max ${latencyMaxUs / 1000} ms; frames captured $framesCaptured, skipped $framesSkipped",
                    )
                    latencySumUs = 0
                    latencyMaxUs = 0
                    latencyCount = 0
                }
            }
            // Duration from the camera's own timestamps: a fixed 66 ms per frame drifts from the
            // real frame rate and the receiver's buffer (and delay) grows over the call.
            val duration =
                if (lastPtsUs < 0 || pts <= lastPtsUs) {
                    1_000_000L / FPS
                } else {
                    (pts - lastPtsUs).coerceAtMost(500_000L)
                }
            lastPtsUs = pts
            runCatching { send?.invoke(au, duration) }
        }
    }

    private fun chooseSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Size(WIDTH, HEIGHT)
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        if (sizes.any { it.width == WIDTH && it.height == HEIGHT }) return Size(WIDTH, HEIGHT)
        // Otherwise the closest 4:3 size no bigger than 720p.
        return sizes.filter { it.width <= 1280 && it.width * 3 == it.height * 4 }.minByOrNull { kotlin.math.abs(it.width - WIDTH) }
            ?: Size(WIDTH, HEIGHT)
    }

    private fun chooseFps(chars: CameraCharacteristics): Range<Int>? {
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return null
        return ranges.firstOrNull { it.lower == FPS && it.upper == FPS }
            ?: ranges.filter { it.lower <= FPS && it.upper >= FPS }.minByOrNull { it.upper }
    }

    // ------------------------------------------------------------------ receive side

    /** The peer's video in a 1:1 call. */
    private val peer =
        RemoteDecoder(
            "peer",
            onFirstShown = { onRemoteFrameShown?.invoke() },
            onSize = { w, h -> WaState.remoteVideoSize.value = w to h },
        )

    /** Each participant's video in a group call, by participant JID. */
    private val participants = java.util.concurrent.ConcurrentHashMap<String, RemoteDecoder>()

    /** The surface the peer's video is drawn into; null when the video view goes away. */
    fun setRemoteSurface(surface: Surface?) = peer.setSurface(surface)

    /** The surface [participant]'s video is drawn into; null when their tile goes away. */
    fun setParticipantSurface(
        participant: String,
        surface: Surface?,
    ) {
        participants[participant]?.setSurface(surface)
    }

    /** When [participant]'s last frame arrived (elapsed ms), 0 if none yet. */
    fun lastFrameAt(participant: String): Long = participants[participant]?.lastFrameAt ?: 0L

    fun releaseDecoder() {
        peer.release()
        participants.values.forEach { it.release() }
        participants.clear()
        WaState.groupVideo.value = emptyMap()
    }

    /** Called from Go with one access unit of the peer's video. */
    override fun frame(accessUnit: ByteArray) {
        if (!peer.frame(accessUnit)) {
            val types = nalTypes(accessUnit)
            if (IDR in types || SPS in types) onRemoteVideoWithoutView?.invoke()
        }
    }

    /** Called from Go with one access unit of a group call participant's video. */
    override fun participantFrame(
        participant: String,
        orientation: Long,
        accessUnit: ByteArray,
    ) {
        val d =
            participants.getOrPut(participant) {
                RemoteDecoder(
                    "participant ${participants.size + 1}",
                    onFirstShown = { onRemoteFrameShown?.invoke() },
                    onSize = { w, h -> updateTile(participant) { it.copy(width = w, height = h) } },
                )
            }
        // Their packets may carry a counter-clockwise turn, as in 1:1 calls; usually in a group
        // call they don't (-1), and the turn comes from their video state ([participantState]).
        val o = orientation.toInt()
        if (o >= 0) {
            val turns = (4 - o) % 4
            if (WaState.groupVideo.value[participant]?.quarterTurns != turns) updateTile(participant) { it.copy(quarterTurns = turns) }
        }
        if (participant !in WaState.groupVideo.value) updateTile(participant) { it }
        d.frame(accessUnit)
    }

    /**
     * A group call participant's camera state: on or off, and the clockwise quarter turns
     * ([orientation]) that show their frames upright (sent this way, unlike the packets' turn).
     */
    fun participantState(
        participant: String,
        active: Boolean,
        orientation: Int,
    ) = updateTile(participant) { it.copy(quarterTurns = orientation.mod(4), cameraOn = active) }

    private fun updateTile(
        participant: String,
        change: (VideoTile) -> VideoTile,
    ) = WaState.groupVideo.update { it + (participant to change(it[participant] ?: VideoTile())) }

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val WIDTH = 640
        const val HEIGHT = 480
        const val FPS = 15

        // ~800 kbps, variable: measured on a real call the path showed ~0% loss and ~10 ms
        // jitter, and 300 kbps constant-rate produced 2 KB keyframes (very blocky picture).
        private const val BITRATE = 800_000
        const val IDR = 5
        const val SPS = 7

        /** NAL unit types present in an Annex-B access unit. */
        fun nalTypes(au: ByteArray): Set<Int> {
            val out = mutableSetOf<Int>()
            var i = 0
            while (i + 3 < au.size) {
                if (au[i].toInt() == 0 && au[i + 1].toInt() == 0) {
                    val start =
                        when {
                            au[i + 2].toInt() == 1 -> i + 3
                            au[i + 2].toInt() == 0 && au[i + 3].toInt() == 1 -> i + 4
                            else -> -1
                        }
                    if (start in au.indices) {
                        out += au[start].toInt() and 0x1F
                        i = start
                        continue
                    }
                }
                i++
            }
            return out
        }
    }
}
