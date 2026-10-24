package vbh.ai.whatsapp

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.defaultKeyboardOptions
import com.thelightphone.sdk.ui.designVerticalPxToSp
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.ceil
import android.graphics.Color as AColor

/** How long the original of a tapped quote stays highlighted. */
private const val HIGHLIGHT_MS = 1_500L

/** Breaks a run of messages from one sender into a new group (time shown again). */
private const val GROUP_GAP_MS = 15 * 60 * 1000L

/** A message takes at most this much of the row's width. */
private const val MESSAGE_WIDTH_FRACTION = 0.875f

/** Messages loaded per step when scrolling up a thread. */
private const val THREAD_PAGE = 100

/** How long to show "loading earlier messages" while the main phone answers. */
private const val PHONE_WAIT_MS = 15_000L

// ---------------------------------------------------------------- thread

/**
 * One chat. The list is laid out bottom-up (item 0 is a spacer under the newest message), so it
 * opens at the bottom, stays there as messages arrive, and older pages load in above without
 * moving the view. Its scroll position and page count are saved with the screen, so returning
 * from a photo comes back to the same place.
 */
@Composable
fun ThreadScreen(chat: String) {
    val isGroup = chat.endsWith("@g.us")
    val title = rememberTitle(chat)
    val error by WaState.error.collectAsState()
    val connected by WaState.connected.collectAsState()
    val seenStatus by AppSettings.seenStatus.collectAsState()

    var limit by rememberSaveable(chat) { mutableIntStateOf(THREAD_PAGE) }
    val page = rememberThreadPage(chat, limit)
    val msgs = page.messages
    // Reactions for the loaded messages, as one line each ("❤️ 2 · 👍").
    val changes by MessageStore.changes.collectAsState()
    val reactions =
        rememberQuery(msgs, changes, initial = emptyMap()) {
            MessageStore.reactions(msgs.map { it.id }).mapValues { reactionLine(it.value) }
        } ?: emptyMap()
    val newest = msgs.lastOrNull()
    val listState = rememberLazyListState()
    val tag = remember(msgs, isGroup) { if (isGroup) null else receiptTag(msgs) }

    // Whatever arrives while the thread is open counts as read.
    LaunchedEffect(newest?.id, seenStatus, connected) {
        MessageStore.markRead(chat)
        WaCore.sendReadReceipts(chat)
    }
    ScrollDownForOwnMessages(listState, newest)

    // Tapping a quote scrolls to the original and highlights it for a moment, loading older
    // stored messages first if it isn't loaded yet.
    val context = LocalContext.current
    var jumpTo by remember { mutableStateOf<String?>(null) }
    var highlighted by remember { mutableStateOf<String?>(null) }
    // Waits until the original is loaded. Clears the target only after scrolling: changing it is
    // what restarts (and so would cancel) this effect.
    LaunchedEffect(jumpTo, msgs.size) {
        val target = jumpTo ?: return@LaunchedEffect
        val i = msgs.indexOfFirst { it.id == target }
        if (i < 0) return@LaunchedEffect
        // Reverse layout: index 0 is the bottom spacer, then newest first.
        listState.animateScrollToItem(msgs.size - i)
        highlighted = target
        jumpTo = null
    }
    LaunchedEffect(highlighted) {
        if (highlighted == null) return@LaunchedEffect
        delay(HIGHLIGHT_MS)
        highlighted = null
    }

    fun openQuote(m: Msg) {
        val original = MessageStore.get(m.quoteId)?.takeIf { it.chat == chat }
        if (original == null) {
            android.widget.Toast
                .makeText(context, "The original message isn't on this phone.", android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (msgs.none { it.id == original.id }) limit = maxOf(limit, MessageStore.countSince(chat, original.ts) + THREAD_PAGE)
        jumpTo = original.id
    }
    val waitingForPhone = loadOlderAtTop(chat, listState, page, connected, onLoadStoredPage = { limit += THREAD_PAGE })

    ScreenColumn {
        LightTopBar(
            leftButton = backButton("messages") { Nav.pop() },
            center = LightTopBarCenter.Text(title, onClick = { Nav.push(Screen.ChatOptions(chat)) }),
        )
        Column(Modifier.weight(1f).fillMaxWidth()) {
            error?.let { Banner(it) }
            Box(Modifier.weight(1f)) {
                if (msgs.isEmpty()) {
                    StatusText("No messages yet.")
                } else {
                    // The scrollbar is drawn over the list, as in Chats; sent rows keep clear of it with
                    // their wider end padding (see MessageRow).
                    Box(Modifier.fillMaxSize()) {
                        // Only the rows on screen are built; they vary in height.
                        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                            item(key = "bottom") { Box(Modifier.height(1f.gridUnitsAsDp())) }
                            items(msgs.size, key = { msgs[msgs.size - 1 - it].id }) { j ->
                                val i = msgs.size - 1 - j
                                val m = msgs[i]
                                val showTime = startsGroup(msgs.getOrNull(i - 1), m)
                                MessageRow(
                                    m,
                                    showTime = showTime,
                                    showSender = isGroup && showTime,
                                    tag = tag?.takeIf { it.first == m.id }?.second,
                                    reactions = reactions[m.id],
                                    chatTitle = title,
                                    highlighted = highlighted == m.id,
                                    onQuote = { openQuote(m) },
                                    onLongPress = { Nav.push(Screen.MessageMenu(chat, m.id)) },
                                )
                            }
                            if (waitingForPhone) item(key = "loading") { Banner("Loading earlier messages…") }
                        }
                        ThreadScrollBar(listState, Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            // Call (choose voice or video next) | attach a photo or video | write.
            // Groups can't be called here; the slot stays empty so the others keep their places.
            items =
                listOf(
                    if (isGroup) null else icon(LightIcons.CALL, "Call $title") { Nav.push(Screen.CallType(chat)) },
                    icon(LightIcons.ADD, "Send a photo or video to $title") {
                        MediaPicker.pick { uri -> if (uri != null) WaCore.sendMedia(chat, uri) }
                    },
                    icon(LightIcons.COMPOSE_MESSAGE, "Write a message to $title") { Nav.push(Screen.Composer(chat)) },
                ),
        )
    }
}

/** The loaded part of a thread: its newest messages, oldest first, and whether older ones are stored. */
private data class ThreadPage(
    val messages: List<Msg>,
    val olderStored: Boolean,
)

private fun readPage(
    chat: String,
    limit: Int,
) = ThreadPage(MessageStore.thread(chat, limit), MessageStore.count(chat) > limit)

/** The newest [limit] messages of [chat], kept current. Read directly the first time (one small indexed query), so the thread never opens empty. */
@Composable
private fun rememberThreadPage(
    chat: String,
    limit: Int,
): ThreadPage {
    val changes by MessageStore.changes.collectAsState()
    val first = remember(chat) { readPage(chat, limit) }
    return rememberQuery(chat, limit, changes, initial = first) { readPage(chat, limit) } ?: first
}

/** Goes back down when the user sends a message while scrolled up; not on opening or returning. */
@Composable
private fun ScrollDownForOwnMessages(
    listState: LazyListState,
    newest: Msg?,
) {
    var lastSeen by rememberSaveable { mutableStateOf(newest?.id) }
    LaunchedEffect(newest?.id) {
        val m = newest ?: return@LaunchedEffect
        if (m.id != lastSeen && m.fromMe) listState.scrollToItem(0)
        lastSeen = m.id
    }
}

/**
 * Loads older messages as the top of the thread comes into view: the next stored page if there
 * is one ([onLoadStoredPage]), else a request to the main phone, once per oldest message.
 * Returns true while that request is outstanding (for up to [PHONE_WAIT_MS]).
 */
@Composable
private fun loadOlderAtTop(
    chat: String,
    listState: LazyListState,
    page: ThreadPage,
    connected: Boolean,
    onLoadStoredPage: () -> Unit,
): Boolean {
    val nearTop by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }
    }
    var askedFor by rememberSaveable(chat) { mutableStateOf<String?>(null) }
    val oldestId = page.messages.firstOrNull { !it.pending }?.id
    LaunchedEffect(nearTop, page.olderStored, oldestId, connected) {
        if (!nearTop || oldestId == null) return@LaunchedEffect
        if (page.olderStored) {
            onLoadStoredPage()
        } else if (connected && askedFor != oldestId) {
            askedFor = oldestId
            WaCore.requestOlderMessages(chat)
        }
    }
    // Until something older arrives (the oldest changes) or the phone has had time to answer.
    var waiting by remember(chat) { mutableStateOf(false) }
    LaunchedEffect(askedFor, oldestId) {
        waiting = askedFor != null && askedFor == oldestId
        if (waiting) {
            delay(PHONE_WAIT_MS)
            waiting = false
        }
    }
    return waiting
}

/**
 * Scroll position for the thread, drawn like the Light scroll bar (rail and thumb). Rows vary in
 * height, so the content height is estimated from the rows on screen.
 */
@Composable
private fun ThreadScrollBar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val metrics by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
            if (visible.isEmpty() || viewport <= 0f) return@derivedStateOf null
            val avg = visible.sumOf { it.size }.toFloat() / visible.size
            val maxScroll = avg * info.totalItemsCount - viewport
            if (maxScroll <= 0f) return@derivedStateOf null
            // Distance scrolled up from the bottom.
            val scrolled = (listState.firstVisibleItemIndex * avg + listState.firstVisibleItemScrollOffset).coerceIn(0f, maxScroll)
            val thumb = (viewport / (viewport + maxScroll)).coerceIn(0.1f, 0.85f)
            thumb to (1f - scrolled / maxScroll) * (1f - thumb)
        }
    }
    val barColor = LightThemeTokens.colors.content
    Box(modifier.width(2f.gridUnitsAsDp()).fillMaxHeight()) {
        val (thumb, top) = metrics ?: return@Box
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .align(Alignment.TopCenter)
                    .background(barColor),
            )
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = maxHeight * top)
                    .width(5.dp)
                    .height(maxHeight * thumb)
                    .background(barColor),
            )
        }
    }
}

