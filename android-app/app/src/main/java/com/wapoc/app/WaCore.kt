package com.wapoc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.Ringtone
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import com.wapoc.walib.Client
import com.wapoc.walib.Listener
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

    fun mediaDir(): File = File(app.filesDir, "media").also { it.mkdirs() }

    /** Sets up what the UI needs even before the connection starts (camera preview, demo mode). */
    @Synchronized
    fun prepare(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
        if (WaState.logFile == null) WaState.logFile = java.io.File(app.filesDir, "wapoc.log")
        if (!::video.isInitialized) video = VideoEngine(app).also { v ->
            // Their frames are the ground truth for "their camera is on", whatever the signalling says.
            v.onRemoteFrameShown = { markRemoteVideo() }
            v.onRemoteVideoWithoutView = { markRemoteVideo() }
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (started) return
        started = true
        prepare(context)
        audio = AudioEngine(app)
        MessageStore.init(app)
        AppSettings.init(app)
        CallLog.init(app)
        createChannels()
        thread(name = "wa-connect") {
            try {
                val c = Client(File(app.filesDir, "wa.db").absolutePath, this)
                c.setSpeaker(audio)
                c.setVideoRenderer(video)
                c.setMediaDir(mediaDir().absolutePath)
                client = c
                WaState.status.value = if (c.isPaired) "connecting…" else "waiting for QR…"
                c.connect()
            } catch (e: Exception) {
                WaState.error.value = e.message
                WaState.status.value = "error"
                WaState.log("connect failed: ${e.message}")
            }
        }
    }

    fun serviceNotification(): Notification =
        NotificationCompat.Builder(app, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("WA PoC")
            .setContentText(WaState.status.value)
            .setOngoing(true)
            .setContentIntent(openApp())
            .build()

    private fun openApp() = PendingIntent.getActivity(
        app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannels() {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_INCOMING, "Incoming calls", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT))
    }

    // --- contact resolution ---

    /** Looks up a name / phone number for [jid] off the main thread and publishes it for titles. */
    fun resolve(jid: String) {
        val c = client ?: return
        val key = normalizeJid(jid)
        thread(name = "wa-resolve") {
            try {
                val name = c.contactName(key)
                val phone = c.phoneNumber(key)
                if (name.isNotBlank() && MessageStore.names.value[key] != name) MessageStore.names.value = MessageStore.names.value + (key to name)
                if (phone.isNotBlank() && MessageStore.phones.value[key] != phone) MessageStore.phones.value = MessageStore.phones.value + (key to phone)
            } catch (_: Exception) {}
        }
    }

    private fun resolveAllChats() {
        MessageStore.messages.value.map { it.chat }.distinct().filterNot { it.endsWith("@g.us") }.forEach { resolve(it) }
    }

    // --- contacts ---

    /** Reads saved contacts from WhatsApp's store (off the main thread) and publishes them. */
    fun loadContacts() {
        val cl = client ?: return
        thread(name = "wa-contacts") {
            try {
                val list = cl.contacts().lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 3) null else Contact(chat = cl.canonicalChat(p[0]), name = p[1], phone = p[2])
                }.distinctBy { it.chat }.sortedBy { it.name.lowercase() }.toList()
                WaState.contacts.value = list
            } catch (e: Exception) {
                WaState.log("contacts: ${e.message}")
            } finally {
                WaState.contactsLoaded.value = true
            }
        }
    }

    /** Maps a typed number to the chat key WhatsApp uses for that person, then calls [done] on the main thread. */
    fun canonicalChat(number: String, done: (String) -> Unit) {
        val cl = client ?: return
        thread(name = "wa-canon") {
            val key = try { cl.canonicalChat(number) } catch (_: Exception) { number.removePrefix("+") + "@s.whatsapp.net" }
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

    fun call(target: String, withVideo: Boolean = false) = bg {
        WaState.error.value = null
        val id = if (withVideo) client!!.callVideo(target.trim()) else client!!.call(target.trim())
        CallLog.start(id, if (target.contains("@")) target.trim() else target.trim().removePrefix("+") + "@s.whatsapp.net", incoming = false, video = withVideo)
        WaState.call.value = CallUi(id, target.trim(), incoming = false, phase = 1, video = withVideo, localVideo = withVideo, remoteVideo = withVideo)
        resolve(if (target.contains("@")) target.trim() else target.trim().removePrefix("+") + "@s.whatsapp.net")
    }

    /** Answers; for a video call our camera turns on too (unless camera access was refused). */
    fun answer(withCamera: Boolean = true) = bg {
        val c = WaState.call.value ?: return@bg
        stopRing()
        client!!.answer(c.id)
        if (c.video) WaState.call.value = WaState.call.value?.copy(localVideo = withCamera, phase = maxOf(c.phase, 3))
    }

    // --- video ---

    /** Ask to switch the current audio call to video; our camera turns on. */
    fun startVideo() = bg {
        val c = WaState.call.value ?: return@bg
        client!!.startVideo(c.id)
        CallLog.markVideo(c.id)
        WaState.call.value = WaState.call.value?.copy(video = true, localVideo = true)
        setSpeaker(true)
        sendOrientation(c.id)
    }

    /** Accept their request to switch to video; our camera turns on. */
    fun acceptVideo() = bg {
        val c = WaState.call.value ?: return@bg
        client!!.acceptVideo(c.id)
        CallLog.markVideo(c.id)
        WaState.call.value = WaState.call.value?.copy(video = true, localVideo = true, upgradeRequested = false)
        setSpeaker(true)
        sendOrientation(c.id)
    }

    /** Turn our camera off for the rest of the call; audio and their video continue. */
    fun stopVideo() = bg {
        val c = WaState.call.value ?: return@bg
        WaState.call.value = WaState.call.value?.copy(localVideo = false, upgradeRequested = false)
        video.stopCamera()
        client!!.stopVideo(c.id)
    }

    /** Starts the camera and encoder for the current call, drawing a preview into [preview]. */
    fun startCamera(preview: android.view.Surface?) {
        WaState.call.value ?: return
        lastPreview = preview
        WaState.log("experiments for this call: ${if (Experiments.flags.isEmpty()) "none" else Experiments.flags.sorted().joinToString(",")}")
        setMuted(Experiments.noAudio)
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
        sentOk = 0; sentFailed = 0; lastSendError = null
        video.startCamera(preview) { au, durationUs ->
            if (Demo.loopback) { video.frame(au); return@startCamera }
            // Fails until the call's video media is up; meowcaller then waits for a keyframe.
            try {
                cl?.sendVideo(c.id, au, durationUs)
                if (sentOk++ == 0) WaState.log("video out: first frame accepted (${au.size} bytes, NAL ${VideoEngine.nalTypes(au).sorted()})")
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
        val cvo = if (Experiments.cvo0) 0 else (4 - video.sensorQuarterTurns) % 4
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

    fun reject() = bg {
        val c = WaState.call.value ?: return@bg
        CallLog.markDeclined(c.id)
        stopRing()
        client!!.reject(c.id)
    }

    fun hangup() = bg {
        val c = WaState.call.value ?: return@bg
        client!!.hangup(c.id)
    }

    /** Sends a text message; shows it immediately as pending, then replaces it with the real one. */
    fun sendText(chat: String, text: String) = bg {
        val body = text.trim()
        if (body.isEmpty()) return@bg
        val tmp = "pending-" + System.nanoTime()
        MessageStore.add(Msg(tmp, chat, "me", "", body, System.currentTimeMillis(), true, chat.endsWith("@g.us"), pending = true))
        try {
            val id = client!!.sendText(chat, body)
            MessageStore.messages.value = MessageStore.messages.value.filterNot { it.id == tmp }
            MessageStore.add(Msg(id, chat, "me", "", body, System.currentTimeMillis(), true, chat.endsWith("@g.us"), status = Status.SENT))
        } catch (e: Exception) {
            MessageStore.messages.value = MessageStore.messages.value.filterNot { it.id == tmp }
            throw e
        }
    }

    /**
     * Sends a picked photo or video: shows it at once as a pending "[image]" / "[video]" row,
     * then uploads and swaps in the real message (or removes the row and shows the error).
     */
    fun sendMedia(chat: String, uri: android.net.Uri) = bg {
        val tmp = "pending-" + System.nanoTime()
        val isGroup = chat.endsWith("@g.us")
        var local: File? = null
        try {
            val media = MediaSend.prepare(app, uri)
            val label = if (media.kind == "image") "[image]" else "[video]"
            // Keep our own copy to show in the thread: the photo itself, or a video's thumbnail.
            local = File(mediaDir(), "$tmp.jpg").also { it.writeBytes(if (media.kind == "image") media.data else media.thumbnail) }
            MessageStore.add(Msg(tmp, chat, "me", "", label, System.currentTimeMillis(), true, isGroup, pending = true, media = local.path))
            val id = client!!.sendMedia(chat, media.kind, media.data, media.mimetype, "",
                media.width.toLong(), media.height.toLong(), media.seconds.toLong(), media.thumbnail)
            val kept = File(mediaDir(), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg").also { local.renameTo(it) }
            MessageStore.messages.value = MessageStore.messages.value.filterNot { it.id == tmp }
            MessageStore.add(Msg(id, chat, "me", "", label, System.currentTimeMillis(), true, isGroup, status = Status.SENT, media = kept.path))
            WaState.log("media sent: ${media.kind} ${media.data.size / 1024} KB ${media.width}x${media.height}")
        } catch (e: Exception) {
            MessageStore.messages.value = MessageStore.messages.value.filterNot { it.id == tmp }
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

    private fun bg(block: () -> Unit) = thread {
        try { block() } catch (e: Exception) {
            WaState.error.value = e.message
            WaState.log("error: ${e.message}")
        }
    }

    private fun stopRing() {
        ringtone?.stop(); ringtone = null
        app.getSystemService(NotificationManager::class.java).cancel(NOTIF_INCOMING)
    }

    // --- Listener (called from Go goroutines) ---

    override fun onQR(code: String) { WaState.qr.value = code; WaState.status.value = "scan the QR code in WhatsApp" }

    override fun onConnected(jid: String) {
        WaState.qr.value = null
        WaState.connected.value = true
        WaState.status.value = "connected"
        WaState.ownNumber.value = client?.ownNumber().orEmpty()
        resolveAllChats()
        loadContacts()
    }

    override fun onDisconnected() { WaState.connected.value = false; WaState.status.value = "disconnected, reconnecting…" }

    override fun onIncomingCall(callID: String, peer: String, video: Boolean) {
        WaState.call.value = CallUi(callID, peer, incoming = true, phase = 2, video = video, remoteVideo = video)
        CallLog.start(callID, peer, incoming = true, video = video)
        resolve(peer)
        ringtone = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.also { it.play() }
        val n = NotificationCompat.Builder(app, CH_INCOMING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle(if (video) "Incoming WhatsApp video call" else "Incoming WhatsApp call")
            .setContentText(MessageStore.title(normalizeJid(peer), MessageStore.messages.value))
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

    override fun onCallState(callID: String, phase: Long) {
        WaState.call.value = WaState.call.value?.takeIf { it.id == callID }?.let {
            val p = phase.toInt()
            it.copy(phase = p, connectedAt = if (p == 4 && it.connectedAt == 0L) System.currentTimeMillis() else it.connectedAt)
        } ?: WaState.call.value
    }

    override fun onCallEnded(callID: String, reason: String) {
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
        chat: String, sender: String, senderName: String, id: String, text: String,
        timestampMs: Long, fromMe: Boolean, isGroup: Boolean, mediaPath: String,
    ) {
        // Our own messages sent from another device have already reached the server.
        MessageStore.add(Msg(id, chat, sender, senderName, text, timestampMs, fromMe, isGroup,
            status = if (fromMe) Status.SENT else Status.NONE, media = mediaPath))
        if (!isGroup) resolve(chat)
        if (!fromMe && MessageStore.openChat.value != chat) {
            val title = MessageStore.title(chat, MessageStore.messages.value)
            val n = NotificationCompat.Builder(app, CH_MESSAGES)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(if (isGroup && senderName.isNotBlank()) "$title · $senderName" else title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build()
            app.getSystemService(NotificationManager::class.java).notify(chat.hashCode(), n)
        }
    }

    override fun onMediaReady(id: String, path: String) = MessageStore.setMedia(id, path)

    override fun onReceipt(chat: String, ids: String, kind: String, timestampMs: Long) {
        val set = ids.split(',').filter { it.isNotBlank() }.toSet()
        when (kind) {
            "delivered" -> MessageStore.applyReceipt(set, Status.DELIVERED)
            "read", "played" -> MessageStore.applyReceipt(set, Status.SEEN)
            "read-self" -> MessageStore.readElsewhere(chat, set)
        }
    }

    override fun onVideoState(callID: String, active: Boolean, upgrade: Boolean, orientation: Long, raw: Long) {
        val cur = WaState.call.value?.takeIf { it.id == callID } ?: return
        WaState.log("video state raw=$raw active=$active upgrade=$upgrade orientation=$orientation")
        val stopped = raw == 0L || raw == 6L
        WaState.call.value = cur.copy(
            video = cur.video || active || upgrade,
            remoteVideo = when { active -> true; stopped -> false; else -> cur.remoteVideo },
            upgradeRequested = if (upgrade && !cur.localVideo) true else cur.upgradeRequested && !stopped,
        )
        if (active || upgrade) CallLog.markVideo(callID)
    }

    override fun onKeyframeRequest(callID: String) {
        WaState.log("video out: peer asked for a keyframe")
        video.requestKeyframe()
    }

    override fun onRemoteOrientation(callID: String, quarterTurns: Long) {
        WaState.log("video in: orientation from RTP = $quarterTurns")
        val cur = WaState.call.value?.takeIf { it.id == callID } ?: return
        WaState.call.value = cur.copy(remoteOrientation = quarterTurns.toInt())
    }

    override fun onLog(line: String) { WaState.log(line) }
}
