package io.github.chenxiex.calibrecloud.tasks.persistence

import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.BookFormat
import io.github.chenxiex.calibrecloud.model.BookKey
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.FileVersion
import io.github.chenxiex.calibrecloud.model.FormatResource
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.model.SourceFileLocator
import io.github.chenxiex.calibrecloud.storage.api.DiagnosticId
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.BookFailure
import io.github.chenxiex.calibrecloud.tasks.api.CandidateContext
import io.github.chenxiex.calibrecloud.tasks.api.CommitState
import io.github.chenxiex.calibrecloud.tasks.api.DependencyRequirement
import io.github.chenxiex.calibrecloud.tasks.api.FrozenSet
import io.github.chenxiex.calibrecloud.tasks.api.PriorityPromotion
import io.github.chenxiex.calibrecloud.tasks.api.QueueSequence
import io.github.chenxiex.calibrecloud.tasks.api.SchedulingPosition
import io.github.chenxiex.calibrecloud.tasks.api.SnapshotFreshness
import io.github.chenxiex.calibrecloud.tasks.api.StageFailure
import io.github.chenxiex.calibrecloud.tasks.api.TaskControls
import io.github.chenxiex.calibrecloud.tasks.api.TaskDependency
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskId
import io.github.chenxiex.calibrecloud.tasks.api.TaskOrigin
import io.github.chenxiex.calibrecloud.tasks.api.TaskPriority
import io.github.chenxiex.calibrecloud.tasks.api.TaskProgress
import io.github.chenxiex.calibrecloud.tasks.api.TaskRecord
import io.github.chenxiex.calibrecloud.tasks.api.TaskRequest
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskStage
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.TaskSubmission
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Versioned private queue payload. Never log payloads: file tokens and locators are retained here. */
internal object TaskCodec {
    fun encode(record: TaskRecord): String = obj(
        "version" to 1,
        "id" to record.id.value.toString(),
        "submission" to submission(record.submission),
        "priority" to record.scheduling.priority.code,
        "sequence" to record.scheduling.sequence.value,
        "promotion" to record.promotion?.let { obj("origin" to it.origin.code, "sequence" to it.sequence.value) },
        "state" to state(record.state),
        "controls" to obj(
            "pause" to record.controls.canPause, "cancel" to record.controls.canCancel,
            "resume" to record.controls.canResume, "retry" to record.controls.canRetry,
        ),
        "commit" to commit(record.commit),
    ).toString()

    fun decode(value: String): TaskRecord {
        val json = JSONObject(value)
        require(json.getInt("version") == 1) { "Unsupported task payload version" }
        val controls = json.getJSONObject("controls")
        return TaskRecord(
            id = taskId(json.getString("id")),
            submission = readSubmission(json.getJSONObject("submission")),
            scheduling = SchedulingPosition(
                TaskPriority.entries.single { it.code == json.getString("priority") },
                QueueSequence(json.getLong("sequence")),
            ),
            promotion = json.optionalObject("promotion")?.let {
                PriorityPromotion(origin(it.getString("origin")), QueueSequence(it.getLong("sequence")))
            },
            state = readState(json.getJSONObject("state")),
            controls = TaskControls(
                controls.getBoolean("pause"), controls.getBoolean("cancel"),
                controls.getBoolean("resume"), controls.getBoolean("retry"),
            ),
            commit = readCommit(json.getJSONObject("commit")),
        )
    }

    private fun submission(value: TaskSubmission) = obj(
        "request" to request(value.request), "origin" to value.origin.code,
        "dependencies" to sortedArray(value.dependencies.map {
            obj("id" to it.taskId.value.toString(), "requirement" to it.requirement.code)
        }),
    )

    private fun readSubmission(json: JSONObject) = TaskSubmission(
        request = readRequest(json.getJSONObject("request")),
        origin = origin(json.getString("origin")),
        dependencies = FrozenSet(json.getJSONArray("dependencies").objects().map {
            TaskDependency(taskId(it.getString("id")), DependencyRequirement.entries.single { entry ->
                entry.code == it.getString("requirement")
            })
        }),
    )

    private fun request(value: TaskRequest): JSONObject = when (value) {
        is TaskRequest.CandidateConfiguration -> obj(
            "tag" to "candidate_configuration", "selection" to value.context.selectionToken.toString(),
            "backend" to backendCode(value.context.backend), "authorization" to value.context.authorizationId.toString(),
            "operation" to value.operation,
        )
        is TaskRequest.MetadataSync -> obj(
            "tag" to "metadata_sync", "library" to value.libraryId.value.toString(),
            "freshness" to when (val freshness = value.freshness) {
                SnapshotFreshness.CurrentSource -> obj("tag" to "current_source")
                is SnapshotFreshness.AfterWrite -> obj("tag" to "after_write", "task" to freshness.writeTaskId.value.toString())
            },
        )
        is TaskRequest.FormatCopy -> obj(
            "tag" to "format_copy", "book" to book(value.resource.book),
            "format" to value.resource.format.value, "source" to locator(value.resource.source),
            "expected_version" to value.expectedVersion?.let(::version),
        )
        is TaskRequest.CoverLoad -> obj("tag" to "cover_load", "book" to book(value.book))
        is TaskRequest.ReadStatusWrite -> obj(
            "tag" to "read_status_write", "library" to value.libraryId.value.toString(),
            "books" to sortedArray(value.books.map(::book)),
            "column" to obj("id" to value.column.sourceId, "lookup" to value.column.lookupName),
            "target" to value.target,
        )
    }

