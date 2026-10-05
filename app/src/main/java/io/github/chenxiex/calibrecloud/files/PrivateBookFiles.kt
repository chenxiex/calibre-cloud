package io.github.chenxiex.calibrecloud.files

import io.github.chenxiex.calibrecloud.storage.api.ApplicationCopyHandle
import io.github.chenxiex.calibrecloud.storage.api.CompleteCopyLocation
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.storage.cache.ApplicationCopyHandleFactory
import io.github.chenxiex.calibrecloud.storage.cache.HandleOpenResult
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** Reads filesDir/books only. Construction and reads neither create files nor access a source library. */
class PrivateBookFiles(private val filesDir: File) : ApplicationCopyHandleFactory {
    override fun open(location: CompleteCopyLocation, expectedSize: Long?): HandleOpenResult {
        try {
            val base = filesDir.toPath().toRealPath()
            val root = base.resolve("books")
            val library = root.resolve(location.libraryId.value.toString())
            val file = library.resolve("${location.fileGeneration}.book")
            // Reject links at every generated component, including links into another library.
            if (listOf(root, library, file).any { Files.isSymbolicLink(it) }) {
                return failure(StorageErrorKind.CORRUPT_CONTENT)
            }
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!file.toRealPath().startsWith(root) || !attributes.isRegularFile) {
                return failure(StorageErrorKind.CORRUPT_CONTENT)
            }
            val channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            try {
                val size = channel.size()
                if (size <= 0 || (expectedSize != null && size != expectedSize)) {
                    channel.close()
                    return failure(StorageErrorKind.CORRUPT_CONTENT)
                }
                val stream = java.nio.channels.Channels.newInputStream(channel)
                val handle = object : ApplicationCopyHandle {
                    override val input = stream
                    override val sizeBytes = size
                    override fun close() = input.close()
                }
                return HandleOpenResult.Opened(handle)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        } catch (_: NoSuchFileException) {
            return HandleOpenResult.Missing
        } catch (_: java.nio.file.AccessDeniedException) {
            return failure(StorageErrorKind.AUTHORIZATION_EXPIRED)
        } catch (_: SecurityException) {
            return failure(StorageErrorKind.AUTHORIZATION_EXPIRED)
        } catch (_: IOException) {
            return failure(StorageErrorKind.LOCAL_IO)
        }
    }

    private fun failure(kind: StorageErrorKind) = HandleOpenResult.Failed(StorageError(kind))
}
