package vbh.ai.whatsapp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File

data class Msg(
    val id: String,
    val chat: String,
    val sender: String,
    val senderName: String,
    val text: String,
    val ts: Long,
    val fromMe: Boolean,
    val isGroup: Boolean,
    val pending: Boolean = false,
    /** For our own messages: how far it got. See [Status]. */
    val status: Int = Status.NONE,
    /** For incoming messages: we already sent a read receipt for it. */
    val readSent: Boolean = false,
    /** Local file for a photo (full size once downloaded) or a video's thumbnail; "" if none. */
    val media: String = "",
    /** Download info for the full photo (see [Photos]); "" if none. */
    val mediaRef: String = "",
) {
    /** A photo we only have the small embedded preview of. */
    val needsFullPhoto: Boolean get() = !isVideo && mediaRef.isNotEmpty() && (media.isEmpty() || isThumbnailFile(media))

    /**
     * Shown as a picture: there's a file, or the photo can be fetched. History photos often
     * come without an embedded preview, so the file may not exist yet.
     */
    val hasPicture: Boolean get() = media.isNotEmpty() || needsFullPhoto

    val isVideo: Boolean get() = text.startsWith("[video]")

    /** Caption, without the "[image]" / "[video]" placeholder. */
    val caption: String get() = text.removePrefix("[image]").removePrefix("[video]").trim()
}

/** walib saves a message's embedded preview as "<id>_t.jpg" and the full photo as "<id>.jpg". */
fun isThumbnailFile(path: String): Boolean = path.endsWith("_t.jpg")

/** Delivery state of our own messages; only ever moves forward. */
object Status {
    const val NONE = 0
    const val SENT = 1 // server accepted it (single tick)
    const val DELIVERED = 2 // reached their phone (double tick)
    const val SEEN = 3 // they opened it (blue ticks)

    fun label(s: Int): String? =
        when (s) {
            SENT -> "sent"
            DELIVERED -> "delivered"
            SEEN -> "seen"
            else -> null
        }
}

/** One row of the chat list: the chat, its newest message, and whether it has unread messages. */
data class ChatSummary(
    val chat: String,
    val last: Msg,
    val unread: Boolean,
)

/**
 * Messages and per-chat state, in [ChatDb]. Screens collect [changes] and re-run their (small,
 * indexed) queries when it moves. Pending local echoes (not yet acked by the server) are kept in
 * memory only and shown with the stored messages.
 */
object MessageStore {
    /** Bumped on every write; screens re-query when it changes. */
    val changes = MutableStateFlow(0L)
    val openChat = MutableStateFlow<String?>(null)

    /** Contact names and phone numbers resolved from WhatsApp's contact/LID stores, keyed by chat JID. */
    val names = MutableStateFlow<Map<String, String>>(emptyMap())
    val phones = MutableStateFlow<Map<String, String>>(emptyMap())

    @Volatile private var db: SQLiteDatabase? = null
    private val pending = mutableMapOf<String, Msg>()

    /** Receipts that arrived before the message they refer to was stored (id -> status). */
    private val earlyReceipts = mutableMapOf<String, Int>()

    @Synchronized
    fun init(context: Context) {
        if (db != null) return
        open(ChatDb.get(context).writableDatabase)
        migrateLegacyFiles(context.applicationContext)
    }

    /** For tests: use an already-open database. */
    @Synchronized
    fun open(database: SQLiteDatabase) {
        db = database
        pending.clear()
        earlyReceipts.clear()
        changes.value++
    }

    /** Pending echoes, copied under the lock (reads don't take it; SQLite handles its own). */
    @Synchronized
    private fun pendingNow(): List<Msg> = pending.values.toList()

    private var batchDepth = 0
    private var batchChanged = false

    private fun changed() {
        if (batchDepth > 0) batchChanged = true else changes.value++
    }

    /** Runs several writes as one change for the screens (history sync writes per chat). */
    @Synchronized
    fun <T> batch(block: () -> T): T {
        batchDepth++
        try {
            return block()
        } finally {
            if (--batchDepth == 0 && batchChanged) {
                batchChanged = false
                changes.value++
            }
        }
    }

    // ------------------------------------------------------------------ writes

    @Synchronized
    fun add(m: Msg) {
        if (m.pending) {
            pending[m.id] = m
            changed()
            return
        }
        val d = db ?: return
        d.tx { upsert(this, m) }
        changed()
    }

    /** Stores many messages at once (history sync). Existing rows keep their progress. */
    @Synchronized
    fun addAll(list: List<Msg>) {
        val d = db ?: return
        if (list.isEmpty()) return
        d.tx { list.forEach { upsert(this, it) } }
        changed()
    }

