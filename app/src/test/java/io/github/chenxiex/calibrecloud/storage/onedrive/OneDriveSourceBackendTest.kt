package io.github.chenxiex.calibrecloud.storage.onedrive

import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.local.SnapshotValidator
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OneDriveSourceBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private val location = LibraryLocation.OneDrive("account", "drive", "root")

    private class Fixture {
        val decoded = mutableMapOf<String, Map<String, Any?>>()
        val requests = mutableListOf<Request>()
        val refreshes = mutableListOf<Boolean>()
        var driveType = "personal"
        var accountId = "account"
        var ownerIdPresent = true
        var subject: String? = null
        var contentReads = 0
        var data = "database"
        var tag = "content-1"
        var valid = true
        var onRequest: (Request) -> Response? = { null }
        var onContent: () -> Unit = {}
        var childPages: (String, String?) -> Map<String, Any?> = { parent, _ ->
            mapOf("value" to if (parent == "root") listOf(item("db", "metadata.db", "root", false)) else emptyList<Map<String, Any?>>())
        }
        fun item(id: String, name: String, parent: String?, directory: Boolean, drive: String = "drive") = buildMap<String, Any?> {
            put("id", id); put("name", name); put(if (directory) "folder" else "file", emptyMap<String, Any?>())
            if (parent != null) put("parentReference", mapOf("id" to parent, "driveId" to drive))
            put("cTag", tag); put("size", data.toByteArray().size.toLong())
        }
        fun response(request: Request, code: Int = 200, data: String = "", headers: Map<String, String> = emptyMap()) =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(data.toResponseBody()).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            onRequest(request) ?: run {
                val path = request.url.encodedPath
                if (request.url.host == "download.example") {
                    contentReads++
                    onContent()
                    response(request, data = data)
                } else if (path.endsWith("/content")) {
                    response(request, 302, headers = mapOf("Location" to "https://download.example/file?secret=ephemeral"))
                } else {
                    val result = when {
                        path.endsWith("/me/drive") -> mapOf("id" to "drive", "driveType" to driveType, "owner" to mapOf("user" to (if (ownerIdPresent) mapOf("id" to accountId) else emptyMap<String, Any?>())))
                        path.endsWith("/children") -> childPages(request.url.pathSegments[4], request.url.queryParameter("page"))
                        path.endsWith("/root") -> item("root", "library", null, true)
                        path.endsWith("/items/db") -> item("db", "metadata.db", "root", false)
                        else -> item(request.url.pathSegments.last(), "folder", "root", true)
                    }
                    val marker = UUID.randomUUID().toString()
                    decoded[marker] = result
                    response(request, data = marker)
                }
            }
        }.build()
        val decoder = GraphJsonDecoder { decoded.getValue(it) }
    }

    @Test fun personalStableIdentityAndAccountSwitchAreVerified() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val identity = (backend.discover() as OneDriveSourceResult.Available).value
        assertEquals("account", identity.accountId)
        assertEquals("drive", identity.driveId)
        fixture.accountId = "other"
        assertEquals(StorageErrorKind.LOGIN_REQUIRED, failure(backend.version(location, RelativeSourcePath("metadata.db"))))
        fixture.driveType = "business"
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.discover()))
    }

    @Test fun missingOwnerIdUsesStableAuthenticatedSubjectAndDoesNotDependOnDisplayName() = runTest {
        val fixture = Fixture()
        fixture.ownerIdPresent = false
        fixture.subject = "stable-subject"
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val first = backend.discover() as OneDriveSourceResult.Available
        assertEquals("microsoft-consumers:stable-subject", first.value.accountId)
        fixture.ownerIdPresent = true
        fixture.accountId = "different-owner-representation"
        val second = backend.discover() as OneDriveSourceResult.Available
        assertEquals(first.value.accountId, second.value.accountId)
        fixture.subject = "another-subject"
        val selected = location.copy(accountId = first.value.accountId)
        assertEquals(StorageErrorKind.LOGIN_REQUIRED, failure(backend.listDirectories(selected)))
    }

    @Test fun pagesStayInsideSameParentAndUnsafeNextLinkReceivesNoToken() = runTest {
        val fixture = Fixture()
        fixture.childPages = { _, page ->
            if (page == null) mapOf("value" to listOf(fixture.item("folder", "Folder", "root", true)), "@odata.nextLink" to "https://graph.microsoft.com/v1.0/drives/drive/items/root/children?page=2")
            else mapOf("value" to listOf(fixture.item("folder2", "Second", "root", true)))
        }
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val first = (backend.listDirectories(location) as OneDriveSourceResult.Available).value
        assertEquals("folder", first.items.single().id)
        val second = (backend.listDirectories(location, pageUrl = first.nextPageUrl) as OneDriveSourceResult.Available).value
        assertEquals("folder2", second.items.single().id)
        fixture.childPages = { _, _ -> mapOf("value" to emptyList<Any>(), "@odata.nextLink" to "https://attacker.example/v1.0/drives/drive/items/root/children") }
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.listDirectories(location)))
        assertFalse(fixture.requests.any { it.url.host == "attacker.example" })
    }

    @Test fun unrelatedUnsupportedChildrenDoNotPreventDirectoryBrowsing() = runTest {
        val fixture = Fixture()
        fixture.childPages = { _, _ -> mapOf("value" to listOf(
            fixture.item("folder", "Supported", "root", true),
            fixture.item("file", "ordinary.txt", "root", false),
            fixture.item("shared", "Shared", "root", true) + ("shared" to emptyMap<String, Any?>()),
            fixture.item("remote", "Remote", "root", true) + ("remoteItem" to emptyMap<String, Any?>()),
            mapOf("id" to "package", "name" to "Notebook", "package" to emptyMap<String, Any?>()),
            fixture.item("deleted", "Deleted", "root", true) + ("deleted" to emptyMap<String, Any?>()),
        )) }
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val directories = (backend.listDirectories(location) as OneDriveSourceResult.Available).value
        assertEquals(listOf("folder", "shared"), directories.items.map { it.id })
        val allDirectories = (backend.listAllDirectories(location) as OneDriveSourceResult.Available).value
        assertEquals(directories.items, allDirectories.items)
        // Source resolution retains its strict boundary even for unrelated unsupported siblings.
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.resolve(location, RelativeSourcePath("ordinary.txt"))))
    }

    @Test fun sharedFacetOnOwnedItemsPreservesDirectoryBrowsingAndSourceRead() = runTest {
        val fixture = Fixture()
        fixture.childPages = { _, _ -> mapOf("value" to listOf(
            fixture.item("shared-folder", "Owned shared folder", "root", true) + ("shared" to mapOf("scope" to "users")),
            fixture.item("db", "metadata.db", "root", false) + ("shared" to mapOf("scope" to "users")),
        )) }
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val directories = (backend.listAllDirectories(location) as OneDriveSourceResult.Available).value
        assertEquals(listOf("shared-folder"), directories.items.map { it.id })
        val source = (backend.resolve(location, RelativeSourcePath("metadata.db")) as OneDriveSourceResult.Available).value
        assertEquals("content-1", source.version.token)
        val stream = (backend.openRead(location, RelativeSourcePath("metadata.db")) as OneDriveSourceResult.Available).value
        assertEquals("database", stream.use { it.readBytes().decodeToString() })
        assertNull(fixture.requests.single { it.url.host == "download.example" }.header("Authorization"))
        fixture.childPages = { _, _ -> mapOf("value" to listOf(
            fixture.item("db", "metadata.db", "root", false) + ("shared" to emptyMap<String, Any?>()) + ("remoteItem" to emptyMap<String, Any?>()),
        )) }
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.openRead(location, RelativeSourcePath("metadata.db"))))
        assertEquals(1, fixture.contentReads)
    }

    @Test fun completeDirectoryLoadFollowsServerPagesAndVerifiesIdentityAndParentOnce() = runTest {
        val fixture = Fixture()
        fixture.childPages = { _, page ->
            val index = page?.toInt() ?: 0
            buildMap {
                put("value", (index * 5 until minOf(index * 5 + 5, 14)).map {
                    fixture.item("folder-$it", "Folder $it", "root", true)
                })
                if (index < 2) put("@odata.nextLink", "https://graph.microsoft.com/v1.0/drives/drive/items/root/children?page=${index + 1}")
            }
        }
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        var checks = 0
        val result = (backend.listAllDirectories(location, checkControl = { checks++ }) as OneDriveSourceResult.Available).value
        assertEquals((0 until 14).map { "folder-$it" }, result.items.map { it.id })
        assertEquals("library", result.parentName)
        assertNull(result.nextPageUrl)
        assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith("/me/drive") })
        assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith("/items/root") })
        assertEquals(3, fixture.requests.count { it.url.encodedPath.endsWith("/children") })
        assertTrue(fixture.requests.none { it.url.queryParameter("\$top") != null })
        assertTrue(checks >= 6)
    }

    @Test fun completeDirectoryLoadRefusesCrossParentAndCrossDriveFolders() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        for (child in listOf(
            fixture.item("folder", "Wrong parent", "outside", true),
            fixture.item("folder", "Wrong drive", "root", true, "other-drive"),
        )) {
            fixture.childPages = { _, _ -> mapOf("value" to listOf(child)) }
            assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.listAllDirectories(location)))
        }
    }

    @Test fun completeDirectoryLoadRejectsUnsafeLinksCyclesAndDuplicateItems() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        for (link in listOf(
            "https://attacker.example/v1.0/drives/drive/items/root/children",
            "https://graph.microsoft.com/v1.0/drives/other-drive/items/root/children",
            "https://graph.microsoft.com/v1.0/drives/drive/items/outside/children",
            "http://graph.microsoft.com/v1.0/drives/drive/items/root/children",
        )) {
            fixture.requests.clear()
            fixture.childPages = { _, _ -> mapOf("value" to emptyList<Any>(), "@odata.nextLink" to link) }
            assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.listAllDirectories(location)))
            assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith("/children") })
            assertFalse(fixture.requests.any { it.url.host == "attacker.example" })
        }
        fixture.requests.clear()
        fixture.childPages = { _, _ -> mapOf("value" to emptyList<Any>(), "@odata.nextLink" to "https://graph.microsoft.com/v1.0/drives/drive/items/root/children?page=loop") }
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.listAllDirectories(location)))
        assertEquals(2, fixture.requests.count { it.url.encodedPath.endsWith("/children") })
        fixture.childPages = { _, page ->
            buildMap {
                put("value", listOf(fixture.item("duplicate", "Folder", "root", true)))
                if (page == null) put("@odata.nextLink", "https://graph.microsoft.com/v1.0/drives/drive/items/root/children?page=2")
            }
        }
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.listAllDirectories(location)))
    }

    @Test fun completeDirectoryLoadChecksTaskControlBeforeRequestingNextServerPage() = runTest {
        val fixture = Fixture()
        fixture.childPages = { _, _ -> mapOf("value" to emptyList<Any>(), "@odata.nextLink" to "https://graph.microsoft.com/v1.0/drives/drive/items/root/children?page=2") }
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val cancelled = CancellationException("fixture cancellation")
        try {
            backend.listAllDirectories(location, checkControl = {
                if (fixture.requests.any { it.url.encodedPath.endsWith("/children") }) throw cancelled
            })
            throw AssertionError("task cancellation was swallowed")
        } catch (actual: CancellationException) {
            // Coroutine stack recovery may copy a standard CancellationException.
            assertEquals(cancelled.message, actual.message)
        }
        assertEquals(1, fixture.requests.count { it.url.encodedPath.endsWith("/children") })
    }

    @Test fun rootResolutionRefusesWrongParentRemoteItemsAndMissingFiles() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        fixture.childPages = { _, _ -> mapOf("value" to listOf(fixture.item("db", "metadata.db", "outside", false))) }
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.resolve(location, RelativeSourcePath("metadata.db"))))
        fixture.childPages = { _, _ -> mapOf("value" to listOf(fixture.item("db", "metadata.db", "root", false) + ("remoteItem" to emptyMap<String, Any?>()))) }
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.resolve(location, RelativeSourcePath("metadata.db"))))
        fixture.childPages = { _, _ -> mapOf("value" to listOf(fixture.item("db", "metadata.db", "root", false) + ("package" to emptyMap<String, Any?>()))) }
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.resolve(location, RelativeSourcePath("metadata.db"))))
        fixture.childPages = { _, _ -> mapOf("value" to emptyList<Any>()) }
        assertEquals(StorageErrorKind.SOURCE_MISSING, failure(backend.resolve(location, RelativeSourcePath("metadata.db"))))
    }

    @Test fun opaqueDirectoryIdsCannotNormalizeIntoDifferentGraphEndpoints() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        for (id in listOf(".", "..", "outside/child", "outside\\child")) {
            fixture.requests.clear()
            assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.listDirectories(location, id)))
            assertEquals(listOf("/v1.0/me/drive", "/v1.0/drives/drive/root"), fixture.requests.map { it.url.encodedPath })
        }
    }

    @Test fun contentRedirectDropsCredentialsAndHttpDowngradeIsRejected() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val stream = (backend.openRead(location, RelativeSourcePath("metadata.db")) as OneDriveSourceResult.Available).value
        assertEquals("database", stream.use { it.readBytes().decodeToString() })
        assertNull(fixture.requests.single { it.url.host == "download.example" }.header("Authorization"))
        fixture.onRequest = { request ->
            if (request.url.encodedPath.endsWith("/content")) fixture.response(request, 302, headers = mapOf("Location" to "http://attacker.example/file")) else null
        }
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.openRead(location, RelativeSourcePath("metadata.db"))))
        assertFalse(fixture.requests.any { it.url.host == "attacker.example" })
    }

    @Test fun authenticationRetriesOnceAndRetryAfterIsReturnedToScheduler() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        fixture.onRequest = { request -> if (request.header("Authorization") == "Bearer old") fixture.response(request, 401) else null }
        assertTrue(backend.discover() is OneDriveSourceResult.Available)
        assertTrue(fixture.refreshes.contains(true))
        fixture.onRequest = { fixture.response(it, 429, headers = mapOf("Retry-After" to "120")) }
        val limited = backend.discover() as OneDriveSourceResult.Failed
        assertEquals(120_000L, limited.retryDelayMillis)
        assertTrue(limited.transient)
        fixture.onRequest = { fixture.response(it, 403) }
        assertEquals(StorageErrorKind.AUTHORIZATION_EXPIRED, failure(backend.discover()))
        fixture.onRequest = { throw IOException() }
        assertEquals(StorageErrorKind.NO_NETWORK, failure(backend.discover()))
    }

    @Test fun snapshotUsesCtagAndDoubleReadAndPreservesPublishedGenerationOnConflict() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val candidate = UUID.randomUUID()
        val good = (backend.acquireSnapshot(location, candidate) as OneDriveSourceResult.Available).value
        assertEquals("database", good.file.readText())
        assertEquals("content-1", good.version.token)
        assertEquals(2, fixture.contentReads)
        fixture.onContent = { fixture.tag = "changed" }
        assertEquals(StorageErrorKind.VERSION_CONFLICT, failure(backend.acquireSnapshot(location, candidate)))
        assertTrue(good.file.exists())
        assertFalse(good.file.parentFile!!.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun activeTransactionLogsRefuseSnapshotAndKeepOldGeneration() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val candidate = UUID.randomUUID()
        val good = (backend.acquireSnapshot(location, candidate) as OneDriveSourceResult.Available).value
        for (name in listOf("metadata.db-wal", "metadata.db-journal", "metadata.db-shm")) {
            fixture.childPages = { _, _ -> mapOf("value" to listOf(
                fixture.item("db", "metadata.db", "root", false), fixture.item("log", name, "root", false),
            )) }
            assertEquals(StorageErrorKind.VERSION_CONFLICT, failure(backend.acquireSnapshot(location, candidate)))
            assertPreserved(good)
        }
    }

    @Test fun interruptedBodyReadKeepsOldSnapshotAndDeletesPartialCopy() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val candidate = UUID.randomUUID()
        val good = (backend.acquireSnapshot(location, candidate) as OneDriveSourceResult.Available).value
        fixture.onRequest = { request ->
            if (request.url.host != "download.example") null else {
                val interrupted = object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = 8L
                    private val input = object : ForwardingSource(Buffer().writeUtf8("partial")) {
                        private var first = true
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (!first) throw IOException()
                            first = false
                            return super.read(sink, byteCount)
                        }
                    }.buffer()
                    override fun source() = input
                }
                fixture.response(request).newBuilder().body(interrupted).build()
            }
        }
        assertEquals(StorageErrorKind.NO_NETWORK, failure(backend.acquireSnapshot(location, candidate)))
        assertPreserved(good)
    }

    @Test fun failedPrivateValidationKeepsOldSnapshotAndDeletesStaging() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val candidate = UUID.randomUUID()
        val good = (backend.acquireSnapshot(location, candidate) as OneDriveSourceResult.Available).value
        fixture.valid = false
        assertEquals(StorageErrorKind.CORRUPT_CONTENT, failure(backend.acquireSnapshot(location, candidate)))
        assertPreserved(good)
    }

    @Test fun taskCancellationPropagatesAfterPartialWriteAndKeepsOldSnapshot() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val candidate = UUID.randomUUID()
        val good = (backend.acquireSnapshot(location, candidate) as OneDriveSourceResult.Available).value
        val cancelled = object : CancellationException("fixture cancellation") {}
        var checksAfterContent = 0
        try {
            backend.acquireSnapshot(location, candidate) {
                // The fixture has already read two bodies for the old snapshot. During this attempt,
                // stop on the second copy-loop control check, after the first bytes reached staging.
                if (fixture.contentReads > 2 && ++checksAfterContent == 2) throw cancelled
            }
            throw AssertionError("task cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertPreserved(good)
    }

    @Test fun serverAndMissingResponsesRemainDistinctAndHttpDateRetryAfterIsHonored() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        fixture.onRequest = { fixture.response(it, 503) }
        val unavailable = backend.discover() as OneDriveSourceResult.Failed
        assertEquals(StorageErrorKind.NO_NETWORK, unavailable.error.kind)
        assertTrue(unavailable.transient)
        assertEquals(30_000L, unavailable.retryDelayMillis)
        fixture.onRequest = { fixture.response(it, 404) }
        val missing = backend.discover() as OneDriveSourceResult.Failed
        assertEquals(StorageErrorKind.SOURCE_MISSING, missing.error.kind)
        assertFalse(missing.transient)
        val retryDate = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(2)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        fixture.onRequest = { fixture.response(it, 429, headers = mapOf("Retry-After" to retryDate)) }
        val limited = backend.discover() as OneDriveSourceResult.Failed
        assertTrue(limited.retryDelayMillis!! in 110_000L..120_000L)
    }

    private fun assertPreserved(snapshot: OneDriveDatabaseSnapshot) {
        assertEquals("database", snapshot.file.readText())
        val files = snapshot.file.parentFile!!.listFiles()!!
        assertEquals(listOf(snapshot.file), files.toList())
    }

    private fun backend(fixture: Fixture, dispatcher: kotlinx.coroutines.CoroutineDispatcher) = OneDriveSourceBackend(
        tokenProvider = { refresh -> fixture.refreshes.add(refresh); if (refresh) "fresh" else "old" },
        snapshotsDirectory = temporary.newFolder(), validator = SnapshotValidator { fixture.valid }, ioDispatcher = dispatcher,
        client = fixture.client, decoder = fixture.decoder, contentClient = fixture.client, accountProvider = { fixture.subject },
    )

    private fun failure(result: OneDriveSourceResult<*>) = (result as OneDriveSourceResult.Failed).error.kind
}
