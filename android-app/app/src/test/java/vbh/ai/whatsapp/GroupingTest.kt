package vbh.ai.whatsapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class GroupingTest {
    private fun at(time: LocalDateTime) = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun m(
        ts: Long,
        sender: String = "a@lid",
    ) = Msg("$ts", "a@lid", sender, "", "hi", ts, false, false)

    private val noon = at(LocalDateTime.of(2026, 10, 19, 12, 0))

    @Test fun firstMessageStartsAGroup() = assertTrue(startsGroup(null, m(noon)))

    @Test fun sameSenderWithinTheGapContinues() = assertFalse(startsGroup(m(noon), m(noon + 5 * 60_000)))

    @Test fun longGapOrNewSenderStartsAGroup() {
        assertTrue(startsGroup(m(noon), m(noon + 16 * 60_000)))
        assertTrue(startsGroup(m(noon), m(noon + 60_000, sender = "b@lid")))
    }

    @Test fun newDayStartsAGroupEvenWithinTheGap() {
        val beforeMidnight = at(LocalDateTime.of(2026, 10, 19, 23, 58))
        val afterMidnight = at(LocalDateTime.of(2026, 10, 20, 0, 2))
        assertTrue(startsGroup(m(beforeMidnight), m(afterMidnight)))
    }
}
