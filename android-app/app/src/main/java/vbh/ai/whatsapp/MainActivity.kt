package vbh.ai.whatsapp

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeColors
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled

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

    private fun startCore() {
        ContextCompat.startForegroundService(this, Intent(this, WaService::class.java))
    }

    @androidx.compose.runtime.Composable
    private fun Root() {
        val perms =
            buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
        var denied by remember { mutableStateOf(false) }
        val launcher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
                if (r[Manifest.permission.RECORD_AUDIO] == true) startCore() else denied = true
            }
        LaunchedEffect(Unit) { launcher.launch(perms) }

        val qr by WaState.qr.collectAsState()
        val call by WaState.call.collectAsState()
        val stack by Nav.stack.collectAsState()

        // Back never hangs up: during a call it only sends the app to the background.
        BackHandler(enabled = call != null) { moveTaskToBack(true) }
        BackHandler(enabled = call == null && stack.size > 1) { Nav.pop() }

        Box(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) {
            val c = call
            when {
                denied -> {
                    StatusText("Microphone access is needed for calls. Allow it in the app's permissions.")
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

                qr != null -> {
                    PairingScreen(qr!!)
                }

                else -> {
                    when (val s = stack.last()) {
                        Screen.Home -> HomeScreen()
                        is Screen.Thread -> ThreadScreen(s.chat)
                        is Screen.Composer -> ComposerScreen(s.chat)
                        is Screen.Contacts -> ContactsScreen(s.purpose)
                        is Screen.Number -> NumberScreen(s.purpose)
                        Screen.Settings -> SettingsScreen()
                        is Screen.CallType -> CallTypeScreen(s.chat)
                        is Screen.MediaViewer -> MediaViewerScreen(s.path, s.backTo)
                        is Screen.CallConfirm -> CallConfirmScreen(s.chat, s.video)
                    }
                }
            }
        }
    }

    /** The caller's chat JID, so the title is a name or number, never a raw LID. */
    private fun callPeer(c: CallUi): String =
        if (c.peer.contains("@")) normalizeJid(c.peer) else "${c.peer.removePrefix("+")}@s.whatsapp.net"
}
