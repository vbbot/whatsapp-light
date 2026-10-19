package com.wapoc.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One call, as seen by this device. [peer] is the other party's chat JID (no device suffix).
 * Outcome is filled in when the call ends.
 */
data class CallRecord(
    val id: String,
    val peer: String,
    val incoming: Boolean,
    val startedAt: Long,
    val answeredAt: Long = 0L,
    val endedAt: Long = 0L,
    val outcome: Outcome = Outcome.ONGOING,
    val video: Boolean = false,
) {
    enum class Outcome { ONGOING, ANSWERED, MISSED, DECLINED, NO_ANSWER }

    /** Lowercase subtitle for the call log row: what happened, and for how long. */
    val summary: String
        get() = (if (video) "video · " else "") + when (outcome) {
            Outcome.ANSWERED -> (if (incoming) "incoming" else "outgoing") + " · " + formatDuration(endedAt - answeredAt)
            Outcome.MISSED -> "missed"
            Outcome.DECLINED -> "declined"
            Outcome.NO_ANSWER -> "no answer"
            Outcome.ONGOING -> if (incoming) "incoming" else "outgoing"
        }
}

/** Call history recorded while the app is connected; newest first; persisted as JSON. */
object CallLog {
    val calls = MutableStateFlow<List<CallRecord>>(emptyList())
    private var file: File? = null
    /** Calls the user declined, so the end event can tell "declined" from "missed". */
    private val declined = mutableSetOf<String>()

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "calls.json")
        runCatching {
            val arr = JSONArray(file!!.readText())
            calls.value = (0 until arr.length()).map {
                arr.getJSONObject(it).let { o ->
                    CallRecord(
                        o.getString("id"), o.getString("peer"), o.getBoolean("incoming"), o.getLong("startedAt"),
                        o.optLong("answeredAt"), o.optLong("endedAt"),
                        runCatching { CallRecord.Outcome.valueOf(o.getString("outcome")) }.getOrDefault(CallRecord.Outcome.MISSED),
                        video = o.optBoolean("video", false),
                    )
                }
            }
        }
    }

    @Synchronized
    fun start(id: String, peer: String, incoming: Boolean, video: Boolean = false) {
        if (calls.value.any { it.id == id }) return
        calls.value = (listOf(CallRecord(id, normalizeJid(peer), incoming, System.currentTimeMillis(), video = video)) + calls.value).take(500)
        save()
    }

    @Synchronized
    fun answered(id: String) = update(id) { if (it.answeredAt == 0L) it.copy(answeredAt = System.currentTimeMillis()) else it }

    @Synchronized
    fun markVideo(id: String) = update(id) { if (it.video) it else it.copy(video = true) }

    @Synchronized
    fun markDeclined(id: String) { declined += id }

    @Synchronized
    fun ended(id: String) = update(id) {
        val outcome = when {
            it.answeredAt != 0L -> CallRecord.Outcome.ANSWERED
            id in declined -> CallRecord.Outcome.DECLINED
            it.incoming -> CallRecord.Outcome.MISSED
            else -> CallRecord.Outcome.NO_ANSWER
        }
        it.copy(endedAt = System.currentTimeMillis(), outcome = outcome)
    }

    private fun update(id: String, f: (CallRecord) -> CallRecord) {
        calls.value = calls.value.map { if (it.id == id) f(it) else it }
        save()
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        calls.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("peer", it.peer).put("incoming", it.incoming)
                .put("startedAt", it.startedAt).put("answeredAt", it.answeredAt).put("endedAt", it.endedAt)
                .put("outcome", it.outcome.name).put("video", it.video))
        }
        runCatching { f.writeText(arr.toString()) }
    }
}
