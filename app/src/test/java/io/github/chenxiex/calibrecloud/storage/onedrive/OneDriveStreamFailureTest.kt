package io.github.chenxiex.calibrecloud.storage.onedrive

import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.api.SourceFailure
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OneDriveStreamFailureTest {
    @Test fun interruptedNetworkReadsAndCloseBecomeRetryableNetworkFailure() {
        val source = object : InputStream() {
            override fun read(): Int = throw IOException("fixture connection interruption")
            override fun close() { throw IOException("fixture close interruption") }
        }.networkFailures()
        for (operation in listOf<() -> Unit>({ source.read() }, { source.read(ByteArray(8)) }, { source.close() })) {
            val failure = failure(operation)
            assertEquals(StorageErrorKind.NO_NETWORK, failure.error.kind)
            assertTrue(failure.transient)
        }
    }

    @Test fun invalidRangeBodyRetainsCorruptContentWithoutNetworkRetry() {
        val source = object : InputStream() {
            override fun read(): Int = throw OneDriveSourceException(StorageErrorKind.CORRUPT_CONTENT, reason = "range_truncated")
        }.networkFailures()
        val failure = failure { source.read(ByteArray(8)) }
        assertEquals(StorageErrorKind.CORRUPT_CONTENT, failure.error.kind)
        assertFalse(failure.transient)
    }

    @Test fun structuredFailureKeepsServerRetryDelayAndAuthorizationReason() {
        var exception = OneDriveSourceException(StorageErrorKind.NO_NETWORK, 120_000, true)
        val source = object : InputStream() {
            override fun read(): Int = throw exception
        }.networkFailures()
        val throttled = failure { source.read() }
        assertEquals(StorageErrorKind.NO_NETWORK, throttled.error.kind)
        assertTrue(throttled.transient)
        assertEquals(120_000L, throttled.retryDelayMillis)
        exception = OneDriveSourceException(StorageErrorKind.AUTHORIZATION_EXPIRED)
        val unauthorized = failure { source.read() }
        assertEquals(StorageErrorKind.AUTHORIZATION_EXPIRED, unauthorized.error.kind)
        assertFalse(unauthorized.transient)
    }

    private fun failure(operation: () -> Unit): SourceFailure {
        try { operation() } catch (failure: SourceFailure) { return failure }
        throw AssertionError("Expected a classified source failure")
    }
}
