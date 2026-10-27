package vbh.ai.whatsapp

/**
 * @mentions. WhatsApp sends a mention as the text `@<user>` (the user part of the person's
 * address) plus the address itself ([Msg.mentions]); a message is stored that way and shown
 * with each token as `@Name`.
 */
object Mentions {
    /** [text] with each `@<user>` of [mentions] as `@` and [name] of that address. */
    fun shown(
        text: String,
        mentions: List<String>,
        name: (String) -> String?,
    ): String {
        if (mentions.isEmpty() || '@' !in text) return text
        var out = text
        for (jid in mentions) {
            val user = jid.substringBefore('@').ifEmpty { continue }
            val shownName = name(jid)?.takeIf { it.isNotBlank() } ?: continue
            // Not inside a longer number: "@123" mustn't match the start of "@1234".
            out = out.replace(Regex("@" + Regex.escape(user) + "(?![0-9A-Za-z])"), Regex.escapeReplacement("@$shownName"))
        }
        return out
    }

    /**
     * [text] as WhatsApp gets it: each `@Name` from [picked] (name to address) that's still in it
     * becomes `@<user>`. Returns that and the addresses it mentions. Longer names go first, so
     * "@Ana Maria" isn't taken for "@Ana".
     */
    fun toWire(
        text: String,
        picked: List<Pair<String, String>>,
    ): Pair<String, List<String>> {
        var out = text
        val jids = mutableListOf<String>()
        for ((name, jid) in picked.distinct().sortedByDescending { it.first.length }) {
            val token = "@$name"
            val user = jid.substringBefore('@')
            if (name.isEmpty() || user.isEmpty() || token !in out) continue
            out = out.replace(token, "@$user")
            jids += jid
        }
        return out to jids.distinct()
    }

    /** How a mention of [jid] reads: "You" for this account, else the person's name or number. */
    fun name(jid: String): String {
        val own = WaState.ownNumber.value
        if (own.isNotEmpty() && (displayNumber(jid) == own || MessageStore.phones.value[jid] == own)) return "You"
        return MessageStore.title(jid)
    }
}

/** [Msg.text] with its mentions named ([Mentions.shown]). */
fun Msg.shownText(name: (String) -> String? = Mentions::name): String = Mentions.shown(text, mentions.jidList(), name)

/** [Msg.caption] with its mentions named. */
fun Msg.shownCaption(name: (String) -> String? = Mentions::name): String = Mentions.shown(caption, mentions.jidList(), name)
