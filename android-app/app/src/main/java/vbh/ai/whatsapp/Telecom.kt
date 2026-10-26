package vbh.ai.whatsapp

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells Android's call system (Telecom) about WhatsApp calls, as self-managed calls: the app keeps
 * its own ringing, notification and call screen, while anything that controls calls through Telecom
 * (Bluetooth earphones, a cellular call arriving) can answer, decline or end them, and Telecom routes
 * the call's audio to a connected headset.
 */
object CallTelecom {
    private const val SCHEME = "wa"
    private const val EXTRA_CALL_ID = "vbh.ai.whatsapp.CALL_ID"
    private const val EXTRA_NAME = "vbh.ai.whatsapp.NAME"
    private const val EXTRA_VIDEO = "vbh.ai.whatsapp.VIDEO"

    private lateinit var tm: TelecomManager
    private lateinit var handle: PhoneAccountHandle

    /** Live connections by call ID. */
    private val connections = ConcurrentHashMap<String, WaConnection>()

    /** Calls ended or declined from this phone, so the end reads as local rather than remote. */
    private val endedHere = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        tm = context.getSystemService(TelecomManager::class.java)
        handle = PhoneAccountHandle(ComponentName(context, WaConnectionService::class.java), "whatsapp")
        runCatching {
            tm.registerPhoneAccount(
                PhoneAccount
                    .builder(handle, "WhatsApp")
                    .setCapabilities(
                        PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_VIDEO_CALLING or
                            PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING,
                    ).setSupportedUriSchemes(listOf(SCHEME))
                    .build(),
            )
        }.onFailure { WaState.log("telecom: couldn't register the phone account: ${it.message}") }
    }

    private fun details(
        callID: String,
        name: String,
        video: Boolean,
    ) = Bundle().apply {
        putString(EXTRA_CALL_ID, callID)
        putString(EXTRA_NAME, name)
        putBoolean(EXTRA_VIDEO, video)
    }

    /** A call is ringing here. */
    fun incoming(
        callID: String,
        name: String,
        video: Boolean,
    ) {
        if (!::tm.isInitialized) return
        runCatching {
            tm.addNewIncomingCall(
                handle,
                Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts(SCHEME, callID, null))
                    putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, details(callID, name, video))
                },
            )
        }.onFailure { WaState.log("telecom: incoming call not registered: ${it.message}") }
    }

    /** We're calling out. */
    fun outgoing(
        callID: String,
        name: String,
        video: Boolean,
    ) {
        if (!::tm.isInitialized) return
        runCatching {
            tm.placeCall(
                Uri.fromParts(SCHEME, callID, null),
                Bundle().apply {
                    putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                    if (video) putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
                    putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, details(callID, name, video))
                },
            )
        }.onFailure { WaState.log("telecom: outgoing call not registered: ${it.message}") }
    }

    /** Media is flowing: the call is active. */
    fun active(callID: String) {
        connections[callID]?.setActive()
    }

    /** Marks [callID] as ended or declined from this phone. */
    fun endingHere(callID: String) {
        endedHere += callID
    }

    fun ended(
        callID: String,
        outcome: CallRecord.Outcome?,
    ) {
        val here = endedHere.remove(callID)
        val c = connections.remove(callID) ?: return
        val cause =
            when {
                outcome == CallRecord.Outcome.MISSED -> DisconnectCause.MISSED
                outcome == CallRecord.Outcome.DECLINED -> DisconnectCause.REJECTED
                here -> DisconnectCause.LOCAL
                else -> DisconnectCause.REMOTE
            }
        c.setDisconnected(DisconnectCause(cause))
        c.destroy()
    }

    /** Whether the call's audio is on a Bluetooth headset. */
    fun onBluetooth() =
        connections.values
            .firstOrNull()
            ?.callAudioState
            ?.route == CallAudioState.ROUTE_BLUETOOTH

    /** Routes call audio to the loudspeaker or back; false if Telecom doesn't hold this call. */
    fun speaker(on: Boolean): Boolean {
        val c = connections.values.firstOrNull() ?: return false
        // Off the loudspeaker, back to a Bluetooth headset if one is connected.
        val back =
            if (c.callAudioState?.supportedRouteMask?.and(CallAudioState.ROUTE_BLUETOOTH) != 0) {
                CallAudioState.ROUTE_BLUETOOTH
            } else {
                CallAudioState.ROUTE_WIRED_OR_EARPIECE
            }
        @Suppress("DEPRECATION")
        c.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else back)
        return true
    }

    internal fun create(
        request: ConnectionRequest,
        incoming: Boolean,
    ): Connection {
        val extras =
            if (incoming) {
                request.extras.getBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS) ?: request.extras
            } else {
                request.extras.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) ?: request.extras
            }
        val callID = extras.getString(EXTRA_CALL_ID) ?: request.address?.schemeSpecificPart.orEmpty()
        val c = WaConnection(callID)
        c.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        c.audioModeIsVoip = true
        c.setAddress(Uri.fromParts(SCHEME, callID, null), TelecomManager.PRESENTATION_ALLOWED)
        c.setCallerDisplayName(extras.getString(EXTRA_NAME).orEmpty(), TelecomManager.PRESENTATION_ALLOWED)
        c.videoState = if (extras.getBoolean(EXTRA_VIDEO)) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        if (incoming) c.setRinging() else c.setDialing()
        // The call may already have ended while Telecom set this up.
        if (WaState.call.value?.id != callID) {
            c.setDisconnected(DisconnectCause(DisconnectCause.OTHER))
            c.destroy()
        } else {
            connections[callID] = c
        }
        WaState.log("telecom: ${if (incoming) "incoming" else "outgoing"} call registered")
        return c
    }
}

/** One WhatsApp call as Telecom sees it; Telecom's commands go to the app's own call handling. */
class WaConnection(
    private val callID: String,
) : Connection() {
    private fun current() = WaState.call.value?.id == callID

    override fun onAnswer() = onAnswer(VideoProfile.STATE_AUDIO_ONLY)

    override fun onAnswer(videoState: Int) {
        WaState.log("telecom: answer")
        if (current()) WaCore.answer()
    }

    override fun onReject() {
        WaState.log("telecom: reject")
        if (current()) WaCore.reject()
    }

    override fun onDisconnect() {
        WaState.log("telecom: disconnect")
        if (!current()) return
        if (WaState.call.value?.let { it.incoming && it.phase == 2 } == true) WaCore.reject() else WaCore.hangup()
    }

    override fun onAbort() = onDisconnect()

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: CallAudioState) {
        WaState.log("telecom: audio route ${CallAudioState.audioRouteToString(state.route)}")
        // Keep the call screen's speaker button in step with routes changed outside the app.
        if (current()) WaState.call.value = WaState.call.value?.copy(speaker = state.route == CallAudioState.ROUTE_SPEAKER)
    }
}

class WaConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection = CallTelecom.create(request, incoming = true)

    override fun onCreateOutgoingConnection(
        account: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection = CallTelecom.create(request, incoming = false)

    override fun onCreateIncomingConnectionFailed(
        account: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) = WaState.log("telecom: incoming call refused")

    override fun onCreateOutgoingConnectionFailed(
        account: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) = WaState.log("telecom: outgoing call refused")
}
