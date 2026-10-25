package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupCallTest {
    @Test fun aGroupsCallIsTitledByTheGroup() {
        assertEquals("Book club", groupCallTitle("Book club", listOf("Ann", "Bob")))
    }

    @Test fun aCallBetweenChosenPeopleNamesThem() {
        assertEquals("Group call", groupCallTitle(null, emptyList()))
        assertEquals("Ann", groupCallTitle(null, listOf("Ann")))
        assertEquals("Ann and Bob", groupCallTitle("", listOf("Ann", "Bob")))
        assertEquals("Ann, Bob and Cat", groupCallTitle(null, listOf("Ann", "Bob", "Cat")))
        assertEquals("Ann, Bob and 2 others", groupCallTitle(null, listOf("Ann", "Bob", "Cat", "Dan")))
    }

    @Test fun walibListsLoseDeviceSuffixesAndBlanks() {
        assertEquals(listOf("1@lid", "2@lid"), "1:3@lid, 2@lid,,1@lid".jidList())
        assertEquals(emptyList<String>(), "".jidList())
    }

    @Test fun groupDetailsMarkTheCall() {
        val c =
            CallUi(
                "C",
                "1@lid",
                incoming = true,
                video = true,
            ).withGroup(GroupCallInfo("g@g.us", listOf("1@lid", "2@lid"), listOf("1@lid")))
        assertEquals("incoming group video call", incomingLabel(c))
        assertEquals("incoming call", incomingLabel(CallUi("C", "1@lid", incoming = true)))
        assertEquals(listOf("1@lid"), c.joined)
    }

    @Test fun theGridShowsLiveVideoInTheCallsOrder() {
        val now = 100_000L
        val frames = mapOf("c" to now - 100, "a" to now - 200, "stale" to now - VIDEO_STALE_MS - 1, "none" to 0L)
        assertEquals(listOf("a", "c"), videoTiles(frames, listOf("a", "b", "c", "stale"), now))
    }

    @Test fun theGridKeepsTheFourMostRecentlyActive() {
        val now = 100_000L
        val frames = (1..6).associate { "p$it" to now - it * 10L }
        assertEquals(listOf("p1", "p2", "p3", "p4"), videoTiles(frames, listOf("p6", "p5", "p4", "p3", "p2", "p1"), now).sorted())
        assertEquals(listOf("p4", "p3", "p2", "p1"), videoTiles(frames, listOf("p6", "p5", "p4", "p3", "p2", "p1"), now))
    }

    @Test fun groupCallsSayWhatKindTheyWere() {
        val answered =
            CallRecord(
                "C",
                "g@g.us",
                incoming = true,
                startedAt = 0,
                answeredAt = 1_000,
                endedAt = 61_000,
                outcome = CallRecord.Outcome.ANSWERED,
            )
        assertEquals("group · incoming · 01:00", answered.summary)
        assertEquals("group video · missed", answered.copy(video = true, outcome = CallRecord.Outcome.MISSED).summary)
        val chosen =
            CallRecord(
                "D",
                "1@lid",
                incoming = true,
                startedAt = 0,
                members = listOf("1@lid", "2@lid"),
                outcome = CallRecord.Outcome.MISSED,
            )
        assertEquals("group · missed", chosen.summary)
        assertEquals("missed", chosen.copy(members = emptyList()).summary)
    }
}
