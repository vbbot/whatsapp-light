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

    @Test fun aQueuedMessageIsStoredAndShowsItsChat() {
        MessageStore.add(m("q1", "new@lid", 5, fromMe = true).copy(status = Status.QUEUED))
        assertEquals(listOf("new@lid"), MessageStore.chats().map { it.chat })
        assertTrue(MessageStore.thread("new@lid", 10).single().pending)
        assertEquals(listOf("q1"), MessageStore.queued().map { it.id })
        MessageStore.deleteMessage("q1")
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

    @Test fun aGroupMemberIsNamedByWhatTheyWroteUnderInAGroup() {
        MessageStore.add(Msg("g1", "1203@g.us", "m@lid", "Priya", "hi", 1, false, true))
        MessageStore.add(Msg("g2", "1203@g.us", "m@lid", "", "again", 2, false, true))
        assertEquals("Priya", MessageStore.title("m@lid"))
        MessageStore.names.value = mapOf("m@lid" to "Priya Shah")
        assertEquals("Priya Shah", MessageStore.title("m@lid"))
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

    @Test fun downloadedPhotoIsNotReplacedByItsThumbnail() {
        val photo = Msg("p", "a@lid", "a@lid", "", "[image]", 10, false, false, media = "/m/p_t.jpg", mediaRef = "REF")
        MessageStore.add(photo)
        assertTrue(MessageStore.get("p")!!.needsFullPhoto)
        MessageStore.setMedia("p", "/m/p.jpg")
        // History sends the same message again, with its preview and no ref.
        MessageStore.add(photo.copy(mediaRef = ""))
        val got = MessageStore.get("p")!!
        assertEquals("/m/p.jpg", got.media)
        assertEquals("REF", got.mediaRef)
        assertFalse(got.needsFullPhoto)
    }

    @Test fun needsFullPhotoOnlyForPhotoPreviewsWithARef() {
        val base = Msg("v", "a@lid", "a@lid", "", "[image]", 1, false, false, media = "/m/v_t.jpg", mediaRef = "REF")
        assertTrue(base.needsFullPhoto)
        assertFalse(base.copy(mediaRef = "").needsFullPhoto)
        assertFalse(base.copy(text = "[video]").needsFullPhoto)
        assertFalse(base.copy(media = "/m/v.jpg").needsFullPhoto)
        // No preview at all: still a picture, to be fetched.
        assertTrue(base.copy(media = "").needsFullPhoto)
        assertTrue(base.copy(media = "").hasPicture)
        assertFalse(base.copy(media = "", mediaRef = "").hasPicture)
    }

    @Test fun upgradesVersionOneDatabase() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file =
            context.getDatabasePath("upgrade-test.db").also {
                it.parentFile?.mkdirs()
                it.delete()
            }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL(
                "CREATE TABLE messages (id TEXT PRIMARY KEY, chat TEXT NOT NULL, sender TEXT NOT NULL, " +
                    "sender_name TEXT NOT NULL DEFAULT '', text TEXT NOT NULL, ts INTEGER NOT NULL, " +
                    "from_me INTEGER NOT NULL, is_group INTEGER NOT NULL, status INTEGER NOT NULL DEFAULT 0, " +
                    "read_sent INTEGER NOT NULL DEFAULT 0, media TEXT NOT NULL DEFAULT '')",
            )
            old.execSQL("CREATE TABLE chats (chat TEXT PRIMARY KEY, last_read INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL DEFAULT '')")
            old.execSQL(
                "CREATE TABLE calls (id TEXT PRIMARY KEY, peer TEXT NOT NULL, incoming INTEGER NOT NULL, started_at INTEGER NOT NULL, " +
                    "answered_at INTEGER NOT NULL DEFAULT 0, ended_at INTEGER NOT NULL DEFAULT 0, outcome TEXT NOT NULL, " +
                    "video INTEGER NOT NULL DEFAULT 0)",
            )
            old.execSQL("INSERT INTO messages(id, chat, sender, text, ts, from_me, is_group) VALUES('1', 'a@lid', 'a@lid', 'old', 5, 0, 0)")
            old.version = 1
        }
        val upgraded = ChatDb(context, "upgrade-test.db").writableDatabase
        MessageStore.open(upgraded)
        assertEquals("old", MessageStore.get("1")?.text)
        assertEquals("", MessageStore.get("1")?.mediaRef)
        upgraded.close()
        MessageStore.open(db)
    }

    @Test fun searchFindsWordsInAnyChatNewestFirst() {
        MessageStore.addAll(
            listOf(
                m("1", "a@lid", 10).copy(text = "Lunch at noon?"),
                m("2", "b@lid", 30).copy(text = "no LUNCH today"),
                m("3", "a@lid", 20).copy(text = "[image] lunch spot"),
                m("4", "a@lid", 40).copy(text = "[voice message]"),
                m("5", "b@lid", 50).copy(text = "100% sure_thing"),
                m("6", "b@lid", 60).copy(text = DELETED_TEXT),
            ),
        )
        assertEquals(listOf("2", "3", "1"), MessageStore.search("lunch").map { it.id })
        // Placeholders aren't words anyone wrote.
        assertTrue(MessageStore.search("voice").isEmpty())
        assertTrue(MessageStore.search("image").isEmpty())
        assertTrue(MessageStore.search("deleted").isEmpty())
        // LIKE's wildcards are taken literally.
        assertEquals(listOf("5"), MessageStore.search("0% s").map { it.id })
        assertEquals(listOf("5"), MessageStore.search("e_t").map { it.id })
        assertTrue(MessageStore.search("_").none { it.id != "5" })
        assertTrue(MessageStore.search("  ").isEmpty())
        assertEquals(1, MessageStore.search("lunch", limit = 1).size)
    }

    @Test fun captionStripsPlaceholder() {
        val img = Msg("i", "a@lid", "a@lid", "", "[image] look", 1, false, false)
        assertEquals("look", img.caption)
        assertFalse(img.isVideo)
        assertTrue(img.copy(text = "[video]").isVideo)
    }
}
