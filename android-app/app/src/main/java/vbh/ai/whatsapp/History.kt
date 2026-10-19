package vbh.ai.whatsapp

import org.json.JSONArray
import org.json.JSONObject

/** One batch of history from the main phone (see walib's historyBatch). */
data class HistoryBatch(
    val onDemand: Boolean,
    /** Sync stage (INITIAL_BOOTSTRAP, RECENT, FULL, …) and progress 0-100 within it. */
    val type: String,
    val progress: Int,
    val messages: List<Msg>,
    /** chat -> (name, unread count). */
    val chats: Map<String, Pair<String, Int>>,
    val calls: List<CallRecord>,
)

/** Imports history sync: past messages, chat names and calls. Never notifies. */
object History {
    fun parse(json: String): HistoryBatch {
        val o = JSONObject(json)
        val chats =
            o.optJSONArray("chats").objects().associate {
                it.getString("chat") to (it.optString("name") to it.optInt("unread"))
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
                )
            }
        // Old messages count as read (no blue ticks for them now), except the ones the phone
        // still shows as unread: those get receipts when the user opens the chat here.
        val stillUnread =
            raw
                .filter { !it.fromMe }
                .groupBy { it.chat }
                .flatMap { (chat, list) -> list.sortedByDescending { it.ts }.take(chats[chat]?.second ?: 0) }
                .map { it.id }
                .toSet()
        val messages = raw.map { it.copy(readSent = it.id !in stillUnread) }
        val calls = o.optJSONArray("calls").objects().map(::call)
        return HistoryBatch(o.optBoolean("onDemand"), o.optString("type"), o.optInt("progress"), messages, chats, calls)
    }

    fun import(batch: HistoryBatch) {
        MessageStore.batch {
            MessageStore.addAll(batch.messages)
            for ((chat, info) in batch.chats) {
                MessageStore.setChatName(chat, info.first)
                if (!batch.onDemand) MessageStore.markReadExceptNewest(chat, info.second)
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
