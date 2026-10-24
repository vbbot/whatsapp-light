package vbh.ai.whatsapp

import kotlin.concurrent.thread

/**
 * Sends our queued messages. A message is stored as soon as it's written ([Status.QUEUED]),
 * under the WhatsApp ID it will be sent with, so it survives the app restarting, and sending it
 * again after a lost reply can't deliver it twice. [flush] sends the queue oldest first, which
 * keeps each chat's order. It runs after each new message and whenever the connection comes back.
 *
 * Offline, or when the connection drops mid-send, the message stays queued for the next
 * [flush]. When WhatsApp refuses it for another reason it becomes [Status.NOT_SENT], to be
 * retried ([retry]) or deleted.
 */
object Outbox {
    /** Sends one queued message (throws if it can't); set by [WaCore] when the client starts. */
    @Volatile var sender: ((Msg) -> Unit)? = null

    /** Whether the connection is up; set by [WaCore]. */
    @Volatile var isConnected: () -> Boolean = { false }

    /** Waits before trying again after a failure that looks like the connection's (tests shorten it). */
    @Volatile var retryDelayMs = 10_000L

    /** Tries per message for connection-like failures while connected, before leaving it for the next flush. */
    private const val TRIES = 3

    private var running = false
    private var again = false

    /** Sends whatever is queued, in the background. Safe to call often: one flush runs at a time. */
    fun flush() {
        synchronized(this) {
            if (running) {
                again = true
                return
            }
            running = true
        }
        thread(name = "wa-outbox", isDaemon = true) {
            while (true) {
                runCatching { sendQueued() }.onFailure { WaState.log("outbox: ${it.message}") }
                synchronized(this) {
                    if (!again) {
                        running = false
                        return@thread
                    }
                    again = false
                }
            }
        }
    }

    /** Queues a refused message again and sends it. */
    fun retry(m: Msg) {
        MessageStore.setUnsent(m.id, Status.QUEUED)
        flush()
    }

    private fun sendQueued() {
        for (m in MessageStore.queued()) {
            if (!sendOne(m)) return
        }
    }

    /** Sends [m]; false if the connection is gone, so the rest must wait too. */
    private fun sendOne(m: Msg): Boolean {
        repeat(TRIES) { attempt ->
            val send = sender
            if (send == null || !isConnected()) return false
            // Deleted while waiting.
            if (MessageStore.get(m.id)?.status != Status.QUEUED) return true
            try {
                send(m)
                MessageStore.applyReceipt(setOf(m.id), Status.SENT)
                return true
            } catch (e: Exception) {
                WaState.log("outbox: ${m.id} attempt ${attempt + 1}: ${e.message}")
                if (!isConnected()) return false
                if (!looksLikeConnection(e.message)) {
                    MessageStore.setUnsent(m.id, Status.NOT_SENT)
                    WaState.error.value = "A message wasn't sent (${e.message}). Long-press it to retry or delete it."
                    return true
                }
                Thread.sleep(retryDelayMs)
            }
        }
        // Still failing like the connection: leave it queued for the next flush.
        return false
    }

    /** Errors from a connection that's down or dropping, as opposed to WhatsApp refusing the message. */
    fun looksLikeConnection(message: String?): Boolean {
        val m = message.orEmpty().lowercase()
        return listOf("not connected", "websocket", "timed out", "timeout", "deadline exceeded", "connection", "eof", "broken pipe")
            .any { it in m }
    }
}
