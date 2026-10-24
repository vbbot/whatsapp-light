package vbh.ai.whatsapp

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
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
import kotlinx.coroutines.flow.conflate
import android.graphics.Color as AColor

internal const val LOADING_TEXT = "loading…"

// ---------------------------------------------------------------- shared pieces

/** Every screen paints true black explicitly; the theme does not. */
@Composable
internal fun ScreenColumn(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(LightThemeTokens.colors.background)) { content() }
}

@Composable
internal fun backButton(
    destination: String,
    onClick: () -> Unit,
) = LightBarButton.LightIcon(
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
internal fun Banner(text: String) {
    LightText(
        text = text,
        variant = LightTextVariant.Detail,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp(), vertical = 10.dp),
    )
}

internal fun icon(
    icon: com.thelightphone.sdk.ui.LightIconConfiguration,
    description: String,
    onClick: () -> Unit,
) = LightBarButton.LightIcon(icon = icon, onClick = onClick, contentDescription = description)

// ---------------------------------------------------------------- home (tabs)

/** How long the connection must stay down before the offline banner shows: reconnects usually take a second. */
private const val OFFLINE_BANNER_DELAY_MS = 5_000L

/** True once the connection has been down for [OFFLINE_BANNER_DELAY_MS]; false as soon as it's back. */
@Composable
internal fun rememberOffline(): Boolean {
    val connected by WaState.connected.collectAsState()
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(connected) {
        if (connected) {
            offline = false
        } else {
            delay(OFFLINE_BANNER_DELAY_MS)
            offline = true
        }
    }
    return offline
}

/**
 * Home: a list-home screen with two destinations. The top bar carries the active destination as
 * context and settings on the right; the bottom bar holds both destinations plus the active tab's
 * primary action (new chat / new call).
 */

@Composable
fun HomeScreen() {
    val tab by Nav.tab.collectAsState()
    val offline = rememberOffline()
    val error by WaState.error.collectAsState()
    ScreenColumn {
        LightTopBar(
            center = LightTopBarCenter.Text(tab.label),
            rightButton = icon(LightIcons.SETTINGS, "Settings") { Nav.push(Screen.Settings) },
        )
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                if (offline) Banner("Offline — reconnecting. Calls and sending are paused.")
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
            items =
                listOf(
                    TabItem(R.drawable.ic_messages, "Messages", selected = tab == Tab.MESSAGES) { Nav.tab.value = Tab.MESSAGES },
                    TabItem(LightIcons.CALL.drawableResource, "Calls", selected = tab == Tab.CALLS) { Nav.tab.value = Tab.CALLS },
                    when (tab) {
                        Tab.MESSAGES -> {
                            TabItem(
                                LightIcons.COMPOSE_MESSAGE.drawableResource,
                                "New chat",
                            ) { Nav.push(Screen.Contacts(Purpose.MESSAGE)) }
                        }

                        Tab.CALLS -> {
                            TabItem(LightIcons.DIALPAD.drawableResource, "New call") { Nav.push(Screen.Contacts(Purpose.CALL)) }
                        }
                    },
                ),
        )
    }
}

/** [MessageStore.title] for [chat], kept current as names and messages change. */
@Composable
fun rememberTitle(chat: String): String {
    val changes by MessageStore.changes.collectAsState()
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    // Starts from a direct read (a few indexed lookups), so the title never shows a placeholder first.
    val initial = remember(chat) { MessageStore.title(chat) }
    return rememberQuery(chat, changes, names, phones, initial = initial) { MessageStore.title(chat) } ?: initial
}

/** A call's title: the person's name, or for a group call its group or participants ([groupCallTitle]). */
@Composable
fun rememberCallTitle(c: CallUi): String {
    if (!c.groupCall) return rememberTitle(callPeer(c))
    val changes by MessageStore.changes.collectAsState()
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    val query = { groupCallTitle(c.group.takeIf { it.isNotEmpty() }?.let(MessageStore::title), c.members.map(MessageStore::title)) }
    val initial = remember(c.group, c.members) { query() }
    return rememberQuery(c.group, c.members, changes, names, phones, initial = initial, query = query) ?: initial
}

/** Who's in a group call now, by name; "" before anyone has joined, or for a 1:1 call. */
@Composable
fun rememberCallJoined(c: CallUi): String {
    if (!c.groupCall) return ""
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    return rememberQuery(c.joined, names, phones, initial = "") { groupCallJoined(c.joined.map(MessageStore::title)) } ?: ""
}

/** The caller of a group call that belongs to a group, by name; "" otherwise. */
@Composable
fun rememberGroupCaller(c: CallUi): String =
    if (c.groupCall && c.group.isNotEmpty() && c.members.isNotEmpty()) rememberTitle(c.members.first()) else ""

