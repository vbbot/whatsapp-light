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
class RepliesTest {
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        MessageStore.names.value = mapOf("a@lid" to "Ana")
    }

    @After fun tearDown() = db.close()

    private fun msg(
        id: String,
        text: String,
        ts: Long,
        fromMe: Boolean = false,
    ) = Msg(id, "a@lid", if (fromMe) "me" else "a@lid", "", text, ts, fromMe, false)

    @Test fun snippetsFoldLinesAndNameMedia() {
        assertEquals("see you at three", snippet("see you\\nat three".replace("\\n", "\n")))
        assertEquals("Photo", snippet("[image] "))
        assertEquals("this one", snippet("[image] this one"))
        assertEquals("GIF", snippet("[gif] "))
        assertEquals("Video", snippet("[video]"))
        assertEquals("Voice message", snippet("[voice message]"))
        assertEquals("Sticker", snippet("[sticker]"))
    }

    @Test fun lineReadsGlyphAuthorAndSnippet() {
        assertEquals("↶ You: hi", Replies.line("You", "hi").text)
        assertEquals("↶ Ana", Replies.line("Ana", "").text)
    }

    @Test fun replyUsesTheStoredOriginalElseTheSnapshot() {
        MessageStore.add(Msg("o1", "a@lid", "a@lid", "", "see you at three", 10, false, false))
        val reply =
            Msg("r1", "a@lid", "me", "", "perfect", 20, true, false, quoteId = "o1", quoteSender = "a@lid", quoteText = "old snapshot")
        assertEquals("↶ Ana: see you at three", Replies.lineFor(reply).text)
        val orphan = reply.copy(quoteId = "gone", quoteSender = "me", quoteText = "[image] ")
        assertEquals("↶ You: Photo", Replies.lineFor(orphan).text)
        assertEquals("↶ You: ${Replies.ORIGINAL_MISSING}", Replies.lineFor(orphan.copy(quoteText = "")).text)
    }

    @Test fun quoteIsStoredAndSurvivesRedelivery() {
        MessageStore.add(msg("r1", "perfect", 20).copy(quoteId = "o1", quoteSender = "me", quoteText = "hi"))
        // History often re-sends a message without the reply details.
        MessageStore.add(msg("r1", "perfect", 20))
        val m = MessageStore.get("r1")!!
        assertTrue(m.isReply)
        assertEquals(Triple("o1", "me", "hi"), Triple(m.quoteId, m.quoteSender, m.quoteText))
        assertFalse(MessageStore.get("r1")!!.copy(quoteId = "").isReply)
    }

    @Test fun countSinceSaysHowDeepToLoad() {
        MessageStore.addAll((1..5).map { msg("m$it", "x", it * 10L) })
        assertEquals(3, MessageStore.countSince("a@lid", 30))
    }
}
