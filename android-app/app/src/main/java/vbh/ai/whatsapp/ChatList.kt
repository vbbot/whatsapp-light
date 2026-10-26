package vbh.ai.whatsapp

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightLazyScrollView
import com.thelightphone.sdk.ui.LightScrollBarPosition
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.launch

/** End padding of list rows: 0.5 gu of space plus the 2 gu track of the scrollbar drawn over the list. */
internal const val ROW_END_GU = 2.5f

/** Height of a chat row in grid units (also the scrollbar's per-row estimate). */
private const val ROW_GU = 4.5f

/** Height of the pull-down cue above the first row. */
private const val CUE_GU = 0.75f

/** A pull is slowed to this fraction of the finger's travel, so the row feels attached but heavy. */
private const val PULL_DRAG = 0.5f

private var lastChatRows: List<Pair<ChatSummary, String>>? = null

/**
 * The chat list. Archived chats are left out; when there are any, a small down arrow sits above
 * the first row, and pulling down at the top draws out an "Archived" row (see [ArchivedReveal]).
 */
@Composable
internal fun ChatList() {
    val changes by MessageStore.changes.collectAsState()
    val names by MessageStore.names.collectAsState()
    val phones by MessageStore.phones.collectAsState()
    val connected by WaState.connected.collectAsState()
    val syncing by History.syncing.collectAsState()
    // Starts from the last result, so coming back to the list doesn't flash empty.
    val rows =
        rememberQuery(changes, names, phones, initial = lastChatRows) {
            MessageStore.chats().map { it to MessageStore.title(it.chat) }.also { lastChatRows = it }
        }
    val archived = rememberQuery(changes, initial = 0) { MessageStore.archivedCount() } ?: 0
    val listState = rememberLazyListState()
    when {
        rows == null -> {}

        rows.isNotEmpty() || archived > 0 -> {
            Column(Modifier.fillMaxSize()) {
                syncing?.let {
                    Banner(if (it > 0) "Syncing from your phone · %,d messages".format(it) else "Syncing messages from your phone…")
                }
                Box(Modifier.weight(1f)) {
                    ChatRows(rows, listState, archivedCount = archived)
                }
            }
        }

        syncing != null -> {
            StatusText("Syncing messages from your phone…")
        }

        !connected -> {
            StatusText(LOADING_TEXT)
        }

        else -> {
            StatusText("No conversations. Tap the pencil to start one.")
        }
    }
}

/**
 * A community's groups, from its row in the chat list: its announcements group first, then the
 * others newest first. Each is a normal chat row (long-press for its options).
 */
