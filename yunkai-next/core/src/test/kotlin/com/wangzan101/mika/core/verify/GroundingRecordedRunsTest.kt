package com.wangzan101.mika.core.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 兜底误拒对的回答的三类(真机记录里的原话和读到的网页文字),以及各自的反例:证据只证它证得了的,错的回答一律拦下。
 * - 运行时钟:题目或证据里写了「明天」「周二」,按时钟换算出的那一天;
 * - 简单运算:回答写明的算式(a 运算符 b = c),a、b 都在证据里对上;明说约数(约、≈)的取整;
 * - 截断:回答自己用 … 省略引文,屏上那一行是完整的,不算截断。
 */
class GroundingRecordedRunsTest {
    /** 记录里核查的那天:2026-10-24,周六。 */
    private val today = LocalDate.of(2026, 10, 24)

    private val beijingPage = listOf(
        "18:00更新 | 数据来源 中央气象台",
        "周末两天（10月24日至25日），我国华东沿海多地仍有降雨，其中今天雨势较强；同时，西北地区东部和华北等地降水增多。",
        "25日（明天） 小雨 12℃/9℃ <3级",
        "26日（后天） 阴转晴 16℃/6℃ <3级",
    ).joinToString("\n")
    private val beijingGoal = "用网页查一下明天北京的天气,告诉我最高和最低气温"
    private val beijingAnswer = "明天(10月25日,周日)北京天气已查到。\n\n- 天气:小雨\n- 最高气温:12℃\n- 最低气温:9℃\n\n" +
        "依据:中国天气网北京7天预报页面,「25日(明天)」一栏显示「小雨 12℃/9℃ <3级」,更新时间 18:00,数据来源中央气象台。"

    // 实测 web-weather-beijing:页面只写「25日(明天)」,回答换算成「10月25日」被当成找不到的日期
    @Test fun dateResolvedFromRunClockIsKnown() {
        val without = Grounding.check(beijingGoal, beijingAnswer, listOf(beijingPage))
        assertEquals(false, without.found)
        assertEquals(listOf("10月25日"), without.unmatched.map { it.text })
        assertEquals(true, Grounding.check(beijingGoal, beijingAnswer, listOf(beijingPage), today).found)
        // web-weather-three-cities:写成带年份的「2026年10月25日」也一样
        val cities = "中国天气网(weather.com.cn)明天(2026年10月25日)三城市最高气温如下:\n\n• 北京:最高 12℃(明天 小雨,12℃/9℃)\n\n" +
            "依据:中国天气网 7 天预报页面,北京显示\"25日(明天) 小雨 12℃/9℃\"。"
        assertEquals(true, Grounding.check("用网页查北京明天的最高气温", cities, listOf(beijingPage), today).found)
        // 页面上写着「周二」:周二是 10月27日
        val week = "27日（周二） 晴 16℃/2℃ <3级"
        assertEquals(true, Grounding.check("北京周二天气", "周二(10月27日)北京晴,最高气温:16℃\n依据:「晴 16℃/2℃」", listOf(week), today).found)
    }

