package com.wapoc.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
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
)

/** Delivery state of our own messages; only ever moves forward. */
object Status {
    const val NONE = 0
    const val SENT = 1      // server accepted it (single tick)
    const val DELIVERED = 2 // reached their phone (double tick)
    const val SEEN = 3      // they opened it (blue ticks)

    fun label(s: Int): String? = when (s) {
        SENT -> "sent"
        DELIVERED -> "delivered"
        SEEN -> "seen"
        else -> null
    }
}

/** In-memory message list with a simple JSON file for persistence (PoC-grade). */
object MessageStore {
    val messages = MutableStateFlow<List<Msg>>(emptyList())
    val openChat = MutableStateFlow<String?>(null)

    /** Contact names and phone numbers resolved from WhatsApp's contact/LID stores, keyed by chat JID. */
    val names = MutableStateFlow<Map<String, String>>(emptyMap())
    val phones = MutableStateFlow<Map<String, String>>(emptyMap())
    private var file: File? = null
    private var prefs: android.content.SharedPreferences? = null

    /** Timestamp (ms) of the newest message seen in each chat; drives the unread asterisk. */
    val lastRead = MutableStateFlow<Map<String, Long>>(emptyMap())

    fun markRead(chat: String) {
        val newest = messages.value.filter { it.chat == chat }.maxOfOrNull { it.ts } ?: return
        if ((lastRead.value[chat] ?: 0L) >= newest) return
        lastRead.value = lastRead.value + (chat to newest)
        prefs?.edit()?.putLong(chat, newest)?.apply()
    }

    fun hasUnread(chat: String, all: List<Msg>, read: Map<String, Long>): Boolean {
        val seen = read[chat] ?: 0L
        return all.any { it.chat == chat && !it.fromMe && it.ts > seen }
    }

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        file = File(context.filesDir, "messages.json")
        prefs = context.getSharedPreferences("read", Context.MODE_PRIVATE).also { p ->
            @Suppress("UNCHECKED_CAST")
            lastRead.value = (p.all as Map<String, Long>)
        }
        runCatching {
            val arr = JSONArray(file!!.readText())
            messages.value = (0 until arr.length()).map {
                arr.getJSONObject(it).let { o ->
                    Msg(
                        o.getString("id"), o.getString("chat"), o.getString("sender"), o.getString("senderName"),
                        o.getString("text"), o.getLong("ts"), o.getBoolean("fromMe"), o.getBoolean("isGroup"),
                        status = o.optInt("status", Status.NONE), readSent = o.optBoolean("readSent", false),
                    )
                }
            }
        }
    }

    /** Receipts that arrived before the message they refer to was stored (id -> status). */
    private val earlyReceipts = mutableMapOf<String, Int>()

    @Synchronized
    fun add(m: Msg) {
        val cur = messages.value
        val old = cur.firstOrNull { it.id == m.id }
        // Never lose progress when a message is re-delivered (echo from another device, etc.).
        val merged = m.copy(
            status = maxOf(m.status, old?.status ?: Status.NONE, earlyReceipts.remove(m.id) ?: Status.NONE),
            readSent = m.readSent || old?.readSent == true,
        )
        // replace a pending local echo or duplicate with the same id
        messages.value = (cur.filterNot { it.id == m.id } + merged).sortedBy { it.ts }.takeLast(5000)
        save()
    }

    /** Applies a delivered/seen receipt to our own messages; status only moves forward. */
    @Synchronized
    fun applyReceipt(ids: Set<String>, status: Int) {
        val known = messages.value.map { it.id }.toSet()
        ids.filter { it !in known }.forEach { earlyReceipts[it] = maxOf(status, earlyReceipts[it] ?: 0) }
        if (earlyReceipts.size > 200) earlyReceipts.keys.take(earlyReceipts.size - 200).toList().forEach { earlyReceipts.remove(it) }
        var changed = false
        messages.value = messages.value.map {
            if (it.id in ids && it.fromMe && it.status < status) { changed = true; it.copy(status = status) } else it
        }
        if (changed) save()
    }

    /** We read these messages on another of our devices: treat the chat as read up to them. */
    fun readElsewhere(chat: String, ids: Set<String>) {
        val newest = messages.value.filter { it.id in ids }.maxOfOrNull { it.ts } ?: return
        val key = messages.value.firstOrNull { it.id in ids }?.chat ?: chat
        if ((lastRead.value[key] ?: 0L) >= newest) return
        lastRead.value = lastRead.value + (key to newest)
        prefs?.edit()?.putLong(key, newest)?.apply()
        markReadSent(ids)
    }

    /** Incoming messages in [chat] we haven't sent a read receipt for yet. */
    fun needingReadReceipt(chat: String): List<Msg> =
        messages.value.filter { it.chat == chat && !it.fromMe && !it.readSent && !it.pending }.takeLast(50)

    @Synchronized
    fun markReadSent(ids: Set<String>) {
        messages.value = messages.value.map { if (it.id in ids) it.copy(readSent = true) else it }
        save()
    }

    /** Chats ordered by most recent message. */
    fun chats(all: List<Msg>): List<Pair<String, Msg>> =
        all.groupBy { it.chat }.map { it.key to it.value.maxBy { m -> m.ts } }.sortedByDescending { it.second.ts }

    /** Saved name, else push name from messages, else a phone number, else "Unknown contact". */
    fun title(chat: String, all: List<Msg>): String {
        names.value[chat]?.takeIf { it.isNotBlank() }?.let { return it }
        all.lastOrNull { it.chat == chat && !it.fromMe && !it.isGroup && it.senderName.isNotBlank() }?.senderName?.let { return it }
        phones.value[chat]?.takeIf { it.isNotBlank() }?.let { return it }
        displayNumber(chat).takeIf { it.isNotEmpty() }?.let { return it }
        return if (chat.endsWith("@g.us")) "Group" else "Unknown contact"
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        messages.value.filterNot { it.pending }.forEach {
            arr.put(JSONObject().put("id", it.id).put("chat", it.chat).put("sender", it.sender)
                .put("senderName", it.senderName).put("text", it.text).put("ts", it.ts)
                .put("fromMe", it.fromMe).put("isGroup", it.isGroup)
                .put("status", it.status).put("readSent", it.readSent))
        }
        runCatching { f.writeText(arr.toString()) }
    }
}