    private fun upsert(
        d: SQLiteDatabase,
        m: Msg,
    ) {
        val old =
            d.rawQuery("SELECT * FROM messages WHERE id = ?", arrayOf(m.id)).readAll(ChatDb::msg).firstOrNull()
        // Never lose progress when a message is re-delivered (echo from another device, history).
        val merged =
            m.copy(
                status = maxOf(m.status, old?.status ?: Status.NONE, earlyReceipts.remove(m.id) ?: Status.NONE),
                readSent = m.readSent || old?.readSent == true,
                // Never swap a downloaded photo back for its thumbnail (history re-sends those).
                media =
                    when {
                        m.media.isEmpty() -> old?.media.orEmpty()
                        old != null && old.media.isNotEmpty() && !isThumbnailFile(old.media) && isThumbnailFile(m.media) -> old.media
                        else -> m.media
                    },
                mediaRef = m.mediaRef.ifEmpty { old?.mediaRef.orEmpty() },
                senderName = m.senderName.ifEmpty { old?.senderName.orEmpty() },
            )
        d.insertWithOnConflict("messages", null, ChatDb.values(merged), SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Drops a pending local echo (it was sent and re-added under its real ID, or it failed). */
    @Synchronized
    fun remove(id: String) {
        if (pending.remove(id) != null) changed()
    }

    /** A photo finished downloading: point the message at the full-size file. */
    @Synchronized
    fun setMedia(
        id: String,
        path: String,
    ) {
        val d = db ?: return
        if (d.update("messages", android.content.ContentValues().apply { put("media", path) }, "id = ?", arrayOf(id)) > 0) changed()
    }

    /** Applies a delivered/seen receipt to our own messages; status only moves forward. */
    @Synchronized
    fun applyReceipt(
        ids: Set<String>,
        status: Int,
    ) {
        val d = db ?: return
        var changedRows = 0
        d.tx {
            for (id in ids) {
                val n =
                    compileStatement("UPDATE messages SET status = ? WHERE id = ? AND from_me = 1 AND status < ?").run {
                        bindLong(1, status.toLong())
                        bindString(2, id)
                        bindLong(3, status.toLong())
                        executeUpdateDelete()
                    }
                if (n == 0 && !exists(this, id)) {
                    earlyReceipts[id] = maxOf(status, earlyReceipts[id] ?: 0)
                }
                changedRows += n
            }
        }
        if (earlyReceipts.size > 200) {
            earlyReceipts.keys
                .take(earlyReceipts.size - 200)
                .toList()
                .forEach { earlyReceipts.remove(it) }
        }
        if (changedRows > 0) changed()
    }

    private fun exists(
        d: SQLiteDatabase,
        id: String,
    ) = d.rawQuery("SELECT 1 FROM messages WHERE id = ?", arrayOf(id)).use { it.moveToFirst() }

    /** The user has seen everything in [chat] up to now. */
    @Synchronized
    fun markRead(chat: String) {
        val d = db ?: return
        val newest = newestTs(d, chat) ?: return
        if (lastRead(d, chat) >= newest) return
        setLastRead(d, chat, newest)
        changed()
    }

    /** We read these messages on another of our devices: treat the chat as read up to them. */
    @Synchronized
    fun readElsewhere(
        chat: String,
        ids: Set<String>,
    ) {
        val d = db ?: return
        if (ids.isEmpty()) return
        val q = ids.joinToString(",") { "?" }
        val rows =
            d.rawQuery("SELECT chat, MAX(ts) FROM messages WHERE id IN ($q) GROUP BY chat", ids.toTypedArray()).readAll {
                it.getString(0) to
                    it.getLong(1)
            }
        val (key, newest) = rows.maxByOrNull { it.second } ?: return
        if (lastRead(d, key) < newest) setLastRead(d, key, newest)
        markReadSent(ids)
        changed()
    }

    /** History sync: everything in [chat] is read except its newest [unread] incoming messages. */
    @Synchronized
    fun markReadExceptNewest(
        chat: String,
        unread: Int,
    ) {
        val d = db ?: return
        val ts =
            if (unread <= 0) {
                newestTs(d, chat)
            } else {
                // Just before the oldest unread one.
                d
                    .rawQuery(
                        "SELECT ts FROM messages WHERE chat = ? AND from_me = 0 ORDER BY ts DESC LIMIT 1 OFFSET ?",
                        arrayOf(chat, (unread - 1).toString()),
                    ).use { if (it.moveToFirst()) it.getLong(0) - 1 else null }
            } ?: return
        if (lastRead(d, chat) >= ts) return
        setLastRead(d, chat, ts)
        changed()
    }

    /** Remembers a chat's display name from history sync (groups, saved contacts). */
    @Synchronized
    fun setChatName(
        chat: String,
        name: String,
    ) {
        val d = db ?: return
        if (name.isBlank()) return
        d.execSQL("INSERT INTO chats(chat, name) VALUES(?, ?) ON CONFLICT(chat) DO UPDATE SET name = excluded.name", arrayOf(chat, name))
        changed()
    }

    @Synchronized
    fun markReadSent(ids: Set<String>) {
        val d = db ?: return
        d.tx { ids.forEach { execSQL("UPDATE messages SET read_sent = 1 WHERE id = ?", arrayOf(it)) } }
        changed()
    }

    private fun newestTs(
        d: SQLiteDatabase,
        chat: String,
    ): Long? =
        d.rawQuery("SELECT MAX(ts) FROM messages WHERE chat = ?", arrayOf(chat)).use {
            if (it.moveToFirst() &&
                !it.isNull(0)
            ) {
                it.getLong(0)
            } else {
                null
            }
        }

    private fun lastRead(
        d: SQLiteDatabase,
        chat: String,
    ): Long = d.rawQuery("SELECT last_read FROM chats WHERE chat = ?", arrayOf(chat)).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun setLastRead(
        d: SQLiteDatabase,
        chat: String,
        ts: Long,
    ) = d.execSQL(
        "INSERT INTO chats(chat, last_read) VALUES(?, ?) ON CONFLICT(chat) DO UPDATE SET last_read = MAX(last_read, excluded.last_read)",
        arrayOf<Any>(chat, ts),
    )

    // ------------------------------------------------------------------ reads

    /** Chats ordered by most recent message, with their newest message and unread state. */
    fun chats(): List<ChatSummary> {
        val d = db ?: return emptyList()
        val stored =
            d
                .rawQuery(
                    """
                    SELECT m.*,
                      EXISTS(SELECT 1 FROM messages u WHERE u.chat = m.chat AND u.from_me = 0
                             AND u.ts > IFNULL((SELECT last_read FROM chats c WHERE c.chat = m.chat), 0)) AS unread
                    FROM (SELECT chat, MAX(ts) AS newest FROM messages GROUP BY chat) t
                    JOIN messages m ON m.chat = t.chat AND m.ts = t.newest
                    ORDER BY m.ts DESC
                    """.trimIndent(),
                    null,
                ).readAll {
                    ChatSummary(
                        it.getString(it.getColumnIndexOrThrow("chat")),
                        ChatDb.msg(it),
                        it.getInt(it.getColumnIndexOrThrow("unread")) == 1,
                    )
                }
                // Two messages with the same newest timestamp: keep one.
                .distinctBy { it.chat }
        val pending = pendingNow()
        if (pending.isEmpty()) return stored
        // A chat whose only message is still sending (a brand-new chat) shows up too.
        val byChat = stored.associateBy { it.chat }.toMutableMap()
        for (p in pending) {
            val cur = byChat[p.chat]
            if (cur == null || p.ts > cur.last.ts) byChat[p.chat] = ChatSummary(p.chat, p, cur?.unread ?: false)
        }
        return byChat.values.sortedByDescending { it.last.ts }
    }

    /** The newest [limit] messages of [chat], oldest first, including pending local echoes. */
    fun thread(
        chat: String,
        limit: Int,
    ): List<Msg> {
        val d = db ?: return pendingNow().filter { it.chat == chat }.sortedBy { it.ts }
        val stored =
            d
                .rawQuery(
                    "SELECT * FROM (SELECT * FROM messages WHERE chat = ? ORDER BY ts DESC LIMIT ?) ORDER BY ts ASC",
                    arrayOf(chat, limit.toString()),
                ).readAll(ChatDb::msg)
        val mine = pendingNow().filter { it.chat == chat }
        return if (mine.isEmpty()) stored else (stored + mine).sortedBy { it.ts }
    }

    /** One stored message, or null. */
    fun get(id: String): Msg? = db?.rawQuery("SELECT * FROM messages WHERE id = ?", arrayOf(id))?.readAll(ChatDb::msg)?.firstOrNull()

    /** How many messages of [chat] are stored. */
    fun count(chat: String): Int =
        db?.rawQuery("SELECT COUNT(*) FROM messages WHERE chat = ?", arrayOf(chat))?.use { if (it.moveToFirst()) it.getInt(0) else 0 } ?: 0

    /** The oldest stored message of [chat]: the anchor for asking the main phone for earlier ones. */
    fun oldest(chat: String): Msg? =
        db?.rawQuery("SELECT * FROM messages WHERE chat = ? ORDER BY ts ASC LIMIT 1", arrayOf(chat))?.readAll(ChatDb::msg)?.firstOrNull()

    /**
     * Moves everything stored under [from] onto [into]: one person's two addresses become one chat
     * (see [ChatIdentity]). Their messages, their name as a sender, the read position (the later of
     * the two) and the chat name (kept from [into] if it has one). Returns whether anything moved.
     */
    @Synchronized
    fun mergeChat(
        from: String,
        into: String,
    ): Boolean {
        val d = db ?: return false
        if (from == into) return false
        val moved =
            d.tx {
                val n =
                    compileStatement("UPDATE messages SET chat = ? WHERE chat = ?").run {
                        bindString(1, into)
                        bindString(2, from)
                        executeUpdateDelete()
                    }
                execSQL("UPDATE messages SET sender = ? WHERE sender = ?", arrayOf(into, from))
                val state =
                    rawQuery("SELECT last_read, name FROM chats WHERE chat = ?", arrayOf(from)).use {
                        if (it.moveToFirst()) {
                            it.getLong(0) to
                                it.getString(1)
                        } else {
                            null
                        }
                    }
                if (state != null) {
                    setLastRead(this, into, state.first)
                    if (state.second.isNotBlank()) {
                        execSQL("UPDATE chats SET name = ? WHERE chat = ? AND name = ''", arrayOf(state.second, into))
                    }
                    execSQL("DELETE FROM chats WHERE chat = ?", arrayOf(from))
                }
                n > 0 || state != null
            }
        if (moved) changed()
        return moved
    }

    /** Every chat address we hold anything for: messages or chat state (read position, name). */
    fun knownChats(): Set<String> =
        db
            ?.rawQuery("SELECT chat FROM messages UNION SELECT chat FROM chats", null)
            ?.readAll { it.getString(0) }
            .orEmpty()
            .toSet()

    /** All chat IDs (for resolving names and the contact pickers). */
    fun chatIds(): List<String> = db?.rawQuery("SELECT DISTINCT chat FROM messages", null)?.readAll { it.getString(0) }.orEmpty()

    /** Incoming messages in [chat] we haven't sent a read receipt for yet. */
    fun needingReadReceipt(chat: String): List<Msg> =
        db
            ?.rawQuery(
                "SELECT * FROM (SELECT * FROM messages WHERE chat = ? AND from_me = 0 AND read_sent = 0 ORDER BY ts DESC LIMIT 50) ORDER BY ts ASC",
                arrayOf(chat),
            )?.readAll(ChatDb::msg)
            .orEmpty()

    /** Saved name, else history chat name, else push name from messages, else a number, else a placeholder. */
    fun title(chat: String): String {
        names.value[chat]?.takeIf { it.isNotBlank() }?.let { return it }
        val d = db
        if (d != null) {
            d.rawQuery("SELECT name FROM chats WHERE chat = ?", arrayOf(chat)).use {
                if (it.moveToFirst() &&
                    it.getString(0).isNotBlank()
                ) {
                    return it.getString(0)
                }
            }
            d
                .rawQuery(
                    "SELECT sender_name FROM messages WHERE chat = ? AND from_me = 0 AND is_group = 0 AND sender_name != '' ORDER BY ts DESC LIMIT 1",
                    arrayOf(chat),
                ).use { if (it.moveToFirst()) return it.getString(0) }
        }
        return fallbackTitle(chat, phones.value[chat])
    }

    /** Title when nothing better is known: a phone number, else "Group" / "Unknown contact". */
    fun fallbackTitle(
        chat: String,
        phone: String?,
    ): String {
        phone?.takeIf { it.isNotBlank() }?.let { return it }
        displayNumber(chat).takeIf { it.isNotEmpty() }?.let { return it }
        return if (chat.endsWith("@g.us")) "Group" else "Unknown contact"
    }

    // ------------------------------------------------------------------ migration

    /**
     * One-time import of the JSON files and read-state preferences used before this database.
     * The old files are renamed to *.migrated, not deleted.
     */
    fun migrateLegacyFiles(context: Context) {
        val d = db ?: return
        val dir = context.filesDir
        val msgs = File(dir, "messages.json")
        if (msgs.exists()) {
            val arr = runCatching { JSONArray(msgs.readText()) }.getOrNull()
            if (arr != null) {
                val list =
                    (0 until arr.length()).map { i ->
                        arr.getJSONObject(i).let { o ->
                            Msg(
                                o.getString("id"),
                                o.getString("chat"),
                                o.getString("sender"),
                                o.getString("senderName"),
                                o.getString("text"),
                                o.getLong("ts"),
                                o.getBoolean("fromMe"),
                                o.getBoolean("isGroup"),
                                status = o.optInt("status", Status.NONE),
                                readSent = o.optBoolean("readSent", false),
                                media = o.optString("media", ""),
                            )
                        }
                    }
                addAll(list)
            }
            msgs.renameTo(File(dir, "messages.json.migrated"))
        }
        val prefs = context.getSharedPreferences("read", Context.MODE_PRIVATE)
        if (prefs.all.isNotEmpty()) {
            d.tx { prefs.all.forEach { (chat, ts) -> (ts as? Long)?.let { setLastRead(this, chat, it) } } }
            prefs.edit().clear().apply()
            changed()
        }
    }
}
