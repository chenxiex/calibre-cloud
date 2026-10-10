package io.github.chenxiex.calibrecloud.storage.local

import android.system.ErrnoException
import android.system.OsConstants
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.PushJournal
import io.github.chenxiex.calibrecloud.storage.api.PushOutcome
import io.github.chenxiex.calibrecloud.storage.api.SourceFailure
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.api.WriteBlock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Replaces a local library's metadata.db with a staged database (R15, Q78). SAF has no atomic replace
 * and renaming onto an existing name picks another name, so the commit only creates or renames to
 * names that do not exist, in three renames between fixed temporary names in the library root:
 *
 * 1. `metadata.db.calibrecloud-new` is created, written truncating, synced and read back against the staged hash;
 * 2. metadata.db must still hash to the base version with no nonempty -wal or -journal and no -shm,
 *    otherwise the push is a conflict; it is then renamed to `metadata.db.calibrecloud-old`;
 * 3. `-new` is renamed to metadata.db and read back against the staged hash;
 * 4. `-old` is deleted once it still hashes to the base.
 *
 * Before each step its phase, both hashes and the staged file's private path are written to the task's
 * [PushJournal]. Whenever the commit stops, metadata.db is the whole base, the whole staged database, or
 * absent while the whole `-old` or `-new` is still there. [finish] completes or rolls back from the
 * journal and the files alone: a missing metadata.db is restored from `-new` when it hashes to the staged
 * database, otherwise from `-old` when it hashes to the base; a remaining `-new` is deleted only when its
 * content is a prefix of the staged file (or, without that file, equals the staged hash) and a remaining
 * `-old` only when it hashes to the base. Anything else stays untouched and fails as LEFTOVER_FILES, as
 * do files under these names found when no journal exists. A failure inside [push] runs the same
 * finishing at once; it then reports an unknown result so the caller runs a whole round again.
 *
 * Every document is located by name ([LocalDocumentAccess.locate]); no directory is listed. Neither
 * pausable nor cancellable. Logs nothing: callers log the outcome without names or content (R33).
 */
class LocalDatabaseCommit(private val documents: LocalDocumentAccess, private val io: CoroutineDispatcher) {
    enum class Phase { NEW_WRITING, NEW_WRITTEN, OLD_RENAMED, NEW_RENAMED }

    private data class Record(val phase: Phase, val base: String, val staged: String, val stagedFile: String) {
        fun encode() = listOf(VERSION, phase.name, base, staged, stagedFile).joinToString("\n")
    }

    /** No network request; provider queries only. */
    suspend fun capability(treeUri: String): WriteBlock? = withContext(io) {
        try {
            if (!documents.locatesByPath) return@withContext WriteBlock.UNSUPPORTED_PROVIDER
            if (!documents.writeGranted(treeUri)) return@withContext WriteBlock.READ_ONLY_GRANT
            val root = documents.root(treeUri)
            val database = locate(treeUri, DATABASE) ?: return@withContext WriteBlock.SOURCE_UNAVAILABLE
            if (!root.directory || !root.creatable || !database.writable || !database.renamable || !database.deletable) {
                WriteBlock.UNSUPPORTED_PROVIDER
            } else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            WriteBlock.AUTHORIZATION_REQUIRED
        } catch (_: Exception) {
            WriteBlock.SOURCE_UNAVAILABLE
        }
    }

    suspend fun push(treeUri: String, staged: File, stagedSha256: String, base: FileVersion, journal: PushJournal): PushOutcome =
        mapped {
            require(base.backend == BackendKind.LOCAL)
            if (!documents.locatesByPath) throw LocalSourceException(StorageErrorKind.UNSUPPORTED_OPERATION)
            val root = documents.root(treeUri)
            // The journal is empty here, so files under these names are not this task's to touch.
            if (locate(treeUri, NEW) != null || locate(treeUri, OLD) != null) throw leftover()
            var record = Record(Phase.NEW_WRITING, base.token, stagedSha256, staged.absolutePath)
            var ownsNew = false
            try {
                journal.write(record.encode())
                val created = documents.create(treeUri, root.id, NEW)
                ownsNew = true
                if (created.name != NEW) {
                    documents.delete(treeUri, created.id)
                    ownsNew = false
                    throw IOException()
                }
                documents.openWrite(treeUri, created.id).use { output ->
                    staged.inputStream().use { it.copyTo(output, BUFFER) }
                    output.flush()
                    output.fd.sync()
                }
                if (hash(treeUri, created.id) != stagedSha256) throw IOException()

                record = record.copy(phase = Phase.NEW_WRITTEN)
                journal.write(record.encode())
                val database = locate(treeUri, DATABASE)
                if (database == null || logsPresent(treeUri) || hash(treeUri, database.id) != base.token) {
                    documents.delete(treeUri, created.id)
                    ownsNew = false
                    journal.write(null)
                    return@mapped PushOutcome.Conflict
                }

                record = record.copy(phase = Phase.OLD_RENAMED)
                journal.write(record.encode())
                renameExactly(treeUri, database, OLD, DATABASE)

                record = record.copy(phase = Phase.NEW_RENAMED)
                journal.write(record.encode())
                val committed = renameExactly(treeUri, created, DATABASE, NEW)
                ownsNew = false
                if (hash(treeUri, committed.id) != stagedSha256) throw IOException()

                val old = locate(treeUri, OLD)
                if (old != null && hash(treeUri, old.id) == base.token) documents.delete(treeUri, old.id)
                journal.write(null)
                PushOutcome.Pushed(FileVersion(BackendKind.LOCAL, stagedSha256))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                try { finishRecord(treeUri, record, journal, ownsNew) } catch (_: Exception) {}
                throw failure
            }
        }

