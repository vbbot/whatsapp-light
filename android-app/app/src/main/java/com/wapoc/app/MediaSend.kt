package com.wapoc.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

/** A photo or video prepared for WhatsApp: bytes, metadata and a small JPEG thumbnail. */
class PreparedMedia(
    val kind: String, // "image" or "video"
    val data: ByteArray,
    val mimetype: String,
    val width: Int,
    val height: Int,
    val seconds: Int,
    val thumbnail: ByteArray,
)

object MediaSend {
    private const val MAX_IMAGE_SIDE = 1600
    private const val MAX_VIDEO_BYTES = 64L * 1024 * 1024
    private const val THUMB_SIDE = 96

    /**
     * Reads a picked photo or video. Photos are re-encoded as JPEG (max 1600 px, upright: the
     * decoder applies EXIF rotation) since WhatsApp expects JPEG; videos must already be MP4.
     */
    fun prepare(context: Context, uri: Uri): PreparedMedia {
        val cr = context.contentResolver
        val type = cr.getType(uri).orEmpty()
        return when {
            type.startsWith("image/") -> {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
                    val (w, h) = info.size.width to info.size.height
                    val scale = minOf(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(w, h))
                    decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                PreparedMedia("image", jpeg(bmp, 82), "image/jpeg", bmp.width, bmp.height, 0, thumbnail(bmp))
            }
            type.startsWith("video/") -> {
                if (type != "video/mp4") error("Only MP4 videos can be sent")
                val size = cr.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                if (size > MAX_VIDEO_BYTES) error("That video is too large to send (max 64 MB)")
                val data = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read the video")
                val mmr = MediaMetadataRetriever()
                try {
                    mmr.setDataSource(context, uri)
                    val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    if (rot % 180 != 0) { val t = w; w = h; h = t }
                    val ms = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val frame = mmr.getFrameAtTime(0)
                    PreparedMedia("video", data, "video/mp4", w, h, ((ms + 500) / 1000).toInt(), frame?.let { thumbnail(it) } ?: ByteArray(0))
                } finally {
                    mmr.release()
                }
            }
            else -> error("Only photos and videos can be sent")
        }
    }

    private fun jpeg(bmp: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    private fun thumbnail(bmp: Bitmap): ByteArray {
        val scale = THUMB_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
        val t = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
        return jpeg(t, 60)
    }
}
