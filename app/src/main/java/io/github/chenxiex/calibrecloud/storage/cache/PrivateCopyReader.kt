package io.github.chenxiex.calibrecloud.storage.cache

import io.github.chenxiex.calibrecloud.model.CopyKey
import io.github.chenxiex.calibrecloud.storage.api.ApplicationCopyHandle
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyQuery
import io.github.chenxiex.calibrecloud.storage.api.CopyReadResult
import io.github.chenxiex.calibrecloud.storage.api.CopyReader
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface HandleOpenResult {
    data class Opened(val handle: ApplicationCopyHandle) : HandleOpenResult
    data object Missing : HandleOpenResult
    data class Failed(val error: StorageError) : HandleOpenResult
}

fun interface ApplicationCopyHandleFactory {
    fun open(location: CompleteCopyLocation, expectedSize: Long?): HandleOpenResult
}

/** Both backends reuse this reader. The query implementation supplies only published complete records. */
class PrivateCopyReader(
    private val copies: CompleteCopyQuery,
    private val handles: ApplicationCopyHandleFactory,
    private val ioDispatcher: CoroutineDispatcher,
) : CopyReader {
    override suspend fun read(key: CopyKey): CopyReadResult {
        var acquired: ApplicationCopyHandle? = null
        var delivered = false
        try {
            val result = withContext(ioDispatcher) {
                try {
                    val copy = copies.find(key) ?: return@withContext CopyReadResult.Missing
                    if (copy.key != key) {
                        return@withContext CopyReadResult.Failed(StorageError(StorageErrorKind.CORRUPT_CONTENT))
                    }
                    when (val opened = handles.open(copy.location, copy.sizeBytes)) {
                        is HandleOpenResult.Opened -> {
                            acquired = opened.handle
                            CopyReadResult.Available(opened.handle, copy.savedVersion)
                        }
                        HandleOpenResult.Missing -> CopyReadResult.Missing
                        is HandleOpenResult.Failed -> CopyReadResult.Failed(opened.error)
                    }
                } catch (_: SecurityException) {
                    CopyReadResult.Failed(StorageError(StorageErrorKind.AUTHORIZATION_EXPIRED))
                } catch (_: IOException) {
                    CopyReadResult.Failed(StorageError(StorageErrorKind.LOCAL_IO))
                }
            }
            delivered = true
            return result
        } finally {
            // Dispatcher handoff can discard a result on cancellation before the caller owns it.
            if (!delivered) {
                withContext(ioDispatcher + NonCancellable) { acquired?.close() }
            }
        }
    }
}
