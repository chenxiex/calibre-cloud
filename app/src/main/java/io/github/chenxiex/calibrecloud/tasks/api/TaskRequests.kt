package io.github.chenxiex.calibrecloud.tasks.api

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.FormatResource
import io.github.chenxiex.calibrecloud.model.LibraryId
import java.util.Collections
import java.util.UUID

/** Defensive, deduplicated snapshot, including when exposed through a Java collection view. */
class FrozenSet<T>(values: Collection<T>) : AbstractSet<T>() {
    private val snapshot: Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
    override val size: Int get() = snapshot.size
    override fun iterator(): Iterator<T> = snapshot.iterator()
    override fun contains(element: T): Boolean = snapshot.contains(element)
}

data class TaskId(val value: UUID)

/** Persist code, never ordinal. Necessary preparation and follow-up inherit the parent's priority. */
enum class TaskOrigin(val code: String, val priority: TaskPriority) {
    USER_DOWNLOAD("user_download", TaskPriority.HIGH),
    USER_OPEN("user_open", TaskPriority.HIGH),
    MANUAL_SYNC("manual_sync", TaskPriority.HIGH),
    USER_READ_STATUS("user_read_status", TaskPriority.HIGH),
    VISIBLE_COVER("visible_cover", TaskPriority.LOW),
    STARTUP_SYNC("startup_sync", TaskPriority.LOW),
    DOWNLOADED_FORMAT_UPDATE("downloaded_format_update", TaskPriority.LOW),
}

enum class TaskPriority(val code: String) { HIGH("high"), LOW("low") }

/** Only a freshness barrier after this particular write can satisfy its write-refetch requirement. */
sealed interface SnapshotFreshness {
    data object CurrentSource : SnapshotFreshness
    data class AfterWrite(val writeTaskId: TaskId) : SnapshotFreshness
}

/** Narrow selection-scoped context; authorizationId identifies an authorization session, never a token. */
data class CandidateContext(val selectionToken: UUID, val backend: BackendKind, val authorizationId: UUID)

sealed interface TaskRequest {
    val libraryId: LibraryId?

    /** Internal backend registration only; no book identity exists before successful validation. */
    data class CandidateConfiguration(val context: CandidateContext, val operation: String) : TaskRequest {
        override val libraryId: LibraryId? = null
        init { require(operation.matches(Regex("[a-z][a-z0-9_]{0,63}"))) }
    }

    data class MetadataSync(
        override val libraryId: LibraryId,
        val freshness: SnapshotFreshness = SnapshotFreshness.CurrentSource,
    ) : TaskRequest

    /** One book/format per schedulable child; expectedVersion is an opaque comparison premise. */
    data class FormatCopy(val resource: FormatResource, val expectedVersion: FileVersion? = null) : TaskRequest {
        override val libraryId: LibraryId get() = resource.book.libraryId
        init { require(expectedVersion == null || expectedVersion.backend == resource.source.backend) }
    }

    data class CoverLoad(val book: BookKey) : TaskRequest {
        override val libraryId: LibraryId get() = book.libraryId
    }

    /** Retrying applies target again only after commit/recovery checks, never a source-state toggle. */
    data class ReadStatusWrite(
        override val libraryId: LibraryId,
        val books: FrozenSet<BookKey>,
        val column: CustomColumnId,
        val target: Boolean,
    ) : TaskRequest {
        init {
            require(books.isNotEmpty() && books.all { it.libraryId == libraryId })
        }
    }
}

/** Related writes wait for the predecessor's entire safe workflow, including required refetch. */
enum class DependencyRequirement(val code: String) {
    /** Entire dependency workflow completed successfully, including any mandatory import. */
    SUCCESS("success"),
    /** Confirmed commit only; allows refetch before the parent workflow has completed. */
    SOURCE_COMMIT_CONFIRMED("source_commit_confirmed"),
    /** No unresolved commit or mandatory refetch/recovery; Finished alone is insufficient. */
    SAFE_TERMINAL("safe_terminal"),
}

data class TaskDependency(val taskId: TaskId, val requirement: DependencyRequirement)

/** Origin is deliberately excluded: an equivalent user request can promote automatic work. */
data class RequestKey(val request: TaskRequest, val dependencies: FrozenSet<TaskDependency>)

data class TaskSubmission(
    val request: TaskRequest,
    val origin: TaskOrigin,
    val dependencies: FrozenSet<TaskDependency> = FrozenSet(emptyList()),
) {
    val key: RequestKey get() = RequestKey(request, dependencies)

    init {
        val freshness = (request as? TaskRequest.MetadataSync)?.freshness
        if (freshness is SnapshotFreshness.AfterWrite) {
            require(origin == TaskOrigin.USER_READ_STATUS)
            require(TaskDependency(freshness.writeTaskId, DependencyRequirement.SOURCE_COMMIT_CONFIRMED) in dependencies)
        }
    }
}

/** Initially off; only a configured library's first user-opened main screen per process may enqueue. */
data class StartupSyncSetting(val enabled: Boolean = false)

/** Separate from request equivalence: opposite targets on overlapping books still serialize. */
data class RelatedWriteKey(val book: BookKey, val column: CustomColumnId)

fun TaskRequest.ReadStatusWrite.relatedWriteKeys(): FrozenSet<RelatedWriteKey> =
    FrozenSet(books.map { RelatedWriteKey(it, column) })
