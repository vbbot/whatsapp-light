package vbh.ai.whatsapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTouchableProgressBar
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import vbh.ai.whatsapp.walib.Walib
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val videoSeconds = ConcurrentHashMap<String, Int>()

/**
 * How long [m]'s video is, in seconds: from the message's download info, or else from the local
 * copy (our own sent videos have no download info). 0 until known.
 */
@Composable
internal fun rememberVideoSeconds(m: Msg): Int {
    val state =
        produceState(videoSeconds[m.id] ?: 0, m.id, m.mediaRef) {
            if (value > 0) return@produceState
            value =
                withContext(Dispatchers.IO) {
                    val fromRef = if (m.mediaRef.isEmpty()) 0 else runCatching { Walib.videoSeconds(m.mediaRef).toInt() }.getOrDefault(0)
                    val s = if (fromRef > 0) fromRef else fileSeconds(Media.videoFile(m.id))
                    s.also { if (it > 0) videoSeconds[m.id] = it }
                }
        }
    return state.value
}

private fun fileSeconds(f: File): Int {
    if (!f.exists()) return 0
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(f.path)
        val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        ((ms + 500) / 1000).toInt()
    } catch (_: Exception) {
        0
    } finally {
        mmr.release()
    }
}

/**
 * A message's video full screen; back returns to the chat. Plays the local copy, downloading it
 * first if needed ([Media.opened]); meanwhile it shows the preview and why it's waiting.
 * [previewPath] covers a video that's still sending (not stored yet).
 */
@Composable
fun VideoViewerScreen(
    id: String,
    previewPath: String,
    backTo: String,
) {
    val changes by MessageStore.changes.collectAsState()
    val msg = rememberQuery(id, changes) { MessageStore.get(id) }
    val connected by WaState.connected.collectAsState()
    val states by Media.states.collectAsState()
    LaunchedEffect(msg?.id, connected) { if (msg != null && connected) Media.opened(msg) }
    val state = states[id]
    // Re-checked as downloads finish (states change) and once the message is read.
    val file = remember(id, states, msg) { Media.videoFile(id).takeIf { it.exists() } }
    ScreenColumn {
        LightTopBar(leftButton = backButton(backTo) { Nav.pop() })
        if (file != null) VideoPlayer(file) else VideoWaiting(msg, previewPath, state, connected)
    }
}

