package com.wapoc.app

import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

private const val LOADING_TEXT = "loading…"
private const val MESSAGE_WIDTH_FRACTION = 0.875f
private const val GROUP_GAP_MS = 15 * 60 * 1000L

// ---------------------------------------------------------------- shared pieces

/** Every screen paints true black explicitly; the theme does not. */
@Composable
private fun ScreenColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) { content() }
}

@Composable
private fun backButton(destination: String, onClick: () -> Unit) = LightBarButton.LightIcon(
    icon = LightIcons.BACK,
    onClick = onClick,
    contentDescription = "Back to $destination",
)

/** Centered status text, optically biased upward (4 gu bottom padding). */
@Composable
fun StatusText(text: String) {
    Box(Modifier.fillMaxSize().padding(bottom = 4f.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
        LightText(
            text = text,
            variant = LightTextVariant.Heading,
            align = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
        )
    }
}

@Composable
private fun Banner(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp(), vertical = 10.dp),
    )
}

private fun icon(
    icon: com.thelightphone.sdk.ui.LightIconConfiguration,
    description: String,
    onClick: () -> Unit,
) = LightBarButton.LightIcon(icon = icon, onClick = onClick, contentDescription = description)

// ---------------------------------------------------------------- home (tabs)

/**
 * Home: a list-home screen with two destinations. The top bar carries the active destination as
 * context and settings on the right; the bottom bar holds both destinations plus the active tab's
 * primary action (new chat / new call).
 */
@Composable
fun HomeScreen() {
    val tab by Nav.tab.collectAsState()
    val connected by WaState.connected.collectAsState()
    val error by WaState.error.collectAsState()
    ScreenColumn {
        LightTopBar(
            center = LightTopBarCenter.Text(tab.label),
            rightButton = icon(LightIcons.SETTINGS, "Settings") { Nav.push(Screen.Settings) },
        )
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                if (!connected) Banner("Offline — reconnecting. Calls and sending are paused.")
                error?.let { Banner(it) }
                Box(Modifier.weight(1f)) {
                    when (tab) {
                        Tab.MESSAGES -> ChatList()
                        Tab.CALLS -> CallList()
                    }
                }
            }
        }
        TabBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(
                TabItem(R.drawable.ic_messages, "Messages", selected = tab == Tab.MESSAGES) { Nav.tab.value = Tab.MESSAGES },
                TabItem(LightIcons.CALL.drawableResource, "Calls", selected = tab == Tab.CALLS) { Nav.tab.value = Tab.CALLS },
                when (tab) {
                    Tab.MESSAGES -> TabItem(LightIcons.COMPOSE_MESSAGE.drawableResource, "New chat") { Nav.push(Screen.Contacts(Purpose.MESSAGE)) }
                    Tab.CALLS -> TabItem(LightIcons.DIALPAD.drawableResource, "New call") { Nav.push(Screen.Contacts(Purpose.CALL)) }
                },
            ),
        )
    }
}

@Composable
private fun ChatList() {
    val all by MessageStore.messages.collectAsState()
    val read by MessageStore.lastRead.collectAsState()
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    val connected by WaState.connected.collectAsState()
    val chats = remember(all) { MessageStore.chats(all) }
    val listState = rememberLazyListState()
    when {
        chats.isNotEmpty() -> LightLazyScrollView(uniformItemHeightGridUnits = 4.5f, listState = listState) {
            items(chats, key = { it.first }) { (chat, last) ->
                ChatRow(
                    name = remember(names, phones, all) { MessageStore.title(chat, all) },
                    time = formatRowTime(last.ts),
                    unread = MessageStore.hasUnread(chat, all, read),
                    onOpen = { Nav.push(Screen.Thread(chat)) },
                )
            }
        }
        !connected -> StatusText(LOADING_TEXT)
        else -> StatusText("No conversations. Tap the pencil to start one.")
    }
}