@Composable
fun CommunityScreen(community: String) {
    val changes by MessageStore.changes.collectAsState()
    val names by MessageStore.names.collectAsState()
    val title = rememberTitle(community)
    val rows =
        rememberQuery(community, changes, names) { MessageStore.communityGroups(community).map { it to MessageStore.title(it.chat) } }
    ScreenColumn {
        LightTopBar(
            leftButton = backButton("messages") { Nav.pop() },
            center = LightTopBarCenter.Text(title),
        )
        Box(Modifier.weight(1f)) {
            when {
                rows == null -> {}

                rows.isEmpty() -> {
                    StatusText("No groups of this community have messages here.")
                }

                else -> {
                    ChatRows(rows, rememberLazyListState(), archivedCount = 0)
                }
            }
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}

/** Archived chats, opened from the chat list's pull-down row. */
@Composable
fun ArchivedScreen() {
    val changes by MessageStore.changes.collectAsState()
    val names by MessageStore.names.collectAsState()
    val rows = rememberQuery(changes, names) { MessageStore.chats(archived = true).map { it to MessageStore.title(it.chat) } }
    ScreenColumn {
        LightTopBar(
            leftButton = backButton("messages") { Nav.pop() },
            center = LightTopBarCenter.Text("Archived"),
        )
        Box(Modifier.weight(1f)) {
            when {
                rows == null -> {}

                rows.isEmpty() -> {
                    StatusText("No archived chats.")
                }

                else -> {
                    ChatRows(rows, rememberLazyListState(), archivedCount = 0)
                }
            }
        }
        Box(Modifier.navigationBarsPadding().height(1f.gridUnitsAsDp()))
    }
}

/** The rows of a chat list; with archived chats, the pull-down reveal sits above them. */
@Composable
private fun ChatRows(
    rows: List<Pair<ChatSummary, String>>,
    listState: LazyListState,
    archivedCount: Int,
) {
    val reveal = rememberArchivedReveal(listState, enabled = archivedCount > 0)
    // No stretch overscroll: at the top, pulling down reveals Archived instead.
    CompositionLocalProvider(LocalOverscrollFactory provides null) {
        LightLazyScrollView(
            modifier = Modifier.nestedScroll(reveal.connection),
            scrollBarPosition = LightScrollBarPosition.Inside,
            uniformItemHeightGridUnits = ROW_GU,
            listState = listState,
        ) {
            if (archivedCount > 0) archivedHeader(reveal, archivedCount)
            items(rows, key = { it.first.chat }) { (summary, name) ->
                ChatRow(
                    name = name,
                    time = formatRowTime(summary.last.ts),
                    unread = summary.unread,
                    muted = summary.muted,
                    pinned = summary.pinned,
                    community = summary.isCommunity,
                    onOpen = { Nav.push(if (summary.isCommunity) Screen.Community(summary.chat) else Screen.Thread(summary.chat)) },
                    // A community has no options of its own: its groups each have theirs.
                    onOptions = { Nav.push(if (summary.isCommunity) Screen.Community(summary.chat) else Screen.ChatOptions(summary.chat)) },
                )
            }
        }
    }
}

/**
 * The list's first item when there are archived chats. Closed, it's the cue: a small down
 * arrow. Pulled, it grows with the finger and shows the Archived row coming down from under the
 * top bar (anchored to its bottom edge).
 */
private fun LazyListScope.archivedHeader(
    reveal: ArchivedReveal,
    count: Int,
) {
    item(key = "archived") {
        val density = LocalDensity.current
        val pulled = with(density) { reveal.offset.value.toDp() }
        val cue = CUE_GU.gridUnitsAsDp()
        Box(
            Modifier
                .fillMaxWidth()
                .height(maxOf(cue, pulled))
                .clipToBounds(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (pulled < cue) {
                LightIcon(LightIcons.DOWN, size = 0.6f, contentDescription = "Pull down for archived chats")
            } else {
                Box(Modifier.requiredHeight(ROW_GU.gridUnitsAsDp())) {
                    ArchivedRow(count) { Nav.push(Screen.Archived) }
                }
            }
        }
    }
}

@Composable
private fun ArchivedRow(
    count: Int,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .lightPressable(onTapLabel = "Open archived chats", onTap = onOpen)
            .padding(start = 1.75f.gridUnitsAsDp(), end = ROW_END_GU.gridUnitsAsDp(), top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { LightText(text = "Archived", variant = LightTextVariant.Heading) }
        LightText(text = "$count", variant = LightTextVariant.Fine)
    }
}

/**
 * State of the pull-down Archived row: [offset] is how far it's drawn out, in px. A pull at
 * the top of the list (drag the list itself doesn't use) draws it out at [PULL_DRAG] speed, up
 * to a full row; on release it settles open past halfway, closed otherwise. Scrolling down
 * closes it before the list moves.
 */
private class ArchivedReveal(
    val offset: Animatable<Float, *>,
    val connection: NestedScrollConnection,
)

@Composable
private fun rememberArchivedReveal(
    listState: LazyListState,
    enabled: Boolean,
): ArchivedReveal {
    val scope = rememberCoroutineScope()
    val rowPx = with(LocalDensity.current) { ROW_GU.gridUnitsAsDp().toPx() }
    val isEnabled by rememberUpdatedState(enabled)
    return remember(listState, rowPx) {
        val offset = Animatable(0f)
        val atTop = { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }

        suspend fun settle() = offset.animateTo(if (offset.value >= rowPx / 2) rowPx else 0f)
        val connection =
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Scrolling down (finger up) closes the row first.
                    if (available.y >= 0 || offset.value <= 0f) return Offset.Zero
                    val next = (offset.value + available.y).coerceAtLeast(0f)
                    val used = next - offset.value
                    scope.launch { offset.snapTo(next) }
                    return Offset(0f, used)
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    // Downward drag the list couldn't use: it's at the top, so draw the row out.
                    if (!isEnabled || source != NestedScrollSource.UserInput || available.y <= 0f || !atTop()) return Offset.Zero
                    val next = (offset.value + available.y * PULL_DRAG).coerceAtMost(rowPx)
                    scope.launch { offset.snapTo(next) }
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (offset.value in 0.001f..rowPx - 0.001f) {
                        settle()
                        return available
                    }
                    return Velocity.Zero
                }
            }
        ArchivedReveal(offset, connection)
    }
}

/** One chat in a list: unread `*`, name, community, pin and muted markers, time. Tap opens it; long-press opens its options. */
@Composable
private fun ChatRow(
    name: String,
    time: String,
    unread: Boolean,
    muted: Boolean,
    pinned: Boolean,
    community: Boolean = false,
    onOpen: () -> Unit,
    onOptions: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .lightPressable(
                onTapLabel = if (community) "Open community $name" else "Open chat with $name",
                onTap = onOpen,
                onLongPressLabel = "Options for $name",
                onLongPress = onOptions,
            )
            // 12dp vertical padding fits six rows on the panel. The list's scrollbar is drawn over
            // the rows, so the end leaves 0.5 gu plus its 2 gu track.
            .padding(start = 0.5f.gridUnitsAsDp(), end = ROW_END_GU.gridUnitsAsDp(), top = 12.dp, bottom = 12.dp),
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
            Row(horizontalArrangement = Arrangement.spacedBy(0.25f.gridUnitsAsDp()), verticalAlignment = Alignment.CenterVertically) {
                // A community holds several chats: a list mark, where a pin or mute would sit.
                if (community) LightIcon(LightIcons.LIST, size = MUTED_ICON_GU, contentDescription = "Community")
                if (pinned) PinMarker()
                if (muted) LightIcon(LightIcons.SPEAKER_MUTED, size = MUTED_ICON_GU, contentDescription = "Muted")
                LightText(text = time, variant = LightTextVariant.Fine)
            }
        }
    }
}

