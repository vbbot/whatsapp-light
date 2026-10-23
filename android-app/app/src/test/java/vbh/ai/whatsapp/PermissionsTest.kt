package vbh.ai.whatsapp

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test fun refreshReadsEachKind() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        Permissions.refresh(app)
        val granted = Permissions.granted.value
        assertTrue(Perm.MICROPHONE in granted)
        assertTrue(Perm.BATTERY in granted)
        assertFalse(Perm.CAMERA in granted)
    }

    @Test fun settingsPages() {
        val ctx: Context = app
        assertEquals(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Permissions.settingsIntent(ctx, Perm.BATTERY, allowed = false).action,
        )
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Permissions.settingsIntent(ctx, Perm.BATTERY, allowed = true).action)
        assertEquals(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Permissions.settingsIntent(ctx, Perm.FULL_SCREEN, allowed = false).action,
        )
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Permissions.settingsIntent(ctx, Perm.CAMERA, allowed = false).action)
        assertEquals("package:${app.packageName}", Permissions.settingsIntent(ctx, Perm.CAMERA, allowed = false).dataString)
    }
}
