package com.wangzan101.mika.core.record

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.SystemPrompt
import com.wangzan101.mika.core.prompt.PromptVersion
import com.wangzan101.mika.core.testing.fakeModel
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * 运行记录里记下本任务提示词的版本:每组都展开时发给模型的系统提示词加工具定义的摘要,
 * 和这次实际展开了哪几组无关(只有手机时,展开了手机一组后看到的就是它)。
 */
class PromptVersionRecordTest {
    private suspend fun run(tools: ToolDispatcher): Pair<String, String> {
        val events = mutableListOf<RunEvent>()
        val m = fakeModel("main") { call("phone_observe"); answer("好了") }
        AgentLoop(m, tools, SystemPrompt.identity(), sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }).run("t")
        val r = m.requests.last()
        return events.filterIsInstance<RunEvent.PromptVersion>().single().hash to PromptVersion.of(r.system, r.tools)
    }

    @Test fun recordsHashOfWhatTheModelSaw() = runTest {
        val (phone, expected) = run(toolDispatcher(FakeScreen()))
        assertEquals(expected, phone)
        val (web, _) = run(toolDispatcher(FakeScreen(), web = FakeWebPort()))
        assertNotEquals(phone, web)
    }
}
