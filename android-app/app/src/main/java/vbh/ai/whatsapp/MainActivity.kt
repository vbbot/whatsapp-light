package vbh.ai.whatsapp

import android.Manifest
import android.content.Intent
import android.os.Bundle
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
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    override fun onResume() {
        super.onResume()
        // Permissions may have changed in the system settings meanwhile.
        Permissions.refresh(this)
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
                        rememberTitle(callPeer(c)),
                    )
                }

                c != null && c.video && (c.localVideo || c.remoteVideo) -> {
                    VideoCallScreen(
                        c,
                        rememberTitle(callPeer(c)),
                    )
                }

                c != null -> {
                    InCallScreen(
                        c,
                        rememberTitle(callPeer(c)),
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
                Screen.Home -> HomeScreen()
                is Screen.Thread -> ThreadScreen(s.chat)
                is Screen.Composer -> ComposerScreen(s.chat)
                is Screen.Contacts -> ContactsScreen(s.purpose)
                is Screen.Number -> NumberScreen(s.purpose)
                Screen.Settings -> SettingsScreen()
                is Screen.ChatOptions -> ChatOptionsScreen(s.chat)
                Screen.Archived -> ArchivedScreen()
                is Screen.CallType -> CallTypeScreen(s.chat)
                is Screen.MediaViewer -> MediaViewerScreen(s.id, s.path, s.backTo)
                is Screen.CallConfirm -> CallConfirmScreen(s.chat, s.video)
                Screen.Permissions -> PermissionsScreen(firstRun = false) { Nav.pop() }
                Screen.LogOut -> LogOutScreen()
                is Screen.DeleteChat -> DeleteChatScreen(s.chat)
                is Screen.MessageMenu -> MessageMenuScreen(s.chat, s.id)
                is Screen.DeleteMessage -> DeleteMessageScreen(s.chat, s.id)
                is Screen.ReactionPicker -> ReactionPickerScreen(s.chat, s.id)
            }
        }
    }

    /** The caller's chat JID, so the title is a name or number, never a raw LID. */
    private fun callPeer(c: CallUi): String =
        if (c.peer.contains("@")) normalizeJid(c.peer) else "${c.peer.removePrefix("+")}@s.whatsapp.net"
}
