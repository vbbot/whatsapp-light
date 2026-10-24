package vbh.ai.whatsapp

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.designVerticalPxToSp

/**
 * A reply's quote line, `↶ Author: what they said`, shared by the thread and the composer so a
 * pending reply reads exactly as it will once sent. No card or rule: the glyph and a dimmed,
 * smaller line carry it.
 */
object Replies {
    /** U+21B6. Not U+21A9, which can come back as a colour emoji. */
    const val GLYPH = "↶"

    /** The glyph comes from a fallback font and sits small in the line; this scales it up. */
    private const val GLYPH_SCALE = 1.6f

    const val ORIGINAL_MISSING = "original message not available"

    fun line(
        author: String,
        snippet: String,
    ): AnnotatedString =
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = GLYPH_SCALE.em)) { append(GLYPH) }
            append(" ")
            append(author)
            if (snippet.isNotEmpty()) append(": $snippet")
        }

    /** The quote line for reply [m]: the original if it's stored here, else WhatsApp's snapshot. */
    fun lineFor(m: Msg): AnnotatedString {
        val original = MessageStore.get(m.quoteId)?.takeUnless { it.isDeleted }
        val author = authorName(original?.let { if (it.fromMe) "me" else it.sender } ?: m.quoteSender, m.chat)
        val snippet = original?.let(::quoteSnippet) ?: snippet(m.quoteText).ifEmpty { ORIGINAL_MISSING }
        return line(author, snippet)
    }

    /** "You" for our own messages, else the contact or group member's name. */
    fun authorName(
        sender: String,
        chat: String,
    ): String =
        when {
            sender == "me" -> "You"

            sender.isNotEmpty() -> MessageStore.title(sender)

            // Not said: in a 1:1 chat it's most likely the other person.
            !chat.endsWith("@g.us") -> MessageStore.title(chat)

            else -> "Someone"
        }
}

/** A quote line: `Detail`, dimmed, one line with an ellipsis (as the thread and composer show it). */
@Composable
fun QuoteLine(
    line: AnnotatedString,
    modifier: Modifier = Modifier,
) {
    Text(text = line, style = detailStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/** The SDK's `Detail` at this screen's scale, in the dimmed colour (as `LightText(lighten = true)`). */
@Composable
private fun detailStyle(): TextStyle {
    val d = LightThemeTokens.typography.detail

    @Composable
    fun TextUnit.scaled() = if (this == TextUnit.Unspecified) this else value.designVerticalPxToSp()
    return d.copy(
        color = LightThemeTokens.colors.contentSecondary,
        fontSize = d.fontSize.scaled(),
        lineHeight = TextUnit.Unspecified,
        letterSpacing = d.letterSpacing.scaled(),
    )
}

/** What a message said, in one line: its text, or a noun for media without a caption. */
fun quoteSnippet(m: Msg): String = snippet(m.text)

/** walib's placeholders ("[image] caption", "[voice message]", …) as a quote shows them. */
fun snippet(raw: String): String {
    val nouns =
        listOf(
            "[image]" to "Photo",
            "[video]" to "Video",
            "[voice message]" to "Voice message",
            "[document]" to "Document",
            "[sticker]" to "Sticker",
        )
    for ((placeholder, noun) in nouns) {
        if (raw.startsWith(placeholder)) {
            return raw
                .removePrefix(placeholder)
                .trim()
                .ifEmpty { noun }
                .oneLine()
        }
    }
    return raw.oneLine()
}

private fun String.oneLine() = replace('\n', ' ').trim()
