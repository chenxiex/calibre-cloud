package io.github.chenxiex.calibrecloud.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chenxiex.calibrecloud.model.BackendKind
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Production paged rendering of the task page, without source access or a background executor. */
@RunWith(AndroidJUnit4::class)
class TaskScreenTest {
    @get:Rule val compose = createComposeRule()

    /** Room for the tab row, six 56dp rows, or five and the page bar. */
    private fun show(records: () -> List<TaskRecord>, failed: Boolean = false, operationFailed: Boolean = false,
                     onControl: (TaskRecord, TaskControl) -> Unit = { _, _ -> }) {
        compose.setContent { Box(Modifier.height(410.dp)) { TaskList(records(), failed, operationFailed, onControl) } }
    }

    @Test
    fun emptyQueueSaysSoWithoutAPageRow() {
        show({ emptyList() })
        compose.onNodeWithTag("task_empty").assertTextEquals("暂无任务")
        compose.onNodeWithTag("tasks_page_status").assertDoesNotExist()
    }

    @Test
    fun queuedTasksShowOnlyTheirTurnAndTheQueuePagesClampAfterRemoval() {
        val records = mutableStateOf((1..20).map { record(it, TaskState.Queued) })
        show({ records.value })
        compose.onNodeWithText("排队 #1").assertIsDisplayed()
        compose.onNodeWithTag("tasks_page_status").assertTextEquals("1 / 4")
        compose.onNodeWithTag("tasks_last_page").performClick()
        compose.onNodeWithTag("tasks_page_status").assertTextEquals("4 / 4")
        compose.onNodeWithText("排队 #20").assertIsDisplayed()
        compose.onNodeWithTag("tasks_next_page").assertIsNotEnabled()
        compose.runOnIdle { records.value = records.value.take(7) }
        compose.onNodeWithTag("tasks_page_status").assertTextEquals("2 / 2")
        compose.onNodeWithText("排队 #7").assertIsDisplayed()
    }