@Composable
private fun ChatRow(name: String, time: String, unread: Boolean, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = "Open chat with $name", onClick = onOpen)
            // 12dp vertical padding fits six rows on the panel (same as Chats).
            .padding(start = 0.5f.gridUnitsAsDp(), end = 0.5f.gridUnitsAsDp(), top = 12.dp, bottom = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The unread marker is a large asterisk in a fixed 1 gu slot so names never shift.
            Box(Modifier.width(1f.gridUnitsAsDp())) {
                if (unread) LightText(text = "*", variant = LightTextVariant.Heading)
            }
            Box(Modifier.width(0.25f.gridUnitsAsDp()))
            Box(Modifier.weight(1f)) {
                LightText(text = name, variant = LightTextVariant.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LightText(text = time, variant = LightTextVariant.Fine)
        }
    }
}

@Composable
private fun CallList() {
    val calls by CallLog.calls.collectAsState()
    val all by MessageStore.messages.collectAsState()
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    LaunchedEffect(calls.size) { calls.map { it.peer }.distinct().forEach { WaCore.resolve(it) } }
    val listState = rememberLazyListState()
    if (calls.isEmpty()) {
        StatusText("No calls yet.")
        return
    }
    LightLazyScrollView(uniformItemHeightGridUnits = 6f, listState = listState) {
        items(calls, key = { it.id }) { c ->
            val name = remember(names, phones, all, c.peer) { MessageStore.title(c.peer, all) }
            CallRow(
                name = name,
                summary = c.summary,
                time = formatRowTime(c.startedAt),
                // A missed call is the one thing worth flagging, with the same asterisk as unread chats.
                flagged = c.outcome == CallRecord.Outcome.MISSED,
                onClick = { WaCore.call(c.peer) },
                label = "Call $name back",
            )
        }
    }
}

@Composable
private fun CallRow(name: String, summary: String, time: String, flagged: Boolean, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = label, onClick = onClick)
            .padding(start = 0.5f.gridUnitsAsDp(), end = 0.5f.gridUnitsAsDp(), top = 10.dp, bottom = 10.dp),
    ) {
        // Marker and time ride on the name line, like the chat list; the summary sits under the name.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(1f.gridUnitsAsDp())) {
                if (flagged) LightText(text = "*", variant = LightTextVariant.Heading)
            }
            Box(Modifier.width(0.25f.gridUnitsAsDp()))
            Box(Modifier.weight(1f)) {
                LightText(text = name, variant = LightTextVariant.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            LightText(text = time, variant = LightTextVariant.Fine)
        }
        LightText(
            text = summary,
            variant = LightTextVariant.Detail,
            lighten = true,
            maxLines = 1,
            modifier = Modifier.padding(start = 1.25f.gridUnitsAsDp()),
        )
    }
}

// ---------------------------------------------------------------- tab bar

/** One bottom-bar slot. [selected] null = an action, not a destination. */
data class TabItem(
    val icon: Int,
    val description: String,
    val selected: Boolean? = null,
    val onClick: () -> Unit,
)

/**
 * App-local bottom bar: the SDK's [LightBottomBar] geometry (4 gu tall, 2 gu side padding, 2 gu
 * icons, start/centre/end slots) plus a selected state, which the SDK bar items don't have.
 * Unselected destinations render in contentSecondary; state is never carried by hue.
 */
