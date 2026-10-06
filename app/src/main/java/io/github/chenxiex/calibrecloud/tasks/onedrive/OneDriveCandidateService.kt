package io.github.chenxiex.calibrecloud.tasks.onedrive

import io.github.chenxiex.calibrecloud.auth.OneDriveAuthorization
import io.github.chenxiex.calibrecloud.model.*
import io.github.chenxiex.calibrecloud.state.ApplicationStateRepository
import io.github.chenxiex.calibrecloud.storage.onedrive.*
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private directory results contain stable IDs and names only, never tokens or content URLs. */
data class OneDriveBrowseResult(
    val location: LibraryLocation.OneDrive,
    val parentItemId: String,
    val items: List<OneDriveItem>,
    val page: Int,
    val hasNext: Boolean,
    val directoryName: String = "",
    val complete: Boolean = false,
) {
    /** In-memory UI pagination; no queue, source access or disk reads. */
    fun pageAt(index: Int, pageSize: Int = OneDriveCandidateTaskHandler.PAGE_SIZE): OneDriveBrowseResult? {
        require(pageSize > 0)
        if (!complete || index < 0) return null
        val start = index.toLong() * pageSize
        if (start >= items.size && !(index == 0 && items.isEmpty())) return null
        val end = minOf(start + pageSize, items.size.toLong()).toInt()
        return copy(items = items.subList(start.toInt(), end), page = index, hasNext = end < items.size)
    }
}

class OneDriveBrowseStore(private val directory: File) {
    suspend fun save(id: TaskId, result: OneDriveBrowseResult) = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "${id.value}.json")
        val staging = File(directory, "${id.value}.part")
        try {
            val json = JSONObject().put("account", result.location.accountId).put("drive", result.location.driveId)
                .put("root", result.location.rootItemId).put("parent", result.parentItemId)
                .put("complete", result.complete).put("name", result.directoryName).put("page", result.page).put("next", result.hasNext).put("items", JSONArray().apply {
                    result.items.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
                })
            staging.outputStream().use { it.write(json.toString().toByteArray()); it.fd.sync() }
            check(staging.renameTo(target))
        } finally { staging.delete() }
    }

    suspend fun read(id: TaskId): OneDriveBrowseResult? = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(File(directory, "${id.value}.json").readText())
            val items = json.getJSONArray("items")
            OneDriveBrowseResult(LibraryLocation.OneDrive(json.getString("account"), json.getString("drive"),
                json.getString("root")), json.getString("parent"), (0 until items.length()).map {
                val item = items.getJSONObject(it)
                OneDriveItem(item.getString("id"), item.getString("name"), true, json.getString("parent"), null, null)
            }, json.getInt("page"), json.getBoolean("next"), json.optString("name"), json.optBoolean("complete", false))
        } catch (_: Exception) { null }
    }
}

/** Submission/restoration only; all Graph access is owned by the registered handler. */
class OneDriveCandidateService(
    private val state: ApplicationStateRepository,
    private val authorization: OneDriveAuthorization,
    val queue: DurableTaskQueue,
    val coordinator: TaskCoordinator,
    private val results: OneDriveBrowseStore,
) {
    suspend fun browse(parentItemId: String? = null): TaskId? {
        val session = authorization.sessionId() ?: return null
        val selected = state.current()
        val context = if (selected?.backend == BackendKind.ONEDRIVE && selected.authorizationId == session &&
            selected.location == null) CandidateContext(selected.token, BackendKind.ONEDRIVE, session)
            else state.beginCandidate(BackendKind.ONEDRIVE, session)
        return submit(TaskRequest.CandidateConfiguration(context, OneDriveCandidateTaskHandler.BROWSE, parentItemId))
    }

    suspend fun choose(itemId: String): Boolean {
        val selected = state.current() ?: return false
        val session = authorization.sessionId() ?: return false
        if (selected.authorizationId != session) return false
        val result = currentPage() ?: return false
        if (itemId != result.parentItemId && result.items.none { it.id == itemId }) return false
        val location = result.location.copy(rootItemId = itemId)
        // Choosing a root is the explicit selection boundary; directory navigation never resolves it.
        return state.chooseCandidate(CandidateContext(selected.token, BackendKind.ONEDRIVE, session), location) != null
    }

    suspend fun acquire(): TaskId? {
        val session = authorization.sessionId() ?: return null
        val selected = state.current() ?: return null
        if (selected.backend != BackendKind.ONEDRIVE || selected.location == null) return null
        val context = if (selected.authorizationId == session) CandidateContext(selected.token, BackendKind.ONEDRIVE, session)
            else state.reauthorizeCandidate(selected.token, session) ?: return null
        return submit(TaskRequest.CandidateConfiguration(context, OneDriveCandidateTaskHandler.SNAPSHOT))
    }

    suspend fun currentLocation() = state.current()?.location as? LibraryLocation.OneDrive

    suspend fun currentRecord(): TaskRecord? = entries().maxByOrNull { it.scheduling.sequence.value }

    suspend fun currentPage(): OneDriveBrowseResult? {
        val latest = entries().filter {
            (it.submission.request as TaskRequest.CandidateConfiguration).operation == OneDriveCandidateTaskHandler.BROWSE &&
                it.state == TaskState.Finished(TaskResult.Completed)
        }.maxByOrNull { it.scheduling.sequence.value } ?: return null
        return results.read(latest.id)?.takeIf { it.complete }
    }

    private suspend fun entries(): List<TaskRecord> {
        val selected = state.current() ?: return emptyList()
        val session = authorization.sessionId() ?: return emptyList()
        if (selected.backend != BackendKind.ONEDRIVE || selected.authorizationId != session) return emptyList()
        return queue.list().map { it.record }.filter {
            val request = it.submission.request as? TaskRequest.CandidateConfiguration
            request?.context?.selectionToken == selected.token && request.context.authorizationId == session
        }
    }

    private suspend fun submit(request: TaskRequest): TaskId? = when (val result = coordinator.submit(
        TaskSubmission(request, TaskOrigin.MANUAL_SYNC))) {
        is SubmissionResult.Created -> result.taskId
        is SubmissionResult.Reused -> result.taskId
        is SubmissionResult.Promoted -> result.taskId
        is SubmissionResult.Rejected -> null
    }
}
