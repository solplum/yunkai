package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Until
import com.wangzan101.mika.core.uistate.PageDialog
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.UiWindowTree
import com.wangzan101.mika.core.uistate.WebInfo
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 一屏的样子:包名、元素名字、窗口(编号, 类型, 包名)、页面。 */
private data class Look(
    val pkg: String = "com.x",
    val names: List<String> = listOf("搜索框"),
    val windows: List<Triple<Int, String, String>> = listOf(Triple(1, "application", "com.x")),
    val web: WebInfo? = null,
)

/** 屏幕按虚拟时间换:[frames] 是 (从第几毫秒起, 样子),读屏几次都不影响看到什么。 */
private class TimedScreen(private val now: () -> Long, vararg val frames: Pair<Long, Look>) : ScreenPort by FakeScreen() {
    val calls = mutableListOf<String>()
    var stableCalls = 0
    var changeCalls = 0
    private var reads = 0
    private fun current(): Look = frames.last { it.first <= now() }.second
    private fun build(id: String): ScreenSnapshot {
        val l = current()
        return ScreenSnapshot(id, l.pkg, l.names.mapIndexed { i, n ->
            UiElement("@e${i + 1}", "button", n, 540, 100 + i * 100, listOf(0, i * 40, 999, i * 40 + 30), null, true, false, false)
        }, 1080, 2400, web = l.web, windows = l.windows.map { (id, type, p) -> UiWindowTree(id, type, 0, null, p, true, true, emptyList()) })
    }
    override suspend fun snapshot(): ScreenSnapshot = build("p${++reads}")
    override suspend fun peek(): ScreenSnapshot = build("peek")
    override suspend fun back() = com.wangzan101.mika.core.uistate.ActionResult.Ok(changed = true).also { calls += "back" }
    override suspend fun waitStable(timeoutMs: Long): Boolean { stableCalls++; return true }
    override suspend fun awaitChange(timeoutMs: Long): Boolean { changeCalls++; kotlinx.coroutines.delay(timeoutMs); return false }
}

