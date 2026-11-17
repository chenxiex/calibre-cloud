package io.github.chenxiex.calibrecloud.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chenxiex.calibrecloud.R
import io.github.chenxiex.calibrecloud.model.CustomColumnId
import io.github.chenxiex.calibrecloud.model.LibraryId
import io.github.chenxiex.calibrecloud.storage.api.StorageError
import io.github.chenxiex.calibrecloud.storage.api.StorageErrorKind
import io.github.chenxiex.calibrecloud.tasks.api.*
import io.github.chenxiex.calibrecloud.tasks.persistence.TaskCodec
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskErrorPersistenceTest {
    @Test fun sourceResponseFailureRetainsItsReasonAfterQueuePayloadReload() {
        val error = TaskError.Source(StorageError(StorageErrorKind.INVALID_SOURCE_RESPONSE))
        val record = TaskRecord(TaskId(UUID.randomUUID()),
            TaskSubmission(TaskRequest.ReadStatusWrite(LibraryId(UUID.randomUUID()), CustomColumnId(1, "#read"),
                UUID.randomUUID()), TaskOrigin.USER_READ_STATUS),
            SchedulingPosition(TaskPriority.HIGH, QueueSequence(1)),
            state = TaskState.Finished(TaskResult.Failed(StageFailure(TaskStage.WRITE_SNAPSHOT, error))),
            controls = TaskControls(false, false, false, true))

        val restored = TaskCodec.decode(TaskCodec.encode(record))
        assertEquals(record, restored)
        val restoredError = ((restored.state as TaskState.Finished).result as TaskResult.Failed).failure.error
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(context.getString(R.string.task_error_response), context.getString(taskErrorResource(restoredError)))
        assertEquals("存储服务响应异常，无法验证源数据", context.getString(taskErrorResource(restoredError)))
    }
}
