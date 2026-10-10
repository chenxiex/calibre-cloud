package io.github.chenxiex.calibrecloud.storage.onedrive

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.storage.api.PushJournal
import io.github.chenxiex.calibrecloud.storage.api.PushOutcome
import io.github.chenxiex.calibrecloud.storage.api.SourceFailure
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.api.WriteBlock
import io.github.chenxiex.calibrecloud.storage.local.SnapshotValidator
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.net.URLDecoder
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

/**
 * Graph fixture with path addressing below the root item. Every request is recorded so tests can
 * assert the exact request sequence: Graph metadata requests, children listings and content reads.
 */
class OneDriveSourceBackendTest {
    @get:Rule val temporary = TemporaryFolder()
    private val location = LibraryLocation.OneDrive("microsoft-consumers:subject", "drive", "root")
    private val database = RelativeSourcePath("metadata.db")

    private class Fixture {
        val decoded = mutableMapOf<String, Map<String, Any?>>()
        val requests = mutableListOf<Request>()
        val refreshes = mutableListOf<Boolean>()
        var driveType = "personal"
        var ownerId: String? = "owner"
        var subject: String? = "subject"
        var contentReads = 0
        var valid = true
        var directUrls = true
        var thumbnails = emptyList<Map<String, Any?>>()
        var onRequest: (Request) -> Response? = { null }
        var onContent: () -> Unit = {}
        /** Library files by decoded relative path; values are item JSON without content. */
        val files = mutableMapOf<String, Map<String, Any?>>()
        val contents = mutableMapOf<String, String>()
        var childPages: (String, String?) -> Map<String, Any?> = { _, _ -> mapOf("value" to emptyList<Any>()) }

        init { file("metadata.db", "db", "database") }

        fun file(path: String, id: String, content: String, tag: String = "content-1", extra: Map<String, Any?> = emptyMap()) {
            contents[id] = content
            files[path] = item(id, path.substringAfterLast('/'), "parent-of-$id", false, tag = tag, size = content.toByteArray().size.toLong()) + extra
        }
        fun item(id: String, name: String, parent: String?, directory: Boolean, drive: String = "drive", tag: String = "content-1", size: Long = 0) = buildMap<String, Any?> {
            put("id", id); put("name", name); put(if (directory) "folder" else "file", emptyMap<String, Any?>())
            if (parent != null) put("parentReference", mapOf("id" to parent, "driveId" to drive))
            put("cTag", tag); put("size", size)
        }
        fun response(request: Request, code: Int = 200, data: String = "", headers: Map<String, String> = emptyMap()) =
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(data.toResponseBody()).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
        fun json(request: Request, value: Map<String, Any?>): Response {
            val marker = UUID.randomUUID().toString()
            decoded[marker] = value
            return response(request, data = marker)
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            onRequest(request) ?: run {
                val path = request.url.encodedPath
                when {
                    request.url.host == "download.example" -> {
                        contentReads++
                        onContent()
                        response(request, data = contents.getValue(request.url.pathSegments.last()))
                    }
                    path.endsWith("/content") -> response(request, 302,
                        headers = mapOf("Location" to "https://download.example/${request.url.pathSegments[4]}?secret=ephemeral"))
                    "/items/root:/" in path -> {
                        val relative = path.substringAfter("/items/root:/").split('/').joinToString("/") { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
                        val item = files[relative] ?: return@run response(request, 404)
                        var value = item
                        if (directUrls && item["file"] != null) value = value + ("@microsoft.graph.downloadUrl" to "https://download.example/${item["id"]}?secret=ephemeral")
                        if (request.url.queryParameter("\$expand") == "thumbnails") value = value + ("thumbnails" to thumbnails)
                        json(request, value)
                    }
                    path.endsWith("/me/drive") -> json(request, mapOf("id" to "drive", "driveType" to driveType,
                        "owner" to mapOf("user" to (ownerId?.let { mapOf("id" to it) } ?: emptyMap<String, Any?>()))))
                    path.endsWith("/children") -> json(request, childPages(request.url.pathSegments[4], request.url.queryParameter("page")))
                    path.endsWith("/root") -> json(request, item("root", "library", null, true))
                    else -> json(request, item(request.url.pathSegments.last(), "folder", "root", true))
                }
            }
        }.build()
        val decoder = GraphJsonDecoder { decoded.getValue(it) }
        fun graph() = requests.filter { it.url.host == "graph.microsoft.com" }
    }

