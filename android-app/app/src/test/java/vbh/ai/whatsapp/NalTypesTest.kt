package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Test

class NalTypesTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun findsSpsPpsIdrWithBothStartCodeLengths() {
        val au = bytes(0, 0, 0, 1, 0x67, 0x42, 0, 0, 1, 0x68, 0xce, 0, 0, 0, 1, 0x65, 0x88)
        assertEquals(setOf(7, 8, 5), VideoEngine.nalTypes(au))
    }

    @Test fun deltaFrame() = assertEquals(setOf(1), VideoEngine.nalTypes(bytes(0, 0, 0, 1, 0x41, 0x9a)))

    @Test fun emptyOrGarbage() {
        assertEquals(emptySet<Int>(), VideoEngine.nalTypes(ByteArray(0)))
        assertEquals(emptySet<Int>(), VideoEngine.nalTypes(bytes(1, 2, 3, 4, 5)))
    }
}
