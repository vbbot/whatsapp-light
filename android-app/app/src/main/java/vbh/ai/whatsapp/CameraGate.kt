package vbh.ai.whatsapp

/** Asks for the camera permission on demand; set up by [MainActivity]. */
object CameraGate {
    var request: (((Boolean) -> Unit) -> Unit) = { it(false) }

    /** Runs [block] with whether the camera may be used, asking the user first if needed. */
    fun withCamera(block: (Boolean) -> Unit) = request(block)
}
