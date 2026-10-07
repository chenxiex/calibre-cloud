package io.github.chenxiex.calibrecloud.state

import android.database.sqlite.SQLiteDatabase

/** Reconstructs the actual v4 queue shape without relying on SQLite's newer DROP COLUMN syntax. */
object LegacyCacheSchemaFixture {
    fun downgradeToFour(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(false)
        database.beginTransaction()
        try {
            val columns = "task_id,record,stage,attempts,retry_at,checkpoint,checkpoint_backend,checkpoint_version,recovery_required,control"
            database.execSQL("CREATE TEMP TABLE legacy_queue_backup AS SELECT $columns FROM queued_tasks")
            database.execSQL("DROP TABLE queued_tasks")
            database.execSQL("""
                CREATE TABLE queued_tasks (
                    task_id TEXT PRIMARY KEY NOT NULL,
                    record TEXT NOT NULL,
                    stage TEXT NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    retry_at INTEGER NOT NULL DEFAULT 0,
                    checkpoint TEXT,
                    checkpoint_backend TEXT,
                    checkpoint_version TEXT,
                    recovery_required INTEGER NOT NULL DEFAULT 0,
                    control TEXT
                )
            """.trimIndent())
            database.execSQL("INSERT INTO queued_tasks($columns) SELECT $columns FROM legacy_queue_backup")
            database.execSQL("DROP TABLE legacy_queue_backup")
            database.execSQL("DROP TABLE cache_cleanup")
            database.execSQL("DROP TABLE library_preferences")
            database.version = 4
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
            database.setForeignKeyConstraintsEnabled(true)
        }
    }
}
