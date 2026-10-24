package vbh.ai.whatsapp

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StickersTest {
    private val animation = """{"v":"5.9.0","fr":60,"ip":0,"op":10,"w":512,"h":512,"layers":[]}"""

    private fun zip(vararg entries: Pair<String, String>): File =
        File.createTempFile("sticker", ".webp").also { f ->
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("animation/"))
                for ((name, body) in entries) {
                    z.putNextEntry(ZipEntry(name))
                    z.write(body.toByteArray())
                }
            }
        }

    @Test fun aLottieZipIsReadWhateverItsOtherEntriesAndName() {
        // As WhatsApp sends them: the animation, then files whose names also contain ".json".
        val f =
            zip(
                "animation/animation.json" to animation,
                "animation/animation.json.trust_token" to "not json",
                "animation/animation.json.overridden_metadata" to "not json",
            )
        assertTrue(decodeSticker(f) is StickerArt.Lottie)
    }

    @Test fun aZipWithoutAnAnimationOrAnUnreadableFileIsNothing() {
        assertNull(decodeSticker(zip("animation/readme.txt" to "hi")))
        assertNull(decodeSticker(File.createTempFile("sticker", ".webp").also { it.writeText("junk") }))
    }
}
