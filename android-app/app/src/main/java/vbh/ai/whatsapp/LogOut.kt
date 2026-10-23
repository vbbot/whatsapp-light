package vbh.ai.whatsapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** Confirms logging out: the question, what it deletes, then LOG OUT. Afterwards the pairing code shows. */
@Composable
fun LogOutScreen() {
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    ScreenColumn {
        LightTopBar(leftButton = if (busy) null else backButton("settings") { Nav.pop() })
        failed?.let { Banner("Couldn't log out ($it). Check the connection and try again.") }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp())
                .padding(bottom = 4f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LightText(
                    text = if (busy) "Logging out…" else "Log out of WhatsApp?",
                    variant = LightTextVariant.Subheading,
                    align = TextAlign.Center,
                )
                LightText(
                    text = "this phone is unlinked, and its messages, calls and photos are deleted",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items =
                listOf(
                    if (busy) {
                        null
                    } else {
                        LightBarButton.Text(text = "LOG OUT", contentDescription = "Log out of WhatsApp", onClick = {
                            busy = true
                            failed = null
                            WaCore.logOut { reason ->
                                busy = false
                                failed = reason
                            }
                        })
                    },
                ),
        )
    }
}
