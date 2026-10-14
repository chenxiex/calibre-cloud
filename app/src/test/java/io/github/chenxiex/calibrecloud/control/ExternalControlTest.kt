package io.github.chenxiex.calibrecloud.control

import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.state.ExternalClient
import io.github.chenxiex.calibrecloud.state.ExternalControlStore
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.SubmissionResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** R37 request parsing and the decisions of [ExternalControl], with the app replaced at its port. */
class ExternalControlTest {
    private val token = "a".repeat(32)
    private val library = LibraryId(UUID.randomUUID())
    private val other = LibraryId(UUID.randomUUID())
    private val selection = UUID.randomUUID()
    private val book = BookKey(library, 4, UUID(0, 4))

    private class FakeStore : ExternalControlStore {
        var on = false
        val hashes = mutableMapOf<String, String>()
        override suspend fun enabled() = on
        override suspend fun setEnabled(enabled: Boolean) { on = enabled }
        override suspend fun clients() = hashes.keys.map { ExternalClient(it, 0) }
        override suspend fun register(name: String, tokenHash: String, enable: Boolean) {
            hashes.values.remove(tokenHash)
            hashes[name] = tokenHash
            if (enable) on = true
        }
        override suspend fun remove(name: String) { hashes.remove(name) }
        override suspend fun registered(tokenHash: String) = tokenHash in hashes.values
    }

    private inner class FakePort : ExternalControlPort {
        override val store = FakeStore()
        var current: Pair<LibraryId, UUID>? = library to selection
        val opened = mutableMapOf<String, List<CopyKey>>()
        var result: SubmissionResult = SubmissionResult.Created(TaskId(UUID.randomUUID()))
        val submitted = mutableListOf<Triple<BookKey, Boolean, UUID>>()
        var woken = 0
        override suspend fun current() = current
        override suspend fun opened(readerName: String) = opened[readerName].orEmpty()
        override suspend fun submit(book: BookKey, target: Boolean, selectionToken: UUID): SubmissionResult {
            submitted += Triple(book, target, selectionToken)
            return result
        }
        override suspend fun wake() { woken++ }
    }

    private fun parse(uri: String): ParsedRequest {
        val query = uri.substringAfter('?', "").split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return ExternalProtocol.parse(uri.substringAfter("://").substringBefore('?')) { query[it] }
    }

    private suspend fun registered(port: FakePort) {
        ExternalControl(port).register(ExternalRequest.Register("KOReader", token))
    }

    @Test
    fun parsesEachOperationAndRejectsUnknownOrIncompleteOnes() {
        assertEquals(ParsedRequest.Valid(ExternalRequest.MarkReadStatus(true, token, "哈姆莱特-4.epub")),
            parse("calibrecloud://mark_read?token=$token&file=/storage/koreader/哈姆莱特-4.epub"))
        assertEquals(ParsedRequest.Valid(ExternalRequest.MarkReadStatus(false, token, "a-1.pdf")),
            parse("calibrecloud://mark_unread?token=$token&file=a-1.pdf"))
        assertEquals(ParsedRequest.Valid(ExternalRequest.Register("KOReader", token)), parse("calibrecloud://register?token=$token&name= KOReader "))
        assertEquals(ParsedRequest.UnknownOperation, parse("calibrecloud://open_book?token=$token&file=a-1.epub"))
        assertEquals(ParsedRequest.Malformed, parse("calibrecloud://mark_read?token=$token"))
        assertEquals(ParsedRequest.Malformed, parse("calibrecloud://mark_read?token=short&file=a-1.epub"))
        assertEquals(ParsedRequest.Malformed, parse("calibrecloud://mark_read?token=$token&file=dir/"))
        assertEquals(ParsedRequest.Malformed, parse("calibrecloud://register?token=$token&name="))
        assertEquals(ParsedRequest.Malformed, parse("calibrecloud://register?token=$token&name=${"n".repeat(65)}"))
    }

    @Test
    fun requestsNeverShowTheirTokenOrFile() {
        val text = listOf(ExternalRequest.MarkReadStatus(true, token, "secret-1.epub"), ExternalRequest.Register("KOReader", token)).toString()
        assertFalse(token in text)
        assertFalse("secret" in text)
    }