/** The caller's chat JID, so the title is a name or number, never a raw LID. */
fun callPeer(c: CallUi): String = if (c.peer.contains("@")) normalizeJid(c.peer) else "${c.peer.removePrefix("+")}@s.whatsapp.net"

/**
 * Runs [query] off the main thread and again whenever one of [keys] changes. Null until the first
 * result. A run always finishes: changes made meanwhile (history sync makes many) are folded into
 * one re-run after it, rather than cancelling it, which could starve the screen of any result.
 */
@Composable
fun <T> rememberQuery(
    vararg keys: Any?,
    initial: T? = null,
    query: () -> T,
): T? {
    val currentKeys = androidx.compose.runtime.rememberUpdatedState(keys.toList())
    val currentQuery = androidx.compose.runtime.rememberUpdatedState(query)
    val state =
        androidx.compose.runtime.produceState(initial) {
            androidx.compose.runtime
                .snapshotFlow { currentKeys.value }
                .conflate()
                .collect { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { currentQuery.value() } }
        }
    return state.value
}

@Composable
private fun CallList() {
    val calls by CallLog.calls.collectAsState()
    val changes by MessageStore.changes.collectAsState()
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    LaunchedEffect(
        calls.size,
    ) { WaCore.resolveAll((calls.map { it.peer } + calls.flatMap { it.members }).filterNot { it.endsWith("@g.us") }) }
    val titles =
        rememberQuery(calls, changes, names, phones) {
            calls.map { it.peer }.distinct().associateWith { MessageStore.title(it) } +
                calls.filter { it.isGroup }.associate { it.id to callLogTitle(it) }
        }.orEmpty()
    val listState = rememberLazyListState()
    if (calls.isEmpty()) {
        StatusText("No calls yet.")
        return
    }
    LightLazyScrollView(scrollBarPosition = LightScrollBarPosition.Inside, uniformItemHeightGridUnits = 6f, listState = listState) {
        items(calls, key = { it.id }) { c ->
            val name = (if (c.isGroup) titles[c.id] else titles[c.peer]) ?: MessageStore.fallbackTitle(c.peer, phones[c.peer])
            CallRow(
                name = name,
                summary = c.summary,
                time = formatRowTime(c.startedAt),
                // A missed call is the one thing worth flagging, with the same asterisk as unread chats.
                flagged = c.outcome == CallRecord.Outcome.MISSED,
                // Group calls can't be made from here yet: open the group's (or the caller's) chat.
                onClick = { if (c.isGroup) Nav.push(Screen.Thread(c.peer)) else WaCore.call(c.peer) },
                label = if (c.isGroup) "Open chat with $name" else "Call $name back",
            )
        }
    }
}