    suspend fun finish(treeUri: String, journal: PushJournal) {
        mapped {
            val value = journal.read() ?: return@mapped
            val record = decode(value)
            if (record == null) {
                // A record this version cannot read: clear it only when there is nothing to finish.
                if (locate(treeUri, DATABASE) == null || locate(treeUri, NEW) != null || locate(treeUri, OLD) != null) throw leftover()
                journal.write(null)
            } else finishRecord(treeUri, record, journal, false)
        }
    }

    /** [ownsNew]: this process created `-new` in the current call, so its content need not be checked. */
    private suspend fun finishRecord(treeUri: String, record: Record, journal: PushJournal, ownsNew: Boolean) {
        var newFile = locate(treeUri, NEW)
        var oldFile = locate(treeUri, OLD)
        if (locate(treeUri, DATABASE) == null) {
            if (newFile != null && hash(treeUri, newFile.id) == record.staged) {
                renameExactly(treeUri, newFile, DATABASE, NEW)
                newFile = null
            } else if (oldFile != null && hash(treeUri, oldFile.id) == record.base) {
                renameExactly(treeUri, oldFile, DATABASE, OLD)
                oldFile = null
            } else throw leftover()
        }
        if (newFile != null) {
            if (!ownsNew && !isStagedPrefix(treeUri, newFile, record)) throw leftover()
            documents.delete(treeUri, newFile.id)
        }
        if (oldFile != null) {
            if (hash(treeUri, oldFile.id) != record.base) throw leftover()
            documents.delete(treeUri, oldFile.id)
        }
        journal.write(null)
    }

    private fun isStagedPrefix(treeUri: String, document: LocalDocument, record: Record): Boolean {
        val staged = File(record.stagedFile)
        if (!staged.isFile) return hash(treeUri, document.id) == record.staged
        documents.openRead(treeUri, document.id).use { candidate ->
            staged.inputStream().use { expected ->
                val left = ByteArray(BUFFER)
                val right = ByteArray(BUFFER)
                while (true) {
                    val count = readFully(candidate, left, BUFFER)
                    if (count == 0) return true
                    if (readFully(expected, right, count) != count) return false
                    for (index in 0 until count) if (left[index] != right[index]) return false
                }
            }
        }
    }

    /** InputStream.readNBytes needs API 33. */
    private fun readFully(input: java.io.InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val count = input.read(buffer, total, length - total)
            if (count < 0) break
            total += count
        }
        return total
    }

    /** A rename that lands on another name is undone and fails, so the fixed names stay meaningful. */
    private fun renameExactly(treeUri: String, document: LocalDocument, name: String, original: String): LocalDocument {
        if (locate(treeUri, name) != null) throw leftover()
        val renamed = documents.rename(treeUri, document.id, name)
        if (renamed.name != name) {
            if (renamed.name != original) try { documents.rename(treeUri, renamed.id, original) } catch (_: Exception) {}
            throw IOException()
        }
        return renamed
    }

    /** Only the logs a consistent replacement rules out; located by name like everything else. */
    private fun logsPresent(treeUri: String): Boolean =
        locate(treeUri, "$DATABASE-shm") != null ||
            listOf("$DATABASE-wal", "$DATABASE-journal").any { name ->
                locate(treeUri, name)?.let { log -> documents.openRead(treeUri, log.id).use { it.read() != -1 } } == true
            }

    private fun locate(treeUri: String, name: String): LocalDocument? =
        documents.locate(treeUri, RelativeSourcePath(name))?.also {
            if (!documents.isWithinRoot(treeUri, it.id) || it.directory) throw leftover()
        }

    private fun hash(treeUri: String, documentId: String): String = documents.openRead(treeUri, documentId).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun decode(value: String): Record? {
        val parts = value.split('\n')
        if (parts.size != 5 || parts[0] != VERSION) return null
        val phase = Phase.entries.firstOrNull { it.name == parts[1] } ?: return null
        return Record(phase, parts[2], parts[3], parts[4])
    }

    private fun leftover() = LocalSourceException(StorageErrorKind.LEFTOVER_FILES)

    /**
     * A source that changed, a local I/O failure or a provider error leaves the result unknown and is
     * transient, so the caller runs a whole round again; leftover files, a lost grant, a full volume and
     * an unsupported provider are not.
     */
    private suspend fun <T> mapped(block: suspend () -> T): T = withContext(io) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SourceFailure) {
            throw failure
        } catch (error: LocalSourceException) {
            throw SourceFailure(StorageError(error.kind), transient = error.kind == StorageErrorKind.VERSION_CONFLICT ||
                error.kind == StorageErrorKind.LOCAL_IO || error.kind == StorageErrorKind.SOURCE_MISSING)
        } catch (_: SecurityException) {
            throw SourceFailure(StorageErrorKind.AUTHORIZATION_EXPIRED)
        } catch (error: IOException) {
            val noSpace = generateSequence<Throwable>(error) { it.cause }.any { it is ErrnoException && it.errno == OsConstants.ENOSPC }
            throw if (noSpace) SourceFailure(StorageErrorKind.INSUFFICIENT_SPACE) else SourceFailure(StorageError(StorageErrorKind.LOCAL_IO), transient = true)
        } catch (_: UnsupportedOperationException) {
            throw SourceFailure(StorageErrorKind.UNSUPPORTED_OPERATION)
        } catch (_: IllegalArgumentException) {
            // The provider rejects a document ID that disappeared between lookup and use.
            throw SourceFailure(StorageError(StorageErrorKind.LOCAL_IO), transient = true)
        }
    }

    companion object {
        const val DATABASE = "metadata.db"
        const val NEW = "metadata.db.calibrecloud-new"
        const val OLD = "metadata.db.calibrecloud-old"
        private const val VERSION = "local-commit-1"
        private const val BUFFER = 64 * 1024
    }
}
