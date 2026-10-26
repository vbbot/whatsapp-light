package vbh.ai.whatsapp

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LocalHapticsEnabled
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Light Phone's scroll wheel, when the "Scroll Wheel Navigation" setting is on. Turning it
 * moves a highlight row by row through the list on screen (the bars aren't part of it), and
 * pressing it does what the highlighted row's [wheelRow] says: open a chat, a message's options.
 *
 * The Luma launcher normally takes the wheel for the whole phone (brightness, and its keymap on
 * press), so the app claims it from Luma while a screen with a list is in front.
 */
object ScrollWheel {
    // LightOS's key codes for the wheel (LightDeviceKeys in light-keyboard).
    private const val TURN_UP = 317
    private const val TURN_DOWN = 318
    private const val PRESS = 319

    private const val LUMA = "com.vandam.luma"
    private const val ACTION_CLAIM = "com.vandam.luma.action.SCROLLWHEEL"

    /** The list or menu on screen that the wheel moves through, if any. */
    val list = MutableStateFlow<WheelTarget?>(null)

    /** Turns are handled one at a time, in order, however fast the wheel spins. */
    private val steps = Mutex()

    /** Asks Luma to pass the wheel to this app while it's in front ([on]), or to take it back. */
    fun claim(
        context: Context,
        on: Boolean,
    ) {
        context.sendBroadcast(
            Intent(ACTION_CLAIM)
                .setPackage(LUMA)
                .putExtra("package", context.packageName)
                .putExtra("claim", on),
        )
    }

    /** Handles a wheel key [event]; false when it isn't the wheel. */
    fun onKey(
        event: KeyEvent,
        scope: CoroutineScope,
    ): Boolean {
        val down =
            when (event.keyCode) {
                TURN_UP -> {
                    false
                }

                TURN_DOWN -> {
                    true
                }

                PRESS -> {
                    if (event.action == KeyEvent.ACTION_UP) press()
                    return true
                }

                else -> {
                    return false
                }
            }
        val l = list.value
        if (event.action == KeyEvent.ACTION_DOWN && l != null) scope.launch { steps.withLock { l.step(down) } }
        return true
    }

    private fun press() {
        list.value?.press()
    }

    /** A touch takes over from the wheel: the highlight goes. */
    fun touched() {
        list.value?.selected = null
    }
}

/** Rows the wheel moves a highlight through, and what pressing it does on each. */
abstract class WheelTarget {
    /** The highlighted row's key. */
    var selected by mutableStateOf<Any?>(null)

    /** What pressing the wheel does on each row on screen, by key. */
    val actions = HashMap<Any, () -> Unit>()

    /** Moves the highlight one row [down] (or up). */
    abstract suspend fun step(down: Boolean)

    fun press() {
        selected?.let { actions[it] }?.invoke()
    }
}

/** A short menu that fits the screen: its rows top to bottom, without scrolling. */
class WheelMenu : WheelTarget() {
    /** Each row's top on screen, for their order. */
    val tops = HashMap<Any, Float>()

    override suspend fun step(down: Boolean) {
        val rows = actions.keys.sortedBy { tops[it] ?: 0f }
        if (rows.isEmpty()) return
        val i = rows.indexOf(selected)
        selected =
            when {
                i < 0 -> if (down) rows.first() else rows.last()
                else -> rows[(i + if (down) 1 else -1).coerceIn(0, rows.lastIndex)]
            }
    }
}

/** A lazy list the wheel can move through, newest-at-the-bottom when [reverse] (a thread). */
class WheelList(
    val state: LazyListState,
    val reverse: Boolean,
    /** Items that aren't rows: spacers, banners. */
    val skip: Set<Any>,
) : WheelTarget() {
    /** Moves the highlight one row [down] (or up), scrolling at once to show all of it. */
    override suspend fun step(down: Boolean) {
        // Index order runs bottom to top in a reversed list.
        val dir = if (down != reverse) 1 else -1
        val rows = state.layoutInfo.visibleItemsInfo.filter { it.key !in skip }
        if (rows.isEmpty()) return
        val current = rows.firstOrNull { it.key == selected }
        var target =
            if (current == null) {
                // Start from the row at the edge the wheel moves away from.
                (if (dir > 0) rows.minBy { it.index } else rows.maxBy { it.index }).index
            } else {
                current.index + dir
            }
        repeat(MAX_TRIES) {
            val info = state.layoutInfo
            if (target !in 0 until info.totalItemsCount) return
            val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            if (item == null) {
                // Not laid out yet: scroll towards it by about a row, then look again.
                state.scrollBy(
                    dir * (
                        info.visibleItemsInfo
                            .map { it.size }
                            .average()
                            .toFloat()
                    ),
                )
                return@repeat
            }
            if (item.key in skip) {
                target += dir
                return@repeat
            }
            val start = info.viewportStartOffset
            val end = info.viewportEndOffset
            val by =
                if (dir > 0) {
                    // Show its far end, but never past its near end (a row taller than the screen).
                    minOf(item.offset + item.size - end, item.offset - start).coerceAtLeast(0)
                } else {
                    minOf(item.offset - start, 0)
                }
            if (by != 0) state.scrollBy(by.toFloat())
            selected = item.key
            return
        }
    }

    private companion object {
        const val MAX_TRIES = 6
    }
}

private val LocalWheelList = staticCompositionLocalOf<WheelTarget?> { null }

/** Makes [state]'s list the one the wheel moves through while [content] is on screen. */
@Composable
internal fun WheelListScope(
    state: LazyListState,
    reverse: Boolean = false,
    skip: Set<Any> = emptySet(),
    content: @Composable () -> Unit,
) {
    WheelScope(remember(state) { WheelList(state, reverse, skip) }, content)
}

/** Makes the [wheelRow]s in [content] a menu the wheel moves through, top to bottom. */
@Composable
internal fun WheelMenuScope(content: @Composable () -> Unit) {
    WheelScope(remember { WheelMenu() }, content)
}

@Composable
private fun WheelScope(
    list: WheelTarget,
    content: @Composable () -> Unit,
) {
    DisposableEffect(list) {
        ScrollWheel.list.value = list
        onDispose { if (ScrollWheel.list.value === list) ScrollWheel.list.value = null }
    }
    CompositionLocalProvider(LocalWheelList provides list) { content() }
}

/** A row of the wheel's list, item [key], outlined while highlighted; pressing the wheel runs [onPress]. */
@Composable
internal fun Modifier.wheelRow(
    key: Any,
    onPress: () -> Unit,
): Modifier {
    val list = LocalWheelList.current ?: return this
    val haptics = LocalHapticFeedback.current
    val hapticsOn = LocalHapticsEnabled.current
    val press by rememberUpdatedState(onPress)
    DisposableEffect(list, key) {
        list.actions[key] = {
            if (hapticsOn) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            press()
        }
        onDispose {
            list.actions.remove(key)
            if (list is WheelMenu) list.tops.remove(key)
        }
    }
    val outline = LightThemeTokens.colors.content
    val placed = if (list is WheelMenu) onGloballyPositioned { list.tops[key] = it.positionInRoot().y } else this
    return placed.drawWithContent {
        drawContent()
        if (list.selected == key) {
            val w = 2.dp.toPx()
            drawRect(outline, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), style = Stroke(w))
        }
    }
}
