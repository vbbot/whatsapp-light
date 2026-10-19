package vbh.ai.whatsapp

/**
 * Where the single receipt tag goes in a 1:1 thread, and what it says (Chats' rules):
 * - only our newest message gets "sent"/"delivered";
 * - "seen" marks the newest message they've read, even if we sent more after it;
 * - their reply being the newest message drops the tag (a reply says they read it),
 *   except "seen" stays when they haven't read our newest message yet.
 */
fun receiptTag(msgs: List<Msg>): Pair<String, String>? {
    val newestMine = msgs.lastOrNull { it.fromMe && !it.pending } ?: return null
    val newestSeen = msgs.lastOrNull { it.fromMe && it.status >= Status.SEEN }
    val replyIsNewest = msgs.lastOrNull()?.fromMe == false
    val seen =
        if (newestSeen != null && newestSeen.id == newestMine.id && replyIsNewest) {
            null
        } else {
            newestSeen?.let { it.id to "seen" }
        }
    val progress =
        newestMine
            .takeIf { !replyIsNewest && it.status in Status.SENT..Status.DELIVERED }
            ?.let { it.id to Status.label(it.status)!! }
    return if (newestSeen?.id == newestMine.id) seen else progress ?: seen
}
