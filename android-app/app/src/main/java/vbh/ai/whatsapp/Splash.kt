package vbh.ai.whatsapp

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay

/** How long the splash stays at least, so it doesn't flash. */
const val SPLASH_MIN_MS = 500L

/** Splash art fading in on black; "connecting…" appears under it if starting takes a while. */
@Composable
fun SplashScreen() {
    val fade = remember { Animatable(0f) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { fade.animateTo(1f, tween(300)) }
    LaunchedEffect(Unit) {
        delay(1500)
        slow = true
    }
    ScreenColumn {
        Box(Modifier.fillMaxSize().padding(bottom = 4f.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    painterResource(R.drawable.splash_art),
                    contentDescription = "WhatsApp Light",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 3f.gridUnitsAsDp()).alpha(fade.value),
                )
                LightText(
                    text = if (slow) "connecting…" else "",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    modifier = Modifier.padding(top = 1.5f.gridUnitsAsDp()),
                )
            }
        }
    }
}
