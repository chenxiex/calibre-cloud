package io.github.chenxiex.calibrecloud.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskControl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Production static page rendering, without source access or a background executor. */
@RunWith(AndroidJUnit4::class)
class TaskScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun emptyPageHasNoEnabledPagination() {
        compose.setContent { Column { TaskList(emptyList(), false) { _, _ -> } } }
        compose.onNodeWithText("暂无任务").assertIsDisplayed()
        compose.onNodeWithText("上一项任务").assertIsNotEnabled()
        compose.onNodeWithText("下一项任务").assertIsNotEnabled()
    }

    @Test
    fun longQueueUsesExplicitPagesAndClampsAfterRemoval() {
        val records = mutableStateOf((1..12).map { record(it, TaskState.Queued) })
        compose.setContent { Column { TaskList(records.value, false) { _, _ -> } } }
        repeat(11) { compose.onNodeWithText("下一项任务").performClick() }
        compose.onNodeWithText("任务 12 / 12").assertIsDisplayed()
        compose.onNodeWithText("下一项任务").assertIsNotEnabled()
        compose.runOnIdle { records.value = records.value.take(2) }
        compose.onNodeWithText("任务 2 / 2").assertIsDisplayed()
        compose.onNodeWithText("下一项任务").assertIsNotEnabled()
    }

    @Test
    fun unknownProgressAndOnlySupportedControlsAreVisible() {
        var selected: TaskControl? = null
        val running = record(1, TaskState.Running(TaskStage.FORMAT_TRANSFER))
            .copy(controls = TaskControls(true, true, false, false), restartedTransfer = true)
        compose.setContent { Column { TaskList(listOf(running), false) { _, command -> selected = command } } }
        compose.onNodeWithText("执行中：传输书籍副本").assertIsDisplayed()
        compose.onNodeWithText("原断点无法复用，重新传输完整文件。").assertIsDisplayed()
        compose.onNodeWithText("正在处理，尚无可计算的总量").assertIsDisplayed()
        compose.onNodeWithText("重试").assertDoesNotExist()
        compose.onNodeWithText("继续").assertDoesNotExist()
        compose.onNodeWithText("暂停").performClick()
        assertEquals(TaskControl.PAUSE, selected)
    }

    @Test
    fun pausedTasksDoNotHaveOrConsumeSchedulingPositions() {
        val records = listOf(record(1, TaskState.Paused(TaskStage.METADATA_FETCH)),
            record(2, TaskState.Queued))
        compose.setContent { Column { TaskList(records, false) { _, _ -> } } }
        compose.onNodeWithText("优先级顺序：1；满足条件后执行").assertDoesNotExist()
        compose.onNodeWithText("下一项任务").performClick()
        compose.onNodeWithText("优先级顺序：1；满足条件后执行").assertIsDisplayed()
    }

    @Test
    fun controlErrorHasItsOwnVisibleMessage() {
        compose.setContent { Column { TaskList(emptyList(), false, true) { _, _ -> } } }
        compose.onNodeWithText("任务控制未完成，任务状态可能已改变；请查看最新状态后重试。").assertIsDisplayed()
        compose.onNodeWithText("无法读取或更新任务状态，请重试。").assertDoesNotExist()
    }

    private fun record(sequence: Int, state: TaskState): TaskRecord = TaskRecord(
        TaskId(UUID.randomUUID()),
        TaskSubmission(TaskRequest.MetadataSync(LibraryId(UUID.randomUUID())), TaskOrigin.MANUAL_SYNC),
        SchedulingPosition(TaskPriority.HIGH, QueueSequence(sequence.toLong())),
        state = state,
        controls = TaskControls(false, true, false, false),
    )
}
