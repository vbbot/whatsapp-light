package vbh.ai.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveMediaTest {
    private fun msg(
        text: String,
        id: String = "3EB0A1B2C3",
        media: String = "",
        mediaRef: String = "",
        fromMe: Boolean = false,
        status: Int = Status.NONE,
    ) = Msg(id, "a@lid", "a@lid", "", text, 10, fromMe, false, status = status, media = media, mediaRef = mediaRef)

    private fun photo(
        text: String = "[image] ",
        id: String = "3EB0A1B2C3",
        fromMe: Boolean = false,
        status: Int = Status.NONE,
    ) = msg(text, id = id, media = "/m/$id.jpg", mediaRef = "REF", fromMe = fromMe, status = status)

    @Test fun photosVideosGifsAndVoicesCanBeSaved() {
        assertTrue(photo().canSaveMedia())
        assertTrue(photo(fromMe = true).canSaveMedia())
        // Only the small preview is here: the full photo can be downloaded.
        assertTrue(msg("[image]", media = "/m/t_t.jpg", mediaRef = "REF").canSaveMedia())
        assertTrue(msg("[video] clip", mediaRef = "REF").canSaveMedia())
        assertTrue(msg("[gif] loop", mediaRef = "REF").canSaveMedia())
        assertTrue(msg("[voice message]", mediaRef = "REF").canSaveMedia())
    }

    @Test fun textStickersDeletedAndUnsentMessagesCannotBeSaved() {
        assertFalse(msg("hello").canSaveMedia())
        assertFalse(msg("[document] report.pdf", mediaRef = "REF").canSaveMedia())
        assertFalse(msg("[sticker]", media = "/m/s.webp", mediaRef = "REF").canSaveMedia())
        assertFalse(msg(DELETED_TEXT).canSaveMedia())
        assertFalse(photo(status = Status.QUEUED).canSaveMedia())
        assertFalse(photo(status = Status.NOT_SENT).canSaveMedia())
        // A photo with no local file and no download info has nothing to save.
        assertFalse(msg("[image]").canSaveMedia())
        // A preview with nowhere to download the full photo from has nothing to save.
        assertFalse(msg("[image]", media = "/m/t_t.jpg").canSaveMedia())
        // A video with nothing local and no download info has nothing to save.
        assertFalse(msg("[video] clip").canSaveMedia())
        // A voice message with nothing local and no download info has nothing to save.
        assertFalse(msg("[voice message]").canSaveMedia())
    }

    @Test fun eachKindGetsItsMimeType() {
        assertEquals("image/jpeg", photo().saveMimeType())
        assertEquals("video/mp4", msg("[video] clip").saveMimeType())
        assertEquals("video/mp4", msg("[gif] loop").saveMimeType())
        assertEquals("audio/ogg", msg("[voice message]").saveMimeType())
    }

    @Test fun theCaptionNamesTheFileAndKeepsTheRightExtension() {
        assertEquals("holiday.jpg", photo("[image] holiday").saveFileName())
        assertEquals("clip.mp4", msg("[video] clip").saveFileName())
        assertEquals("party.mp4", msg("[gif] party").saveFileName())
        // A caption that already ends with the extension isn't doubled up.
        assertEquals("beach.jpg", photo("[image] beach.jpg").saveFileName())
        assertEquals("clip.MP4", msg("[video] clip.MP4").saveFileName())
    }

    @Test fun withoutACaptionTheMessageIdNamesTheFile() {
        assertEquals("3EB0A1B2C3.jpg", photo().saveFileName())
        assertEquals("3EB0A1B2C3.mp4", msg("[video]").saveFileName())
        assertEquals("3EB0A1B2C3.mp4", msg("[gif]").saveFileName())
        // A voice message's placeholder isn't a caption.
        assertEquals("3EB0A1B2C3.ogg", msg("[voice message]").saveFileName())
    }

    @Test fun captionsAreMadeSafeForAFileName() {
        assertEquals("a_b_c.jpg", photo("[image] a/b:c").saveFileName())
        assertEquals("hello world.jpg", photo("[image]  hello   world ").saveFileName())
        assertEquals("line_one.jpg", photo("[image] line\none").saveFileName())
        assertEquals("_.._etc_passwd.jpg", photo("[image] ../../etc/passwd").saveFileName())
        // Nothing usable in the caption: fall back to the ID, also made safe.
        assertEquals("3EB0_1.jpg", photo("[image] ...", id = "3EB0:1").saveFileName())
        assertEquals("hidden.jpg", photo("[image] .hidden.").saveFileName())
    }

    @Test fun longCaptionsAreShortened() {
        val name = photo("[image] " + "a".repeat(200)).saveFileName()
        assertEquals("a".repeat(60) + ".jpg", name)
    }

    @Test fun namesAreTrimmedOfWhitespaceAndDots() {
        assertEquals("hi", sanitizeSaveName("  hi.  "))
        assertEquals("", sanitizeSaveName("..."))
        assertEquals("a_b", sanitizeSaveName("a\\b"))
    }
}
