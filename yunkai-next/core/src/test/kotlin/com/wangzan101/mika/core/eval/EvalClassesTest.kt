package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.testing.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评测核对的几类毛病(真机记录里的原话和读到的网页文字):条目按行数、复述题目的词、登录墙后接管、渠道说法重复、
 * 手机自带的 App 不钉包名。
 */
class EvalClassesTest {
    private fun obs(answer: String, screens: List<String>, goal: String = "", outcome: String = "succeeded", barrier: String? = null) =
        Observed(outcome, answer, screens, null, emptyMap(), emptyMap(), 1, 1000, 0, 0, 0, null, goal = goal, takeoverBarrier = barrier)

    private val doubanPage = listOf(
        "肖申克的救赎 / The Shawshank Redemption / 月黑高飞(港) / 刺激1995(台) [可播放]\n9.7 3352986人评价\n" +
            "霸王别姬 / 再见，我的妾 / Farewell My Concubine [可播放]\n9.6 2461519人评价\n" +
            "泰坦尼克号 / Titanic / 铁达尼号(港 / 台) [可播放]\n9.5 2539069人评价\n" +
            "阿甘正传 / Forrest Gump / 福雷斯特·冈普 [可播放]\n9.5 2465285人评价\n" +
            "千与千寻 / 千と千尋の神隠し / 神隐少女(台) / 千与千寻的神隐\n9.4 2577706人评价",
    )
    private val doubanGoal = "用网页打开豆瓣电影 Top250(https://movie.douban.com/top250),告诉我排名前五的电影名和评分。只看,不要登录、不要标记想看"

    // 实测 web-douban-top-five:两部片子都是 9.5 分,按不同的值只数出 4 条
    @Test fun itemsAreCountedPerAnswerLine() {
        val answer = "豆瓣电影 Top250 排名前五的电影名和评分：\n\n1. 肖申克的救赎 —— 9.7 分\n2. 霸王别姬 —— 9.6 分\n3. 泰坦尼克号 —— 9.5 分\n" +
            "4. 阿甘正传 —— 9.5 分\n5. 千与千寻 —— 9.4 分\n\n依据:豆瓣电影 Top250 网页正文,排名 1-5 依次显示「肖申克的救赎 9.7」"
        assertTrue(Checks.grounded(answer, doubanPage, 5, doubanGoal))
        // 同一行重复写一遍不多算
        val repeated = "1. 泰坦尼克号 —— 9.5 分\n1. 泰坦尼克号 —— 9.5 分"
        assertFalse(Checks.grounded(repeated, doubanPage, 2, doubanGoal))
        // 每行的值照样要在屏上:编出来的评分不算
        assertFalse(Checks.grounded(answer.replace("9.4 分", "9.1 分"), doubanPage, 5, doubanGoal))
    }

    // 屏上的数要是完整的一个词:哈希、id 里夹着的数字不给回答作证
    @Test fun screenNumbersMustBeWholeTokens() {
        assertFalse(Checks.grounded("- 评分:899", listOf("评分", "img d50735b899e51bc8a.jpg"), 1))
        assertTrue(Checks.grounded("- 评分:899", listOf("评分 899"), 1))
        assertTrue(Checks.grounded("- 存储:128GB", listOf("存储 128GB"), 1))
        // 实测 settings-kernel-version:夹在字母里的编号整个按一个词核对
        val kernel = listOf("内核版本", "5.15.194-android13-8-00019-gf4321180a397-ab15212794")
        assertTrue(Checks.grounded("内核版本号为 5.15.194-android13-8-00019-gf4321180a397-ab15212794", kernel, 1))
        assertFalse(Checks.grounded("内核版本号为 5.15.194-android14-8-00019-gf4321180a397-ab15212794", kernel, 1))
    }

    private val forecast = listOf(
        "25日（明天） 小雨 12℃/9℃ <3级",
        "25日（明天） 阴 23℃/17℃ 3-4级转<3级",
        "25日（明天） 多云转晴 30℃/20℃ <3级",
    )
    private val citiesGoal = "用网页查中国天气网(weather.com.cn)上北京、上海、广州三个城市明天的最高气温,分别列出来"

