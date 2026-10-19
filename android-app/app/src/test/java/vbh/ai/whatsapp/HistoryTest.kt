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
class HistoryTest {
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        CallLog.open(db)
    }

    @After fun tearDown() = db.close()

    private val json =
        """
        {"onDemand":false,"progress":10,
         "chats":[{"chat":"a@lid","name":"Ann","unread":1},{"chat":"b@lid","name":"","unread":0}],
         "messages":[
           {"chat":"a@lid","sender":"a@lid","senderName":"","id":"1","text":"one","ts":10,"fromMe":false,"isGroup":false,"status":0,"media":""},
           {"chat":"a@lid","sender":"a@lid","senderName":"","id":"2","text":"two","ts":20,"fromMe":false,"isGroup":false,"status":0,"media":""},
           {"chat":"b@lid","sender":"me@s.whatsapp.net","senderName":"","id":"3","text":"hey","ts":15,"fromMe":true,"isGroup":false,"status":3,"media":""},
           {"chat":"b@lid","sender":"b@lid","senderName":"","id":"4","text":"[image] ","ts":16,"fromMe":false,"isGroup":false,"status":0,"media":"/m/4_t.jpg","mediaRef":"REF"}],
         "calls":[
           {"id":"c1","peer":"a@lid","incoming":true,"startedAt":1000,"duration":0,"video":false,"result":"missed"},
           {"id":"c2","peer":"b:3@lid","incoming":false,"startedAt":2000,"duration":60,"video":true,"result":"connected"}]}
        """.trimIndent()

    @Test fun parsesAndKeepsUnreadEligibleForReceipts() {
        val b = History.parse(json)
        assertEquals(4, b.messages.size)
        assertTrue(b.messages.single { it.id == "4" }.needsFullPhoto)
        // Only the newest incoming message in a@lid is still unread.
        assertEquals(mapOf("1" to true, "2" to false), b.messages.filter { it.chat == "a@lid" }.associate { it.id to it.readSent })
        assertEquals(Status.SEEN, b.messages.single { it.id == "3" }.status)
        val connected = b.calls.single { it.id == "c2" }
        assertEquals("b@lid", connected.peer)
        assertEquals(CallRecord.Outcome.ANSWERED, connected.outcome)
        assertEquals(62_000L, connected.endedAt)
        assertEquals(CallRecord.Outcome.MISSED, b.calls.single { it.id == "c1" }.outcome)
    }

    @Test fun syncCountsBatchesUntilTheFullHistoryIsIn() {
        History.syncing.value = 0
        History.receive(json.replace("\"progress\":10", "\"type\":\"RECENT\",\"progress\":50"))
        assertEquals(4, History.syncing.value)
        History.receive("""{"onDemand":true,"type":"ON_DEMAND","progress":100,"chats":[],"messages":[],"calls":[]}""")
        assertEquals(4, History.syncing.value)
        History.receive("""{"onDemand":false,"type":"FULL","progress":100,"chats":[],"messages":[],"calls":[]}""")
        assertEquals(null, History.syncing.value)
    }

    @Test fun importStoresMessagesNamesUnreadAndCalls() {
        History.import(History.parse(json))
        val chats = MessageStore.chats().associateBy { it.chat }
        assertTrue(chats.getValue("a@lid").unread)
        assertFalse(chats.getValue("b@lid").unread)
        assertEquals("Ann", MessageStore.title("a@lid"))
        assertEquals(listOf("2"), MessageStore.needingReadReceipt("a@lid").map { it.id })
        assertEquals(listOf("c2", "c1"), CallLog.calls.value.map { it.id })
    }
}
