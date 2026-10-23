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
class ChatStateTest {
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        CallLog.open(db)
    }

    @After fun tearDown() {
        ChatIdentity.canonical = null
        db.close()
    }

    private fun m(
        id: String,
        chat: String,
        ts: Long,
    ) = Msg(id, chat, chat, "", "hi", ts, false, false)

    @Test fun archivedChatsLeaveTheListAndAreCounted() {
        MessageStore.addAll(listOf(m("1", "a@lid", 10), m("2", "b@lid", 20)))
        MessageStore.setArchived("b@lid", true)
        assertEquals(listOf("a@lid"), MessageStore.chats().map { it.chat })
        assertEquals(listOf("b@lid"), MessageStore.chats(archived = true).map { it.chat })
        assertEquals(1, MessageStore.archivedCount())
        // New messages don't bring it back.
        MessageStore.add(m("3", "b@lid", 30))
        assertEquals(listOf("a@lid"), MessageStore.chats().map { it.chat })
        MessageStore.setArchived("b@lid", false)
        assertEquals(listOf("b@lid", "a@lid"), MessageStore.chats().map { it.chat })
        assertEquals(0, MessageStore.archivedCount())
    }

    @Test fun mutedChatsAreFlaggedAndStillShowUnread() {
        MessageStore.add(m("1", "a@lid", 10))
        MessageStore.setMutedUntil("a@lid", MUTED_FOREVER)
        val row = MessageStore.chats().single()
        assertTrue(row.muted)
        assertTrue(row.unread)
        assertTrue(MessageStore.chatState("a@lid").quietAt(System.currentTimeMillis()))
        MessageStore.setMutedUntil("a@lid", 0)
        assertFalse(MessageStore.chats().single().muted)
    }

    @Test fun timedMutesExpire() {
        val now = 1_000_000L
        assertTrue(ChatState(mutedUntil = now + 1).mutedAt(now))
        assertFalse(ChatState(mutedUntil = now - 1).mutedAt(now))
        assertTrue(ChatState(mutedUntil = MUTED_FOREVER).mutedAt(now))
        assertFalse(ChatState().quietAt(now))
        assertTrue(ChatState(archived = true).quietAt(now))
    }

    @Test fun mergingKeepsTheTargetsStateOrTakesTheMovedOne() {
        ChatIdentity.canonical = { if (it.endsWith("@s.whatsapp.net")) it.replace("@s.whatsapp.net", "@lid") else it }
        // Target has no state: the moved chat's archive and mute come along.
        MessageStore.add(m("1", "1@s.whatsapp.net", 10))
        MessageStore.setArchived("1@s.whatsapp.net", true)
        MessageStore.setMutedUntil("1@s.whatsapp.net", MUTED_FOREVER)
        // Target already has state: it wins.
        MessageStore.addAll(listOf(m("2", "2@s.whatsapp.net", 10), m("3", "2@lid", 20)))
        MessageStore.setArchived("2@s.whatsapp.net", true)
        MessageStore.setMutedUntil("2@lid", 0)
        ChatIdentity.mergeDuplicates()
        assertEquals(ChatState(true, MUTED_FOREVER), MessageStore.chatState("1@lid"))
        assertEquals(ChatState(false, 0), MessageStore.chatState("2@lid"))
    }

    @Test fun historyOnlyTurnsArchiveAndMuteOn() {
        val json = { archived: Boolean, muted: Long ->
            """{"onDemand":false,"type":"RECENT","progress":10,""" +
                """"chats":[{"chat":"a@lid","name":"","unread":0,"archived":$archived,"mutedUntil":$muted}],""" +
                """"messages":[{"chat":"a@lid","sender":"a@lid","senderName":"","id":"1","text":"hi","ts":10,""" +
                """"fromMe":false,"isGroup":false,"status":0,"media":""}],"calls":[]}"""
        }
        History.import(History.parse(json(true, MUTED_FOREVER)))
        assertEquals(ChatState(true, MUTED_FOREVER), MessageStore.chatState("a@lid"))
        // A later batch without the flags doesn't undo them.
        History.import(History.parse(json(false, 0)))
        assertEquals(ChatState(true, MUTED_FOREVER), MessageStore.chatState("a@lid"))
    }

    @Test fun upgradesVersionTwoDatabase() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file =
            context.getDatabasePath("v2-test.db").also {
                it.parentFile?.mkdirs()
                it.delete()
            }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE messages (id TEXT PRIMARY KEY, chat TEXT NOT NULL, sender TEXT NOT NULL, " +
                    "sender_name TEXT NOT NULL DEFAULT '', text TEXT NOT NULL, ts INTEGER NOT NULL, " +
                    "from_me INTEGER NOT NULL, is_group INTEGER NOT NULL, status INTEGER NOT NULL DEFAULT 0, " +
                    "read_sent INTEGER NOT NULL DEFAULT 0, media TEXT NOT NULL DEFAULT '', media_ref TEXT NOT NULL DEFAULT '')",
            )
            old.execSQL(
                "CREATE TABLE calls (id TEXT PRIMARY KEY, peer TEXT NOT NULL, incoming INTEGER NOT NULL, started_at INTEGER NOT NULL, answered_at INTEGER NOT NULL DEFAULT 0, ended_at INTEGER NOT NULL DEFAULT 0, outcome TEXT NOT NULL, video INTEGER NOT NULL DEFAULT 0)",
            )
            old.execSQL("CREATE TABLE chats (chat TEXT PRIMARY KEY, last_read INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL DEFAULT '')")
            old.execSQL("INSERT INTO chats(chat, last_read, name) VALUES('a@lid', 5, 'Kept')")
            old.version = 2
        }
        val upgraded = ChatDb(context, "v2-test.db").writableDatabase
        MessageStore.open(upgraded)
        assertEquals(ChatState(), MessageStore.chatState("a@lid"))
        MessageStore.setArchived("a@lid", true)
        assertTrue(MessageStore.chatState("a@lid").archived)
        upgraded.close()
        MessageStore.open(db)
    }
}