    private fun readRequest(json: JSONObject): TaskRequest = when (json.getString("tag")) {
        "candidate_configuration" -> TaskRequest.CandidateConfiguration(
            CandidateContext(
                UUID.fromString(json.getString("selection")), readBackend(json.getString("backend")),
                UUID.fromString(json.getString("authorization")),
            ),
            json.getString("operation"),
        )
        "metadata_sync" -> TaskRequest.MetadataSync(
            libraryId(json.getString("library")),
            json.getJSONObject("freshness").let {
                when (it.getString("tag")) {
                    "current_source" -> SnapshotFreshness.CurrentSource
                    "after_write" -> SnapshotFreshness.AfterWrite(taskId(it.getString("task")))
                    else -> invalidTag()
                }
            },
        )
        "format_copy" -> TaskRequest.FormatCopy(
            FormatResource(
                readBook(json.getJSONObject("book")), BookFormat.parse(json.getString("format")),
                readLocator(json.getJSONObject("source")),
            ),
            json.optionalObject("expected_version")?.let(::readVersion),
        )
        "cover_load" -> TaskRequest.CoverLoad(readBook(json.getJSONObject("book")))
        "read_status_write" -> TaskRequest.ReadStatusWrite(
            libraryId(json.getString("library")),
            FrozenSet(json.getJSONArray("books").objects().map(::readBook)),
            json.getJSONObject("column").let { CustomColumnId(it.getLong("id"), it.getString("lookup")) },
            json.getBoolean("target"),
        )
        else -> invalidTag()
    }

    private fun book(value: BookKey) = obj(
        "library" to value.libraryId.value.toString(), "id" to value.sourceId, "uuid" to value.sourceUuid.toString(),
    )

    private fun readBook(json: JSONObject) = BookKey(
        libraryId(json.getString("library")), json.getLong("id"), UUID.fromString(json.getString("uuid")),
    )

    private fun locator(value: SourceFileLocator): JSONObject = when (value) {
        is SourceFileLocator.Local -> obj("tag" to "local", "document" to value.documentId)
        is SourceFileLocator.OneDrive -> obj("tag" to "onedrive", "drive" to value.driveId, "item" to value.itemId)
    }

    private fun readLocator(json: JSONObject): SourceFileLocator = when (json.getString("tag")) {
        "local" -> SourceFileLocator.Local(json.getString("document"))
        "onedrive" -> SourceFileLocator.OneDrive(json.getString("drive"), json.getString("item"))
        else -> invalidTag()
    }

    private fun version(value: FileVersion) = obj("backend" to backendCode(value.backend), "token" to value.token)
    private fun readVersion(json: JSONObject) = FileVersion(readBackend(json.getString("backend")), json.getString("token"))
    private fun backendCode(value: BackendKind) = when (value) {
        BackendKind.LOCAL -> "local"
        BackendKind.ONEDRIVE -> "onedrive"
    }
    private fun readBackend(value: String) = when (value) {
        "local" -> BackendKind.LOCAL
        "onedrive" -> BackendKind.ONEDRIVE
        else -> invalidTag()
    }

    private fun commit(value: CommitState): JSONObject = when (value) {
        CommitState.NotCommitted -> obj("tag" to "not_committed")
        CommitState.Confirmed -> obj("tag" to "confirmed")
        is CommitState.Unknown -> obj("tag" to "unknown", "recovery" to value.recoveryRecordId.toString())
    }

    private fun readCommit(json: JSONObject): CommitState = when (json.getString("tag")) {
        "not_committed" -> CommitState.NotCommitted
        "confirmed" -> CommitState.Confirmed
        "unknown" -> CommitState.Unknown(UUID.fromString(json.getString("recovery")))
        else -> invalidTag()
    }

    private fun state(value: TaskState): JSONObject = when (value) {
        TaskState.Queued -> obj("tag" to "queued")
        is TaskState.Waiting -> obj("tag" to "waiting", "reasons" to JSONArray(value.reasons.map { it.code }.sorted()))
        is TaskState.Running -> obj(
            "tag" to "running", "stage" to value.stage.code,
            "progress" to value.progress?.let { obj("completed" to it.completed, "total" to it.total) },
        )
        is TaskState.Paused -> obj("tag" to "paused", "stage" to value.stage.code)
        is TaskState.Finished -> obj("tag" to "finished", "result" to result(value.result))
    }