    @Test
    fun theSwitchAndTheRegisteredTokenGateEveryRequest() = runTest {
        val port = FakePort()
        port.opened["哈姆莱特-4.epub"] = listOf(CopyKey(book, BookFormat.parse("EPUB")))
        val request = ExternalRequest.MarkReadStatus(true, token, "哈姆莱特-4.epub")
        val control = ExternalControl(port)
        assertEquals(ExternalOutcome.Rejected(ExternalRejection.DISABLED), control.markReadStatus(request))
        port.store.on = true
        assertEquals(ExternalOutcome.Rejected(ExternalRejection.UNREGISTERED), control.markReadStatus(request))
        port.store.on = false
        // Confirming a registration while the switch is off turns it on as well (Q92).
        registered(port)
        assertTrue(port.store.on)
        assertEquals(ExternalOutcome.Accepted, control.markReadStatus(request))
        port.store.remove("KOReader")
        assertEquals(ExternalOutcome.Rejected(ExternalRejection.UNREGISTERED), control.markReadStatus(request))
        assertEquals(1, port.submitted.size)
    }

    @Test
    fun anAcceptedRequestSubmitsTheBookLikeTheButtonAndWakesTheQueue() = runTest {
        val port = FakePort()
        registered(port)
        port.opened["哈姆莱特-4.epub"] = listOf(CopyKey(BookKey(other, 4, UUID(0, 4)), BookFormat.parse("EPUB")), CopyKey(book, BookFormat.parse("EPUB")))
        val control = ExternalControl(port)
        assertEquals(ExternalOutcome.Accepted, control.markReadStatus(ExternalRequest.MarkReadStatus(false, token, "哈姆莱特-4.epub")))
        port.result = SubmissionResult.Reused(TaskId(UUID.randomUUID()))
        assertEquals(ExternalOutcome.Accepted, control.markReadStatus(ExternalRequest.MarkReadStatus(true, token, "哈姆莱特-4.epub")))
        assertEquals(listOf(Triple(book, false, selection), Triple(book, true, selection)), port.submitted)
        assertEquals(2, port.woken)
    }

    @Test
    fun aBookIsOnlyFoundAmongTheCurrentLibrarysOpenedCopies() = runTest {
        val port = FakePort()
        registered(port)
        val control = ExternalControl(port)
        val request = ExternalRequest.MarkReadStatus(true, token, "哈姆莱特-4.epub")
        assertEquals(ExternalOutcome.Rejected(ExternalRejection.NOT_OPENED), control.markReadStatus(request))
        port.opened["哈姆莱特-4.epub"] = listOf(CopyKey(BookKey(other, 4, UUID(0, 4)), BookFormat.parse("EPUB")))
        assertEquals(ExternalOutcome.Rejected(ExternalRejection.OTHER_LIBRARY), control.markReadStatus(request))
        port.current = null
        assertEquals(ExternalOutcome.Rejected(ExternalRejection.NO_LIBRARY), control.markReadStatus(request))
        assertTrue(port.submitted.isEmpty())
        assertEquals(0, port.woken)
    }

    @Test
    fun aRejectedSubmissionKeepsItsReason() = runTest {
        val port = FakePort()
        registered(port)
        port.opened["a-4.epub"] = listOf(CopyKey(book, BookFormat.parse("EPUB")))
        val control = ExternalControl(port)
        val request = ExternalRequest.MarkReadStatus(true, token, "a-4.epub")
        fun source(kind: StorageErrorKind) = SubmissionResult.Rejected(TaskError.Source(StorageError(kind)))
        mapOf(
            SubmissionResult.Rejected(TaskError.InvalidColumn) to ExternalRejection.NO_READ_COLUMN,
            source(StorageErrorKind.UNSUPPORTED_OPERATION) to ExternalRejection.NOT_WRITABLE,
            source(StorageErrorKind.VERSION_CONFLICT) to ExternalRejection.NOT_IN_LIBRARY,
        ).forEach { (result, reason) ->
            port.result = result
            assertEquals(ExternalOutcome.Rejected(reason), control.markReadStatus(request))
        }
        assertEquals(0, port.woken)
    }

    @Test
    fun registeringAgainReplacesTheEarlierTokenOfThatName() = runTest {
        val port = FakePort()
        registered(port)
        val renewed = "b".repeat(40)
        ExternalControl(port).register(ExternalRequest.Register("KOReader", renewed))
        assertFalse(port.store.registered(ExternalProtocol.tokenHash(token)))
        assertTrue(port.store.registered(ExternalProtocol.tokenHash(renewed)))
        assertEquals(64, ExternalProtocol.tokenHash(token).length)
    }
}
