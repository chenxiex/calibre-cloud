package io.github.chenxiex.calibrecloud.state

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A caller the user allowed to send requests (R37); the token itself is never stored or shown. */
data class ExternalClient(val name: String, val registeredAt: Long)

/** The external control switch and the registered callers; replaceable in tests. */
interface ExternalControlStore {
    suspend fun enabled(): Boolean

    suspend fun setEnabled(enabled: Boolean)

    suspend fun clients(): List<ExternalClient>

    /**
     * Registers [name] with [tokenHash], replacing an earlier registration of the same name, after the user
     * confirmed it; [enable] also turns the switch on in the same transaction (Q92).
     */
    suspend fun register(name: String, tokenHash: String, enable: Boolean)

    suspend fun remove(name: String)

    /** Whether [tokenHash] belongs to a registered caller, whatever the switch. */
    suspend fun registered(tokenHash: String): Boolean
}

/** The KOReader data folder the plugin is installed into (R37, Q98); replaceable in tests. */
interface KoreaderFolderSetting {
    /** The folder's tree URI; null when none was chosen. */
    suspend fun koreaderFolder(): String?

    suspend fun setKoreaderFolder(treeUri: String)
}

/**
 * Global like the other settings: no cache cleanup or library removal touches it. Only token hashes are
 * stored, so the database never holds a usable token.
 */
class ExternalControlRepository(
    private val database: ApplicationStateDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : ExternalControlStore, KoreaderFolderSetting {
    override suspend fun enabled(): Boolean = withContext(ioDispatcher) { enabled(database.readableDatabase) }

    override suspend fun setEnabled(enabled: Boolean): Unit = withContext(ioDispatcher) {
        database.writableDatabase.update("application_settings", ContentValues().apply { put("external_control", if (enabled) 1 else 0) },
            "singleton = 1", null)
    }

    override suspend fun clients(): List<ExternalClient> = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery("SELECT name, registered_at FROM external_clients ORDER BY registered_at, name", null).use {
            buildList { while (it.moveToNext()) add(ExternalClient(it.getString(0), it.getLong(1))) }
        }
    }

    override suspend fun register(name: String, tokenHash: String, enable: Boolean): Unit = withContext(ioDispatcher) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            // A token is one caller's; the same token under another name replaces that registration too.
            db.delete("external_clients", "name = ? OR token_hash = ?", arrayOf(name, tokenHash))
            db.insertOrThrow("external_clients", null, ContentValues().apply {
                put("name", name)
                put("token_hash", tokenHash)
                put("registered_at", clock())
            })
            if (enable) db.update("application_settings", ContentValues().apply { put("external_control", 1) }, "singleton = 1", null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun remove(name: String): Unit = withContext(ioDispatcher) {
        database.writableDatabase.delete("external_clients", "name = ?", arrayOf(name))
    }

    override suspend fun registered(tokenHash: String): Boolean = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery("SELECT 1 FROM external_clients WHERE token_hash = ?", arrayOf(tokenHash)).use { it.moveToFirst() }
    }

    private fun enabled(db: SQLiteDatabase) =
        db.rawQuery("SELECT external_control FROM application_settings WHERE singleton = 1", null).use { it.moveToFirst() && it.getInt(0) == 1 }

    override suspend fun koreaderFolder(): String? = withContext(ioDispatcher) {
        database.readableDatabase.rawQuery("SELECT koreader_folder FROM application_settings WHERE singleton = 1", null)
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
    }

    override suspend fun setKoreaderFolder(treeUri: String): Unit = withContext(ioDispatcher) {
        database.writableDatabase.update("application_settings", ContentValues().apply { put("koreader_folder", treeUri) },
            "singleton = 1", null)
    }
}
