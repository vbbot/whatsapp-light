package vbh.ai.whatsapp

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaTest {
    private lateinit var db: SQLiteDatabase
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        Media.states.value = emptyMap()
        Media.dir =
            kotlin.io.path
                .createTempDirectory("media")
                .toFile()
    }

    @After fun tearDown() {
        Media.photoDownloader = null
        Media.videoDownloader = null
        Media.videoBytes = null
        Media.videoPreview = null
        Media.dir?.deleteRecursively()
        db.close()
    }

    private fun photo(id: String) =
        Msg(id, "a@lid", "a@lid", "", "[image]", 1, false, false, media = "/m/${id}_t.jpg", mediaRef = "REF").also { MessageStore.add(it) }

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(what())
    }

    @Test fun shownFetchesOnceAndStoresTheFullPhoto() {
        Media.photoDownloader = { m ->
            calls += m.id
            "/m/${m.id}.jpg"
        }
        val m = photo("s1")
        Media.shown(m)
        Media.shown(m)
        waitFor { MessageStore.get("s1")?.media == "/m/s1.jpg" }
        assertEquals(listOf("s1"), calls.toList())
        assertFalse(MessageStore.get("s1")!!.needsFullPhoto)
    }

    @Test fun openingRetriesAFailedPhoto() {
        Media.photoDownloader = { m ->
            calls += m.id
            if (calls.size == 1) error("network down")
            "/m/${m.id}.jpg"
        }
        val m = photo("o1")
        Media.shown(m)
        waitFor { Media.states.value["o1"] is Media.State.Failed }
        // Showing it again doesn't retry; opening it does.
        Media.shown(m)
        Media.opened(m)
        waitFor { MessageStore.get("o1")?.media == "/m/o1.jpg" }
        assertEquals(listOf("o1", "o1"), calls.toList())
        assertFalse(Media.states.value.containsKey("o1"))
    }

    @Test fun openedPhotosGoFirstThenTheLatest() {
        val now = System.currentTimeMillis()
        val jobs =
            listOf(
                Media.Job("old shown", opened = false, order = 1, queuedAt = now),
                Media.Job("opened", opened = true, order = 2, queuedAt = now),
                Media.Job("new shown", opened = false, order = 3, queuedAt = now),
            )
        assertEquals(listOf("opened", "new shown", "old shown"), jobs.sorted().map { it.id })
    }

    private fun video(id: String) =
        Msg(id, "a@lid", "a@lid", "", "[video] ", 1, false, false, media = "/m/${id}_t.jpg", mediaRef = "REF").also { MessageStore.add(it) }

    @Test fun videosDownloadOnlyWhenOpened() {
        Media.videoDownloader = { m ->
            calls += m.id
            Media.videoFile(m.id).also { it.writeText("mp4") }.path
        }
        val m = video("v1:a")
        assertTrue(Media.needsVideo(m))
        Media.shown(m)
        Thread.sleep(100)
        assertTrue(calls.isEmpty())
        Media.opened(m)
        waitFor { Media.videoFile("v1:a").exists() && !Media.states.value.containsKey("v1:a") }
        assertFalse(Media.needsVideo(m))
        // Already here: opening again doesn't download.
        Media.opened(m)
        Thread.sleep(100)
        assertEquals(listOf("v1:a"), calls.toList())
        assertEquals("v1_a.mp4", Media.videoFile("v1:a").name)
        // The thumbnail stays the message's media.
        assertEquals("/m/v1:a_t.jpg", MessageStore.get("v1:a")?.media)
    }

    @Test fun aFailedVideoSaysWhy() {
        Media.videoDownloader = { error("no longer on WhatsApp's servers") }
        Media.opened(video("v2"))
        waitFor { Media.states.value["v2"] == Media.State.Failed("no longer on WhatsApp's servers") }
    }

    @Test fun videosWithoutDownloadInfoAreNotFetched() {
        val m = video("v3").copy(mediaRef = "")
        assertFalse(Media.needsVideo(m))
    }

    @Test fun videoIdsListsAChatsVideos() {
        video("v4")
        photo("p4")
        assertEquals(listOf("v4"), MessageStore.videoIds("a@lid"))
    }

    @Test fun smallVideosDownloadWhenShownAndGetASharpPreview() {
        Media.videoBytes = { m -> if (m.id == "small") 300_000L else Media.SHOWN_VIDEO_MAX_BYTES + 1 }
        Media.videoDownloader = { m ->
            calls += m.id
            Media.videoFile(m.id).also { it.writeText("mp4") }.path
        }
        Media.videoPreview = { id, _ -> "/m/${id}_p.jpg" }
        Media.shown(video("big"))
        Media.shown(video("small"))
        waitFor { MessageStore.get("small")?.media == "/m/small_p.jpg" }
        assertEquals(listOf("small"), calls.toList())
        assertFalse(Media.needsPreview(MessageStore.get("small")!!))
    }

    @Test fun aVideoAlreadyHereGetsItsPreviewWithoutDownloading() {
        Media.videoDownloader = { m ->
            calls += m.id
            ""
        }
        Media.videoPreview = { id, _ -> "/m/${id}_p.jpg" }
        val m = video("here")
        Media.videoFile("here").writeText("mp4")
        assertTrue(Media.needsPreview(m))
        Media.shown(m)
        waitFor { MessageStore.get("here")?.media == "/m/here_p.jpg" }
        assertTrue(calls.isEmpty())
    }

    @Test fun stickersAndGifsDownloadWhenShown() {
        Media.photoDownloader = { m ->
            calls += m.id
            "/m/${m.id}.webp"
        }
        Media.videoDownloader = { m ->
            calls += m.id
            Media.videoFile(m.id).also { it.writeText("mp4") }.path
        }
        val sticker = Msg("st", "a@lid", "a@lid", "", "[sticker]", 1, false, false, mediaRef = "REF").also { MessageStore.add(it) }
        assertTrue(sticker.isSticker && sticker.needsFullPhoto)
        Media.shown(sticker)
        waitFor { MessageStore.get("st")?.media == "/m/st.webp" }
        assertFalse(MessageStore.get("st")!!.needsFullPhoto)

        val gif = Msg("g", "a@lid", "a@lid", "", "[gif] ", 1, false, false, mediaRef = "REF").also { MessageStore.add(it) }
        assertTrue(gif.isGif && gif.isVideo)
        // GIFs play in the thread, so they come as soon as they're shown, whatever their size.
        Media.shown(gif)
        waitFor { Media.videoFile("g").exists() }
        assertEquals(listOf("st", "g"), calls.toList())
        assertEquals(listOf("g"), MessageStore.videoIds("a@lid").filter { it == "g" })
    }
}
