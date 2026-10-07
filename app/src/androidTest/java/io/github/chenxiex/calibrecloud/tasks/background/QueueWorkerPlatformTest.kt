package io.github.chenxiex.calibrecloud.tasks.background

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import io.github.chenxiex.calibrecloud.BuildConfig
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Production Application/WorkManager integration, restricted to a fresh unconfigured debug app. */
@RunWith(AndroidJUnit4::class)
class QueueWorkerPlatformTest {
    @Test
    fun productionWakeupsRunTwoOneTimeWorkersWithoutCreatingSourceTasks() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BuildConfig.DEBUG)
        assertTrue(context.packageName.endsWith(".debug"))
        val application = context.applicationContext as CalibreCloudApplication
        val dependencies = application.dependencies
        // Never dispatch or cancel a user's configured source tasks. Install a fresh independent
        // debug APK for this check; a configured environment is explicitly skipped.
        assumeTrue("Requires a fresh unconfigured debug app", dependencies.state.current() == null && dependencies.taskQueue.list().isEmpty())
        assertNull(dependencies.state.current())
        assertFalse(dependencies.backgroundTasks.startupEnabled())
        assertTrue(context.applicationContext is Configuration.Provider)

        // No test initializer/factory is installed: getInstance uses the Application's on-demand
        // Configuration.Provider and reflection constructs the real production QueueWorker.
        val manager = WorkManager.getInstance(context)
        assertEquals(Log.WARN, manager.configuration.minimumLoggingLevel)
        val previous = tagged(manager).map { it.id }.toSet()
        val allPrevious = all(manager).map { it.id }.toSet()
        try {
            dependencies.backgroundTasks.wake()
            dependencies.backgroundTasks.wake()
            val finished = awaitTwoSucceeded(manager, previous)
            assertEquals(2, finished.size)
            assertTrue(finished.all { it.state == WorkInfo.State.SUCCEEDED })
            // WorkerWrapper increments the persisted count when changing ENQUEUED to RUNNING;
            // WorkInfo therefore reports 1 after a successful first execution, not the worker's
            // initial WorkerParameters.runAttemptCount (0).
            assertTrue("Both workers must succeed on their first execution", finished.all { it.runAttemptCount == 1 })
            assertTrue(finished.all { it.periodicityInfo == null })
            assertTrue(finished.all { QueueWorker::class.java.name in it.tags })
            val chain = withContext(Dispatchers.IO) {
                manager.getWorkInfosForUniqueWork(BackgroundTasks.WAKE_NAME).get(10, TimeUnit.SECONDS)
            }
            assertTrue(finished.all { worker -> chain.any { it.id == worker.id } })
            val newlyCreated = all(manager).filter { it.id !in allPrevious }
            assertEquals(finished.map { it.id }.toSet(), newlyCreated.map { it.id }.toSet())
            assertTrue(newlyCreated.all { it.periodicityInfo == null })
            assertNull(dependencies.state.current())
            assertTrue(dependencies.taskQueue.list().isEmpty())
            assertFalse(dependencies.backgroundTasks.startupEnabled())
            assertFalse(dependencies.backgroundTasks.platformWaiting())
        } finally {
            // Only this production queue's tag, without pruning unrelated work or changing source state.
            withContext(Dispatchers.IO) {
                manager.cancelAllWorkByTag(BackgroundTasks.QUEUE_TAG).result.get(10, TimeUnit.SECONDS)
            }
        }
    }

    private suspend fun awaitTwoSucceeded(manager: WorkManager, previous: Set<UUID>): List<WorkInfo> = withTimeout(30_000) {
        var workers = tagged(manager).filter { it.id !in previous }
        while (workers.size < 2 || workers.any { !it.state.isFinished }) {
            delay(100)
            workers = tagged(manager).filter { it.id !in previous }
        }
        workers
    }

    private suspend fun tagged(manager: WorkManager): List<WorkInfo> = withContext(Dispatchers.IO) {
        manager.getWorkInfosByTag(BackgroundTasks.QUEUE_TAG).get(10, TimeUnit.SECONDS)
    }

    private suspend fun all(manager: WorkManager): List<WorkInfo> = withContext(Dispatchers.IO) {
        manager.getWorkInfos(WorkQuery.Builder.fromStates(WorkInfo.State.values().toList()).build()).get(10, TimeUnit.SECONDS)
    }
}