    @Test
    fun externalConditionsShowReasonsInsteadOfAPosition() {
        val waiting = record(1, TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK, WaitingReason.LOGIN))))
        show({ listOf(waiting, record(2, TaskState.Queued)) })
        compose.onNodeWithText("等待网络、等待登录").assertIsDisplayed()
        // The waiting task takes no turn, so the queued one is first.
        compose.onNodeWithText("排队 #1").assertIsDisplayed()
    }

    @Test
    fun runningShowsStageAndPercentWithOnlySupportedControlIcons() {
        var selected: TaskControl? = null
        val running = record(1, TaskState.Running(TaskStage.FORMAT_TRANSFER, TaskProgress(512, 2048)))
            .copy(controls = TaskControls(true, true, false, false), restartedTransfer = true)
        show({ listOf(running) }) { _, command -> selected = command }
        compose.onNodeWithText("元数据同步").assertIsDisplayed()
        compose.onNodeWithTag("task_status").assertTextEquals("传输书籍副本 · 25% · 重新下载")
        compose.onNodeWithTag("task_user_mark", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("task_retry").assertDoesNotExist()
        compose.onNodeWithTag("task_resume").assertDoesNotExist()
        compose.onNodeWithTag("task_done").assertDoesNotExist()
        compose.onNodeWithTag("task_pause").performClick()
        assertEquals(TaskControl.PAUSE, selected)
        compose.onNodeWithContentDescription("取消").performClick()
        assertEquals(TaskControl.CANCEL, selected)
    }

    @Test
    fun finishedTasksLiveInTheirOwnTabWithACheckOrARetry() {
        var selected: TaskControl? = null
        val failed = record(1, TaskState.Finished(TaskResult.Failed(StageFailure(TaskStage.METADATA_FETCH,
            TaskError.Source(StorageError(StorageErrorKind.NO_NETWORK)), CommitState.NotCommitted))))
            .copy(controls = TaskControls(false, false, false, true))
        val done = record(2, TaskState.Finished(TaskResult.Completed)).copy(controls = TaskControls(false, false, false, false))
        show({ listOf(record(3, TaskState.Queued), failed, done) }) { _, command -> selected = command }
        compose.onNodeWithText("排队 #1").assertIsDisplayed()
        compose.onNodeWithTag("task_tab_finished").performClick()
        compose.onNodeWithText("排队 #1").assertDoesNotExist()
        compose.onNodeWithText("失败：获取元数据快照（网络不可用）").assertIsDisplayed()
        compose.onNodeWithTag("task_done").assertIsDisplayed()
        // The latest submitted comes first among finished tasks.
        val doneTop = compose.onNodeWithTag("task_${done.id.value}").getBoundsInRoot().top
        val failedTop = compose.onNodeWithTag("task_${failed.id.value}").getBoundsInRoot().top
        assertTrue(doneTop < failedTop)
        compose.onNodeWithTag("task_retry").performClick()
        assertEquals(TaskControl.RETRY, selected)
    }

    @Test
    fun sourceFilterSeparatesUserRequestsFromAutomaticTasks() {
        val automatic = TaskRecord(
            TaskId(UUID.randomUUID()),
            TaskSubmission(TaskRequest.MetadataSync(LibraryId(UUID.randomUUID())), TaskOrigin.STARTUP_SYNC),
            SchedulingPosition(TaskPriority.LOW, QueueSequence(2)),
            state = TaskState.Waiting(FrozenSet(listOf(WaitingReason.NETWORK))),
            controls = TaskControls(false, true, false, false),
        )
        show({ listOf(record(1, TaskState.Queued), automatic) })
        compose.onNodeWithText("排队 #1").assertIsDisplayed()
        compose.onNodeWithText("等待网络").assertIsDisplayed()
        compose.onNodeWithTag("task_filter_automatic").performClick()
        compose.onNodeWithText("排队 #1").assertDoesNotExist()
        compose.onNodeWithText("等待网络").assertIsDisplayed()
        compose.onNodeWithTag("task_user_mark", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("task_filter_user").performClick()
        compose.onNodeWithText("排队 #1").assertIsDisplayed()
        compose.onNodeWithText("等待网络").assertDoesNotExist()
    }

    @Test
    fun librarySyncIsNamedAsMetadataSyncAndDirectoryLoadsByTheirOperation() {
        fun candidate(sequence: Int, operation: String) = record(sequence, TaskState.Queued).let {
            val context = CandidateContext(UUID.randomUUID(), BackendKind.ONEDRIVE, UUID.randomUUID())
            it.copy(submission = it.submission.copy(request = TaskRequest.CandidateConfiguration(context, operation)))
        }
        show({ listOf(candidate(1, TaskRequest.CandidateConfiguration.LIBRARY_SYNC), candidate(2, "onedrive_browse")) })
        compose.onNodeWithText("元数据同步").assertIsDisplayed()
        compose.onNodeWithText("加载目录").assertIsDisplayed()
    }

    @Test
    fun controlErrorHasItsOwnVisibleMessage() {
        show({ emptyList() }, operationFailed = true)
        compose.onNodeWithText("任务控制未完成，任务状态可能已改变；请查看最新状态后重试。").assertIsDisplayed()
        compose.onNodeWithText("无法读取或更新任务状态，请重试。").assertDoesNotExist()
    }

    @Test
    fun automaticCoverBatchesFoldIntoOneRowUntilExpanded() {
        val library = LibraryId(UUID.randomUUID())
        val batches = (1..3).map { n ->
            TaskRecord(TaskId(UUID.randomUUID()), TaskSubmission(TaskRequest.CoverLoad(library,
                FrozenSet(listOf(io.github.chenxiex.calibrecloud.model.BookKey(library, n.toLong(), UUID.randomUUID())))), TaskOrigin.VISIBLE_COVER),
                SchedulingPosition(TaskPriority.LOW, QueueSequence(10L + n)),
                state = TaskState.Finished(if (n == 3) TaskResult.Completed else TaskResult.Cancelled(CommitState.NotCommitted)),
                controls = TaskControls(false, false, false, false))
        }
        val sync = record(1, TaskState.Finished(TaskResult.Completed))
        show({ batches + sync })
        compose.onNodeWithTag("task_tab_finished").performClick()
        // The latest batch comes first in the finished tab, so the folded row repeats its result.
        compose.onNodeWithTag("task_cover_group").assertIsDisplayed()
        compose.onNodeWithText("3 个批次 · 已完成").assertIsDisplayed()
        batches.forEach { compose.onNodeWithTag("task_${it.id.value}").assertDoesNotExist() }
        compose.onNodeWithTag("task_${sync.id.value}").assertIsDisplayed()
        compose.onNodeWithTag("task_cover_group").performClick()
        batches.forEach { compose.onNodeWithTag("task_${it.id.value}").assertIsDisplayed() }
        compose.onNodeWithContentDescription("收起").assertIsDisplayed()
        compose.onNodeWithTag("task_cover_group").performClick()
        batches.forEach { compose.onNodeWithTag("task_${it.id.value}").assertDoesNotExist() }
    }

    private fun record(sequence: Int, state: TaskState): TaskRecord = TaskRecord(
        TaskId(UUID.randomUUID()),
        TaskSubmission(TaskRequest.MetadataSync(LibraryId(UUID.randomUUID())), TaskOrigin.MANUAL_SYNC),
        SchedulingPosition(TaskPriority.HIGH, QueueSequence(sequence.toLong())),
        state = state,
        controls = TaskControls(false, true, false, false),
    )
}
