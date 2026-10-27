package vbh.ai.whatsapp

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The splash gives up waiting after this and shows the app as it is (the status says why). */
private const val SPLASH_MAX_MS = 8000L

class MainActivity : ComponentActivity() {
    private var pendingCamera: ((Boolean) -> Unit)? = null
    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingCamera?.invoke(granted)
            pendingCamera = null
        }
    private var pendingMedia: ((android.net.Uri?) -> Unit)? = null
    private val mediaLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            pendingMedia?.invoke(uri)
            pendingMedia = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Over the lock screen, and turning the screen on, only while a call rings or is on: once
        // it ends on a locked phone, the lock screen covers the chats again.
        lifecycleScope.launch {
            WaState.call.map { it != null }.distinctUntilChanged().collect { inCall ->
                setShowWhenLocked(inCall)
                setTurnScreenOn(inCall)
            }
        }
        AppSettings.init(this)
        WaCore.prepare(this)
        MediaPicker.request = { block ->
            pendingMedia = block
            MediaPicker.rescanCameraFolders(this) {
                mediaLauncher.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }
        }
        MicGate.request = { block ->
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                block(true)
            } else {
                pendingMic = block
                AppSettings.markAsked(Manifest.permission.RECORD_AUDIO)
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
        Permissions.refresh(this)
        CameraGate.request = { block ->
            if (checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                block(true)
            } else {
                pendingCamera = block
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
        }
        setContent {
            // The LP3 has no day mode: pin to the dark palette.
            LightTheme(colors = LightThemeColors.Dark) {
                CompositionLocalProvider(LocalHapticsEnabled provides true) { Root() }
            }
        }
    }

    private var pendingMic: ((Boolean) -> Unit)? = null
    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Permissions.refresh(this)
            pendingMic?.invoke(granted)
            pendingMic = null
        }

    companion object {
        /** Whether the app is on screen, so an incoming call needn't open it. */
        @Volatile var resumed = false
    }

    /** Whether the scroll wheel navigates the app (its setting), so the app claims it from Luma while in front. */
    private var wheelWanted = false

    override fun onPause() {
        super.onPause()
        resumed = false
        if (wheelWanted) ScrollWheel.claim(this, false)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (wheelWanted) ScrollWheel.claim(this, true)
        // Permissions may have changed in the system settings meanwhile.
        Permissions.refresh(this)
    }

    // With the setting on, the wheel is the app's on every screen; where there's no list it does nothing.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        (wheelWanted && ScrollWheel.onKey(event, lifecycleScope)) || super.dispatchKeyEvent(event)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) ScrollWheel.touched()
        return super.dispatchTouchEvent(event)
    }

    /** Starts the connection, or restarts the service so it can claim the microphone once allowed. */
    private fun startCore() {
        ContextCompat.startForegroundService(this, Intent(this, WaService::class.java))
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        val granted by Permissions.granted.collectAsState()
        val mic = Perm.MICROPHONE in granted
        LaunchedEffect(mic) { startCore() }

        // The splash stays until we know where to go: linked (chats) or not (pairing code).
        val ready =
            remember {
                combine(WaState.linked, WaState.status) { isLinked, status -> isLinked != null || status == "error" }
            }
        var splash by rememberSaveable { mutableStateOf(WaState.linked.value == null) }
        if (splash) {
            LaunchedEffect(Unit) {
                delay(SPLASH_MIN_MS)
                withTimeoutOrNull(SPLASH_MAX_MS) { ready.first { it } }
                splash = false
            }
        }
        val permissionsDone by AppSettings.permissionsDone.collectAsState()

        val qr by WaState.qr.collectAsState()
        val linked by WaState.linked.collectAsState()
        val call by WaState.call.collectAsState()
        val stack by Nav.stack.collectAsState()

        // Back never hangs up: during a call it only sends the app to the background.
        BackHandler(enabled = call != null) { moveTaskToBack(true) }
        BackHandler(enabled = call == null && stack.size > 1) { Nav.pop() }

        val wheelNavigates by AppSettings.wheelNavigates.collectAsState()
        LaunchedEffect(wheelNavigates) {
            wheelWanted = wheelNavigates
            if (resumed) ScrollWheel.claim(this@MainActivity, wheelNavigates)
        }

        Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
            val c = call
            when {
                splash -> {
                    SplashScreen()
                }

                !permissionsDone -> {
                    PermissionsScreen(firstRun = true) { AppSettings.setPermissionsDone() }
                }

                c != null && c.incoming && c.phase < 3 -> {
                    IncomingCallScreen(
                        c,
                        rememberCallTitle(c),
                    )
                }

                c != null && c.video && (c.localVideo || c.remoteVideo) -> {
                    VideoCallScreen(
                        c,
                        rememberCallTitle(c),
                    )
                }

                c != null -> {
                    InCallScreen(
                        c,
                        rememberCallTitle(c),
                    )
                }

                qr != null || linked == false -> {
                    PairingScreen(qr)
                }

                else -> {
                    StackScreen(stack)
                }
            }
        }
    }

    /**
     * Shows the top of the back stack. Each entry keeps its saved state (scroll positions,
     * loaded pages) while it is covered by the screens above it, and loses it once popped.
     */
    @Composable
    private fun StackScreen(stack: List<Screen>) {
        val holder = rememberSaveableStateHolder()
        val keys = stack.mapIndexed(::entryKey)
        var kept by remember { mutableStateOf(keys.toSet()) }
        LaunchedEffect(keys) {
            (kept - keys.toSet()).forEach(holder::removeState)
            kept = keys.toSet()
        }
        holder.SaveableStateProvider(keys.last()) {
            when (val s = stack.last()) {
                Screen.Home -> {
                    HomeScreen()
                }

                is Screen.Thread -> {
                    ThreadScreen(s.chat, s.focus)
                }

                is Screen.Composer -> {
                    ComposerScreen(s.chat)
                }

                is Screen.MentionPicker -> {
                    MentionPickerScreen(s.chat)
                }

                is Screen.EditMessage -> {
                    EditMessageScreen(s.chat, s.id)
                }

                is Screen.Contacts -> {
                    ContactsScreen(s.purpose)
                }

                is Screen.Number -> {
                    NumberScreen(s.purpose)
                }

                Screen.Search -> {
                    SearchScreen()
                }

                is Screen.SearchResults -> {
                    SearchResultsScreen(s.query)
                }

                Screen.Settings -> {
                    SettingsScreen()
                }

                is Screen.ChatOptions -> {
                    ChatOptionsScreen(s.chat)
                }

                is Screen.Community -> {
                    CommunityScreen(s.community)
                }

                Screen.Archived -> {
                    ArchivedScreen()
                }

                is Screen.Attach -> {
                    AttachScreen(s.chat)
                }

                is Screen.VoiceRecord -> {
                    VoiceRecordScreen(s.chat)
                }

                is Screen.CallType -> {
                    CallTypeScreen(s.chat, s.members)
                }

                is Screen.MediaViewer -> {
                    if (s.video) {
                        VideoViewerScreen(
                            s.id,
                            s.path,
                            s.backTo,
                            s.gif,
                        )
                    } else {
                        MediaViewerScreen(s.id, s.path, s.backTo)
                    }
                }

                is Screen.CallConfirm -> {
                    CallConfirmScreen(s.chat, s.video, s.members)
                }

                Screen.Permissions -> {
                    PermissionsScreen(firstRun = false) { Nav.pop() }
                }

                Screen.LogOut -> {
                    LogOutScreen()
                }

                is Screen.DeleteChat -> {
                    DeleteChatScreen(s.chat)
                }

                is Screen.MessageMenu -> {
                    MessageMenuScreen(s.chat, s.id)
                }

                is Screen.DeleteMessage -> {
                    DeleteMessageScreen(s.chat, s.id)
                }

                is Screen.ReactionPicker -> {
                    ReactionPickerScreen(s.chat, s.id)
                }
            }
        }
    }
}