    // 实测 web-weather-three-cities:「北京:最高 12℃」里的「最高」是复述题目,屏上没有这个词;明天换算成的日期按任务那天核对
    @Test fun labelWordsThatOnlyComeFromTheGoalAreIgnored() {
        val answer = "中国天气网(weather.com.cn)明天(2026年10月25日)三城市最高气温如下:\n\n• 北京:最高 12℃(明天 小雨,12℃/9℃)\n" +
            "• 上海:最高 23℃(明天 阴,23℃/17℃)\n• 广州:最高 30℃(明天 多云转晴,30℃/20℃)\n\n依据:中国天气网 7 天预报页面"
        val day = java.time.LocalDate.of(2026, 10, 24)
        assertTrue(Checks.grounded(answer, forecast, 3, citiesGoal, day))
        // 编出来的温度照样拦下;值和城市对没对上由核查模型判
        assertFalse(Checks.grounded(answer.replace("北京:最高 12℃", "北京:最高 15℃"), forecast, 3, citiesGoal, day))
    }

    private fun readTask(accept: List<String>? = null, category: EvalCategory = EvalCategory.Read, requires: List<String> = emptyList()) =
        EvalTask(id = "t", goal = "g", apps = emptyList(), category = category, requires = requires, expect = Expect.Succeeded,
            checks = listOf(Check.AnswerGrounded(2)), acceptTakeoverOn = accept)

    // 实测 meituan-hotpot-ratings、xhs-search-titles:搜索被登录墙挡住,交给用户登录。单列一类,不算办成
    @Test fun takeoverAfterLoginWallIsItsOwnBucket() {
        val handed = obs("", emptyList(), outcome = "takeover", barrier = "login")
        val s = Scoring.score(readTask(), handed)
        assertEquals("login", s.handedOver)
        assertFalse(s.passed)
        // 屏上没证据的(没有 takeoverBarrier)、别的障碍、题目说不认、写类题、声明了已登录的,都不归这一类
        assertNull(Scoring.score(readTask(), handed.copy(takeoverBarrier = null)).handedOver)
        assertNull(Scoring.score(readTask(), handed.copy(takeoverBarrier = "captcha")).handedOver)
        assertNull(Scoring.score(readTask(accept = emptyList()), handed).handedOver)
        assertNull(Scoring.score(readTask(category = EvalCategory.Write), handed).handedOver)
        assertNull(Scoring.score(readTask(requires = listOf("signedIn:com.example.shop")), handed).handedOver)
        assertNull(Scoring.score(readTask(requires = listOf("site:example.com")), handed).handedOver)
        assertEquals("captcha", Scoring.score(readTask(accept = listOf("captcha")), handed.copy(takeoverBarrier = "captcha")).handedOver)
    }

    private var t = 0L
    private fun screen(vararg names: String) = com.wangzan101.mika.core.testing.legacyScreenText(++t, 1, "com.example.shop", names.toList())
    private fun reply(tool: String, args: String = "{}") = RunEvent.ModelReply(++t, 1, "", tool, args, null, null, null)

    // 只认紧挨着接管、而且那一屏上看得出来的障碍
    @Test fun takeoverBarrierNeedsScreenEvidenceRightBeforeTakeover() {
        val wall = screen("欢迎登录美团", "请输入密码", "登录", "验证码登录")
        val ev = listOf(wall, reply("handle_obstacle"), RunEvent.Barrier(++t, "login"), reply("hand_to_user"), RunEvent.State(++t, "waiting_takeover", "请登录"))
        assertEquals("login", RunFacts.takeoverBarrier(ev, TOOLS))
        // 屏上只是普通内容:模型说是登录墙也不认
        assertNull(RunFacts.takeoverBarrier(listOf(screen("火锅", "评分 4.8")) + ev.drop(1), TOOLS))
        // 报告障碍之后又做了别的才接管:不是这次接管的原因
        assertNull(RunFacts.takeoverBarrier(ev.take(3) + reply("phone_tap") + ev.drop(3), TOOLS))
        // 根本没接管
        assertNull(RunFacts.takeoverBarrier(ev.take(3), TOOLS))
    }

    // 实测:题目里写了「用网页」又强制网页渠道,目标成了「用网页用网页……」
    @Test fun channelPrefixIsNotDuplicated() {
        val t = EvalTask(id = "t", goal = "用网页分别查一下长江和黄河的全长", apps = emptyList(), category = EvalCategory.Read,
            expect = Expect.Succeeded, checks = listOf(Check.AnswerContains(listOf("长江"))), channel = "web")
        assertEquals("用网页分别查一下长江和黄河的全长", EvalTasks.substitute(t, "r", emptyMap()).goal)
        assertEquals("用网页查长江", EvalTasks.substitute(t.copy(goal = "查长江"), "r", emptyMap()).goal)
        assertEquals("用 App查长江", EvalTasks.substitute(t.copy(goal = "查长江", channel = "app"), "r", emptyMap()).goal)
        assertEquals("用App查长江", EvalTasks.withChannel("用App查长江", "app"))
    }

