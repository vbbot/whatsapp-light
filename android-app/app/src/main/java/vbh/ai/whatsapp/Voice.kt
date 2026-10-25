package vbh.ai.whatsapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import vbh.ai.whatsapp.walib.Walib
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What WhatsApp's voice messages are: Ogg Opus, which [VoiceRecorder] records. */
const val VOICE_MIMETYPE = "audio/ogg; codecs=opus"

/**
 * Plays voice messages in the thread, one at a time, on the media stream. Tapping the one
 * that's playing pauses it, and tapping it again resumes. A voice message that isn't here yet
 * is downloaded first ([Media.opened]) and starts when it arrives, if it's still the one asked for.
 */
object VoicePlayer {
    data class State(
        val id: String,
        val playing: Boolean,
    )

    /** The voice message playing or paused; null when none is. */
    val state = kotlinx.coroutines.flow.MutableStateFlow<State?>(null)

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    /** Waiting for its download, to play when it arrives. */
    @Volatile private var pending: String? = null

    private val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    /** Where the playing message is, in ms. */
    fun position(): Long = runCatching { player?.currentPosition?.toLong() }.getOrNull() ?: 0L

    /** Plays [m], or pauses or resumes it if it's the one playing. Main thread. */
    fun toggle(
        context: Context,
        m: Msg,
    ) {
        app = context.applicationContext
        val s = state.value
        val p = player
        if (s?.id == m.id && p != null) {
            if (s.playing) {
                p.pause()
            } else {
                if (!requestFocus()) return
                p.start()
            }
            state.value = s.copy(playing = !s.playing)
            return
        }
        stop()
        if (WaState.call.value != null) {
            WaState.error.value = "Voice messages can't play during a call."
            return
        }
        val f = Media.audioFile(m.id)
        when {
            f.exists() -> {
                play(m.id, f)
            }

            m.mediaRef.isEmpty() -> {
                Media.states.value += (m.id to Media.State.Failed("it can't be downloaded"))
            }

            else -> {
                pending = m.id
                Media.opened(m)
            }
        }
    }

    /** [id]'s file has arrived; plays it if it's still the one asked for. Any thread. */
    fun downloaded(id: String) {
        main.post {
            if (pending != id) return@post
            pending = null
            play(id, Media.audioFile(id))
        }
    }

    /** Stops whatever is playing. Main thread. */
    fun stop() {
        pending = null
        player?.let { runCatching { it.release() } }
        player = null
        abandonFocus()
        state.value = null
    }

    private fun play(
        id: String,
        f: File,
    ) {
        if (WaState.call.value != null || !requestFocus()) return
        try {
            val p = MediaPlayer()
            p.setAudioAttributes(attributes)
            p.setDataSource(f.path)
            p.setOnCompletionListener { if (player === it) stop() }
            p.setOnErrorListener { mp, what, _ ->
                if (player === mp) {
                    stop()
                    Media.states.value += (id to Media.State.Failed("couldn't play it ($what)"))
                }
                true
            }
            p.prepare()
            p.start()
            player = p
            state.value = State(id, playing = true)
        } catch (e: Exception) {
            WaState.log("voice message $id: ${e.message}")
            stop()
            Media.states.value += (id to Media.State.Failed("couldn't play it"))
        }
    }

    private fun requestFocus(): Boolean {
        val am = app?.getSystemService(AudioManager::class.java) ?: return true
        val req =
            focus ?: AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener({ change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        val s = state.value
                        if (s?.playing == true) {
                            runCatching { player?.pause() }
                            state.value = s.copy(playing = false)
                        }
                    }
                }, main)
                .build()
                .also { focus = it }
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val req = focus ?: return
        app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(req)
        focus = null
    }
}

private val voiceSeconds = ConcurrentHashMap<String, Int>()

/** A voice message's length in seconds: from its download info, else its file; 0 while unknown. */
@Composable
internal fun rememberVoiceSeconds(m: Msg): Int {
    val f = Media.audioFile(m.id)
    val state =
        produceState(voiceSeconds[m.id] ?: 0, m.id, m.mediaRef, f.exists()) {
            if (value > 0) return@produceState
            value =
                withContext(Dispatchers.IO) {
                    val fromRef = if (m.mediaRef.isEmpty()) 0 else runCatching { Walib.audioSeconds(m.mediaRef).toInt() }.getOrDefault(0)
                    val s = if (fromRef > 0) fromRef else fileSeconds(f)
                    s.also { if (it > 0) voiceSeconds[m.id] = it }
                }
        }
    return state.value
}