/**
 * Whether [m] starts a new group (shows its time, and the sender in groups): a different sender
 * than [prev], more than [GROUP_GAP_MS] later, or on a new day, as in Chats.
 */
internal fun startsGroup(
    prev: Msg?,
    m: Msg,
): Boolean =
    prev == null || prev.fromMe != m.fromMe || prev.sender != m.sender ||
        m.ts - prev.ts > GROUP_GAP_MS || dayOf(prev.ts) != dayOf(m.ts)

@Composable
private fun MessageRow(
    m: Msg,
    showTime: Boolean,
    showSender: Boolean,
    tag: String?,
    reactions: String?,
    chatTitle: String,
    highlighted: Boolean,
    onQuote: () -> Unit,
    onLongPress: () -> Unit,
) {
    // Outgoing sits right, incoming left, each capped at 7/8 of the row. Outgoing gets a
    // wider right buffer so it stays clear of the scrollbar.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(if (highlighted) Modifier.background(LightThemeTokens.colors.contentSecondary.copy(alpha = 0.3f)) else Modifier)
            .lightPressable(onTapLabel = "Message", onTap = {}, onLongPressLabel = "Message options", onLongPress = onLongPress)
            .padding(
                start = 1.5f.gridUnitsAsDp(),
                end = if (m.fromMe) 2.5f.gridUnitsAsDp() else 1.5f.gridUnitsAsDp(),
                top = if (showTime) 8.dp else 3.dp,
                bottom = if (showTime) 8.dp else 3.dp,
            ),
    ) {
        val columnWidthPx = with(LocalDensity.current) { (maxWidth * MESSAGE_WIDTH_FRACTION).roundToPx() }
        Column(
            Modifier
                .fillMaxWidth(MESSAGE_WIDTH_FRACTION)
                .align(if (m.fromMe) Alignment.CenterEnd else Alignment.CenterStart),
            horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start,
        ) {
            if (showSender && !m.fromMe && m.senderName.isNotBlank()) {
                LightText(text = m.senderName, variant = LightTextVariant.Detail)
            }
            // The time only leads a group; an in-flight message always says so. Solid white, like the text.
            when {
                m.pending -> TimeTag("SENDING")
                showTime -> TimeTag(formatMessageTime(m.ts))
            }
            if (m.isReply) {
                // The quote line, then the body, sharing a left edge.
                Column(Modifier.width(IntrinsicSize.Max)) {
                    val changes by MessageStore.changes.collectAsState()
                    val line = remember(m.id, m.quoteId, changes) { Replies.lineFor(m) }
                    QuoteLine(
                        line,
                        Modifier
                            .fillMaxWidth()
                            .lightClickable(onClickLabel = "Go to the message this replies to", onClick = onQuote)
                            .padding(top = 1.dp),
                    )
                    MessageBody(m, columnWidthPx, chatTitle)
                }
            } else {
                MessageBody(m, columnWidthPx, chatTitle)
            }
            // Receipt tag in place of ticks: one quiet word under the message it applies to.
            tag?.let { LightText(text = it, variant = LightTextVariant.Superfine, modifier = Modifier.padding(top = 1.dp)) }
            // Reactions: one quiet line under the message, like the receipt word.
            reactions?.let { LightText(text = it, variant = LightTextVariant.Superfine, modifier = Modifier.padding(top = 1.dp)) }
        }
    }
}