    @Test fun identityIsComparedLocallyWithoutRequests() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val identity = (backend.discover() as OneDriveSourceResult.Available).value
        assertEquals(location.accountId, identity.accountId)
        assertEquals("drive", identity.driveId)
        fixture.requests.clear()
        fixture.subject = "another-subject"
        assertEquals(StorageErrorKind.LOGIN_REQUIRED, failure(backend.version(location, database)))
        assertEquals(StorageErrorKind.LOGIN_REQUIRED, failure(backend.acquireSnapshot(location, UUID.randomUUID())))
        assertTrue(fixture.requests.isEmpty())
        fixture.driveType = "business"
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.discover()))
    }

    @Test fun sessionWithoutSubjectFallsBackToDriveOwner() = runTest {
        val fixture = Fixture()
        fixture.subject = null
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val owned = location.copy(accountId = "owner")
        assertEquals("content-1", (backend.version(owned, database) as OneDriveSourceResult.Available).value.token)
        assertEquals(listOf("/v1.0/me/drive", "/v1.0/drives/drive/root", "/v1.0/drives/drive/items/root:/metadata.db"),
            fixture.requests.map { it.url.encodedPath })
        fixture.ownerId = "other"
        assertEquals(StorageErrorKind.LOGIN_REQUIRED, failure(backend.version(owned, database)))
    }

    @Test fun lookupIsOnePathRequestAndDownloadNeedsNoFurtherGraphRequest() = runTest {
        val fixture = Fixture()
        fixture.file("Author #1/Book 100% + more 书/Book 100% + more 书.epub", "book", "epub bytes", tag = "book-tag")
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val path = RelativeSourcePath("Author #1/Book 100% + more 书/Book 100% + more 书.epub")
        val file = (backend.lookup(location, path) as OneDriveSourceResult.Available).value
        assertEquals(FileVersion(BackendKind.ONEDRIVE, "book-tag"), file.version)
        assertEquals(10L, file.sizeBytes)
        assertFalse(file.toString().contains("secret"))
        val stream = (backend.open(location, file) as OneDriveSourceResult.Available).value
        assertEquals("epub bytes", stream.use { it.readBytes().decodeToString() })
        val graph = fixture.graph().single()
        assertEquals("/v1.0/drives/drive/items/root:/Author%20%231/Book%20100%25%20%2B%20more%20%E4%B9%A6/Book%20100%25%20%2B%20more%20%E4%B9%A6.epub",
            graph.url.encodedPath)
        assertNull(graph.url.queryParameter("\$select"))
        assertNull(fixture.requests.single { it.url.host == "download.example" }.header("Authorization"))
        assertFalse(fixture.requests.any { it.url.encodedPath.endsWith("/children") })
    }

    @Test fun missingDownloadUrlFallsBackToContentRedirectWithoutCredentials() = runTest {
        val fixture = Fixture()
        fixture.directUrls = false
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val file = (backend.lookup(location, database) as OneDriveSourceResult.Available).value
        val stream = (backend.open(location, file) as OneDriveSourceResult.Available).value
        assertEquals("database", stream.use { it.readBytes().decodeToString() })
        assertEquals(listOf("/v1.0/drives/drive/items/root:/metadata.db", "/v1.0/drives/drive/items/db/content"),
            fixture.graph().map { it.url.encodedPath })
        assertNull(fixture.requests.single { it.url.host == "download.example" }.header("Authorization"))
        fixture.onRequest = { request ->
            if (request.url.encodedPath.endsWith("/content")) fixture.response(request, 302, headers = mapOf("Location" to "http://attacker.example/file")) else null
        }
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.open(location, file)))
        assertFalse(fixture.requests.any { it.url.host == "attacker.example" })
    }

    @Test fun unsafeDownloadUrlIsNeverRequested() = runTest {
        val fixture = Fixture()
        fixture.file("book.epub", "book", "bytes", extra = mapOf("@microsoft.graph.downloadUrl" to "http://attacker.example/book"))
        fixture.directUrls = false
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val file = (backend.lookup(location, RelativeSourcePath("book.epub")) as OneDriveSourceResult.Available).value
        assertEquals("bytes", (backend.open(location, file) as OneDriveSourceResult.Available).value.use { it.readBytes().decodeToString() })
        assertFalse(fixture.requests.any { it.url.host == "attacker.example" })
    }

    @Test fun pathLookupRefusesFoldersRemoteItemsOtherDrivesAndMissingFiles() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        fixture.files["folder"] = fixture.item("folder", "folder", "root", true)
        assertEquals(StorageErrorKind.SOURCE_MISSING, failure(backend.lookup(location, RelativeSourcePath("folder"))))
        assertEquals(StorageErrorKind.SOURCE_MISSING, failure(backend.lookup(location, RelativeSourcePath("absent/book.epub"))))
        for (extra in listOf("remoteItem", "package", "deleted")) {
            fixture.file("odd.epub", "odd", "x", extra = mapOf(extra to emptyMap<String, Any?>()))
            assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.lookup(location, RelativeSourcePath("odd.epub"))))
        }
        fixture.files["odd.epub"] = fixture.item("odd", "odd.epub", "parent", false, drive = "other-drive")
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.lookup(location, RelativeSourcePath("odd.epub"))))
        // The shared facet of an owned item does not change its drive boundary.
        fixture.file("shared.epub", "shared", "bytes", extra = mapOf("shared" to mapOf("scope" to "users")))
        assertTrue(backend.lookup(location, RelativeSourcePath("shared.epub")) is OneDriveSourceResult.Available)
        assertFalse(fixture.requests.any { it.url.encodedPath.endsWith("/children") })
    }

    @Test fun coverIsOneRequestAndChoosesSmallestSufficientThumbnailWithoutCredentials() = runTest {
        val fixture = Fixture()
        fixture.file("Book/cover.jpg", "cover-image", "original", tag = "cover-tag")
        fixture.thumbnails = listOf(mapOf(
            "small" to thumbnail(64, 96, "small"),
            "medium" to thumbnail(200, 300, "medium"),
            "large" to thumbnail(800, 1200, "large"),
        ))
        fixture.onRequest = { request ->
            if (request.url.host == "thumbnail.example") fixture.response(request, data = request.url.pathSegments.last()) else null
        }
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        var dimensions: Pair<Int, Int>? = null
        val cover = (backend.openCover(location, RelativeSourcePath("Book/cover.jpg"), 120, 180) { width, height ->
            dimensions = width to height
        } as OneDriveSourceResult.Available).value
        assertEquals("medium", cover.stream.use { it.readBytes().decodeToString() })
        assertEquals("cover-tag", cover.version.token)
        assertEquals(200 to 300, dimensions)
        val graph = fixture.graph().single()
        assertEquals("/v1.0/drives/drive/items/root:/Book/cover.jpg", graph.url.encodedPath)
        assertEquals("thumbnails", graph.url.queryParameter("\$expand"))
        assertNull(fixture.requests.single { it.url.host == "thumbnail.example" }.header("Authorization"))
        val larger = (backend.openCover(location, RelativeSourcePath("Book/cover.jpg"), 1000, 1500) as OneDriveSourceResult.Available).value
        assertEquals("large", larger.stream.use { it.readBytes().decodeToString() })
    }

    @Test fun absentUnsafeAndExpiredThumbnailsFallBackToSameCoverOriginal() = runTest {
        val fixture = Fixture()
        fixture.file("cover.jpg", "cover-image", "original")
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        for (available in listOf(
            emptyList(),
            listOf(mapOf("small" to thumbnail(64, 96, "unsafe") + ("url" to "http://unsafe.example/image"))),
            listOf(mapOf("medium" to thumbnail(200, 300, "expired"))),
        )) {
            fixture.requests.clear()
            fixture.thumbnails = available
            fixture.onRequest = { request -> if (request.url.host == "thumbnail.example") fixture.response(request, 404) else null }
            val cover = (backend.openCover(location, RelativeSourcePath("cover.jpg"), 120, 180) { _, _ ->
                throw AssertionError("fallback counted as thumbnail")
            } as OneDriveSourceResult.Available).value
            assertEquals("original", cover.stream.use { it.readBytes().decodeToString() })
            assertEquals(1, fixture.graph().size)
            assertEquals("/cover-image", fixture.requests.single { it.url.host == "download.example" }.url.encodedPath)
            assertFalse(fixture.requests.any { it.url.host == "unsafe.example" })
        }
    }

    @Test fun thumbnailFailuresRetainAuthorizationAndRetrySemanticsWithoutOriginalFallback() = runTest {
        val fixture = Fixture()
        fixture.file("cover.jpg", "cover-image", "original")
        fixture.thumbnails = listOf(mapOf("medium" to thumbnail(200, 300, "image")))
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        for ((code, kind) in listOf(403 to StorageErrorKind.AUTHORIZATION_EXPIRED, 429 to StorageErrorKind.THROTTLED, 503 to StorageErrorKind.THROTTLED)) {
            fixture.requests.clear()
            fixture.onRequest = { request ->
                if (request.url.host == "thumbnail.example") fixture.response(request, code, headers = mapOf("Retry-After" to "12")) else null
            }
            val result = backend.openCover(location, RelativeSourcePath("cover.jpg"), 120, 180) as OneDriveSourceResult.Failed
            assertEquals(kind, result.error.kind)
            assertEquals(code != 403, result.transient)
            if (result.transient) assertEquals(12_000L, result.retryDelayMillis)
            assertFalse(fixture.requests.any { it.url.host == "download.example" })
        }
    }

    @Test fun thumbnailRedirectRejectsDowngradeAndForeignCoverReceivesNoThumbnailRequest() = runTest {
        val fixture = Fixture()
        fixture.file("cover.jpg", "cover-image", "original")
        fixture.thumbnails = listOf(mapOf("medium" to thumbnail(200, 300, "image")))
        fixture.onRequest = { request ->
            if (request.url.host == "thumbnail.example") fixture.response(request, 302, headers = mapOf("Location" to "http://unsafe.example/image")) else null
        }
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.openCover(location, RelativeSourcePath("cover.jpg"), 120, 180)))
        assertFalse(fixture.requests.any { it.url.host == "unsafe.example" })
        fixture.requests.clear()
        fixture.files["cover.jpg"] = fixture.item("cover-image", "cover.jpg", "parent", false, drive = "other-drive")
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.openCover(location, RelativeSourcePath("cover.jpg"), 120, 180)))
        assertFalse(fixture.requests.any { it.url.host == "thumbnail.example" })
    }

    private fun thumbnail(width: Int, height: Int, name: String): Map<String, Any?> = mapOf(
        "width" to width, "height" to height, "url" to "https://thumbnail.example/$name",
    )

    @Test fun stableSubjectIdentityDoesNotDependOnOwnerRepresentation() = runTest {
        val fixture = Fixture()
        fixture.ownerId = null
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val first = backend.discover() as OneDriveSourceResult.Available
        assertEquals("microsoft-consumers:subject", first.value.accountId)
        fixture.ownerId = "different-owner-representation"
        val second = backend.discover() as OneDriveSourceResult.Available
        assertEquals(first.value.accountId, second.value.accountId)
        fixture.subject = "another-subject"
        assertEquals(StorageErrorKind.LOGIN_REQUIRED, failure(backend.listAllDirectories(location)))
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
        val directories = (backend.listAllDirectories(location) as OneDriveSourceResult.Available).value
        assertEquals(listOf("folder", "shared"), directories.items.map { it.id })
    }

    @Test fun completeDirectoryLoadFollowsServerPagesAndVerifiesParentOnce() = runTest {
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
        assertEquals(0, fixture.requests.count { it.url.encodedPath.endsWith("/me/drive") })
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

    @Test fun opaqueDirectoryIdsCannotNormalizeIntoDifferentGraphEndpoints() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        for (id in listOf(".", "..", "outside/child", "outside\\child")) {
            assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, failure(backend.listAllDirectories(location, id)))
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun authenticationRetriesOnceAndRetryAfterIsReturnedToScheduler() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        fixture.onRequest = { request -> if (request.header("Authorization") == "Bearer old") fixture.response(request, 401) else null }
        assertTrue(backend.lookup(location, database) is OneDriveSourceResult.Available)
        assertTrue(fixture.refreshes.contains(true))
        fixture.onRequest = { fixture.response(it, 429, headers = mapOf("Retry-After" to "120")) }
        val limited = backend.lookup(location, database) as OneDriveSourceResult.Failed
        assertEquals(120_000L, limited.retryDelayMillis)
        assertTrue(limited.transient)
        fixture.onRequest = { fixture.response(it, 403) }
        assertEquals(StorageErrorKind.AUTHORIZATION_EXPIRED, failure(backend.lookup(location, database)))
        fixture.onRequest = { throw IOException() }
        assertEquals(StorageErrorKind.NO_NETWORK, failure(backend.lookup(location, database)))
    }

    @Test fun serverAndMissingResponsesRemainDistinctAndHttpDateRetryAfterIsHonored() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        fixture.onRequest = { fixture.response(it, 503) }
        val unavailable = backend.lookup(location, database) as OneDriveSourceResult.Failed
        assertEquals(StorageErrorKind.THROTTLED, unavailable.error.kind)
        assertTrue(unavailable.transient)
        assertEquals(30_000L, unavailable.retryDelayMillis)
        fixture.onRequest = { fixture.response(it, 404) }
        val missing = backend.lookup(location, database) as OneDriveSourceResult.Failed
        assertEquals(StorageErrorKind.SOURCE_MISSING, missing.error.kind)
        assertFalse(missing.transient)
        val retryDate = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(2)
            .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
        fixture.onRequest = { fixture.response(it, 429, headers = mapOf("Retry-After" to retryDate)) }
        val limited = backend.lookup(location, database) as OneDriveSourceResult.Failed
        assertEquals(StorageErrorKind.THROTTLED, limited.error.kind)
        assertTrue(limited.retryDelayMillis!! in 110_000L..120_000L)
    }

    @Test fun unchangedSnapshotIsOneRequestWithoutReading() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val result = backend.acquireSnapshot(location, UUID.randomUUID(), FileVersion(BackendKind.ONEDRIVE, "content-1"))
        assertNull((result as OneDriveSourceResult.Available).value)
        assertEquals(listOf("/v1.0/drives/drive/items/root:/metadata.db"), fixture.requests.map { it.url.encodedPath })
    }

    @Test fun changedSnapshotQueriesLogsByNameAndReadsOnceWithPreReadVersion() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val good = (backend.acquireSnapshot(location, UUID.randomUUID(), FileVersion(BackendKind.ONEDRIVE, "older"))
            as OneDriveSourceResult.Available).value!!
        assertEquals("database", good.file.readText())
        assertEquals("content-1", good.version.token)
        assertEquals(1, fixture.contentReads)
        assertEquals(listOf("/v1.0/drives/drive/items/root:/metadata.db", "/v1.0/drives/drive/items/root:/metadata.db-wal",
            "/v1.0/drives/drive/items/root:/metadata.db-journal"), fixture.graph().map { it.url.encodedPath })
        assertFalse(fixture.requests.any { it.url.encodedPath.endsWith("/children") })
        assertFalse(good.file.parentFile!!.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun nonEmptyTransactionLogsRefuseSnapshotAndEmptyLogsDoNot() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val candidate = UUID.randomUUID()
        val good = (backend.acquireSnapshot(location, candidate) as OneDriveSourceResult.Available).value!!
        for (name in listOf("metadata.db-wal", "metadata.db-journal")) {
            fixture.file(name, "log", "pending")
            assertEquals(StorageErrorKind.VERSION_CONFLICT, failure(backend.acquireSnapshot(location, candidate)))
            assertPreserved(good)
            fixture.file(name, "log", "")
            val empty = (backend.acquireSnapshot(location, UUID.randomUUID()) as OneDriveSourceResult.Available).value!!
            assertEquals("database", empty.file.readText())
            fixture.files.remove(name)
        }
        fixture.files["metadata.db-journal"] = fixture.item("log", "metadata.db-journal", "root", true)
        assertEquals(StorageErrorKind.VERSION_CONFLICT, failure(backend.acquireSnapshot(location, candidate)))
    }

    @Test fun snapshotLengthMismatchIsCorrupt() = runTest {
        val fixture = Fixture()
        fixture.file("metadata.db", "db", "database")
        fixture.contents["db"] = "truncated"
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        assertEquals(StorageErrorKind.CORRUPT_CONTENT, failure(backend.acquireSnapshot(location, UUID.randomUUID())))
    }

    @Test fun interruptedBodyReadKeepsOldSnapshotAndDeletesPartialCopy() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val candidate = UUID.randomUUID()
        val good = (backend.acquireSnapshot(location, candidate) as OneDriveSourceResult.Available).value!!
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
        val good = (backend.acquireSnapshot(location, candidate) as OneDriveSourceResult.Available).value!!
        fixture.valid = false
        assertEquals(StorageErrorKind.CORRUPT_CONTENT, failure(backend.acquireSnapshot(location, candidate)))
        assertPreserved(good)
    }

    @Test fun taskCancellationPropagatesAfterPartialWriteAndKeepsOldSnapshot() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val candidate = UUID.randomUUID()
        val good = (backend.acquireSnapshot(location, candidate) as OneDriveSourceResult.Available).value!!
        val cancelled = object : CancellationException("fixture cancellation") {}
        var checksAfterContent = 0
        try {
            backend.acquireSnapshot(location, candidate) {
                // The old snapshot read one body. During this attempt, stop on the second copy-loop
                // control check, after the first bytes reached staging.
                if (fixture.contentReads > 1 && ++checksAfterContent == 2) throw cancelled
            }
            throw AssertionError("task cancellation was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
        assertPreserved(good)
    }

    @Test fun rangeUsesLookedUpDownloadUrlAndReturnsOnlyValidatedSuffix() = runTest {
        val fixture = Fixture()
        fixture.onRequest = { request ->
            if (request.url.host == "download.example") fixture.response(request, 206, "base", mapOf("Content-Range" to "bytes 4-7/8")) else null
        }
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val file = (backend.lookup(location, database) as OneDriveSourceResult.Available).value
        val stream = (backend.openRange(location, file, 4) as OneDriveSourceResult.Available).value!!
        assertEquals("base", stream.use { it.readBytes().decodeToString() })
        val content = fixture.requests.single { it.url.host == "download.example" }
        assertEquals("bytes=4-", content.header("Range"))
        assertEquals("identity", content.header("Accept-Encoding"))
        assertNull(content.header("Authorization"))
        assertEquals(1, fixture.graph().size)
        fixture.directUrls = false
        fixture.requests.clear()
        val redirected = (backend.lookup(location, database) as OneDriveSourceResult.Available).value
        (backend.openRange(location, redirected, 4) as OneDriveSourceResult.Available).value!!.close()
        assertNull(fixture.requests.single { it.url.encodedPath.endsWith("/content") }.header("Range"))
        assertEquals("bytes=4-", fixture.requests.single { it.url.host == "download.example" }.header("Range"))
    }

    @Test fun ignoredRangeAndUnsatisfiableRangeCloseResponseAndRequireFullRestart() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val file = (backend.lookup(location, database) as OneDriveSourceResult.Available).value
        for (status in listOf(200, 416)) {
            var closed = false
            fixture.onRequest = { request ->
                if (request.url.host != "download.example") null else {
                    val source = object : ForwardingSource(Buffer().writeUtf8("database")) {
                        override fun close() { closed = true; super.close() }
                    }.buffer()
                    fixture.response(request, status).newBuilder().body(object : ResponseBody() {
                        override fun contentType() = null
                        override fun contentLength() = 8L
                        override fun source() = source
                    }).build()
                }
            }
            assertNull((backend.openRange(location, file, 4) as OneDriveSourceResult.Available).value)
            assertTrue(closed)
        }
    }

    @Test fun rangeRefusesWrongBoundsAndWrongTotal() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val file = (backend.lookup(location, database) as OneDriveSourceResult.Available).value
        for ((header, expected) in listOf(
            "bytes 0-7/8" to StorageErrorKind.CORRUPT_CONTENT,
            "bytes 4-6/8" to StorageErrorKind.CORRUPT_CONTENT,
            "bytes 4-7/9" to StorageErrorKind.VERSION_CONFLICT,
            "bytes 4-7/*" to StorageErrorKind.CORRUPT_CONTENT,
        )) {
            fixture.onRequest = { request -> if (request.url.host == "download.example") fixture.response(request, 206, "base", mapOf("Content-Range" to header)) else null }
            assertEquals(expected, failure(backend.openRange(location, file, 4)))
        }
    }

    @Test fun unknownLengthRangeStreamDetectsTruncationAndExcessBytes() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val file = (backend.lookup(location, database) as OneDriveSourceResult.Available).value
        for (bytes in listOf("ba", "base-extra")) {
            fixture.onRequest = { request -> if (request.url.host != "download.example") null else {
                fixture.response(request, 206, headers = mapOf("Content-Range" to "bytes 4-7/8")).newBuilder().body(object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = -1L
                    override fun source() = Buffer().writeUtf8(bytes)
                }).build()
            } }
            val stream = (backend.openRange(location, file, 4) as OneDriveSourceResult.Available).value!!
            try {
                stream.use { it.readBytes() }
                throw AssertionError("invalid suffix accepted")
            } catch (failure: OneDriveSourceException) {
                assertEquals(StorageErrorKind.CORRUPT_CONTENT, failure.kind)
            }
        }
    }

    @Test fun pushUploadsTheStagedFileOnlyIfTheBaseCTagStillMatches() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val staged = staged()
        val bodies = mutableListOf<String>()
        fixture.onRequest = { request ->
            if (request.method != "PUT") null else {
                bodies.add(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                uploaded(fixture, request, staged)
            }
        }
        val result = backend.replaceDatabase(location, staged, sha(staged), base)
        assertEquals(FileVersion(BackendKind.ONEDRIVE, "content-2"), (result as OneDriveSourceResult.Available).value)
        assertEquals(listOf("GET /v1.0/drives/drive/items/root:/metadata.db", "PUT /v1.0/drives/drive/items/db/content"),
            fixture.requests.map { "${it.method} ${it.url.encodedPath}" })
        val upload = fixture.requests.last()
        assertEquals("content-1", upload.header("If-Match"))
        assertEquals("Bearer old", upload.header("Authorization"))
        assertEquals(listOf("staged database"), bodies)
        assertEquals(0, fixture.contentReads)
    }

    @Test fun aChangedBaseIsAConflictWithoutUploading() = runTest {
        val fixture = Fixture()
        fixture.file("metadata.db", "db", "another writer", tag = "content-9")
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val staged = staged()
        assertNull((backend.replaceDatabase(location, staged, sha(staged), base) as OneDriveSourceResult.Available).value)
        assertEquals(listOf("GET"), fixture.requests.map { it.method })
    }

    @Test fun aRejectedPreconditionOrReplacedItemIsAConflict() = runTest {
        for (code in listOf(412, 409, 404)) {
            val fixture = Fixture()
            val backend = backend(fixture, StandardTestDispatcher(testScheduler))
            fixture.onRequest = { request -> if (request.method == "PUT") fixture.response(request, code) else null }
            val staged = staged()
            assertNull("status $code", (backend.replaceDatabase(location, staged, sha(staged), base) as OneDriveSourceResult.Available).value)
            assertEquals(listOf("GET", "PUT"), fixture.requests.map { it.method })
        }
    }

    @Test fun anUnknownUploadResultIsTransientSoTheWholeRoundRunsAgain() = runTest {
        val cases = listOf<Triple<String, (Fixture, Request, File) -> Response, Pair<StorageErrorKind, Long?>>>(
            Triple("429", { f, r, _ -> f.response(r, 429, headers = mapOf("Retry-After" to "7")) }, StorageErrorKind.THROTTLED to 7_000L),
            Triple("503", { f, r, _ -> f.response(r, 503) }, StorageErrorKind.THROTTLED to 30_000L),
            Triple("interrupted", { _, _, _ -> throw IOException("connection reset") }, StorageErrorKind.NO_NETWORK to 30_000L),
            Triple("no cTag", { f, r, file -> f.json(r, uploadedItem(f, file) - "cTag") }, StorageErrorKind.UNSUPPORTED_OPERATION to null),
            Triple("size", { f, r, file -> f.json(r, uploadedItem(f, file) + ("size" to file.length() - 1)) }, StorageErrorKind.UNSUPPORTED_OPERATION to null),
            Triple("item", { f, r, file -> f.json(r, uploadedItem(f, file) + ("id" to "other")) }, StorageErrorKind.UNSUPPORTED_OPERATION to null),
            Triple("not json", { f, r, _ -> f.response(r, 200, "not json") }, StorageErrorKind.UNSUPPORTED_OPERATION to null),
        )
        for ((name, respond, expected) in cases) {
            val fixture = Fixture()
            val backend = backend(fixture, StandardTestDispatcher(testScheduler))
            val staged = staged()
            fixture.onRequest = { request -> if (request.method == "PUT") respond(fixture, request, staged) else null }
            val failure = backend.replaceDatabase(location, staged, sha(staged), base) as OneDriveSourceResult.Failed
            assertEquals(name, expected.first, failure.error.kind)
            assertTrue(name, failure.transient)
            assertEquals(name, expected.second, failure.retryDelayMillis)
            assertEquals(name, listOf("GET", "PUT"), fixture.requests.map { it.method })
        }
    }

    @Test fun anExpiredTokenIsRefreshedOnceAndTheSameUploadSentAgain() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val staged = staged()
        val bodies = mutableListOf<String>()
        fixture.onRequest = { request ->
            if (request.method != "PUT") null else {
                bodies.add(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                if (request.header("Authorization") == "Bearer old") fixture.response(request, 401) else uploaded(fixture, request, staged)
            }
        }
        assertTrue(backend.replaceDatabase(location, staged, sha(staged), base) is OneDriveSourceResult.Available)
        assertEquals(listOf("staged database", "staged database"), bodies)
        assertEquals(listOf("content-1", "content-1"), fixture.requests.filter { it.method == "PUT" }.map { it.header("If-Match") })

        val denied = Fixture()
        val deniedBackend = backend(denied, StandardTestDispatcher(testScheduler))
        denied.onRequest = { request -> if (request.method == "PUT") denied.response(request, 403) else null }
        val failure = deniedBackend.replaceDatabase(location, staged, sha(staged), base) as OneDriveSourceResult.Failed
        assertEquals(StorageErrorKind.AUTHORIZATION_EXPIRED, failure.error.kind)
        assertFalse(failure.transient)
    }

    @Test fun nothingIsRequestedForAChangedStagedFileAnOversizedDatabaseOrAnotherAccount() = runTest {
        val fixture = Fixture()
        val backend = backend(fixture, StandardTestDispatcher(testScheduler))
        val staged = staged()
        val digest = backend.replaceDatabase(location, staged, "0".repeat(64), base) as OneDriveSourceResult.Failed
        assertEquals(StorageErrorKind.LOCAL_IO, digest.error.kind)
        assertTrue(digest.transient)

        val oversized = temporary.newFile().also { RandomAccessFile(it, "rw").use { file -> file.setLength(250L * 1024 * 1024 + 1) } }
        val size = backend.replaceDatabase(location, oversized, "unused", base) as OneDriveSourceResult.Failed
        assertEquals(StorageErrorKind.UNSUPPORTED_OPERATION, size.error.kind)
        assertFalse(size.transient)

        fixture.subject = "another-subject"
        assertEquals(StorageErrorKind.LOGIN_REQUIRED, failure(backend.replaceDatabase(location, staged, sha(staged), base)))
        assertEquals(emptyList<Request>(), fixture.requests)
    }

    @Test fun writeCapabilityComparesTheSignedInAccountWithoutRequests() = runTest {
        val fixture = Fixture()
        val source = OneDriveLibrarySource(backend(fixture, StandardTestDispatcher(testScheduler)))
        assertNull(source.writeCapability(location))
        fixture.subject = "another-subject"
        assertEquals(WriteBlock.AUTHORIZATION_REQUIRED, source.writeCapability(location))
        fixture.subject = null
        assertEquals(WriteBlock.AUTHORIZATION_REQUIRED, source.writeCapability(location))
        assertEquals(emptyList<Request>(), fixture.requests)
    }

    /** Two rounds as the write handler runs them: the rejected push is followed by a new snapshot. */
    @Test fun aConflictRoundFetchesTheLatestDatabaseBeforePushingAgain() = runTest {
        val fixture = Fixture()
        val source = OneDriveLibrarySource(backend(fixture, StandardTestDispatcher(testScheduler)))
        val journal = object : PushJournal {
            override suspend fun read(): String? = null
            override suspend fun write(value: String?) = throw AssertionError("OneDrive keeps no journal")
        }
        val first = requireNotNull(source.acquireSnapshot(location, UUID.randomUUID(), null) {})
        fixture.onRequest = { request ->
            if (request.method != "PUT") null
            else if (request.header("If-Match") == "content-1") {
                // Another writer replaced metadata.db after this round's snapshot.
                fixture.file("metadata.db", "db", "another writer", tag = "content-2")
                fixture.response(request, 412)
            } else fixture.json(request, uploadedItem(fixture, staged(), "content-3"))
        }
        val staged = staged()
        assertEquals(PushOutcome.Conflict, source.pushDatabase(location, staged, sha(staged), first.version, journal))
        fixture.requests.clear()
        source.finishPendingPush(location, journal)
        val second = requireNotNull(source.acquireSnapshot(location, UUID.randomUUID(), null) {})
        assertEquals("another writer", second.file.readText())
        assertEquals(PushOutcome.Pushed(FileVersion(BackendKind.ONEDRIVE, "content-3")),
            source.pushDatabase(location, staged, sha(staged), second.version, journal))
        assertEquals(listOf("GET metadata.db", "GET metadata.db-wal", "GET metadata.db-journal", "GET download",
            "GET metadata.db", "PUT content"), fixture.requests.map { request ->
                "${request.method} ${if (request.url.host == "download.example") "download" else request.url.pathSegments.last()}"
            })
        assertEquals("content-2", fixture.requests.last().header("If-Match"))

        fixture.onRequest = { request -> if (request.method == "PUT") fixture.response(request, 500) else null }
        val failure = try { source.pushDatabase(location, staged, sha(staged), second.version, journal); null } catch (error: SourceFailure) { error }
        assertEquals(StorageErrorKind.THROTTLED, failure!!.error.kind)
        assertTrue(failure.transient)
    }

    private val base = FileVersion(BackendKind.ONEDRIVE, "content-1")
    private fun staged() = temporary.newFile().apply { writeText("staged database") }
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun uploadedItem(fixture: Fixture, file: File, tag: String = "content-2") =
        fixture.item("db", "metadata.db", "parent-of-db", false, tag = tag, size = file.length())
    private fun uploaded(fixture: Fixture, request: Request, file: File) = fixture.json(request, uploadedItem(fixture, file))

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
