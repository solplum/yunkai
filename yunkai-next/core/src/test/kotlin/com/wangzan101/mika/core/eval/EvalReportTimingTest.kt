package com.wangzan101.mika.core.eval

import org.junit.Assert.assertTrue
import org.junit.Test

class EvalReportTimingTest {
    private fun pair(id: String, calls: Int, input: Long, cached: Long, modelMs: Long, toolMs: Long) =
        TaskScore(id, true, false, false, emptyList(), null) to
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), calls, 10_000, input, 10, cached, null,
                modelCalls = calls, modelMs = modelMs, toolMs = toolMs)

    @Test fun reportsCallMedianAndCacheRatio() {
        val scores = listOf(
            pair("a", 4, 400, 100, 5_000, 2_000),
            pair("b", 6, 300, 50, 5_000, 2_000),
            pair("c", 10, 300, 100, 5_000, 1_000),
        )
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null, flags = "inlineScreen,replay")
        assertTrue(md, "| 模型调用次数中位数 | 6 |" in md)
        assertTrue(md, "| 模型调用次数 P90 | 10 |" in md)
        assertTrue(md, "| 缓存命中率(cached/input) | 25.0% |" in md)
        // 总耗时 30 秒:模型 15 秒、工具 5 秒
        assertTrue(md, "| 模型等待占比 | 50.0% |" in md)
        assertTrue(md, "| 工具执行占比 | 16.7% |" in md)
        assertTrue(md, "| 回放命中 | 0 |" in md)
        assertTrue(md, "| 回放交还 | 0 |" in md)
        assertTrue(md, "inlineScreen,replay" in md)
    }

    // 占比加起来是 100%:模型、工具之外的是「其他/等用户」;没量到等界面时写「(暂无数据)」
    @Test fun sharesSumToHundredWithOtherRow() {
        val scores = listOf(pair("a", 4, 400, 100, 5_000, 2_000), pair("b", 6, 300, 50, 5_000, 2_000), pair("c", 10, 300, 100, 5_000, 1_000))
        val md = EvalReport.markdown("r1", "m", scores, emptyList(), null)
        assertTrue(md, "| 其他/等用户占比 | 33.3% |" in md)
        assertTrue(md, "| 其中等界面占比 | (暂无数据) |" in md)
    }

    // 回放评测:变体的回放命中、交还与交还后办成;原题单列,不进指标
    @Test fun replayRatesAndOriginalsSection() {
        fun v(id: String, passed: Boolean, hits: Int, backs: Int) = TaskScore(id, passed, false, false, emptyList(), null) to
            Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 2, 10_000, 0, 0, 0, null, replayHits = hits, replayHandBacks = backs)
        val scores = listOf(v("a~v1", true, 1, 0), v("a~v2", true, 0, 1), v("b~v1", false, 0, 1), v("b~v2", true, 0, 0))
        val md = EvalReport.markdown("r", "m", scores, emptyList(), null, originals = listOf(pair("a", 4, 1, 0, 1, 1)))
        assertTrue(md, "| 回放命中率(走完路线的题) | 1/4(25.0%) |" in md)
        assertTrue(md, "| 回放交还率(中途交还模型的题) | 2/4(50.0%) |" in md)
        assertTrue(md, "| 交还后办成率 | 1/2(50.0%) |" in md)
        assertTrue(md, "## 原题(记下路线用,不计入上面的指标)" in md)
        assertTrue(md, "实跑 4 题" in md)
    }
}
