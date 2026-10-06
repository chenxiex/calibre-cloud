package io.github.chenxiex.calibrecloud.storage.onedrive

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.LibraryLocation
import io.github.chenxiex.calibrecloud.model.RelativeSourcePath
import io.github.chenxiex.calibrecloud.model.SourceFileLocator
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.local.SnapshotValidator
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.FilterInputStream
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

sealed interface OneDriveSourceResult<out T> {
    data class Available<T>(val value: T) : OneDriveSourceResult<T>
    data class Failed(
        val error: StorageError,
        val retryDelayMillis: Long? = null,
        val transient: Boolean = false,
    ) : OneDriveSourceResult<Nothing>
}

data class OneDriveItem(val id: String, val name: String, val directory: Boolean, val parentId: String?, val cTag: String?, val sizeBytes: Long?)
data class OneDriveIdentity(val accountId: String, val driveId: String, val root: OneDriveItem)
data class OneDriveDirectoryPage(val items: List<OneDriveItem>, val nextPageUrl: String?, val parentName: String? = null)
data class OneDriveSourceFile(val locator: SourceFileLocator.OneDrive, val version: FileVersion, val sizeBytes: Long? = null)
data class OneDriveDatabaseSnapshot(val file: File, val version: FileVersion)

/** Safe failure only: exception messages never contain HTTP bodies, tokens or download URLs. */
class OneDriveSourceException(
    val kind: StorageErrorKind,
    val retryDelayMillis: Long? = null,
    val transient: Boolean = false,
    val reason: String = "source_failure",
) : IOException()

/**
 * Read-only personal drive access, invoked exclusively by explicit source task handlers.
 * Each operation verifies /me/drive identity; remote items and other drive types are refused.
 * The shared facet alone describes sharing of an owned item and does not change its drive boundary.
 * Item IDs are resolved component by component, checking each immediate parent and drive.
 * Graph cTag is content evidence; eTag and timestamps are deliberately not used as file versions.
 * Content redirects use a separate unauthenticated client and HTTPS, at most five hops.
 * Transient failures expose Retry-After to the persistent scheduler; this backend never sleeps.
 */