@Composable
private fun CallRow(
    name: String,
    summary: String,
    time: String,
    flagged: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = label, onClick = onClick)
            .padding(start = 0.5f.gridUnitsAsDp(), end = ROW_END_GU.gridUnitsAsDp(), top = 10.dp, bottom = 10.dp),
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
fun TabBar(
    items: List<TabItem>,
    modifier: Modifier = Modifier,
) {
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
                contentAlignment =
                    when (i) {
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
                            role =
                                if (item.selected !=
                                    null
                                ) {
                                    androidx.compose.ui.semantics.Role.Tab
                                } else {
                                    androidx.compose.ui.semantics.Role.Button
                                },
                            onClick = item.onClick,
                        ).semantics { if (item.selected != null) selected = item.selected },
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        painter =
                            androidx.compose.ui.res
                                .painterResource(item.icon),
                        contentDescription = item.description,
                        tint = if (item.selected == false) colors.contentSecondary else colors.content,
                        modifier = Modifier.size(2f.gridUnitsAsDp()),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- composer / dial / new chat

internal val keyboardOptions = MutableStateFlow(defaultKeyboardOptions())

/**
 * Turns what the user typed into E.164 digits, using the phone's region for local numbers
 * ("07…" with region GB becomes "447…"). Returns null when it can't be a phone number.
 */
private fun normalizeNumber(raw: String): String? {
    val cleaned = raw.trim()
    if (cleaned.count { it.isDigit() } < 7) return null
    val region =
        java.util.Locale
            .getDefault()
            .country
            .ifBlank { "GB" }
    val e164 =
        android.telephony.PhoneNumberUtils.formatNumberToE164(cleaned, region)
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
                digits == null -> {
                    android.widget.Toast
                        .makeText(context, "Enter the number with its country code", android.widget.Toast.LENGTH_SHORT)
                        .show()
                }

                purpose == Purpose.CALL -> {
                    WaCore.call(digits)
                    Nav.pop()
                }

                else -> {
                    WaCore.canonicalChat(digits) { Nav.replaceTop(Screen.Thread(it)) }
                }
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
    val changes by MessageStore.changes.collectAsState()
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    val calls by CallLog.calls.collectAsState()
    val contacts by WaState.contacts.collectAsState()
    val loaded by WaState.contactsLoaded.collectAsState()
    val connected by WaState.connected.collectAsState()
    LaunchedEffect(connected) { if (connected) WaCore.loadContacts() }

    // Saved contacts, plus anyone already chatted with or called who isn't a saved contact.
    val entries =
        rememberQuery(contacts, changes, calls, names, phones) {
            val known = contacts.map { it.chat }.toSet()
            val others =
                (MessageStore.chats().map { it.chat } + calls.map { it.peer })
                    .distinct()
                    .filter { it !in known && !it.endsWith("@g.us") }
                    .map { Contact(it, MessageStore.title(it), phones[it].orEmpty()) }
                    .filter { it.name != "Unknown contact" }
            // Named people alphabetically, bare numbers after them.
            (contacts + others).sortedWith(compareBy({ it.name.startsWith("+") }, { it.name.lowercase() }))
        } ?: contacts
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
                        entries.isNotEmpty() -> {
                            LightLazyScrollView(
                                scrollBarPosition = LightScrollBarPosition.Inside,
                                uniformItemHeightGridUnits = 4.5f,
                                listState = listState,
                            ) {
                                items(entries, key = { it.chat }) { c ->
                                    ContactRow(name = c.name, subtitle = null) {
                                        when (purpose) {
                                            Purpose.MESSAGE -> {
                                                Nav.replaceTop(Screen.Thread(c.chat))
                                            }

                                            Purpose.CALL -> {
                                                WaCore.call(c.chat)
                                                Nav.pop()
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        !loaded -> {
                            StatusText(LOADING_TEXT)
                        }

                        else -> {
                            StatusText("No contacts yet. WhatsApp hasn't synced them to this phone.")
                        }
                    }
                }
            }
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}

@Composable
private fun ContactRow(
    name: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightClickable(onClickLabel = name, onClick = onClick)
            .padding(start = 2f.gridUnitsAsDp(), end = ROW_END_GU.gridUnitsAsDp(), top = 12.dp, bottom = 12.dp),
    ) {
        LightText(text = name, variant = LightTextVariant.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { LightText(text = it, variant = LightTextVariant.Detail, lighten = true) }
    }
}

// ---------------------------------------------------------------- call type / confirm

/** Full-screen choice between a voice and a video call, opened from a thread's call button. */
@Composable
fun CallTypeScreen(chat: String) {
    val title = rememberTitle(chat)
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
fun CallConfirmScreen(
    chat: String,
    video: Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val title = rememberTitle(chat)

    fun dial() {
        // Back to the thread first, so ending the call returns there, not to this screen.
        Nav.pop()
        Nav.pop()
        WaCore.call(chat, withVideo = video)
    }
    ScreenColumn {
        LightTopBar(leftButton = backButton("call options") { Nav.pop() })
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
            items =
                listOf(
                    LightBarButton.Text(
                        text = "CALL",
                        contentDescription = if (video) "Start video call with $title" else "Start voice call with $title",
                        onClick = {
                            if (!video) {
                                dial()
                            } else {
                                CameraGate.withCamera { ok ->
                                    if (ok) {
                                        dial()
                                    } else {
                                        android.widget.Toast
                                            .makeText(
                                                context,
                                                "Camera access is needed for video calls",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                    }
                                }
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
    val granted by Permissions.granted.collectAsState()
    val missing = Perm.entries.count { it !in granted }
    ScreenColumn {
        LightTopBar(
            leftButton =
                backButton(
                    Nav.tab
                        .collectAsState()
                        .value.label
                        .lowercase(),
                ) { Nav.pop() },
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
                // Only while something the app asks for isn't allowed.
                if (missing > 0) {
                    SettingRow("Permissions", "$missing not allowed") { Nav.push(Screen.Permissions) }
                }
                if (own.isNotEmpty()) {
                    SettingRow("Log out", "unlink this phone and delete its messages") { Nav.push(Screen.LogOut) }
                }
            }
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}

/** Toggle row as in Chats: the LP3 switch glyph, then title (names the setting) and consequence. */
@Composable
internal fun ToggleRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .lightClickable(
                onClickLabel = if (checked) "Turn off $title" else "Turn on $title",
                role = androidx.compose.ui.semantics.Role.Switch,
                onClick = onToggle,
            ).padding(horizontal = 2f.gridUnitsAsDp(), vertical = 0.75f.gridUnitsAsDp()),
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
private fun SettingRow(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.lightClickable(onClickLabel = "Open $title", onClick = onClick) else Modifier)
            .padding(horizontal = 2f.gridUnitsAsDp(), vertical = 10.dp),
    ) {
        LightText(text = title, variant = LightTextVariant.Heading)
        LightText(text = subtitle, variant = LightTextVariant.Detail, lighten = true)
    }
}

// ---------------------------------------------------------------- pairing

@Composable
fun PairingScreen(code: String?) {
    val notice by WaState.loggedOutNotice.collectAsState()
    val bmp = remember(code) { code?.let { qrBitmap(it) } }
    ScreenColumn {
        LightTopBar(center = LightTopBarCenter.Text("Link device"))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 2f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Why the last session ended (removed on the main phone), above the new code.
            notice?.let {
                LightText(
                    text = it,
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 1.5f.gridUnitsAsDp()),
                )
            }
            if (bmp != null) {
                // A QR needs its light quiet zone: black-on-black would not scan.
                Image(bmp.asImageBitmap(), contentDescription = "Pairing code", modifier = Modifier.size(15f.gridUnitsAsDp()))
            } else {
                Box(Modifier.size(15f.gridUnitsAsDp()), contentAlignment = Alignment.Center) {
                    LightText(text = "getting a code…", variant = LightTextVariant.Detail, lighten = true)
                }
            }
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

private fun qrBitmap(
    text: String,
    size: Int = 600,
): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) AColor.BLACK else AColor.WHITE)
    return bmp
}

// ---------------------------------------------------------------- calls

@Composable
fun IncomingCallScreen(
    call: CallUi,
    title: String,
) {
    val error by WaState.error.collectAsState()
    ScreenColumn {
        LightTopBar(center = LightTopBarCenter.Text("WhatsApp"))
        error?.let { Banner(it) }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp())
                .padding(bottom = 4f.gridUnitsAsDp()),
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
            val caller = rememberGroupCaller(call)
            if (caller.isNotEmpty()) {
                LightText(
                    text = "$caller is calling",
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LightText(
                text = incomingLabel(call),
                variant = LightTextVariant.Heading,
                align = TextAlign.Center,
                modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
            )
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items =
                listOf(
                    icon(LightIcons.DENY, "Decline call from $title") { WaCore.reject() },
                    if (call.video) {
                        icon(LightIcons.CAMERA, "Answer video call from $title") {
                            CameraGate.withCamera { ok -> WaCore.answer(withCamera = ok) }
                        }
                    } else {
                        icon(LightIcons.CALL, "Answer call from $title") { WaCore.answer() }
                    },
                ),
        )
    }
}

@Composable
fun InCallScreen(
    call: CallUi,
    title: String,
) {
    // Tick once a second while connected so the duration stays live.
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.connectedAt) {
        while (call.connectedAt != 0L) {
            now.longValue = System.currentTimeMillis()
            delay(1000)
        }
    }
    val status =
        when {
            call.upgradeRequested -> "wants to switch to video"
            call.connectedAt != 0L && call.phase == 4 -> formatDuration(now.longValue - call.connectedAt)
            else -> call.phaseLabel
        }
    ScreenColumn {
        LightTopBar(center = LightTopBarCenter.Text("WhatsApp"))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 2f.gridUnitsAsDp())
                .padding(bottom = 4f.gridUnitsAsDp()),
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
            val joined = rememberCallJoined(call)
            if (joined.isNotEmpty()) {
                LightText(
                    text = "with $joined",
                    variant = LightTextVariant.Paragraph,
                    align = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1f.gridUnitsAsDp()),
                )
            }
        }
        LightBottomBar(
            modifier = Modifier.navigationBarsPadding(),
            items =
                listOf(
                    if (call.muted) {
                        icon(LightIcons.MUTE, "Unmute microphone") { WaCore.setMuted(false) }
                    } else {
                        icon(LightIcons.MICROPHONE, "Mute microphone") { WaCore.setMuted(true) }
                    },
                    if (call.speaker) {
                        icon(LightIcons.SPEAKER_ON, "Switch to earpiece") { WaCore.setSpeaker(false) }
                    } else {
                        icon(LightIcons.SPEAKER_MUTED, "Switch to speaker") { WaCore.setSpeaker(true) }
                    },
                    if (call.upgradeRequested) {
                        icon(LightIcons.CAMERA, "Accept video") {
                            CameraGate.withCamera { ok -> if (ok) WaCore.acceptVideo() }
                        }
                    } else {
                        icon(LightIcons.CAMERA, "Switch to video") {
                            CameraGate.withCamera { ok -> if (ok) WaCore.startVideo() }
                        }
                    },
                    icon(LightIcons.DENY, "End call") { WaCore.hangup() },
                ),
        )
    }
}
