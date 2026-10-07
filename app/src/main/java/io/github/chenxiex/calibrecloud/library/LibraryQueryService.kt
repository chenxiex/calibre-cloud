package io.github.chenxiex.calibrecloud.library

import io.github.chenxiex.calibrecloud.metadata.LibraryRevision
import io.github.chenxiex.calibrecloud.metadata.MetadataRepository
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.api.DownloadedCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Source of the latest complete import; replaceable in tests. */
interface LibraryImports {
    suspend fun currentRevision(): LibraryRevision?
    suspend fun currentIndex(): LibraryIndex?
}

/** Complete copies of one library from the minimal manifest. */
fun interface LibraryCopies {
    suspend fun list(libraryId: LibraryId): List<DownloadedCopy>
}

/**
 * Local-only library queries. Reads the last valid import and the downloaded-copy manifest; never
 * touches a storage backend, the network or the task queue, and never implies a complete library
 * from the manifest. The index is rebuilt in the background when the import generation, library or
 * read column changes, so a replaced import never serves old rows and a [LibraryRequest.expected]
 * that no longer matches yields [LibraryQueryResult.Stale].
 */
class LibraryQueryService(
    private val imports: LibraryImports,
    private val copies: LibraryCopies,
    private val compute: CoroutineDispatcher,
) {
    private val lock = Mutex()
    private var cached: LibraryIndex? = null

    suspend fun query(request: LibraryRequest): LibraryQueryResult = withContext(compute) {
        val index = index() ?: return@withContext LibraryQueryResult.Unavailable(LibraryProblem.NO_METADATA)
        index.query(request, copies.list(index.revision.identity.id))
    }

    /** Null when there is no complete import. */
    suspend fun overview(): LibraryOverview? = withContext(compute) {
        index()?.let { LibraryOverview(it.revision, it.importedAt, it.categoryColumns, it.readFilterAvailable, it.formats) }
    }

    private suspend fun index(): LibraryIndex? = lock.withLock {
        val revision = imports.currentRevision() ?: return@withLock null.also { cached = null }
        cached?.takeIf { it.revision == revision }?.let { return@withLock it }
        // The payload read may observe a newer import than the revision; the index records its own.
        imports.currentIndex().also { cached = it }
    }
}

class MetadataLibraryImports(private val metadata: MetadataRepository) : LibraryImports {
    override suspend fun currentRevision() = metadata.currentRevision()
    override suspend fun currentIndex() = metadata.currentImport()?.let(::LibraryIndex)
}

/** Pages through the bounded manifest listing. */
class StateLibraryCopies(private val state: ApplicationStateRepository) : LibraryCopies {
    override suspend fun list(libraryId: LibraryId): List<DownloadedCopy> = buildList {
        while (true) {
            val page = state.listCopies(libraryId, PAGE, size)
            addAll(page)
            if (page.size < PAGE) break
        }
    }

    private companion object { const val PAGE = 200 }
}
