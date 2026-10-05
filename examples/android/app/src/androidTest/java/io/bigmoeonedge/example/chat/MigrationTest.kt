package io.bigmoeonedge.example.chat

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.bigmoeonedge.example.chat.data.ChatDb
import io.bigmoeonedge.example.chat.data.MIGRATION_1_2
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), ChatDb::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun version1ChatsSurviveTheMigrationToTheScanTables() {
        helper.createDatabase("mig", 1).apply {
            execSQL(
                "INSERT INTO conversations (title, modelPath, systemPrompt, thinking, createdAt, updatedAt, lastReadAt) " +
                    "VALUES ('kept', '/m.gguf', '', 0, 1, 1, 1)",
            )
            execSQL(
                "INSERT INTO messages (conversationId, role, text, reasoning, status, tokens, tokPerSec, prefillS, metrics, " +
                    "outOfContext, attempt, createdAt, queuedAt) VALUES (1, 'user', 'hello', '', 'DONE', 0, 0, 0, '', 0, 0, 1, 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("mig", 2, true, MIGRATION_1_2)
        db.query("SELECT title FROM conversations").use { c ->
            c.moveToFirst()
            assertEquals("kept", c.getString(0))
        }
        db.query("SELECT text FROM messages").use { c ->
            c.moveToFirst()
            assertEquals("hello", c.getString(0))
        }
        // The new tables exist and are empty.
        for (t in listOf("scan_runs", "scan_cells", "model_profiles")) {
            db.query("SELECT COUNT(*) FROM $t").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }
}
