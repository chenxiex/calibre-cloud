package io.github.chenxiex.calibrecloud.storage.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in with `-e localSaf true` after the system picker grants the dedicated repository-sample test copy. Revokes it. */
@RunWith(AndroidJUnit4::class)
class LocalSourceBackendDeviceTest {
    @Before
    fun requireExplicitOptIn() {
        assumeTrue("Real SAF tests require a picker-granted dedicated sample and explicit opt-in",
            InstrumentationRegistry.getArguments().getString("localSaf") == "true")
    }

    /** Requires a granted dedicated sample; does not revoke the grant or modify source data. */
    @Test
    fun realSafRangeMatchesFullSourceSuffix() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        val tree = dependencies.state.current()?.location?.let { dependencies.state.accessKey(it) }
        assertNotNull("Authorize a dedicated sample copy through the system picker first", tree)
        val path = RelativeSourcePath("metadata.db")
        val backend = dependencies.localBackend
        val before = (backend.version(tree!!, path) as LocalSourceResult.Available).value
        val complete = (backend.openRead(tree, path) as LocalSourceResult.Available).value
        val bytes = withContext(Dispatchers.IO) { complete.use { it.readBytes() } }
        assertTrue(bytes.size > 4096)
        // The provider's document size drives the copy precheck and progress total.
        assertEquals(bytes.size.toLong(), (backend.size(tree, path) as LocalSourceResult.Available).value)
        val suffix = (backend.openRange(tree, path, 4096, before) as LocalSourceResult.Available).value
        assertNotNull("The supported system external-storage provider should support direct seek", suffix)
        val actual = withContext(Dispatchers.IO) { suffix!!.use { it.readBytes() } }
        assertArrayEquals(bytes.copyOfRange(4096, bytes.size), actual)
        assertEquals(before, (backend.version(tree, path) as LocalSourceResult.Available).value)
    }

    @Test
    fun realSafSnapshotSourceReadsCancellationAndRevocation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        val tree = dependencies.state.current()?.location?.let { dependencies.state.accessKey(it) }
        assertNotNull("Authorize only the dedicated sample copy through the system picker first", tree)
        tree!!
        val backend = dependencies.localBackend
        val path = RelativeSourcePath("metadata.db")
        val before = (backend.version(tree, path) as LocalSourceResult.Available).value
        val snapshot = backend.acquireSnapshot(tree, UUID.randomUUID()) as LocalSourceResult.Available
        assertEquals(before, snapshot.value.version)
        assertTrue(AndroidSnapshotValidator().validate(snapshot.value.file))
        assertTrue(snapshot.value.file.canonicalPath.startsWith(context.filesDir.canonicalPath + "/snapshots/"))
        assertEquals(before, (backend.version(tree, path) as LocalSourceResult.Available).value)
        for (name in listOf("Quick Start Guide - John Schember.epub", "cover.jpg")) {
            val stream = backend.openRead(tree, RelativeSourcePath("John Schember/Quick Start Guide (1)/$name")) as LocalSourceResult.Available
            withContext(Dispatchers.IO) { stream.value.use { assertTrue(it.read() >= 0) } }
        }
        assertEquals(StorageErrorKind.SOURCE_MISSING,
            (backend.resolve(tree, RelativeSourcePath("absent.db")) as LocalSourceResult.Failed).error.kind)
        var checks = 0
        try {
            backend.acquireSnapshot(tree, UUID.randomUUID()) {
                if (++checks == 3) throw CancellationException("test cancellation")
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(snapshot.value.file.isFile)
            assertFalse(context.filesDir.resolve("snapshots/local").walkTopDown().any { it.extension == "part" })
        }
        val selected = requireNotNull(dependencies.state.current())
        val submitted = dependencies.taskCoordinator.submit(TaskSubmission(TaskRequest.CandidateConfiguration(
            CandidateContext(selected.token, BackendKind.LOCAL, selected.authorizationId ?: selected.token),
            TaskRequest.CandidateConfiguration.LIBRARY_SYNC), TaskOrigin.MANUAL_SYNC)) as SubmissionResult.Created
        dependencies.taskCoordinator.drain()
        assertEquals(TaskState.Finished(TaskResult.Completed), dependencies.taskQueue.get(submitted.taskId)!!.record.state)
        // A completed sync imports the snapshot and binds the selected directory to a library identity.
        assertEquals(AndroidDirectoryPermissions(context).localLocation(tree), dependencies.state.current()!!.identity?.location)
        assertEquals(before, (backend.version(tree, path) as LocalSourceResult.Available).value)
        AndroidDirectoryPermissions(context).release(tree)
        assertEquals(StorageErrorKind.AUTHORIZATION_EXPIRED,
            (backend.acquireSnapshot(tree, UUID.randomUUID()) as LocalSourceResult.Failed).error.kind)
        assertTrue(snapshot.value.file.isFile)
        assertEquals(DirectoryAuthorizationStatus.REAUTHORIZATION_REQUIRED, dependencies.localAuthorization.status(tree))
    }
}
