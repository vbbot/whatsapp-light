package vbh.ai.whatsapp

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.ContextLightVibrator
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.designVerticalPxToDp
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.keyboard.LightEmbeddedLp3Keyboard
import com.thelightphone.sdk.ui.lightClickable

/**
 * Writing a message, optionally as a reply: the pending reply's quote line sits directly above
 * the text, lined up with it, with a ✕ that drops the reply and keeps the text. Text and reply
 * are kept as a draft until the message is sent, so leaving and coming back keeps both.
 */
@Composable
fun ComposerScreen(chat: String) {
    val title = rememberTitle(chat)
    val state = rememberTextFieldState(Drafts.text(chat))
    LaunchedEffect(state.text) { Drafts.setText(chat, state.text.toString()) }
    var replyTo by remember { mutableStateOf(Drafts.reply(chat)?.let(MessageStore::get)) }
    MessageEditor(
        title = title,
        state = state,
        header =
            replyTo?.let { m ->
                {
                    ReplyHeader(Replies.line(Replies.authorName(if (m.fromMe) "me" else m.sender, chat), quoteSnippet(m))) {
                        Drafts.setReply(chat, null)
                        replyTo = null
                    }
                }
            },
        onSubmit = { text ->
            val body = text.toString().trim()
            if (body.isNotEmpty()) {
                // The draft goes once WhatsApp has the message; if sending fails it's still here.
                WaCore.sendText(chat, body, replyTo) { Drafts.clear(chat) }
                Nav.pop()
            }
        },
        onBack = { Nav.pop() },
    )
}

/**
 * Editing our own message: its text to change, then SAVE. Nothing changes until WhatsApp accepts;
 * if it refuses (for example once the time to edit has passed), the reason shows in the thread.
 */
@Composable
fun EditMessageScreen(
    chat: String,
    id: String,
) {
    val m = remember(id) { MessageStore.get(id) }
    if (m == null || !m.isText) {
        StatusText("This message can't be edited.")
        return
    }
    val state = rememberTextFieldState(m.text)
    MessageEditor(
        title = "Edit message",
        state = state,
        header = null,
        onSubmit = { text ->
            val body = text.toString().trim()
            if (body.isNotEmpty() && body != m.text) {
                WaCore.editMessage(m, body) { error ->
                    if (error != null) WaState.error.value = "Couldn't edit the message ($error). Check the connection and try again."
                }
            }
            Nav.pop()
        },
        onBack = { Nav.pop() },
        submitLabel = "SAVE",
    )
}

/** The pending reply above the text: its quote line, and ✕ to send without it. */
@Composable
private fun ReplyHeader(
    line: androidx.compose.ui.text.AnnotatedString,
    onClear: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(bottom = 0.5f.gridUnitsAsDp()), verticalAlignment = Alignment.CenterVertically) {
        QuoteLine(line, Modifier.weight(1f))
        Box(Modifier.lightClickable(onClickLabel = "Clear reply", onClick = onClear).padding(start = 0.5f.gridUnitsAsDp())) {
            LightIcon(icon = LightIcons.CLOSE, size = 1.5f, contentDescription = "Clear reply")
        }
    }
}

/** Unsent drafts (text, and the message being replied to) survive leaving the composer; process-scoped. */
object Drafts {
    private val texts = mutableMapOf<String, String>()
    private val replies = mutableMapOf<String, String>()

    @Synchronized
    fun text(chat: String) = texts[chat].orEmpty()

    @Synchronized
    fun setText(
        chat: String,
        text: String,
    ) {
        if (text.isEmpty()) texts.remove(chat) else texts[chat] = text
    }

    /** The ID of the message the draft replies to, or null. */
    @Synchronized
    fun reply(chat: String) = replies[chat]

    @Synchronized
    fun setReply(
        chat: String,
        id: String?,
    ) {
        if (id == null) replies.remove(chat) else replies[chat] = id
    }

    @Synchronized
    fun clear(chat: String) {
        texts.remove(chat)
        replies.remove(chat)
    }
}

// ---------------------------------------------------------------- editor
//
// MessageEditor and EditorKeyboardCallback are adapted from the Light SDK's LightTextInputEditor and
// TextInputKeyboardCallback (https://github.com/lightphone/light-sdk, MIT License, Copyright (c) 2026
// The Light Phone), with a [header] slot above the text: the SDK's editor has none, and the reply line
// has to sit directly above what's typed.

private const val INPUT_UNDERLINE_THICKNESS_PX = 3f
private const val INPUT_UNDERLINE_GAP_GRID_UNITS = 0.5f