    @Test fun clockOnlyVouchesForRelativeDaysTheEvidenceMentions() {
        fun unmatched(answer: String, page: String = beijingPage, goal: String = beijingGoal) =
            Grounding.check(goal, answer, listOf(page), today).unmatched.map { it.text }
        // 「周二」只出现在回答里:题目和页面都没说周二,时钟不替它作证
        assertEquals(listOf("10月27日"), unmatched("周二(10月27日)北京晴,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        // 题目和页面说的是明天(25 日)、后天(26 日):27 日不对
        assertEquals(listOf("10月27日"), unmatched(beijingAnswer.replace("10月25日", "10月27日"), beijingPage + "\n29日 小雨 10月29日"))
        // 题目、页面里都没有相对说法:一周之内的日期也不由时钟作证
        val plainPage = "25日 小雨 12℃/9℃ <3级"
        assertEquals(listOf("10月25日"), unmatched("明天(10月25日)北京小雨,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」", plainPage, "北京天气"))
        // 回答说是从页面读到的日期(在引文里、在依据里),时钟不作证
        assertTrue("10月25日" in unmatched("明天北京「10月25日 小雨」,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        assertEquals(listOf("10月25日"), unmatched("明天(10月25日)北京小雨,最高气温:12℃\n依据:页面写着 10月25日 小雨 12℃/9℃"))
        // 时刻从不由时钟作证
        assertEquals(listOf("10:27"), unmatched("今天 10:27 更新,明天小雨,最高气温:12℃\n依据:「小雨 12℃/9℃ <3级」"))
        // 远处的日期
        assertEquals(listOf("11月5日"), unmatched(beijingAnswer.replace("10月25日", "11月5日")))
        // 时钟只给日期作证,不给数作证:编出来的气温照样拦下
        assertEquals(listOf("31"), unmatched(beijingAnswer.replace("最高气温:12℃", "最高气温:31℃")))
    }

    private val riverPages = listOf(
        "长江，中国境内太平洋水系河流，最后注入东海。全长6363千米 [16]（一说有6236千米，一说6380千米，一说6397千米，一说 6403千米，一说6300余千米 [66]）。是中国、亚洲第一大河。",
        "黄河，是位于中国北方地区的大河，最后在东营市垦利区流入渤海。黄河全长约5464千米 [51]，流域面积约79.47万平方千米。",
        "web_navigate:网址 https://baike.example.com/cj,网页标题「长江（中国境内太平洋水系河流）_百科」",
        "web_navigate:网址 https://baike.example.com/hh,网页标题「黄河（中国北方独流入渤海水系河流）_百科」",
    )
    private val riverGoal = "用网页分别查一下长江和黄河的全长,告诉我哪条更长、大约长多少公里"
    private fun riverAnswer(diff: String) = "长江和黄河的全长都已在百度百科词条正文中查到:\n\n" +
        "- 长江:全长 6363 千米(词条还注明另有 6236、6380、6397、6403、6300 余千米等说法)。\n- 黄河:全长约 5464 千米。\n\n" +
        "对比结果:长江更长,$diff。\n\n" +
        "依据:百度百科「长江(中国境内太平洋水系河流)」词条正文「全长6363千米」;「黄河(中国北方独流入渤海水系河流)」词条正文「全长约5464千米」。"

    // 实测 web-rivers-length-compare:差值 899 = 6363 − 5464,屏上自然没有。写明算式、两个操作数都在证据里才算
    @Test fun explicitComputationOfGroundedNumbersIsGrounded() {
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464=899 公里"), riverPages).found)
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363 千米 - 5464 千米 = 899 千米"), riverPages).found)
        // 明说约数(≈、= 约)才按末尾的 0 取整
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464≈900 公里"), riverPages).found)
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363−5464=约900 公里"), riverPages).found)
        // 百分比:÷ 的结果按写出的小数位取整
        assertEquals(true, Grounding.check(riverGoal, riverAnswer("6363÷5464=116.5%"), riverPages).found)
        // 价格之差
        val prices = listOf("手机 ¥1999", "耳机 ¥299")
        assertEquals(true, Grounding.check("比较价格", "手机 ¥1999,耳机 ¥299,¥1999 − ¥299 = ¥1700\n依据:「手机 ¥1999」", prices).found)
        // 明说约数的单个数,按写出的精度取整等于证据里的数
        assertEquals(emptyList<String>(), Grounding.check("多少钱", "售价约 2000元\n依据:「售价 1999元」", listOf("售价 1999元")).unmatched.map { it.text })
    }

    @Test fun inventedNumberIsStillRejected() {
        fun unmatched(goal: String, answer: String, screens: List<String>) = Grounding.check(goal, answer, screens).unmatched.map { it.text }
        // 没写算式:差值只是「不在屏上」
        assertEquals(listOf("899"), unmatched(riverGoal, riverAnswer("比黄河长约 899 公里"), riverPages))
        assertEquals(listOf("1234"), unmatched(riverGoal, riverAnswer("比黄河长 1234 公里"), riverPages))
        // 算式写错了
        assertEquals(listOf("999"), unmatched(riverGoal, riverAnswer("6363−5464=999 公里"), riverPages))
        // 约数也不能粗到对上一大片数:1000 离 899 超过一成
        assertEquals(listOf("1000"), unmatched(riverGoal, riverAnswer("6363−5464≈1000 公里"), riverPages))
        // 操作数不在证据里
        assertEquals(listOf("5063", "1300"), unmatched(riverGoal, riverAnswer("6363−5063=1300 公里"), riverPages))
        // 正好等于两个价格之差,但没写算式:「共」「相差」之类的字不算证据
        val prices = listOf("手机 ¥1999", "耳机 ¥299")
        assertEquals(listOf("1700"), unmatched("比较价格", "手机 ¥1999,耳机 ¥299,共 ¥1700\n依据:「手机 ¥1999」", prices))
        assertEquals(listOf("1700"), unmatched("比较价格", "手机 ¥1999,耳机 ¥299,平板 ¥1700\n依据:「手机 ¥1999」", prices))
        // 编出来的百分数:96% 不是 100%,也不是 40%
        assertTrue("100" in unmatched("电量多少", "电量:100%\n依据:「电量 96%」", listOf("电量 96%")))
        assertTrue("40" in unmatched("电量多少", "电量 96%,已用约 40%\n依据:「电量 96%」", listOf("电量 96%")))
        // 没说约数:1999 元不是 2000 元
        assertEquals(listOf("2000"), unmatched("多少钱", "售价 2000元\n依据:「售价 1999元」", listOf("售价 1999元")))
        // 题目里给的数算出来的结果仍要在屏上读到(计算器题:屏上只有算式,没有结果)
        assertEquals(listOf("3950.6"), unmatched("用计算器算 98765 除以 25", "98765÷25=3950.6\n依据:计算器屏上「98765÷25」", listOf("98765÷25")))
    }

    // 实测 web-rivers-length-compare:页面上一张图的网址哈希里夹着 899,被当成屏上的数
    @Test fun numbersOnScreenMustBeWholeTokens() {
        val hash = "https://bkimg.cdn.bcebos.com/smart/d50735fae6cd7b899e51bc8a177c55a7d933c895a038-bkimg-process,v_1"
        val answer = riverAnswer("比黄河长约 899 公里")
        assertEquals(listOf("899"), Grounding.check(riverGoal, answer, riverPages + hash).unmatched.map { it.text })
        assertEquals(emptyList<String>(), Grounding.check(riverGoal, answer, riverPages + "长江比黄河长约 899 公里").unmatched.map { it.text })
        // id 里的数、贴着字母的版本号都不算;单位贴着写(128GB)、小数和千分位照样算
        assertEquals(false, Grounding.check("编号", "编号是 899 号码\n依据:「订单」", listOf("订单 a3f899c")).found)
        assertEquals(true, Grounding.check("存储多少", "存储:128GB\n依据:「存储 128GB」", listOf("存储 128GB")).found)
        assertEquals(true, Grounding.check("价格", "价格:1,299.50元\n依据:「价格」", listOf("价格 ¥1,299.50")).found)
        // 夹在字母里的编号整个按一个词比:G7541 对得上,编出来的 G9999 拦下
        assertEquals(emptyList<String>(), Grounding.check("车次", "车次:G7541\n依据:「预订G7541次车」", listOf("预订G7541次车05:52从上海虹桥出发")).unmatched.map { it.text })
        assertEquals(listOf("G9999"), Grounding.check("车次", "车次 G9999 05:52 出发\n依据:「预订G7541次车」", listOf("预订G7541次车05:52从上海虹桥出发")).unmatched.map { it.text })
        // 日期也一样:网址里的 1025 不是 10月25日
        assertEquals(listOf("10月25日"), Grounding.check("哪天", "日期是 10月25日\n依据:「图片」", listOf("图片 https://x.cn/p/img10x25b.jpg")).unmatched.map { it.text })
    }

    // 题目里的数按值认:题目写 1000.0,回答写 1000,不当成要在屏上找的值
    @Test fun goalNumbersAreKnownByValue() {
        assertEquals(true, Grounding.check("用计算器算 1000.0 除以 8", "1000 除以 8 等于 125.0\n依据:计算器屏上「125」", listOf("125")).found)
    }

    private val trainRows = listOf(
        "预订G7541次车05:52从上海虹桥出发，06:51到杭州东,当日到达历时00:59的车票",
        "预订G1321次车06:07从上海虹桥出发，06:56到杭州东,当日到达历时00:49的车票",
        "预订G7331次车06:13从上海虹桥出发，07:07到杭州东,当日到达历时00:54的车票",
    ).joinToString("\n")
    private val trainAnswer = "明天(2026-10-25)上海到杭州的高铁车次:\n\n| G7541 | 上海虹桥 05:52 | 杭州东 06:51 |\n| G1321 | 上海虹桥 06:07 | 杭州东 06:56 |\n\n" +
        "依据:12306 官网余票页查询结果列表,页面元素逐条显示\"预订G7541次车05:52从上海虹桥出发,06:51到杭州东…\"\"预订G1321次车06:07从上海虹桥出发,06:56到杭州东…\"等车次信息。"

    // 实测 web-12306-trains:回答自己用 … 省略了引文后半句,屏上那几行是完整的,被判成依据截断
    @Test fun agentEllipsisOnCompleteLineIsNotTruncation() {
        assertFalse(Grounding.builtOnTruncated(trainAnswer, listOf(trainRows)))
        val pass = Verification(Verdict.Pass, listOf(CriterionCheck("列出至少三个车次号", "met", "预订G7541次车05:52从上海虹桥出发")), "屏上有")
        assertEquals(Verdict.Pass, Verifier.backstop(pass, "在 12306 上查明天上海到杭州的高铁", trainAnswer, listOf(trainRows), imageSent = false, today).verdict)
    }

    @Test fun truncatedScreenLineIsStillTruncation() {
        // 屏上那一行本身被截断(以 … 结尾),回答的引文一直连到截断处:只看到了一部分
        val cut = "预订G7541次车05:52从上海虹桥出发，06:51到杭州东…\n预订G1321次车06:07从上海虹桥出发，06:56到杭州东…"
        assertTrue(Grounding.builtOnTruncated(trainAnswer, listOf(cut)))
        // 引文以 … 开头、屏上那一行前半截被截掉
        assertTrue(Grounding.builtOnTruncated("标题是「…到杭州东,当日到达」", listOf("…到杭州东,当日到达")))
        assertFalse(Grounding.builtOnTruncated("标题是「…到杭州东,当日到达」", listOf(trainRows)))
    }

    // ---------- 只核字面:对的回答不因写法被拦(真机记录 emu-web-smoke-2),错的照样拦 ----------

    private val doubanRows = listOf("1", "肖申克的救赎 / The Shawshank Redemption", "9.7 3353059人评价", "4", "阿甘正传 / Forrest Gump", "9.5 2465343人评价",
        "5", "千与千寻 / 千と千尋の神隠し", "9.4 2577773人评价").joinToString("\n")

    // 实测 web-douban-top-five:下一行的列表编号去掉后,「9.5」和下一行的「千与千寻」被拼成「9.5千」;依据里写的读取日期被当成要核的日期
    @Test fun answerLinesAreNotJoinedAndBasisProseIsNotAClaim() {
        val a = "前五如下:\n\n1. 肖申克的救赎 — 评分 9.7\n4. 阿甘正传 — 评分 9.5\n5. 千与千寻 — 评分 9.4\n\n" +
            "依据:网页正文「1 肖申克的救赎 … 9.7 3353059人评价」「4 阿甘正传 … 9.5 2465343人评价」「5 千与千寻 … 9.4 2577773人评价」(movie.douban.com/top250,2026-10-24 读取)。"
        val g = Grounding.check("豆瓣 Top250 前五的电影名和评分", a, listOf(doubanRows), today)
        assertEquals(g.unmatched.toString(), true, g.found)
        assertFalse(g.claims.toString(), g.claims.any { "千" in it.text && it.kind == Grounding.Kind.Number })
        // 依据里的引文照样核:编出来的人数、引错的片名拦下
        assertEquals(false, Grounding.check("豆瓣前五", a.replace("2577773人评价", "2577999人评价"), listOf(doubanRows), today).found)
        assertEquals(false, Grounding.check("豆瓣前五", a.replace("「5 千与千寻", "「5 天空之城"), listOf(doubanRows), today).found)
    }

    // 实测 web-rivers-length-compare:「黄河:全长约 5464 千米(词条正文「…」)」被当成「标签:值」,整段要求在屏上
    @Test fun unquotedLabelValuesAreNotLiteralClaims() {
        val a = "- 黄河:全长约 5464 千米(词条正文「全长约5464千米 [51],流域面积约79.47万平方千米」)\n依据:黄河词条「全长约5464千米 [51],流域面积约79.47万平方千米」"
        assertEquals(true, Grounding.check(riverGoal, a, riverPages).found)
        assertEquals(listOf("5465"), Grounding.check(riverGoal, a.replaceFirst("5464 千米", "5465 千米"), riverPages).unmatched.map { it.text })
    }

    // 实测 web-github-trending-top:「今日 +N」用 N 代替回答自己写过的数,不是屏上的字
    @Test fun quoteThatNamesTheAnswersOwnWordingIsNotAScreenClaim() {
        val page = listOf("## cloudflare / security-audit-skill", "JavaScript 43,250 2,699 Built by", "2,271 stars today")
        val a = "1. cloudflare / security-audit-skill — 43,250 颗星(今日 +2,271)\n说明:「今日 +N」是当天新增星标数。\n依据:「2,271 stars today」"
        assertEquals(true, Grounding.check("GitHub Trending 前三", a, page).found)
        // 回答里没有对应的数:引文照样要在屏上;编出来的引文也拦
        assertEquals(false, Grounding.check("GitHub Trending 前三", a.replace("(今日 +2,271)", ""), page).found)
        assertEquals(false, Grounding.check("GitHub Trending 前三", a.replace("「今日 +N」", "「Top N」"), page).found)
    }

    // 实测 settings-android-version:屏上分两行、引文里夹在字母里的数字(AQ3A)不当成单独的数
    @Test fun basisQuoteAcrossTwoLinesOfOneEntryLocates() {
        val about = listOf("当前 App:com.android.settings\nAndroid 版本\n15 AQ3A.240829.003")
        val a = "安卓版本是 Android 15(系统版本号 AQ3A.240829.003)。\n依据:关于手机页显示「Android 版本：15 AQ3A.240829.003」"
        assertEquals(true, Grounding.check("安卓版本", a, about).found)
        // 引文的几段要在同一处:分在两次读里的不算定位到
        val split = listOf("当前 App:com.android.settings\nAndroid 版本\n16", "15 AQ3A.240829.003")
        assertEquals(false, Grounding.check("安卓版本", a, split).found)
    }
}
