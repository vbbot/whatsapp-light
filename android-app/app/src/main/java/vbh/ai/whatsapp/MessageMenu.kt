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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * then full-width actions, and ✕ to close: REPLY and DELETE; reactions (#4) go above them.
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
                text = if (m.isDeleted) "deleted message" else quoteSnippet(m),
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
            if (!m.pending && !m.isDeleted) {
                ToggleButton("REPLY") {
                    Drafts.setReply(chat, m.id)
                    Nav.replaceTop(Screen.Composer(chat))
                }
            }
            if (!m.pending) ToggleButton("DELETE") { Nav.replaceTop(Screen.DeleteMessage(chat, m.id)) }
        }
        Spacer(Modifier.weight(5.5f))
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(null, icon(LightIcons.CLOSE, "Close message options") { Nav.pop() }, null),
        )
    }
}

/**
 * Deleting one message: who said what, then DELETE FOR EVERYONE (our own recent messages only)
 * and DELETE FOR ME, and ✕ to cancel. Nothing changes here until WhatsApp accepts.
 */
@Composable
fun DeleteMessageScreen(
    chat: String,
    id: String,
) {
    val changes by MessageStore.changes.collectAsState()
    val m = rememberQuery(id, changes, initial = MessageStore.get(id)) { MessageStore.get(id) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    if (m == null) {
        StatusText("This message is no longer here.")
        return
    }
    val canRevoke = canDeleteForEveryone(m, System.currentTimeMillis())
    val done: (String?) -> Unit = { error ->
        busy = false
        if (error == null) Nav.pop() else failed = error
    }
    ScreenColumn {
        failed?.let { Banner("Couldn't delete the message ($it). Check the connection and try again.") }
        Spacer(Modifier.weight(21f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp()), horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = if (busy) "Deleting…" else "Delete this message?",
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
            )
            LightText(
                text = if (m.isDeleted) "deleted message" else quoteSnippet(m),
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
            if (!busy) {
                if (canRevoke) {
                    ToggleButton("DELETE FOR EVERYONE") {
                        busy = true
                        failed = null
                        WaCore.deleteForEveryone(m, done)
                    }
                }
                ToggleButton("DELETE FOR ME") {
                    busy = true
                    failed = null
                    WaCore.deleteForMe(m, done)
                }
            }
        }
        Spacer(Modifier.weight(5.5f))
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(null, if (busy) null else icon(LightIcons.CLOSE, "Cancel deleting") { Nav.pop() }, null),
        )
    }
}

/** Our own message, sent, not already deleted, and within WhatsApp's window for deleting for everyone. */
fun canDeleteForEveryone(
    m: Msg,
    now: Long,
): Boolean = m.fromMe && !m.pending && !m.isDeleted && now - m.ts < REVOKE_WINDOW_MS
