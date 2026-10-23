package vbh.ai.whatsapp

/**
 * One chat per person. WhatsApp reaches the same person through their phone number
 * (`…@s.whatsapp.net`) or their private ID (`…@lid`). New messages, receipts, history and calls
 * are already stored under one address (walib's `canonical`: the private ID when known). This
 * merges what was stored before that, or before a mapping was known: a phone-number chat whose
 * private ID is now known moves onto the private-ID chat, along with its calls.
 */
object ChatIdentity {
    /** The address to keep a chat under (walib's `CanonicalChat`); set by [WaCore] when the client starts. */
    @Volatile var canonical: ((String) -> String)? = null

    /**
     * Merges every phone-number chat and call peer whose private ID is known into it. Returns the
     * addresses merged into, so their names can be looked up again. Cheap when there's nothing
     * to do, so it runs on every connect and after each history batch (which brings mappings).
     */
    @Synchronized
    fun mergeDuplicates(): Set<String> {
        val resolve = canonical ?: return emptySet()
        val candidates = (MessageStore.knownChats() + CallLog.peers()).filter { it.endsWith("@s.whatsapp.net") }
        val merged = HashSet<String>()
        for (number in candidates) {
            val id = runCatching { resolve(number) }.getOrNull() ?: continue
            if (id == number || !id.endsWith("@lid")) continue
            val messages = MessageStore.mergeChat(number, id)
            val calls = CallLog.mergePeer(number, id)
            if (messages || calls) merged += id
        }
        if (merged.isNotEmpty()) WaState.log("moved ${merged.size} chats from phone numbers to private IDs")
        return merged
    }
}