/** A voice message in the thread: play or pause, and its length, or where it's got to while playing. */
@Composable
internal fun VoiceMessage(m: Msg) {
    val context = LocalContext.current
    val playing by VoicePlayer.state.collectAsState()
    val states by Media.states.collectAsState()
    val seconds = rememberVoiceSeconds(m)
    val mine = playing?.takeIf { it.id == m.id }
    var position by remember(m.id) { mutableLongStateOf(0L) }
    LaunchedEffect(mine) {
        position = VoicePlayer.position()
        while (mine?.playing == true) {
            delay(250)
            position = VoicePlayer.position()
        }
    }
    val total = if (seconds > 0) " / " + formatClock(seconds * 1000L) else ""
    val label =
        when {
            states[m.id] is Media.State.Loading -> "Voice message · loading…"
            mine != null -> formatClock(position) + total
            else -> voiceLabel(seconds)
        }
    Column(Modifier.padding(top = 1.dp)) {
        Row(
            Modifier.lightClickable(
                onClickLabel = if (mine?.playing == true) "Pause voice message" else "Play voice message",
                onClick = { VoicePlayer.toggle(context, m) },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LightIcon(
                if (mine?.playing == true) LightIcons.PAUSE else LightIcons.PLAY,
                size = 0.9f,
                contentDescription = if (mine?.playing == true) "Pause" else "Play",
            )
            Box(Modifier.width(0.5f.gridUnitsAsDp()))
            LightText(text = label, variant = LightTextVariant.Paragraph)
        }
        (states[m.id] as? Media.State.Failed)?.let {
            LightText(text = "Couldn't play: ${it.reason}", variant = LightTextVariant.Superfine, modifier = Modifier.padding(top = 1.dp))
        }
    }
}

/**
 * Records one voice message to [file], as WhatsApp sends them: Ogg Opus, mono, from the
 * microphone. [stop] returns how long it ran, in ms.
 */
private class VoiceRecorder(
    context: Context,
    private val file: File,
) {
    private val recorder = MediaRecorder(context)
    private var startedAt = 0L

    fun start() {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        recorder.setAudioChannels(1)
        recorder.setAudioSamplingRate(48_000)
        recorder.setAudioEncodingBitRate(32_000)
        recorder.setMaxDuration(MAX_VOICE_MS.toInt())
        recorder.setOutputFile(file.path)
        recorder.prepare()
        recorder.start()
        startedAt = SystemClock.elapsedRealtime()
    }

    fun elapsed(): Long = SystemClock.elapsedRealtime() - startedAt

    /** Stops; the length recorded in ms, or 0 if nothing usable was (stopped at once, or failed). */
    fun stop(): Long {
        val ms = elapsed()
        val ok = runCatching { recorder.stop() }.isSuccess
        recorder.release()
        return if (ok) ms else 0L
    }

    fun release() = runCatching { recorder.release() }
}

/** A voice message stops recording at this length. */
private const val MAX_VOICE_MS = 15 * 60_000L

/** Shorter than this isn't sent: it's a slip, not a message. */
private const val MIN_VOICE_MS = 1_000L

private sealed interface Take {
    data object Denied : Take

    data class Recording(
        val recorder: VoiceRecorder,
    ) : Take

    data class Recorded(
        val ms: Long,
    ) : Take

    data class Failed(
        val reason: String,
    ) : Take
}

/**
 * Records a voice message for [chat]. Recording starts at once; the centre button stops it,
 * then plays it back. SEND sends it (stopping first if it's still recording), RETRY records it
 * again, and the cross or going back throws it away.
 */
@Composable
fun VoiceRecordScreen(chat: String) {
    val context = LocalContext.current
    val title = rememberTitle(chat)
    val draft = remember { File(context.cacheDir, "voice-draft.ogg") }
    var take by remember { mutableStateOf<Take?>(null) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var preview by remember { mutableStateOf<MediaPlayer?>(null) }
    var previewPlaying by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }

    fun stopPreview() {
        preview?.let { runCatching { it.release() } }
        preview = null
        previewPlaying = false
    }

    fun record() {
        stopPreview()
        VoicePlayer.stop()
        if (WaState.call.value != null) {
            take = Take.Failed("You can't record during a call.")
            return
        }
        MicGate.withMic { ok ->
            if (!ok) {
                take = Take.Denied
                return@withMic
            }
            draft.delete()
            val r = VoiceRecorder(context, draft)
            take =
                try {
                    r.start()
                    elapsed = 0L
                    Take.Recording(r)
                } catch (e: Exception) {
                    r.release()
                    WaState.log("voice record: ${e.message}")
                    Take.Failed("The microphone couldn't start.")
                }
        }
    }

    fun stopRecording() {
        val t = take as? Take.Recording ?: return
        val ms = t.recorder.stop()
        take = if (ms > 0) Take.Recorded(ms) else Take.Failed("Nothing was recorded. Try again.")
    }

    fun discard() {
        (take as? Take.Recording)?.recorder?.release()
        take = null
        stopPreview()
        draft.delete()
        Nav.pop()
    }

    fun send() {
        stopRecording()
        stopPreview()
        val t = take as? Take.Recorded ?: return
        if (t.ms < MIN_VOICE_MS) {
            take = Take.Failed("Too short to send. Try again.")
            return
        }
        sent = true
        WaCore.sendVoice(chat, draft)
        Nav.pop()
    }

    fun togglePreview() {
        val p = preview
        if (p != null) {
            if (previewPlaying) p.pause() else p.start()
            previewPlaying = !previewPlaying
            return
        }
        runCatching {
            MediaPlayer().apply {
                setDataSource(draft.path)
                setOnCompletionListener { previewPlaying = false }
                prepare()
                start()
            }
        }.onSuccess {
            preview = it
            previewPlaying = true
        }
    }

    LaunchedEffect(Unit) { record() }
    // Ticks the timer, and stops at the longest a voice message may be.
    LaunchedEffect(take) {
        val t = take as? Take.Recording ?: return@LaunchedEffect
        while (true) {
            elapsed = t.recorder.elapsed()
            if (elapsed >= MAX_VOICE_MS) {
                stopRecording()
                break
            }
            delay(200)
        }
    }
    // Recording keeps the screen on; leaving throws an unsent take away.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            (take as? Take.Recording)?.recorder?.release()
            preview?.let { runCatching { it.release() } }
            if (!sent) draft.delete()
        }
    }

    val t = take
    ScreenColumn {
        LightTopBar(
            leftButton = backButton(title) { discard() },
            center = LightTopBarCenter.Text("Voice message"),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                when (t) {
                    is Take.Recording -> {
                        Box(Modifier.lightClickable(onClickLabel = "Stop recording", onClick = { stopRecording() })) {
                            LightIcon(LightIcons.STOP, size = 4f, contentDescription = "Stop recording")
                        }
                        LightText(
                            text = formatClock(elapsed),
                            variant = LightTextVariant.Subheading,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                        LightText(text = "recording", variant = LightTextVariant.Detail, lighten = true)
                    }

                    is Take.Recorded -> {
                        Box(
                            Modifier.lightClickable(
                                onClickLabel = if (previewPlaying) "Pause" else "Play it back",
                                onClick = { togglePreview() },
                            ),
                        ) {
                            LightIcon(
                                if (previewPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                                size = 4f,
                                contentDescription = if (previewPlaying) "Pause" else "Play it back",
                            )
                        }
                        LightText(
                            text = formatClock(t.ms),
                            variant = LightTextVariant.Subheading,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                        LightText(text = "tap to play it back", variant = LightTextVariant.Detail, lighten = true)
                    }

                    Take.Denied -> {
                        Box(Modifier.lightClickable(onClickLabel = "Ask for the microphone", onClick = { record() })) {
                            LightIcon(LightIcons.MICROPHONE, size = 4f, contentDescription = "Ask for the microphone")
                        }
                        LightText(
                            text = "Recording needs the microphone. Tap to allow it.",
                            variant = LightTextVariant.Detail,
                            align = TextAlign.Center,
                            modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                        )
                    }

                    is Take.Failed -> {
                        LightText(text = t.reason, variant = LightTextVariant.Detail, align = TextAlign.Center)
                    }

                    null -> {}
                }
            }
        }
        val canSend = t is Take.Recording || t is Take.Recorded
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items =
                listOfNotNull(
                    LightBarButton
                        .Text(text = "RETRY", contentDescription = "Record it again", onClick = { record() })
                        .takeIf { t !is Take.Recording && t != null },
                    LightBarButton.LightIcon(icon = LightIcons.CLOSE, contentDescription = "Throw it away", onClick = { discard() }),
                    LightBarButton.Text(text = "SEND", contentDescription = "Send to $title", onClick = { send() }).takeIf { canSend },
                ),
        )
    }
}