class BatchWaitTest {
    private val sink = object : RunSink { override fun event(e: com.wangzan101.mika.core.record.RunEvent) {} }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.entries() = this["steps"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.wait() = this["wait"]!!.jsonObject

    private fun TestScope.runner(s: ScreenPort, steer: () -> Boolean = { false }): BatchRunner {
        val d = toolDispatcher(s)
        val clock = { testScheduler.currentTime }
        return BatchRunner(d, OperationGuards(), ActionExecutor(d, OperationGuards(), ConfirmationGate(), gate = {}, recorder = ActionRecorder(sink, clock)), steerWaiting = steer)
    }
    private fun back(until: Until? = null) = BatchStep("phone_back", "{}", until)

    private val loading = Look(names = listOf("加载中"))
    private val results = Look(names = listOf("搜索结果", "第一条"))

    @Test fun conditionMetAfterSomePollsContinuesWithoutTheModel() = runTest {
        val s = TimedScreen({ testScheduler.currentTime }, 0L to loading, 2_600L to results)
        val r = runner(s)
        val out = r.run(listOf(back(Until(appears = "搜索结果")), back()), "c1").content
        assertEquals("true", out.s("ok"))
        assertEquals(listOf("back", "back"), s.calls)
        val w = out.entries()[0].wait()
        assertEquals("true", w.s("met"))
        assertTrue(w["waited_ms"]!!.jsonPrimitive.long in 2_600L..3_200L)
        assertTrue(s.changeCalls >= 3)
        assertEquals(w["waited_ms"]!!.jsonPrimitive.long, r.waitedMs)
    }

    @Test fun timeoutStopsWithReasonAndTheCondition() = runTest {
        val s = TimedScreen({ testScheduler.currentTime }, 0L to loading)
        val out = runner(s).run(listOf(back(Until(appears = "搜索结果", timeoutMs = 3_000)), back()), "c1").content
        assertEquals("expectation_unmet", out.s("reason"))
        val last = out.entries().last()
        assertEquals("搜索结果", last.s("expect"))
        assertEquals("timeout", last.wait().s("stopped"))
        assertTrue(last.wait()["waited_ms"]!!.jsonPrimitive.long >= 3_000)
        assertEquals(listOf("back"), s.calls)
    }

    @Test fun popupInTheSameAppStopsEarly() = runTest {
        val dialog = loading.copy(windows = listOf(Triple(1, "application", "com.x"), Triple(2, "application", "com.x")))
        val s = TimedScreen({ testScheduler.currentTime }, 0L to loading, 1_000L to dialog)
        val out = runner(s).run(listOf(back(Until(appears = "搜索结果")), back()), "c1").content
        assertEquals("popup", out.s("reason"))
        val w = out.entries().last().wait()
        assertEquals("popup", w.s("stopped"))
        assertTrue(w["waited_ms"]!!.jsonPrimitive.long < 3_000)
        assertEquals(listOf("back"), s.calls)
    }

    @Test fun anotherPackagesWindowIsASystemDialog() = runTest {
        val permission = loading.copy(windows = listOf(Triple(1, "application", "com.x"), Triple(7, "application", "android.permission.ui")))
        val s = TimedScreen({ testScheduler.currentTime }, 0L to loading, 500L to permission)
        val out = runner(s).run(listOf(back(Until(appears = "搜索结果"))), "c1").content
        assertEquals("system_dialog", out.s("reason"))
    }

    @Test fun appSwitchStopsEarlyUnlessItIsTheAppWaitedFor() = runTest {
        val other = Look(pkg = "com.other", names = listOf("别的"), windows = listOf(Triple(3, "application", "com.other")))
        val s = TimedScreen({ testScheduler.currentTime }, 0L to loading, 800L to other)
        val out = runner(s).run(listOf(back(Until(appears = "搜索结果")), back()), "c1").content
        assertEquals("app_switched", out.s("reason"))
        assertTrue(out.entries().last().wait()["waited_ms"]!!.jsonPrimitive.long < 2_000)
        val s2 = TimedScreen({ testScheduler.currentTime }, 0L to loading, 800L to other)
        val ok = runner(s2).run(listOf(back(Until(place = "com.other")), back()), "c2").content
        assertEquals("true", ok.s("ok"))
        assertEquals("true", ok.entries()[0].wait().s("met"))
    }

    @Test fun pageDialogStopsAtOnce() = runTest {
        val page = WebInfo("https://a.example/", "A", "t1", 200)
        val s = TimedScreen({ testScheduler.currentTime },
            0L to Look(pkg = "a.example", windows = emptyList(), web = page),
            700L to Look(pkg = "a.example", names = emptyList(), windows = emptyList(), web = page.copy(dialog = PageDialog(PageDialog.ALERT, "hi"))))
        val out = runner(s).run(listOf(back(Until(appears = "完成"))), "c1").content
        assertEquals("popup", out.s("reason"))
    }

    @Test fun webAddressChangeIsNotAnAppSwitch() = runTest {
        val page = WebInfo("https://a.example/", "A", "t1", 200)
        val s = TimedScreen({ testScheduler.currentTime },
            0L to Look(pkg = "a.example", windows = emptyList(), web = page),
            700L to Look(pkg = "b.example", names = listOf("完成"), windows = emptyList(), web = page.copy(url = "https://b.example/done")))
        val out = runner(s).run(listOf(back(Until(appears = "完成"))), "c1").content
        assertEquals("true", out.s("ok"))
        val s2 = TimedScreen({ testScheduler.currentTime },
            0L to Look(pkg = "a.example", windows = emptyList(), web = page),
            700L to Look(pkg = "b.example", windows = emptyList(), web = page.copy(url = "https://b.example/done")))
        assertEquals("true", runner(s2).run(listOf(back(Until(place = "/done"))), "c2").content.s("ok"))
    }

    @Test fun expectAloneStillWorksAndTextAlreadyThereMatchesAtOnce() = runTest {
        val (steps, problem) = BatchRunner.parse("""{"steps":[{"tool":"phone_back","args":{},"expect":"搜索结果"}]}""", BATCHABLE.toSet(), BATCHABLE)
        assertNull(problem)
        assertEquals(Until(appears = "搜索结果", timeoutMs = Until.DEFAULT_MS), steps!!.single().until)
        val s = TimedScreen({ testScheduler.currentTime }, 0L to results)
        val out = runner(s).run(steps, "c1").content
        assertEquals("true", out.s("ok"))
        assertEquals(0L, out.entries()[0].wait()["waited_ms"]!!.jsonPrimitive.long)
        // 不带条件的步不等,结果里也没有 wait
        val plain = runner(TimedScreen({ testScheduler.currentTime }, 0L to results)).run(listOf(back(), back()), "c2").content
        assertTrue(plain.entries().all { it["wait"] == null })
    }

    @Test fun waitsForTextToGo() = runTest {
        val s = TimedScreen({ testScheduler.currentTime }, 0L to loading, 1_500L to results)
        val out = runner(s).run(listOf(back(Until(gone = "加载中")), back()), "c1").content
        assertEquals("true", out.s("ok"))
        assertTrue(out.entries()[0].wait()["waited_ms"]!!.jsonPrimitive.long >= 1_500)
        // 什么都读不到不算「已经没了」
        val blank = TimedScreen({ testScheduler.currentTime }, 0L to Look(names = emptyList()))
        assertEquals("expectation_unmet", runner(blank).run(listOf(back(Until(gone = "加载中", timeoutMs = 2_000))), "c2").content.s("reason"))
    }

    @Test fun waitsForAStableScreenThroughThePort() = runTest {
        val s = TimedScreen({ testScheduler.currentTime }, 0L to results)
        val out = runner(s).run(listOf(back(Until(stable = true))), "c1").content
        assertEquals("true", out.s("ok"))
        assertTrue(s.stableCalls >= 1)
        assertEquals("true", out.entries()[0].wait().s("met"))
    }

    @Test fun newMessageCutsTheWaitShort() = runTest {
        var steer = false
        val s = TimedScreen({ testScheduler.currentTime }, 0L to loading)
        val r = runner(s) { steer || (testScheduler.currentTime >= 1_000).also { steer = it } }
        val out = r.run(listOf(back(Until(appears = "搜索结果")), back(), back()), "c1").content
        assertEquals(BatchRunner.NEW_MESSAGE, out.s("reason"))
        assertEquals(listOf("back"), s.calls)
        val e = out.entries()
        assertEquals("new_message", e[0].wait().s("stopped"))
        assertTrue(e[0].wait()["waited_ms"]!!.jsonPrimitive.long < 2_000)
        assertEquals(listOf(BatchRunner.NEW_MESSAGE, BatchRunner.NEW_MESSAGE), e.drop(1).map { it.s("error") })
        assertEquals(1, out["completed"]!!.jsonPrimitive.int)
        // 最后一步的等待被打断:停在这一步,说清楚是等待被打断
        var steer2 = false
        val last = runner(TimedScreen({ testScheduler.currentTime }, 0L to loading)) { steer2 || (testScheduler.currentTime >= 1_000).also { steer2 = it } }
            .run(listOf(back(Until(appears = "搜索结果"))), "c2").content
        assertEquals(BatchRunner.NEW_MESSAGE, last.s("reason"))
        assertTrue(last.s("message"), "waiting for its condition was cut short" in last.s("message"))
        assertEquals("false", last.s("counts_as_failure"))
    }

    @Test fun parseChecksTheWaitFields() {
        val avail = BATCHABLE.toSet()
        fun p(step: String) = BatchRunner.parse("""{"steps":[{"tool":"phone_back","args":{},$step}]}""", avail, BATCHABLE)
        assertEquals(Until(gone = "加载中", place = "com.x", stable = true, timeoutMs = 12_500),
            p(""""expect_gone":"加载中","expect_app":"com.x","wait_stable":true,"timeout_s":12.5""").first!!.single().until)
        assertTrue(p(""""timeout_s":5""").second!!.contains("only goes with"))
        assertTrue(p(""""expect":"x","timeout_s":30""").second!!.contains("1-20"))
        assertTrue(p(""""expect":"x","timeout_s":"5"""").second!!.contains("timeout_s"))
        assertTrue(p(""""wait_stable":"yes"""").second!!.contains("wait_stable"))
        assertTrue(p(""""expect_gone":"  """").second!!.contains("expect_gone"))
        assertNull(p(""""wait_stable":false""").first!!.single().until)
        assertTrue(p(""""until":{}""").second!!.contains("unknown field"))
    }
}
