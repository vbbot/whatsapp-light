package vbh.ai.whatsapp

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeleteMessageTest {
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
    }

    @After fun tearDown() = db.close()

    private fun msg(
        id: String,
        ts: Long,
        fromMe: Boolean = true,
    ) = Msg(id, "a@lid", if (fromMe) "me" else "a@lid", "", "hi", ts, fromMe, false)

    @Test fun deleteForMeRemovesTheMessageAndReturnsItsMedia() {
        MessageStore.addAll(listOf(msg("1", 10).copy(text = "[image] ", media = "/m/1.jpg"), msg("2", 20)))
        assertEquals("/m/1.jpg", MessageStore.deleteMessage("1"))
        assertNull(MessageStore.get("1"))
        assertEquals(listOf("2"), MessageStore.thread("a@lid", 10).map { it.id })
    }

    @Test fun deleteForEveryoneLeavesADeletedLineThatStays() {
        MessageStore.add(msg("1", 10).copy(text = "[image] look", media = "/m/1.jpg", quoteId = "q"))
        assertEquals("/m/1.jpg", MessageStore.markDeleted("1"))
        val m = MessageStore.get("1")!!
        assertTrue(m.isDeleted)
        assertFalse(m.hasPicture)
        assertFalse(m.isReply)
        // History re-sending the original doesn't bring it back.
        MessageStore.add(msg("1", 10).copy(text = "[image] look", media = "/m/1.jpg"))
        assertTrue(MessageStore.get("1")!!.isDeleted)
    }

    @Test fun onlyOwnRecentMessagesCanBeDeletedForEveryone() {
        val now = 100 * 60 * 60_000L
        assertTrue(canDeleteForEveryone(msg("1", now - 60_000), now))
        assertFalse(canDeleteForEveryone(msg("1", now - REVOKE_WINDOW_MS - 1), now))
        assertFalse(canDeleteForEveryone(msg("1", now, fromMe = false), now))
        assertFalse(canDeleteForEveryone(msg("1", now).copy(pending = true), now))
        assertFalse(canDeleteForEveryone(msg("1", now).copy(text = DELETED_TEXT), now))
    }

    @Test fun aQuoteOfADeletedMessageSaysItsGone() {
        MessageStore.add(msg("o", 10).copy(text = DELETED_TEXT))
        val reply = msg("r", 20).copy(quoteId = "o", quoteSender = "me", quoteText = "")
        assertEquals("↶ You: ${Replies.ORIGINAL_MISSING}", Replies.lineFor(reply).text)
    }
}
