package com.wangzan101.mika.core.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentGateTest {
    @Test fun classifierSeesCurrentMessageAndRecentContextWithoutTools() = runTest {
        val judge = FakeModel(com.wangzan101.mika.core.testing.answerReply("DISCUSS"))
        val decision = IntentGate(judge).assess("以后可以帮我同步发布，对吧？",
            listOf(Turn("打开两个 App 看看", "看过了", "succeeded")))
        assertEquals(IntentGate.Intent.Discuss, decision)
        assertTrue(judge.offeredTools.single().isEmpty())
        val input = judge.requests.single().single().parts.single() as com.wangzan101.mika.core.model.Part.Text
        assertTrue(input.text.contains("用户这次原话:\n以后可以帮我同步发布，对吧？"))
    }

    @Test fun onlyKnownIntentLabelsAreAccepted() {
        assertEquals(IntentGate.Intent.Read, IntentGate.parse(" READ "))
        assertEquals(IntentGate.Intent.Act, IntentGate.parse("ACT"))
        assertNull(IntentGate.parse("maybe"))
    }

    @Test fun fallsBackToMainModelWhenLightJudgeFails() = runTest {
        val light = FakeModel(IllegalStateException("unavailable"))
        val main = FakeModel(com.wangzan101.mika.core.testing.answerReply("READ"))
        assertEquals(IntentGate.Intent.Read, IntentGate(light, main).assess("查一下订单", emptyList()))
        assertTrue(light.offeredTools.single().isEmpty())
        assertTrue(main.offeredTools.single().isEmpty())
    }
}
