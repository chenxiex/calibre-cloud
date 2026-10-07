package io.github.chenxiex.calibrecloud.library

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Seeds the installed debug application with a desktop-maintained-format test library so its screens
 * can be driven over ADB without storage authorization. It writes only the debug package's private state
 * (selection and imported metadata) and never touches a source library; uninstall the package afterwards.
 * Skipped unless the instrumentation argument `seedLibraryDb` names a `metadata.db` readable by the shell:
 *
 *     adb push <library>/metadata.db /data/local/tmp/seed-metadata.db
 *     adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.library.ExtendedLibrarySeedTest \
 *         -e seedLibraryDb /data/local/tmp/seed-metadata.db io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ExtendedLibrarySeedTest {
    @Test
    fun importTheLibraryIntoTheApplicationState() {
        val path = InstrumentationRegistry.getArguments().getString("seedLibraryDb")
        assumeTrue("seedLibraryDb is not supplied", path != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        val copy = File(context.cacheDir, "seed-import.db")
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> copy.outputStream().use { input.copyTo(it) } }
        assertTrue(copy.length() > 100)
        runBlocking {
            val selected = dependencies.state.select(LibraryLocation.Local("seed.documents", "seeded-test-library"))
            assertNotNull(dependencies.metadata.importSnapshot(selected.token, copy))
            assertTrue(dependencies.metadata.selectReadColumn(dependencies.metadata.currentImport()!!, CustomColumnId(1, "#read_status")))
        }
        copy.delete()
    }
}
