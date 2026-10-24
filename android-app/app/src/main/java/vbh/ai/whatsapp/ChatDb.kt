package vbh.ai.whatsapp

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * App data: messages, calls and per-chat state. WhatsApp's own session state lives separately
 * in wa.db (whatsmeow's store). Files are app-private, like the official client's.
 */
class ChatDb(
    context: Context,
    name: String? = NAME,
) : SQLiteOpenHelper(context, name, null, VERSION) {
    // Reads (screens) run alongside writes (incoming messages, history) instead of queueing.
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY,
                chat TEXT NOT NULL,
                sender TEXT NOT NULL,
                sender_name TEXT NOT NULL DEFAULT '',
                text TEXT NOT NULL,
                ts INTEGER NOT NULL,
                from_me INTEGER NOT NULL,
                is_group INTEGER NOT NULL,
                status INTEGER NOT NULL DEFAULT 0,
                read_sent INTEGER NOT NULL DEFAULT 0,
                media TEXT NOT NULL DEFAULT '',
                media_ref TEXT NOT NULL DEFAULT '',
                quote_id TEXT NOT NULL DEFAULT '',
                quote_sender TEXT NOT NULL DEFAULT '',
                quote_text TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX messages_chat_ts ON messages(chat, ts)")
        db.execSQL(
            """
            CREATE TABLE calls (
                id TEXT PRIMARY KEY,
                peer TEXT NOT NULL,
                incoming INTEGER NOT NULL,
                started_at INTEGER NOT NULL,
                answered_at INTEGER NOT NULL DEFAULT 0,
                ended_at INTEGER NOT NULL DEFAULT 0,
                outcome TEXT NOT NULL,
                video INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX calls_started ON calls(started_at)")
        // last_read: newest message timestamp the user has seen (drives the unread marker).
        // name: chat name from history sync (groups, saved contacts), if any.
        // archived: kept out of the chat list. muted_until: 0 not muted, -1 until unmuted, else Unix ms.
        // pinned_at: 0 not pinned, else when it was pinned (Unix ms).
        db.execSQL(
            "CREATE TABLE chats (chat TEXT PRIMARY KEY, last_read INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL DEFAULT '', " +
                "archived INTEGER NOT NULL DEFAULT 0, muted_until INTEGER NOT NULL DEFAULT 0, pinned_at INTEGER NOT NULL DEFAULT 0, " +
                "reacted_at INTEGER NOT NULL DEFAULT 0)",
        )
        createReactions(db)
    }

    /** One reaction per person per message (WhatsApp replaces, never adds). */
    private fun createReactions(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE reactions (message_id TEXT NOT NULL, chat TEXT NOT NULL, reactor TEXT NOT NULL, " +
                "emoji TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (message_id, reactor))",
        )
        db.execSQL("CREATE INDEX reactions_chat ON reactions(chat)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int,
    ) {
        // 2: download info for fetching full photos on demand.
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN media_ref TEXT NOT NULL DEFAULT ''")
        // 3: archive and mute state.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE chats ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE chats ADD COLUMN muted_until INTEGER NOT NULL DEFAULT 0")
        }
        // 4: pinned chats.
        if (oldVersion < 4) db.execSQL("ALTER TABLE chats ADD COLUMN pinned_at INTEGER NOT NULL DEFAULT 0")
        // 5: what a reply answers.
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE messages ADD COLUMN quote_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN quote_sender TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN quote_text TEXT NOT NULL DEFAULT ''")
        }
        // 6: reactions.
        if (oldVersion < 6) createReactions(db)
        // 7: when someone last reacted to one of ours (it marks the chat unread).
        if (oldVersion < 7) db.execSQL("ALTER TABLE chats ADD COLUMN reacted_at INTEGER NOT NULL DEFAULT 0")
    }

    companion object {
        const val NAME = "chats.db"
        const val VERSION = 7

        @Volatile private var shared: ChatDb? = null

        /** The app's one connection to chats.db (shared by [MessageStore] and [CallLog]). */
        fun get(context: Context): ChatDb =
            shared ?: synchronized(this) { shared ?: ChatDb(context.applicationContext).also { shared = it } }

        fun values(m: Msg) =
            ContentValues().apply {
                put("id", m.id)
                put("chat", m.chat)
                put("sender", m.sender)
                put("sender_name", m.senderName)
                put("text", m.text)
                put("ts", m.ts)
                put("from_me", if (m.fromMe) 1 else 0)
                put("is_group", if (m.isGroup) 1 else 0)
                put("status", m.status)
                put("read_sent", if (m.readSent) 1 else 0)
                put("media", m.media)
                put("media_ref", m.mediaRef)
                put("quote_id", m.quoteId)
                put("quote_sender", m.quoteSender)
                put("quote_text", m.quoteText)
            }

        fun msg(c: Cursor) =
            Msg(
                id = c.getString(c.getColumnIndexOrThrow("id")),
                chat = c.getString(c.getColumnIndexOrThrow("chat")),
                sender = c.getString(c.getColumnIndexOrThrow("sender")),
                senderName = c.getString(c.getColumnIndexOrThrow("sender_name")),
                text = c.getString(c.getColumnIndexOrThrow("text")),
                ts = c.getLong(c.getColumnIndexOrThrow("ts")),
                fromMe = c.getInt(c.getColumnIndexOrThrow("from_me")) == 1,
                isGroup = c.getInt(c.getColumnIndexOrThrow("is_group")) == 1,
                status = c.getInt(c.getColumnIndexOrThrow("status")),
                readSent = c.getInt(c.getColumnIndexOrThrow("read_sent")) == 1,
                media = c.getString(c.getColumnIndexOrThrow("media")),
                mediaRef = c.getString(c.getColumnIndexOrThrow("media_ref")),
                quoteId = c.getString(c.getColumnIndexOrThrow("quote_id")),
                quoteSender = c.getString(c.getColumnIndexOrThrow("quote_sender")),
                quoteText = c.getString(c.getColumnIndexOrThrow("quote_text")),
            )

        fun values(r: CallRecord) =
            ContentValues().apply {
                put("id", r.id)
                put("peer", r.peer)
                put("incoming", if (r.incoming) 1 else 0)
                put("started_at", r.startedAt)
                put("answered_at", r.answeredAt)
                put("ended_at", r.endedAt)
                put("outcome", r.outcome.name)
                put("video", if (r.video) 1 else 0)
            }

        fun call(c: Cursor) =
            CallRecord(
                id = c.getString(c.getColumnIndexOrThrow("id")),
                peer = c.getString(c.getColumnIndexOrThrow("peer")),
                incoming = c.getInt(c.getColumnIndexOrThrow("incoming")) == 1,
                startedAt = c.getLong(c.getColumnIndexOrThrow("started_at")),
                answeredAt = c.getLong(c.getColumnIndexOrThrow("answered_at")),
                endedAt = c.getLong(c.getColumnIndexOrThrow("ended_at")),
                outcome =
                    runCatching { CallRecord.Outcome.valueOf(c.getString(c.getColumnIndexOrThrow("outcome"))) }
                        .getOrDefault(CallRecord.Outcome.MISSED),
                video = c.getInt(c.getColumnIndexOrThrow("video")) == 1,
            )
    }
}

/** Runs [block] in one transaction. */
inline fun <T> SQLiteDatabase.tx(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val r = block()
        setTransactionSuccessful()
        return r
    } finally {
        endTransaction()
    }
}

inline fun <T> Cursor.readAll(row: (Cursor) -> T): List<T> =
    use {
        val out = ArrayList<T>(count.coerceAtLeast(0))
        while (moveToNext()) out += row(this)
        out
    }
