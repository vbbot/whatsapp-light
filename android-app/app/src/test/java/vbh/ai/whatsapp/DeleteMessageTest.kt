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
        assertFalse(canDeleteForEveryone(msg("1", now).copy(status = Status.QUEUED), now))
        assertFalse(canDeleteForEveryone(msg("1", now).copy(text = DELETED_TEXT), now))
    }

    @Test fun aQuoteOfADeletedMessageSaysItsGone() {
        MessageStore.add(msg("o", 10).copy(text = DELETED_TEXT))
        val reply = msg("r", 20).copy(quoteId = "o", quoteSender = "me", quoteText = "")
        assertEquals("↶ You: ${Replies.ORIGINAL_MISSING}", Replies.lineFor(reply).text)
    }

    @Test fun anEditReplacesTheTextOnlyFromItsAuthor() {
        MessageStore.add(msg("1", 10, fromMe = false))
        // Someone else (or we) can't edit their message.
        assertFalse(MessageStore.editMessage("a@lid", "1", "nope", 50, byMe = true, by = ""))
        assertFalse(MessageStore.editMessage("b@lid", "1", "nope", 50, byMe = false, by = "a@lid"))
        assertTrue(MessageStore.editMessage("a@lid", "1", "fixed", 50, byMe = false, by = "a@lid"))
        assertEquals("fixed", MessageStore.get("1")!!.text)
        assertEquals(50L, MessageStore.get("1")!!.editedAt)
        // An older edit arriving late doesn't undo a newer one.
        assertFalse(MessageStore.editMessage("a@lid", "1", "older", 40, byMe = false, by = "a@lid"))
        assertEquals("fixed", MessageStore.get("1")!!.text)
        assertEquals("edited", editedNote(MessageStore.get("1")!!, null))
        assertEquals("edited · seen", editedNote(MessageStore.get("1")!!, "seen"))
        assertNull(editedNote(msg("2", 10), null))
    }

    @Test fun theOriginalDeliveredAgainKeepsTheEdit() {
        MessageStore.add(msg("1", 10))
        MessageStore.editMessage("a@lid", "1", "fixed", 50, byMe = true, by = "")
        MessageStore.addAll(listOf(msg("1", 10)))
        assertEquals("fixed", MessageStore.get("1")!!.text)
    }

    @Test fun historyCanSayAMessageWasDeletedOrEdited() {
        MessageStore.addAll(listOf(msg("1", 10).copy(media = "/m/1.jpg", text = "[image] x"), msg("2", 20)))
        // A deleted stub in place of a message we have.
        MessageStore.addAll(listOf(msg("1", 10).copy(text = DELETED_TEXT)))
        assertTrue(MessageStore.get("1")!!.isDeleted)
        assertEquals("", MessageStore.get("1")!!.media)
        History.import(
            HistoryBatch(
                false,
                "RECENT",
                10,
                emptyList(),
                emptyMap(),
                emptyList(),
                edits = listOf(HistoryEdit("a@lid", "2", "fixed", 60, fromMe = true, sender = "")),
            ),
        )
        assertEquals("fixed", MessageStore.get("2")!!.text)
    }

    @Test fun onlyRecentOwnTextMessagesCanBeEdited() {
        val now = 1_000_000_000L
        assertTrue(canEdit(msg("1", now - 60_000), now))
        assertFalse(canEdit(msg("1", now - EDIT_WINDOW_MS - 1), now))
        assertFalse(canEdit(msg("1", now, fromMe = false), now))
        assertFalse(canEdit(msg("1", now).copy(text = "[image] x"), now))
        assertFalse(canEdit(msg("1", now).copy(text = DELETED_TEXT), now))
    }
}
