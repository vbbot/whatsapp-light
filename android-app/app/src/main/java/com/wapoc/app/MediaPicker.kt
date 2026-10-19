package com.wapoc.app

import android.net.Uri

/** Opens the system photo/video picker; set up by [MainActivity]. */
object MediaPicker {
    var request: (((Uri?) -> Unit) -> Unit) = { it(null) }

    /** Calls [block] with the chosen photo or video, or null if the user backed out. */
    fun pick(block: (Uri?) -> Unit) = request(block)
}
