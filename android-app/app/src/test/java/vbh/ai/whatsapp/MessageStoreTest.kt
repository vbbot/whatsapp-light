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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageStoreTest {
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        MessageStore.names.value = emptyMap()
        MessageStore.phones.value = emptyMap()
    }

    @After fun tearDown() = db.close()

    private fun m(
        id: String,
        chat: String,
        ts: Long,
        fromMe: Boolean = false,
        name: String = "",
        group: Boolean = false,
    ) = Msg(id, chat, if (fromMe) "me" else chat, name, "hi", ts, fromMe, group)

    @Test fun chatsNewestFirst() {
        MessageStore.addAll(listOf(m("1", "a@lid", 10), m("2", "b@lid", 30), m("3", "a@lid", 20)))
        val chats = MessageStore.chats()
        assertEquals(listOf("b@lid", "a@lid"), chats.map { it.chat })
        assertEquals("3", chats.last().last.id)
    }

    @Test fun pendingMessageShowsUntilRemoved() {
        MessageStore.add(m("pending-1", "new@lid", 5, fromMe = true).copy(pending = true))
        assertEquals(listOf("new@lid"), MessageStore.chats().map { it.chat })
        assertEquals(1, MessageStore.thread("new@lid", 10).size)
        MessageStore.remove("pending-1")
        assertTrue(MessageStore.chats().isEmpty())
    }

    @Test fun titleFallsBackFromChatNameToPushNameToNumberToUnknown() {
        MessageStore.add(m("1", "x@lid", 1, name = "Daniel"))
        assertEquals("Daniel", MessageStore.title("x@lid"))
        MessageStore.setChatName("x@lid", "Dan")
        assertEquals("Dan", MessageStore.title("x@lid"))
        MessageStore.names.value = mapOf("x@lid" to "Daniel Smith")
        assertEquals("Daniel Smith", MessageStore.title("x@lid"))
        assertEquals("+447700900123", MessageStore.title("447700900123@s.whatsapp.net"))
        assertEquals("Unknown contact", MessageStore.title("999@lid"))
        assertEquals("Group", MessageStore.title("1203@g.us"))
    }

    @Test fun unreadOnlyCountsTheirNewerMessages() {
        MessageStore.addAll(listOf(m("1", "a@lid", 10), m("2", "a@lid", 20, fromMe = true)))
        assertTrue(MessageStore.chats().single().unread)
        MessageStore.markRead("a@lid")
        assertFalse(MessageStore.chats().single().unread)
        MessageStore.add(m("3", "a@lid", 30))
        assertTrue(MessageStore.chats().single().unread)
    }

    @Test fun markReadExceptNewestLeavesThatManyUnread() {
        MessageStore.addAll(listOf(m("1", "a@lid", 10), m("2", "a@lid", 20), m("3", "a@lid", 30)))
        MessageStore.markReadExceptNewest("a@lid", 2)
        assertTrue(MessageStore.chats().single().unread)
        MessageStore.markReadExceptNewest("a@lid", 0)
        assertFalse(MessageStore.chats().single().unread)
    }

    @Test fun redeliveryKeepsProgress() {
        MessageStore.add(m("1", "a@lid", 10, fromMe = true).copy(status = Status.SENT, media = "/p.jpg"))
        MessageStore.applyReceipt(setOf("1"), Status.SEEN)
        MessageStore.add(m("1", "a@lid", 10, fromMe = true).copy(status = Status.SENT))
        val got = MessageStore.thread("a@lid", 10).single()
        assertEquals(Status.SEEN, got.status)
        assertEquals("/p.jpg", got.media)
        // Receipts never move status backwards.
        MessageStore.applyReceipt(setOf("1"), Status.DELIVERED)
        assertEquals(Status.SEEN, MessageStore.thread("a@lid", 10).single().status)
    }

    @Test fun receiptBeforeMessageIsApplied() {
        MessageStore.applyReceipt(setOf("1"), Status.DELIVERED)
        MessageStore.add(m("1", "a@lid", 10, fromMe = true).copy(status = Status.SENT))
        assertEquals(Status.DELIVERED, MessageStore.thread("a@lid", 10).single().status)
    }

    @Test fun threadPagesNewestOldestFirst() {
        MessageStore.addAll((1..250).map { m("$it", "a@lid", it.toLong()) })
        val page = MessageStore.thread("a@lid", 100)
        assertEquals(100, page.size)
        assertEquals("151", page.first().id)
        assertEquals("250", page.last().id)
        assertEquals(250, MessageStore.count("a@lid"))
        assertEquals("1", MessageStore.oldest("a@lid")?.id)
    }

    @Test fun needingReadReceiptSkipsSentAndOwn() {
        MessageStore.addAll(listOf(m("1", "a@lid", 10).copy(readSent = true), m("2", "a@lid", 20), m("3", "a@lid", 30, fromMe = true)))
        assertEquals(listOf("2"), MessageStore.needingReadReceipt("a@lid").map { it.id })
        MessageStore.markReadSent(setOf("2"))
        assertTrue(MessageStore.needingReadReceipt("a@lid").isEmpty())
    }

    @Test fun migratesLegacyJson() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "messages.json").writeText(
            """[{"id":"1","chat":"a@lid","sender":"a@lid","senderName":"Ann","text":"old","ts":5,"fromMe":false,"isGroup":false,"readSent":true}]""",
        )
        context
            .getSharedPreferences("read", 0)
            .edit()
            .putLong("a@lid", 5L)
            .commit()
        MessageStore.migrateLegacyFiles(context)
        val chat = MessageStore.chats().single()
        assertEquals("old", chat.last.text)
        assertFalse(chat.unread)
        assertFalse(File(context.filesDir, "messages.json").exists())
        assertTrue(File(context.filesDir, "messages.json.migrated").exists())
    }

    @Test fun captionStripsPlaceholder() {
        val img = Msg("i", "a@lid", "a@lid", "", "[image] look", 1, false, false)
        assertEquals("look", img.caption)
        assertFalse(img.isVideo)
        assertTrue(img.copy(text = "[video]").isVideo)
    }
}
