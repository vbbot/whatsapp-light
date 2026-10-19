package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageStoreTest {
    private fun m(
        id: String,
        chat: String,
        ts: Long,
        fromMe: Boolean = false,
        name: String = "",
        group: Boolean = false,
    ) = Msg(id, chat, if (fromMe) "me" else chat, name, "hi", ts, fromMe, group)

    @Test fun chatsNewestFirst() {
        val all = listOf(m("1", "a@lid", 10), m("2", "b@lid", 30), m("3", "a@lid", 20))
        assertEquals(listOf("b@lid", "a@lid"), MessageStore.chats(all).map { it.first })
        assertEquals(
            "3",
            MessageStore
                .chats(all)
                .last()
                .second.id,
        )
    }

    @Test fun titleFallsBackFromPushNameToNumberToUnknown() {
        val withName = listOf(m("1", "x@lid", 1, name = "Daniel"))
        assertEquals("Daniel", MessageStore.title("x@lid", withName))
        assertEquals("+447700900123", MessageStore.title("447700900123@s.whatsapp.net", emptyList()))
        assertEquals("Unknown contact", MessageStore.title("999@lid", emptyList()))
        assertEquals("Group", MessageStore.title("1203@g.us", emptyList()))
    }

    @Test fun unreadOnlyCountsTheirNewerMessages() {
        val all = listOf(m("1", "a@lid", 10), m("2", "a@lid", 20, fromMe = true))
        assertTrue(MessageStore.hasUnread("a@lid", all, emptyMap()))
        assertFalse(MessageStore.hasUnread("a@lid", all, mapOf("a@lid" to 10L)))
    }

    @Test fun captionStripsPlaceholder() {
        val img = Msg("i", "a@lid", "a@lid", "", "[image] look", 1, false, false)
        assertEquals("look", img.caption)
        assertFalse(img.isVideo)
        assertTrue(img.copy(text = "[video]").isVideo)
    }
}
