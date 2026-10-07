package io.github.chenxiex.calibrecloud.library

import io.github.chenxiex.calibrecloud.metadata.*
import io.github.chenxiex.calibrecloud.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LibraryQueryServiceTest {
    private val identity = LibraryIdentity(LibraryId(UUID.randomUUID()), LibraryLocation.Local("a.documents", "tree"), UUID.randomUUID())
    private fun index(generation: UUID = UUID.randomUUID(), title: String = "A") = LibraryIndex(ImportedLibrary(identity, generation, 1,
        ParsedLibrary(null, listOf(ImportedBook(1, UUID(0, 1), title, emptyList(), null, null, null, null, emptyList(), "",
            emptyList(), RelativeSourcePath("p"), false, emptyMap())), emptyList()), null, ReadColumnStatus.NOT_CONFIGURED))

    private class FakeImports(var current: LibraryIndex?) : LibraryImports {
        var payloadReads = 0
        override suspend fun currentRevision() = current?.revision
        override suspend fun currentIndex() = current.also { payloadReads++ }
    }

    private val request = LibraryRequest(pageSize = 10)

    @Test
    fun noCompleteImportReportsNoMetadataEvenWhenCopiesExist() = runBlocking<Unit> {
        var manifestReads = 0
        val service = LibraryQueryService(FakeImports(null), { manifestReads++; emptyList() }, Dispatchers.Unconfined)
        assertEquals(LibraryQueryResult.Unavailable(LibraryProblem.NO_METADATA), service.query(request))
        assertEquals(0, manifestReads)
    }

    @Test
    fun indexIsBuiltOncePerRevisionAndRebuiltAfterReimportRejectingOldRequests() = runBlocking<Unit> {
        val imports = FakeImports(index(title = "Old"))
        val service = LibraryQueryService(imports, { emptyList() }, Dispatchers.Unconfined)
        val first = service.query(request) as LibraryQueryResult.Books
        service.query(request)
        assertEquals(1, imports.payloadReads)
        imports.current = index(title = "New")
        assertEquals(LibraryQueryResult.Stale, service.query(request.copy(expected = first.revision)))
        val second = service.query(request) as LibraryQueryResult.Books
        assertEquals(listOf("New"), second.rows.map { it.title })
        assertEquals(2, imports.payloadReads)
        imports.current = null
        assertEquals(LibraryQueryResult.Unavailable(LibraryProblem.NO_METADATA), service.query(request))
    }
}
