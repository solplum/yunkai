package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Tests the real central executor, not just the pure policy. */
class YunkaiApprovalProvenanceTest {
    @Test fun unattendedAndNonUiDeletionNeverReachesDevice() = runTest {
        for (origin in RunOrigin.entries) for (attendance in Attendance.entries) {
            if (origin == RunOrigin.Ui && attendance != Attendance.Unattended) continue
            var taps = 0
            val screen = object : ScreenPort by FakeScreen() {
                override suspend fun snapshot() = ScreenSnapshot("s1", "com.chat", listOf(
                    UiElement("@e1", "button", "删除", 50, 50, listOf(0, 0, 100, 100), null, true, false, false)
                ), 1080, 2400)
                override suspend fun peek() = snapshot()
                override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { taps++; return ActionResult.Ok() }
            }
            val tools = toolDispatcher(screen)
            tools.execute(Part.ToolCall("read", "phone_observe", "{}"))
            val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {},
                recorder = ActionRecorder(RunSink.NONE), provenance = RunProvenance(origin, attendance))
            val out = executor.run(Part.ToolCall("delete", "phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""), Origin.Model(1))
            assertEquals("$origin $attendance", "approval_unavailable", (out as ActionOutcome.Refused).code)
            assertEquals(0, taps)
        }
    }
}
