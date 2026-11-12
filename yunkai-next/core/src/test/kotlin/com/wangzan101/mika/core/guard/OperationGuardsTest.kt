package com.wangzan101.mika.core.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull

import org.junit.Assert.assertTrue
import org.junit.Test

class OperationGuardsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun coordinateBucketing() {
        // 坐标按 50 取整、忽略 reason 和键顺序、滑动只记方向。
        assertEquals(
            actionSignature("phone_tap", obj("""{"x":101,"y":149,"reason":"one"}""")),
            actionSignature("phone_tap", obj("""{"reason":"two","y":145,"x":105}""")),
        )
        assertEquals(
            actionSignature("phone_swipe", obj("""{"startX":10,"startY":90,"endX":15,"endY":10}""")),
            actionSignature("phone_swipe", obj("""{"startX":30,"startY":70,"endX":31,"endY":20}""")),
        )
        assertNotEquals(
            actionSignature("phone_tap", obj("""{"x":149,"y":100}""")),
            actionSignature("phone_tap", obj("""{"x":150,"y":100}""")),
        )
        assertNotEquals(
            actionSignature("phone_swipe", obj("""{"startX":10,"startY":90,"endX":15,"endY":10}""")),
            actionSignature("phone_swipe", obj("""{"startX":10,"startY":10,"endX":15,"endY":90}""")),
        )
    }

    @Test fun repeatedTapAloneDoesNotMeanStuck() {
        val g = OperationGuards()
        repeat(80) { i ->
            assertTrue(g.beforeAction("phone_tap", obj("""{"x":105,"y":106}""")).isEmpty())
            g.noteAction()
            assertTrue(g.frame("f$i", i.toLong()).isEmpty())
        }
        assertNull(g.stop)
    }

    @Test fun diagnosticToolsUseHigherThresholdsByToolName() {
        val g = OperationGuards()
        val perCall = (1..15).map { g.beforeAction("phone_observe", obj("""{"reason":"r$it"}"""), diagnostic = true) }
        assertTrue(perCall.all { it.isEmpty() })
        assertNull(g.stop)
    }

    @Test fun loopWindowIsSixteenSteps() {
        val g = OperationGuards()
        g.beforeAction("phone_tap", obj("""{"x":1,"y":1}"""))
        g.beforeAction("phone_tap", obj("""{"x":1,"y":1}"""))
        repeat(15) { g.beforeAction("phone_tap", obj("""{"x":${100 + it * 60},"y":500}""")) }
        // 前两次 tap 已滑出 16 步窗口,第三次不算重复。
        assertTrue(g.beforeAction("phone_tap", obj("""{"x":1,"y":1}""")).isEmpty())
    }

    @Test fun lookingWithoutActingIsNotNoProgress() {
        val g = OperationGuards()
        g.frame("initial", 0L)
        repeat(20) { g.frame("look-$it", 0L) }
        assertEquals(0, g.unchangedFrames)
        g.noteAction()
        g.frame("after-action", 0L)
        assertEquals(1, g.unchangedFrames)
    }

    @Test fun unchangedScreenNudgesAt3_6_9StopsAt12() {
        val g = OperationGuards()
        g.frame("f0", 42L)
        val perAction = (1..12).map { g.noteAction(); g.frame("f$it", 42L) }
        assertEquals(listOf(3, 6, 9, 12), perAction.withIndex().filter { it.value.any { v -> v.code == "no_progress" } }.map { it.index + 1 })
        val v = perAction.flatten().filter { it.code == "no_progress" }
        assertEquals(listOf(Severity.Warn, Severity.Warn, Severity.Warn, Severity.Stop), v.map { it.severity })
        assertEquals(12, v.last().evidence["unchanged"]!!.jsonPrimitive.int)
    }

    @Test fun reportedNoEffectCountsOnceNotAgainForTheFrame() {
        val g = OperationGuards()
        g.frame("f0", 42L)
        val perAction = (1..3).map { g.noteAction(); g.effect("no_effect") + g.frame("f$it", 42L) }
        assertEquals(3, g.unchangedFrames)
        assertEquals("no_progress", perAction.last().single().code)
        // 小的局部变化也是进展，应清零
        g.noteAction(); g.effect("small_change"); assertEquals(0, g.unchangedFrames)
        g.noteAction(); g.effect("done"); assertEquals(0, g.unchangedFrames)
    }

    @Test fun backAndForthBetweenTwoStatesDoesNotStopProgress() {
        val g = OperationGuards()
        g.frame("s0", 1L)
        val v = listOf(2L, 1L, 2L).mapIndexed { i, h -> g.noteAction(); g.frame("s${i + 1}", h) }.flatten()
        assertTrue(v.isEmpty())
    }

    @Test fun threeStateLoopIsCaught() {
        val g = OperationGuards()
        g.frame("s0", 1L)
        val v = listOf(2L, 3L, 1L, 2L, 3L).mapIndexed { i, h -> g.noteAction(); g.frame("s${i + 1}", h) }.flatten()
        assertTrue(v.isEmpty())
    }

    @Test fun revisitingKnownScreensWithoutNewContentPromptsReassessment() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        g.noteAction(); g.effect("done"); g.frame("new", 2L)
        val warnings = (1..10).flatMap { i ->
            g.noteAction()
            g.effect("done") + g.frame("repeat-$i", if (i % 2 == 0) 2L else 1L)
        }
        assertTrue(warnings.toString(), warnings.any { it.code == "no_new_state" && it.severity == Severity.Warn })
        assertNull(g.stop)
    }

    @Test fun rereadingTheSameContentAfterNavigationDoesNotResetTaskProgress() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        g.read("phone_observe", g.firstSeen(100L), fingerprint = 100L)
        g.noteAction(); g.effect("done"); g.frame("other", 2L)
        val warnings = (1..10).flatMap { i ->
            g.noteAction(); g.effect("done")
            val frame = g.frame("back-$i", if (i % 2 == 0) 2L else 1L)
            val fp = if (i % 2 == 0) 200L else 100L
            frame + g.read("phone_observe", g.firstSeen(fp), fingerprint = fp)
        }
        assertTrue(warnings.toString(), warnings.any { it.code == "no_new_state" })
    }

    @Test fun freshContentDuringLongScrollKeepsResettingTaskProgress() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        val warnings = (1..40).flatMap { i ->
            g.noteAction(); g.effect("done")
            val frame = g.frame("scroll-$i", 1L)
            frame + g.read("phone_observe", g.firstSeen(1000L + i), fingerprint = 1000L + i)
        }
        assertTrue(warnings.toString(), warnings.none { it.code == "no_new_state" })
        assertNull(g.stop)
    }

    @Test fun cycleStopFollowsTheStepBudget() {
        val g = OperationGuards(stepBudget = 30)
        g.frame("s0", 1L)
        var stopAt = 0
        for (i in 1..40) {
            g.noteAction(); g.effect("done")
            g.frame("s$i", if (i % 2 == 1) 2L else 1L)
            if (g.stop != null) { stopAt = i; break }
        }
        assertNull(g.stop)
        assertEquals(0, stopAt)
    }

    @Test fun returningToTheSameScreenWithoutAnythingNewNudgesAtThree() {
        val g = OperationGuards()
        g.frame("s0", 1L)
        // 1 → 2 → 1 → 3 → 1:2、3 是新的一屏,回到 1 的次数从头算,不提醒
        val fresh = listOf(2L, 1L, 3L, 1L).mapIndexed { i, h -> g.noteAction(); g.frame("a$i", h) }.flatten()
        assertTrue(fresh.toString(), fresh.none { it.code == "same_state" })
        // 之后只在见过的几屏里转:第三次回到 1 时提醒
        val stuck = listOf(2L, 3L, 1L, 2L, 3L, 1L).mapIndexed { i, h -> g.noteAction(); g.frame("b$i", h) }
        // 只在见过的几个状态里转:回到同一状态或兜圈,总有一条提醒
        val same = stuck.flatten().filter { it.code == "same_state" || it.code == "state_cycle" }
        assertTrue(same.isEmpty())
    }

    @Test fun manyActionsWithoutANewScreenNudgeThenStopLate() {
        val g = OperationGuards()
        g.frame("s0", 1L)
        g.noteAction(); g.frame("s1", 2L)
        val v = (1..30).map { i -> g.noteAction(); g.effect("small_change") + g.frame("x$i", 2L) }
            .withIndex().filter { (_, l) -> l.any { it.code == "no_new_state" } }.map { it.index + 1 }
        assertEquals(listOf(8, 16, 30), v)
        assertNull(g.stop)
        // 步数上限高的任务,安全网跟着往后
        val big = OperationGuards(stepBudget = 100)
        big.frame("s0", 1L); big.noteAction(); big.frame("s1", 2L)
        repeat(49) { i -> big.noteAction(); big.effect("small_change"); big.frame("y$i", 2L) }
        assertNull(big.stop)
        big.noteAction(); big.frame("y-last", 2L)
        assertNull(big.stop)
    }

    @Test fun changedScreenResetsNoProgress() {
        val g = OperationGuards()
        g.frame("f0", 1L)
        repeat(2) { g.noteAction(); g.frame("a$it", 1L) }
        g.noteAction(); assertTrue(g.frame("b", 2L).isEmpty())
        assertEquals(0, g.unchangedFrames)
        // 同一 id 重复上报不计。
        g.noteAction(); g.frame("b", 2L)
        assertEquals(0, g.unchangedFrames)
    }

    @Test fun freshContentKeepsLongScrollAliveEvenWhenActionReportsNoEffect() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        repeat(80) { i ->
            assertTrue(g.beforeAction("web_scroll", obj("""{"direction":"down"}""")).isEmpty())
            g.noteAction()
            g.effect("no_effect")
            assertTrue(g.read("web_read_page", g.firstSeen(1000L + i)).isEmpty())
        }
        assertNull(g.stop)
        assertEquals(0, g.unchangedFrames)
    }

    @Test fun changedFrameCancelsProvisionalNoProgressStop() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        repeat(11) { i -> g.noteAction(); g.effect("no_effect"); g.frame("same-$i", 1L) }
        g.noteAction()
        assertEquals(Severity.Stop, g.effect("no_effect").single().severity)
        g.frame("changed", 2L)
        assertNull(g.stop)
        assertEquals(0, g.unchangedFrames)
    }

    @Test fun failureCircuit() {
        val fail = obj("""{"ok":false,"error":"action_failed","recoverable":true}""")
        val g = OperationGuards()
        assertTrue(g.result("phone_tap", fail).isEmpty())
        val counts = (2..8).map { g.result("phone_tap", fail) }
        assertEquals(List(6) { Severity.Warn } + Severity.Stop, counts.map { it.single().severity })
        assertEquals("too_many_failures", counts.last().single().code)
        assertEquals(8, counts.last().single().evidence["consecutive"]!!.jsonPrimitive.int)
        // 第 4 次起提醒里附出路
        assertFalse(counts[0].single().nudge!!.contains("ask the user"))
        assertTrue(counts[2].single().nudge!!.contains("ask the user"))

        // 成功或 counts_as_failure=false 清零。
        val g2 = OperationGuards()
        g2.result("phone_tap", fail)
        g2.result("phone_tap", obj("""{"ok":true}"""))
        assertTrue(g2.result("phone_tap", fail).isEmpty())
        g2.result("end_not_done", obj("""{"ok":false,"counts_as_failure":false}"""))
        assertTrue(g2.result("phone_tap", fail).isEmpty())
    }
    @Test fun unrecoverableFailureWarnsOnceStopsAtThree() {
        val denied = obj("""{"ok":false,"error":"denied","recoverable":false}""")
        val g = OperationGuards()
        val v = (1..3).map { g.result("phone_tap", denied).single() }
        assertEquals(listOf(Severity.Warn, Severity.Warn, Severity.Stop), v.map { it.severity })
        assertEquals(3, v.last().evidence["unrecoverable"]!!.jsonPrimitive.int)
        assertEquals("denied", (v.last().evidence["recent"] as kotlinx.serialization.json.JsonArray).last().jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test fun verdictJsonForModel() {
        val v = GuardVerdict("repeating_action", Severity.Warn, JsonObject(emptyMap()))
        assertEquals("""{"code":"repeating_action","severity":"warn","evidence":{}}""", v.toJson().toString())
    }

    // 页面没变、换着工具和参数反复读:读不出新东西,5、8 次提醒,12 次停;读到新内容、画面变了都从头算
    @Test fun rereadingAnUnchangedPageWarnsThenStops() {
        val g = OperationGuards()
        assertTrue(g.firstSeen(1L))
        assertTrue(g.read("phone_observe", fresh = true).isEmpty())
        val verdicts = (1..12).map { i -> g.read(if (i % 2 == 0) "web_evaluate_js" else "web_read_page", g.firstSeen(1L)) }
        assertEquals(listOf(5, 8, 12), verdicts.withIndex().filter { it.value.isNotEmpty() }.map { it.index + 1 })
        assertEquals(listOf(Severity.Warn, Severity.Warn, Severity.Stop), verdicts.flatten().map { it.severity })
        assertTrue(verdicts.flatten().all { it.code == "read_loop" })
        assertEquals("read_loop", g.stop?.code)
    }

    @Test fun newContentOrChangedPageResetsRereads() {
        val g = OperationGuards()
        g.firstSeen(1L)
        repeat(4) { assertTrue(g.read("phone_observe", g.firstSeen(1L)).isEmpty()) }
        // 读整页换了起点:新内容,不算重读
        repeat(10) { assertTrue(g.read("web_read_page", g.firstSeen(100L + it)).isEmpty()) }
        // 画面变了:之前读到的不再作数
        g.frame("f0", 5L); g.noteAction(); g.frame("f1", 6L)
        assertTrue(g.firstSeen(1L))
        repeat(4) { assertTrue(g.read("phone_observe", g.firstSeen(1L)).isEmpty()) }
        g.pageChanged()
        repeat(4) { assertTrue(g.read("phone_observe", g.firstSeen(1L)).isEmpty()) }
    }
}
