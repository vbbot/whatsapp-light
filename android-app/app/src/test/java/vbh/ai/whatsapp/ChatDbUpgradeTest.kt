package vbh.ai.whatsapp

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatDbUpgradeTest {
    private fun columns(
        db: SQLiteDatabase,
        table: String,
    ) = db.rawQuery("PRAGMA table_info($table)", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(1)) } }

    @Test fun aVersion6DatabaseGainsReactedAt() {
        val db = SQLiteDatabase.create(null)
        // As version 6 left it: chats without reacted_at.
        db.execSQL("CREATE TABLE chats (chat TEXT PRIMARY KEY, last_read INTEGER NOT NULL DEFAULT 0, name TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, text TEXT NOT NULL)")
        db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, peer TEXT NOT NULL)")
        ChatDb(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext(),
            null,
        ).onUpgrade(db, 6, ChatDb.VERSION)
        assertTrue("reacted_at" in columns(db, "chats"))
    }

    @Test fun aVersion7DatabaseGainsEditedAt() {
        val db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE messages (id TEXT PRIMARY KEY, text TEXT NOT NULL)")
        db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, peer TEXT NOT NULL)")
        ChatDb(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext(),
            null,
        ).onUpgrade(db, 7, ChatDb.VERSION)
        assertTrue("edited_at" in columns(db, "messages"))
    }

    @Test fun aVersion8DatabaseGainsCallMembers() {
        val db = SQLiteDatabase.create(null)
        db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, peer TEXT NOT NULL)")
        ChatDb(
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext(),
            null,
        ).onUpgrade(db, 8, ChatDb.VERSION)
        assertTrue("members" in columns(db, "calls"))
    }
}
