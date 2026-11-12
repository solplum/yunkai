package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.EffectTuning
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分发器判动作效果:异步更新再看一次、端口等过就不再等、多花的时间有上限、changed 跟着效果走。 */
class ToolDispatcherEffectTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.kind() = this["effect"]!!.jsonObject["kind"]!!.jsonPrimitive.content

    private fun page(vararg names: String) = ScreenSnapshot("p", "com.x", names.mapIndexed { i, n ->
        UiElement("@e${i + 1}", "text", n, 500, 100 + i * 100, listOf(0, 40 + i * 40, 999, 70 + i * 40), null, true, false, false)
    }, 1080, 2400)

    /** 动作 [afterMs] 毫秒之后界面才换成 [later](异步更新);[peekMs] 是看一眼要花的时间;[result] 是端口的动作结果。 */
    private class LateScreen(
        val now: () -> Long, val first: ScreenSnapshot, val later: ScreenSnapshot, val afterMs: Long,
        val peekMs: Long = 0, val result: ActionResult = ActionResult.Ok(),
    ) : ScreenPort by FakeScreen() {
        var actedAt = Long.MAX_VALUE
        var settles = 0
        private fun current() = if (now() - actedAt >= afterMs) later else first
        override suspend fun snapshot() = current()
        override suspend fun peek(): ScreenSnapshot { if (peekMs > 0) delay(peekMs); return current() }
        override suspend fun waitStable(timeoutMs: Long): Boolean { settles++; return true }
        override suspend fun tapXY(x: Int, y: Int): ActionResult { actedAt = now(); return result }
        override suspend fun back(): ActionResult { actedAt = now(); return result }
    }

    @Test fun asyncUpdateIsCaughtByTheRecheck() = runTest {
        val s = LateScreen({ currentTime }, page("列表", "一", "二"), page("详情", "价格", "加入购物车", "评价"), afterMs = EffectTuning.RECHECK_MS / 2)
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("done", r.content.kind())
        assertEquals(true, r.changed)
    }

    // 动作之后新出现「到上限了」的提示:结果里写 limitReached 和停下的提示;动作前就在屏上的说明文字不算
    @Test fun limitNoticeAfterTheActionIsReported() = runTest {
        val s = LateScreen({ currentTime }, page("职位详情", "立即沟通"), page("职位详情", "立即沟通", "今日沟通次数已用完，明天再来吧"), afterMs = 0)
        val c = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}""")).content
        assertEquals("今日沟通次数已用完，明天再来吧", c["limitReached"]!!.jsonPrimitive.content)
        assertTrue(c["hint"]!!.jsonPrimitive.content.contains("stop here"))
        val faq = LateScreen({ currentTime }, page("常见问题", "次数已达上限怎么办"), page("常见问题", "次数已达上限怎么办", "答案"), afterMs = 0)
        assertEquals(null, toolDispatcher(faq).execute(call("phone_tap", """{"x":500,"y":500}""")).content["limitReached"])
    }

    // 提示条来得晚一点(第一次看没有、重看时才有)也算
    @Test fun lateLimitToastIsCaughtByTheRecheck() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表", "操作过于频繁，请稍后再试"), afterMs = EffectTuning.RECHECK_MS / 2)
        val c = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}""")).content
        assertEquals("操作过于频繁，请稍后再试", c["limitReached"]!!.jsonPrimitive.content)
    }

    @Test fun stillNothingAfterTheRecheckIsNoEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0)
        val r = toolDispatcher(s).execute(call("phone_tap", """{"x":500,"y":500}"""))
        assertEquals("no_effect", r.content.kind())
        assertEquals(false, r.changed)
    }

    @Test fun portThatAlreadyWaitedIsNotWaitedForAgain() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("详情", "价格", "加入购物车"), afterMs = 0, result = ActionResult.Ok(changed = true))
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertEquals("done", r.content.kind())
        assertEquals(0, s.settles)
    }

    @Test fun portSaysChangedIsNeverReportedAsNoEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0, result = ActionResult.Ok(changed = true))
        val c = toolDispatcher(s).execute(call("phone_back")).content
        assertEquals("small_change", c.kind())
        assertEquals("true", c["changed"]!!.jsonPrimitive.content)
    }

    @Test fun portSaysUnchangedButTheInterfaceChangedFollowsTheEffect() = runTest {
        val s = LateScreen({ currentTime }, page("列表"), page("详情", "价格", "加入购物车"), afterMs = 0, result = ActionResult.Ok(changed = false))
        val r = toolDispatcher(s).execute(call("phone_back"))
        assertEquals("done", r.content.kind())
        assertEquals(true, r.changed)
        assertEquals("true", r.content["changed"]!!.jsonPrimitive.content)
    }

    @Test fun extraTimePerActionIsBounded() = runTest {
        // 看一眼要 2 秒的慢端口:判效果最多多花 BUDGET_MS,到了就按端口的事实下结论
        val s = LateScreen({ currentTime }, page("列表"), page("列表"), afterMs = 0, peekMs = 2_000, result = ActionResult.Ok(changed = true))
        val d = toolDispatcher(s)
        val start = currentTime
        val c = d.execute(call("phone_back")).content
        val spent = currentTime - start
        // 动作前看一眼、动作后的判断各有上限
        assertTrue("spent $spent", spent <= EffectTuning.PROBE_MS + EffectTuning.BUDGET_MS)
        assertEquals("small_change", c.kind())
    }
}
