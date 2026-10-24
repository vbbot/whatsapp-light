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
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutboxTest {
    private lateinit var db: SQLiteDatabase
    private val sent = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var online = true

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        Outbox.isConnected = { online }
        Outbox.retryDelayMs = 10
        Outbox.sender = { m -> sent += m.id }
    }

    @After fun tearDown() {
        Outbox.sender = null
        db.close()
    }

    private fun queue(
        id: String,
        ts: Long,
        chat: String = "a@lid",
    ) = Msg(id, chat, "me", "", "hi $id", ts, true, false, status = Status.QUEUED).also { MessageStore.add(it) }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(what())
    }

    private fun status(id: String) = MessageStore.get(id)?.status

    @Test fun offlineMessagesWaitThenGoInOrder() {
        online = false
        queue("b", 20)
        queue("a", 10, chat = "b@lid")
        queue("c", 30)
        Outbox.flush()
        Thread.sleep(100)
        assertTrue(sent.isEmpty())
        assertEquals(Status.QUEUED, status("a"))
        online = true
        Outbox.flush()
        waitFor { status("c") == Status.SENT }
        assertEquals(listOf("a", "b", "c"), sent.toList())
        assertTrue(MessageStore.queued().isEmpty())
    }

    @Test fun aDroppedConnectionLeavesTheRestQueued() {
        Outbox.sender = { m ->
            if (m.id == "b") {
                online = false
                error("failed to send usync query: websocket not connected")
            }
            sent += m.id
        }
        queue("a", 10)
        queue("b", 20)
        queue("c", 30)
        Outbox.flush()
        waitFor { status("a") == Status.SENT }
        Thread.sleep(100)
        assertEquals(listOf("a"), sent.toList())
        assertEquals(Status.QUEUED, status("b"))
        assertEquals(Status.QUEUED, status("c"))
    }

    @Test fun aRefusalIsNotSentAndCanBeRetried() {
        var refuse = true
        Outbox.sender = { m ->
            if (m.id == "a" && refuse) error("server returned error 479")
            sent += m.id
        }
        queue("a", 10)
        queue("b", 20)
        Outbox.flush()
        waitFor { status("b") == Status.SENT }
        assertEquals(Status.NOT_SENT, status("a"))
        assertTrue(MessageStore.get("a")!!.notSent && MessageStore.get("a")!!.pending)
        refuse = false
        Outbox.retry(MessageStore.get("a")!!)
        waitFor { status("a") == Status.SENT }
        assertFalse(MessageStore.get("a")!!.pending)
    }

    @Test fun aReceiptBeforeTheSendReturnsIsKept() {
        Outbox.sender = { m -> MessageStore.applyReceipt(setOf(m.id), Status.DELIVERED) }
        queue("a", 10)
        Outbox.flush()
        waitFor { status("a") == Status.DELIVERED }
        Thread.sleep(50)
        assertEquals(Status.DELIVERED, status("a"))
    }

    @Test fun connectionErrorsAreToldFromRefusals() {
        assertTrue(Outbox.looksLikeConnection("failed to get device list: failed to send usync query: websocket not connected"))
        assertTrue(Outbox.looksLikeConnection("context deadline exceeded"))
        assertFalse(Outbox.looksLikeConnection("server returned error 479"))
    }

    @Test fun tagsSayWhereAnUnsentMessageIs() {
        val m = Msg("x", "a@lid", "me", "", "hi", 1, true, false, status = Status.QUEUED)
        assertEquals("SENDING", pendingTag(m, connected = true))
        assertEquals("QUEUED", pendingTag(m, connected = false))
        assertEquals("NOT SENT", pendingTag(m.copy(status = Status.NOT_SENT), connected = true))
    }
}
