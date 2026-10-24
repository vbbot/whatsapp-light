package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiptTagTest {
    private var ts = 0L

    private fun mine(
        id: String,
        status: Int,
        pending: Boolean = false,
    ) = Msg(id, "c@s.whatsapp.net", "me", "", "x", ++ts, fromMe = true, isGroup = false, status = if (pending) Status.QUEUED else status)

    private fun theirs(id: String) = Msg(id, "c@s.whatsapp.net", "c", "C", "x", ++ts, fromMe = false, isGroup = false)

    @Test fun noOwnMessagesNoTag() = assertNull(receiptTag(listOf(theirs("a"))))

    @Test fun newestOwnMessageShowsItsProgress() {
        assertEquals("b" to "sent", receiptTag(listOf(mine("a", Status.DELIVERED), mine("b", Status.SENT))))
        assertEquals("b" to "delivered", receiptTag(listOf(mine("a", Status.SENT), mine("b", Status.DELIVERED))))
    }

    @Test fun seenOnNewest() = assertEquals("a" to "seen", receiptTag(listOf(mine("a", Status.SEEN))))

    @Test fun progressOnNewestWinsOverOlderSeen() =
        assertEquals("b" to "delivered", receiptTag(listOf(mine("a", Status.SEEN), mine("b", Status.DELIVERED))))

    @Test fun theirReplyDropsTheTag() = assertNull(receiptTag(listOf(mine("a", Status.SEEN), theirs("r"))))

    @Test fun seenStaysWhenTheyHaventReadOurNewest() =
        assertEquals("a" to "seen", receiptTag(listOf(mine("a", Status.SEEN), mine("b", Status.DELIVERED), theirs("r"))))

    @Test fun pendingMessagesAreIgnored() =
        assertEquals("a" to "delivered", receiptTag(listOf(mine("a", Status.DELIVERED), mine("p", Status.NONE, pending = true))))
}