/** Full-screen text entry as LightTextInputEditor draws it, plus an optional [header] above the text. */
@Composable
private fun MessageEditor(
    title: String,
    state: TextFieldState,
    header: (@Composable () -> Unit)?,
    onSubmit: (CharSequence) -> Unit,
    onBack: () -> Unit,
    submitLabel: String? = null,
) {
    val hapticsEnabled = LocalHapticsEnabled.current
    val context = LocalContext.current
    val currentOnHaptic by rememberUpdatedState {
        if (hapticsEnabled) ContextLightVibrator(context).click()
    }
    val callback = remember(state) { EditorKeyboardCallback(state) { currentOnHaptic() } }
    // The keyboard's view model lives exactly as long as this editor. The activity's own store
    // would keep the first one, still typing into the first editor's text, for every later one.
    val store = remember { KeyboardStore() }
    DisposableEffect(store) { onDispose { store.viewModelStore.clear() } }
    val keyboard: Lp3KeyboardViewModel<*> =
        viewModel<EnQwertyLp3KeyboardViewModel<*>>(viewModelStoreOwner = store, factory = keyboardFactory(callback))
    val colors = LightThemeTokens.colors
    val inputStyle = headingStyle()
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        LightTopBar(
            leftButton = LightBarButton.LightIcon(icon = LightIcons.BACK, onClick = onBack, contentDescription = "Back"),
            center = LightTopBarCenter.Text(title),
            modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
        )
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp())) {
            header?.invoke()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // Tap or drag in the text to move the cursor.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            textLayout?.let { l -> state.edit { selection = TextRange(l.getOffsetForPosition(down.position)) } }
                            drag(down.id) { change ->
                                textLayout?.let { l -> state.edit { selection = TextRange(l.getOffsetForPosition(change.position)) } }
                                change.consume()
                            }
                        }
                    },
                contentAlignment = Alignment.TopStart,
            ) {
                Column(Modifier.fillMaxWidth()) {
                    BasicText(
                        text = state.text.toString(),
                        style = inputStyle,
                        onTextLayout = { textLayout = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(INPUT_UNDERLINE_GAP_GRID_UNITS.gridUnitsAsDp()))
                    Spacer(
                        Modifier
                            .fillMaxWidth()
                            .height(INPUT_UNDERLINE_THICKNESS_PX.designVerticalPxToDp())
                            .background(colors.content),
                    )
                }
                textLayout?.let { layout ->
                    val rect = layout.getCursorRect(state.selection.min.coerceIn(0, layout.layoutInput.text.length))
                    Box(
                        Modifier
                            .offset { IntOffset(rect.left.toInt(), rect.top.toInt()) }
                            .width(2.dp)
                            .height(with(LocalDensity.current) { rect.height.toDp() })
                            .background(colors.content),
                    )
                }
            }
        }
        LightEmbeddedLp3Keyboard(
            viewModel = keyboard,
            additionalBottomHeight = 5f.gridUnitsAsDp(),
            bottomBar = {
                LightBottomBar(
                    items =
                        listOf(
                            if (submitLabel != null) {
                                LightBarButton.Text(text = submitLabel, onClick = { onSubmit(state.text) })
                            } else {
                                LightBarButton.LightIcon(icon = LightIcons.SEND, onClick = {
                                    onSubmit(state.text)
                                }, contentDescription = "Send")
                            },
                        ),
                )
            },
        )
    }
}

/** The SDK's input style: Heading, at this screen's scale. */
@Composable
private fun headingStyle(): androidx.compose.ui.text.TextStyle {
    val t = LightThemeTokens.typography.heading

    @Composable
    fun androidx.compose.ui.unit.TextUnit.scaled() =
        if (this == androidx.compose.ui.unit.TextUnit.Unspecified) this else value.designVerticalPxToSp()
    return t.copy(
        color = LightThemeTokens.colors.content,
        fontSize = t.fontSize.scaled(),
        lineHeight = t.lineHeight.scaled(),
        letterSpacing = t.letterSpacing.scaled(),
    )
}

/** Holds one editor's keyboard view model, cleared when the editor leaves the screen. */
private class KeyboardStore : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

private fun keyboardFactory(callback: Lp3RepeatableKeyboardCallback): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            EnQwertyLp3KeyboardViewModel<Unit>(
                callback,
                keyboardOptionsFlow = keyboardOptions,
                optionsForLayout = { LayoutOptions(!it.isRootLayout) },
            ).apply { setCapsMode(true) } as T
    }

/** Types into [state] from the LP3 keyboard: return is a newline (SEND sends). */
private class EditorKeyboardCallback(
    private val state: TextFieldState,
    private val onHaptic: () -> Unit,
) : Lp3RepeatableKeyboardCallback {
    override fun onKeyPressed(code: Int) = onHaptic()

    override fun onSpecialKeyPressed(key: SpecialKey) {
        onHaptic()
        if (key == SpecialKey.Space) insert(" ")
    }

    override fun onKeyReleased(code: Int) = insert(buildString { appendCodePoint(code) })

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> {
                val before = state.text.subSequence(0, state.selection.min)
                deleteBefore(if (before.isNotEmpty() && Character.isLowSurrogate(before.last())) 2 else 1)
            }

            SpecialKey.Return -> {
                insert("\n")
            }

            else -> {
                Unit
            }
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) {
            // A whole word.
            val before = state.text.subSequence(0, state.selection.min)
            val trimmed = before.trimEnd()
            val lastSpace = trimmed.indexOfLast { it.isWhitespace() }
            deleteBefore(before.length - if (lastSpace >= 0) lastSpace + 1 else 0)
        }
    }

    override fun onKeyRepeated(code: Int) = insert(buildString { appendCodePoint(code) })

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insert(" ")
    }

    override fun onSubmitWord(word: CharSequence) = insert(word.toString())

    private fun insert(text: String) {
        state.edit {
            val start = selection.min
            replace(start, selection.max, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBefore(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }
}