class OneDriveSourceBackend(
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
    private val snapshotsDirectory: File,
    private val validator: SnapshotValidator,
    private val ioDispatcher: CoroutineDispatcher,
    client: OkHttpClient = OkHttpClient(),
    private val decoder: GraphJsonDecoder = AndroidGraphJsonDecoder,
    contentClient: OkHttpClient = OkHttpClient(),
    private val diagnostic: (String) -> Unit = {},
    private val accountProvider: suspend () -> String? = { null },
) {
    private val graphClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val contentClient = contentClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    // No app interceptors, authenticators or cookie jar can attach Graph credentials to a download.
    private val graphBase = "https://graph.microsoft.com/v1.0/".toHttpUrl()
    private val itemFields = "id,name,folder,file,parentReference,cTag,size,remoteItem,shared,deleted,package"

    suspend fun discover(): OneDriveSourceResult<OneDriveIdentity> = operation { discoverInternal() }

    suspend fun listDirectories(
        location: LibraryLocation.OneDrive,
        parentItemId: String = location.rootItemId,
        pageUrl: String? = null,
    ): OneDriveSourceResult<OneDriveDirectoryPage> = operation {
        verifyIdentity(location)
        val parent = requireDescendant(location, parentItemId)
        val page = childrenPage(location, parentItemId, pageUrl, directoriesOnly = true)
        OneDriveDirectoryPage(page.items, page.nextPageUrl, parent.name)
    }

    /**
     * Loads the complete supported directory list for one browse task. Graph may split the
     * response across nextLink pages; identity and ancestry are checked once for the load.
     * Callers retain this result and paginate locally without requesting the source again.
     */
    suspend fun listAllDirectories(
        location: LibraryLocation.OneDrive,
        parentItemId: String = location.rootItemId,
        checkControl: suspend () -> Unit = {},
    ): OneDriveSourceResult<OneDriveDirectoryPage> = operation {
        checkControl()
        verifyIdentity(location)
        checkControl()
        val parent = requireDescendant(location, parentItemId)
        val directories = allChildren(location, parentItemId, checkControl, directoriesOnly = true)
        OneDriveDirectoryPage(directories, null, parent.name)
    }

    suspend fun resolve(location: LibraryLocation.OneDrive, path: RelativeSourcePath): OneDriveSourceResult<OneDriveSourceFile> = operation {
        verifyIdentity(location)
        val item = resolveItem(location, path)
        OneDriveSourceFile(SourceFileLocator.OneDrive(location.driveId, item.id), versionOf(item), item.sizeBytes)
    }

    /** Caller owns the response stream and closes it; stream reads remain off the UI thread. */
    suspend fun openRead(location: LibraryLocation.OneDrive, path: RelativeSourcePath): OneDriveSourceResult<InputStream> = operation {
        verifyIdentity(location)
        openContent(location, resolveItem(location, path).id)
    }

    /** Requests only the suffix from a freshly resolved, unauthenticated content URL. */
    suspend fun openRange(location: LibraryLocation.OneDrive, path: RelativeSourcePath, offset: Long, expectedVersion: FileVersion): OneDriveSourceResult<InputStream?> = operation {
        require(offset > 0 && expectedVersion.backend == BackendKind.ONEDRIVE)
        verifyIdentity(location)
        val item = resolveItem(location, path)
        if (versionOf(item) != expectedVersion) conflict()
        val length = item.sizeBytes ?: return@operation null
        if (offset >= length) return@operation null
        val response = contentResponse(location, item.id, offset)
        try {
            // Providers can ignore Range. Never append a full response to the retained prefix.
            if (response.code == 200 || response.code == 416) {
                response.close()
                return@operation null
            }
            checkResponse(response)
            if (response.code != 206) unsupported("range_status")
            val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range") ?: "")
                ?: throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT, reason = "range_header")
            val start = match.groupValues[1].toLongOrNull()
            val end = match.groupValues[2].toLongOrNull()
            val total = match.groupValues[3].toLongOrNull()
            if (total != length) conflict()
            if (start != offset || end != length - 1) throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT, reason = "range_bounds")
            if (response.header("Content-Encoding")?.let { it != "identity" } == true) unsupported("range_encoding")
            val body = response.body ?: unsupported()
            val suffixLength = length - offset
            if (body.contentLength() >= 0 && body.contentLength() != suffixLength) {
                throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT, reason = "range_length")
            }
            return@operation object : FilterInputStream(body.byteStream()) {
                private var remaining = suffixLength
                override fun read(): Int {
                    val value = `in`.read()
                    account(if (value < 0) -1 else 1)
                    return value
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    val count = `in`.read(buffer, offset, length)
                    account(count)
                    return count
                }
                private fun account(count: Int) {
                    if (count < 0) {
                        if (remaining != 0L) throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT, reason = "range_truncated")
                    } else {
                        remaining -= count
                        if (remaining < 0) throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT, reason = "range_overflow")
                    }
                }
                override fun close() { response.close() }
            }
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }

    suspend fun version(location: LibraryLocation.OneDrive, path: RelativeSourcePath): OneDriveSourceResult<FileVersion> = operation {
        verifyIdentity(location)
        versionOf(resolveItem(location, path))
    }

    suspend fun acquireSnapshot(
        location: LibraryLocation.OneDrive,
        candidateId: UUID,
        checkControl: suspend () -> Unit = {},
    ): OneDriveSourceResult<OneDriveDatabaseSnapshot> = operation {
        verifyIdentity(location)
        val directory = File(snapshotsDirectory, candidateId.toString())
        if (!directory.isDirectory && !directory.mkdirs()) throw LocalFailure()
        val generation = UUID.randomUUID().toString()
        val staging = File(directory, "$generation.part")
        val published = File(directory, "$generation.db")
        try {
            checkControl()
            checkLogs(location, checkControl)
            val original = resolveItem(location, RelativeSourcePath("metadata.db"), checkControl)
            val originalVersion = versionOf(original)
            val firstHash = openContent(location, original.id).use { input ->
                localIo { staging.outputStream() }.use { output ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkControl()
                        val count = input.read(buffer)
                        if (count < 0) break
                        localIo { output.write(buffer, 0, count) }
                        digest.update(buffer, 0, count)
                    }
                    localIo { output.flush(); output.fd.sync() }
                    digest.digest()
                }
            }
            checkLogs(location, checkControl)
            val refreshed = resolveItem(location, RelativeSourcePath("metadata.db"), checkControl)
            if (original.id != refreshed.id || originalVersion != versionOf(refreshed)) conflict()
            val secondHash = hash(openContent(location, refreshed.id), checkControl)
            checkLogs(location, checkControl)
            val finalItem = resolveItem(location, RelativeSourcePath("metadata.db"), checkControl)
            if (original.id != finalItem.id || originalVersion != versionOf(finalItem) || !firstHash.contentEquals(secondHash)) conflict()
            if (original.sizeBytes != null && original.sizeBytes != staging.length()) {
                throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT)
            }
            if (!validator.validate(staging)) throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT)
            currentCoroutineContext().ensureActive()
            checkControl()
            if (!staging.renameTo(published)) throw LocalFailure()
            OneDriveDatabaseSnapshot(published, originalVersion)
        } finally {
            staging.delete()
        }
    }

    private suspend fun discoverInternal(): OneDriveIdentity {
        val drive = json(url("me", "drive").newBuilder().addQueryParameter("\$select", "id,driveType,owner").build())
        if (drive["driveType"] != "personal") unsupported("drive_type")
        // owner.user.id is optional in real personal-drive responses. The authenticated
        // subject remains the same even when Graph changes its owner representation.
        val subject = accountProvider()?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        val owner = (drive["owner"] as? Map<*, *>)?.get("user") as? Map<*, *>
        val account = subject?.let { "microsoft-consumers:$it" }
            ?: (owner?.get("id") as? String)?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            ?: throw OneDriveSourceException(StorageErrorKind.LOGIN_REQUIRED, reason = "account_identity_unavailable")
        val driveId = drive.string("id", "drive_id")
        val root = item(json(url("drives", driveId, "root").newBuilder().addQueryParameter("\$select", itemFields).build()), driveId)
        if (!root.directory) unsupported("root_kind")
        return OneDriveIdentity(account, driveId, root)
    }

    private suspend fun verifyIdentity(location: LibraryLocation.OneDrive) {
        val identity = discoverInternal()
        if (identity.accountId != location.accountId || identity.driveId != location.driveId) {
            throw OneDriveSourceException(StorageErrorKind.LOGIN_REQUIRED)
        }
    }

    private suspend fun requireDescendant(location: LibraryLocation.OneDrive, itemId: String): OneDriveItem {
        var current = itemId
        var requested: OneDriveItem? = null
        val seen = mutableSetOf<String>()
        while (true) {
            if (!seen.add(current) || seen.size > 256) unsupported()
            val value = getItem(location, current)
            if (!value.directory) unsupported()
            if (requested == null) requested = value
            if (current == location.rootItemId) return requested
            current = value.parentId ?: unsupported()
        }
    }

    private suspend fun resolveItem(location: LibraryLocation.OneDrive, path: RelativeSourcePath, control: suspend () -> Unit = {}): OneDriveItem {
        control()
        var parent = getItem(location, location.rootItemId)
        if (!parent.directory) unsupported()
        val segments = path.value.split('/')
        segments.forEachIndexed { index, name ->
            control()
            val matches = allChildren(location, parent.id, control).filter { it.name == name }
            if (matches.isEmpty()) throw OneDriveSourceException(StorageErrorKind.SOURCE_MISSING)
            if (matches.size != 1) unsupported()
            parent = matches.single()
            if (index < segments.lastIndex && !parent.directory) throw OneDriveSourceException(StorageErrorKind.SOURCE_MISSING)
        }
        if (parent.directory) throw OneDriveSourceException(StorageErrorKind.SOURCE_MISSING)
        return parent
    }

    private suspend fun getItem(location: LibraryLocation.OneDrive, id: String) = item(
        json(url("drives", location.driveId, "items", id).newBuilder().addQueryParameter("\$select", itemFields).build()), location.driveId,
    ).also { if (it.id != id) unsupported() }

    private suspend fun childrenPage(
        location: LibraryLocation.OneDrive,
        parent: String,
        next: String?,
        directoriesOnly: Boolean = false,
    ): OneDriveDirectoryPage {
        val base = url("drives", location.driveId, "items", parent, "children")
        val target = if (next == null) base.newBuilder().addQueryParameter("\$select", itemFields).build()
            else safeGraphUrl(next).also { if (it.encodedPath != base.encodedPath) unsupported() }
        val response = json(target)
        val items = (response["value"] as? List<*>)?.mapNotNull {
            @Suppress("UNCHECKED_CAST")
            val raw = it as? Map<String, Any?> ?: unsupported()
            // Unsupported siblings are irrelevant to the directory picker. Source resolution
            // and snapshot checks keep their strict parsing of every child in the source tree.
            if (directoriesOnly && (raw["folder"] !is Map<*, *> || raw["remoteItem"] != null ||
                raw["deleted"] != null || raw["package"] != null)) return@mapNotNull null
            item(raw, location.driveId).also { value ->
                if (value.parentId != parent) unsupported()
            }
        } ?: unsupported()
        val nextUrl = response["@odata.nextLink"] as? String
        if (nextUrl != null && safeGraphUrl(nextUrl).encodedPath != base.encodedPath) unsupported()
        return OneDriveDirectoryPage(items, nextUrl)
    }

    private suspend fun allChildren(
        location: LibraryLocation.OneDrive,
        parent: String,
        control: suspend () -> Unit = {},
        directoriesOnly: Boolean = false,
    ): List<OneDriveItem> {
        val values = mutableListOf<OneDriveItem>()
        val seen = mutableSetOf<String>()
        var next: String? = null
        do {
            currentCoroutineContext().ensureActive()
            control()
            val page = childrenPage(location, parent, next, directoriesOnly)
            control()
            values.addAll(page.items)
            next = page.nextPageUrl
            if (next != null && !seen.add(next)) unsupported()
        } while (next != null)
        if (values.map { it.id }.toSet().size != values.size) unsupported()
        return values
    }

    private suspend fun checkLogs(location: LibraryLocation.OneDrive, control: suspend () -> Unit) {
        control()
        val logs = allChildren(location, location.rootItemId, control).filter {
            it.name in setOf("metadata.db-wal", "metadata.db-journal", "metadata.db-shm") || it.name.startsWith("metadata.db-mj")
        }
        for (log in logs) {
            control()
            if (log.directory) unsupported()
            if (log.name == "metadata.db-shm" || openContent(location, log.id).use { it.read() != -1 }) conflict()
        }
    }

    private fun item(value: Map<String, Any?>, driveId: String): OneDriveItem {
        if (value["remoteItem"] != null || value["deleted"] != null) unsupported("remote_deleted_item")
        if (value["package"] != null) unsupported("package_item")
        val parent = value["parentReference"] as? Map<*, *>
        if (parent != null && parent["driveId"] != driveId) unsupported("parent_drive")
        val folder = value["folder"] is Map<*, *>
        if (!folder && value["file"] !is Map<*, *>) unsupported("item_kind")
        return OneDriveItem(value.string("id"), value.string("name"), folder, parent?.get("id") as? String,
            (value["cTag"] as? String)?.takeIf { it.isNotBlank() }, (value["size"] as? Number)?.toLong()?.takeIf { it >= 0 })
    }

    private fun versionOf(item: OneDriveItem) = FileVersion(BackendKind.ONEDRIVE, item.cTag ?: unsupported())

    private suspend fun json(target: HttpUrl): Map<String, Any?> = graph(target).use { response ->
        checkResponse(response)
        val body = response.body ?: unsupported()
        // Bound metadata responses; content streams are separately unbounded and never decoded here.
        val source = body.source()
        if (source.request(2_000_001) && source.buffer.size > 2_000_000) unsupported()
        try { decoder.decode(source.readUtf8()) } catch (_: Exception) { unsupported() }
    }

    private suspend fun graph(target: HttpUrl): Response {
        safeGraphUrl(target.toString())
        for (attempt in 0..1) {
            currentCoroutineContext().ensureActive()
            val token = tokenProvider(attempt == 1)?.takeIf { it.isNotBlank() }
                ?: throw OneDriveSourceException(StorageErrorKind.LOGIN_REQUIRED)
            val response = graphClient.newCall(Request.Builder().url(target).header("Authorization", "Bearer $token").build()).execute()
            if (response.code != 401 || attempt == 1) return response
            response.close()
        }
        throw OneDriveSourceException(StorageErrorKind.LOGIN_REQUIRED)
    }

    private suspend fun openContent(location: LibraryLocation.OneDrive, itemId: String): InputStream {
        val response = contentResponse(location, itemId)
        try {
            checkResponse(response)
            return object : FilterInputStream(response.body?.byteStream() ?: unsupported()) {
                override fun close() { response.close() }
            }
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }

    private suspend fun contentResponse(location: LibraryLocation.OneDrive, itemId: String, offset: Long? = null): Response {
        // Graph /content resolves the URL; Range belongs to the redirected content request only.
        var response = graph(url("drives", location.driveId, "items", itemId, "content"))
        try {
            var hops = 0
            while (response.code in setOf(301, 302, 303, 307, 308)) {
                if (++hops > 5) unsupported()
                val target = response.header("Location")?.let { response.request.url.resolve(it) } ?: unsupported()
                if (!target.isHttps || target.username.isNotEmpty() || target.password.isNotEmpty()) unsupported()
                response.close()
                currentCoroutineContext().ensureActive()
                val request = Request.Builder().url(target).apply {
                    if (offset != null) header("Range", "bytes=$offset-").header("Accept-Encoding", "identity")
                }.build()
                response = contentClient.newCall(request).execute()
            }
            return response
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }

    private fun checkResponse(response: Response) {
        if (response.isSuccessful) return
        val code = response.code
        val kind = when (code) {
            401 -> StorageErrorKind.LOGIN_REQUIRED
            403 -> StorageErrorKind.AUTHORIZATION_EXPIRED
            404, 410 -> StorageErrorKind.SOURCE_MISSING
            409, 412 -> StorageErrorKind.VERSION_CONFLICT
            429 -> StorageErrorKind.NO_NETWORK
            in 500..599 -> StorageErrorKind.NO_NETWORK
            else -> StorageErrorKind.UNSUPPORTED_OPERATION
        }
        val transient = code == 429 || code in 500..599
        throw OneDriveSourceException(kind, if (transient) retryDelay(response.header("Retry-After")) ?: 30_000 else null, transient, "http_status_$code")
    }

    private fun retryDelay(value: String?): Long? {
        if (value == null) return null
        value.toLongOrNull()?.let { if (it >= 0) return if (it > Long.MAX_VALUE / 1000) Long.MAX_VALUE else it * 1000 }
        return try {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
        } catch (_: Exception) { null }
    }

    private fun safeGraphUrl(value: String): HttpUrl {
        val target = try { value.toHttpUrl() } catch (_: IllegalArgumentException) { unsupported() }
        if (!target.isHttps || target.host != graphBase.host || target.port != 443 ||
            target.username.isNotEmpty() || target.password.isNotEmpty() || target.fragment != null ||
            !target.encodedPath.startsWith("/v1.0/")) unsupported()
        return target
    }

    private fun url(vararg segments: String): HttpUrl = graphBase.newBuilder().apply {
        segments.forEach {
            // Preserve opaque IDs as one endpoint segment, without path normalization.
            if (it.isBlank() || it == "." || it == ".." || it.any(Char::isISOControl) || '/' in it || '\\' in it) unsupported()
            addPathSegment(it)
        }
    }.build()

    private suspend fun hash(input: InputStream, control: suspend () -> Unit): ByteArray = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            control()
            val count = it.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest()
    }

    private inline fun <T> localIo(block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        val noSpace = generateSequence<Throwable>(error) { it.cause }.any {
            it is android.system.ErrnoException && it.errno == android.system.OsConstants.ENOSPC
        }
        throw OneDriveSourceException(if (noSpace) StorageErrorKind.INSUFFICIENT_SPACE else StorageErrorKind.LOCAL_IO)
    }

    private class LocalFailure : IOException()
    private fun unsupported(reason: String = "unsupported_shape"): Nothing = throw OneDriveSourceException(StorageErrorKind.UNSUPPORTED_OPERATION, reason = reason)
    private fun conflict(): Nothing = throw OneDriveSourceException(StorageErrorKind.VERSION_CONFLICT)
    private fun Map<String, Any?>.string(key: String, reason: String = "string_$key"): String = (get(key) as? String)?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: unsupported(reason)
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(key: String): Map<String, Any?> = get(key) as? Map<String, Any?> ?: unsupported("object_$key")

    private suspend fun <T> operation(block: suspend () -> T): OneDriveSourceResult<T> = withContext(ioDispatcher) {
        try {
            OneDriveSourceResult.Available(block())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: OneDriveSourceException) {
            diagnostic(error.reason)
            OneDriveSourceResult.Failed(StorageError(error.kind), error.retryDelayMillis, error.transient)
        } catch (_: LocalFailure) {
            OneDriveSourceResult.Failed(StorageError(StorageErrorKind.LOCAL_IO))
        } catch (_: IOException) {
            OneDriveSourceResult.Failed(StorageError(StorageErrorKind.NO_NETWORK), 30_000, true)
        } catch (_: SecurityException) {
            OneDriveSourceResult.Failed(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED))
        }
    }
}
