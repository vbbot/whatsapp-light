package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The composer scrolls just enough to keep the caret (and the rule below it) in view. */
class ComposerScrollTest {
    @Test
    fun `caret inside the viewport does not scroll`() {
        assertNull(caretFollowTarget(caretTop = 20f, caretBottom = 55f, viewportHeight = 120, current = 0, max = 300))
    }

    @Test
    fun `caret below the viewport scrolls down to reveal its bottom`() {
        assertEquals(50, caretFollowTarget(caretTop = 140f, caretBottom = 170f, viewportHeight = 120, current = 0, max = 300))
    }

    @Test
    fun `caret above the viewport scrolls up to its top`() {
        assertEquals(60, caretFollowTarget(caretTop = 60f, caretBottom = 90f, viewportHeight = 120, current = 100, max = 300))
    }

    @Test
    fun `scrolling down stops at the last line`() {
        assertEquals(300, caretFollowTarget(caretTop = 600f, caretBottom = 640f, viewportHeight = 120, current = 250, max = 300))
    }

    @Test
    fun `scrolling up stops at the first line`() {
        assertEquals(0, caretFollowTarget(caretTop = 0f, caretBottom = 30f, viewportHeight = 120, current = 10, max = 300))
    }

    @Test
    fun `content that fits does not scroll`() {
        assertNull(caretFollowTarget(caretTop = 10f, caretBottom = 110f, viewportHeight = 120, current = 0, max = 0))
    }

    @Test
    fun `a viewport not measured yet does not scroll`() {
        assertNull(caretFollowTarget(caretTop = 10f, caretBottom = 110f, viewportHeight = 0, current = 0, max = 300))
    }
}
