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
class PhotosTest {
    private lateinit var db: SQLiteDatabase
    private val calls = Collections.synchronizedList(mutableListOf<String>())

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        Photos.states.value = emptyMap()
    }

    @After fun tearDown() {
        Photos.downloader = null
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
        Photos.downloader = { m ->
            calls += m.id
            "/m/${m.id}.jpg"
        }
        val m = photo("s1")
        Photos.shown(m)
        Photos.shown(m)
        waitFor { MessageStore.get("s1")?.media == "/m/s1.jpg" }
        assertEquals(listOf("s1"), calls.toList())
        assertFalse(MessageStore.get("s1")!!.needsFullPhoto)
    }

    @Test fun openingRetriesAFailedPhoto() {
        Photos.downloader = { m ->
            calls += m.id
            if (calls.size == 1) error("network down")
            "/m/${m.id}.jpg"
        }
        val m = photo("o1")
        Photos.shown(m)
        waitFor { Photos.states.value["o1"] is Photos.State.Failed }
        // Showing it again doesn't retry; opening it does.
        Photos.shown(m)
        Photos.opened(m)
        waitFor { MessageStore.get("o1")?.media == "/m/o1.jpg" }
        assertEquals(listOf("o1", "o1"), calls.toList())
        assertFalse(Photos.states.value.containsKey("o1"))
    }

    @Test fun openedPhotosGoFirstThenTheLatest() {
        val now = System.currentTimeMillis()
        val jobs =
            listOf(
                Photos.Job("old shown", opened = false, order = 1, queuedAt = now),
                Photos.Job("opened", opened = true, order = 2, queuedAt = now),
                Photos.Job("new shown", opened = false, order = 3, queuedAt = now),
            )
        assertEquals(listOf("opened", "new shown", "old shown"), jobs.sorted().map { it.id })
    }
}
