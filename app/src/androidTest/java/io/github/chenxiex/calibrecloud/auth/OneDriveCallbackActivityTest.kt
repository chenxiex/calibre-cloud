package io.github.chenxiex.calibrecloud.auth

import android.app.Activity
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.chenxiex.calibrecloud.BuildConfig
import io.github.chenxiex.calibrecloud.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The login callback arrives in the browser's task. It must bring back the MainActivity that started
 * the login, with its pages (such as the add-library wizard), not open a fresh one in the browser's task.
 */
@RunWith(AndroidJUnit4::class)
class OneDriveCallbackActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun mainActivities(): List<Activity> {
        val found = mutableListOf<Activity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.RESTARTED).forEach { stage ->
                found += monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>()
            }
        }
        return found
    }

    @Test
    fun aCallbackFromAnotherTaskReturnsToTheRunningMainActivity() {
        assumeTrue("OneDrive is not configured, so the callback activity is disabled", BuildConfig.ONEDRIVE_CONFIGURED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var original: Activity? = null
            scenario.onActivity { original = it }
            // A browser delivers the redirect from its own task; MULTIPLE_TASK gives the callback such a task.
            val context = instrumentation.targetContext
            val callbacks = instrumentation.addMonitor(OneDriveCallbackActivity::class.java.name, null, false)
            context.startActivity(Intent(context, OneDriveCallbackActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
            val callback = requireNotNull(callbacks.waitForActivityWithTimeout(10_000))
            instrumentation.removeMonitor(callbacks)
            val deadline = System.currentTimeMillis() + 10_000
            while (!callback.isDestroyed && System.currentTimeMillis() < deadline) Thread.sleep(100)
            var resumed: Activity? = null
            while (System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().singleOrNull()
                }
                if (resumed != null && mainActivities().none { it != resumed && !it.isFinishing }) break
                Thread.sleep(100)
            }
            try {
                assertSame(original, resumed)
                assertEquals(1, mainActivities().count { !it.isFinishing })
            } finally {
                val strays = mainActivities().filter { it !== original }
                instrumentation.runOnMainSync { strays.forEach { it.finish() } }
            }
        }
    }
}
