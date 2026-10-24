package vbh.ai.whatsapp

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ROW_TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val MESSAGE_TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
private val MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** Short time for chat-list rows: "14:02", "Yest", "Mon", "Aug 12", "Aug 2025". */
fun formatRowTime(ts: Long): String {
    if (ts <= 0) return ""
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(ts).atZone(zone)
    val today = LocalDate.now(zone)
    val date = dt.toLocalDate()
    return when {
        date == today -> dt.toLocalTime().format(ROW_TIME)
        date == today.minusDays(1) -> "Yest"
        date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)
        date.year == today.year -> date.format(MONTH_DAY)
        else -> date.format(MONTH_YEAR)
    }
}

/** Time above a message in a thread: "3:24 PM", "Yesterday 3:24 PM", "Aug 12 3:24 PM". */
fun formatMessageTime(ts: Long): String {
    if (ts <= 0) return ""
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(ts).atZone(zone)
    val today = LocalDate.now(zone)
    val date = dt.toLocalDate()
    val time = dt.toLocalTime().format(MESSAGE_TIME)
    val tag =
        when {
            date == today -> null
            date == today.minusDays(1) -> "Yesterday"
            date.isAfter(today.minusDays(7)) -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
            date.year == today.year -> date.format(MONTH_DAY)
            else -> date.format(MONTH_DAY_YEAR)
        }
    return if (tag == null) time else "$tag $time"
}

/** The local date of a timestamp: thread groups break when the day changes. */
fun dayOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()

/** "00:23" / "1:02:03" call duration. */
fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** "0:42" / "1:02:03": a position or length in a video. */
fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** A video's label in the thread: "Video · 0:42", or "Video" while its length isn't known. */
fun videoLabel(seconds: Int): String = if (seconds > 0) "Video · ${formatClock(seconds * 1000L)}" else "Video"

/** A video's length under its preview ("0:42"), or "" while it isn't known. */
fun videoLength(seconds: Int): String = if (seconds > 0) formatClock(seconds * 1000L) else ""

/** "+<digits>" for phone JIDs; "" for anything else (a LID is an internal ID, not a number). */
fun displayNumber(jid: String): String {
    val user = jid.substringBefore('@').substringBefore(':')
    return if (jid.endsWith("@s.whatsapp.net") && user.all { it.isDigit() }) "+$user" else ""
}

/** Strips a device suffix: "user:12@server" -> "user@server". */
fun normalizeJid(jid: String): String =
    if (jid.contains("@")) jid.substringBefore(':').substringBefore('@') + "@" + jid.substringAfter('@') else jid
