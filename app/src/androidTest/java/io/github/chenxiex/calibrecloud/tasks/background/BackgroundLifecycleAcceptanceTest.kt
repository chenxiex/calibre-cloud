package io.github.chenxiex.calibrecloud.tasks.background

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.ApplicationDependencies
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.ui.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real Activity lifecycle and production worker; not a physical reboot/process-death check. */
@RunWith(AndroidJUnit4::class)
class BackgroundLifecycleAcceptanceTest {
    @Test
    fun firstMainOpeningSyncsOnceAcrossClosedActivityRecreationAndBackgroundReturn() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Requires the dedicated step 09 read-only acceptance setup",
            InstrumentationRegistry.getArguments().getString("step09ReadOnly") == "true")
        val context = instrumentation.targetContext
        assertEquals("io.github.chenxiex.calibrecloud.debug", context.packageName)
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        dependencies.taskQueue.executionLock.withLock {
            val selected = requireNotNull(dependencies.state.current())
            assertTrue("Only the authorized SAF fixture is eligible", selected.location is LibraryLocation.Local)
            val tree = requireNotNull(dependencies.state.localTreeUri())
            assertTrue("Only the step 09 dedicated library-a tree is eligible",
                Uri.decode(tree.toString()).endsWith("calibre-step09-acceptance-20261007/library-a"))
            val imported = requireNotNull(dependencies.metadata.currentImport())
            assertEquals(selected.identity, imported.identity)
            assertTrue("Complete other tasks before lifecycle acceptance",
                dependencies.taskQueue.list().all { it.record.state is TaskState.Finished })
        }
        val originalSetting = dependencies.backgroundTasks.startupEnabled()
        val before = startupCount(dependencies)
        try {
            dependencies.backgroundTasks.setStartupEnabled(true)
            // Run this test alone in a fresh instrumentation process which has not opened Main.
            // Closing the Activity before awaiting completion leaves execution to the worker.
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(30_000) {
                    while (startupCount(dependencies) == before) delay(100)
                }
                assertEquals(before + 1, startupCount(dependencies))
            }
            val startup = dependencies.taskQueue.list().last { it.record.originalOrigin == TaskOrigin.STARTUP_SYNC }
            withTimeout(600_000) {
                while (requireNotNull(dependencies.taskQueue.get(startup.record.id)).record.state !is TaskState.Finished) delay(100)
            }
            assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(startup.record.id)?.record?.state)

            // Wait for the first sync to complete before reopening: deduplication of unfinished
            // work must not hide an incorrectly repeated startup submission.
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                repeat(2) {
                    scenario.recreate()
                    verifyNoNewStartup(dependencies, before + 1)
                    scenario.moveToState(Lifecycle.State.STARTED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    verifyNoNewStartup(dependencies, before + 1)
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    verifyNoNewStartup(dependencies, before + 1)
                }
            }
            assertEquals(before + 1, startupCount(dependencies))
            instrumentation.sendStatus(0, Bundle().apply {
                putString("step09Lifecycle", "production MainActivity launch/recreate/background return; one startup sync completed by worker")
                putString("step09LifecycleLimit", "Activity lifecycle only; not physical reboot or process-death evidence")
            })
        } finally {
            dependencies.backgroundTasks.setStartupEnabled(originalSetting)
        }
    }

    private suspend fun startupCount(dependencies: ApplicationDependencies): Int = dependencies.taskQueue.list()
        .count { it.record.originalOrigin == TaskOrigin.STARTUP_SYNC }

    private suspend fun verifyNoNewStartup(dependencies: ApplicationDependencies, expected: Int) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Main dispatches the startup hook into the process IO scope; observe throughout a settling
        // interval after each platform transition rather than assert before that callback can run.
        repeat(10) {
            assertEquals("Activity transitions must not resubmit startup sync", expected, startupCount(dependencies))
            delay(100)
        }
    }
}
