package vbh.ai.whatsapp

import kotlinx.coroutines.flow.MutableStateFlow

/** A person to message or call. [chat] is the canonical chat JID (LID when known). */
data class Contact(
    val chat: String,
    val name: String,
    val phone: String,
)

data class CallUi(
    val id: String,
    val peer: String,
    val incoming: Boolean,
    val phase: Int = 0,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val connectedAt: Long = 0L,
    /** A video call (either side has, or asked for, video). */
    val video: Boolean = false,
    /** Our camera is on. */
    val localVideo: Boolean = false,
    /** Their camera is on. */
    val remoteVideo: Boolean = false,
    /** They asked to switch this audio call to video and we haven't answered yet. */
    val upgradeRequested: Boolean = false,
    /** Rotate their frames by this many quarter turns clockwise to show them upright. */
    val remoteOrientation: Int = 0,
    /** Our video comes from the front camera (false: rear). */
    val frontCamera: Boolean = true,
    /** A group call; [group], [members] and [joined] describe it. */
    val groupCall: Boolean = false,
    /** The group chat the call belongs to, or "" for a call between chosen people. */
    val group: String = "",
    /** The other participants, the caller first. */
    val members: List<String> = emptyList(),
    /** Those of [members] in the call now. */
    val joined: List<String> = emptyList(),
) {
    val phaseLabel: String
        get() =
            when (phase) {
                1 -> "calling…"
                2 -> if (incoming) "incoming" else "ringing…"
                3 -> "connecting…"
                5 -> "call ended"
                6 -> "waiting room"
                else -> "…"
            }
}

/** Process-wide UI state, written by [WaCore], observed by Compose. */
object WaState {
    val status = MutableStateFlow("Starting…")
    val qr = MutableStateFlow<String?>(null)

    /** Whether this device is linked to a WhatsApp account; null until the session store is read. */
    val linked = MutableStateFlow<Boolean?>(null)

    /** Why WhatsApp ended the last session, shown with the new pairing code; null after linking. */
    val loggedOutNotice = MutableStateFlow<String?>(null)
    val connected = MutableStateFlow(false)
    val ownNumber = MutableStateFlow("")
    val contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contactsLoaded = MutableStateFlow(false)
    val call = MutableStateFlow<CallUi?>(null)
    val error = MutableStateFlow<String?>(null)

    /** Decoded size of the peer's video, once known. */
    val remoteVideoSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val logs = MutableStateFlow<List<String>>(emptyList())

    /** Persistent copy of the log (app-private), so a call can be diagnosed afterwards. */
    @Volatile var logFile: java.io.File? = null
    private val stamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    fun log(line: String) {
        android.util.Log.i("WaPoc", line)
        logs.value = (logs.value + line).takeLast(60)
        logFile?.let { f ->
            runCatching {
                synchronized(this) {
                    if (f.length() > 2_000_000) f.writeText("")
                    f.appendText(stamp.format(java.util.Date()) + " " + line + "\n")
                }
            }
        }
    }
}

/** What to tell the user when WhatsApp ends the session, by connect failure code. */
fun loggedOutNotice(reason: Int): String =
    when (reason) {
        403 -> "This phone was logged out because WhatsApp on your main phone was logged out or reinstalled."
        406 -> "WhatsApp logged this phone out. Link it again to keep using it."
        else -> "This phone was removed from Linked devices on your main phone. Link it again to keep using it."
    }
