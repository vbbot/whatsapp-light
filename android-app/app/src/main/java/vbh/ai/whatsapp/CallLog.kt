package vbh.ai.whatsapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File

/**
 * One call, as seen by this device. [peer] is the other party's chat JID (no device suffix); for
 * a group call, the group's, or the caller's when it was a call between chosen people. [members]
 * lists a group call's other participants (everyone who was in it or invited), caller first.
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
    val members: List<String> = emptyList(),
) {
    val isGroup: Boolean get() = members.isNotEmpty() || peer.endsWith("@g.us")

    enum class Outcome { ONGOING, ANSWERED, MISSED, DECLINED, NO_ANSWER }

    /** The missed-call notification's title: "Missed WhatsApp call", "Missed WhatsApp group video call", … */
    val missedTitle: String
        get() = "Missed WhatsApp " + (if (isGroup) "group " else "") + (if (video) "video call" else "call")

    /** Lowercase subtitle for the call log row: what happened, and for how long. */
    val summary: String
        get() =
            (
                when {
                    isGroup && video -> "group video · "
                    isGroup -> "group · "
                    video -> "video · "
                    else -> ""
                }
            ) +
                when (outcome) {
                    Outcome.ANSWERED -> (if (incoming) "incoming" else "outgoing") + " · " + formatDuration(endedAt - answeredAt)
                    Outcome.MISSED -> "missed"
                    Outcome.DECLINED -> "declined"
                    Outcome.NO_ANSWER -> "no answer"
                    Outcome.ONGOING -> if (incoming) "incoming" else "outgoing"
                }
}

/**
 * Call history: calls made while the app runs, plus records from history sync; newest first.
 * Stored in [ChatDb]; [calls] holds the newest 500 for the Calls tab.
 */
object CallLog {
    val calls = MutableStateFlow<List<CallRecord>>(emptyList())
    private var db: SQLiteDatabase? = null

    /** Calls the user declined, so the end event can tell "declined" from "missed". */
    private val declined = mutableSetOf<String>()

    @Synchronized
    fun init(context: Context) {
        if (db != null) return
        open(ChatDb.get(context).writableDatabase)
        migrateLegacyFile(context.applicationContext)
    }

    /** For tests: use an already-open database. */
    @Synchronized
    fun open(database: SQLiteDatabase) {
        db = database
        reload()
    }

    /** Moves calls with [from] onto [into] (one person's two addresses; see [ChatIdentity]). */
    @Synchronized
    fun mergePeer(
        from: String,
        into: String,
    ): Boolean {
        val d = db ?: return false
        if (from == into) return false
        val n =
            d.compileStatement("UPDATE calls SET peer = ? WHERE peer = ?").run {
                bindString(1, into)
                bindString(2, from)
                executeUpdateDelete()
            }
        if (n > 0) reload()
        return n > 0
    }

    /** Everyone in the call log. */
    @Synchronized
    fun peers(): Set<String> =
        db
            ?.rawQuery("SELECT DISTINCT peer FROM calls", null)
            ?.readAll { it.getString(0) }
            .orEmpty()
            .toSet()

    /** Deletes every call (logging out). */
    @Synchronized
    fun clearAll() {
        db?.delete("calls", null, null)
        declined.clear()
        reload()
    }

    private fun reload() {
        calls.value = db?.rawQuery("SELECT * FROM calls ORDER BY started_at DESC LIMIT 500", null)?.readAll(ChatDb::call).orEmpty()
    }

    @Synchronized
    fun start(
        id: String,
        peer: String,
        incoming: Boolean,
        video: Boolean = false,
        members: List<String> = emptyList(),
    ) {
        val d = db ?: return
        val rec = CallRecord(id, normalizeJid(peer), incoming, System.currentTimeMillis(), video = video, members = members)
        d.insertWithOnConflict("calls", null, ChatDb.values(rec), SQLiteDatabase.CONFLICT_IGNORE)
        reload()
    }

    /** Records from history sync; calls already known are left alone. */
    @Synchronized
    fun addAll(records: List<CallRecord>) {
        val d = db ?: return
        if (records.isEmpty()) return
        d.tx { records.forEach { insertWithOnConflict("calls", null, ChatDb.values(it), SQLiteDatabase.CONFLICT_IGNORE) } }
        reload()
    }

    @Synchronized
    fun answered(id: String) = update(id) { if (it.answeredAt == 0L) it.copy(answeredAt = System.currentTimeMillis()) else it }

    /** Adds a group call's newly reported participants; those already listed keep their place. */
    @Synchronized
    fun addMembers(
        id: String,
        members: List<String>,
    ) = update(id) { r -> (r.members + members).distinct().let { if (it == r.members) r else r.copy(members = it) } }

    @Synchronized
    fun markVideo(id: String) = update(id) { if (it.video) it else it.copy(video = true) }

    @Synchronized
    fun markDeclined(id: String) {
        declined += id
    }

    /** Records how call [id] ended; returns the finished record. */
    @Synchronized
    fun ended(id: String): CallRecord? =
        update(id) {
            val outcome =
                when {
                    it.answeredAt != 0L -> CallRecord.Outcome.ANSWERED
                    id in declined -> CallRecord.Outcome.DECLINED
                    it.incoming -> CallRecord.Outcome.MISSED
                    else -> CallRecord.Outcome.NO_ANSWER
                }
            it.copy(endedAt = System.currentTimeMillis(), outcome = outcome)
        }

    private fun update(
        id: String,
        f: (CallRecord) -> CallRecord,
    ): CallRecord? {
        val d = db ?: return null
        val cur = d.rawQuery("SELECT * FROM calls WHERE id = ?", arrayOf(id)).readAll(ChatDb::call).firstOrNull() ?: return null
        val next = f(cur)
        d.update("calls", ChatDb.values(next), "id = ?", arrayOf(id))
        reload()
        return next
    }

    /** One-time import of calls.json (renamed to calls.json.migrated afterwards). */
    private fun migrateLegacyFile(context: Context) {
        val f = File(context.filesDir, "calls.json")
        if (!f.exists()) return
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull()
        if (arr != null) {
            addAll(
                (0 until arr.length()).map { i ->
                    arr.getJSONObject(i).let { o ->
                        CallRecord(
                            o.getString("id"),
                            o.getString("peer"),
                            o.getBoolean("incoming"),
                            o.getLong("startedAt"),
                            o.optLong("answeredAt"),
                            o.optLong("endedAt"),
                            runCatching { CallRecord.Outcome.valueOf(o.getString("outcome")) }.getOrDefault(CallRecord.Outcome.MISSED),
                            video = o.optBoolean("video", false),
                        )
                    }
                },
            )
        }
        f.renameTo(File(context.filesDir, "calls.json.migrated"))
    }
}
