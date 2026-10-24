package vbh.ai.whatsapp

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReactionsTest {
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        MessageStore.add(Msg("m1", "g@g.us", "a@lid", "", "hi", 10, false, true))
    }

    @After fun tearDown() = db.close()

    private fun line() = reactionLine(MessageStore.reactions(listOf("m1"))["m1"].orEmpty())

    @Test fun oneLineWithCountsOldestFirst() {
        MessageStore.setReaction("g@g.us", "m1", "a@lid", "❤️", 1)
        MessageStore.setReaction("g@g.us", "m1", "b@lid", "👍", 2)
        MessageStore.setReaction("g@g.us", "m1", "me", "❤️", 3)
        assertEquals("❤️ 2 · 👍", line())
        assertEquals("❤️", MessageStore.ownReaction("m1"))
    }

    @Test fun eachPersonHasOneReactionAndCanRemoveIt() {
        MessageStore.setReaction("g@g.us", "m1", "a@lid", "❤️", 1)
        MessageStore.setReaction("g@g.us", "m1", "a@lid", "😂", 2)
        assertEquals("😂", line())
        MessageStore.setReaction("g@g.us", "m1", "a@lid", "", 3)
        assertEquals("", line())
    }

    @Test fun anOlderChangeDoesNotUndoANewerOne() {
        MessageStore.setReaction("g@g.us", "m1", "a@lid", "😂", 20)
        // History arriving after the live change, with the earlier reaction.
        MessageStore.setReaction("g@g.us", "m1", "a@lid", "❤️", 10)
        assertEquals("😂", line())
    }

    @Test fun deletingTheMessageOrChatDropsItsReactions() {
        MessageStore.setReaction("g@g.us", "m1", "a@lid", "❤️", 1)
        MessageStore.deleteMessage("m1")
        assertTrue(MessageStore.reactions(listOf("m1")).isEmpty())
        MessageStore.add(Msg("m2", "g@g.us", "a@lid", "", "hi", 20, false, true))
        MessageStore.setReaction("g@g.us", "m2", "me", "👍", 1)
        MessageStore.deleteChat("g@g.us")
        assertNull(MessageStore.ownReaction("m2"))
    }

    @Test fun historyReactionsAreImported() {
        val json =
            """{"type":"RECENT","progress":10,"messages":[{"id":"m3","chat":"g@g.us","sender":"a@lid","text":"yo","ts":30,""" +
                """"fromMe":false,"isGroup":true,"reactions":[{"reactor":"me","emoji":"🔥","ts":31}]}],"chats":[],"calls":[]}"""
        History.import(History.parse(json))
        assertEquals("🔥", MessageStore.ownReaction("m3"))
    }

    @Test fun aReactionToOurMessageMarksTheChatUnreadUntilOpened() {
        MessageStore.add(Msg("mine", "g@g.us", "me", "", "hello", 20, true, true))
        MessageStore.markRead("g@g.us")
        assertEquals(false, MessageStore.chats().single().unread)
        MessageStore.markReacted("g@g.us", 30)
        assertEquals(true, MessageStore.chats().single().unread)
        MessageStore.markRead("g@g.us")
        assertEquals(false, MessageStore.chats().single().unread)
    }
}