@Composable
fun TabBar(items: List<TabItem>, modifier: Modifier = Modifier) {
    val colors = LightThemeTokens.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(4f.gridUnitsAsDp())
            .padding(horizontal = 2f.gridUnitsAsDp()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, item ->
            Box(
                Modifier.weight(1f),
                contentAlignment = when (i) {
                    0 -> Alignment.CenterStart
                    items.lastIndex -> Alignment.CenterEnd
                    else -> Alignment.Center
                },
            ) {
                Box(
                    Modifier
                        .height(4f.gridUnitsAsDp())
                        .lightClickable(
                            onClickLabel = item.description,
                            role = if (item.selected != null) androidx.compose.ui.semantics.Role.Tab else androidx.compose.ui.semantics.Role.Button,
                            onClick = item.onClick,
                        )
                        .semantics { if (item.selected != null) selected = item.selected },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        painter = androidx.compose.ui.res.painterResource(item.icon),
                        contentDescription = item.description,
                        tint = if (item.selected == false) colors.contentSecondary else colors.content,
                        modifier = Modifier.size(2f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- thread

@Composable
fun ThreadScreen(chat: String) {
    val all by MessageStore.messages.collectAsState()
    val error by WaState.error.collectAsState()
    val msgs = remember(all, chat) { all.filter { it.chat == chat } }
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    val title = remember(all, chat, names, phones) { MessageStore.title(chat, all) }
    val scrollState = rememberScrollState()
    val isGroup = chat.endsWith("@g.us")

    // Anything that arrives while the thread is open counts as read.
    val seenStatus by AppSettings.seenStatus.collectAsState()
    val connected by WaState.connected.collectAsState()
    LaunchedEffect(msgs.size, seenStatus, connected) { WaCore.sendReadReceipts(chat) }
    val tag = remember(msgs, isGroup) { if (isGroup) null else statusTag(msgs) }
    LaunchedEffect(msgs.size) {
        MessageStore.markRead(chat)
        delay(50)
        scrollState.scrollTo(scrollState.maxValue)
    }

    ScreenColumn {
        LightTopBar(
            leftButton = backButton("messages") { Nav.pop() },
            center = LightTopBarCenter.Text(title),
        )
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                error?.let { Banner(it) }
                Box(Modifier.weight(1f)) {
                    if (msgs.isEmpty()) {
                        StatusText("No messages yet.")
                    } else {
                        // Variable-height rows: a plain scroll view, not the lazy one.
                        LightScrollView(scrollState = scrollState) {
                            msgs.forEachIndexed { i, m ->
                                val prev = msgs.getOrNull(i - 1)
                                val showTime = prev == null || prev.fromMe != m.fromMe ||
                                    prev.sender != m.sender || m.ts - prev.ts > GROUP_GAP_MS
                                MessageRow(m, showTime = showTime, showSender = isGroup && showTime, tag = tag?.takeIf { it.first == m.id }?.second, chatTitle = title)
                            }
                            Box(Modifier.height(1f.gridUnitsAsDp()))
                        }
                    }
                }
            }
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            // Call (choose voice or video next) | attach a photo or video | write.
            // Groups can't be called here; the slot stays empty so the others keep their places.
            items = listOf(
                if (isGroup) null else icon(LightIcons.CALL, "Call $title") { Nav.push(Screen.CallType(chat)) },
                icon(LightIcons.ADD, "Send a photo or video to $title") {
                    MediaPicker.pick { uri -> if (uri != null) WaCore.sendMedia(chat, uri) }
                },
                icon(LightIcons.COMPOSE_MESSAGE, "Write a message to $title") { Nav.push(Screen.Composer(chat)) },
            ),
        )
    }
}

@Composable
private fun MessageRow(m: Msg, showTime: Boolean, showSender: Boolean, tag: String?, chatTitle: String) {
    // Outgoing sits right, incoming left, each capped at 7/8 of the row. Outgoing gets a
    // wider right buffer so it stays clear of the scrollbar.
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(
                start = 1.5f.gridUnitsAsDp(),
                end = if (m.fromMe) 2.5f.gridUnitsAsDp() else 1.5f.gridUnitsAsDp(),
                top = if (showTime) 8.dp else 3.dp,
                bottom = if (showTime) 8.dp else 3.dp,
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth(MESSAGE_WIDTH_FRACTION)
                .align(if (m.fromMe) Alignment.CenterEnd else Alignment.CenterStart),
            horizontalAlignment = if (m.fromMe) Alignment.End else Alignment.Start,
        ) {
            if (showSender && !m.fromMe && m.senderName.isNotBlank()) {
                LightText(text = m.senderName, variant = LightTextVariant.Detail)
            }
            // The time only leads a group; an in-flight message always says so.
            when {
                m.pending -> LightText(text = "SENDING", variant = LightTextVariant.Detail)
                showTime -> LightText(text = formatMessageTime(m.ts), variant = LightTextVariant.Detail)
            }
            if (m.media.isNotEmpty()) {
                // Media is the one place colour is allowed: shown as-is, tap for full screen.
                MediaThumb(m.media, onOpen = { Nav.push(Screen.MediaViewer(m.media, chatTitle)) })
                if (m.isVideo) LightText(text = "video", variant = LightTextVariant.Detail, lighten = true)
                if (m.caption.isNotEmpty()) {
                    LightText(text = m.caption, variant = LightTextVariant.Copy, align = if (m.fromMe) TextAlign.End else TextAlign.Start)
                }
            } else {
                LightText(
                    text = m.text,
                    variant = LightTextVariant.Copy,
                    align = if (m.fromMe) TextAlign.End else TextAlign.Start,
                )
            }
            // Receipt tag in place of ticks: one quiet word under the message it applies to.
            tag?.let { LightText(text = it, variant = LightTextVariant.Superfine, modifier = Modifier.padding(top = 1.dp)) }
        }
    }
}

/** Decodes [path] scaled down to about [maxSide] px, off the main thread; null until ready. */
@Composable
private fun rememberBitmap(path: String, maxSide: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val state = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path, maxSide) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
                android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    return state.value
}

/** A photo in a thread row: 60% of the row wide, its own aspect ratio, tap to open. */
@Composable
private fun MediaThumb(path: String, onOpen: () -> Unit) {
    val bmp = rememberBitmap(path, 720)
    if (bmp == null) {
        LightText(text = "photo", variant = LightTextVariant.Detail, lighten = true)
        return
    }
    Image(
        bitmap = bmp,
        contentDescription = "Photo, open full screen",
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth(0.7f)
            .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
            .lightClickable(onClickLabel = "Open photo", onClick = onOpen),
    )
}

/** Full-screen photo; back returns to the chat. */
@Composable
fun MediaViewerScreen(path: String, backTo: String) {
    val bmp = rememberBitmap(path, 2048)
    ScreenColumn {
        LightTopBar(leftButton = backButton(backTo) { Nav.pop() })
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (bmp == null) StatusText(LOADING_TEXT)
            else Image(
                bitmap = bmp,
                contentDescription = "Photo",
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}

/**
 * Where the single receipt tag goes in a 1:1 thread, and what it says (Chats' rules):
 * - only our newest message gets "sent"/"delivered";
 * - "seen" marks the newest message they've read, even if we sent more after it;
 * - their reply being the newest message drops the tag (a reply says they read it),
 *   except "seen" stays when they haven't read our newest message yet.
 */
private fun statusTag(msgs: List<Msg>): Pair<String, String>? {
    val newestMine = msgs.lastOrNull { it.fromMe && !it.pending } ?: return null
    val newestSeen = msgs.lastOrNull { it.fromMe && it.status >= Status.SEEN }
    val replyIsNewest = msgs.lastOrNull()?.fromMe == false
    val seen = if (newestSeen != null && newestSeen.id == newestMine.id && replyIsNewest) null
        else newestSeen?.let { it.id to "seen" }
    val progress = newestMine.takeIf { !replyIsNewest && it.status in Status.SENT..Status.DELIVERED }
        ?.let { it.id to Status.label(it.status)!! }
    return if (newestSeen?.id == newestMine.id) seen else progress ?: seen
}

// ---------------------------------------------------------------- composer / dial / new chat

private val keyboardOptions = MutableStateFlow(defaultKeyboardOptions())

@Composable
fun ComposerScreen(chat: String) {
    val all by MessageStore.messages.collectAsState()
    val names by MessageStore.names.collectAsState()
    val title = remember(all, chat, names) { MessageStore.title(chat, all) }
    val state = rememberTextFieldState(Drafts.get(chat))
    LaunchedEffect(state.text) { Drafts.set(chat, state.text.toString()) }
    LightTextInputEditor(
        title = title,
        state = state,
        keyboardOptionsFlow = keyboardOptions,
        onSubmit = { text ->
            val body = text.toString().trim()
            if (body.isNotEmpty()) {
                WaCore.sendText(chat, body)
                Drafts.set(chat, "")
                Nav.pop()
            }
        },
        onBack = { Nav.pop() },
        submitLabel = "Send",
        submitIcon = LightIcons.SEND,
        initialCaps = true,
    )
}

/** Unsent drafts survive leaving the composer; process-scoped. */
private object Drafts {
    private val map = mutableMapOf<String, String>()
    fun get(chat: String) = map[chat].orEmpty()
    fun set(chat: String, text: String) { if (text.isEmpty()) map.remove(chat) else map[chat] = text }
}

/**
 * Turns what the user typed into E.164 digits, using the phone's region for local numbers
 * ("07…" with region GB becomes "447…"). Returns null when it can't be a phone number.
 */
private fun normalizeNumber(raw: String): String? {
    val cleaned = raw.trim()
    if (cleaned.count { it.isDigit() } < 7) return null
    val region = java.util.Locale.getDefault().country.ifBlank { "GB" }
    val e164 = android.telephony.PhoneNumberUtils.formatNumberToE164(cleaned, region)
        ?: cleaned.filter { it.isDigit() || it == '+' }.takeIf { it.startsWith("+") }
    val digits = e164?.filter { it.isDigit() } ?: return null
    return digits.takeIf { it.length in 8..15 }
}

/** Number entry for someone not in contacts. Calls or opens a chat, depending on [purpose]. */
@Composable
fun NumberScreen(purpose: Purpose) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state = rememberTextFieldState("")
    LightTextInputEditor(
        title = "Enter a number",
        state = state,
        keyboardOptionsFlow = keyboardOptions,
        onSubmit = { text ->
            val digits = normalizeNumber(text.toString())
            when {
                digits == null -> android.widget.Toast
                    .makeText(context, "Enter the number with its country code", android.widget.Toast.LENGTH_SHORT).show()
                purpose == Purpose.CALL -> { WaCore.call(digits); Nav.pop() }
                else -> WaCore.canonicalChat(digits) { Nav.replaceTop(Screen.Thread(it)) }
            }
        },
        onBack = { Nav.pop() },
        submitLabel = if (purpose == Purpose.CALL) "Call" else "Open",
        submitIcon = if (purpose == Purpose.CALL) LightIcons.CALL else LightIcons.COMPOSE_MESSAGE,
        singleLine = true,
    )
}

// ---------------------------------------------------------------- contact picker

/** Pick someone to message or call. The first row is always "Enter a number". */
@Composable
fun ContactsScreen(purpose: Purpose) {
    val all by MessageStore.messages.collectAsState()
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    val calls by CallLog.calls.collectAsState()
    val contacts by WaState.contacts.collectAsState()
    val loaded by WaState.contactsLoaded.collectAsState()
    val connected by WaState.connected.collectAsState()
    LaunchedEffect(connected) { if (connected) WaCore.loadContacts() }

    // Saved contacts, plus anyone already chatted with or called who isn't a saved contact.
    val entries = remember(contacts, all, calls, names, phones) {
        val known = contacts.map { it.chat }.toSet()
        val others = (MessageStore.chats(all).map { it.first } + calls.map { it.peer })
            .distinct()
            .filter { it !in known && !it.endsWith("@g.us") }
            .map { Contact(it, MessageStore.title(it, all), phones[it].orEmpty()) }
            .filter { it.name != "Unknown contact" }
        // Named people alphabetically, bare numbers after them.
        (contacts + others).sortedWith(compareBy({ it.name.startsWith("+") }, { it.name.lowercase() }))
    }
    val listState = rememberLazyListState()
    val title = if (purpose == Purpose.CALL) "New call" else "New chat"
    val back = if (purpose == Purpose.CALL) "calls" else "messages"

    ScreenColumn {
        LightTopBar(
            leftButton = backButton(back) { Nav.pop() },
            center = LightTopBarCenter.Text(title),
        )
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                ContactRow(name = "Enter a number", subtitle = "for someone not listed") { Nav.replaceTop(Screen.Number(purpose)) }
                Box(Modifier.weight(1f)) {
                    when {
                        entries.isNotEmpty() -> LightLazyScrollView(uniformItemHeightGridUnits = 4.5f, listState = listState) {
                            items(entries, key = { it.chat }) { c ->
                                ContactRow(name = c.name, subtitle = null) {
                                    when (purpose) {
                                        Purpose.MESSAGE -> Nav.replaceTop(Screen.Thread(c.chat))
                                        Purpose.CALL -> { WaCore.call(c.chat); Nav.pop() }
                                    }
                                }
                            }
                        }
                        !loaded -> StatusText(LOADING_TEXT)
                        else -> StatusText("No contacts yet. WhatsApp hasn't synced them to this phone.")
                    }
                }
            }
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}

@Composable
private fun ContactRow(name: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = name, onClick = onClick)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 12.dp),
    ) {
        LightText(text = name, variant = LightTextVariant.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { LightText(text = it, variant = LightTextVariant.Detail, lighten = true) }
    }
}

