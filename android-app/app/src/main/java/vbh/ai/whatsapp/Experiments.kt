package vbh.ai.whatsapp

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo

/**
 * Debug-only switches for A/B testing video calls without reinstalling:
 *   adb shell am start -n vbh.ai.whatsapp/.MainActivity --es exp "cvo0,fixed,gop1,br500,noaudio"
 *   adb shell am start -n vbh.ai.whatsapp/.MainActivity --es exp none
 * Each flag makes one aspect match meowcaller's browser console. Read when a call's camera
 * starts; logged so the call log says which variant ran. Ignored in non-debuggable builds.
 */
object Experiments {
    @Volatile var flags: Set<String> = emptySet()
        private set

    fun load(context: Context) {
        flags =
            context
                .getSharedPreferences("experiments", Context.MODE_PRIVATE)
                .getStringSet("flags", emptySet())
                .orEmpty()
    }

    fun applyIntent(
        context: Context,
        intent: Intent?,
    ) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val raw = intent?.getStringExtra("exp") ?: return
        val set =
            raw
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "none" }
                .toSet()
        context
            .getSharedPreferences("experiments", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("flags", set)
            .apply()
        flags = set
        WaState.log("experiments set: ${if (set.isEmpty()) "none" else set.sorted().joinToString(",")}")
    }

    fun on(flag: String) = flag in flags

    /** Send orientation 0 (frames arrive sideways) instead of the rotation they need. */
    val cvo0 get() = on("cvo0")

    /** Fixed 1/15 s per frame (like the browser) instead of camera-measured durations. */
    val fixedDuration get() = on("fixed")

    /** A keyframe every second (like the browser) instead of every 2 s. */
    val gop1 get() = on("gop1")

    /** 500 kbps (like the browser) instead of 800 kbps. */
    val br500 get() = on("br500")

    /** Send no microphone audio, to test whether the receiver delays video to match audio. */
    val noAudio get() = on("noaudio")
}
