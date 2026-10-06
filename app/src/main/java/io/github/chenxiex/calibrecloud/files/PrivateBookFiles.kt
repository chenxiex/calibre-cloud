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
    private val readers = mutableMapOf<CompleteCopyLocation, Int>()
    private val retired = mutableSetOf<CompleteCopyLocation>()

    @Synchronized
    override fun retire(location: CompleteCopyLocation) {
        retired.add(location)
        collect(location)
    }

    @Synchronized
    override fun collectUnreferenced(libraryId: io.github.chenxiex.calibrecloud.model.LibraryId, retained: Set<CompleteCopyLocation>) {
        val root = File(filesDir, "books")
        val directory = File(root, libraryId.value.toString())
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(directory.toPath())) return
        directory.listFiles().orEmpty().forEach { file ->
            val generation = runCatching { java.util.UUID.fromString(file.name.removeSuffix(".book")) }.getOrNull()
            if (file.name.endsWith(".book") && generation != null) {
                val location = CompleteCopyLocation(libraryId, generation)
                if (location !in retained) retire(location)
            }
        }
    }

    private fun collect(location: CompleteCopyLocation) {
        if (location in retired && (readers[location] ?: 0) == 0) {
            val file = File(filesDir, "books/${location.libraryId.value}/${location.fileGeneration}.book")
            if (!Files.isSymbolicLink(File(filesDir, "books").toPath()) &&
                !Files.isSymbolicLink(file.parentFile!!.toPath()) && !Files.isSymbolicLink(file.toPath()) && file.delete()) retired.remove(location)
        }
    }

    @Synchronized
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
                readers[location] = (readers[location] ?: 0) + 1
                var closed = false
                val handle = object : ApplicationCopyHandle {
                    override val input = stream
                    override val sizeBytes = size
                    override fun close() = synchronized(this@PrivateBookFiles) {
                        if (!closed) {
                            closed = true
                            try { input.close() } finally {
                                readers[location] = requireNotNull(readers[location]) - 1
                                if (readers[location] == 0) readers.remove(location)
                                collect(location)
                            }
                        }
                    }
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