    private val alarmTask = EvalTask(id = "alarm", goal = "在手机自带的时钟里新建闹钟「Mika评测-{run}」", apps = listOf("{app:alarm}"),
        category = EvalCategory.Write, requires = listOf("app:alarm"), expect = Expect.Succeeded,
        checks = listOf(Check.ScreenHas("{app:alarm}", "Mika评测-{run}")), marker = "Mika评测-{run}")

    @Test fun systemAppsResolveFromCapabilities() {
        assertEquals(emptyList<String>(), EvalTasks.validate(alarmTask))
        assertEquals(setOf("alarm"), EvalTasks.appCapabilities(alarmTask))
        val r = EvalTasks.resolveApps(alarmTask, mapOf("alarm" to "com.android.deskclock"))
        assertEquals(listOf("com.android.deskclock"), r.apps)
        assertEquals("com.android.deskclock", (r.checks.single() as Check.ScreenHas).packageName)
        // 没定下来的能力不留在 apps 里(离开范围只按定下来的算)
        assertEquals(emptyList<String>(), EvalTasks.resolveApps(alarmTask, emptyMap()).apps)
        // 不认识的能力、写错的前置条件过不了校验
        assertTrue(EvalTasks.validate(alarmTask.copy(apps = listOf("{app:toaster}"))).any { "toaster" in it })
        assertTrue(EvalTasks.validate(alarmTask.copy(requires = listOf("app:toaster"))).any { "toaster" in it })
        assertTrue(EvalTasks.validate(alarmTask.copy(requires = listOf("logged:com.x"))).any { "前置条件" in it })
        assertEquals(emptyList<String>(), EvalTasks.validate(alarmTask.copy(requires = listOf("signedIn:zhihu.com", "signedIn:com.x"))))
    }

    @Test fun writtenAppIsTheForegroundAppWhenTyping() {
        val ev = listOf(
            com.wangzan101.mika.core.testing.legacyScreenText(++t, 1, "com.example.launcher", listOf("桌面")), reply("phone_launch_app", "{\"package\":\"com.example.notes\"}"),
            com.wangzan101.mika.core.testing.legacyScreenText(++t, 2, "com.example.notes", listOf("新建")), reply("phone_type_text", "{\"text\":\"买牛奶\"}"),
            com.wangzan101.mika.core.testing.legacyScreenText(++t, 3, "网站 example.com", listOf("搜索")), reply("phone_type_text", "{\"text\":\"x\"}"),
        )
        assertEquals(listOf("com.example.notes"), RunFacts.appsWrittenIn(ev, TOOLS))
    }

    @Test fun capabilityCandidatesComeFromStandardIntents() {
        // resolve-activity --brief 的输出:默认处理的那一个;好几个都能处理时是系统的选择界面,不是候选
        assertEquals(listOf("com.android.deskclock"), AppCapabilities.packagesIn("priority=0 preferredOrder=0 match=0x108000\ncom.android.deskclock/.AlarmClock"))
        assertEquals(emptyList<String>(), AppCapabilities.packagesIn("priority=0\nandroid/com.android.internal.app.ResolverActivity"))
        val query = "2 activities found:\n  Activity #0:\n    priority=0\n    com.example.calendar/.Launcher\n  Activity #1:\n    org.other.cal/org.other.cal.Main\n"
        assertEquals(listOf("com.example.calendar", "org.other.cal"), AppCapabilities.packagesIn(query))
        assertTrue(AppCapabilities.commands(APP_CAPABILITIES.getValue("calendar").single()).all { "-c android.intent.category.APP_CALENDAR" in it })
        // Agent 打开过的候选优先;没有候选时取 Agent 打开的第一个 App(Mika 自己不算)
        assertEquals("org.other.cal", AppCapabilities.choose(listOf("com.example.calendar", "org.other.cal"), listOf("com.mika", "org.other.cal"), "com.mika"))
        assertEquals("com.example.calendar", AppCapabilities.choose(listOf("com.example.calendar"), emptyList()))
        // 没有候选时只认 Agent 打字时在前台的那一个 App;只打开过、或在几个 App 里都打过字,说不准就不定
        assertEquals("com.example.notes", AppCapabilities.choose(emptyList(), listOf("com.example.other"), "com.mika", written = listOf("com.mika", "com.example.notes")))
        assertNull(AppCapabilities.choose(emptyList(), listOf("com.mika", "com.example.notes"), "com.mika"))
        assertNull(AppCapabilities.choose(emptyList(), emptyList(), written = listOf("com.a.notes", "com.b.notes")))
        assertNull(AppCapabilities.choose(emptyList(), emptyList()))
    }
}
