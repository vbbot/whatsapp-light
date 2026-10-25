package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test fun duration() {
        assertEquals("00:00", formatDuration(0))
        assertEquals("01:05", formatDuration(65_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("00:00", formatDuration(-5))
    }

    @Test fun displayNumberOnlyForPhoneJids() {
        assertEquals("+447700900123", displayNumber("447700900123@s.whatsapp.net"))
        assertEquals("+447700900123", displayNumber("447700900123:7@s.whatsapp.net"))
        assertEquals("", displayNumber("195824822825056@lid"))
        assertEquals("", displayNumber("12036302@g.us"))
    }

    @Test fun normalizeJidDropsDeviceSuffix() {
        assertEquals("195824822825056@lid", normalizeJid("195824822825056:12@lid"))
        assertEquals("447700900123@s.whatsapp.net", normalizeJid("447700900123@s.whatsapp.net"))
        assertEquals("447700900123", normalizeJid("447700900123"))
    }

    @Test fun rowTimeRelative() {
        val now = System.currentTimeMillis()
        assertTrue(formatRowTime(now).matches(Regex("\\d\\d:\\d\\d")))
        val yesterdayNoon =
            java.time.LocalDate
                .now()
                .minusDays(1)
                .atTime(12, 0)
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        assertEquals("Yest", formatRowTime(yesterdayNoon))
        assertEquals("", formatRowTime(0))
    }

    @Test fun messageTimeTodayHasNoDayTag() {
        assertTrue(formatMessageTime(System.currentTimeMillis()).matches(Regex("\\d{1,2}:\\d\\d [AP]M")))
    }

    @Test fun searchSnippetStartsNearTheMatchOnOneLine() {
        assertEquals("see you at lunch", searchSnippet("see you\nat  lunch", "lunch"))
        val long = "We talked about a lot of things yesterday evening, and then the lunch plan came up"
        assertEquals("…yesterday evening, and then the lunch plan came up", searchSnippet(long, "LUNCH"))
        assertEquals("no match here", searchSnippet("no match here", "zzz"))
    }

    @Test fun clockShowsMinutesWithoutPaddingAndHoursWhenLong() {
        assertEquals("0:00", formatClock(0))
        assertEquals("0:42", formatClock(42_900))
        assertEquals("12:05", formatClock(725_000))
        assertEquals("1:02:03", formatClock(3_723_000))
        assertEquals("Video · 0:42", videoLabel(42))
        assertEquals("Video", videoLabel(0))
        assertEquals("Voice message · 0:12", voiceLabel(12))
        assertEquals("Voice message", voiceLabel(0))
        assertEquals("0:42", videoLength(42))
        assertEquals("", videoLength(0))
    }
}
