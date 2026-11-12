package vbh.ai.whatsapp

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import kotlin.concurrent.thread

/**
 * Copies a message's photo, video, GIF or voice message into the phone's public Downloads folder,
 * under `WhatsApp Light/`. Media that isn't here yet is downloaded first with [Media]'s
 * downloaders. The work runs off the main thread; [save]'s callback comes back on it.
 *
 * Nothing is overwritten: an existing display name gets a " (1)" suffix, and MediaStore renames
 * on its own if two saves race.
 */
object MediaSaver {
    /** The folder under Downloads that saved media goes in. */
    internal const val FOLDER = "WhatsApp Light"

    /** Saves [m]'s media. [done] runs on the main thread with an error, or null when it worked. */
    fun save(
        context: Context,
        m: Msg,
        done: (String?) -> Unit,
    ) {
        val app = context.applicationContext
        thread(name = "wa-save-media", isDaemon = true) {
            val error =
                try {
                    saveNow(app, m)
                    null
                } catch (e: Exception) {
                    WaState.log("save media ${m.id}: ${e.message}")
                    e.message?.takeIf { it.isNotBlank() } ?: "couldn't save the media"
                }
            Handler(Looper.getMainLooper()).post { done(error) }
        }
    }

    private fun saveNow(
        context: Context,
        m: Msg,
    ) {
        val source = localFile(m)
        val resolver = context.contentResolver
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER
        val name = uniqueName(context, relativePath, m.saveFileName())
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, m.saveMimeType())
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("couldn't create the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: error("couldn't write the file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** The local copy of [m]'s media, downloading it first when needed. */
    private fun localFile(m: Msg): File =
        when {
            m.isVoice -> downloadedFile(m, Media.audioDownloader, Media.audioFile(m.id))
            m.isVideo -> downloadedFile(m, Media.videoDownloader, Media.videoFile(m.id))
            else -> photoFile(m)
        }

    private fun photoFile(m: Msg): File {
        val here = File(m.media)
        if (m.media.isNotEmpty() && !isThumbnailFile(m.media) && here.exists()) return here
        if (m.mediaRef.isEmpty()) error("the media isn't on this phone")
        val path = Media.photoDownloader?.invoke(m) ?: error("not connected")
        return File(path).takeIf { it.exists() } ?: error("the media isn't on this phone")
    }

    private fun downloadedFile(
        m: Msg,
        downloader: ((Msg) -> String)?,
        local: File,
    ): File {
        if (local.exists()) return local
        if (m.mediaRef.isEmpty()) error("the media isn't on this phone")
        downloader?.invoke(m) ?: error("not connected")
        return local.takeIf { it.exists() } ?: error("the download didn't arrive")
    }

    /** [name], or the first " (n)" variant of it that Downloads doesn't have yet. */
    private fun uniqueName(
        context: Context,
        relativePath: String,
        name: String,
    ): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var n = 1
        while (n < MAX_COPIES && taken(context, relativePath, candidate)) {
            candidate = "$stem ($n)$extension"
            n++
        }
        return candidate
    }

    private fun taken(
        context: Context,
        relativePath: String,
        name: String,
    ): Boolean =
        runCatching {
            context.contentResolver
                .query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                    arrayOf(relativePath, name),
                    null,
                )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)

    private const val MAX_COPIES = 1_000
}

/** A photo, video, GIF or voice message that has something to save: not a sticker, deleted or still sending. */
internal fun Msg.canSaveMedia(): Boolean {
    if (isDeleted || pending) return false
    return when {
        isVoice -> mediaRef.isNotEmpty() || Media.audioFile(id).exists()
        isVideo -> mediaRef.isNotEmpty() || Media.videoFile(id).exists()
        text.startsWith(PHOTO_PREFIX) ->
            mediaRef.isNotEmpty() || (media.isNotEmpty() && !isThumbnailFile(media))
        else -> false
    }
}

private const val PHOTO_PREFIX = "[image]"

/** What [MediaSaver] writes [this] as: JPEG photos, MP4 videos and GIFs, OGG voice messages. */
internal fun Msg.saveMimeType(): String =
    when {
        isVoice -> "audio/ogg"
        isVideo -> "video/mp4"
        else -> "image/jpeg"
    }

/** The extension that goes with [saveMimeType]. */
internal fun Msg.saveExtension(): String =
    when {
        isVoice -> ".ogg"
        isVideo -> ".mp4"
        else -> ".jpg"
    }

/**
 * The name a saved message gets in Downloads: its caption when it has one, otherwise its ID,
 * made safe for a file name and given the right extension for its [saveMimeType].
 */
internal fun Msg.saveFileName(): String {
    val words = if (isVoice) "" else caption
    val base = sanitizeSaveName(words).ifEmpty { sanitizeSaveName(id) }.ifEmpty { "media" }
    val extension = saveExtension()
    return if (base.endsWith(extension, ignoreCase = true)) base else base + extension
}

/** [raw] as a file name: path separators and control characters become '_', spaces collapse, and it's trimmed. */
internal fun sanitizeSaveName(raw: String): String =
    raw
        .replace(UNSAFE_NAME_CHARS, "_")
        .replace(WHITESPACE, " ")
        .trim()
        .trim('.')
        .take(MAX_NAME_LENGTH)
        .trim()
        .trim('.')

private val UNSAFE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
private val WHITESPACE = Regex("\\s+")
private const val MAX_NAME_LENGTH = 60
