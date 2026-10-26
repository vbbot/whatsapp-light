package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Test

class CopyMessageTest {
    private fun msg(text: String) = Msg("1", "a@lid", "a@lid", "", text, 10, false, false)

    @Test fun copiesTextAsItIs() = assertEquals("hi there\nsecond line", msg("hi there\nsecond line").copyText)

    @Test fun copiesACaptionWithoutItsPlaceholder() {
        assertEquals("look", msg("[image] look").copyText)
        assertEquals("clip", msg("[video] clip").copyText)
    }

    @Test fun hasNothingToCopyWithoutWords() {
        assertEquals("", msg("[image] ").copyText)
        assertEquals("", msg("[voice message]").copyText)
        assertEquals("", msg("[sticker]").copyText)
        assertEquals("", msg(DELETED_TEXT).copyText)
    }
}