/** The muted icon's size: about the height of the row's `Fine` time. */
private const val MUTED_ICON_GU = 0.9f

/** A small outline pushpin (the SDK has none), the size of the muted icon, in the text colour. */
@Composable
private fun PinMarker() {
    Image(
        painter = painterResource(R.drawable.ic_pin),
        contentDescription = "Pinned",
        colorFilter = ColorFilter.tint(LightThemeTokens.colors.content),
        modifier = Modifier.size(MUTED_ICON_GU.gridUnitsAsDp()),
    )
}

/**
 * Tap and, optionally, long-press, with the Light haptic on each (when the user has haptics on)
 * and both actions exposed to accessibility services.
 */
@Composable
internal fun Modifier.lightPressable(
    onTapLabel: String,
    onTap: () -> Unit,
    onLongPressLabel: String? = null,
    onLongPress: (() -> Unit)? = null,
    wheelPressOpensOptions: Boolean = false,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)

    fun buzz(type: HapticFeedbackType) {
        if (hapticsOn) haptics.performHapticFeedback(type)
    }
    return this
        // Reachable with the scroll wheel: pressing it taps, or long-presses with [wheelPressOpensOptions].
        .onKeyEvent { e ->
            if (e.key != Key.DirectionCenter && e.key != Key.Enter) return@onKeyEvent false
            if (e.type == KeyEventType.KeyUp) {
                val press = if (wheelPressOpensOptions) longPress else tap
                if (press != null) {
                    buzz(HapticFeedbackType.LongPress)
                    press()
                }
            }
            true
        }.focusable()
        .semantics {
            onClick(onTapLabel) {
                tap()
                true
            }
            if (onLongPress != null) {
                onLongClick(onLongPressLabel) {
                    longPress?.invoke()
                    true
                }
            }
        }.pointerInput(onLongPress != null) {
            detectTapGestures(
                onTap = {
                    buzz(HapticFeedbackType.ContextClick)
                    tap()
                },
                onLongPress =
                    longPress?.let {
                        {
                            buzz(HapticFeedbackType.LongPress)
                            it()
                        }
                    },
            )
        }
}
