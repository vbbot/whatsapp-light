package vbh.ai.whatsapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallToneTest {
    private val outgoing = CallUi("id", "p@lid", incoming = false, phase = 2)

    @Test fun onlyWhileAnOutgoingCallRings() {
        assertTrue(CallTone.ringbackWanted(outgoing))
        assertFalse(CallTone.ringbackWanted(outgoing.copy(incoming = true)))
        assertFalse(CallTone.ringbackWanted(outgoing.copy(phase = 1)))
        assertFalse(CallTone.ringbackWanted(outgoing.copy(phase = 3)))
        assertFalse(CallTone.ringbackWanted(outgoing.copy(phase = 4)))
        assertFalse(CallTone.ringbackWanted(outgoing.copy(phase = 5)))
        assertFalse(CallTone.ringbackWanted(outgoing.copy(phase = 6)))
        assertFalse(CallTone.ringbackWanted(outgoing.copy(phase = 7)))
        assertFalse(CallTone.ringbackWanted(null))
    }
}
