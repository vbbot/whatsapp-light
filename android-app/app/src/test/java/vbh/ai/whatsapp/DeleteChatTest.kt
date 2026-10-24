package vbh.ai.whatsapp

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeleteChatTest {
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
    }

    @After fun tearDown() = db.close()

    private fun m(
        id: String,
        chat: String,
        ts: Long,
    ) = Msg(id, chat, chat, "", "hi", ts, false, false)

    @Test fun deletesTheChatItsMessagesAndReturnsTheirMedia() {
        MessageStore.addAll(listOf(m("1", "a@lid", 10).copy(media = "/m/1.jpg"), m("2", "a@lid", 20), m("3", "b@lid", 30)))
        MessageStore.setArchived("a@lid", true)

        val media = MessageStore.deleteChat("a@lid")

        assertEquals(listOf("/m/1.jpg"), media)
        assertTrue(MessageStore.thread("a@lid", 10).isEmpty())
        assertEquals(ChatState(), MessageStore.chatState("a@lid") ?: ChatState())
        assertTrue(MessageStore.chats(archived = true).isEmpty())
        assertEquals(listOf("b@lid"), MessageStore.chats().map { it.chat })
    }

    @Test fun keepsMessagesNewerThanTheDeletion() {
        MessageStore.addAll(listOf(m("1", "a@lid", 10), m("2", "a@lid", 20)))
        MessageStore.deleteChat("a@lid", beforeMs = 15)
        assertEquals(listOf("2"), MessageStore.thread("a@lid", 10).map { it.id })
        assertEquals(listOf("a@lid"), MessageStore.chats().map { it.chat })
    }

    @Test fun clearingKeepsTheChat() {
        MessageStore.add(m("1", "a@lid", 10))
        MessageStore.setMutedUntil("a@lid", MUTED_FOREVER)
        MessageStore.deleteChat("a@lid", keepChat = true)
        assertTrue(MessageStore.thread("a@lid", 10).isEmpty())
        assertEquals(MUTED_FOREVER, MessageStore.chatState("a@lid")?.mutedUntil)
    }

    @Test fun namedMessagesSayHowFarADeletionGoes() {
        // Our own message, stored with this phone's clock a moment after the server's time.
        MessageStore.addAll(listOf(m("1", "a@lid", 1_000), m("mine", "a@lid", 2_400)))
        assertEquals(2_400L, MessageStore.newestOf("a@lid", listOf("mine", "unknown")))
        assertEquals(null, MessageStore.newestOf("a@lid", listOf("unknown")))
        assertEquals(null, MessageStore.newestOf("b@lid", listOf("mine")))
    }
}
