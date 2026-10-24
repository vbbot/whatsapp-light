package vbh.ai.whatsapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/** One batch of history from the main phone (see walib's historyBatch). */
data class HistoryBatch(
    val onDemand: Boolean,
    /** Sync stage (INITIAL_BOOTSTRAP, RECENT, FULL, …) and progress 0-100 within it. */
    val type: String,
    val progress: Int,
    val messages: List<Msg>,
    val chats: Map<String, HistoryChat>,
    val calls: List<CallRecord>,
    /** Reactions on the batch's messages, with each message's chat. */
    val reactions: List<Pair<String, Reaction>> = emptyList(),
    /** Edits and deletes-for-everyone of earlier messages. */
    val edits: List<HistoryEdit> = emptyList(),
)

/** An edit of message [id] in a history batch ([text] is the new text), or a delete-for-everyone ([text] ""). */
data class HistoryEdit(
    val chat: String,
    val id: String,
    val text: String,
    val ts: Long,
    val fromMe: Boolean,
    val sender: String,
)

/** A conversation's details in a history batch; [mutedUntil] as in [ChatState]. */
data class HistoryChat(
    val name: String,
    val unread: Int,
    val archived: Boolean = false,
    val mutedUntil: Long = 0,
    val pinnedAt: Long = 0,
)

/**
 * History sync: the past messages, chat names and calls the main phone sends after linking (and
 * older messages asked for on demand). Imported without notifications. [syncing] tracks the
 * transfer after a link so the UI can say it's under way.
 */
object History {
    /** After linking: messages received so far, until the full history is in; else null. */
    val syncing = MutableStateFlow<Int?>(null)

    /** The first batch can take a few minutes after linking; between batches it's seconds. */
    private const val FIRST_BATCH_WAIT_MS = 3 * 60_000L
    private const val NEXT_BATCH_WAIT_MS = 60_000L

    @Volatile private var lastBatchAt = 0L

    /** A device was just linked: the phone will now send its history. */
    fun linked() {
        lastBatchAt = System.currentTimeMillis()
        syncing.value = 0
        // The phone uploads the history while this one may be asleep; stay awake until it's in.
        Wake.hold(FIRST_BATCH_WAIT_MS)
        // Ends the sync if the phone stops sending before the full history (e.g. it declined it).
        thread(name = "wa-history-watch", isDaemon = true) {
            while (true) {
                Thread.sleep(5_000)
                val count = syncing.value ?: break
                val wait = if (count == 0) FIRST_BATCH_WAIT_MS else NEXT_BATCH_WAIT_MS
                if (System.currentTimeMillis() - lastBatchAt > wait) syncing.compareAndSet(count, null)
            }
        }
    }

    /** Imports one batch (walib's OnHistory JSON) and advances [syncing]. */
    fun receive(json: String): HistoryBatch {
        val batch = parse(json)
        import(batch)
        if (!batch.onDemand) {
            lastBatchAt = System.currentTimeMillis()
            val done = batch.type == "FULL" && batch.progress >= 100
            if (!done) Wake.hold(NEXT_BATCH_WAIT_MS)
            syncing.update { n -> if (n == null || done) null else n + batch.messages.size }
        }
        return batch
    }

    fun parse(json: String): HistoryBatch {
        val o = JSONObject(json)
        val chats =
            o.optJSONArray("chats").objects().associate {
                it.getString("chat") to
                    HistoryChat(
                        it.optString("name"),
                        it.optInt("unread"),
                        it.optBoolean("archived"),
                        it.optLong("mutedUntil"),
                        it.optLong("pinnedAt"),
                    )
            }
        val raw =
            o.optJSONArray("messages").objects().map {
                Msg(
                    id = it.getString("id"),
                    chat = it.getString("chat"),
                    sender = it.getString("sender"),
                    senderName = it.optString("senderName"),
                    text = it.getString("text"),
                    ts = it.getLong("ts"),
                    fromMe = it.getBoolean("fromMe"),
                    isGroup = it.getBoolean("isGroup"),
                    status = it.optInt("status"),
                    media = it.optString("media"),
                    mediaRef = it.optString("mediaRef"),
                    quoteId = it.optString("quotedId"),
                    quoteSender = it.optString("quotedSender"),
                    quoteText = it.optString("quotedText"),
                )
            }
        // Old messages count as read (no blue ticks for them now), except the ones the phone
        // still shows as unread: those get receipts when the user opens the chat here.
        val stillUnread =
            raw
                .filter { !it.fromMe }
                .groupBy { it.chat }
                .flatMap { (chat, list) -> list.sortedByDescending { it.ts }.take(chats[chat]?.unread ?: 0) }
                .map { it.id }
                .toSet()
        val messages = raw.map { it.copy(readSent = it.id !in stillUnread) }
        val calls = o.optJSONArray("calls").objects().map(::call)
        val reactions =
            o.optJSONArray("messages").objects().flatMap { m ->
                m.optJSONArray("reactions").objects().map {
                    m.getString("chat") to Reaction(m.getString("id"), it.getString("reactor"), it.getString("emoji"), it.optLong("ts"))
                }
            }
        val edits =
            o.optJSONArray("edits").objects().map {
                HistoryEdit(
                    it.getString("chat"),
                    it.getString("id"),
                    it.optString("text"),
                    it.optLong("ts"),
                    it.optBoolean("fromMe"),
                    it.optString("sender"),
                )
            }
        return HistoryBatch(o.optBoolean("onDemand"), o.optString("type"), o.optInt("progress"), messages, chats, calls, reactions, edits)
    }

    fun import(batch: HistoryBatch) {
        MessageStore.batch {
            MessageStore.addAll(batch.messages)
            for ((chat, r) in batch.reactions) MessageStore.setReaction(chat, r.messageId, r.reactor, r.emoji, r.ts)
            // After the messages, which they change. A delete's media file stays until logging out.
            for (e in batch.edits) {
                if (e.text.isEmpty()) {
                    if (MessageStore.get(e.id)?.chat == e.chat) MessageStore.markDeleted(e.id)
                } else {
                    MessageStore.editMessage(e.chat, e.id, e.text, e.ts, e.fromMe, e.sender)
                }
            }
            for ((chat, info) in batch.chats) {
                MessageStore.setChatName(chat, info.name)
                if (!batch.onDemand) {
                    MessageStore.markReadExceptNewest(chat, info.unread)
                    // History only turns these on: app-state events (which may come first) are the
                    // authority, and a batch that leaves a field out mustn't undo them.
                    if (info.archived) MessageStore.setArchived(chat, true)
                    if (info.mutedUntil != 0L) MessageStore.setMutedUntil(chat, info.mutedUntil)
                    if (info.pinnedAt > 0L && !info.archived) MessageStore.setPinnedAt(chat, info.pinnedAt)
                }
            }
        }
        CallLog.addAll(batch.calls)
    }

    private fun call(o: JSONObject): CallRecord {
        val incoming = o.getBoolean("incoming")
        val started = o.getLong("startedAt")
        val connected = o.optString("result") == "connected"
        return CallRecord(
            id = o.getString("id"),
            peer = normalizeJid(o.getString("peer")),
            incoming = incoming,
            startedAt = started,
            answeredAt = if (connected) started else 0L,
            endedAt = started + o.optLong("duration") * 1000,
            outcome =
                when (o.optString("result")) {
                    "connected" -> CallRecord.Outcome.ANSWERED
                    "rejected" -> CallRecord.Outcome.DECLINED
                    else -> if (incoming) CallRecord.Outcome.MISSED else CallRecord.Outcome.NO_ANSWER
                },
            video = o.optBoolean("video"),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
}
