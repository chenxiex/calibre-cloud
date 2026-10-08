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
data class OneDriveDatabaseSnapshot(val file: File, val version: FileVersion)

/**
 * One path lookup of a source file. The pre-signed download URL is used only by this task's
 * immediate reads: it is never persisted, logged or exposed, and toString omits it.
 */
class OneDriveSourceFile internal constructor(
    val itemId: String,
    val version: FileVersion,
    val sizeBytes: Long?,
    internal val downloadUrl: HttpUrl?,
) {
    override fun toString() = "OneDriveSourceFile(redacted)"
}

/** A cover stream together with the cTag of the cover image it was selected from. */
class OneDriveCover(val stream: InputStream, val version: FileVersion)

/** Safe failure only: exception messages never contain HTTP bodies, tokens or download URLs. */
class OneDriveSourceException(
    val kind: StorageErrorKind,
    val retryDelayMillis: Long? = null,
    val transient: Boolean = false,
    val reason: String = "source_failure",
) : IOException()

/**
 * Read-only personal drive access, invoked exclusively by explicit source task handlers.
 * Identity is established by discover at login and directory selection; each later operation only
 * compares the signed-in subject with the stored account locally. Source files are addressed by
 * path below the stored root item, one Graph request per lookup; only the directory picker lists
 * children. Remote items and other drive types are refused. The shared facet alone describes
 * sharing of an owned item and does not change its drive boundary.
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

    /**
     * Loads the complete supported directory list for one browse task. Graph may split the
     * response across nextLink pages; identity and ancestry are checked once for the load.
     * Callers retain this result and paginate locally without requesting the source again.
     * This picker is the only operation that lists children.
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
        val directories = allChildren(location, parentItemId, checkControl)
        OneDriveDirectoryPage(directories, null, parent.name)
    }

    /** One Graph request: cTag, size and an in-memory download URL for the file at [path]. */
    suspend fun lookup(location: LibraryLocation.OneDrive, path: RelativeSourcePath): OneDriveSourceResult<OneDriveSourceFile> = operation {
        verifyIdentity(location)
        sourceFile(location, json(pathUrl(location, path)))
    }

    suspend fun version(location: LibraryLocation.OneDrive, path: RelativeSourcePath): OneDriveSourceResult<FileVersion> = operation {
        verifyIdentity(location)
        sourceFile(location, json(pathUrl(location, path))).version
    }

    /** Reads the looked-up file without another Graph request when its download URL is available. Caller closes the stream. */
    suspend fun open(location: LibraryLocation.OneDrive, file: OneDriveSourceFile): OneDriveSourceResult<InputStream> = operation {
        responseStream(contentResponse(location, file))
    }

    /**
     * Reads thumbnails of the exact source cover image, never thumbnails of a book format. One Graph
     * request returns the image item with its thumbnail sets.
     * Prefers the smallest image meeting both target dimensions, otherwise the largest available.
     * Missing or unusable thumbnails fall back to that same image's original content. Authorization,
     * network and throttling failures remain scheduler failures. The caller owns and decodes the stream.
     * The optional callback exposes only dimensions of a successfully opened thumbnail for acceptance.
     */
    suspend fun openCover(
        location: LibraryLocation.OneDrive,
        path: RelativeSourcePath,
        targetWidth: Int,
        targetHeight: Int,
        thumbnailSelected: (width: Int, height: Int) -> Unit = { _, _ -> },
    ): OneDriveSourceResult<OneDriveCover> = operation {
        require(targetWidth > 0 && targetHeight > 0)
        verifyIdentity(location)
        val raw = json(pathUrl(location, path).newBuilder().addQueryParameter("\$expand", "thumbnails").build())
        val source = sourceFile(location, raw)
        val sets = raw["thumbnails"] as? List<*> ?: emptyList<Any>()
        val images = sets.filterIsInstance<Map<*, *>>().flatMap { set ->
            listOf("small", "medium", "large").mapNotNull { size ->
                val value = set[size] as? Map<*, *> ?: return@mapNotNull null
                val width = (value["width"] as? Number)?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                val height = (value["height"] as? Number)?.toInt()?.takeIf { it > 0 } ?: return@mapNotNull null
                val target = (value["url"] as? String)?.let(::safeContentUrl) ?: return@mapNotNull null
                CoverThumbnail(width, height, target)
            }
        }
        val thumbnail = images.filter { it.width >= targetWidth && it.height >= targetHeight }
            .minByOrNull { it.width.toLong() * it.height }
            ?: images.maxByOrNull { it.width.toLong() * it.height }
        if (thumbnail != null) {
            val stream = try {
                currentCoroutineContext().ensureActive()
                val response = followContentRedirects(contentClient.newCall(Request.Builder().url(thumbnail.url).build()).execute())
                responseStream(response)
            } catch (failure: OneDriveSourceException) {
                if (failure.kind != StorageErrorKind.SOURCE_MISSING) throw failure
                null
            }
            if (stream != null) {
                try {
                    thumbnailSelected(thumbnail.width, thumbnail.height)
                    return@operation OneDriveCover(stream, source.version)
                } catch (failure: Throwable) {
                    stream.close()
                    throw failure
                }
            }
        }
        OneDriveCover(responseStream(contentResponse(location, source)), source.version)
    }

    private data class CoverThumbnail(val width: Int, val height: Int, val url: HttpUrl)

    /**
     * Requests only the suffix of the looked-up file from its unauthenticated content URL. The caller
     * compares the lookup's cTag with the retained prefix before calling.
     */
    suspend fun openRange(location: LibraryLocation.OneDrive, file: OneDriveSourceFile, offset: Long): OneDriveSourceResult<InputStream?> = operation {
        require(offset > 0)
        val length = file.sizeBytes ?: return@operation null
        if (offset >= length) return@operation null
        val response = contentResponse(location, file, offset)
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

    /**
     * Returns null without reading when metadata.db still has [unchangedVersion]. Otherwise transaction
     * logs are queried by name (a WAL index or super-journal is meaningful only beside them), the
     * database is read once, its length and private SQLite integrity are checked, and the snapshot
     * carries the cTag observed before the read. These observations do not guarantee arbitrary
     * concurrent source safety; a later sync observes any newer cTag.
     */
    suspend fun acquireSnapshot(
        location: LibraryLocation.OneDrive,
        candidateId: UUID,
        unchangedVersion: FileVersion? = null,
        checkControl: suspend () -> Unit = {},
    ): OneDriveSourceResult<OneDriveDatabaseSnapshot?> = operation {
        checkControl()
        verifyIdentity(location)
        val database = sourceFile(location, json(pathUrl(location, RelativeSourcePath("metadata.db"))))
        if (database.version == unchangedVersion) return@operation null
        for (name in listOf("metadata.db-wal", "metadata.db-journal")) {
            checkControl()
            val log = try {
                json(pathUrl(location, RelativeSourcePath(name)))
            } catch (failure: OneDriveSourceException) {
                if (failure.kind != StorageErrorKind.SOURCE_MISSING) throw failure
                null
            } ?: continue
            val value = item(log, location.driveId)
            if (value.directory || value.sizeBytes != 0L) conflict()
        }
        checkControl()
        val directory = File(snapshotsDirectory, candidateId.toString())
        if (!directory.isDirectory && !directory.mkdirs()) throw LocalFailure()
        val generation = UUID.randomUUID().toString()
        val staging = File(directory, "$generation.part")
        val published = File(directory, "$generation.db")
        try {
            responseStream(contentResponse(location, database)).use { input ->
                localIo { staging.outputStream() }.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        checkControl()
                        val count = input.read(buffer)
                        if (count < 0) break
                        localIo { output.write(buffer, 0, count) }
                    }
                    localIo { output.flush(); output.fd.sync() }
                }
            }
            if (database.sizeBytes != null && database.sizeBytes != staging.length()) {
                throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT)
            }
            if (!validator.validate(staging)) throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT)
            currentCoroutineContext().ensureActive()
            checkControl()
            if (!staging.renameTo(published)) throw LocalFailure()
            OneDriveDatabaseSnapshot(published, database.version)
        } finally {
            staging.delete()
        }
    }

    private suspend fun discoverInternal(): OneDriveIdentity {
        val drive = json(url("me", "drive").newBuilder().addQueryParameter("\$select", "id,driveType,owner").build())
        if (drive["driveType"] != "personal") unsupported("drive_type")
        // owner.user.id is optional in real personal-drive responses. The authenticated
        // subject remains the same even when Graph changes its owner representation.
        val owner = (drive["owner"] as? Map<*, *>)?.get("user") as? Map<*, *>
        val account = signedInAccount()
            ?: (owner?.get("id") as? String)?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
            ?: throw OneDriveSourceException(StorageErrorKind.LOGIN_REQUIRED, reason = "account_identity_unavailable")
        val driveId = drive.string("id", "drive_id")
        val root = item(json(url("drives", driveId, "root").newBuilder().addQueryParameter("\$select", itemFields).build()), driveId)
        if (!root.directory) unsupported("root_kind")
        return OneDriveIdentity(account, driveId, root)
    }

    private suspend fun signedInAccount(): String? =
        accountProvider()?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }?.let { "microsoft-consumers:$it" }

    /**
     * Local comparison with the signed-in subject; no request. Only a session without a stored subject
     * falls back to discovering the drive owner. A drive or root that the account can no longer reach
     * fails through the Graph status of the subsequent request.
     */
    private suspend fun verifyIdentity(location: LibraryLocation.OneDrive) {
        val account = signedInAccount() ?: discoverInternal().also {
            if (it.driveId != location.driveId) throw OneDriveSourceException(StorageErrorKind.LOGIN_REQUIRED)
        }.accountId
        if (account != location.accountId) throw OneDriveSourceException(StorageErrorKind.LOGIN_REQUIRED)
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

    private suspend fun getItem(location: LibraryLocation.OneDrive, id: String) = item(
        json(url("drives", location.driveId, "items", id).newBuilder().addQueryParameter("\$select", itemFields).build()), location.driveId,
    ).also { if (it.id != id) unsupported() }

    /** Directory picker only. Files and unsupported siblings are irrelevant to choosing a root. */
    private suspend fun allChildren(
        location: LibraryLocation.OneDrive,
        parent: String,
        control: suspend () -> Unit = {},
    ): List<OneDriveItem> {
        val base = url("drives", location.driveId, "items", parent, "children")
        val values = mutableListOf<OneDriveItem>()
        val seen = mutableSetOf<String>()
        var next: String? = null
        do {
            currentCoroutineContext().ensureActive()
            control()
            val target = if (next == null) base.newBuilder().addQueryParameter("\$select", itemFields).build()
                else safeGraphUrl(next).also { if (it.encodedPath != base.encodedPath) unsupported() }
            val response = json(target)
            control()
            (response["value"] as? List<*>)?.forEach {
                @Suppress("UNCHECKED_CAST")
                val raw = it as? Map<String, Any?> ?: unsupported()
                if (raw["folder"] !is Map<*, *> || raw["remoteItem"] != null || raw["deleted"] != null || raw["package"] != null) return@forEach
                values.add(item(raw, location.driveId).also { value -> if (value.parentId != parent) unsupported() })
            } ?: unsupported()
            next = response["@odata.nextLink"] as? String
            if (next != null && (safeGraphUrl(next).encodedPath != base.encodedPath || !seen.add(next))) unsupported()
        } while (next != null)
        if (values.map { it.id }.toSet().size != values.size) unsupported()
        return values
    }

    /**
     * Graph path addressing below the stored root item. Every component is percent-encoded per
     * RFC 3986, so characters such as '#', '%', '+' and spaces stay inside their segment;
     * RelativeSourcePath already excludes '..', ':' and empty components.
     */
    private fun pathUrl(location: LibraryLocation.OneDrive, path: RelativeSourcePath): HttpUrl =
        url("drives", location.driveId, "items").newBuilder().apply {
            addEncodedPathSegment(encodeSegment(checkSegment(location.rootItemId)) + ":")
            path.value.split('/').forEach { addEncodedPathSegment(encodeSegment(it)) }
        }.build()

    private fun encodeSegment(value: String): String = buildString {
        value.toByteArray(Charsets.UTF_8).forEach { byte ->
            val char = (byte.toInt() and 0xff).toChar()
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~") append(char)
            else append('%').append("%02X".format(byte.toInt() and 0xff))
        }
    }

    /** A path response must be a file inside the stored drive; a folder at a book path is missing. */
    private fun sourceFile(location: LibraryLocation.OneDrive, value: Map<String, Any?>): OneDriveSourceFile {
        val item = item(value, location.driveId)
        if (item.directory) throw OneDriveSourceException(StorageErrorKind.SOURCE_MISSING)
        return OneDriveSourceFile(item.id, versionOf(item), item.sizeBytes,
            (value["@microsoft.graph.downloadUrl"] as? String)?.let(::safeContentUrl))
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

    private fun responseStream(response: Response): InputStream {
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

    /**
     * The looked-up download URL needs no further Graph request. Without it, Graph /content resolves
     * the URL; Range belongs to the unauthenticated content request only.
     */
    private suspend fun contentResponse(location: LibraryLocation.OneDrive, file: OneDriveSourceFile, offset: Long? = null): Response {
        val direct = file.downloadUrl ?: return followContentRedirects(graph(url("drives", location.driveId, "items", file.itemId, "content")), offset)
        currentCoroutineContext().ensureActive()
        return followContentRedirects(contentClient.newCall(contentRequest(direct, offset)).execute(), offset)
    }

    private fun contentRequest(target: HttpUrl, offset: Long?) = Request.Builder().url(target).apply {
        if (offset != null) header("Range", "bytes=$offset-").header("Accept-Encoding", "identity")
    }.build()

    private fun safeContentUrl(value: String): HttpUrl? = try { value.toHttpUrl() } catch (_: IllegalArgumentException) { null }
        ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null }

    private suspend fun followContentRedirects(initial: Response, offset: Long? = null): Response {
        var response = initial
        try {
            var hops = 0
            while (response.code in setOf(301, 302, 303, 307, 308)) {
                if (++hops > 5) unsupported()
                val target = response.header("Location")?.let { response.request.url.resolve(it) } ?: unsupported()
                if (!target.isHttps || target.username.isNotEmpty() || target.password.isNotEmpty()) unsupported()
                response.close()
                currentCoroutineContext().ensureActive()
                response = contentClient.newCall(contentRequest(target, offset)).execute()
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
            429 -> StorageErrorKind.THROTTLED
            in 500..599 -> StorageErrorKind.THROTTLED
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
        segments.forEach { addPathSegment(checkSegment(it)) }
    }.build()

    /** Preserve opaque IDs as one endpoint segment, without path normalization. */
    private fun checkSegment(value: String): String {
        if (value.isBlank() || value == "." || value == ".." || value.any(Char::isISOControl) || '/' in value || '\\' in value) unsupported()
        return value
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
