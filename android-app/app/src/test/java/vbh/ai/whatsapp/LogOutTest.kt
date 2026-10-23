package vbh.ai.whatsapp

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogOutTest {
    private lateinit var db: SQLiteDatabase

    @Before fun setUp() {
        db = ChatDb(ApplicationProvider.getApplicationContext(), null).writableDatabase
        MessageStore.open(db)
        CallLog.open(db)
    }

    @After fun tearDown() = db.close()

    @Test fun clearAllDeletesMessagesChatsAndCalls() {
        MessageStore.add(Msg("1", "a@lid", "a@lid", "", "hi", 10, false, false))
        MessageStore.setChatName("a@lid", "A")
        MessageStore.setArchived("a@lid", true)
        MessageStore.names.value = mapOf("a@lid" to "A")
        CallLog.start("c1", "a@lid", incoming = true)

        MessageStore.clearAll()
        CallLog.clearAll()

        assertTrue(MessageStore.chats().isEmpty())
        assertTrue(MessageStore.chats(archived = true).isEmpty())
        assertTrue(MessageStore.thread("a@lid", 10).isEmpty())
        assertTrue(MessageStore.names.value.isEmpty())
        assertEquals("Unknown contact", MessageStore.title("a@lid"))
        assertTrue(CallLog.calls.value.isEmpty())
    }

    @Test fun noticeSaysWhyTheSessionEnded() {
        assertTrue(loggedOutNotice(401).contains("removed from Linked devices"))
        assertTrue(loggedOutNotice(403).contains("main phone was logged out or reinstalled"))
        assertTrue(loggedOutNotice(406).contains("logged this phone out"))
    }
}
