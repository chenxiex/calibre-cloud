package io.github.chenxiex.calibrecloud.storage.local

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite integrity check of private candidates; this does not replace real SAF acceptance. */
@RunWith(AndroidJUnit4::class)
class LocalSnapshotIntegrityTest {
    @Test fun privateValidDatabaseAcceptedAndCorruptionRejectedWithoutDeletingCandidate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "snapshot-integrity-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val valid = File(directory, "valid.db")
            SQLiteDatabase.openOrCreateDatabase(valid, null).use { database ->
                database.rawQuery("PRAGMA journal_mode=DELETE", null).use { mode ->
                    assertTrue(mode.moveToFirst())
                    assertTrue(mode.getString(0).equals("delete", ignoreCase = true))
                }
                database.execSQL("CREATE TABLE sample(id INTEGER PRIMARY KEY, value TEXT)")
                database.execSQL("INSERT INTO sample(value) VALUES ('test')")
            }
            val corrupt = File(directory, "corrupt.db").apply { writeText("not a SQLite database") }
            val before = directory.listFiles()!!.associate { it.name to it.readBytes() }
            val validator = AndroidSnapshotValidator()
            assertTrue(validator.validate(valid))
            assertFalse(validator.validate(corrupt))
            assertTrue(corrupt.exists())
            val after = directory.listFiles()!!.associate { it.name to it.readBytes() }
            assertTrue(before.keys == after.keys)
            before.forEach { (name, bytes) -> assertTrue(bytes.contentEquals(after.getValue(name))) }
        } finally {
            directory.deleteRecursively()
        }
    }
}
