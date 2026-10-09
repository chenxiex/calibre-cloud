package io.github.chenxiex.calibrecloud.tasks

import io.github.chenxiex.calibrecloud.state.addLibrary
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.metadata.CalibreFixture
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.local.LocalDocument
import io.github.chenxiex.calibrecloud.storage.local.LocalDocumentAccess
import io.github.chenxiex.calibrecloud.storage.local.LocalLibrarySource
import io.github.chenxiex.calibrecloud.storage.local.LocalSourceBackend
import io.github.chenxiex.calibrecloud.storage.local.SnapshotValidator
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.sync.LibrarySyncTaskHandler
import io.github.chenxiex.calibrecloud.storage.api.LibrarySources
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real persisted scheduling and production handler; injected documents never touch a real library. */
@RunWith(AndroidJUnit4::class)
class LocalLibrarySyncTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var queue: DurableTaskQueue
    private lateinit var snapshots: File
    private lateinit var documents: Documents
    private lateinit var importer: MetadataRepository

    @Before
    fun setUp() = runBlocking<Unit> {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseName = "local-snapshot-task-${UUID.randomUUID()}.db"
        snapshots = File(context.cacheDir, "snapshot-task-${UUID.randomUUID()}")
        snapshots.mkdirs()
        documents = Documents(CalibreFixture.create(File(snapshots, "fixture.db")).readBytes())
        reopen()
        select("first")
    }

    @After
    fun tearDown() {
        documents.release.countDown()
        database.close()
        context.deleteDatabase(databaseName)
        snapshots.deleteRecursively()
    }

    @Test
    fun completeSnapshotActivatesImportedLibraryAndEmitsCacheChanged() = runBlocking<Unit> {
        val events = mutableListOf<TaskEvent>()
        val observation = launch(start = CoroutineStart.UNDISPATCHED) { queue.events.collect { events.add(it) } }
        val task = submit()
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertNotNull(state.current()!!.identity)
        assertEquals(2, documents.reads.get())
        assertArrayEquals(documents.bytes, snapshot(task).readBytes())
        observation.cancel()
        observation.join()
        assertTrue(events.any { it is TaskEvent.CacheChanged })
        assertEquals(state.current()!!.identity, importer.currentImport()!!.identity)
    }

    @Test
    fun cancelDuringReadClosesStreamAndFreshContextCanValidateAgain() = runBlocking<Unit> {
        documents.blockNextRead = true
        val oldContext = candidate()
        val task = submit(oldContext)
        val driver = launch(Dispatchers.Default) { coordinator().drain() }
        awaitRead()
        assertTrue(queue.control(task, TaskControl.CANCEL))
        documents.release.countDown()
        withTimeout(10_000) { driver.join() }
        assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(task)!!.record.state)
        assertTrue(documents.closes.get() > 0)
        assertTrue(File(snapshots, task.value.toString()).listFiles().orEmpty().isEmpty())
        assertTrue(queue.submit(submission(oldContext)) is SubmissionResult.Rejected)
        val newContext = candidate()
        assertNotEquals(oldContext.selectionToken, newContext.selectionToken)
        val replacement = submit(newContext)
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(replacement)!!.record.state)
        assertNotNull(state.current()!!.identity)
    }

    @Test
    fun reselectDuringReadCancelsOldResultAndKeepsNewSelection() = runBlocking<Unit> {
        documents.blockNextRead = true
        val task = submit()
        val driver = launch(Dispatchers.Default) { coordinator().drain() }
        awaitRead()
        select("second")
        val replacement = state.current()!!
        documents.release.countDown()
        withTimeout(10_000) { driver.join() }
        assertEquals(replacement, state.current())
        assertNull(replacement.identity)
        assertEquals(TaskState.Finished(TaskResult.Cancelled(CommitState.NotCommitted)), queue.get(task)!!.record.state)
        assertTrue(File(snapshots, task.value.toString()).listFiles().orEmpty().isEmpty())
        val newTask = submit()
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(newTask)!!.record.state)
    }

    @Test
    fun reopenedRunningTaskReacquiresSourceInsteadOfTrustingCheckpoint() = runBlocking<Unit> {
        val task = submit()
        assertEquals(task, queue.claim(0, { emptySet() }, { true })!!.record.id)
        val staleGeneration = UUID.randomUUID()
        queue.update(task) { it.copy(checkpoint = RecoveryCheckpoint(staleGeneration, FileVersion(BackendKind.LOCAL, "old"))) }
        documents.bytes = CalibreFixture.create(File(snapshots, "changed-fixture.db")).readBytes()
        database.close()
        reopen()
        coordinator().drain()
        assertEquals(2, documents.reads.get())
        assertArrayEquals(documents.bytes, snapshot(task).readBytes())
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(task)!!.record.state)
        assertNull(queue.get(task)!!.checkpoint)
        assertNotNull(state.current()!!.identity)
    }

    private fun reopen() {
        database = ApplicationStateDatabase(context, databaseName)
        state = ApplicationStateRepository(database, PrivateBookFiles(context.filesDir), Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
        importer = MetadataRepository(database, state, File(snapshots, "imports"), Dispatchers.IO)
    }

    private suspend fun select(root: String) {
        state.addLibrary(LibraryLocation.Local("test.documents", root), "content://test.documents/tree/$root")
    }

    private suspend fun candidate(): CandidateContext {
        val selected = state.current()!!
        return CandidateContext(selected.token, BackendKind.LOCAL, selected.token)
    }

    private fun submission(candidate: CandidateContext) = TaskSubmission(
        TaskRequest.CandidateConfiguration(candidate, TaskRequest.CandidateConfiguration.LIBRARY_SYNC), TaskOrigin.MANUAL_SYNC,
    )

    private suspend fun submit(context: CandidateContext? = null): TaskId =
        (queue.submit(submission(context ?: candidate())) as SubmissionResult.Created).taskId

    private fun coordinator(): TaskCoordinator {
        val source = LocalLibrarySource(LocalSourceBackend(documents, snapshots, SnapshotValidator { it.length() > 0 }, Dispatchers.IO)) { location ->
            state.accessKey(location)
        }
        return TaskCoordinator(queue, listOf(LibrarySyncTaskHandler(
            state, LibrarySources { source }, TestAuthorizations.of(state), importer, Dispatchers.IO,
        )))
    }

    private fun snapshot(task: TaskId): File = File(snapshots, task.value.toString()).listFiles().orEmpty().single { it.extension == "db" }

    private fun awaitRead() {
        assertTrue("Source read did not start", documents.started.await(10, TimeUnit.SECONDS))
    }

    private class Documents(@Volatile var bytes: ByteArray) : LocalDocumentAccess {
        @Volatile var blockNextRead = false
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        override fun root(treeUri: String) = LocalDocument("root", "library", true, true)
        override fun children(treeUri: String, parentId: String) = listOf(LocalDocument("metadata", "metadata.db", false, true))
        override fun isWithinRoot(treeUri: String, documentId: String) = documentId in setOf("root", "metadata")
        override fun openRead(treeUri: String, documentId: String): InputStream {
            reads.incrementAndGet()
            val shouldBlock = blockNextRead
            blockNextRead = false
            return object : ByteArrayInputStream(bytes.copyOf()) {
                private var firstRead = true
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (firstRead && shouldBlock) {
                        firstRead = false
                        started.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Test did not release source read" }
                    }
                    return super.read(buffer, offset, length)
                }
                override fun close() {
                    closes.incrementAndGet()
                    super.close()
                }
            }
        }
    }
}
