package io.github.chenxiex.calibrecloud.tasks.readstatus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.files.PrivateBookFiles
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.metadata.ReadStatusStaging
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.state.addLibrary
import io.github.chenxiex.calibrecloud.storage.SourcePolicies
import io.github.chenxiex.calibrecloud.storage.api.*
import io.github.chenxiex.calibrecloud.tasks.TestAuthorizations
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.background.StartupSync
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import io.github.chenxiex.calibrecloud.tasks.sync.LibrarySyncTaskHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.github.chenxiex.calibrecloud.library.LibraryQueryService
import io.github.chenxiex.calibrecloud.library.MetadataLibraryImports
import io.github.chenxiex.calibrecloud.library.ReadMarkAction
import io.github.chenxiex.calibrecloud.library.StateLibraryCopies
import io.github.chenxiex.calibrecloud.state.SearchHistoryStore
import io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenance
import io.github.chenxiex.calibrecloud.tasks.copies.CopyService
import io.github.chenxiex.calibrecloud.ui.LibraryContent
import io.github.chenxiex.calibrecloud.ui.LibraryCovers
import io.github.chenxiex.calibrecloud.ui.LibraryViewModel
import io.github.chenxiex.calibrecloud.ui.QueueLibraryBatch
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The production write handler, queue, coordinator, staging and library sync on real SQLite and private
 * files. The source is a fixture holding metadata.db in memory: it checks the push's version
 * precondition and the staged digest, and lets a test change the database as another writer would. A
 * rebuilt coordinator after a cancelled drain is a protocol restart, not a real process death.
 */
