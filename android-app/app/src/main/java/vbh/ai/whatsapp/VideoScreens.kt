package vbh.ai.whatsapp

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay

/**
 * A video call. Chrome stays outside the media: top bar (who / how long) above, controls in
 * the bottom bar below, nothing that takes touches laid over the video. Video is shown as-is
 * (media is the one place colour is allowed); our own preview sits inset in a corner.
 */
@Composable
fun VideoCallScreen(
    call: CallUi,
    title: String,
) {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.connectedAt) {
        while (call.connectedAt != 0L) {
            now.longValue = System.currentTimeMillis()
            delay(1000)
        }
    }
    val status = if (call.connectedAt != 0L && call.phase == 4) formatDuration(now.longValue - call.connectedAt) else call.phaseLabel
    val size by WaState.remoteVideoSize.collectAsState()

    Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
        LightTopBar(center = LightTopBarCenter.TwoLineDetail(title, status))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            if (call.groupCall) {
                GroupVideoGrid(call, maxWidth, maxHeight)
            } else {
                PeerVideo(call, size, maxWidth, maxHeight)
            }
            if (call.localVideo) {
                // Our preview: a small portrait inset. Camera2 already turns preview frames upright
                // and mirrors the front camera for a TextureView, so no transform here (the
                // encoder gets raw sensor frames instead; the peer rotates those by what we send).
                val pw = 7f.gridUnitsAsDp()
                val ph = pw * 4 / 3
                Box(Modifier.align(Alignment.BottomEnd).padding(1f.gridUnitsAsDp()).size(pw, ph)) {
                    RotatedVideo(
                        frameW = VideoEngine.HEIGHT,
                        frameH = VideoEngine.WIDTH,
                        quarterTurns = 0,
                        mirror = false,
                        boxW = pw,
                        boxH = ph,
                        modifier = Modifier.align(Alignment.Center),
                        onSurface = { s -> if (s != null) WaCore.startCamera(s) else WaCore.stopCamera() },
                        bufferSize = VideoEngine.WIDTH to VideoEngine.HEIGHT,
                    )
                }
            }
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items =
                listOf(
                    if (call.muted) {
                        bar(LightIcons.MUTE, "Unmute microphone") { WaCore.setMuted(false) }
                    } else {
                        bar(LightIcons.MICROPHONE, "Mute microphone") { WaCore.setMuted(true) }
                    },
                    if (call.speaker) {
                        bar(LightIcons.SPEAKER_ON, "Switch to earpiece") { WaCore.setSpeaker(false) }
                    } else {
                        bar(LightIcons.SPEAKER_MUTED, "Switch to speaker") { WaCore.setSpeaker(true) }
                    },
                    if (call.localVideo) {
                        bar(LightIcons.CAMERA, "Turn camera off") { WaCore.stopVideo() }
                    } else {
                        bar(LightIcons.CAMERA, "Turn camera on") { CameraGate.withCamera { ok -> if (ok) WaCore.startVideo() } }
                    },
                    // Front / rear switch, only while our camera is on (5 icons is the bar's limit).
                    if (call.localVideo) {
                        bar(
                            LightIcons.ROTATE,
                            if (call.frontCamera) "Switch to rear camera" else "Switch to front camera",
                        ) { WaCore.switchCamera() }
                    } else {
                        null
                    },
                    bar(LightIcons.DENY, "End call") { WaCore.hangup() },
                ).filterNotNull(),
        )
    }
}

/** The peer's video in a 1:1 call, or "camera off" over it. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PeerVideo(
    call: CallUi,
    size: Pair<Int, Int>?,
    boxW: Dp,
    boxH: Dp,
) {
    // The remote view (and its decoder) stays alive for the whole call, so their video
    // resumes instantly after they turn their camera off and on again.
    val (w, h) = size ?: (VideoEngine.WIDTH to VideoEngine.HEIGHT)
    RotatedVideo(
        // Their packets carry a counter-clockwise turn (WhatsApp's convention, tested);
        // we draw with clockwise turns.
        frameW = w,
        frameH = h,
        quarterTurns = (4 - call.remoteOrientation) % 4,
        mirror = false,
        boxW = boxW,
        boxH = boxH,
        modifier = Modifier.align(Alignment.Center),
        onSurface = { WaCore.video.setRemoteSurface(it) },
    )
    if (!call.remoteVideo) {
        // Covers the last frame; draws only, takes no touches.
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            StatusText(if (call.phase < 4) call.phaseLabel else "camera off")
        }
    }
}

/**
 * A group call's video: everyone whose camera is on, up to four, in a grid (one fills the
 * screen, two are stacked, three or four share a 2×2 grid), each with their name.
 */
