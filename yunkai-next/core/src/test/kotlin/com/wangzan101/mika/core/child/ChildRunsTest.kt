package com.wangzan101.mika.core.child

import com.wangzan101.mika.core.trigger.Scheduled
import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.cap.web.FakeWebPort
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.observation.Trust
import com.wangzan101.mika.core.run.Attendance
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildRunsTest {
    @Test fun spawnReturnsBoundedObservationAndPersistsChildFirst() = runBlocking {
        val parent = RunSpec(RunId("parent"), "goal", "thread")
        val store = MemoryChildRunStore()
        val runner = ChildAgentRunner({ spec ->
            AgentLoop(FakeModel(answerReply("子结论")),
                toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec), "BASE", confirmations = null)
        }, store, ownerAlive = { store.all().single().phase == ChildPhase.Completed })
        val tools = toolDispatcher(FakeScreen(), runSpec = parent, childHost = runner)
        val result = tools.execute(Part.ToolCall("s", "spawn_agent", """{"goal":"只查一条事实"}"""), ActionPass())
        val record = store.all().single()
        assertEquals(ChildPhase.Completed, record.phase)
        assertEquals("parent", record.parentId)
        assertEquals("子结论", record.result)
        assertEquals(Trust.Untrusted, result.observations.single().trust)
        assertEquals("子结论", result.observations.single().text)
    }

    @Test fun childRestrictionsCannotBeBypassedByRunSpecFlags() = runBlocking {
        val spec = RunSpec(RunId("child"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Present),
            capabilities = null, readOnly = false, parent = RunId("parent"))
        val tools = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = spec,
            childHost = ChildRunHost { _, _ -> error("must not spawn") }, scheduleTrigger = { Scheduled(true) })
        val names = tools.definitions().map { it.name }
        assertTrue("web_read_page" in names)
        assertFalse(names.any { it.startsWith("phone_") || it == "web_interact" || it == "write_file" ||
            it == "spawn_agent" || it == "memory_save" || it == "schedule_task" ||
            it == "ask_user" || it == "hand_to_user" || it == "request_confirmation" })
        assertEquals("unknown_tool", tools.execute(Part.ToolCall("w", "web_interact", "{}"), ActionPass())
            .content["error"].toString().trim('"'))
    }

    @Test fun absentOwnerLeavesUnknownResultWithoutRerun() = runBlocking {
        val store = MemoryChildRunStore()
        val spec = RunSpec(RunId("c"), "research", "thread",
            provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended), parent = RunId("p"))
        val runner = ChildAgentRunner({ s -> AgentLoop(FakeModel(answerReply("answer")),
            toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = s), "BASE", confirmations = null) }, store,
            ownerAlive = { false })
        assertEquals(ChildPhase.Unknown, runner.spawn(spec, null).phase)
        assertEquals(ChildPhase.Unknown, store.get("c")?.phase)
    }
}
