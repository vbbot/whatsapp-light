package vbh.ai.whatsapp

import android.net.Uri

/** Opens the system photo/video picker; set up by [MainActivity]. */
object MediaPicker {
    var request: (((Uri?) -> Unit) -> Unit) = { it(null) }

    /** Calls [block] with the chosen photo or video, or null if the user backed out. */
    fun pick(block: (Uri?) -> Unit) = request(block)

    /**
     * The Light camera registers each photo in the media index before writing it and never
     * updates the entry (size 0, no dimensions), so the system picker hides them. Asking the
     * media scanner to rescan the camera folders repairs those entries; then [done] runs (on
     * the main thread), at most ~1.5 s later.
     */
    fun rescanCameraFolders(context: android.content.Context, done: () -> Unit) {
        val dirs = listOf(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES).resolve("Light"),
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM),
        ).map { it.absolutePath }.toTypedArray()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var finished = false
        val finish = { if (!finished) { finished = true; done() } }
        var left = dirs.size
        android.media.MediaScannerConnection.scanFile(context, dirs, null) { _, _ ->
            main.post { if (--left == 0) finish() }
        }
        main.postDelayed({ finish() }, 1500)
    }
}
