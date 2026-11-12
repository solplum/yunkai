package com.wangzan101.mika.core.verify

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.eval.Checks
import com.wangzan101.mika.core.eval.RunFacts
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.Redactor
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.RECORDED_TOOLS
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceLedgerTest {
    private val goal = "比较长江和黄河哪条更长,各多长"
    private val answer = "长江更长:长江 6300 公里,黄河 5464 公里\n依据: 「长江全长 6300 公里」「黄河全长 5464 公里」"
    private val criteria = Criteria(listOf("回答给出两条河的长度并比较"), "read", fallback = false)
    private val pass = """{"checks": [{"criterion": "回答给出两条河的长度并比较", "status": "met", "quote": "6300 公里"}], "verdict": "pass", "reason": "有"}"""
    private fun reply(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private fun filler(i: Int) = "当前网页:https://news.example.com/$i\n" + "无关的新闻正文。".repeat(700)

    /** 两个词条页读在前面,后面又读了很多页:旧的「最近 3 屏」窗口里已经没有它们。 */
    private fun ledger(): EvidenceLedger = EvidenceLedger().apply {
        add(2, ObsKind.Read, "https://wiki.example.com/长江", 1, "网页正文(https://wiki.example.com/长江)\n长江全长 6300 公里,是亚洲第一长河")
        add(4, ObsKind.Read, "https://wiki.example.com/黄河", 2, "网页正文(https://wiki.example.com/黄河)\n黄河全长 5464 公里")
        for (i in 1..20) add(4 + i, ObsKind.Read, "https://news.example.com/$i", 2L + i, filler(i))
    }

    @Test fun earlyPagesRelevantToTheAnswerAreChosen() {
        val l = ledger()
        val chosen = l.forAnswer(goal, answer)
        assertTrue(chosen.any { "6300" in it.text } && chosen.any { "5464" in it.text })
        // 选中的按读到的先后排;不沾边的只有最近读的一条
        assertEquals(chosen.sortedBy { it.step }, chosen)
        assertEquals(listOf(2, 4, 24), chosen.map { it.step })
        assertEquals(true, Grounding.check(goal, answer, chosen.map { it.text }).found)
        // 交给核查模型的是节选:总字数有上限,含有回答事实的行都在
        val shown = Verifier.excerpts(goal, answer, chosen, EvidenceLedger.EXCERPT_CHARS)
        assertTrue(shown.sumOf { it.text.length } <= EvidenceLedger.EXCERPT_CHARS)
        assertTrue(shown.any { "长江全长 6300 公里" in it.text } && shown.any { "黄河全长 5464 公里" in it.text })
    }

    @Test fun verifierPassesOnPagesReadLongAgo() = runTest {
        val m = FakeModel(reply(pass))
        val e = Evidence(freshOf(filler(20)), null, ledger(), listOf("第24步 web_observe() 生效"), protected = false)
        val v = Verifier(m, null).verify(goal, criteria, answer, e)
        assertEquals(Verdict.Pass, v.verdict)
        val body = (m.requests.single().single().parts.single() as Part.Text).text
        // 带来处:第几步、哪种来源、哪个网址
        assertTrue(body, "\"step\":2" in body && "\"kind\":\"read\"" in body && "wiki.example.com" in body)
    }

    // 账本只证得了读到过的:回答里没读到过的数,账本挑多少都证不了
    @Test fun ledgerCannotVouchForWhatWasNeverRead() = runTest {
        val wrong = "长江更长:长江 6397 公里,黄河 5464 公里\n依据: 「长江全长 6397 公里」「黄河全长 5464 公里」"
        val l = ledger()
        assertFalse(l.forAnswer(goal, wrong).any { "6397" in it.text })
        val v = Verifier(FakeModel(reply(pass)), null).verify(goal, criteria, wrong,
            Evidence(freshOf(filler(20)), null, l, emptyList(), protected = false))
        assertEquals(Verdict.CannotVerify, v.verdict)
        assertEquals(Verifier.NOT_IN_READS, v.reason)
        assertTrue(v.notFound.toString(), v.notFound.any { "6397" in it })
        assertFalse(Checks.grounded(wrong, l.forAnswer(goal, wrong).map { it.text }, 2, goal))
    }

    // 助手自己打进输入框的字(回显)不给回答作证
    @Test fun typedEchoCannotVouch() = runTest {
        val l = EvidenceLedger().apply {
            noteTyped("长江 6300 公里")
            add(3, ObsKind.Read, "com.example.search", 1, "当前 App:com.example.search\n长江 6300 公里\n没有找到相关结果")
        }
        assertFalse(l.forAnswer(goal, answer).any { "6300" in it.text })
        val v = Verifier(FakeModel(reply(pass)), null).verify(goal, criteria, answer, Evidence(freshOf(""), null, l, emptyList(), protected = false))
        assertEquals(Verdict.CannotVerify, v.verdict)
    }

    // 进账前脱敏:Key、一次性验证码不留原文,也就证不了
    @Test fun secretsAndCodesAreRedactedBeforeTheyEnter() {
        val key = "sk-" + "a".repeat(30)
        val l = EvidenceLedger().apply { add(1, ObsKind.Read, null, 1, "短信:您的验证码 482913,5 分钟内有效\n密钥 $key") }
        val t = l.all.single().text
        assertFalse(t, "482913" in t || key in t)
        assertTrue(t, "验证码 ${Redactor.MASK}" in t)
        assertFalse(Grounding.check("我的验证码是多少", "验证码是 482913", l.forAnswer("我的验证码是多少", "验证码是 482913").map { it.text }).found == true)
    }

    @Test fun boundedByTotalSizeOldestFirstAndDeduplicated() {
        val l = EvidenceLedger(capChars = 10_000)
        for (i in 1..5) l.add(i, ObsKind.Read, null, i.toLong(), "第${i}屏\n" + "字".repeat(3_000))
        assertEquals(listOf(3, 4, 5), l.all.map { it.step })
        // 同样的原文只留最近一次,来处跟着更新
        l.add(9, ObsKind.Read, null, 9, "第4屏\n" + "字".repeat(3_000))
        assertEquals(listOf(3, 5, 9), l.all.map { it.step })
        assertEquals(9L, l.all.last().atMs)
    }

    // 评测的核对和核查兜底从同一份记录、同一个挑法拿证据:两边对同一个回答的看法一致
    @Test fun evalAndVerifierReadTheSameLedger() {
        fun page(step: Int, url: String, text: String) =
            RunEvent.ToolResult(step.toLong(), step, "web_read_page", """{"ok":true,"url":"$url","text":"$text"}""", null, null)
        val events = listOf(RunEvent.TaskStarted(0, goal, "m")) +
            page(2, "https://wiki.example.com/cj", "长江全长 6300 公里") + page(4, "https://wiki.example.com/hh", "黄河全长 5464 公里") +
            (1..8).map { page(4 + it, "https://news.example.com/$it", "无关的新闻正文 第${it}篇") } +
            RunEvent.ModelReply(30, 13, "", "finish", """{"answer":${kotlinx.serialization.json.JsonPrimitive(answer)}}""", null, null, null) +
            RunEvent.Verifier(31, "pass", "{}", 13)
        val (v, f) = RecordedVerifications.pairs(events).single()
        for ((a, ok) in listOf(answer to true, answer.replace("6300", "6397") to false)) {
            val forVerifier = RecordedVerifications.ledgerSources(events, v, f, goal, a)
            val forEval = RunFacts.evidence(events, RECORDED_TOOLS, goal, a)
            assertEquals(a, ok, Grounding.check(goal, a, forVerifier).found == true)
            assertEquals(a, ok, Checks.grounded(a, forEval, 2, goal))
            // 旧窗口(最近 3 屏、3 次读网页)看不到词条页
            assertFalse(RecordedVerifications.liveSources(events, v, f).any { "6300" in it })
        }
    }

    // ---------- 值属于哪个东西:核查模型判,代码保证它看得到来处 ----------

    private val shopGoal = "华为手环 9 卖多少钱"
    private fun shop(): EvidenceLedger = EvidenceLedger().apply {
        add(2, ObsKind.Read, "https://shop.example.com/a", 1, "网页正文\n小米手环 9\n售价 ¥299", title = "小米手环 9")
        add(6, ObsKind.Read, "https://shop.example.com/b", 2, "网页正文\n华为手环 9\n售价 ¥399", title = "华为手环 9")
    }
    private val shopCriteria = Criteria(listOf("回答给出华为手环 9 的售价"), "read", fallback = false)
    private val shopPass = """{"checks": [{"criterion": "回答给出华为手环 9 的售价", "status": "met", "quote": "¥299"}], "verdict": "pass", "reason": "有"}"""
    private val shopUnmet = """{"checks": [{"criterion": "回答给出华为手环 9 的售价", "status": "unmet", "quote": "¥299"}], "verdict": "fail", "reason": "¥299 是小米手环 9 那一页的"}"""

    /** 交给核查模型的账本条目(JSON)。 */
    private fun shownLedger(m: FakeModel) = (m.requests.single().single().parts.first() as Part.Text).text
        .let { kotlinx.serialization.json.Json.parseToJsonElement(it.substring(it.indexOf('{'))) }
        .let { (it as kotlinx.serialization.json.JsonObject)["ledger"] as kotlinx.serialization.json.JsonArray }
        .map { e -> (e as kotlinx.serialization.json.JsonObject).mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content } }

    // 反例:A 页的 ¥299 说成 B 的价格。代码只核字面(¥299 确实读到过),属于谁由核查模型判:
    // 它拿到两页各自的来处(网址、标题、第几步)和价格那一行,提示词说清了这条规则
    @Test fun valueFromAnotherPageIsTheModelsCallWithProvenance() = runTest {
        val wrong = "华为手环 9 售价 ¥299。\n依据:「¥299」"
        assertEquals(true, Grounding.check(shopGoal, wrong, shop().forAnswer(shopGoal, wrong).map { it.text }).found)
        val m = FakeModel(reply(shopUnmet))
        val v = Verifier(m, null).verify(shopGoal, shopCriteria, wrong, Evidence(freshOf("网页标题:华为手环 9\n售价 ¥399"), null, shop(), emptyList(), protected = false))
        assertEquals(Verdict.Fail, v.verdict)
        val shown = shownLedger(m)
        val a = shown.single { it["where"] == "https://shop.example.com/a" }
        assertEquals("小米手环 9", a["title"]); assertEquals("2", a["step"]); assertTrue(a["text"]!!, "小米手环 9\n售价 ¥299" in a["text"]!!)
        // 后来读的那一页(回答说的那个东西)也在
        assertTrue(shown.toString(), shown.any { it["title"] == "华为手环 9" && "¥399" in it["text"]!! })
        assertTrue(Verifier.PROMPT, "值要属于回答说的那个东西" in Verifier.PROMPT && "where、title" in Verifier.PROMPT && "同一页上另一行" in Verifier.PROMPT)
        // 对的回答:代码和模型都放过
        val right = "华为手环 9 售价 ¥399。\n依据:「¥399」"
        val ok = Verifier(FakeModel(reply(shopPass.replace("299", "399"))), null).verify(shopGoal, shopCriteria, right,
            Evidence(freshOf("网页标题:华为手环 9\n售价 ¥399"), null, shop(), emptyList(), protected = false))
        assertEquals(Verdict.Pass, ok.verdict)
        assertTrue(Checks.grounded(right, shop().forAnswer(shopGoal, right).map { it.text }, 1, shopGoal))
    }

    // ---------- 必须看的条目 ----------

    // 后来读到的同一页可能和回答矛盾:那一页最近的一次一定交给核查器
    @Test fun latestReadOfTheAnswersPlaceIsAlwaysIncluded() {
        val l = EvidenceLedger().apply {
            add(2, ObsKind.Read, "https://shop.example.com/b", 1, "网页标题:华为手环 9\n售价 ¥299")
            for (i in 3..20) add(i, ObsKind.Read, "https://news.example.com/$i", 2, filler(i))
            add(21, ObsKind.Read, "https://shop.example.com/b", 3, "网页标题:华为手环 9\n售价 ¥399(已调价)")
            for (i in 22..24) add(i, ObsKind.Read, "https://news.example.com/$i", 4, filler(i))
        }
        val chosen = l.forAnswer(shopGoal, "华为手环 9 售价 ¥299\n依据:「¥299」")
        assertEquals(listOf(2, 21, 24), chosen.map { it.step })
    }

    // ---------- 输入框回显按控件认 ----------

    @Test fun echoIsJudgedByTheFieldNotTheText() {
        val l = EvidenceLedger().apply {
            noteTyped("长江长度")
            // 输入框有焦点:框里的字和以它开头的联想行都不算
            add(3, ObsKind.Read, "com.example.search", 1, "长江长度\n长江长度是 6300 公里\n长江长度和黄河比", inputs = listOf("长江长度"), focused = "长江长度")
            // 结果页:框里的字不算,和搜索词一样的结果标题照样算
            add(5, ObsKind.Read, "com.example.search", 2, "长江长度\n长江长度\n全长 6211 公里", inputs = listOf("长江长度"))
        }
        val texts = l.forAnswer("长江有多长", "长江长度 6211 公里").associate { it.step to it.text }
        assertFalse(texts.toString(), texts[3].orEmpty().contains("6300"))
        assertEquals("长江长度\n全长 6211 公里", texts[5])
        // 只有框里有的数,证不了
        assertFalse(Grounding.check("长江有多长", "长江长 6300 公里", l.forAnswer("长江有多长", "长江长 6300 公里").map { it.text }).found == true)
    }

    // ---------- 脱敏 ----------

    @Test fun codesNearCodeWordsAreMaskedInEitherDirection() {
        for (raw in listOf("验证码 4 8 2 9 1 3,5 分钟内有效", "482913 是您的登录验证码", "短信 code: 4829", "Your OTP is 7712-3390")) {
            val t = Redactor.evidence(raw)
            assertFalse(t, Regex("\\d(?:[ \\-]?\\d){3,}").containsMatchIn(t))
        }
        // 和这类词不沾边的数照常
        assertEquals("售价 ¥2026,库存 1288 件", Redactor.evidence("售价 ¥2026,库存 1288 件"))
    }

    // 接管时用户自己填进输入框的字不进账;助手打的照常
    @Test fun fieldsFilledByTheUserDuringTakeoverAreNotKept() {
        val l = EvidenceLedger().apply {
            noteTyped("北京")
            noteTakeover()
            add(9, ObsKind.Read, "com.example.app", 1, "手机号\n13800138000\n北京\n已登录", inputs = listOf("13800138000", "北京"))
        }
        assertEquals("手机号\n北京\n已登录", l.all.single().text)
    }

    // ---------- 助手自己写的东西不作证 ----------

    @Test fun agentAuthoredOutputCannotVouch() {
        fun reply(step: Int, tool: String, args: String) = RunEvent.ModelReply(step.toLong(), step, "", tool, args, null, null, null)
        fun result(step: Int, tool: String, json: String) = RunEvent.ToolResult(step.toLong(), step, tool, json, null, null)
        val events = listOf(
            reply(1, "run_command", """{"command":"echo 6300"}"""), result(1, "run_command", """{"ok":true,"exitCode":0,"output":"6300\n"}"""),
            reply(2, "write_file", """{"path":"out/a.txt","content":"黄河 5464 公里"}"""),
            result(2, "write_file", """{"ok":true,"path":"/workspace/out/a.txt","bytesWritten":20,"size":20}"""),
            reply(3, "read_file", """{"path":"out/a.txt"}"""), result(3, "read_file", """{"ok":true,"path":"/workspace/out/a.txt","content":"黄河 5464 公里"}"""),
        )
        val texts = RunFacts.ledger(events, RECORDED_TOOLS).all.joinToString("\n") { it.text }
        assertFalse(texts, "6300" in texts.replace("退出码 0", "") || "5464" in texts)
        assertTrue(texts, "/workspace/out/a.txt" in texts)
    }

    // ---------- 值属于哪个东西(续):交给核查模型的节选留着名字和值的那几行 ----------

    // 原生 App 里同一个包名先后两页:来处里有第几步,两页各自的名字和价格都在
    @Test fun nativeAppPagesAcrossStepsKeepTheirSteps() = runTest {
        val l = EvidenceLedger().apply {
            add(5, ObsKind.Read, "com.example.shop", 1, "当前 App:com.example.shop\n小米手环 9\n售价 ¥299")
            add(7, ObsKind.Read, "com.example.shop", 2, "当前 App:com.example.shop\n华为手环 9\n售价 ¥399")
        }
        val fresh = "当前 App:com.example.shop\n华为手环 9\n售价 ¥399"
        val wrong = "华为手环 9 售价 ¥299。\n依据:「¥299」"
        val m = FakeModel(reply(shopUnmet))
        assertEquals(Verdict.Fail, Verifier(m, null).verify(shopGoal, shopCriteria, wrong, Evidence(freshOf(fresh), null, l, emptyList(), protected = false)).verdict)
        val shown = shownLedger(m)
        assertTrue(shown.toString(), shown.any { it["step"] == "5" && "小米手环 9\n售价 ¥299" in it["text"]!! })
        assertTrue(shown.toString(), shown.any { it["step"] == "7" && "华为手环 9\n售价 ¥399" in it["text"]!! })
    }

    // 一屏上两行、按城市列的天气:长页面只给节选时,对上的值连同它前后的名字一起留下
    @Test fun excerptKeepsTheRowAroundEachValue() {
        val rows = (1..300).joinToString("\n") { "无关的一行 $it" }
        val page = "网页正文\n$rows\n小米手环 9\n¥299\n华为手环 9\n¥399\n$rows"
        val e = obs(3, ObsKind.Read, "https://shop.example.com/list", page)
        val shown = Verifier.excerpts("小米手环 9 和华为手环 9 各多少钱", "华为手环 9 ¥299\n依据:「¥299」", listOf(e), 2_000).single().text
        assertTrue(shown, shown.length < 2_000 && "小米手环 9\n¥299\n华为手环 9" in shown && "[省略" in shown)
        val cities = "明天\n北京 小雨 12℃/9℃\n上海 阴 23℃/17℃\n广州 多云 30℃/20℃\n" + rows
        val c = Verifier.excerpts("北京、上海、广州明天的最高气温", "北京:最高 23℃\n依据:城市列表", listOf(e.copy(text = cities)), 2_000).single().text
        assertTrue(c, "北京 小雨 12℃/9℃\n上海 阴 23℃/17℃" in c)
        // 每城一页时,页面自己的标题交给核查模型
        val l = EvidenceLedger().apply {
            add(2, ObsKind.Read, "https://weather.example.com/bj", 1, "25日(明天) 小雨 12℃/9℃", title = "北京天气预报")
            add(4, ObsKind.Read, "https://weather.example.com/sh", 2, "25日(明天) 阴 23℃/17℃", title = "上海天气预报")
        }
        val chosen = l.forAnswer("北京、上海明天的最高气温", "北京:最高 23℃\n上海:最高 12℃\n依据:各城市预报页")
        assertEquals(listOf("北京天气预报", "上海天气预报"), chosen.map { it.title })
    }

    // 一条也不丢:含有回答事实的条目很多、很长时,每条缩短,但每条都在,对上的行都在
    @Test fun excerptsNeverDropAnEntryWithAClaim() {
        val entries = (1..20).map { i -> obs(i, ObsKind.Read, "https://x.example.com/$i", "字".repeat(3_000) + "\n价格 ${1000 + i} 元\n" + "字".repeat(3_000)) }
        val answer = (1..20).joinToString(",") { "${1000 + it} 元" }
        val shown = Verifier.excerpts("", answer, entries, EvidenceLedger.EXCERPT_CHARS)
        assertEquals(20, shown.size)
        assertTrue(shown.sumOf { it.text.length } <= 20 * EvidenceLedger.MIN_EXCERPT + 20 * 40)
        for (i in 1..20) assertTrue(shown[i - 1].text, "价格 ${1000 + i} 元" in shown[i - 1].text)
    }

    // 不认识的单位各算一种:¥5 + $3 = 8 不算数
    @Test fun unknownUnitsAreTheirOwnDimension() {
        val a = "一共 8:¥5 + $3 = 8\n依据:「¥5」「$3」"
        assertFalse(Grounding.check("", a, listOf("门票 ¥5", "饮料 $3")).found == true)
        assertTrue(Grounding.check("", "一共 ¥8:¥5 + ¥3 = ¥8\n依据:「门票 ¥5」「饮料 ¥3」", listOf("门票 ¥5", "饮料 ¥3")).found == true)
    }

    // 码的遮挡不碰带单位、年份、货币的数
    @Test fun codeMaskingSparesQuantities() {
        assertEquals("短信:2026 年 10 月账单 ¥1288,共 3000 元", Redactor.evidence("短信:2026 年 10 月账单 ¥1288,共 3000 元"))
        assertFalse(Redactor.evidence("短信验证码 7712").contains("7712"))
    }
}