/** A message's content: its picture (tap for full screen) and caption, or its text. */
@Composable
private fun MessageBody(
    m: Msg,
    columnWidthPx: Int,
    chatTitle: String,
) {
    if (m.isDeleted) {
        // Deleted for everyone: a dimmed line in its place.
        LightText(
            text = if (m.fromMe) "You deleted this message" else "This message was deleted",
            variant = LightTextVariant.Paragraph,
            lighten = true,
            modifier = Modifier.padding(top = 1.dp),
        )
    } else if (m.hasPicture) {
        // Media is the one place colour is allowed: shown as-is, tap for full screen.
        MediaThumb(m, onOpen = { Nav.push(Screen.MediaViewer(m.id, m.media, chatTitle)) })
        if (m.isVideo) MessageText("[Video]", m.fromMe, columnWidthPx)
        if (m.caption.isNotEmpty()) MessageText(m.caption, m.fromMe, columnWidthPx)
    } else {
        MessageText(m.text, m.fromMe, columnWidthPx)
    }
}

// ---------------------------------------------------------------- photos

/** Decodes [path] scaled down to about [maxSide] px, off the main thread; null until ready. */
@Composable
private fun rememberBitmap(
    path: String,
    maxSide: Int,
): androidx.compose.ui.graphics.ImageBitmap? {
    val state =
        androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path, maxSide) {
            value =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val bounds =
                            android.graphics.BitmapFactory
                                .Options()
                                .apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(path, bounds)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                        android.graphics.BitmapFactory
                            .decodeFile(
                                path,
                                android.graphics.BitmapFactory
                                    .Options()
                                    .apply { inSampleSize = sample },
                            )?.asImageBitmap()
                    }.getOrNull()
                }
        }
    return state.value
}

