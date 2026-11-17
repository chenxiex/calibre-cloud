package io.github.chenxiex.calibrecloud.storage.onedrive

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.local.SnapshotValidator
import java.util.UUID
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OneDriveDriveReferenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun snapshotComparesPersonalHexDriveReferencesWithoutChangingStoredLocation() = runTest {
        data class Case(val stored: String, val returned: String, val accepted: Boolean)
        val cases = listOf(
            Case("0123456789ABCDEF", "0123456789abcdef", true),
            Case("0123456789abcdef", "0123456789ABCDEF", true),
            Case("opaqueDrive", "opaqueDrive", true),
            Case("0123456789ABCDEF", "1123456789ABCDEF", false),
            Case("b!OpaqueDrive", "b!opaquedrive", false),
            Case("0123456789ABCDE", "0123456789abcde", false),
        )
        for ((stored, returned, accepted) in cases) {
            val location = LibraryLocation.OneDrive("microsoft-consumers:account", stored, "root")
            val requests = mutableListOf<okhttp3.Request>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests.add(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("fixture").body("metadata".toResponseBody()).build()
            }.build()
            val backend = OneDriveSourceBackend({ "token" }, temporary.newFolder(), SnapshotValidator { true },
                StandardTestDispatcher(testScheduler), client = client,
                decoder = GraphJsonDecoder { mapOf("id" to "db", "name" to "metadata.db", "file" to emptyMap<String, Any?>(),
                    "parentReference" to mapOf("driveId" to returned, "id" to "root"), "cTag" to "unchanged") },
                accountProvider = { "account" })
            val result = backend.acquireSnapshot(location, UUID.randomUUID(), FileVersion(BackendKind.ONEDRIVE, "unchanged"))
            assertEquals("$stored / $returned: $result", accepted, result is OneDriveSourceResult.Available)
            if (accepted) assertNull((result as OneDriveSourceResult.Available).value)
            else assertEquals(StorageErrorKind.INVALID_SOURCE_RESPONSE, (result as OneDriveSourceResult.Failed).error.kind)
            assertEquals(1, requests.size)
            assertEquals(stored, requests.single().url.pathSegments[2])
            assertEquals(stored, location.driveId)
        }
    }
}
