package vbh.ai.whatsapp

/** What walib reports about a group call: its group chat ("" if none) and other participants. */
data class GroupCallInfo(
    val group: String,
    val members: List<String>,
    val joined: List<String>,
)

fun CallUi.withGroup(info: GroupCallInfo) = copy(groupCall = true, group = info.group, members = info.members, joined = info.joined)

/** A comma-separated JID list from walib, without device suffixes. */
fun String.jidList(): List<String> = split(',').map { normalizeJid(it.trim()) }.filter { it.isNotEmpty() }.distinct()

/** How many people a group call's title names before "and n others". */
private const val NAMED_IN_TITLE = 2

/**
 * A group call's title: its group's name, or for a call between chosen people their names,
 * like "Ann, Bob and Cat" or "Ann, Bob and 3 others".
 */
fun groupCallTitle(
    groupName: String?,
    names: List<String>,
): String {
    if (!groupName.isNullOrBlank()) return groupName
    return when {
        names.isEmpty() -> "Group call"
        names.size == 1 -> names[0]
        names.size <= NAMED_IN_TITLE + 1 -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        else -> names.take(NAMED_IN_TITLE).joinToString(", ") + " and ${names.size - NAMED_IN_TITLE} others"
    }
}

/** Who's in a group call now, for the line under its title: "Ann and Bob", "Ann, Bob and 2 others". */
fun groupCallJoined(names: List<String>): String = if (names.isEmpty()) "" else groupCallTitle(null, names)

/** The incoming call screen's line: "incoming call", "incoming group video call", … */
fun incomingLabel(c: CallUi): String = "incoming " + (if (c.groupCall) "group " else "") + (if (c.video) "video call" else "call")

/** At most this many participants' video at once: a 2×2 grid on the small screen. */
const val MAX_VIDEO_TILES = 4

/** A participant whose last frame is older than this has turned their camera off (or left). */
const val VIDEO_STALE_MS = 3_000L

/**
 * Whose video the grid shows: those with a frame in the last [VIDEO_STALE_MS], at most
 * [MAX_VIDEO_TILES], the most recently active first when there are more; drawn in the call's
 * own order ([members], the caller first), so tiles don't jump around.
 */
fun videoTiles(
    lastFrameAt: Map<String, Long>,
    members: List<String>,
    now: Long,
): List<String> {
    val live = lastFrameAt.filter { (_, at) -> at > 0 && now - at <= VIDEO_STALE_MS }
    val shown =
        live.entries
            .sortedByDescending { it.value }
            .take(MAX_VIDEO_TILES)
            .map { it.key }
            .toSet()
    val order = members.withIndex().associate { (i, m) -> m to i }
    return shown.sortedWith(compareBy<String> { order[it] ?: Int.MAX_VALUE }.thenBy { it })
}

/** A group call's name in the call log: its group's, or its participants' ([groupCallTitle]). */
fun callLogTitle(c: CallRecord): String =
    groupCallTitle(c.peer.takeIf { it.endsWith("@g.us") }?.let(MessageStore::title), c.members.map(MessageStore::title))