/** Until the video is here: why it's waiting, over its preview. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.VideoWaiting(
    msg: Msg?,
    previewPath: String,
    state: Media.State?,
    connected: Boolean,
) {
    Banner(
        when {
            state is Media.State.Failed -> "Couldn't load the video: ${state.reason}"
            msg != null && msg.mediaRef.isEmpty() -> "This video can't be downloaded"
            !connected -> "The video loads when connected"
            else -> "Downloading video…"
        },
    )
    val preview = rememberBitmap(msg?.media?.ifEmpty { null } ?: previewPath, 2048)
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        preview?.let {
            Image(bitmap = it, contentDescription = "Video preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
    Box(Modifier.navigationBarsPadding())
}

/**
 * Plays [file] fitted to the screen, with play/pause and a seek bar under it. Sound goes to the
 * media stream, so the volume keys work. It pauses when the app goes to the background, when
 * another app (or a call) takes the audio, and when a call comes in. At the end it returns to the
 * start and stays paused.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.VideoPlayer(file: File) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val player = remember(file) { MediaPlayer() }
    var released by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var position by remember(file) { mutableIntStateOf(0) }
    var aspect by remember(file) { mutableFloatStateOf(0f) }
    var failed by remember(file) { mutableStateOf(false) }

    val focus =
        remember(file) {
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(MEDIA_ATTRIBUTES)
                .setOnAudioFocusChangeListener { change ->
                    if (change != AudioManager.AUDIOFOCUS_GAIN && !released && player.isPlaying) {
                        player.pause()
                        playing = false
                    }
                }.build()
        }

    fun pause() {
        if (released) return
        if (player.isPlaying) player.pause()
        playing = false
        audio.abandonAudioFocusRequest(focus)
    }

    fun play() {
        if (released || duration <= 0) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        player.start()
        playing = true
    }

    DisposableEffect(player) {
        player.setAudioAttributes(MEDIA_ATTRIBUTES)
        player.setOnPreparedListener {
            duration = it.duration
            // Shows the first frame.
            it.seekTo(0)
        }
        player.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) aspect = w.toFloat() / h }
        player.setOnCompletionListener {
            playing = false
            position = 0
            it.seekTo(0)
            audio.abandonAudioFocusRequest(focus)
        }
        player.setOnErrorListener { _, what, extra ->
            WaState.log("video player error $what/$extra")
            failed = true
            playing = false
            true
        }
        try {
            player.setDataSource(file.path)
            player.prepareAsync()
        } catch (e: Exception) {
            WaState.log("video player: ${e.message}")
            failed = true
        }
        onDispose {
            released = true
            audio.abandonAudioFocusRequest(focus)
            player.release()
        }
    }

    // The volume keys change the media volume while the player is up.
    DisposableEffect(Unit) {
        val activity = context.activity()
        val before = activity?.volumeControlStream
        activity?.volumeControlStream = AudioManager.STREAM_MUSIC
        onDispose { if (activity != null && before != null) activity.volumeControlStream = before }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val call by WaState.call.collectAsState()
    LaunchedEffect(call != null) { if (call != null) pause() }

    LaunchedEffect(playing) {
        while (playing && !released) {
            position = player.currentPosition
            delay(250)
        }
    }

    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (failed) {
            StatusText("Couldn't play this video")
        } else {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener =
                            object : TextureView.SurfaceTextureListener {
                                private var surface: Surface? = null

                                override fun onSurfaceTextureAvailable(
                                    st: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    surface = Surface(st).also { if (!released) player.setSurface(it) }
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    st: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                    if (!released) runCatching { player.setSurface(null) }
                                    surface?.release()
                                    surface = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                            }
                    }
                },
                // As large as fits at the video's shape; square until its size is known.
                modifier = Modifier.aspectRatio(if (aspect > 0f) aspect else 1f),
            )
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp())) {
        LightTouchableProgressBar(
            colors = LightThemeTokens.colors,
            progress = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { p ->
                if (!released && duration > 0) {
                    position = (p * duration).toInt()
                    player.seekTo(position)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth()) {
            LightText(text = formatClock(position.toLong()), variant = LightTextVariant.Superfine)
            Spacer(Modifier.weight(1f))
            LightText(text = if (duration > 0) formatClock(duration.toLong()) else "", variant = LightTextVariant.Superfine)
        }
    }
    LightBottomBar(
        modifier = Modifier.navigationBarsPadding(),
        items =
            listOf(
                null,
                if (playing) {
                    icon(LightIcons.PAUSE, "Pause") { pause() }
                } else {
                    icon(LightIcons.PLAY, "Play") { play() }
                },
                null,
            ),
    )
}

private val MEDIA_ATTRIBUTES: AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
        .build()

private fun Context.activity(): Activity? =
    generateSequence(this) {
        (it as? ContextWrapper)?.baseContext
    }.filterIsInstance<Activity>().firstOrNull()

/** A round mark over a video's or GIF's preview: [content] in white, on black with a white ring. */
@Composable
internal fun RoundBadge(content: @Composable () -> Unit) {
    val colors = LightThemeTokens.colors
    Box(
        Modifier
            .size(3.5f.gridUnitsAsDp())
            .background(colors.background, CircleShape)
            .border(1.dp, colors.content, CircleShape),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The play mark over a video's preview: tap to play it full screen. */
@Composable
internal fun PlayBadge() = RoundBadge { LightIcon(LightIcons.PLAY, size = 1.5f, contentDescription = "Play video") }