@RunWith(AndroidJUnit4::class)
class ReadStatusWriteTaskHandlerTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.cacheDir, "read-status-write-${UUID.randomUUID()}")
    private val databaseName = "read-status-write-${UUID.randomUUID()}.db"
    private lateinit var database: ApplicationStateDatabase
    private lateinit var state: ApplicationStateRepository
    private lateinit var metadata: MetadataRepository
    private lateinit var queue: DurableTaskQueue
    private lateinit var source: Source
    private var staging = ReadStatusStaging()
    private var offline = false
    private val read = CustomColumnId(1, "#read_status")
    private val favorite = CustomColumnId(2, "#favorite")

    @Before fun setUp() = runBlocking<Unit> {
        root.mkdirs()
        source = Source(sample())
        open()
        state.addLibrary(LibraryLocation.Local("test.documents", "root"), "content://test.documents/tree/root")
        val sync = requireNotNull(StartupSync(state, coordinator(), TestAuthorizations.of(state)).request(TaskOrigin.MANUAL_SYNC))
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(sync)!!.record.state)
        assertTrue(metadata.selectReadColumn(metadata.currentImport()!!, read))
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
        root.deleteRecursively()
    }

    @Test fun markReadThenUnreadWritesExplicitValuesAndTheSyncRunsNext() = runBlocking<Unit> {
        val write = submit(mapOf(guide() to true, lear() to true))
        assertEquals(mapOf(guide() to PendingRead.Pending(true), lear() to PendingRead.Pending(true)), pending())
        // A user request queued during the push is ahead of the sync submitted after it, yet must not run in between.
        val guide = guide()
        var cover: TaskId? = null
        source.beforePush = {
            cover = (queue.submit(TaskSubmission(TaskRequest.CoverLoad(guide), TaskOrigin.USER_DOWNLOAD)) as SubmissionResult.Created).taskId
            source.beforePush = null
        }
        val order = mutableListOf<TaskId>()
        coordinator(order).drain()
        val entry = queue.get(write)!!
        assertEquals(TaskState.Finished(TaskResult.Completed), entry.record.state)
        val sync = requireNotNull(entry.followUp)
        assertEquals(listOf(write, sync, cover!!), order.distinct())
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(sync)!!.record.state)
        assertEquals(listOf(1L to 1L, 4L to 1L, 5L to 1L), values(source.bytes, 1))
        assertEquals(true, isRead(guide()))
        assertEquals(true, isRead(lear()))
        assertEquals(emptyMap<BookKey, PendingRead>(), pending())
        assertEquals(1, source.pushes)
        assertEquals(emptyList<String>(), stagingFiles(write))

        val unread = submit(mapOf(guide() to false, hamlet() to false))
        assertEquals(true, isRead(guide()))
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(unread)!!.record.state)
        // "No" is an explicit 0 row, never an absent value.
        assertEquals(listOf(1L to 0L, 4L to 0L, 5L to 1L), values(source.bytes, 1))
        assertEquals(false, isRead(guide()))
        assertEquals(2, source.pushes)
    }

    @Test fun offlineWriteWaitsWithoutChangingTheImport() = runBlocking<Unit> {
        offline = true
        val before = source.bytes
        val write = submit(mapOf(guide() to true))
        coordinator().drain()
        assertEquals(TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))), queue.get(write)!!.record.state)
        assertArrayEquals(before, source.bytes)
        assertEquals(false, isRead(guide()))
        assertEquals(mapOf(guide() to PendingRead.Pending(true)), pending())

        offline = false
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(write)!!.record.state)
        assertEquals(true, isRead(guide()))
    }

    @Test fun aConflictFetchesTheLatestDatabaseAndKeepsTheOtherChange() = runBlocking<Unit> {
        val write = submit(mapOf(guide() to true))
        var changed = false
        source.beforePush = {
            // Another writer sets the favorite column between this round's snapshot and its push.
            if (!changed) { changed = true; source.bytes = mutate(source.bytes, "INSERT OR REPLACE INTO custom_column_2(book,value) VALUES(5,1)") }
        }
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(write)!!.record.state)
        assertEquals(2, source.pushes)
        assertEquals(1, source.conflicts)
        assertEquals(listOf(1L to 1L, 4L to 1L, 5L to 0L), values(source.bytes, 1))
        assertEquals(listOf(1L to 1L, 4L to 0L, 5L to 1L), values(source.bytes, 2))
    }

    @Test fun threeRetriesThenFailureAndAManualRetryStartsAgain() = runBlocking<Unit> {
        val before = source.bytes
        val write = submit(mapOf(guide() to true))
        source.alwaysConflict = true
        coordinator().drain()
        val failed = queue.get(write)!!.record.state
        assertEquals(TaskState.Finished(TaskResult.Failed(StageFailure(TaskStage.WRITE_COMMIT,
            TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT))))), failed)
        assertEquals(4, source.pushes)
        assertArrayEquals(before, source.bytes)
        assertEquals(mapOf(guide() to PendingRead.Failed(true, TaskError.Source(StorageError(StorageErrorKind.VERSION_CONFLICT)))), pending())
        assertEquals(emptyList<String>(), stagingFiles(write))

        source.alwaysConflict = false
        assertTrue(queue.control(write, TaskControl.RETRY))
        assertEquals(mapOf(guide() to PendingRead.Pending(true)), pending())
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(write)!!.record.state)
        assertEquals(5, source.pushes)
        assertEquals(true, isRead(guide()))
    }

    @Test fun aRestartAfterThePushRunsARoundWithoutPushingAgain() = runBlocking<Unit> {
        val write = submit(mapOf(guide() to true, lear() to true))
        source.afterPush = { source.afterPush = null; throw CancellationException("process ends after the push") }
        try { coordinator().drain(); fail("The drain should have ended with the process") } catch (_: CancellationException) {}
        assertTrue(queue.get(write)!!.record.state is TaskState.Running)
        assertEquals(1, source.pushes)

        database.close()
        open()
        coordinator().drain()
        val entry = queue.get(write)!!
        assertEquals(TaskState.Finished(TaskResult.Completed), entry.record.state)
        assertEquals(1, source.pushes)
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(entry.followUp!!)!!.record.state)
        assertEquals(true, isRead(lear()))
    }

    @Test fun aSwitchedReadColumnFailsTheOlderWrite() = runBlocking<Unit> {
        val before = source.bytes
        val write = submit(mapOf(guide() to true))
        assertTrue(metadata.selectReadColumn(metadata.currentImport()!!, favorite))
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Failed(StageFailure(TaskStage.WRITE_PREPARE, TaskError.InvalidColumn))),
            queue.get(write)!!.record.state)
        assertEquals(0, source.pushes)
        assertArrayEquals(before, source.bytes)
    }

    @Test fun booksThatNoLongerMatchAreReportedAndTheOthersWritten() = runBlocking<Unit> {
        val hamlet = hamlet()
        val write = submit(mapOf(guide() to true, hamlet to false))
        source.bytes = mutate(source.bytes, "UPDATE books SET uuid='${UUID.randomUUID()}' WHERE id=4")
        coordinator().drain()
        val entry = queue.get(write)!!
        assertEquals(TaskState.Finished(TaskResult.CompletedWithBookFailures(FrozenSet(listOf(
            BookFailure(hamlet, TaskError.BookIdentityChanged(hamlet)))))), entry.record.state)
        assertEquals(listOf(1L to 1L, 4L to 1L, 5L to 0L), values(source.bytes, 1))
        assertNotNull(entry.followUp)
    }

    @Test fun insufficientSpaceFailsWithoutAPush() = runBlocking<Unit> {
        staging = ReadStatusStaging(availableBytes = { 0 })
        val write = submit(mapOf(guide() to true))
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Failed(StageFailure(TaskStage.WRITE_PREPARE,
            TaskError.Source(StorageError(StorageErrorKind.INSUFFICIENT_SPACE))))), queue.get(write)!!.record.state)
        assertEquals(0, source.pushes)
        assertEquals(emptyList<String>(), stagingFiles(write))
    }

    @Test fun clicksMergedBeforeTheStartAreWrittenInOnePush() = runBlocking<Unit> {
        val write = submit(mapOf(guide() to true))
        assertEquals(write, submit(mapOf(lear() to false)))
        assertEquals(write, submit(mapOf(guide() to false)))
        coordinator().drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(write)!!.record.state)
        assertEquals(1, source.pushes)
        assertEquals(listOf(1L to 0L, 4L to 1L, 5L to 0L), values(source.bytes, 1))
    }

    @Test fun alreadySatisfiedTargetsCompleteWithoutAPushAndStillSync() = runBlocking<Unit> {
        val write = submit(mapOf(hamlet() to true, lear() to false))
        coordinator().drain()
        val entry = queue.get(write)!!
        assertEquals(TaskState.Finished(TaskResult.Completed), entry.record.state)
        assertEquals(0, source.pushes)
        assertEquals(TaskState.Finished(TaskResult.Completed), queue.get(entry.followUp!!)!!.record.state)
    }

    /**
     * R13, R16, R26 through the production library page model and batch: marking from a selection keeps the
     * imported state on the page, shows the pending target, and only the sync after the push changes it.
     */
    @Test fun aMarkFromTheLibraryPageShowsThePendingTargetUntilTheSyncAfterThePush() = runBlocking<Unit> {
        val coordinator = coordinator()
        val sources = LibrarySources { source }
        val batch = QueueLibraryBatch(CopyService(state, metadata, coordinator, queue), coordinator,
            CacheMaintenance(database, state, queue, root, Dispatchers.IO), state, metadata, sources, queue,
            ReadStatusService(state, metadata, sources, coordinator))
        val queries = LibraryQueryService(MetadataLibraryImports(metadata), StateLibraryCopies(state), Dispatchers.Default)
        val model = withContext(Dispatchers.Main) {
            LibraryViewModel(state::current, queries, NoCovers, queue.events, NoHistory, batch).apply { setVisible(true); onMeasured(20) }
        }
        suspend fun <T> onMain(read: LibraryViewModel.() -> T) = withContext(Dispatchers.Main) { model.read() }
        suspend fun until(condition: LibraryViewModel.() -> Boolean) = withTimeout(10_000) { while (!onMain(condition)) delay(20) }
        suspend fun readShown(book: BookKey) = onMain { (content as LibraryContent.Books).rows.single { it.key == book }.read }
        val guide = guide()
        until { (content as? LibraryContent.Books)?.rows?.any { it.key == guide } == true }
        onMain { toggleBook(guide) }
        until { readMark?.action == ReadMarkAction.MARK_READ && readMark?.blocked == null }
        onMain { markSelection(ReadMarkAction.MARK_READ) }
        until { selected == null && pendingReads[guide] == PendingRead.Pending(true) }
        assertEquals(false, readShown(guide))
        // Selecting it again offers the undo, before anything was written.
        onMain { toggleBook(guide) }
        until { readMark?.action == ReadMarkAction.MARK_UNREAD }
        onMain { finishSelection() }

        var pushedButNotSynced: Pair<Boolean?, PendingRead?>? = null
        source.afterPush = { pushedButNotSynced = runBlocking { isRead(guide) to batch.pendingReads()[guide] } }
        coordinator.drain()
        // Pushed: the import is unchanged and the book still pending until its sync ends.
        assertEquals(false to PendingRead.Pending(true), pushedButNotSynced)
        until { pendingReads.isEmpty() }
        until { (content as? LibraryContent.Books)?.rows?.single { it.key == guide }?.read == true }
        assertEquals(listOf(1L to 1L, 4L to 1L, 5L to 0L), values(source.bytes, 1))
        withContext(Dispatchers.Main) { model.setVisible(false) }
    }

    private object NoCovers : LibraryCovers {
        override suspend fun read(book: BookKey): android.graphics.Bitmap? = null
        override suspend fun request(books: List<BookKey>, selectionToken: UUID): TaskId? = null
        override fun changes(task: TaskId): kotlinx.coroutines.flow.Flow<TaskState> = kotlinx.coroutines.flow.emptyFlow()
        override suspend fun wake() {}
    }

    private object NoHistory : SearchHistoryStore {
        override suspend fun list(libraryId: io.github.chenxiex.calibrecloud.model.LibraryId) = emptyList<String>()
        override suspend fun record(libraryId: io.github.chenxiex.calibrecloud.model.LibraryId, query: String) {}
        override suspend fun clear(libraryId: io.github.chenxiex.calibrecloud.model.LibraryId) {}
    }

    private fun open() {
        database = ApplicationStateDatabase(context, databaseName)
        state = ApplicationStateRepository(database, PrivateBookFiles(root), Dispatchers.IO)
        metadata = MetadataRepository(database, state, File(root, "metadata"), Dispatchers.IO)
        queue = DurableTaskQueue(database, Dispatchers.IO)
    }

    private fun coordinator(order: MutableList<TaskId>? = null): TaskCoordinator {
        lateinit var coordinator: TaskCoordinator
        val authorizations = TestAuthorizations.of(state)
        val sources = LibrarySources { source }
        val sync = LibrarySyncTaskHandler(state, sources, authorizations, metadata, Dispatchers.IO)
        val write = ReadStatusWriteTaskHandler(state, metadata, queue, sources, root, Dispatchers.IO, staging,
            requestSync = { origin -> StartupSync(state, coordinator, authorizations).request(origin) }, log = {})
        val cover = object : TaskHandler {
            override fun supports(request: TaskRequest) = request is TaskRequest.CoverLoad
            override fun controls(stage: TaskStage) = TaskControls(true, true, false, false)
            override suspend fun recover(entry: QueueEntry, execution: TaskExecution) = RecoveryDecision(TaskStage.COVER_TRANSFER, null)
            override suspend fun execute(entry: QueueEntry, execution: TaskExecution) = StageOutcome.Complete()
        }
        val handlers = listOf(sync, write, cover).map { handler ->
            if (order == null) handler else object : TaskHandler by handler {
                override suspend fun execute(entry: QueueEntry, execution: TaskExecution): StageOutcome {
                    order.add(entry.record.id)
                    return handler.execute(entry, execution)
                }
            }
        }
        coordinator = TaskCoordinator(queue, handlers, conditions = { if (offline) setOf(WaitingReason.NETWORK) else emptySet() })
        return coordinator
    }

    private suspend fun submit(changes: Map<BookKey, Boolean>): TaskId {
        val service = ReadStatusService(state, metadata, LibrarySources { source }, coordinator())
        val token = state.current()!!.token
        return changes.entries.groupBy({ it.value }, { it.key }).entries.map { (target, books) ->
            when (val result = service.submit(books, target, token)) {
                is SubmissionResult.Created -> result.taskId
                is SubmissionResult.Reused -> result.taskId
                else -> throw AssertionError("Rejected: $result")
            }
        }.distinct().single()
    }

    private suspend fun pending() = queue.pendingReadStatus(library(), read)
    private suspend fun library() = state.current()!!.identity!!.id
    private suspend fun guide() = BookKey(library(), 1, UUID.fromString("ed5e903d-83cb-418c-bbd3-90de61ff46c9")) // absent value
    private suspend fun hamlet() = BookKey(library(), 4, UUID.fromString("8a4e0ccb-7293-4c3a-bc58-3b130d950359")) // yes
    private suspend fun lear() = BookKey(library(), 5, UUID.fromString("c1e49ea8-507e-4f67-ad93-6249aa9d6d68")) // no
    private suspend fun isRead(book: BookKey): Boolean? = metadata.currentImport()!!.let { imported ->
        imported.isRead(imported.metadata.books.single { it.sourceId == book.sourceId })
    }
    private fun stagingFiles(task: TaskId) = File(root, "write-staging/${task.value}").list().orEmpty().toList()

    /** The repository's Calibre 9.14 sample plus a second bool column built from Calibre's own DDL. */
    private fun sample(): ByteArray {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("calibre-sample/metadata.db").use { it.readBytes() }
        val ddl = "SELECT sql FROM sqlite_master WHERE tbl_name='custom_column_1' AND sql IS NOT NULL ORDER BY type<>'table'"
        return mutate(bytes) { db ->
            db.execSQL("INSERT INTO custom_columns(id,label,name,datatype,mark_for_delete,editable,display,is_multiple,normalized) " +
                "SELECT 2,'favorite','Favorite',datatype,mark_for_delete,editable,display,is_multiple,normalized FROM custom_columns WHERE id=1")
            db.rawQuery(ddl, null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                .forEach { db.execSQL(it.replace("custom_column_1", "custom_column_2")) }
            db.execSQL("INSERT INTO custom_column_2(book,value) VALUES(1,1),(4,0)")
        }
    }

    private fun mutate(bytes: ByteArray, sql: String) = mutate(bytes) { it.execSQL(sql) }

    /** books_update_trg needs title_sort to prepare any UPDATE of books; an identity stands in for Calibre's. */
    private fun mutate(bytes: ByteArray, block: (SQLiteDatabase) -> Unit): ByteArray {
        val file = File(root, "mutate-${UUID.randomUUID()}.db").apply { writeBytes(bytes) }
        try {
            SQLiteDatabase.openDatabase(file, SQLiteDatabase.OpenParams.Builder()
                .setOpenFlags(SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).setJournalMode("DELETE").build())
                .use { db -> db.setCustomScalarFunction("title_sort") { it }; block(db) }
            return file.readBytes()
        } finally { file.delete() }
    }

    private fun values(bytes: ByteArray, column: Int): List<Pair<Long, Long>> {
        val file = File(root, "read-${UUID.randomUUID()}.db").apply { writeBytes(bytes) }
        try {
            return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT book,value FROM custom_column_$column ORDER BY book", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0) to c.getLong(1)) }
                }
            }
        } finally { file.delete() }
    }

    /** metadata.db held in memory; its version is the content's SHA-256, like a local library's. */
    private inner class Source(@Volatile var bytes: ByteArray) : LibrarySource by SourcePolicies.sources.of(BackendKind.LOCAL) {
        var pushes = 0
        var conflicts = 0
        var alwaysConflict = false
        var beforePush: (suspend () -> Unit)? = null
        var afterPush: (() -> Unit)? = null
        private fun version(content: ByteArray) = FileVersion(BackendKind.LOCAL, sha256(content))

        override suspend fun writeCapability(location: LibraryLocation): WriteBlock? = null

        override suspend fun acquireSnapshot(location: LibraryLocation, candidateId: UUID, unchangedVersion: FileVersion?,
            control: suspend () -> Unit): SourceSnapshot {
            control()
            val content = bytes
            val directory = File(root, "snapshots/${candidateId}").apply { mkdirs() }
            val file = File(directory, "${UUID.randomUUID()}.db").apply { writeBytes(content) }
            return SourceSnapshot(file, version(content))
        }

        override suspend fun pushDatabase(location: LibraryLocation, staged: File, stagedSha256: String, base: FileVersion,
            journal: PushJournal): PushOutcome {
            pushes++
            beforePush?.invoke()
            if (alwaysConflict || base != version(bytes)) { conflicts++; return PushOutcome.Conflict }
            val content = staged.readBytes()
            assertEquals(stagedSha256, sha256(content))
            bytes = content
            afterPush?.invoke()
            return PushOutcome.Pushed(version(content))
        }

        override suspend fun finishPendingPush(location: LibraryLocation, journal: PushJournal) {}
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