    private fun readState(json: JSONObject): TaskState = when (json.getString("tag")) {
        "queued" -> TaskState.Queued
        "waiting" -> TaskState.Waiting(FrozenSet(json.getJSONArray("reasons").let { array ->
            (0 until array.length()).map { index -> WaitingReason.entries.single { it.code == array.getString(index) } }
        }))
        "running" -> TaskState.Running(stage(json.getString("stage")), json.optionalObject("progress")?.let {
            TaskProgress(it.getLong("completed"), if (it.isNull("total")) null else it.getLong("total"))
        })
        "paused" -> TaskState.Paused(stage(json.getString("stage")))
        "finished" -> TaskState.Finished(readResult(json.getJSONObject("result")))
        else -> invalidTag()
    }

    private fun result(value: TaskResult): JSONObject = when (value) {
        TaskResult.Completed -> obj("tag" to "completed")
        is TaskResult.CompletedWithBookFailures -> obj("tag" to "completed_with_book_failures", "failures" to sortedArray(
            value.failures.map { obj("book" to book(it.book), "error" to error(it.error)) },
        ))
        is TaskResult.Failed -> obj(
            "tag" to "failed", "stage" to value.failure.stage.code,
            "error" to error(value.failure.error), "commit" to commit(value.failure.commit),
        )
        is TaskResult.Cancelled -> obj("tag" to "cancelled", "commit" to commit(value.commit))
    }

    private fun readResult(json: JSONObject): TaskResult = when (json.getString("tag")) {
        "completed" -> TaskResult.Completed
        "completed_with_book_failures" -> TaskResult.CompletedWithBookFailures(FrozenSet(
            json.getJSONArray("failures").objects().map { BookFailure(readBook(it.getJSONObject("book")), readError(it.getJSONObject("error"))) },
        ))
        "failed" -> TaskResult.Failed(StageFailure(
            stage(json.getString("stage")), readError(json.getJSONObject("error")), readCommit(json.getJSONObject("commit")),
        ))
        "cancelled" -> TaskResult.Cancelled(readCommit(json.getJSONObject("commit")))
        else -> invalidTag()
    }

    private fun error(value: TaskError): JSONObject = when (value) {
        is TaskError.Source -> obj(
            "tag" to "source", "kind" to storageCode(value.error.kind),
            "diagnostic" to value.error.diagnosticId?.value?.toString(),
        )
        TaskError.InvalidColumn -> obj("tag" to "invalid_column")
        is TaskError.BookIdentityChanged -> obj("tag" to "book_identity_changed", "book" to book(value.book))
    }

    private fun readError(json: JSONObject): TaskError = when (json.getString("tag")) {
        "source" -> TaskError.Source(StorageError(
            StorageErrorKind.entries.single { storageCode(it) == json.getString("kind") },
            if (json.isNull("diagnostic")) null else DiagnosticId(UUID.fromString(json.getString("diagnostic"))),
        ))
        "invalid_column" -> TaskError.InvalidColumn
        "book_identity_changed" -> TaskError.BookIdentityChanged(readBook(json.getJSONObject("book")))
        else -> invalidTag()
    }

    private fun storageCode(value: StorageErrorKind): String = when (value) {
        StorageErrorKind.NO_NETWORK -> "no_network"
        StorageErrorKind.LOGIN_REQUIRED -> "login_required"
        StorageErrorKind.AUTHORIZATION_EXPIRED -> "authorization_expired"
        StorageErrorKind.SOURCE_MISSING -> "source_missing"
        StorageErrorKind.INCOMPATIBLE_DATABASE -> "incompatible_database"
        StorageErrorKind.VERSION_CONFLICT -> "version_conflict"
        StorageErrorKind.INSUFFICIENT_SPACE -> "insufficient_space"
        StorageErrorKind.CORRUPT_CONTENT -> "corrupt_content"
        StorageErrorKind.UNSUPPORTED_OPERATION -> "unsupported_operation"
        StorageErrorKind.LOCAL_IO -> "local_io"
    }

    private fun origin(value: String) = TaskOrigin.entries.single { it.code == value }
    private fun stage(value: String) = TaskStage.entries.single { it.code == value }
    private fun taskId(value: String) = TaskId(UUID.fromString(value))
    private fun libraryId(value: String) = LibraryId(UUID.fromString(value))
    private fun invalidTag(): Nothing = throw IllegalArgumentException("Unsupported task payload tag")
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    private fun JSONObject.optionalObject(key: String): JSONObject? = if (isNull(key)) null else getJSONObject(key)
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun sortedArray(values: List<JSONObject>) = JSONArray(values.sortedBy { it.toString() })
}
