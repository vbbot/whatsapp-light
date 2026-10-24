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
}
