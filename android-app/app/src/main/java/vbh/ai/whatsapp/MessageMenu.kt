package vbh.ai.whatsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * What a long press on a message offers, laid out like a chat's options: who said what, centred,
 * then full-width actions, and ✕ to close: the reaction rows first (LIKE and REACT, or EDIT and
 * REMOVE REACTION once we've reacted), then REPLY, EDIT (our own text messages, while WhatsApp
 * allows it) and DELETE. A message of ours not sent yet offers only RETRY (if WhatsApp refused
 * it) and DELETE.
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
        WheelMenuScope {
            Column(
                Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.25f.gridUnitsAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Not sent yet: there's nothing on WhatsApp to react or reply to.
                if (!m.pending && !m.isDeleted) {
                    val own = remember(m.id, changes) { MessageStore.ownReaction(m.id) }
                    if (own == null) {
                        ToggleButton("LIKE") {
                            WaCore.react(m, LIKE)
                            Nav.pop()
                        }
                        ToggleButton("REACT") { Nav.replaceTop(Screen.ReactionPicker(chat, m.id)) }
                    } else {
                        ToggleButton("EDIT REACTION") { Nav.replaceTop(Screen.ReactionPicker(chat, m.id)) }
                        ToggleButton("REMOVE REACTION") {
                            WaCore.react(m, "")
                            Nav.pop()
                        }
                    }
                    ToggleButton("REPLY") {
                        Drafts.setReply(chat, m.id)
                        Nav.replaceTop(Screen.Composer(chat))
                    }
                    if (canEdit(m, System.currentTimeMillis())) ToggleButton("EDIT") { Nav.replaceTop(Screen.EditMessage(chat, m.id)) }
                }
                if (m.pending) {
                    // Only here: retrying sends it again, deleting drops it without telling anyone.
                    if (m.notSent) {
                        ToggleButton("RETRY") {
                            Outbox.retry(m)
                            Nav.pop()
                        }
                    }
                    ToggleButton("DELETE") {
                        WaCore.discard(m)
                        Nav.pop()
                    }
                } else {
                    ToggleButton("DELETE") { Nav.replaceTop(Screen.DeleteMessage(chat, m.id)) }
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
        WheelMenuScope {
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

/** The quick reaction. */
private const val LIKE = "❤️"

/**
 * The reaction choices, in the LP3 emoji panel's layout (3 rows of 8): faces, hands, symbols.
 * Choosing our current reaction again removes it.
 */
private val REACTION_ROWS =
    listOf(
        listOf("😅", "😊", "🙄", "😍", "😜", "😂", "😭", "😎"),
        listOf("👏", "👍", "👎", "🤞", "✌️", "👌", "👋", "🙏"),
        listOf("✨", "🔥", "❤️", "💔", "🏆", "🎯", "👑", "👀"),
    )

/** Picking a reaction for one message: the emoji grid, our current one underlined; ✕ to cancel. */
@Composable
fun ReactionPickerScreen(
    chat: String,
    id: String,
) {
    val m = remember(id) { MessageStore.get(id) }
    if (m == null || m.chat != chat) {
        StatusText("This message is no longer here.")
        return
    }
    val own = remember(id) { MessageStore.ownReaction(id) }
    ScreenColumn {
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 1f.gridUnitsAsDp())) {
            REACTION_ROWS.forEach { row ->
                Row(Modifier.fillMaxWidth().height(EMOJI_CELL)) {
                    row.forEach { emoji ->
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .lightClickable(onClickLabel = if (emoji == own) "Remove $emoji" else "React $emoji") {
                                    WaCore.react(m, if (emoji == own) "" else emoji)
                                    Nav.pop()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emoji,
                                fontSize = EMOJI_SIZE,
                                textDecoration = if (emoji == own) TextDecoration.Underline else null,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(null, icon(LightIcons.CLOSE, "Cancel reacting") { Nav.pop() }, null),
        )
    }
}

// The LP3 emoji panel's geometry (as Chats measures it).
private val EMOJI_CELL = 46.dp
private val EMOJI_SIZE = 24.sp

/** Our own text message, recent enough that WhatsApp still lets us edit it. */
fun canEdit(
    m: Msg,
    now: Long,
): Boolean = m.fromMe && !m.pending && m.isText && now - m.ts < EDIT_WINDOW_MS
