package vbh.ai.whatsapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Full-size photos for messages that only have their small embedded preview (or none), which is
 * all history sync brings. Two triggers share one queue:
 *
 * - [shown]: the photo came on screen. Fetched once per session.
 * - [opened]: the photo was opened. Goes ahead of everything shown, and tries again after an
 *   earlier failure.
 *
 * Then the most recently shown go first; a shown photo still waiting after [SHOWN_EXPIRY_MS] has
 * been scrolled past and is dropped. One download runs at a time. A finished download becomes
 * the message's media file, so the thread and the viewer both switch to it.
 */
object Photos {
    sealed interface State {
        data object Loading : State

        data class Failed(
            val reason: String,
        ) : State
    }

    /** Photos being fetched or that failed, by message ID; absent means idle or done. */
    val states = MutableStateFlow<Map<String, State>>(emptyMap())

    /** Downloads a message's full photo and returns the file's path; set by [WaCore] when the client starts. */
    @Volatile var downloader: ((Msg) -> String)? = null

    private const val SHOWN_EXPIRY_MS = 60_000L

    private val queue = PriorityBlockingQueue<Job>()
    private val order = AtomicLong()

    /** IDs a [shown] fetch was queued for this session. */
    private val attempted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val worker by lazy {
        thread(name = "wa-photos", isDaemon = true) {
            while (true) {
                val job = queue.take()
                // Scrolled past long ago: forget it, so it's fetched if it comes on screen again.
                if (job.expired()) attempted.remove(job.id) else fetch(job)
            }
        }
    }

    fun shown(m: Msg) {
        if (m.needsFullPhoto && attempted.add(m.id)) submit(m.id, opened = false)
    }

    fun opened(m: Msg) {
        if (!m.needsFullPhoto) return
        attempted.add(m.id)
        submit(m.id, opened = true)
    }

    private fun submit(
        id: String,
        opened: Boolean,
    ) {
        worker
        queue.put(Job(id, opened, order.incrementAndGet(), System.currentTimeMillis()))
    }

    private fun fetch(job: Job) {
        // Re-read: an earlier job may have fetched it already, or failed it (only opening retries).
        val m = MessageStore.get(job.id)?.takeIf { it.needsFullPhoto } ?: return
        if (!job.opened && states.value[job.id] is State.Failed) return
        val download = downloader
        if (download == null) {
            states.update { it + (job.id to State.Failed("not connected")) }
            return
        }
        states.update { it + (job.id to State.Loading) }
        try {
            MessageStore.setMedia(job.id, download(m))
            states.update { it - job.id }
        } catch (e: Exception) {
            WaState.log("photo ${job.id}: ${e.message}")
            states.update { it + (job.id to State.Failed(e.message ?: "download failed")) }
        }
    }

    internal class Job(
        val id: String,
        val opened: Boolean,
        val order: Long,
        val queuedAt: Long,
    ) : Comparable<Job> {
        fun expired() = !opened && System.currentTimeMillis() - queuedAt > SHOWN_EXPIRY_MS

        // Smallest first: opened before shown, then the latest request first.
        override fun compareTo(other: Job): Int = compareValuesBy(other, this, { it.opened }, { it.order })
    }
}