// ---------------------------------------------------------------- call type / confirm

/** Full-screen choice between a voice and a video call, opened from a thread's call button. */
@Composable
fun CallTypeScreen(chat: String) {
    val all by MessageStore.messages.collectAsState()
    val names by MessageStore.names.collectAsState()
    val title = remember(all, chat, names) { MessageStore.title(chat, all) }
    ScreenColumn {
        LightTopBar(
            leftButton = backButton(title) { Nav.pop() },
            center = LightTopBarCenter.Text("Call"),
        )
        Column(Modifier.weight(1f).fillMaxWidth()) {
            ContactRow(name = "Voice call", subtitle = "audio only") { Nav.push(Screen.CallConfirm(chat, video = false)) }
            ContactRow(name = "Video call", subtitle = "your camera turns on") { Nav.push(Screen.CallConfirm(chat, video = true)) }
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}

/** Confirms before dialling (same grammar as the Chats confirm panel: question, then CALL). */
@Composable
fun CallConfirmScreen(chat: String, video: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val all by MessageStore.messages.collectAsState()
    val names by MessageStore.names.collectAsState()
    val title = remember(all, chat, names) { MessageStore.title(chat, all) }
    fun dial() {
        // Back to the thread first, so ending the call returns there, not to this screen.
        Nav.pop()
        Nav.pop()
        WaCore.call(chat, withVideo = video)
    }
    ScreenColumn {
        LightTopBar(leftButton = backButton("call options") { Nav.pop() })
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp()).padding(bottom = 4f.gridUnitsAsDp()),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LightText(
                    text = if (video) "Video call $title?" else "Call $title?",
                    variant = LightTextVariant.Subheading,
                    align = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = if (video) "your camera turns on" else "voice only",
                    variant = LightTextVariant.Detail,
                    lighten = true,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(
                LightBarButton.Text(
                    text = "CALL",
                    contentDescription = if (video) "Start video call with $title" else "Start voice call with $title",
                    onClick = {
                        if (!video) dial()
                        else CameraGate.withCamera { ok ->
                            if (ok) dial()
                            else android.widget.Toast.makeText(context, "Camera access is needed for video calls", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                ),
            ),
        )
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen() {
    val status by WaState.status.collectAsState()
    val own by WaState.ownNumber.collectAsState()
    val seenStatus by AppSettings.seenStatus.collectAsState()
    ScreenColumn {
        LightTopBar(
            leftButton = backButton(Nav.tab.collectAsState().value.label.lowercase()) { Nav.pop() },
            center = LightTopBarCenter.Text("Settings"),
        )
        Box(Modifier.weight(1f)) {
            LightScrollView {
                SettingRow("Linked account", if (own.isNotEmpty()) own else "not linked yet")
                SettingRow("Connection", status)
                ToggleRow(
                    checked = seenStatus,
                    title = "Seen Status",
                    subtitle = "let people see when you've read their messages",
                    onToggle = { AppSettings.setSeenStatus(!seenStatus) },
                )
            }
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}

/** Toggle row as in Chats: the LP3 switch glyph, then title (names the setting) and consequence. */
@Composable
private fun ToggleRow(checked: Boolean, title: String, subtitle: String, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .lightClickable(
                onClickLabel = if (checked) "Turn off $title" else "Turn on $title",
                role = androidx.compose.ui.semantics.Role.Switch,
                onClick = onToggle,
            )
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            com.thelightphone.sdk.ui.LightIcon(
                icon = if (checked) LightIcons.TOGGLE_STATE_ON else LightIcons.TOGGLE_STATE_OFF,
                size = 2f,
                contentDescription = if (checked) "$title on" else "$title off",
            )
        }
        Box(Modifier.width(12.dp))
        Column {
            LightText(text = title, variant = LightTextVariant.Heading)
            LightText(text = subtitle, variant = LightTextVariant.Detail, lighten = true)
        }
    }
}

/** Title names the setting; the lowercase subtitle states what it governs. */
@Composable
private fun SettingRow(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp(), vertical = 10.dp)) {
        LightText(text = title, variant = LightTextVariant.Heading)
        LightText(text = subtitle, variant = LightTextVariant.Detail, lighten = true)
    }
}

// ---------------------------------------------------------------- pairing

@Composable
fun PairingScreen(code: String) {
    val bmp = remember(code) { qrBitmap(code) }
    ScreenColumn {
        LightTopBar(center = LightTopBarCenter.Text("Link device"))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A QR needs its light quiet zone: black-on-black would not scan.
            Image(bmp.asImageBitmap(), contentDescription = "Pairing code", modifier = Modifier.size(15f.gridUnitsAsDp()))
            LightText(
                text = "Open WhatsApp on your other phone, then Settings, Linked devices, Link a device.",
                variant = LightTextVariant.Detail,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}

private fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) AColor.BLACK else AColor.WHITE)
    return bmp
}

// ---------------------------------------------------------------- calls

@Composable
fun IncomingCallScreen(call: CallUi, title: String) {
    ScreenColumn {
        LightTopBar(center = LightTopBarCenter.Text("WhatsApp"))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp()).padding(bottom = 4f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(
                text = title,
                variant = LightTextVariant.Subtitle,
                align = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = if (call.video) "incoming video call" else "incoming call",
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(
                icon(LightIcons.DENY, "Decline call from $title") { WaCore.reject() },
                if (call.video) icon(LightIcons.CAMERA, "Answer video call from $title") {
                    CameraGate.withCamera { ok -> WaCore.answer(withCamera = ok) }
                } else icon(LightIcons.CALL, "Answer call from $title") { WaCore.answer() },
            ),
        )
    }
}

@Composable
fun InCallScreen(call: CallUi, title: String) {
    // Tick once a second while connected so the duration stays live.
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.connectedAt) {
        while (call.connectedAt != 0L) {
            now.longValue = System.currentTimeMillis()
            delay(1000)
        }
    }
    val status = when {
        call.upgradeRequested -> "wants to switch to video"
        call.connectedAt != 0L && call.phase == 4 -> formatDuration(now.longValue - call.connectedAt)
        else -> call.phaseLabel
    }
    ScreenColumn {
        LightTopBar(center = LightTopBarCenter.Text("WhatsApp"))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp()).padding(bottom = 4f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(
                text = title,
                variant = LightTextVariant.Subtitle,
                align = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LightText(
                text = status,
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items = listOf(
                if (call.muted) icon(LightIcons.MUTE, "Unmute microphone") { WaCore.setMuted(false) }
                else icon(LightIcons.MICROPHONE, "Mute microphone") { WaCore.setMuted(true) },
                if (call.speaker) icon(LightIcons.SPEAKER_ON, "Switch to earpiece") { WaCore.setSpeaker(false) }
                else icon(LightIcons.SPEAKER_MUTED, "Switch to speaker") { WaCore.setSpeaker(true) },
                if (call.upgradeRequested) icon(LightIcons.CAMERA, "Accept video") {
                    CameraGate.withCamera { ok -> if (ok) WaCore.acceptVideo() }
                } else icon(LightIcons.CAMERA, "Switch to video") {
                    CameraGate.withCamera { ok -> if (ok) WaCore.startVideo() }
                },
                icon(LightIcons.DENY, "End call") { WaCore.hangup() },
            ),
        )
    }
}