@Composable
private fun TimeTag(text: String) {
    LightText(text = text, variant = LightTextVariant.Superfine, modifier = Modifier.padding(top = 1.dp))
}

/**
 * Message text in `Paragraph`, as in Chats. Ours sits at the right in a block as wide as its
 * widest line once wrapped to the message column, with the lines themselves left-aligned, so its
 * right edge is the text's own. Theirs is plain left-aligned text.
 */
@Composable
private fun MessageText(
    text: String,
    fromMe: Boolean,
    columnWidthPx: Int,
) {
    if (!fromMe) {
        LightText(text = text, variant = LightTextVariant.Paragraph, modifier = Modifier.padding(top = 1.dp))
        return
    }
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = paragraphStyle()
    val width =
        remember(text, columnWidthPx, style, density) {
            // Wrapped at the column width, then sized to the widest wrapped line, so the block's
            // right edge is the text's: a wrapped message ends flush right instead of leaving the
            // slack of its line breaks. Laying it out again at that width breaks it the same way,
            // since every line already fits.
            val layout = measurer.measure(AnnotatedString(text), style, constraints = Constraints(maxWidth = columnWidthPx))
            val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) } ?: 0f
            // Rounded up: a sub-pixel shortfall would push the last word onto a new line.
            with(density) { minOf(ceil(widest), columnWidthPx.toFloat()).toDp() }
        }
    LightText(text = text, variant = LightTextVariant.Paragraph, modifier = Modifier.padding(top = 1.dp).width(width))
}

