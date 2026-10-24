package vbh.ai.whatsapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {
    private val voice = CallUi("id", "p@lid", incoming = false, phase = 1)

    @Test fun onlyForVoiceCallsOnTheEarpiece() {
        assertTrue(Proximity.wanted(voice))
        assertTrue(Proximity.wanted(voice.copy(incoming = true, phase = 3)))
        assertFalse(Proximity.wanted(null))
        assertFalse(Proximity.wanted(voice.copy(speaker = true)))
        assertFalse(Proximity.wanted(voice.copy(video = true)))
    }

    @Test fun notWhileAnIncomingCallRings() {
        assertFalse(Proximity.wanted(voice.copy(incoming = true, phase = 2)))
    }
}
