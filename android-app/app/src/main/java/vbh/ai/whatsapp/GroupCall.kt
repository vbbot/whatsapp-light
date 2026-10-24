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
