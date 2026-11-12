package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportTest {
    private val secret = "这是回答全文里独有的一句话"

    private fun pair(id: String, outcome: String, passed: Boolean, steps: Int, wallMs: Long, falseSuccess: Boolean = false, cls: FailureClass? = null, marker: String? = null) =
        TaskScore(id, passed, falseSuccess, outcome == "unverified", if (passed) emptyList() else listOf("0:answer_contains"), cls, marker) to
            Observed(outcome, secret, listOf("屏幕文字"), null, emptyMap(), emptyMap(), steps, wallMs, 1_000_000, 200_000, 400_000, null)

    private val scores = listOf(
        pair("a-ok", "succeeded", true, 4, 10_000),
        pair("b-false", "succeeded", false, 6, 20_000, falseSuccess = true, cls = FailureClass.false_success),
        pair("c-unv", "unverified", false, 8, 30_000, cls = FailureClass.unknown),
        pair("d-write", "succeeded", true, 10, 40_000, marker = "Mika评测-r1"),
    )

    @Test fun containsMetricsAndNoAnswers() {
        val md = EvalReport.markdown("r1", "deepseek-flash", scores, listOf("e-xhs" to "未安装 com.xingin.xhs"), null)
        assertFalse(secret in md)
        assertFalse("屏幕文字" in md)
        listOf("r1", "deepseek-flash", "办成率", "2/4", "假成功率", "1/3", "1/4", "未核实成功", "步数", "耗时", "token",
            "false_success", "a-ok", "b-false", "e-xhs", "未安装 com.xingin.xhs", "Mika评测-r1").forEach {
            assertTrue(it, it in md)
        }
        assertTrue(md, "| 输入 token | 4000000 |" in md)
    }

    @Test fun perTaskVerdictShown() {
        val (s, o) = pair("v-ok", "succeeded", true, 4, 10_000)
        val (s2, o2) = pair("v-unv", "unverified", false, 4, 10_000)
        val md = EvalReport.markdown("r1", "m", listOf(s to o.copy(verdict = "pass"), s2 to o2.copy(verdict = "cannot_verify")), emptyList(), null)
        assertTrue(md, "核查" in md)
        assertTrue(md, md.lines().any { it.startsWith("| v-ok |") && "pass" in it })
        assertTrue(md, md.lines().any { it.startsWith("| v-unv |") && "cannot_verify" in it })
    }

    @Test fun perTaskTokensAndCacheRatio() {
        val (s, o) = pair("t-a", "succeeded", true, 4, 10_000)
        val (s2, o2) = pair("t-b", "succeeded", true, 4, 10_000)
        val md = EvalReport.markdown("r1", "m", listOf(s to o, s2 to o2.copy(inputTokens = 1000, cachedTokens = 900, outputTokens = 7)), emptyList(), null)
        assertTrue(md, md.lines().any { it.startsWith("| t-a |") && it.endsWith("| 1000000 | 40.0% | 200000 |") })
        assertTrue(md, md.lines().any { it.startsWith("| t-b |") && it.endsWith("| 1000 | 90.0% | 7 |") })
        // 汇总:按 token 加权的命中率,以及每题命中率的中位数
        assertTrue(md, "| 缓存命中率(cached/input) | 40.0% |" in md)
        assertTrue(md, "| 每题缓存命中率中位数 | 65% |" in md)
    }

    @Test fun costOnlyWithPrices() {
        val none = EvalReport.markdown("r1", "m", scores, emptyList(), null)
        assertFalse("花费" in none)
        val priced = EvalReport.markdown("r1", "m", scores, emptyList(), Prices(inputPerM = 1.0, outputPerM = 2.0, cachedPerM = 0.5))
        // 每题: 未缓存输入 0.6M×1 + 缓存 0.4M×0.5 + 输出 0.2M×2 = 1.2;四题 4.8
        assertTrue("花费" in priced)
        assertTrue(priced, "4.8" in priced)
    }

    @Test fun medianAndP90() {
        assertEquals(5.0, EvalReport.median(listOf(9.0, 1.0, 5.0)), 0.0)
        assertEquals(2.5, EvalReport.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
        assertEquals(9.0, EvalReport.p90((1..10).map { it.toDouble() }), 0.0)
        assertEquals(7.0, EvalReport.p90(listOf(7.0)), 0.0)
        assertEquals(10.0, EvalReport.p90((1..11).map { it.toDouble() }.reversed()), 0.0)
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null)
        assertTrue(md, "| 步数中位数 | 7 |" in md && "| 步数 P90 | 10 |" in md && "| 耗时中位数(秒) | 25 |" in md)
    }

    @Test fun totalWallClockShownWhenGiven() {
        val s = Scoring.score(
            EvalTask(id = "t", goal = "g", apps = emptyList(), category = EvalCategory.Read, expect = Expect.Succeeded, checks = emptyList()),
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 1, 60_000, 0, 0, 0, null),
        )
        val o = Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 1, 60_000, 0, 0, 0, null)
        assertFalse("总墙钟时间" in EvalReport.markdown("r", "m", listOf(s to o), emptyList(), null))
        val md = EvalReport.markdown("r", "m", listOf(s to o), emptyList(), null, totalWallMs = 90_500, parallel = 2)
        assertTrue(md, "| 总墙钟时间(秒) | 90.5 |" in md)
        assertTrue(md, "并行 2" in md)
    }
}
