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
    const val CH_MISSED = "missed"
    private const val NOTIF_INCOMING = 2

    /** How long the answered-looking call notification stays before it's removed. */
    private const val SETTLE_MS = 1_500L
    private const val OLDER_PAGE = 50

    /** A QR code was shown, so the next connection is a fresh link. */
    @Volatile private var pairing = false

    fun mediaDir(): File = File(app.filesDir, "media").also { it.mkdirs() }

    /** Sets up what the UI needs even before the connection starts (camera preview, demo mode). */
    @Synchronized
    fun prepare(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        Media.dir = mediaDir()
        Media.videoBytes = { m ->
            runCatching {
                vbh.ai.whatsapp.walib.Walib
                    .videoBytes(m.mediaRef)
            }.getOrDefault(0L)
        }
        Media.videoPreview = { id, video -> MediaSend.previewFrame(video, File(mediaDir(), mediaFileName(id) + "_p.jpg")) }
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
        Proximity.init(app)
        CallButtons.init(app)
        audio = AudioEngine(app)
        MessageStore.init(app)
        AppSettings.init(app)
        CallLog.init(app)
        createChannels()
        watchNetwork()
        connect()
    }

    /** Released whenever a network becomes available: a failed first connect waits on it. */
    private val networkBack = java.util.concurrent.Semaphore(0)

    /**
     * Reconnects as soon as a network comes back, rather than at the next keep-alive (up to 45 s
     * later): a first connect that failed offline tries again, and a dropped connection redials.
     */
    private fun watchNetwork() {
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(
            object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Wake.hold(10_000)
                    networkBack.release()
                    // Only once connected before: until then, the first connect is retrying itself.
                    val c = client ?: return
                    if (connectedOnce && !c.isConnected) {
                        WaState.log("network back: reconnecting")
                        thread(name = "wa-network") { keepAlive() }
                    }
                }
            },
        )
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
                Outbox.sender = { m -> sendQueued(c, m) }
                Outbox.isConnected = { c.isConnected }
                Media.photoDownloader = { m -> if (m.isSticker) c.downloadSticker(m.id, m.mediaRef) else c.downloadPhoto(m.id, m.mediaRef) }
                Media.videoDownloader = { m -> c.downloadVideo(m.id, m.mediaRef) }
                Media.audioDownloader = { m -> c.downloadAudio(m.id, m.mediaRef) }
                ChatIdentity.canonical = { jid -> c.canonicalChat(jid) }
                WaState.linked.value = c.isPaired
                WaState.status.value = if (c.isPaired) "connecting…" else "waiting for QR…"
                // Offline, the first connect fails: that's not an error (the offline banner says
                // so). Try again when a network comes back, or in a minute anyway.
                while (true) {
                    try {
                        c.connect()
                        break
                    } catch (e: Exception) {
                        val msg = e.message.orEmpty()
                        if (!Outbox.looksLikeConnection(msg) || msg.contains("already connected")) throw e
                        WaState.status.value = "offline"
                        WaState.log("connect failed, waiting for the network: $msg")
                        networkBack.drainPermits()
                        networkBack.tryAcquire(60, java.util.concurrent.TimeUnit.SECONDS)
                        if (client !== c) return@thread
                    }
                }
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
        Media.photoDownloader = null
        Media.videoDownloader = null
        Outbox.sender = null
        ChatIdentity.canonical = null
        runCatching { old?.close() }.onFailure { WaState.log("close failed: ${it.message}") }
        stopRing()
        WaState.call.value = null
        Wake.inCall(false)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        MessageStore.clearAll()
        CallLog.clearAll()
        Media.states.value = emptyMap()
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
        nm.createNotificationChannel(NotificationChannel(CH_MISSED, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT))
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
     * Places a group call: to everyone in [peer] when it's a group chat, else to [members] (a call
     * between chosen people, called back from the call log).
     */
    fun callGroup(
        peer: String,
        members: List<String>,
        withVideo: Boolean = false,
    ) = MicGate.withMic { ok ->
        if (!ok) {
            WaState.error.value = MicGate.NEEDED
            return@withMic
        }
        bg {
            WaState.error.value = null
            val group = peer.endsWith("@g.us")
            val id =
                try {
                    if (group) client!!.callGroup(peer, withVideo) else client!!.callPeople(members.joinToString(","), withVideo)
                } catch (e: Exception) {
                    WaState.log("group call failed: ${e.message}")
                    WaState.error.value = "Couldn't start the group call (${e.message})."
                    return@bg
                }
            // walib reports the members while placing the call, before this screen state exists.
            val info = groupCalls[id] ?: GroupCallInfo(if (group) peer else "", members, emptyList())
            CallLog.start(id, if (group) peer else members.first(), incoming = false, video = withVideo, members = info.members)
            Wake.inCall(true)
            WaState.call.value =
                CallUi(id, peer, incoming = false, phase = 1, video = withVideo, localVideo = withVideo, remoteVideo = withVideo)
                    .withGroup(info)
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

    /**
     * Sends a text, as a reply to [replyTo] if given. It's stored at once, queued, under the ID it
     * will be sent with, and [onSent] runs then (it's safe from here on); [Outbox] sends it, now
     * or once the connection is back.
     */
    fun sendText(
        chat: String,
        text: String,
        replyTo: Msg? = null,
        onSent: () -> Unit = {},
    ) = bg {
        val body = text.trim()
        if (body.isEmpty()) return@bg
        MessageStore.add(
            Msg(
                vbh.ai.whatsapp.walib.Walib
                    .newMessageID(),
                chat,
                "me",
                "",
                body,
                System.currentTimeMillis(),
                true,
                chat.endsWith("@g.us"),
                status = Status.QUEUED,
                quoteId = replyTo?.id.orEmpty(),
                quoteSender = replyTo?.let(::quoteSenderOf).orEmpty(),
                quoteText = replyTo?.let(::quoteSnippet).orEmpty(),
            ),
        )
        onSent()
        Outbox.flush()
    }

    /** A message's author as a reply records it: "me", or the sender's JID (the chat, in 1:1 chats). */
    private fun quoteSenderOf(m: Msg) = if (m.fromMe) "me" else m.sender.ifEmpty { m.chat }

    /**
     * Sends a picked photo or video. It's read straight away (the picker's permission doesn't
     * last) and kept here: the photo as it will be sent, or the video and a sharp frame of it. Then
     * it's queued like a text ([sendText]), and [Outbox] uploads it from those files.
     */
    fun sendMedia(
        chat: String,
        uri: android.net.Uri,
    ) = bg {
        val id =
            vbh.ai.whatsapp.walib.Walib
                .newMessageID()
        val local = File(mediaDir(), mediaFileName(id) + ".jpg")
        try {
            val media = MediaSend.prepare(app, uri)
            local.writeBytes(media.preview)
            if (media.kind == "video") Media.videoFile(id).writeBytes(media.data)
            val label = if (media.kind == "image") "[image]" else "[video]"
            MessageStore.add(
                Msg(
                    id,
                    chat,
                    "me",
                    "",
                    label,
                    System.currentTimeMillis(),
                    true,
                    chat.endsWith("@g.us"),
                    status = Status.QUEUED,
                    media = local.path,
                ),
            )
        } catch (e: Exception) {
            local.delete()
            Media.videoFile(id).delete()
            throw e
        }
        Outbox.flush()
    }

    /**
     * Sends a voice message recorded at [draft]: it's moved into the media folder, stored as
     * queued, and sent by [Outbox] like any other message.
     */
    fun sendVoice(
        chat: String,
        draft: File,
    ) = bg {
        val id =
            vbh.ai.whatsapp.walib.Walib
                .newMessageID()
        val f = Media.audioFile(id)
        try {
            draft.copyTo(f, overwrite = true)
            MessageStore.add(
                Msg(
                    id,
                    chat,
                    "me",
                    "",
                    "[voice message]",
                    System.currentTimeMillis(),
                    true,
                    chat.endsWith("@g.us"),
                    status = Status.QUEUED,
                ),
            )
        } catch (e: Exception) {
            f.delete()
            throw e
        } finally {
            draft.delete()
        }
        Outbox.flush()
    }

    /** Sends one queued message from what's stored ([Outbox.sender]). */
    private fun sendQueued(
        c: Client,
        m: Msg,
    ) {
        // Sending may start with the phone asleep (the connection just came back): stay awake
        // long enough for an upload.
        Wake.hold(if (m.hasPicture || m.isVideo || m.isVoice) 60_000 else 10_000)
        when {
            m.isVoice -> {
                val f = Media.audioFile(m.id)
                val seconds = fileSeconds(f)
                c.sendMedia(m.chat, m.id, "audio", f.readBytes(), VOICE_MIMETYPE, "", 0, 0, seconds.toLong(), ByteArray(0))
                WaState.log("voice message sent: ${f.length() / 1024} KB, ${seconds}s")
            }

            m.hasPicture || m.isVideo -> {
                val media = MediaSend.fromLocal(if (m.isVideo) "video" else "image", File(m.media), Media.videoFile(m.id))
                c.sendMedia(
                    m.chat,
                    m.id,
                    media.kind,
                    media.data,
                    media.mimetype,
                    m.caption,
                    media.width.toLong(),
                    media.height.toLong(),
                    media.seconds.toLong(),
                    media.thumbnail,
                )
                WaState.log("media sent: ${media.kind} ${media.data.size / 1024} KB ${media.width}x${media.height}")
            }

            m.isReply -> {
                c.sendReply(m.chat, m.id, m.text, m.quoteId, m.quoteSender, m.quoteText)
            }

            else -> {
                c.sendText(m.chat, m.id, m.text)
            }
        }
    }

    /** Deletes our message that hasn't been sent (queued, or refused), with its files. */
    fun discard(m: Msg) =
        bg {
            if (MessageStore.get(m.id)?.pending != true) return@bg
            removeMedia(m.id, MessageStore.deleteMessage(m.id))
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

    /** The call ringing now and its caller, for [settleWatchCall]. */
    @Volatile private var ringing: Pair<String, androidx.core.app.Person>? = null

    /** The answered-looking notification is up and will be removed by [settleWatchCall]. */
    @Volatile private var settling = false

    private fun stopRing() {
        ringtone?.stop()
        ringtone = null
        val r = ringing
        ringing = null
        if (r == null) {
            // Declining stops the ringing, then the call's end does again: leave a pending settle be.
            if (!settling) app.getSystemService(NotificationManager::class.java).cancel(NOTIF_INCOMING)
        } else {
            settleWatchCall(r.first, r.second)
        }
    }

    /**
     * Takes a ringing call off a watch bridged by Gadgetbridge. Removing the notification alone
     * leaves it there (Gadgetbridge loses track of which call ended). Gadgetbridge reads a call
     * notification that drops to one button as answered and then clears that caller's call, so
     * the notification briefly shows only Hang up, then goes.
     */
    private fun settleWatchCall(
        callID: String,
        caller: androidx.core.app.Person,
    ) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.notify(
            NOTIF_INCOMING,
            NotificationCompat
                .Builder(app, CH_INCOMING)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                // Gadgetbridge names the call from this text, as it did the ringing one.
                .setContentTitle(caller.name)
                .setContentText(caller.name)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .addAction(0, "Hang up", CallActionReceiver.pending(app, CallActionReceiver.HANG_UP, callID))
                .build(),
        )
        settling = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            settling = false
            // Unless another call has started ringing since.
            if (ringing == null) nm.cancel(NOTIF_INCOMING)
        }, SETTLE_MS)
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
        // What was written while offline goes now.
        Outbox.flush()
        checkCommunities()
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

    override fun onReaction(
        chat: String,
        targetID: String,
        reactor: String,
        emoji: String,
        timestampMs: Long,
    ) {
        Wake.hold(2_000)
        MessageStore.setReaction(chat, targetID, reactor, emoji, timestampMs)
        // As WhatsApp: only reactions to our own messages notify, and quiet chats stay quiet.
        val target = MessageStore.get(targetID) ?: return
        if (emoji.isEmpty() || reactor == "me" || !target.fromMe || MessageStore.openChat.value == chat) return
        // Like a message: the chat shows unread until opened (muted ones too, just without a notification).
        MessageStore.markReacted(chat, maxOf(timestampMs, target.ts + 1))
        if (MessageStore.chatState(chat).quietAt(System.currentTimeMillis())) return
        val who = MessageStore.title(reactor)
        val n =
            NotificationCompat
                .Builder(app, CH_MESSAGES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(if (chat.endsWith("@g.us")) "${MessageStore.title(chat)} · $who" else who)
                .setContentText("reacted $emoji to: ${quoteSnippet(target)}")
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
        app.getSystemService(NotificationManager::class.java).notify(chat.hashCode(), n)
    }

    /**
     * Sets our reaction to [m] to [emoji] ("" removes it), here and for everyone. Shown at once;
     * if sending fails it's undone and the reason shown.
     */
    fun react(
        m: Msg,
        emoji: String,
    ) {
        val before = MessageStore.ownReaction(m.id).orEmpty()
        MessageStore.setReaction(m.chat, m.id, "me", emoji, System.currentTimeMillis())
        thread(name = "wa-react") {
            try {
                val cl = client ?: error("not connected")
                cl.sendReaction(m.chat, m.id, if (m.fromMe) "me" else m.sender, emoji)
            } catch (e: Exception) {
                WaState.log("reaction failed: ${e.message}")
                MessageStore.setReaction(m.chat, m.id, "me", before, System.currentTimeMillis())
                WaState.error.value = "Couldn't send the reaction (${e.message}). Check the connection and try again."
            }
        }
    }

    override fun onMessageEdited(
        chat: String,
        id: String,
        text: String,
        editedAtMs: Long,
        fromMe: Boolean,
        sender: String,
    ) {
        Wake.hold(2_000)
        if (!MessageStore.editMessage(chat, id, text, editedAtMs, fromMe, sender)) WaState.log("edit of $id not applied")
    }

    override fun onMessageRevoked(
        chat: String,
        id: String,
        fromMe: Boolean,
        sender: String,
    ) {
        Wake.hold(2_000)
        // Its author or (in groups) an admin deleted it; WhatsApp checks which may.
        if (MessageStore.get(id)?.chat != chat) return
        removeMedia(id, MessageStore.markDeleted(id))
    }

    /**
     * Changes the text of our own message [m] to [text] for everyone. WhatsApp is told first; only
     * once it accepts does the message change here. [onDone] gets the error, or null.
     */
    fun editMessage(
        m: Msg,
        text: String,
        onDone: (error: String?) -> Unit,
    ) = thread(name = "wa-edit-msg") {
        try {
            val cl = client ?: error("not connected")
            cl.editMessage(m.chat, m.id, text)
            MessageStore.editMessage(m.chat, m.id, text, System.currentTimeMillis(), byMe = true, by = "")
            onDone(null)
        } catch (e: Exception) {
            WaState.log("edit message failed: ${e.message}")
            onDone(e.message ?: "unknown error")
        }
    }

    override fun onMessageDeleted(
        chat: String,
        id: String,
    ) {
        Wake.hold(2_000)
        if (MessageStore.get(id)?.chat != chat) return
        removeMedia(id, MessageStore.deleteMessage(id))
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
        WaState.call.value =
            CallUi(callID, peer, incoming = true, phase = 2, video = video, remoteVideo = video).let { c ->
                groupCalls[callID]?.let { c.withGroup(it) } ?: c
            }
        val group = groupCalls[callID]
        CallLog.start(callID, group?.group?.ifEmpty { null } ?: peer, incoming = true, video = video, members = group?.members.orEmpty())
        resolve(peer)
        ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.also { it.play() }
        val caller =
            androidx.core.app.Person
                .Builder()
                .setName(callNotificationText(callID, peer))
                .setImportant(true)
                .build()
        val n =
            NotificationCompat
                .Builder(app, CH_INCOMING)
                .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                .setContentTitle(
                    "Incoming WhatsApp " + (if (groupCalls.containsKey(callID)) "group " else "") + if (video) "video call" else "call",
                ).setContentText(callNotificationText(callID, peer))
                // Answer and Decline buttons, which watches forward and press.
                .setStyle(
                    NotificationCompat.CallStyle
                        .forIncomingCall(
                            caller,
                            CallActionReceiver.pending(app, CallActionReceiver.DECLINE, callID),
                            CallActionReceiver.pending(app, CallActionReceiver.ANSWER, callID),
                        ).setIsVideo(video),
                ).setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(openApp(), true)
                .setContentIntent(openApp())
                .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_INCOMING, n)
        ringing = callID to caller
        showCallScreen()
    }

    /**
     * Opens the incoming call screen. Android only acts on the full-screen intent while the
     * screen is off or its own lock screen shows; otherwise it shows a heads-up notification,
     * which LightOS doesn't draw (and a lock screen from another app doesn't count as locked).
     * Allowed to draw over other apps, the app may start its screen itself, which then turns
     * the screen on (while a call is on, [MainActivity] shows over the lock screen and turns it on).
     */
    private fun showCallScreen() {
        val interactive = app.getSystemService(android.os.PowerManager::class.java).isInteractive
        val locked = app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        val overApps = android.provider.Settings.canDrawOverlays(app)
        val path =
            when {
                MainActivity.resumed -> "already on screen"
                overApps -> "opened directly"
                !interactive || locked -> "full-screen intent"
                else -> "nothing (not allowed over other apps)"
            }
        if (!MainActivity.resumed && overApps) {
            runCatching {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                        ),
                )
            }.onFailure { WaState.log("incoming call: couldn't open the call screen: ${it.message}") }
        }
        WaState.log("incoming call screen: screen ${if (interactive) "on" else "off"}, ${if (locked) "locked" else "not locked"}, $path")
    }

    /**
     * Leaves a notification for a call that rang out here unanswered. Unlike the ringing one it can be
     * dismissed, so watches that skip ongoing notifications get it too.
     */
    private fun notifyMissed(r: CallRecord) {
        val who =
            if (r.isGroup) {
                groupCallTitle(r.peer.takeIf { it.endsWith("@g.us") }?.let(MessageStore::title), r.members.map(MessageStore::title))
            } else {
                MessageStore.title(r.peer)
            }
        val n =
            NotificationCompat
                .Builder(app, CH_MISSED)
                .setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setContentTitle(r.missedTitle)
                .setContentText(who)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setWhen(r.startedAt)
                .setShowWhen(true)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
        app.getSystemService(NotificationManager::class.java).notify("missed:${r.id}".hashCode(), n)
    }

    /** Who's calling, for the notification: the person, or the group call's group or participants. */
    private fun callNotificationText(
        callID: String,
        peer: String,
    ): String {
        val g = groupCalls[callID] ?: return MessageStore.title(normalizeJid(peer))
        return groupCallTitle(g.group.takeIf { it.isNotEmpty() }?.let(MessageStore::title), g.members.map(MessageStore::title))
    }

    /** Group details reported before a call rings ([onGroupCall] comes first), by call ID. */
    private val groupCalls = java.util.concurrent.ConcurrentHashMap<String, GroupCallInfo>()

    override fun onGroupCall(
        callID: String,
        group: String,
        members: String,
        joined: String,
    ) {
        val info = GroupCallInfo(normalizeJid(group), members.jidList(), joined.jidList())
        groupCalls[callID] = info
        resolveAll(info.members)
        CallLog.addMembers(callID, info.members)
        WaState.call.update { c -> if (c?.id == callID) c.withGroup(info) else c }
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
        groupCalls.remove(callID)
        val rec = CallLog.ended(callID)
        stopRing()
        if (rec?.outcome == CallRecord.Outcome.MISSED) notifyMissed(rec)
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
        quotedID: String,
        quotedSender: String,
        quotedText: String,
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
                quoteId = quotedID,
                quoteSender = quotedSender,
                quoteText = quotedText,
            ),
        )
        if (!isGroup) resolve(chat)
        // A group joined since linking: find out whether it's in a community.
        if (isGroup && MessageStore.communityKind(chat) == CommunityKind.UNKNOWN) checkCommunities()
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

    /**
     * Deletes [m] from this account's devices (Delete for me). WhatsApp is told first; only once it
     * accepts is the message removed here. [onDone] gets the error, or null.
     */
    fun deleteForMe(
        m: Msg,
        onDone: (error: String?) -> Unit,
    ) = thread(name = "wa-delete-msg") {
        try {
            val cl = client ?: error("not connected")
            val author = if (m.isGroup && !m.fromMe) m.sender else ""
            cl.deleteForMe(m.chat, m.id, author, m.fromMe, m.ts)
            removeMedia(m.id, MessageStore.deleteMessage(m.id))
            onDone(null)
        } catch (e: Exception) {
            WaState.log("delete message failed: ${e.message}")
            onDone(e.message ?: "unknown error")
        }
    }

    /** Deletes our own message [m] for everyone in the chat; it stays as a "deleted" line here. */
    fun deleteForEveryone(
        m: Msg,
        onDone: (error: String?) -> Unit,
    ) = thread(name = "wa-revoke-msg") {
        try {
            val cl = client ?: error("not connected")
            cl.revokeMessage(m.chat, m.id)
            removeMedia(m.id, MessageStore.markDeleted(m.id))
            onDone(null)
        } catch (e: Exception) {
            WaState.log("delete for everyone failed: ${e.message}")
            onDone(e.message ?: "unknown error")
        }
    }

    /** Deletes message [id]'s media file [path], if it's one of ours, and its video. */
    private fun removeMedia(
        id: String,
        path: String?,
    ) {
        Media.videoFile(id).delete()
        Media.audioFile(id).delete()
        if (path == null) return
        val f = File(path)
        if (f.canonicalPath.startsWith(mediaDir().canonicalPath + File.separator)) f.delete()
    }

    /** Removes [chat]'s messages up to [beforeMs] and their media files, and its notification. */
    private fun removeChat(
        chat: String,
        beforeMs: Long,
        keepChat: Boolean,
    ) {
        val left = MessageStore.count(chat)
        MessageStore.videoIds(chat, beforeMs).forEach { Media.videoFile(it).delete() }
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

    override fun onParticipantVideoState(
        callID: String,
        participant: String,
        state: Long,
        orientation: Long,
    ) {
        WaState.log("video state (${video.participantName(normalizeJid(participant))}): raw=$state orientation=$orientation")
        // As in 1:1 calls, only 0 and 6 mean the camera went off; other states (2 comes while a
        // call is being set up) leave it as it was.
        val cameraOn =
            when (state) {
                1L -> true
                0L, 6L -> false
                else -> null
            }
        video.participantState(normalizeJid(participant), cameraOn, orientation.toInt())
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
            // Communities named only by their groups still need a name.
            if (!batch.onDemand) checkCommunities()
        } catch (e: Exception) {
            WaState.log("history import failed: ${e.message}")
        }
    }

    override fun onCommunityLink(
        group: String,
        community: String,
        announcements: Boolean,
        name: String,
    ) {
        Wake.hold(2_000)
        WaState.log("community link: ${if (community.isEmpty()) "group left its community" else "group joined a community"}")
        MessageStore.setChatName(group, name)
        MessageStore.setCommunity(group, community, if (announcements) CommunityKind.ANNOUNCEMENTS else CommunityKind.GROUP)
        if (community.isNotEmpty()) checkCommunities()
    }

    @Volatile private var checkingCommunities = false

    /** Groups and communities looked up this session, so a failing one isn't asked about again. */
    private val communitiesTried: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    /**
     * Looks up, one at a time, the groups nobody has said the community of (joined since
     * linking), then the communities with no name yet (which the first may turn up). Safe to call
     * often: one check runs at a time, and each group is asked about once a session.
     */
    private fun checkCommunities() {
        synchronized(this) {
            if (checkingCommunities) return
            checkingCommunities = true
        }
        thread(name = "wa-communities", isDaemon = true) {
            try {
                val c = client ?: return@thread
                while (true) {
                    val todo = (MessageStore.uncheckedGroups() + MessageStore.unnamedCommunities()).filter { communitiesTried.add(it) }
                    if (todo.isEmpty()) break
                    for (group in todo) {
                        if (!c.isConnected) return@thread
                        lookUpCommunity(c, group)
                        Thread.sleep(200)
                    }
                }
            } finally {
                checkingCommunities = false
            }
        }
    }

    private fun lookUpCommunity(
        c: Client,
        group: String,
    ) {
        try {
            val o = org.json.JSONObject(c.groupCommunity(group))
            MessageStore.setChatName(group, o.optString("name"))
            val kind =
                when {
                    o.optBoolean("isCommunity") -> CommunityKind.COMMUNITY
                    o.optBoolean("announcements") -> CommunityKind.ANNOUNCEMENTS
                    else -> CommunityKind.GROUP
                }
            MessageStore.setCommunity(group, normalizeJid(o.optString("community")), kind)
        } catch (e: Exception) {
            // Left, or no longer allowed to see it: treat it as a plain group.
            WaState.log("community check failed: ${e.message}")
            if (MessageStore.communityKind(group) == CommunityKind.UNKNOWN) MessageStore.setCommunity(group, "", CommunityKind.GROUP)
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
