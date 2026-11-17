package io.github.chenxiex.calibrecloud.tasks.background

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.chenxiex.calibrecloud.BuildConfig
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.state.ApplicationStateDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real WorkManager recovery without a configured library, source tasks, or production files. */
@RunWith(AndroidJUnit4::class)
class QueueWorkerCleanupPlatformTest {
    @Test
    fun failedLocalCleanupUsesIndependentRetryChainAndLeavesImmediateWakeupsRunnable() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BuildConfig.DEBUG)
        assertTrue(context.packageName.endsWith(".debug"))
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        assumeTrue("Requires a fresh unconfigured debug app", dependencies.state.current() == null &&
            dependencies.state.libraries().isEmpty() && dependencies.taskQueue.list().isEmpty() && !dependencies.maintenance.cleanupPending())
        val manager = WorkManager.getInstance(context)
        val previous = manager.getWorkInfosByTagFlow(BackgroundTasks.QUEUE_TAG).first()
        assumeTrue("Do not control pre-existing active workers", previous.all { it.state.isFinished })
        val previousIds = previous.mapTo(mutableSetOf()) { it.id }
        val previousWake = manager.getWorkInfosForUniqueWorkFlow(BackgroundTasks.WAKE_NAME).first().mapTo(mutableSetOf()) { it.id }
        val previousRetry = manager.getWorkInfosForUniqueWorkFlow(BackgroundTasks.PLATFORM_NAME).first().mapTo(mutableSetOf()) { it.id }
        val cleanup = UUID.randomUUID().toString()
        val relative = "book-staging/cleanup-worker-$cleanup/linked"
        val fixture = File(context.filesDir, "book-staging/cleanup-worker-$cleanup")
        val link = File(context.filesDir, relative)
        val retained = File(fixture, "retained/sentinel.txt")
        try {
            withContext(Dispatchers.IO) {
                check(retained.parentFile!!.mkdirs())
                retained.writeText("The cleaner must not follow the symbolic link")
                Files.createSymbolicLink(link.toPath(), retained.parentFile!!.toPath())
                val journal = JSONObject().put("kind", "COPIES")
                    .put("paths", JSONArray(listOf(relative))).put("tasks", JSONArray())
                    .put("retire", JSONArray()).put("published", JSONArray()).put("libraries", JSONArray())
                dependencies.database.writableDatabase.insertOrThrow("cache_cleanup", null, ContentValues().apply {
                    put("cleanup_id", cleanup)
                    put("payload", journal.toString())
                })
            }

            dependencies.backgroundTasks.wake()
            val first = awaitNewWake(manager, previousWake)
            assertTrue(hasJournal(dependencies.database, cleanup))
            assertTrue(retained.isFile)
            val retries = withTimeout(60_000) {
                manager.getWorkInfosForUniqueWorkFlow(BackgroundTasks.PLATFORM_NAME).first { work ->
                    work.any { it.id !in previousRetry }
                }
            }.filter { it.id !in previousRetry }
            assertTrue(retries.isNotEmpty())
            assertTrue(retries.none { it.id == first.id || it.state.isFinished })

            val beforeSecond = manager.getWorkInfosForUniqueWorkFlow(BackgroundTasks.WAKE_NAME).first().mapTo(mutableSetOf()) { it.id }
            dependencies.backgroundTasks.wake()
            val second = awaitNewWake(manager, beforeSecond)
            assertNotEquals(first.id, second.id)
            assertTrue(hasJournal(dependencies.database, cleanup))
            assertTrue(retained.isFile)

            // A retry may already be executing. Mutate the fixture while no worker owns the
            // executor, so it cannot complete the journal between unlink and replacement.
            withContext(Dispatchers.IO) {
                dependencies.taskQueue.executionLock.withLock {
                    Files.delete(link.toPath())
                    check(link.mkdir())
                    File(link, "fixture.part").writeText("discarded bytes")
                }
            }
            val beforeRecovery = manager.getWorkInfosForUniqueWorkFlow(BackgroundTasks.WAKE_NAME).first().mapTo(mutableSetOf()) { it.id }
            dependencies.backgroundTasks.wake()
            awaitNewWake(manager, beforeRecovery)
            withTimeout(60_000) {
                manager.getWorkInfosByTagFlow(BackgroundTasks.QUEUE_TAG).first { !hasJournal(dependencies.database, cleanup) }
            }
            assertFalse(link.exists())
            assertTrue(retained.isFile)
            assertNull(dependencies.state.current())
            assertTrue(dependencies.taskQueue.list().isEmpty())
        } finally {
            // Cancel only work created during this isolated test; leave earlier work/configuration alone.
            val created = manager.getWorkInfosByTagFlow(BackgroundTasks.QUEUE_TAG).first().filter { it.id !in previousIds }
            withContext(Dispatchers.IO) {
                created.forEach { manager.cancelWorkById(it.id).result.get(10, TimeUnit.SECONDS) }
                dependencies.database.writableDatabase.delete("cache_cleanup", "cleanup_id = ?", arrayOf(cleanup))
                if (Files.isSymbolicLink(link.toPath())) Files.deleteIfExists(link.toPath())
                fixture.deleteRecursively()
            }
        }
    }

    private suspend fun awaitNewWake(manager: WorkManager, previous: Set<UUID>): WorkInfo = withTimeout(60_000) {
        manager.getWorkInfosForUniqueWorkFlow(BackgroundTasks.WAKE_NAME).first { work ->
            work.any { it.id !in previous && it.state == WorkInfo.State.SUCCEEDED }
        }.first { it.id !in previous && it.state == WorkInfo.State.SUCCEEDED }
    }

    private suspend fun hasJournal(database: ApplicationStateDatabase, cleanup: String): Boolean = withContext(Dispatchers.IO) {
        database.readableDatabase.rawQuery("SELECT 1 FROM cache_cleanup WHERE cleanup_id = ?", arrayOf(cleanup)).use { it.moveToFirst() }
    }
}