@Composable
private fun GroupVideoGrid(
    call: CallUi,
    boxW: Dp,
    boxH: Dp,
) {
    val tiles by WaState.groupVideo.collectAsState()
    // Cameras turn off without a signal here; their frames just stop, so look again each second.
    val now = remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now.longValue = android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val shown = videoTiles(tiles.keys.associateWith { WaCore.video.lastFrameAt(it) }, call.members, now.longValue)
    if (shown.isEmpty()) {
        StatusText(if (call.phase < 4) call.phaseLabel else "no video")
        return
    }
    val columns = if (shown.size > 2) 2 else 1
    val rows = (shown.size + columns - 1) / columns
    val cellW = boxW / columns
    val cellH = boxH / rows
    Column(Modifier.fillMaxSize()) {
        shown.chunked(columns).forEach { row ->
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().weight(1f)) {
                row.forEach { p ->
                    key(p) {
                        Box(Modifier.weight(1f).fillMaxSize()) {
                            val t = tiles[p] ?: VideoTile()
                            RotatedVideo(
                                frameW = t.width,
                                frameH = t.height,
                                quarterTurns = (4 - t.orientation) % 4,
                                mirror = false,
                                boxW = cellW,
                                boxH = cellH,
                                modifier = Modifier.align(Alignment.Center),
                                onSurface = { WaCore.video.setParticipantSurface(p, it) },
                            )
                            LengthTag(rememberTitle(p), Modifier.align(Alignment.BottomStart).padding(1.dp))
                        }
                    }
                }
                // An odd last row keeps its tile the same size as the others.
                if (row.size < columns) Box(Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

private fun bar(
    icon: com.thelightphone.sdk.ui.LightIconConfiguration,
    description: String,
    onClick: () -> Unit,
) = LightBarButton.LightIcon(icon = icon, onClick = onClick, contentDescription = description)

/**
 * A TextureView showing landscape frames rotated upright and scaled to fit [boxW] x [boxH].
 * The view is laid out in the frame's own orientation and turned with a graphics layer, so
 * its layout box is swapped for odd quarter turns.
 */
@Composable
private fun RotatedVideo(
    frameW: Int,
    frameH: Int,
    quarterTurns: Int,
    mirror: Boolean,
    boxW: Dp,
    boxH: Dp,
    modifier: Modifier = Modifier,
    onSurface: (Surface?) -> Unit,
    bufferSize: Pair<Int, Int>? = null,
) {
    val turned = quarterTurns % 2 == 1
    // Upright aspect ratio as displayed.
    val dispW = if (turned) frameH else frameW
    val dispH = if (turned) frameW else frameH
    val scale = minOf(boxW.value / dispW, boxH.value / dispH)
    val viewW = Dp(frameW * scale)
    val viewH = Dp(frameH * scale)
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener =
                    object : TextureView.SurfaceTextureListener {
                        var surface: Surface? = null

                        override fun onSurfaceTextureAvailable(
                            st: SurfaceTexture,
                            w: Int,
                            h: Int,
                        ) {
                            bufferSize?.let { (bw, bh) -> st.setDefaultBufferSize(bw, bh) }
                            surface = Surface(st).also(onSurface)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture,
                            w: Int,
                            h: Int,
                        ) {}

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            onSurface(null)
                            surface?.release()
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
            }
        },
        modifier =
            modifier
                .requiredSize(viewW, viewH)
                .graphicsLayer {
                    rotationZ = quarterTurns * 90f
                    if (mirror) scaleX = -1f
                },
    )
}
