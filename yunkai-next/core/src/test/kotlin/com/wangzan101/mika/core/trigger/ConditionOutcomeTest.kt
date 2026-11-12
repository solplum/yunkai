package com.wangzan101.mika.core.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConditionOutcomeTest {
    @Test fun parsesCheckedResultAndRejectsUnclearReply() {
        assertEquals(ConditionOutcome.Checked(true, "找到新消息"),
            ConditionOutcome.parse("""{"met":true,"evidence":"找到新消息"}"""))
        assertEquals(ConditionOutcome.Checked(false, null),
            ConditionOutcome.parse("```json\n{\"met\":false}\n```"))
        assertTrue(ConditionOutcome.parse("暂时没有") is ConditionOutcome.Error)
        assertTrue(ConditionOutcome.parse("""{"met":"false"}""") is ConditionOutcome.Error)
    }
}
