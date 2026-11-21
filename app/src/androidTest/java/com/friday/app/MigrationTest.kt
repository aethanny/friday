package com.friday.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.friday.app.data.db.FridayDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), FridayDatabase::class.java)

    @Test
    fun v1DataSurvives() {
        val name = "migration-test"
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO tasks (id, title, notes, important, dueDate, dueTime, repeat, repeatDays, autoPrep, createdAt, postponeCount) " +
                    "VALUES (7, 'Gym', '', 0, 20000, 1140, 'NONE', 0, 1, 1, 0)",
            )
            execSQL(
                "INSERT INTO cues (id, taskId, kind, anchor, offsetMin, message, sound) VALUES (3, 7, 'START', 'START', 10, 'go', 1)",
            )
            execSQL("INSERT INTO notes (id, text, inbox, createdAt, updatedAt) VALUES (5, 'idea', 1, 1, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true)
        db.query("SELECT title, dueDate, dueTime FROM tasks WHERE id = 7").use {
            assertTrue(it.moveToFirst())
            assertEquals("Gym", it.getString(0)); assertEquals(20000L, it.getLong(1)); assertEquals(1140, it.getInt(2))
        }
        db.query("SELECT held, smartText IS NULL, smartKey IS NULL, placeType IS NULL, message FROM cues WHERE id = 3").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0)); assertEquals(1, it.getInt(1)); assertEquals(1, it.getInt(2)); assertEquals(1, it.getInt(3))
            assertEquals("go", it.getString(4))
        }
        db.query("SELECT text FROM notes WHERE id = 5").use { assertTrue(it.moveToFirst()); assertEquals("idea", it.getString(0)) }
        db.query("SELECT COUNT(*) FROM memories").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
    }
}
