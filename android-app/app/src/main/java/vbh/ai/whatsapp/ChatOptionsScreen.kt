package vbh.ai.whatsapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.thelightphone.sdk.ui.lightClickable

/**
 * One chat's options: its name and number centred, then a stack of full-width toggles (PIN /
 * UNPIN, MUTE / UNMUTE, ARCHIVE / UNARCHIVE), and a bottom bar with only ✕ to close. Each toggle shows the
 * current state and flips it here and on the user's other devices; the screen stays open so
 * the change is visible.
 */
@Composable
fun ChatOptionsScreen(chat: String) {
    val changes by MessageStore.changes.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    val title = rememberTitle(chat)
    val state = rememberQuery(chat, changes, initial = MessageStore.chatState(chat)) { MessageStore.chatState(chat) } ?: ChatState()
    val muted = state.mutedAt(System.currentTimeMillis())
    // The number as a second line, when it isn't already the title (unsaved contacts).
    val number = (phones[chat] ?: displayNumber(chat)).takeIf { it.isNotBlank() && it != title }

    val error by WaState.error.collectAsState()
    // A refusal or failed change shows here while it's relevant, not after leaving.
    DisposableEffect(Unit) { onDispose { WaState.error.value = null } }

    ScreenColumn {
        error?.let { Banner(it) }
        // The name block sits near the middle; the toggles are centred between it and the bar.
        Spacer(Modifier.weight(21f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 1.5f.gridUnitsAsDp()), horizontalAlignment = Alignment.CenterHorizontally) {
            LightText(
                text = title,
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            number?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
            }
        }
        Spacer(Modifier.weight(5.5f))
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(0.25f.gridUnitsAsDp()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Archived chats can't be pinned (archiving unpins).
            if (!state.archived) ToggleButton(if (state.pinned) "UNPIN" else "PIN") { WaCore.setPinned(chat, !state.pinned) }
            ToggleButton(if (muted) "UNMUTE" else "MUTE") { WaCore.setMuted(chat, !muted) }
            ToggleButton(if (state.archived) "UNARCHIVE" else "ARCHIVE") { WaCore.setArchived(chat, !state.archived) }
        }
        Spacer(Modifier.weight(5.5f))
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(null, icon(LightIcons.CLOSE, "Close options for $title") { Nav.pop() }, null),
        )
    }
}

/** A full-width action in the options stack. */
@Composable
internal fun ToggleButton(
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = label.lowercase(), onClick = onClick)
            .padding(vertical = 0.5f.gridUnitsAsDp()),
        contentAlignment = Alignment.Center,
    ) {
        LightText(text = label, variant = LightTextVariant.Button)
    }
}
