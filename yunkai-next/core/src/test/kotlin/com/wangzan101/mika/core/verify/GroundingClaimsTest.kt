package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 兜底只核回答里的具体事实:回答自己的叙述(计数、排名、步数)和引用编号不算要核对的值;编造的值照样拦下。 */
class GroundingClaimsTest {
    private fun reply(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private val criteria = Criteria(listOf("回答给出热榜第一的问题和热度"), "read", fallback = false)
    private val pass = """{"checks": [{"criterion": "回答给出热榜第一的问题和热度", "status": "met", "quote": "1561 万热度"}], "verdict": "pass", "reason": "屏上有"}"""
    private val hot = "当前 App:com.example.qa\n热榜\n为什么秋天的天空特别蓝\n1561 万热度\n城市里还能看到银河吗\n982 万热度\n" +
        "长期熬夜有哪些代价\n655 万热度\n猫为什么喜欢纸箱\n431 万热度\n第一份工作该怎么选\n307 万热度"
    private fun screen(fresh: String = hot, shot: ByteArray? = null) =
        Evidence(freshOf(fresh), shot, EvidenceLedger(), listOf("第1步 phone_launch_app(com.example.qa) 生效"), protected = false)

    // 实测:依据里「可见排名从1到5」的 1、5 被当成屏上的值,屏上只有「1561 万热度」,对的回答被判成核实不了
    @Test fun narrativeCountsAndRanksInBasisAreNotRequired() = runTest {
        val answer = "热榜前五:\n1. 为什么秋天的天空特别蓝(1561万热度)\n2. 城市里还能看到银河吗\n3. 长期熬夜有哪些代价\n4. 猫为什么喜欢纸箱\n5. 第一份工作该怎么选\n" +
            "依据: 热榜页可见排名从1到5的问题列表,共5条,第1条热度1561万"
        assertEquals(true, Grounding.found("热榜前五是什么", answer, listOf(hot)))
        assertEquals(Verdict.Pass, Verifier(FakeModel(reply(pass)), null).verify("热榜前五是什么", criteria, answer, screen()).verdict)
        // 结论里的小计数、步数同样不是值
        assertEquals(true, Grounding.found("热榜第一是什么", "第3步看到热榜,共 5 条,第一是「为什么秋天的天空特别蓝」\n依据: 热榜页", listOf(hot)))
    }

    // 实测:「@e46显」里 \b 不认汉字边界,46 被当成要核对的数
    @Test fun citationRefsGluedToChineseAreStripped() {
        val styles = listOf(
            "热榜第一是「为什么秋天的天空特别蓝」,1561万热度\n依据: @e46显示「为什么秋天的天空特别蓝」,@e47显示1561 万热度",
            "热榜第一是「为什么秋天的天空特别蓝」@e46,热度1561万@e47。\n依据: 热榜页(@e46)显示该标题",
            "热榜第一是「为什么秋天的天空特别蓝」[@e46、@e47]\n依据:@v2里写着「1561 万热度」",
        )
        for (a in styles) assertEquals(a, true, Grounding.found("热榜第一是什么", a, listOf(hot)))
        assertEquals(" 显示", Grounding.stripRefs("@e46显示"))
    }

    @Test fun madeUpNumberWithUnitIsCaught() = runTest {
        val answer = "热榜第一是「为什么秋天的天空特别蓝」,热度 1800 万\n依据: 热榜页第1条"
        val g = Grounding.check("热榜第一是什么", answer, listOf(hot))
        assertEquals(false, g.found)
        assertEquals(listOf("1800万"), g.unmatched.map { it.text })
        val v = Verifier(FakeModel(reply(pass)), null).verify("热榜第一是什么", criteria, answer, screen())
        assertEquals(Verdict.CannotVerify, v.verdict)
        assertEquals(Verifier.NOT_IN_READS, v.reason)
        assertEquals(listOf("1800万"), v.notFound)
        // 看过截图也不放过:数能写成文字,文字里没有就是没有
        val seen = Verifier(FakeModel(reply(pass)), FakeModel(reply(pass))).verify("热榜第一是什么", criteria, answer, screen(shot = byteArrayOf(1)))
        assertEquals(Verdict.CannotVerify, seen.verdict)
        // 价格、百分比同理
        assertEquals(false, Grounding.found("多少钱", "现价 ¥39\n依据: 商品页", listOf("商品页\n现价 ¥49")))
        assertEquals(false, Grounding.found("好评率", "好评率 97%\n依据: 商品页", listOf("商品页\n好评率 96%")))
    }

    @Test fun madeUpQuotedTitleIsCaught() = runTest {
        val answer = "热榜第一是「秋天为什么容易犯困」\n依据: 热榜页第1条「秋天为什么容易犯困」"
        assertEquals(false, Grounding.found("热榜第一是什么", answer, listOf(hot)))
        val v = Verifier(FakeModel(reply(pass)), null).verify("热榜第一是什么", criteria, answer, screen())
        assertEquals(Verdict.CannotVerify, v.verdict)
        assertEquals(listOf("秋天为什么容易犯困"), v.notFound)
        // 不加引号的「标签:值」不是字面要核的事实(值的文字对不对由核查模型判,提示词要求名称、标题有原文);写成引文就核
        val detail = listOf("标题:为什么秋天的天空特别蓝\n作者 小王")
        assertEquals(false, Grounding.found("文章标题", "标题:「秋天为什么容易犯困」\n依据: 详情页", detail))
        assertEquals(true, Grounding.found("文章标题", "标题:「为什么秋天的天空特别蓝」\n依据: 详情页", detail))
        assertTrue(Verifier.PROMPT, "名称、标题" in Verifier.PROMPT)
        // 回答自己的结构(「结论:」)不是屏上的字段
        assertEquals(true, Grounding.found("热榜第一", "结论:第一条最热,是「为什么秋天的天空特别蓝」\n依据: 热榜页", listOf(hot)))
    }

    @Test fun datesAndTimesAreClaims() {
        val s = listOf("订单详情\n下单时间 2026-10-23 14:05\n已发货")
        assertEquals(true, Grounding.found("什么时候下的单", "10月23日 14:05 下的单\n依据: 订单详情「已发货」", s))
        assertEquals(false, Grounding.found("什么时候下的单", "10月22日 14:05 下的单\n依据: 订单详情「已发货」", s))
        assertEquals(false, Grounding.found("什么时候下的单", "10月23日 15:05 下的单\n依据: 订单详情「已发货」", s))
    }

    // 单选框选中、开关开着这类状态不在文字里:看过截图的核查器判 pass 时算数,记为看图核实
    @Test fun visualStateIsGroundedByImageOnlyWhenAnImageWasSent() = runTest {
        val shown = "配送方式\n标准配送\n加急配送\n¥12"
        val answer = "现在选的是「标准配送」「已选中」,运费 ¥12\n依据: 配送方式一栏"
        val crit = Criteria(listOf("回答给出当前选中的配送方式"), "read", fallback = false)
        val ok = """{"checks": [{"criterion": "回答给出当前选中的配送方式", "status": "met", "quote": "标准配送"}], "verdict": "pass", "reason": "截图里标准配送是选中的"}"""
        val v = Verifier(FakeModel(reply(ok)), FakeModel(reply(ok))).verify("现在选的是哪种配送", crit, answer, screen(shown, shot = byteArrayOf(1)))
        assertEquals(Verdict.Pass, v.verdict)
        assertEquals(Verifier.IMAGE, v.groundedBy)
        assertEquals(listOf("已选中"), v.notFound)
        // 没有截图:同一条回答核实不了
        val noImage = Verifier(FakeModel(reply(ok)), null).verify("现在选的是哪种配送", crit, answer, screen(shown))
        assertEquals(Verdict.CannotVerify, noImage.verdict)
        // 文字全对上时照常是文字核实
        val plain = Verifier(FakeModel(reply(ok)), FakeModel(reply(ok))).verify("现在选的是哪种配送", crit, "选的是「标准配送」\n依据: 配送方式一栏", screen(shown, byteArrayOf(1)))
        assertEquals(null, plain.groundedBy)
    }

    @Test fun timeoutGrowsWithEvidenceAndIsCapped() {
        assertEquals(Verifier.TIMEOUT_MS, Verifier.timeoutMs(500, 0))
        assertTrue(Verifier.timeoutMs(40 * 1024, 1) > Verifier.timeoutMs(500, 1))
        assertTrue(Verifier.timeoutMs(500, 1) > Verifier.timeoutMs(500, 0))
        assertEquals(Verifier.MAX_TIMEOUT_MS, Verifier.timeoutMs(10_000_000, 3))
    }

    @Test fun timeoutRetriesOnceWithTrimmedEvidence() = runTest {
        val bodies = mutableListOf<String>()
        val slowOnce = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                bodies += (messages.single().parts.first() as Part.Text).text
                if (bodies.size == 1) delay(Verifier.MAX_TIMEOUT_MS + 1_000)
                return reply(pass)
            }
        }
        // 每条都含有回答里的值:重问时条目一条不丢,节选更短,动作只留最近的
        val old = (1..5).map { "旧读屏$it\n" + "字".repeat(1_500) + "\n" + "字".repeat(1_500) + "\n1561 万热度\n" + "字".repeat(1_500) }
        val e = Evidence(freshOf(hot), null, ledgerOf(old), (1..30).map { "第${it}步 tap 生效" }, protected = false)
        val v = Verifier(slowOnce, null).verify("热榜第一是什么", criteria, "热榜第一是「为什么秋天的天空特别蓝」,1561万热度\n依据: 热榜页", e)
        assertEquals(Verdict.Pass, v.verdict)
        assertEquals(2, bodies.size)
        assertTrue("第1步" in bodies[0] && bodies[0].length < 12_000)
        assertFalse(bodies[1], "第1步 tap" in bodies[1])
        assertTrue("第30步" in bodies[1] && bodies[1].length < bodies[0].length)
        assertEquals(6, Regex("1561 万热度").findAll(bodies[1]).count())
    }
}
