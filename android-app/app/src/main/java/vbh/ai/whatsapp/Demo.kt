package vbh.ai.whatsapp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.Intent

/**
 * Debug-only: `adb shell am start -n vbh.ai.whatsapp/.MainActivity --es demo <name>` seeds sample
 * data and fake call states so each screen can be screenshotted without a paired account.
 * Ignored entirely in non-debuggable builds.
 */
object Demo {
    /** Debug loopback: our encoded camera frames are decoded into the remote view. */
    @Volatile var loopback = false

    fun requested(context: Context, intent: Intent?): String? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return intent?.getStringExtra("demo")
    }

    fun apply(context: Context, name: String) {
        // Loopback only drives the call screen: it must never write sample data into the
        // real message store (this mode is meant to be safe on a paired phone).
        if (name == "loopback") {
            loopback = true
            WaState.call.value = CallUi("v0", "loopback@s.whatsapp.net", incoming = false, phase = 4,
                connectedAt = System.currentTimeMillis(), video = true, localVideo = true, remoteVideo = true, speaker = true,
                // Show our own frames the way the other phone will: rotated by the orientation we send.
                remoteOrientation = (4 - WaCore.video.sensorQuarterTurns) % 4)
            return
        }
        MessageStore.init(context)
        val now = System.currentTimeMillis()
        val min = 60_000L
        fun m(id: String, chat: String, who: String, text: String, ago: Long, mine: Boolean = false) =
            Msg(id, chat, if (mine) "me" else chat, if (mine) "" else who, text, now - ago * min, mine, false)
        listOf(
            m("1", "111@s.whatsapp.net", "Anni", "See you at the station at 6", 12),
            m("2", "222@s.whatsapp.net", "Sophie", "Sent you the photos", 90),
            m("3", "333@s.whatsapp.net", "Daniel", "Hey Fenn, yeah cool! See you tomorrow!", 60 * 26),
            m("4", "333@s.whatsapp.net", "Daniel", "Can you call me when you land", 60 * 26 + 5),
            m("5", "333@s.whatsapp.net", "Daniel", "Safe flight", 60 * 26 + 6),
            m("6", "333@s.whatsapp.net", "Daniel", "Landed. Calling you in ten.", 60 * 25, mine = true).copy(status = Status.SEEN),
            m("7", "333@s.whatsapp.net", "Daniel", "Perfect, I will be at my desk", 60 * 25 - 2),
            m("9", "333@s.whatsapp.net", "Daniel", "On my way now", 3, mine = true).copy(status = Status.DELIVERED),
            m("8", "444@s.whatsapp.net", "Maya Anderson-Whitfield", "Lunch on Friday?", 60 * 24 * 3),
        ).forEach { MessageStore.add(it) }
        MessageStore.lastRead.value = mapOf("222@s.whatsapp.net" to now, "333@s.whatsapp.net" to now, "444@s.whatsapp.net" to now)

        WaState.contacts.value = listOf(
            Contact("333@s.whatsapp.net", "Daniel", "+447700900333"), Contact("555@s.whatsapp.net", "Aisha Khan", "+447700900555"),
            Contact("666@s.whatsapp.net", "Ben", "+447700900666"), Contact("777@s.whatsapp.net", "Chloe Martin", "+447700900777"),
            Contact("888@s.whatsapp.net", "Dr. Patel", "+447700900888"))
        WaState.contactsLoaded.value = true
        CallLog.calls.value = listOf(
            CallRecord("k1", "333@s.whatsapp.net", false, now - 20 * min, now - 20 * min, now - 18 * min + 14_000, CallRecord.Outcome.ANSWERED),
            CallRecord("k2", "222@s.whatsapp.net", true, now - 60 * 26 * min, outcome = CallRecord.Outcome.MISSED),
            CallRecord("k3", "447513141573@s.whatsapp.net", true, now - 60 * 72 * min, now - 60 * 72 * min, now - 60 * 72 * min + 41_000, CallRecord.Outcome.ANSWERED),
            CallRecord("k4", "444@s.whatsapp.net", false, now - 60 * 24 * 5 * min, outcome = CallRecord.Outcome.NO_ANSWER),
            CallRecord("k5", "195824822825056@lid", true, now - 60 * 24 * 6 * min, outcome = CallRecord.Outcome.DECLINED),
        )
        WaState.connected.value = true
        WaState.status.value = "connected"
        WaState.ownNumber.value = "+447700900000"
        WaState.qr.value = null
        WaState.call.value = null
        when (name) {
            "thread" -> Nav.push(Screen.Thread("333@s.whatsapp.net"))
            "calltype" -> { Nav.push(Screen.Thread("333@s.whatsapp.net")); Nav.push(Screen.CallType("333@s.whatsapp.net")) }
            "callconfirm" -> { Nav.push(Screen.Thread("333@s.whatsapp.net")); Nav.push(Screen.CallType("333@s.whatsapp.net")); Nav.push(Screen.CallConfirm("333@s.whatsapp.net", video = true)) }
            "composer" -> { Nav.push(Screen.Thread("333@s.whatsapp.net")); Nav.push(Screen.Composer("333@s.whatsapp.net")) }
            "newchat" -> Nav.push(Screen.Contacts(Purpose.MESSAGE))
            "newcall" -> { Nav.tab.value = Tab.CALLS; Nav.push(Screen.Contacts(Purpose.CALL)) }
            "number" -> Nav.push(Screen.Number(Purpose.CALL))
            "calls" -> Nav.tab.value = Tab.CALLS
            "calls-empty" -> { CallLog.calls.value = emptyList(); Nav.tab.value = Tab.CALLS }
            "settings" -> Nav.push(Screen.Settings)
            "pairing" -> { WaState.connected.value = false; WaState.qr.value = "2@Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6,AAAAbbbbCCCCdddd1111,2222eeeeFFFF,5" }
            "offline" -> WaState.connected.value = false
            "incoming" -> WaState.call.value = CallUi("c1", "333:7@s.whatsapp.net", incoming = true, phase = 2)
            "incoming-unknown" -> WaState.call.value = CallUi("c4", "195824822825056:7@lid", incoming = true, phase = 2)
            "loopback" -> {
                loopback = true
                WaState.call.value = CallUi("v0", "333:7@s.whatsapp.net", incoming = false, phase = 4, connectedAt = now - 12_000, video = true, localVideo = true, remoteVideo = true, speaker = true)
            }
            "videocall" -> WaState.call.value = CallUi("v1", "333:7@s.whatsapp.net", incoming = false, phase = 4, connectedAt = now - 47_000, video = true, localVideo = true, remoteVideo = false, speaker = true)
            "incoming-video" -> WaState.call.value = CallUi("v2", "333:7@s.whatsapp.net", incoming = true, phase = 2, video = true)
            "upgrade" -> WaState.call.value = CallUi("v3", "333:7@s.whatsapp.net", incoming = false, phase = 4, connectedAt = now - 95_000, upgradeRequested = true, video = true)
            "calling" -> WaState.call.value = CallUi("c2", "333@s.whatsapp.net", incoming = false, phase = 1)
            "incall" -> WaState.call.value = CallUi("c3", "333:7@s.whatsapp.net", incoming = false, phase = 4, connectedAt = now - 83_000)
            "incall-muted" -> WaState.call.value = CallUi("c3", "333:7@s.whatsapp.net", incoming = false, phase = 4, connectedAt = now - 754_000, muted = true, speaker = true)
        }
    }
}