/** The SDK's `Paragraph` style scaled to this screen, as `LightText` draws it (the SDK keeps its scaling internal). */
@Composable
private fun paragraphStyle(): TextStyle {
    val p = LightThemeTokens.typography.paragraph

    @Composable
    fun TextUnit.scaled() = if (this == TextUnit.Unspecified) this else value.designVerticalPxToSp()
    return p.copy(fontSize = p.fontSize.scaled(), lineHeight = p.lineHeight.scaled(), letterSpacing = p.letterSpacing.scaled())
}

/** Width / height of the image at [path], from its header alone; null if there's no readable image. */
private fun imageAspect(path: String): Float? {
    if (path.isEmpty()) return null
    val bounds =
        android.graphics.BitmapFactory
            .Options()
            .apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth.toFloat() / bounds.outHeight else null
}

/**
 * A photo in a thread row: 70% of the row wide, its own aspect ratio, tap to open. The row takes
 * its final size straight away (read from the file's header) and the picture fills in once
 * decoded, so rows don't change height under the reader and a saved scroll position restores
 * exactly. Coming on screen fetches the full photo if only the preview is stored
 * ([Photos.shown]); it has the same shape, so the row only sharpens.
 */
@Composable
private fun MediaThumb(
    m: Msg,
    onOpen: () -> Unit,
) {
    LaunchedEffect(m.id, m.needsFullPhoto) { Photos.shown(m) }
    val aspect = remember(m.media) { imageAspect(m.media) }
    val bmp = rememberBitmap(m.media, 720)
    val open = Modifier.lightClickable(onClickLabel = "Open photo", onClick = onOpen)
    if (aspect == null) {
        // No preview: a placeholder until the photo arrives; it still opens the viewer.
        LightText(text = "[Photo]", variant = LightTextVariant.Paragraph, modifier = open.padding(top = 1.dp))
        return
    }
    Box(Modifier.fillMaxWidth(0.7f).aspectRatio(aspect).then(open)) {
        bmp?.let {
            Image(
                bitmap = it,
                contentDescription = "Photo, open full screen",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Full-screen photo; back returns to the chat. Shows what's stored and fetches the full photo
 * if needed ([Photos.opened]), following the message so it switches over when that arrives.
 * [openedPath] covers a photo that's still sending (not stored yet).
 */
@Composable
fun MediaViewerScreen(
    id: String,
    openedPath: String,
    backTo: String,
) {
    val changes by MessageStore.changes.collectAsState()
    val msg = rememberQuery(id, changes) { MessageStore.get(id) }
    val connected by WaState.connected.collectAsState()
    val states by Photos.states.collectAsState()
    LaunchedEffect(msg?.id, connected) { if (msg != null && connected) Photos.opened(msg) }
    val bmp = rememberBitmap(msg?.media?.ifEmpty { null } ?: openedPath, 2048)
    ScreenColumn {
        LightTopBar(leftButton = backButton(backTo) { Nav.pop() })
        val state = states[id]
        if (msg?.needsFullPhoto == true) {
            Banner(
                when {
                    state is Photos.State.Failed -> "Couldn't load the full photo: ${state.reason}"
                    !connected -> "The full photo loads when connected"
                    else -> "Loading the full photo…"
                },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (bmp == null) {
                // Nothing to show yet; after a failed download the banner says why.
                if (state !is Photos.State.Failed) StatusText(LOADING_TEXT)
            } else {
                Image(
                    bitmap = bmp,
                    contentDescription = "Photo",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}
