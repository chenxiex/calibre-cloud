package io.github.chenxiex.calibrecloud.storage.local

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.PushJournal
import io.github.chenxiex.calibrecloud.storage.api.PushOutcome
import io.github.chenxiex.calibrecloud.storage.api.SourceFailure
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.api.WriteBlock
import io.github.chenxiex.calibrecloud.storage.local.LocalDatabaseCommit.Companion.DATABASE
import io.github.chenxiex.calibrecloud.storage.local.LocalDatabaseCommit.Companion.NEW
import io.github.chenxiex.calibrecloud.storage.local.LocalDatabaseCommit.Companion.OLD
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The three-rename local commit against a directory that behaves like the system external-storage
 * provider: IDs are names, renaming onto an existing name picks "name (1)" and writes go through a
 * truncating stream. Faults are injected at every provider operation, as a process death ([Crash],
 * which no code may catch) or as an I/O failure.
 */
class LocalDatabaseCommitTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Crash : Error()

    private class Journal : PushJournal {
        var value: String? = null
        val phases = mutableListOf<String?>()
        var onWrite: (String?) -> Unit = {}
        override suspend fun read() = value
        override suspend fun write(value: String?) {
            this.value = value
            phases += value?.split('\n')?.get(1)
            onWrite(value)
        }
    }

    /** [fault] runs before each mutating operation and each written chunk, numbered from 0. */
    private class Directory(val root: File) : LocalDocumentAccess {
        var events = 0
        var fault: (Int, String) -> Unit = { _, _ -> }
        var writeGrant = true
        var databaseFlags = true
        var denied = false
        private fun event(label: String) = fault(events++, label)
        private fun doc(file: File) = LocalDocument(file.name, file.name, file.isDirectory, true, file.length(),
            renamable = databaseFlags, deletable = databaseFlags, creatable = true)

        override val locatesByPath = true
        override fun root(treeUri: String): LocalDocument {
            if (denied) throw SecurityException()
            return LocalDocument("", "library", true, true, creatable = true)
        }
        override fun children(treeUri: String, parentId: String) = throw AssertionError("The commit never lists a directory")
        override fun locate(treeUri: String, path: RelativeSourcePath) = File(root, path.value).takeIf { it.exists() }?.let(::doc)
        override fun isWithinRoot(treeUri: String, documentId: String) = !documentId.contains('/')
        override fun openRead(treeUri: String, documentId: String): InputStream = File(root, documentId).inputStream()
        override fun writeGranted(treeUri: String) = writeGrant
        override fun create(treeUri: String, parentId: String, name: String): LocalDocument {
            event("create")
            return doc(unique(name).also { check(it.createNewFile()) })
        }
        override fun rename(treeUri: String, documentId: String, name: String): LocalDocument {
            event("rename $documentId")
            val target = unique(name)
            check(File(root, documentId).renameTo(target))
            return doc(target)
        }
        override fun delete(treeUri: String, documentId: String) {
            event("delete $documentId")
            check(File(root, documentId).delete())
        }
        override fun openWrite(treeUri: String, documentId: String): FileOutputStream =
            object : FileOutputStream(File(root, documentId)) {
                override fun write(b: ByteArray, off: Int, len: Int) {
                    event("write")
                    super.write(b, off, len)
                }
            }
        private fun unique(name: String): File {
            var file = File(root, name)
            var index = 1
            while (file.exists()) file = File(root, "$name (${index++})")
            return file
        }
    }

    private val base = Random(1).nextBytes(200_000)
    private val stagedBytes = Random(2).nextBytes(150_000) + base.copyOfRange(0, 60_000)

    private fun TestScope.fixture(): Triple<Directory, LocalDatabaseCommit, File> {
        val library = temporary.newFolder()
        File(library, DATABASE).writeBytes(base)
        File(library, "other.epub").writeBytes(byteArrayOf(1, 2, 3))
        val staged = File(temporary.newFolder(), "round.staged.db").apply { writeBytes(stagedBytes) }
        val directory = Directory(library)
        return Triple(directory, LocalDatabaseCommit(directory, StandardTestDispatcher(testScheduler)), staged)
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val baseVersion = FileVersion(BackendKind.LOCAL, sha(base))
    private fun File.file(name: String) = File(this, name)
    private fun names(directory: Directory) = directory.root.list()!!.toSortedSet()

    @Test fun pushReplacesTheDatabaseInThreeRenamesAndLeavesNothingBehind() = runTest {
        val (directory, commit, staged) = fixture()
        val journal = Journal()
        val outcome = commit.push("tree", staged, sha(stagedBytes), baseVersion, journal)
        assertEquals(PushOutcome.Pushed(FileVersion(BackendKind.LOCAL, sha(stagedBytes))), outcome)
        assertArrayEquals(stagedBytes, directory.root.file(DATABASE).readBytes())
        assertEquals(sortedSetOf(DATABASE, "other.epub"), names(directory))
        assertEquals(listOf("NEW_WRITING", "NEW_WRITTEN", "OLD_RENAMED", "NEW_RENAMED", null), journal.phases)
        assertNull(journal.value)
    }

    @Test fun processDeathAtEveryStepLeavesAWholeDatabaseAndTheNextRoundFinishes() = runTest {
        val total = fixture().let { (directory, commit, staged) ->
            commit.push("tree", staged, sha(stagedBytes), baseVersion, Journal())
            directory.events
        }
        assertTrue(total >= 6)
        for (crashAt in 0 until total) {
            val (directory, commit, staged) = fixture()
            val journal = Journal()
            directory.fault = { index, _ -> if (index == crashAt) throw Crash() }
            try {
                commit.push("tree", staged, sha(stagedBytes), baseVersion, journal)
                fail("crash $crashAt was not reached")
            } catch (_: Crash) {}
            assertWhole(directory, "after crash $crashAt")
            assertTrue(journal.value != null)

            directory.fault = { _, _ -> }
            commit.finish("tree", journal)
            assertNull("crash $crashAt", journal.value)
            assertEquals("crash $crashAt", sortedSetOf(DATABASE, "other.epub"), names(directory))
            val committed = directory.root.file(DATABASE).readBytes()
            assertTrue("crash $crashAt", committed.contentEquals(base) || committed.contentEquals(stagedBytes))
            if (committed.contentEquals(base)) {
                // A rolled-back round is simply pushed again from the same base.
                assertTrue(commit.push("tree", staged, sha(stagedBytes), baseVersion, journal) is PushOutcome.Pushed)
            }
            assertArrayEquals(stagedBytes, directory.root.file(DATABASE).readBytes())
        }
    }

    @Test fun anIoFailureAtEveryStepIsCleanedUpAtOnceAndReportedAsUnknown() = runTest {
        val total = fixture().let { (directory, commit, staged) ->
            commit.push("tree", staged, sha(stagedBytes), baseVersion, Journal())
            directory.events
        }
        for (failAt in 0 until total) {
            val (directory, commit, staged) = fixture()
            val journal = Journal()
            directory.fault = { index, _ -> if (index == failAt) throw IOException() }
            val outcome = try { commit.push("tree", staged, sha(stagedBytes), baseVersion, journal) } catch (failure: SourceFailure) { failure }
            if (outcome is SourceFailure) {
                assertEquals("failure $failAt", StorageErrorKind.LOCAL_IO, outcome.error.kind)
                assertTrue("failure $failAt", outcome.transient)
            }
            assertNull("failure $failAt", journal.value)
            assertEquals("failure $failAt", sortedSetOf(DATABASE, "other.epub"), names(directory))
            val committed = directory.root.file(DATABASE).readBytes()
            assertTrue("failure $failAt", committed.contentEquals(base) || committed.contentEquals(stagedBytes))
        }
    }

    @Test fun aChangedOrLoggedSourceIsAConflictAndOnlyTheOwnTemporaryFileIsRemoved() = runTest {
        val changed = base.copyOf().also { it[10] = (it[10] + 1).toByte() }
        for (change in listOf<(File) -> Unit>(
            { it.file(DATABASE).writeBytes(changed) },
            { it.file("$DATABASE-wal").writeBytes(byteArrayOf(1)) },
            { it.file("$DATABASE-shm").createNewFile() },
            { it.file(DATABASE).delete() },
        )) {
            val (directory, commit, staged) = fixture()
            val before = directory.root.list()!!.associateWith { directory.root.file(it).readBytes().toList() }
            val journal = Journal()
            journal.onWrite = { if (it?.contains("NEW_WRITTEN") == true) change(directory.root) }
            assertEquals(PushOutcome.Conflict, commit.push("tree", staged, sha(stagedBytes), baseVersion, journal))
            assertNull(journal.value)
            assertFalse(directory.root.file(NEW).exists())
            assertFalse(directory.root.file(OLD).exists())
            assertFalse(directory.root.file(DATABASE).let { it.exists() && it.readBytes().contentEquals(stagedBytes) })
            assertEquals(before["other.epub"], directory.root.file("other.epub").readBytes().toList())
        }
        // An empty WAL is no change.
        val (directory, commit, staged) = fixture()
        directory.root.file("$DATABASE-wal").createNewFile()
        assertTrue(commit.push("tree", staged, sha(stagedBytes), baseVersion, Journal()) is PushOutcome.Pushed)
    }

    @Test fun filesUnderTheTemporaryNamesThatCannotBeProvenOwnAreNeverTouched() = runTest {
        val junk = byteArrayOf(9, 9, 9)
        // Without a journal any such file belongs to someone else.
        for (name in listOf(NEW, OLD)) {
            val (directory, commit, staged) = fixture()
            directory.root.file(name).writeBytes(junk)
            val failure = runCatching { commit.push("tree", staged, sha(stagedBytes), baseVersion, Journal()) }.exceptionOrNull() as SourceFailure
            assertEquals(StorageErrorKind.LEFTOVER_FILES, failure.error.kind)
            assertFalse(failure.transient)
            assertArrayEquals(junk, directory.root.file(name).readBytes())
            assertArrayEquals(base, directory.root.file(DATABASE).readBytes())
            assertEquals(0, directory.events)
        }
        // With a journal, a -new that is no prefix of the staged file and an -old that is not the base stay.
        val (directory, commit, staged) = fixture()
        val journal = Journal()
        directory.fault = { _, label -> if (label.startsWith("rename $DATABASE")) throw Crash() }
        runCatching { commit.push("tree", staged, sha(stagedBytes), baseVersion, journal) }
        directory.fault = { _, _ -> }
        directory.root.file(NEW).writeBytes(junk)
        val failure = runCatching { commit.finish("tree", journal) }.exceptionOrNull() as SourceFailure
        assertEquals(StorageErrorKind.LEFTOVER_FILES, failure.error.kind)
        assertArrayEquals(junk, directory.root.file(NEW).readBytes())
        assertTrue(journal.value != null)

        // Both temporary files unrecognisable while metadata.db is missing: nothing is renamed or deleted.
        directory.root.file(OLD).writeBytes(junk)
        directory.root.file(DATABASE).renameTo(directory.root.file("elsewhere"))
        val events = directory.events
        assertEquals(StorageErrorKind.LEFTOVER_FILES, (runCatching { commit.finish("tree", journal) }.exceptionOrNull() as SourceFailure).error.kind)
        assertEquals(events, directory.events)
        assertFalse(directory.root.file(DATABASE).exists())
    }

    @Test fun aPartialUploadIsRecognisedFromTheStagedFileOrItsHash() = runTest {
        val (directory, commit, staged) = fixture()
        val journal = Journal()
        var writes = 0
        directory.fault = { _, label -> if (label == "write" && ++writes == 2) throw Crash() }
        runCatching { commit.push("tree", staged, sha(stagedBytes), baseVersion, journal) }
        directory.fault = { _, _ -> }
        assertTrue(directory.root.file(NEW).length() in 1 until stagedBytes.size)
        commit.finish("tree", journal)
        assertEquals(sortedSetOf(DATABASE, "other.epub"), names(directory))
        assertArrayEquals(base, directory.root.file(DATABASE).readBytes())

        // Without the staged file only a complete upload can be recognised.
        val (complete, completeCommit, completeStaged) = fixture()
        val completeJournal = Journal()
        complete.fault = { _, label -> if (label.startsWith("rename $DATABASE")) throw Crash() }
        runCatching { completeCommit.push("tree", completeStaged, sha(stagedBytes), baseVersion, completeJournal) }
        complete.fault = { _, _ -> }
        completeStaged.delete()
        completeCommit.finish("tree", completeJournal)
        assertEquals(sortedSetOf(DATABASE, "other.epub"), names(complete))
    }

    @Test fun aRenameThatLandsOnAnotherNameIsUndone() = runTest {
        val (directory, commit, staged) = fixture()
        val journal = Journal()
        // Another writer takes the -old name between the check and the rename.
        directory.fault = { _, label -> if (label.startsWith("rename $DATABASE")) directory.root.file(OLD).writeBytes(byteArrayOf(7)) }
        val failure = runCatching { commit.push("tree", staged, sha(stagedBytes), baseVersion, journal) }.exceptionOrNull() as SourceFailure
        assertTrue(failure.transient)
        assertArrayEquals(base, directory.root.file(DATABASE).readBytes())
        assertArrayEquals(byteArrayOf(7), directory.root.file(OLD).readBytes())
        assertFalse(directory.root.file(NEW).exists())
    }

    @Test fun capabilityNeedsAWriteGrantAndEveryProviderOperation() = runTest {
        val (directory, commit, _) = fixture()
        assertNull(commit.capability("tree"))
        directory.databaseFlags = false
        assertEquals(WriteBlock.UNSUPPORTED_PROVIDER, commit.capability("tree"))
        directory.databaseFlags = true
        directory.writeGrant = false
        assertEquals(WriteBlock.READ_ONLY_GRANT, commit.capability("tree"))
        directory.writeGrant = true
        directory.denied = true
        assertEquals(WriteBlock.AUTHORIZATION_REQUIRED, commit.capability("tree"))
        directory.denied = false
        directory.root.file(DATABASE).delete()
        assertEquals(WriteBlock.SOURCE_UNAVAILABLE, commit.capability("tree"))

        val source = LocalLibrarySource(LocalSourceBackend(directory, temporary.newFolder(), SnapshotValidator { true },
            StandardTestDispatcher(testScheduler))) { null }
        assertEquals(WriteBlock.AUTHORIZATION_REQUIRED, source.writeCapability(LibraryLocation.Local("authority", "root")))
    }

    /** metadata.db is the whole base or staged database, or absent while a whole -old or -new remains. */
    private fun assertWhole(directory: Directory, message: String) {
        val database = directory.root.file(DATABASE)
        if (database.exists()) {
            val bytes = database.readBytes()
            assertTrue(message, bytes.contentEquals(base) || bytes.contentEquals(stagedBytes))
        } else {
            val old = directory.root.file(OLD)
            val new = directory.root.file(NEW)
            assertTrue(message, (old.exists() && old.readBytes().contentEquals(base)) || (new.exists() && new.readBytes().contentEquals(stagedBytes)))
        }
    }
}
