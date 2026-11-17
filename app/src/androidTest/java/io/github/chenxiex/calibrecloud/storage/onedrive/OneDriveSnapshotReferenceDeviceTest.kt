package io.github.chenxiex.calibrecloud.storage.onedrive

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.CalibreCloudApplication
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.storage.local.AndroidSnapshotValidator
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in read-only snapshot probe of the selected OneDrive source explicitly authorized for reading in the debug app. */
@RunWith(AndroidJUnit4::class)
class OneDriveSnapshotReferenceDeviceTest {
    @Test fun snapshotAcceptsTheServicesOwnDriveReference() = runBlocking {
        assumeTrue("Requires a selected source authorized for read-only probing and explicit opt-in",
            InstrumentationRegistry.getArguments().getString("oneDriveSnapshotReference") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.chenxiex.calibrecloud.debug", context.packageName)
        val dependencies = (context.applicationContext as CalibreCloudApplication).dependencies
        val location = requireNotNull(dependencies.state.current()?.location as? LibraryLocation.OneDrive)
        var expectedDriveId = location.driveId
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.isSuccessful && chain.request().url.host == "graph.microsoft.com" &&
                !chain.request().url.encodedPath.endsWith("/content")) {
                val body = try { JSONObject(response.peekBody(2_000_000).string()) } catch (_: Exception) {
                    response.close()
                    throw AssertionError("Graph reference probe could not parse metadata")
                }
                val parent = body.optJSONObject("parentReference")
                val drive = parent?.optString("driveId")
                val hex = Regex("[0-9a-fA-F]{16}")
                // Only shape/comparison evidence, never identifiers, URLs, credentials or response bodies.
                android.util.Log.i("SnapshotReference", "parent=${parent != null} drivePresent=${!drive.isNullOrEmpty()} " +
                    "exact=${drive == expectedDriveId} caseOnly=${drive != expectedDriveId && drive?.equals(expectedDriveId, true) == true} " +
                    "bothHex=${drive != null && hex.matches(drive) && hex.matches(expectedDriveId)}")
            }
            response
        }.build()
        val directory = File(context.cacheDir, "snapshot-reference-probe")
        try {
            dependencies.taskQueue.executionLock.withLock {
                val backend = OneDriveSourceBackend(
                    { refresh -> dependencies.oneDriveAuthorization.backendAccessToken(refresh) },
                    directory, AndroidSnapshotValidator(), Dispatchers.IO, client = client,
                    accountProvider = { dependencies.oneDriveAuthorization.accountSubject() },
                    diagnostic = { android.util.Log.w("SnapshotReference", "stage=$it") })
                val hex = Regex("[0-9a-fA-F]{16}")
                val alternate = if (location.driveId != location.driveId.lowercase()) location.driveId.lowercase()
                    else location.driveId.uppercase()
                val variants = listOf(location.driveId) + listOf(alternate).filter { hex.matches(it) && it != location.driveId }
                var version: FileVersion? = null
                for (driveId in variants) {
                    expectedDriveId = driveId
                    // A transient request models the casing saved by an older configuration; no state update.
                    val result = backend.acquireSnapshot(location.copy(driveId = driveId), UUID.randomUUID(), version)
                    assertTrue("Snapshot acquisition failed; inspect safe SnapshotReference diagnostics", result is OneDriveSourceResult.Available)
                    (result as OneDriveSourceResult.Available).value?.let { version = it.version }
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
