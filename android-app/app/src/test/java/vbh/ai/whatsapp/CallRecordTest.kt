package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Test

class CallRecordTest {
    private fun rec(
        incoming: Boolean,
        outcome: CallRecord.Outcome,
        video: Boolean = false,
    ) = CallRecord("id", "p@lid", incoming, 0, answeredAt = 1_000, endedAt = 135_000, outcome = outcome, video = video)

    @Test fun summaries() {
        assertEquals("outgoing · 02:14", rec(false, CallRecord.Outcome.ANSWERED).summary)
        assertEquals("incoming · 02:14", rec(true, CallRecord.Outcome.ANSWERED).summary)
        assertEquals("missed", rec(true, CallRecord.Outcome.MISSED).summary)
        assertEquals("declined", rec(true, CallRecord.Outcome.DECLINED).summary)
        assertEquals("no answer", rec(false, CallRecord.Outcome.NO_ANSWER).summary)
        assertEquals("video · missed", rec(true, CallRecord.Outcome.MISSED, video = true).summary)
    }
}
