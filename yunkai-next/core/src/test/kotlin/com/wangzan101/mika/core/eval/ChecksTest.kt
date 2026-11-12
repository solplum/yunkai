package com.wangzan101.mika.core.eval

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksTest {
    private fun obs(
        answer: String = "",
        screens: List<String> = emptyList(),
        confirmation: Map<String, String>? = null,
        screenHas: Map<String, Boolean> = emptyMap(),
        probes: Map<String, String> = emptyMap(),
    ) = Observed("succeeded", answer, screens, confirmation, screenHas, probes, 1, 1000, 0, 0, 0, null)

    private val screens = listOf(
        "首页 推荐 秋天第一杯奶茶 周末去哪玩",
        "推荐 上海 咖啡 地图 十月的银杏大道",
    )

    @Test fun answerContainsAll() {
        val c = Check.AnswerContains(listOf("14", "安卓"))
        assertTrue(Checks.passes(c, obs("你的安卓版本是 14")))
        assertFalse(Checks.passes(c, obs("你的版本是 14")))
    }

    @Test fun answerGroundedAcceptsItemsSeenOnScreen() {
        val answer = """
            首页前三条标题如下:
            1. 「秋天第一杯奶茶」
            2、"周末去哪玩"
            3) 十月的银杏 大道
        """.trimIndent()
        assertTrue(Checks.passes(Check.AnswerGrounded(3), obs(answer, screens)))
        // 条目不够
        assertFalse(Checks.passes(Check.AnswerGrounded(4), obs(answer, screens)))
    }

    // 不带值的列表条目整段在屏上才算一条;编出来的不算,条目数不够就不过
    @Test fun answerGroundedRejectsInventedItem() {
        val answer = "1. 秋天第一杯奶茶\n2. 冬天第一顿火锅"
        assertFalse(Checks.passes(Check.AnswerGrounded(2), obs(answer, screens)))
        // 写成引文的编造标题直接不过
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("1. 「秋天第一杯奶茶」\n2. 「冬天第一顿火锅」", screens)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("", screens)))
    }

    @Test fun answerGroundedIgnoresEvidenceLine() {
        // 和核查兜底同一条规则:「依据:」以后都是依据,不算回答里的条目
        val answer = "1. 秋天第一杯奶茶\n2. 周末去哪玩\n依据:我在首页看到了这些标题,共两条"
        assertTrue(Checks.passes(Check.AnswerGrounded(2), obs(answer, screens)))
        assertFalse(Checks.passes(Check.AnswerGrounded(2), obs("1. 秋天第一杯奶茶\n依据:首页\n2. 周末去哪玩", screens)))
        val fullWidth = "- 周末去哪玩\n依据：首页截图"
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs(fullWidth, screens)))
    }

    // 计算器、天气卡片、应用详情页的读屏文字(节选自真机运行记录)
    private val calc = listOf("更多选项\n56,088\nMC\nM+\n清除\n乘\n7\n8\n9")
    private val weather = listOf("今天天气 - 百度\n宝安区 商业街\n23°\n23~31°C\n晴\n24 优\n体感23° 北风3级")
    private val market = listOf("哔哩哔哩\n3.6 9150 人评分\n3.6\n9150 人评分\n46 亿 次安装\n216 MB")

    @Test fun proseAnswerIsGroundedByItsValues() {
        val a = "计算器屏幕上显示的结果是 56,088（即 56088）。\n\n依据: 计算器界面顶部显示区文本为 \"56,088\"。"
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs(a, calc)))
        val w = "百度结果页顶部天气卡片显示如下：\n\n- 城市：宝安区（商业街）\n- 今天天气状况：晴\n- 气温范围：23~31°C（当前 23°，体感 23°，北风3级，空气质量 24 优）"
        assertTrue(Checks.passes(Check.AnswerGrounded(3), obs(w, weather)))
        val m = "在应用市场搜索「哔哩哔哩」后看到：\n\n- 评分：3.6（9150 人评分）\n- 下载次数：46 亿次安装\n\n未点击安装或更新按钮。"
        assertTrue(Checks.passes(Check.AnswerGrounded(2), obs(m, market)))
    }

    @Test fun wordsFromTheGoalNeedNotBeOnScreen() {
        val a = "用手机自带「计算器」计算 123×456,屏幕显示的结果是 56,088。"
        val o = obs(a, calc).copy(goal = "用手机自带的计算器算 123×456,告诉我结果")
        assertTrue(Checks.passes(Check.AnswerGrounded(1), o))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs(a, calc)))
        // 题目里的词不能替编造的值打掩护
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("用「计算器」算出来是 57,088。", calc).copy(goal = o.goal)))
    }

    @Test fun fabricatedValueStillFails() {
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("计算器屏幕上显示的结果是 57,088。", calc)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("- 评分：4.2（9150 人评分）", market)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("- 城市：北京", weather)))
        // 带值的那一行里不加引号的字(「多云」)不是字面核对的事实,值属于什么由核查模型判;写成引文就核
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 天气：晴 23°", weather)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("- 天气：「多云」 23°", weather)))
        // 只有连接性的话、没有可核对的值:不够条目数
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("我已经看完了,结果很好。", calc)))
    }

    @Test fun commentaryLabelsAreProseNotLabelledValues() {
        // 「补充：」「说明：」这类是附带的话,不是要从屏上读到的值
        val a = "计算器屏幕上显示的结果是 56,088。\n补充：我没有改动计算器里的任何设置。\n- 说明：只看了一眼结果"
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs(a, calc)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 天气：晴 23°\n备注：页面里还有广告,没有点开", weather)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 评分：3.6\n另外：没有点安装\n具体依据：应用详情页", market)))
        // 附带的话里写了数值,数值照样要在屏上
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("- 天气：晴 23°\n补充：明天 35°", weather)))
    }

    @Test fun zeroWidthCharactersOnScreenAreIgnored() {
        // 有的 App 在商品标题的字之间插零宽字符,读屏文字里的 316 实际是 3、1、6 中间各隔一个零宽字符
        val zw = listOf("\u200B", "\u200C", "\u200D", "\uFEFF", "\u2060")
        for (z in zw) {
            val shop = listOf("保${z}温${z}杯 3${z}1${z}6${z}L${z}不${z}锈${z}钢 79.00 0人付款")
            assertTrue(z, Checks.passes(Check.AnswerGrounded(2), obs("1. 保温杯 316L 不锈钢 — 79.00 元", shop)))
            assertFalse(z, Checks.passes(Check.AnswerGrounded(1), obs("1. 保温杯 304L 不锈钢 — 79.00 元", shop)))
        }
        // 回答里夹着零宽字符也一样
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 评分：3\u200B.6", market)))
    }

    @Test fun itemsAreCountedByGroundedValuesNotLines() {
        assertTrue(Checks.passes(Check.AnswerGrounded(2), obs("下载量 46亿次,评分 3.6 分(9150 人评分)", market)))
    }

    @Test fun partialNumbersDoNotMatchInsideOtherNumbers() {
        // 屏上的元素不能拼成一整串再找子串;数值按整个数比
        for (fake in listOf("6088", "88")) assertFalse(fake, Checks.passes(Check.AnswerGrounded(1), obs("结果是 $fake。", calc)))
        for (fake in listOf("4.6", "1.5")) assertFalse(fake, Checks.passes(Check.AnswerGrounded(1), obs("- 评分：$fake", market)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("- 评分：3.6", market)))
    }

    // 不带值的列表条目(「天气：多云」)整段在屏上某一行才算一条;只是某个词的一部分(「晴天娃娃」里的晴)不算
    @Test fun bareListItemsCountOnlyWhenOnScreen() {
        val far = listOf("晴天娃娃 推荐\n广告\n广告\n广告\n23°\n23~31°C\n多云")
        assertFalse(Checks.passes(Check.AnswerGrounded(2), obs("- 天气：晴\n- 气温：23~31°C", far)))
        assertTrue(Checks.passes(Check.AnswerGrounded(2), obs("- 天气：多云\n- 气温：23~31°C", far)))
    }

    @Test fun goalNumbersAreKnownOnlyAsWholeNumbers() {
        val goal = "用计算器算 123×456"
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("结果是 56,088,中间值 3456。", calc).copy(goal = goal)))
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs("结果是 56,088,中间值 1234。", calc).copy(goal = goal)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("123×456 的结果是 56,088。", calc).copy(goal = goal)))
    }

    @Test fun confirmationShowsMatchesFields() {
        val card = mapOf("action" to "发送消息", "target" to "张三(同事)", "content" to "晚上好", "amount" to "")
        val o = obs(confirmation = card)
        assertTrue(Checks.passes(Check.ConfirmationShows("发送", "张三", null, null), o))
        assertFalse(Checks.passes(Check.ConfirmationShows("发送", "李四", null, null), o))
        assertFalse(Checks.passes(Check.ConfirmationShows(null, null, null, "10"), o))
        assertFalse(Checks.passes(Check.ConfirmationShows("发送", null, null, null), obs()))
    }

    @Test fun answerGroundedCountsDuplicatesOnce() {
        val answer = "1. 秋天第一杯奶茶\n2. 秋天第一杯奶茶\n3. 「秋天第一杯 奶茶」"
        assertFalse(Checks.passes(Check.AnswerGrounded(3), obs(answer, screens)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs(answer, screens)))
    }

    @Test fun answerGroundedDropsTrivialItems() {
        // 单字、标点这类条目哪个屏上都有,不能拿来凑数
        val answer = "1. 首\n2. 推\n3. 。"
        assertFalse(Checks.passes(Check.AnswerGrounded(1), obs(answer, screens)))
        assertFalse(Checks.passes(Check.AnswerGrounded(2), obs("1. 秋天第一杯奶茶\n2. 页", screens)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("1. 秋天第一杯奶茶\n2. 页", screens)))
    }

    @Test fun answerGroundedStripsMarkdown() {
        val answer = "1. **秋天第一杯奶茶**\n2. `周末去哪玩`\n3. __十月的银杏大道__\n4. **依据**:首页读屏"
        assertTrue(Checks.passes(Check.AnswerGrounded(3), obs(answer, screens)))
        assertTrue(Checks.passes(Check.AnswerGrounded(1), obs("1. 秋天第一杯奶茶\n2. 依据:首页上看到的", screens)))
    }

    @Test fun probeEqualsTrimsAndContains() {
        val o = obs(probes = mapOf("android_release" to " 14\n", "default_home" to "com.example.launcher/.Home"))
        assertTrue(Checks.passes(Check.ProbeEquals("android_release", "14"), o))
        assertFalse(Checks.passes(Check.ProbeEquals("android_release", "1"), o))
        assertTrue(Checks.passes(Check.ProbeEquals("default_home", "com.example.launcher", contains = true), o))
        assertFalse(Checks.passes(Check.ProbeEquals("default_home", "com.other", contains = true), o))
        assertFalse(Checks.passes(Check.ProbeEquals("wifi_on", "1"), o))
    }

    @Test fun screenHasLooksUpByKey() {
        val c = Check.ScreenHas("com.example.notes", "Mika评测-r1")
        assertTrue(Checks.passes(c, obs(screenHas = mapOf(Checks.screenKey(c) to true))))
        assertFalse(Checks.passes(c, obs(screenHas = mapOf(Checks.screenKey(c) to false))))
        assertFalse(Checks.passes(c, obs(screenHas = mapOf(Checks.screenKey(c.copy(text = "别的")) to true))))
    }

    @Test fun answerRegexMatches() {
        val c = Check.AnswerRegex("版本\\s*1[0-9]")
        assertTrue(Checks.passes(c, obs("安卓版本 14")))
        assertFalse(Checks.passes(c, obs("安卓版本九")))
    }
}
