package io.github.chenxiex.calibrecloud.ui

import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.FrozenSet
import io.github.chenxiex.calibrecloud.tasks.api.StageFailure
import io.github.chenxiex.calibrecloud.tasks.api.TaskError
import io.github.chenxiex.calibrecloud.tasks.api.TaskResult
import io.github.chenxiex.calibrecloud.tasks.api.TaskStage
import io.github.chenxiex.calibrecloud.tasks.api.TaskState
import io.github.chenxiex.calibrecloud.tasks.api.WaitingReason
import io.github.chenxiex.calibrecloud.tasks.onedrive.OneDriveCandidateTaskHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class OneDriveDirectoryStatusTest {
    @Test
    fun browseFailureIsVisibleOnTheDirectoryPage() {
        val failed = TaskState.Finished(TaskResult.Failed(StageFailure(
            TaskStage.CANDIDATE_ACCESS,
            TaskError.Source(StorageError(StorageErrorKind.LOCAL_IO)),
        )))
        assertEquals(R.string.onedrive_task_io, directoryStatusResource(failed, OneDriveCandidateTaskHandler.BROWSE, false, false))
        // A network wait continues automatically; only a finished failure asks for a retry.
        val offline = TaskState.Finished(TaskResult.Failed(StageFailure(
            TaskStage.CANDIDATE_ACCESS,
            TaskError.Source(StorageError(StorageErrorKind.NO_NETWORK)),
        )))
        assertEquals(R.string.onedrive_task_network_failed, browseStatus(offline))
    }

    @Test
    fun browsingShowsQueueExecutionAndNetworkWaitWithoutChangingPages() {
        assertEquals(R.string.onedrive_task_queued, directoryStatusResource(null, null, false, true))
        assertEquals(R.string.onedrive_task_queued, browseStatus(TaskState.Queued))
        assertEquals(R.string.onedrive_task_running, browseStatus(TaskState.Running(TaskStage.CANDIDATE_ACCESS)))
        assertEquals(R.string.onedrive_task_network, browseStatus(TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK)))))
    }

    @Test
    fun snapshotCompletionDoesNotClaimThatDirectoryBrowsingCompleted() {
        assertEquals(R.string.onedrive_task_pending, directoryStatusResource(
            TaskState.Finished(TaskResult.Completed), io.github.chenxiex.calibrecloud.tasks.api.TaskRequest.CandidateConfiguration.LIBRARY_SYNC, false, false,
        ))
        assertEquals(R.string.onedrive_browse_completed, browseStatus(TaskState.Finished(TaskResult.Completed)))
    }

    @Test
    fun rejectedNewBrowseDoesNotKeepAnOldSuccessfulStatus() {
        assertEquals(R.string.onedrive_task_rejected, directoryStatusResource(
            TaskState.Finished(TaskResult.Completed), OneDriveCandidateTaskHandler.BROWSE, true, false,
        ))
    }

    private fun browseStatus(state: TaskState) = directoryStatusResource(state, OneDriveCandidateTaskHandler.BROWSE, false, false)
}
