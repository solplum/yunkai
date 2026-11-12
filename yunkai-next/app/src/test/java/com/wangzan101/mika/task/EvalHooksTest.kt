package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EvalHooksTest {
    private val hooks = EvalHooks()

    @After fun reset() { hooks.reset() }

    @Test fun autoAnswerUsedWhenSet() {
        var asked: Pair<String, List<String>?>? = null
        hooks.autoAnswer = { prompt, options -> asked = prompt to options; "选第一个" }
        assertEquals("选第一个", hooks.answerFor(TaskState.WaitingInput("要哪一个?", listOf("A", "B"))))
        assertEquals("要哪一个?" to listOf("A", "B"), asked)
        // 只回答追问,别的状态不碰
        assertNull(hooks.answerFor(TaskState.Running(3, "")))
    }

    @Test fun noHookMeansWaitForUser() {
        assertNull(hooks.answerFor(TaskState.WaitingInput("要哪一个?")))
        // 钩子不答(返回空)也一样等用户,直到评测超时
        hooks.autoAnswer = { _, _ -> null }
        assertNull(hooks.answerFor(TaskState.WaitingInput("要哪一个?")))
        hooks.autoAnswer = { _, _ -> "  " }
        assertNull(hooks.answerFor(TaskState.WaitingInput("要哪一个?")))
    }

    @Test fun autoDecideOnlyForConfirmation() {
        val r = ConfirmationRequest(IrrevAction.delete, "笔记", "删掉", null, "com.notes")
        assertNull(hooks.decisionFor(TaskState.WaitingConfirmation(r)))
        var seen: ConfirmationRequest? = null
        hooks.autoDecideConfirmation = { seen = it; false }
        assertEquals(false, hooks.decisionFor(TaskState.WaitingConfirmation(r)))
        assertEquals(r, seen)
        assertNull(hooks.decisionFor(TaskState.WaitingInput("?")))
        hooks.reset()
        assertNull(hooks.autoDecideConfirmation)
    }
}
