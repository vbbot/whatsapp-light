package vbh.ai.whatsapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import vbh.ai.whatsapp.walib.Client
import vbh.ai.whatsapp.walib.Listener
import java.io.File
import kotlin.concurrent.thread

/** Owns the Go client and audio engine; lives as long as the process (kept alive by [WaService]). */
object WaCore : Listener {
    private lateinit var app: Context
    private var client: Client? = null
    private lateinit var audio: AudioEngine
    lateinit var video: VideoEngine
        private set
    private var ringtone: Ringtone? = null
    private var started = false

    const val CH_SERVICE = "service"
    const val CH_INCOMING = "incoming"
    const val CH_MESSAGES = "messages"
    private const val NOTIF_INCOMING = 2
    private const val OLDER_PAGE = 50

    /** A QR code was shown, so the next connection is a fresh link. */
    @Volatile private var pairing = false

    fun mediaDir(): File = File(app.filesDir, "media").also { it.mkdirs() }

    /** Sets up what the UI needs even before the connection starts (camera preview, demo mode). */
    @Synchronized
    fun prepare(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        if (WaState.logFile == null) WaState.logFile = java.io.File(app.filesDir, "wapoc.log")
        if (!::video.isInitialized) {
            video =
                VideoEngine(app).also { v ->
                    // Their frames are the ground truth for "their camera is on", whatever the signalling says.
                    v.onRemoteFrameShown = { markRemoteVideo() }
                    v.onRemoteVideoWithoutView = { markRemoteVideo() }
                }
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        prepare(context)
        Wake.init(app)
        audio = AudioEngine(app)
        MessageStore.init(app)
        AppSettings.init(app)
        CallLog.init(app)
        createChannels()
        connect()
    }

    /** Opens the session store and connects, showing a pairing code if this phone isn't linked. */
    private fun connect() {
        thread(name = "wa-connect") {
            try {
                connectedOnce = false
                val c = Client(sessionDb().absolutePath, this)
                c.setSpeaker(audio)
                c.setVideoRenderer(video)
                c.setMediaDir(mediaDir().absolutePath)
                client = c
                Photos.downloader = { m -> c.downloadPhoto(m.id, m.mediaRef) }
                ChatIdentity.canonical = { jid -> c.canonicalChat(jid) }
                WaState.linked.value = c.isPaired
                WaState.status.value = if (c.isPaired) "connecting…" else "waiting for QR…"
                c.connect()
            } catch (e: Exception) {
                // A refused session is handled by onLoggedOut, which links again.
                if (e.message == "logged out") return@thread
                WaState.error.value = e.message
                WaState.status.value = "error"
                WaState.log("connect failed: ${e.message}")
            }
        }
    }

    /**
     * From [KeepAliveReceiver]: ping, or reconnect if the socket died or the ping went unanswered.
     * Returns once the ping is answered (or the reconnect started).
     */
    fun keepAlive() {
        val ok = runCatching { client?.keepAlive() }.getOrNull()
        if (ok == false) {
            WaState.log("keepalive: not answered, reconnecting")
            Wake.hold(5_000)
        }
    }

    private fun sessionDb() = File(app.filesDir, "wa.db")

    /**
     * Logs out from Settings: unlinks this phone from the account, then deletes everything stored
     * here and shows a new pairing code. Needs a connection; on failure nothing is deleted.
     */
    fun logOut(onFailure: (String) -> Unit) =
        thread(name = "wa-logout") {
            val c = client
            try {
                if (c == null) error("not connected")
                loggingOut = true
                c.logout()
                relink(notice = null)
            } catch (e: Exception) {
                loggingOut = false
                WaState.log("log out failed: ${e.message}")
                onFailure(e.message ?: "unknown error")
            }
        }

    @Volatile private var loggingOut = false

    /**
     * The session has ended (logged out here or by WhatsApp): closes the client, deletes the
     * session, messages, calls and media, and connects afresh so a new pairing code shows.
     */
    @Synchronized
    private fun relink(notice: String?) {
        val old = client
        client = null
        Photos.downloader = null
        ChatIdentity.canonical = null
        runCatching { old?.close() }.onFailure { WaState.log("close failed: ${it.message}") }
        stopRing()
        WaState.call.value = null
        Wake.inCall(false)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        MessageStore.clearAll()
        CallLog.clearAll()
        Photos.states.value = emptyMap()
        History.syncing.value = null
        mediaDir().deleteRecursively()
        listOf("", "-wal", "-shm", "-journal").forEach { File(sessionDb().path + it).delete() }
        WaState.loggedOutNotice.value = notice
        WaState.error.value = null
        WaState.qr.value = null
        WaState.linked.value = false
        WaState.connected.value = false
        WaState.ownNumber.value = ""
        WaState.contacts.value = emptyList()
        WaState.contactsLoaded.value = false
        WaState.status.value = "logged out"
        Nav.reset()
        loggingOut = false
        connect()
    }

    fun serviceNotification(): Notification =
        NotificationCompat
            .Builder(app, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("WhatsApp")
            .setContentText(WaState.status.value)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp() =
        PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannels() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT))
    }

    // --- contact resolution ---

    /** Looks up a name / phone number for [jid] off the main thread and publishes it for titles. */
    fun resolve(jid: String) = resolveAll(listOf(jid))

    /** Looks up names and numbers for [jids] in one background pass, published in one update. */
    fun resolveAll(jids: Collection<String>) {
        val c = client ?: return
        if (jids.isEmpty()) return
        thread(name = "wa-resolve") {
            val names = HashMap<String, String>()
            val phones = HashMap<String, String>()
            for (key in jids.map(::normalizeJid).distinct()) {
                try {
                    c.contactName(key).takeIf { it.isNotBlank() }?.let { names[key] = it }
                    c.phoneNumber(key).takeIf { it.isNotBlank() }?.let { phones[key] = it }
                } catch (_: Exception) {
                }
            }
            // update {} so concurrent lookups don't overwrite each other's results.
            if (names.any { MessageStore.names.value[it.key] != it.value }) MessageStore.names.update { it + names }
            if (phones.any { MessageStore.phones.value[it.key] != it.value }) MessageStore.phones.update { it + phones }
        }
    }

    private fun resolveAllChats() {
        resolveAll(MessageStore.chatIds().filterNot { it.endsWith("@g.us") })
    }

    // --- contacts ---

    /** Reads saved contacts from WhatsApp's store (off the main thread) and publishes them. */
    fun loadContacts() {
        val cl = client ?: return
        thread(name = "wa-contacts") {
            try {
                val list =
                    cl
                        .contacts()
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            val p = line.split('\t')
                            if (p.size < 3) null else Contact(chat = cl.canonicalChat(p[0]), name = p[1], phone = p[2])
                        }.distinctBy { it.chat }
                        .sortedBy { it.name.lowercase() }
                        .toList()
                WaState.contacts.value = list
            } catch (e: Exception) {
                WaState.log("contacts: ${e.message}")
            } finally {
                WaState.contactsLoaded.value = true
            }
        }
    }

    /** Maps a typed number to the chat key WhatsApp uses for that person, then calls [done] on the main thread. */
    fun canonicalChat(
        number: String,
        done: (String) -> Unit,
    ) {
        val cl = client ?: return
        thread(name = "wa-canon") {
            val key =
                try {
                    cl.canonicalChat(number)
                } catch (_: Exception) {
                    number.removePrefix("+") + "@s.whatsapp.net"
                }
            android.os.Handler(android.os.Looper.getMainLooper()).post { done(key) }
        }
    }

    // --- read receipts ---

    /**
     * Sends read receipts for messages now visible in an open thread, if Seen Status is on.
     * With it off, nothing is sent and the messages stay eligible if it is turned back on.
     */
    fun sendReadReceipts(chat: String) {
        if (!AppSettings.seenStatus.value || !WaState.connected.value) return
        val cl = client ?: return
        val pending = MessageStore.needingReadReceipt(chat)
        if (pending.isEmpty()) return
        thread(name = "wa-read") {
            pending.groupBy { it.sender }.forEach { (sender, msgs) ->
                try {
                    cl.markRead(chat, sender, msgs.joinToString(",") { it.id })
                    MessageStore.markReadSent(msgs.map { it.id }.toSet())
                } catch (e: Exception) {
                    WaState.log("read receipt failed: ${e.message}")
                }
            }
        }
    }

    // --- UI actions (run off the main thread; Go calls can block) ---

    /** Calls [target]; asks for the microphone first if it isn't allowed yet. */
    fun call(
        target: String,
        withVideo: Boolean = false,
    ) = MicGate.withMic { ok ->
        if (!ok) {
            WaState.error.value = MicGate.NEEDED
            return@withMic
        }
        bg {
            WaState.error.value = null
            val id = if (withVideo) client!!.callVideo(target.trim()) else client!!.call(target.trim())
            CallLog.start(
                id,
                if (target.contains("@")) target.trim() else target.trim().removePrefix("+") + "@s.whatsapp.net",
                incoming = false,
                video = withVideo,
            )
            Wake.inCall(true)
            WaState.call.value =
                CallUi(id, target.trim(), incoming = false, phase = 1, video = withVideo, localVideo = withVideo, remoteVideo = withVideo)
            resolve(if (target.contains("@")) target.trim() else target.trim().removePrefix("+") + "@s.whatsapp.net")
        }
    }

    /**
     * Answers; for a video call our camera turns on too (unless camera access was refused). Without
     * the microphone the call keeps ringing and the screen says why.
     */
    fun answer(withCamera: Boolean = true) =
        MicGate.withMic { ok ->
            if (!ok) {
                WaState.error.value = MicGate.NEEDED
                return@withMic
            }
            bg {
                val c = WaState.call.value ?: return@bg
                stopRing()
                client!!.answer(c.id)
                if (c.video) WaState.call.value = WaState.call.value?.copy(localVideo = withCamera, phase = maxOf(c.phase, 3))
            }
        }

    // --- video ---

    /** Ask to switch the current audio call to video; our camera turns on. */
    fun startVideo() =
        bg {
            val c = WaState.call.value ?: return@bg
            client!!.startVideo(c.id)
            CallLog.markVideo(c.id)
            WaState.call.value = WaState.call.value?.copy(video = true, localVideo = true)
            setSpeaker(true)
            sendOrientation(c.id)
        }

    /** Accept their request to switch to video; our camera turns on. */
    fun acceptVideo() =
        bg {
            val c = WaState.call.value ?: return@bg
            client!!.acceptVideo(c.id)
            CallLog.markVideo(c.id)
            WaState.call.value = WaState.call.value?.copy(video = true, localVideo = true, upgradeRequested = false)
            setSpeaker(true)
            sendOrientation(c.id)
        }

    /** Turn our camera off for the rest of the call; audio and their video continue. */
    fun stopVideo() =
        bg {
            val c = WaState.call.value ?: return@bg
            WaState.call.value = WaState.call.value?.copy(localVideo = false, upgradeRequested = false)
            video.stopCamera()
            client!!.stopVideo(c.id)
        }

    /** Starts the camera and encoder for the current call, drawing a preview into [preview]. */
    fun startCamera(preview: android.view.Surface?) {
        WaState.call.value ?: return
        lastPreview = preview
        startCapture(preview)
    }

    /** The preview surface of the current video call, kept so a camera switch can reuse it. */
    private var lastPreview: android.view.Surface? = null

    /** Switches between front and rear camera mid-call; mute and other call state are untouched. */
    fun switchCamera() {
        val cur = WaState.call.value ?: return
        if (!cur.localVideo) return
        video.useFrontCamera = !video.useFrontCamera
        WaState.call.value = cur.copy(frontCamera = video.useFrontCamera)
        startCapture(lastPreview)
    }

    private fun startCapture(preview: android.view.Surface?) {
        val c = WaState.call.value ?: return
        val cl = client
        sentOk = 0
        sentFailed = 0
        lastSendError = null
        video.startCamera(preview) { au, durationUs ->
            // Fails until the call's video media is up; meowcaller then waits for a keyframe.
            try {
                cl?.sendVideo(c.id, au, durationUs)
                if (sentOk++ ==
                    0
                ) {
                    WaState.log("video out: first frame accepted (${au.size} bytes, NAL ${VideoEngine.nalTypes(au).sorted()})")
                }
            } catch (e: Exception) {
                if (sentFailed++ == 0 || e.message != lastSendError) WaState.log("video out: send failed: ${e.message}")
                lastSendError = e.message
            }
            if ((sentOk + sentFailed) % 150 == 0) WaState.log("video out: accepted $sentOk, failed $sentFailed")
        }
        sendOrientation(c.id)
    }

    fun stopCamera() {
        lastPreview = null
        video.stopCamera()
    }

    /**
     * Tells meowcaller the orientation to stamp on every outgoing video packet. WhatsApp
     * receivers rotate by it counter-clockwise (tested: sending the clockwise value showed us
     * upside down), so this is the inverse of the clockwise turn our frames need.
     */
    private fun sendOrientation(callID: String) {
        val cl = client ?: return
        val cvo = (4 - video.sensorQuarterTurns) % 4
        thread(name = "wa-orient") {
            try {
                cl.setVideoOrientation(callID, cvo.toLong())
                WaState.log("video out: orientation $cvo set")
            } catch (e: Exception) {
                WaState.log("video out: orientation not set yet: ${e.message}")
            }
        }
    }

    private fun markRemoteVideo() {
        val cur = WaState.call.value ?: return
        if (!cur.remoteVideo || !cur.video) WaState.call.value = cur.copy(video = true, remoteVideo = true)
    }

    // Outgoing video counters, logged periodically.
    private var sentOk = 0
    private var sentFailed = 0
    private var lastSendError: String? = null

    fun reject() =
        bg {
            val c = WaState.call.value ?: return@bg
            CallLog.markDeclined(c.id)
            stopRing()
            client!!.reject(c.id)
        }

    fun hangup() =
        bg {
            val c = WaState.call.value ?: return@bg
            client!!.hangup(c.id)
        }

    /** Sends a text message; shows it immediately as pending, then replaces it with the real one. */
    fun sendText(
        chat: String,
        text: String,
    ) = bg {
        val body = text.trim()
        if (body.isEmpty()) return@bg
        val tmp = "pending-" + System.nanoTime()
        MessageStore.add(Msg(tmp, chat, "me", "", body, System.currentTimeMillis(), true, chat.endsWith("@g.us"), pending = true))
        try {
            val id = client!!.sendText(chat, body)
            MessageStore.remove(tmp)
            MessageStore.add(Msg(id, chat, "me", "", body, System.currentTimeMillis(), true, chat.endsWith("@g.us"), status = Status.SENT))
        } catch (e: Exception) {
            MessageStore.remove(tmp)
            throw e
        }
    }

    /**
     * Sends a picked photo or video: shows it at once as a pending "[image]" / "[video]" row,
     * then uploads and swaps in the real message (or removes the row and shows the error).
     */
    fun sendMedia(
        chat: String,
        uri: android.net.Uri,
    ) = bg {
        val tmp = "pending-" + System.nanoTime()
        val isGroup = chat.endsWith("@g.us")
        var local: File? = null
        try {
            val media = MediaSend.prepare(app, uri)
            val label = if (media.kind == "image") "[image]" else "[video]"
            // Keep our own copy to show in the thread: the photo itself, or a video's thumbnail.
            local = File(mediaDir(), "$tmp.jpg").also { it.writeBytes(if (media.kind == "image") media.data else media.thumbnail) }
            MessageStore.add(Msg(tmp, chat, "me", "", label, System.currentTimeMillis(), true, isGroup, pending = true, media = local.path))
            val id =
                client!!.sendMedia(
                    chat,
                    media.kind,
                    media.data,
                    media.mimetype,
                    "",
                    media.width.toLong(),
                    media.height.toLong(),
                    media.seconds.toLong(),
                    media.thumbnail,
                )
            val kept = File(mediaDir(), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg").also { local.renameTo(it) }
            MessageStore.remove(tmp)
            MessageStore.add(
                Msg(id, chat, "me", "", label, System.currentTimeMillis(), true, isGroup, status = Status.SENT, media = kept.path),
            )
            WaState.log("media sent: ${media.kind} ${media.data.size / 1024} KB ${media.width}x${media.height}")
        } catch (e: Exception) {
            MessageStore.remove(tmp)
            local?.delete()
            throw e
        }
    }

    fun setMuted(m: Boolean) {
        if (::audio.isInitialized) audio.muted = m
        WaState.call.value = WaState.call.value?.copy(muted = m)
    }

    fun setSpeaker(on: Boolean) {
        if (::audio.isInitialized) audio.setSpeakerphone(on)
        WaState.call.value = WaState.call.value?.copy(speaker = on)
    }

    private fun bg(block: () -> Unit) =
        thread {
            try {
                block()
            } catch (e: Exception) {
                WaState.error.value = e.message
                WaState.log("error: ${e.message}")
            }
        }

    private fun stopRing() {
        ringtone?.stop()
        ringtone = null
        app.getSystemService(NotificationManager::class.java).cancel(NOTIF_INCOMING)
    }

    // --- Listener (called from Go goroutines) ---

    override fun onQR(code: String) {
        pairing = true
        WaState.qr.value = code
        WaState.status.value = "scan the QR code in WhatsApp"
    }

    override fun onConnected(jid: String) {
        Wake.hold(3_000)
        WaState.qr.value = null
        WaState.loggedOutNotice.value = null
        WaState.linked.value = true
        WaState.connected.value = true
        WaState.status.value = "connected"
        WaState.ownNumber.value = client?.ownNumber().orEmpty()
        // Just linked: the main phone now sends past chats, which takes a minute or two.
        if (pairing) {
            pairing = false
            History.linked()
        }
        // This runs again after every reconnect; the one-off work only needs the first.
        if (!connectedOnce) {
            connectedOnce = true
            thread(name = "wa-merge") {
                ChatIdentity.mergeDuplicates()
                resolveAllChats()
            }
            loadContacts()
        }
        // Receipts skipped while offline, for the chat on screen.
        MessageStore.openChat.value?.let { chat -> thread(name = "wa-receipts") { runCatching { sendReadReceipts(chat) } } }
    }

    /** The current client has connected at least once (reset for each new client). */
    @Volatile private var connectedOnce = false

    override fun onLoggedOut(reason: Long) {
        Wake.hold(15_000)
        WaState.log("logged out by WhatsApp ($reason)")
        // Our own logout also ends the session; it relinks itself.
        if (loggingOut) return
        thread(name = "wa-relink") { relink(loggedOutNotice(reason.toInt())) }
    }

    override fun onChatDeleted(
        chat: String,
        beforeMs: Long,
        keepChat: Boolean,
        ids: String,
    ) {
        // Up to the named messages too: ours are stored with this phone's send time, which can
        // be just after the server time the range ends at.
        val named = MessageStore.newestOf(chat, ids.split(',').filter { it.isNotBlank() })
        removeChat(chat, maxOf(beforeMs, named ?: 0L), keepChat)
        // Deleted while open: leave its screens.
        if (!keepChat) Nav.dropChat(chat)
    }

    override fun onDisconnected() {
        Wake.hold(5_000) // whatsmeow reconnects within a second or two
        WaState.connected.value = false
        WaState.status.value = "disconnected, reconnecting…"
    }

    override fun onIncomingCall(
        callID: String,
        peer: String,
        video: Boolean,
    ) {
        Wake.inCall(true)
        WaState.call.value = CallUi(callID, peer, incoming = true, phase = 2, video = video, remoteVideo = video)
        CallLog.start(callID, peer, incoming = true, video = video)
        resolve(peer)
        ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.also { it.play() }
        val n =
            NotificationCompat
                .Builder(app, CH_INCOMING)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle(if (video) "Incoming WhatsApp video call" else "Incoming WhatsApp call")
                .setContentText(MessageStore.title(normalizeJid(peer)))
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(openApp(), true)
                .setContentIntent(openApp())
                .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_INCOMING, n)
    }

    override fun onCallReady(callID: String) {
        CallLog.answered(callID)
        client?.let { audio.start(it) }
        // Video calls are held away from the face: default to the loudspeaker.
        if (WaState.call.value?.video == true) {
            setSpeaker(true)
            sendOrientation(callID)
        }
    }

    override fun onCallState(
        callID: String,
        phase: Long,
    ) {
        WaState.call.value = WaState.call.value?.takeIf { it.id == callID }?.let {
            val p = phase.toInt()
            it.copy(phase = p, connectedAt = if (p == 4 && it.connectedAt == 0L) System.currentTimeMillis() else it.connectedAt)
        } ?: WaState.call.value
    }

    override fun onCallEnded(
        callID: String,
        reason: String,
    ) {
        Wake.inCall(false)
        CallLog.ended(callID)
        stopRing()
        audio.stop()
        if (sentOk + sentFailed > 0) WaState.log("video out: total accepted $sentOk, failed $sentFailed")
        video.stopCamera()
        video.releaseDecoder()
        WaState.remoteVideoSize.value = null
        WaState.log("call ended: $reason")
        if (WaState.call.value?.id == callID) WaState.call.value = null
    }

    override fun onMessage(
        chat: String,
        sender: String,
        senderName: String,
        id: String,
        text: String,
        timestampMs: Long,
        fromMe: Boolean,
        isGroup: Boolean,
        mediaPath: String,
        mediaRef: String,
    ) {
        Wake.hold(5_000)
        // Our own messages sent from another device have already reached the server.
        MessageStore.add(
            Msg(
                id,
                chat,
                sender,
                senderName,
                text,
                timestampMs,
                fromMe,
                isGroup,
                status = if (fromMe) Status.SENT else Status.NONE,
                media = mediaPath,
                mediaRef = mediaRef,
            ),
        )
        if (!isGroup) resolve(chat)
        // Muted and archived chats stay quiet: the message is stored and shows as unread.
        val quiet = MessageStore.chatState(chat).quietAt(System.currentTimeMillis())
        if (!fromMe && !quiet && MessageStore.openChat.value != chat) {
            val title = MessageStore.title(chat)
            val n =
                NotificationCompat
                    .Builder(app, CH_MESSAGES)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(if (isGroup && senderName.isNotBlank()) "$title · $senderName" else title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build()
            app.getSystemService(NotificationManager::class.java).notify(chat.hashCode(), n)
        }
    }

    override fun onChatMuted(
        chat: String,
        mutedUntil: Long,
    ) {
        Wake.hold(2_000)
        MessageStore.setMutedUntil(chat, mutedUntil)
    }

    override fun onChatArchived(
        chat: String,
        archived: Boolean,
    ) {
        Wake.hold(2_000)
        MessageStore.setArchived(chat, archived)
    }

    override fun onChatPinned(
        chat: String,
        pinnedAt: Long,
    ) {
        Wake.hold(2_000)
        MessageStore.setPinnedAt(chat, pinnedAt)
    }

    /** Pins or unpins [chat] here and on every linked device; refuses a pin past WhatsApp's limit. */
    fun setPinned(
        chat: String,
        pinned: Boolean,
    ) {
        val before = MessageStore.chatState(chat).pinnedAt
        if (pinned && before == 0L && MessageStore.pinnedCount() >= MAX_PINNED) {
            WaState.error.value = "You can pin up to $MAX_PINNED chats. Unpin one first."
            return
        }
        MessageStore.setPinnedAt(chat, if (pinned) System.currentTimeMillis() else 0)
        syncChatState("pin", revert = { MessageStore.setPinnedAt(chat, before) }) { it.setChatPinned(chat, pinned) }
    }

    /** Mutes [chat] until unmuted, or unmutes it, here and on every linked device. */
    fun setMuted(
        chat: String,
        muted: Boolean,
    ) {
        val before = MessageStore.chatState(chat).mutedUntil
        MessageStore.setMutedUntil(chat, if (muted) MUTED_FOREVER else 0)
        if (muted) clearNotification(chat)
        syncChatState("mute", revert = { MessageStore.setMutedUntil(chat, before) }) { it.setChatMuted(chat, muted) }
    }

    /** Archives or unarchives [chat] here and on every linked device. */
    fun setArchived(
        chat: String,
        archived: Boolean,
    ) {
        val before = MessageStore.chatState(chat)
        MessageStore.setArchived(chat, archived)
        if (archived) clearNotification(chat)
        val last = MessageStore.newest(chat)
        val revert = {
            MessageStore.setArchived(chat, before.archived)
            MessageStore.setPinnedAt(chat, before.pinnedAt) // archiving unpinned it
        }
        syncChatState("archive", revert = revert) {
            it.setChatArchived(chat, archived, last?.id.orEmpty(), last?.sender.orEmpty(), last?.ts ?: 0L, last?.fromMe ?: false)
        }
    }

    /**
     * Deletes [chat] with its messages and photos, here and on every device. WhatsApp is told
     * first; only once it accepts is anything removed here, so a failure leaves the chat intact.
     */
    fun deleteChat(
        chat: String,
        onDone: (error: String?) -> Unit,
    ) = thread(name = "wa-delete-chat") {
        try {
            val cl = client ?: error("not connected")
            val last = MessageStore.newest(chat)
            cl.deleteChat(chat, last?.id.orEmpty(), last?.sender.orEmpty(), last?.ts ?: 0L, last?.fromMe ?: false)
            removeChat(chat, Long.MAX_VALUE, keepChat = false)
            onDone(null)
        } catch (e: Exception) {
            WaState.log("delete chat failed: ${e.message}")
            onDone(e.message ?: "unknown error")
        }
    }

    /** Removes [chat]'s messages up to [beforeMs] and their media files, and its notification. */
    private fun removeChat(
        chat: String,
        beforeMs: Long,
        keepChat: Boolean,
    ) {
        val left = MessageStore.count(chat)
        val media = MessageStore.deleteChat(chat, beforeMs, keepChat)
        WaState.log("deleted chat $chat up to $beforeMs: ${left - MessageStore.count(chat)} of $left messages")
        val dir = mediaDir().canonicalPath + File.separator
        media.map(::File).filter { it.canonicalPath.startsWith(dir) }.forEach { it.delete() }
        clearNotification(chat)
    }

    /**
     * Sends a chat-state change to WhatsApp in the background. The change is already shown; if
     * sending fails it's undone with [revert] and the reason shown.
     */
    private fun syncChatState(
        what: String,
        revert: () -> Unit,
        send: (Client) -> Unit,
    ) {
        val c = client
        thread(name = "wa-chat-state") {
            try {
                if (c == null) error("not connected")
                send(c)
            } catch (e: Exception) {
                revert()
                WaState.error.value = "Couldn't $what the chat: ${e.message}"
                WaState.log("$what failed: ${e.message}")
            }
        }
    }

    private fun clearNotification(chat: String) = app.getSystemService(NotificationManager::class.java).cancel(chat.hashCode())

    override fun onMediaReady(
        id: String,
        path: String,
    ) {
        Wake.hold(2_000)
        MessageStore.setMedia(id, path)
    }

    override fun onReceipt(
        chat: String,
        ids: String,
        kind: String,
        timestampMs: Long,
    ) {
        Wake.hold(2_000)
        val set = ids.split(',').filter { it.isNotBlank() }.toSet()
        when (kind) {
            "delivered" -> MessageStore.applyReceipt(set, Status.DELIVERED)
            "read", "played" -> MessageStore.applyReceipt(set, Status.SEEN)
            "read-self" -> MessageStore.readElsewhere(chat, set)
        }
    }

    override fun onVideoState(
        callID: String,
        active: Boolean,
        upgrade: Boolean,
        orientation: Long,
        raw: Long,
    ) {
        val cur = WaState.call.value?.takeIf { it.id == callID } ?: return
        WaState.log("video state raw=$raw active=$active upgrade=$upgrade orientation=$orientation")
        val stopped = raw == 0L || raw == 6L
        WaState.call.value =
            cur.copy(
                video = cur.video || active || upgrade,
                remoteVideo =
                    when {
                        active -> true
                        stopped -> false
                        else -> cur.remoteVideo
                    },
                upgradeRequested = if (upgrade && !cur.localVideo) true else cur.upgradeRequested && !stopped,
            )
        if (active || upgrade) CallLog.markVideo(callID)
    }

    override fun onKeyframeRequest(callID: String) {
        WaState.log("video out: peer asked for a keyframe")
        video.requestKeyframe()
    }

    override fun onRemoteOrientation(
        callID: String,
        quarterTurns: Long,
    ) {
        WaState.log("video in: orientation from RTP = $quarterTurns")
        val cur = WaState.call.value?.takeIf { it.id == callID } ?: return
        WaState.call.value = cur.copy(remoteOrientation = quarterTurns.toInt())
    }

    override fun onHistory(json: String) {
        Wake.hold(15_000)
        try {
            val batch = History.receive(json)
            // A batch can bring the mappings that join a person's two addresses.
            val merged = ChatIdentity.mergeDuplicates()
            resolveAll(batch.chats.keys.filterNot { it.endsWith("@g.us") } + merged)
        } catch (e: Exception) {
            WaState.log("history import failed: ${e.message}")
        }
    }

    /** Asks the main phone for messages in [chat] older than the oldest stored one. */
    fun requestOlderMessages(chat: String) =
        bg {
            val oldest = MessageStore.oldest(chat) ?: return@bg
            client!!.requestOlderMessages(chat, oldest.id, oldest.ts, oldest.fromMe, OLDER_PAGE.toLong())
        }

    override fun onLog(line: String) {
        WaState.log(line)
    }
}
