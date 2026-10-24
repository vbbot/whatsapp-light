package vbh.ai.whatsapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Downloads of full-size photos and of videos, for messages that only have their small embedded
 * preview (or none), which is all history sync brings. Two triggers share one queue:
 *
 * - [shown]: a photo, a GIF (it plays in the thread) or a video of up to [SHOWN_VIDEO_MAX_BYTES]
 *   came on screen. Fetched once per session. Bigger videos wait to be opened.
 * - [opened]: a photo or video was opened. Goes ahead of everything shown, and tries again after
 *   an earlier failure.
 *
 * Then the most recently shown go first; a shown one still waiting after [SHOWN_EXPIRY_MS] has
 * been scrolled past and is dropped. One download runs at a time. A finished photo becomes the
 * message's media file, so the thread and the viewer both switch to it; a finished video is
 * [videoFile], and a frame from it becomes the message's media file instead of the blurry
 * embedded preview.
 */
object Media {
    sealed interface State {
        data object Loading : State

        data class Failed(
            val reason: String,
        ) : State
    }

    /** Downloads being fetched or that failed, by message ID; absent means idle or done. */
    val states = MutableStateFlow<Map<String, State>>(emptyMap())

    /** Downloads a message's full photo and returns the file's path; set by [WaCore] when the client starts. */
    @Volatile var photoDownloader: ((Msg) -> String)? = null

    /** Downloads a message's video to [videoFile]; set by [WaCore] when the client starts. */
    @Volatile var videoDownloader: ((Msg) -> String)? = null

    /** A video's file size from its download info (0 if not known); set by [WaCore]. */
    @Volatile var videoBytes: ((Msg) -> Long)? = null

    /** Saves a sharp frame of a video file as message [id]'s preview and returns its path, or null; set by [WaCore]. */
    @Volatile var videoPreview: ((id: String, video: File) -> String?)? = null

    /** Where videos are kept; set by [WaCore]. */
    @Volatile var dir: File? = null

    private const val SHOWN_EXPIRY_MS = 60_000L

    /** Videos up to this size are fetched as they come on screen, for a sharp preview. */
    const val SHOWN_VIDEO_MAX_BYTES = 4L * 1024 * 1024

    private val queue = PriorityBlockingQueue<Job>()
    private val order = AtomicLong()

    /** IDs a [shown] fetch was queued for this session. */
    private val attempted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val worker by lazy {
        thread(name = "wa-media", isDaemon = true) {
            while (true) {
                val job = queue.take()
                // Scrolled past long ago: forget it, so it's fetched if it comes on screen again.
                if (job.expired()) attempted.remove(job.id) else fetch(job)
            }
        }
    }

    /** The local copy of message [id]'s video: downloaded, or kept when we sent it. */
    fun videoFile(id: String): File = File(dir ?: File(""), mediaFileName(id) + ".mp4")

    /** A video that can be downloaded and isn't here yet. */
    fun needsVideo(m: Msg): Boolean = m.isVideo && m.mediaRef.isNotEmpty() && !videoFile(m.id).exists()

    /** A video that's here but still shows only its blurry embedded preview (or none). */
    fun needsPreview(m: Msg): Boolean = m.isVideo && (m.media.isEmpty() || isThumbnailFile(m.media)) && videoFile(m.id).exists()

    private fun fetchedWhenShown(m: Msg): Boolean =
        m.needsFullPhoto || needsPreview(m) || (needsVideo(m) && (m.isGif || (videoBytes?.invoke(m) ?: 0L) in 1..SHOWN_VIDEO_MAX_BYTES))

    fun shown(m: Msg) {
        if (fetchedWhenShown(m) && attempted.add(m.id)) submit(m.id, opened = false)
    }

    fun opened(m: Msg) {
        if (!m.needsFullPhoto && !needsVideo(m)) return
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
        val m = MessageStore.get(job.id) ?: return
        if (needsPreview(m)) {
            preview(m)
            return
        }
        val video = needsVideo(m)
        if (!video && !m.needsFullPhoto) return
        if (!job.opened && states.value[job.id] is State.Failed) return
        val download = if (video) videoDownloader else photoDownloader
        if (download == null) {
            states.update { it + (job.id to State.Failed("not connected")) }
            return
        }
        states.update { it + (job.id to State.Loading) }
        try {
            val path = download(m)
            if (video) preview(m) else MessageStore.setMedia(job.id, path)
            states.update { it - job.id }
        } catch (e: Exception) {
            WaState.log("${if (video) "video" else "photo"} ${job.id}: ${e.message}")
            states.update { it + (job.id to State.Failed(e.message ?: "download failed")) }
        }
    }

    private fun preview(m: Msg) {
        val path = runCatching { videoPreview?.invoke(m.id, videoFile(m.id)) }.getOrNull() ?: return
        MessageStore.setMedia(m.id, path)
        // The blurry embedded preview it replaces.
        if (isThumbnailFile(m.media)) File(m.media).delete()
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
