package vbh.ai.whatsapp

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatIdentityTest {
    private lateinit var db: SQLiteDatabase

    // Fictional addresses: one person reachable by number or private ID, and one only by number.
    private val number = "15550001111@s.whatsapp.net"
    private val id = "99990001@lid"
    private val unmapped = "15550002222@s.whatsapp.net"
    private val group = "120363000000000000@g.us"

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        CallLog.open(db)
        ChatIdentity.canonical = { if (it == number) id else it }
    }

    @After fun tearDown() {
        ChatIdentity.canonical = null
        db.close()
    }

    private fun m(
        msgId: String,
        chat: String,
        ts: Long,
        sender: String = chat,
    ) = Msg(msgId, chat, sender, "", "hi", ts, false, chat.endsWith("@g.us"))

    @Test fun mergesTheNumberChatIntoThePrivateIdChat() {
        MessageStore.addAll(listOf(m("old1", number, 10), m("old2", number, 20), m("new1", id, 30)))
        MessageStore.markReadExceptNewest(number, 0)
        MessageStore.setChatName(number, "From history")

        assertEquals(setOf(id), ChatIdentity.mergeDuplicates())

        assertEquals(listOf(id), MessageStore.chats().map { it.chat })
        val thread = MessageStore.thread(id, 10)
        assertEquals(listOf("old1", "old2", "new1"), thread.map { it.id })
        assertTrue(thread.all { it.sender == id })
        // Read up to the old chat's newest message; the newer one stays unread.
        assertTrue(MessageStore.chats().single().unread)
        assertEquals("From history", MessageStore.title(id))
        assertFalse(number in MessageStore.knownChats())
    }

    @Test fun keepsTheLaterReadPositionAndTheTargetsName() {
        MessageStore.addAll(listOf(m("a", number, 10), m("b", id, 30)))
        MessageStore.markReadExceptNewest(id, 0)
        MessageStore.setChatName(id, "Current")
        MessageStore.setChatName(number, "Old")
        ChatIdentity.mergeDuplicates()
        assertFalse(MessageStore.chats().single().unread)
        assertEquals("Current", MessageStore.title(id))
    }

    @Test fun movesCallsAndRewritesGroupSenders() {
        MessageStore.addAll(listOf(m("g1", group, 5, sender = number), m("x", number, 10)))
        CallLog.start("c1", number, incoming = true)
        ChatIdentity.mergeDuplicates()
        assertEquals(id, MessageStore.thread(group, 10).single().sender)
        assertEquals(listOf(id), CallLog.calls.value.map { it.peer })
    }

    @Test fun leavesUnmappedNumbersAndGroupsAloneAndRepeatsHarmlessly() {
        MessageStore.addAll(listOf(m("u", unmapped, 10), m("g", group, 20)))
        assertTrue(ChatIdentity.mergeDuplicates().isEmpty())
        assertEquals(setOf(unmapped, group), MessageStore.chats().map { it.chat }.toSet())

        MessageStore.add(m("x", number, 30))
        assertEquals(setOf(id), ChatIdentity.mergeDuplicates())
        assertTrue(ChatIdentity.mergeDuplicates().isEmpty())
    }
}
