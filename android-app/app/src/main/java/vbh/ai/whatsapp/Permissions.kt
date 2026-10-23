package vbh.ai.whatsapp

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.flow.MutableStateFlow

/** What the app asks for. Runtime permissions have a [permission]; the others are special app access. */
enum class Perm(
    val title: String,
    val detail: String,
    val permission: String?,
) {
    NOTIFICATIONS("Notifications", "new messages and incoming calls", Manifest.permission.POST_NOTIFICATIONS),
    MICROPHONE("Microphone", "needed for calls", Manifest.permission.RECORD_AUDIO),
    CAMERA("Camera", "video calls and taking photos", Manifest.permission.CAMERA),
    FULL_SCREEN("Full-screen alerts", "incoming calls over the lock screen", null),
    BATTERY("Battery: unrestricted", "keeps messages arriving while the phone sleeps", null),
}

/** Which of [Perm] are allowed right now. Refreshed when the app comes to the front and after each request. */
object Permissions {
    val granted = MutableStateFlow<Set<Perm>>(emptySet())

    fun refresh(context: Context) {
        granted.value = Perm.entries.filter { isGranted(context, it) }.toSet()
    }

    private fun isGranted(
        context: Context,
        p: Perm,
    ): Boolean =
        when (p) {
            Perm.FULL_SCREEN -> context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
            Perm.BATTERY -> context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            else -> context.checkSelfPermission(p.permission!!) == PackageManager.PERMISSION_GRANTED
        }

    /** Where the user changes [p] themselves: its own page for special access, else the app's settings. */
    fun settingsIntent(
        context: Context,
        p: Perm,
        allowed: Boolean,
    ): Intent {
        val pkg = Uri.parse("package:${context.packageName}")
        return when {
            p == Perm.FULL_SCREEN -> Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
            p == Perm.BATTERY && !allowed -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
    }
}

/** Asks for the microphone before a call, like [CameraGate] for the camera. */
object MicGate {
    var request: (((Boolean) -> Unit) -> Unit) = { it(false) }

    const val NEEDED = "Calls need the microphone. Allow it in Settings, Permissions."

    fun withMic(block: (Boolean) -> Unit) = request(block)
}

/**
 * The permissions list: each row shows whether it's allowed and asks for it when tapped. On first
 * run it ends with CONTINUE; from Settings it has a back button. A permission the system won't ask
 * for again (or special access) opens its settings page instead.
 */
@Composable
fun PermissionsScreen(
    firstRun: Boolean,
    onDone: () -> Unit,
) {
    val activity = LocalContext.current as Activity
    val granted by Permissions.granted.collectAsState()
    // ALLOW ALL works through this queue, one request at a time.
    var queue by remember { mutableStateOf(emptyList<Perm>()) }
    var busy by remember { mutableStateOf(false) }
    val finished: () -> Unit = {
        Permissions.refresh(activity)
        busy = false
    }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { finished() }
    val settings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { finished() }

    fun request(p: Perm) {
        busy = true
        val allowed = p in granted
        val permission = p.permission
        // Never asked, or refused once (the system still asks): show the system prompt.
        if (permission != null && !allowed &&
            (!AppSettings.askedFor(permission) || activity.shouldShowRequestPermissionRationale(permission))
        ) {
            AppSettings.markAsked(permission)
            runtime.launch(permission)
        } else {
            runCatching { settings.launch(Permissions.settingsIntent(activity, p, allowed)) }.onFailure { busy = false }
        }
    }
    LaunchedEffect(busy, queue) {
        if (!busy && queue.isNotEmpty()) {
            val next = queue.first()
            queue = queue.drop(1)
            request(next)
        }
    }

    val missing = Perm.entries.filter { it !in granted }
    ScreenColumn {
        LightTopBar(
            leftButton = if (firstRun) null else backButton("settings", onDone),
            center = LightTopBarCenter.Text("Permissions"),
        )
        Box(Modifier.weight(1f)) {
            LightScrollView {
                if (firstRun) {
                    LightText(
                        text = "WhatsApp works best with these. You can change them later in Settings.",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp(), vertical = 10.dp),
                    )
                }
                Perm.entries.forEach { p ->
                    ToggleRow(checked = p in granted, title = p.title, subtitle = p.detail) { if (!busy) request(p) }
                }
            }
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items =
                listOf(
                    if (missing.isNotEmpty()) LightBarButton.Text("ALLOW ALL", onClick = { if (!busy) queue = missing }) else null,
                    if (firstRun) LightBarButton.Text("CONTINUE", onClick = onDone) else null,
                ),
        )
    }
}
