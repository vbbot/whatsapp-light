package vbh.ai.whatsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * What a long press on a message offers, laid out like a chat's options: who said what, centred,
 * then full-width actions, and ✕ to close. REPLY for now; reactions (#4) go above it.
 */
@Composable
fun MessageMenuScreen(
    chat: String,
    id: String,
) {
    val changes by MessageStore.changes.collectAsState()
    val m = rememberQuery(id, changes, initial = MessageStore.get(id)) { MessageStore.get(id) }
    if (m == null) {
        // Deleted meanwhile.
        StatusText("This message is no longer here.")
        return
    }
    val author = Replies.authorName(if (m.fromMe) "me" else m.sender, chat)
    ScreenColumn {
        Spacer(Modifier.weight(21f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp()), horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = author,
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = quoteSnippet(m),
                variant = LightTextVariant.Detail,
                lighten = true,
                align = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
            )
        }
        Spacer(Modifier.weight(5.5f))
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.25f.gridUnitsAsDp()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A message still sending has no ID WhatsApp knows yet.
            if (!m.pending) {
                ToggleButton("REPLY") {
                    Drafts.setReply(chat, m.id)
                    Nav.replaceTop(Screen.Composer(chat))
                }
            }
        }
        Spacer(Modifier.weight(5.5f))
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(null, icon(LightIcons.CLOSE, "Close message options") { Nav.pop() }, null),
        )
    }
}
