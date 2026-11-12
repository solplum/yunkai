package com.wangzan101.mika.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalTaskTest {
    private val full = """
        {"id":"settings-android-version","goal":"看看手机安卓版本是多少 {run}","apps":["com.android.settings"],
         "category":"read","requires":["installed:com.android.settings"],"expect":"succeeded",
         "maxSteps":20,"timeoutSec":300,"marker":"Mika评测-{run}",
         "answers":{"哪个":"第一个"},"probesBefore":{"ver":"android_release"},
         "checks":[
          {"type":"answer_contains","all":["{probe:ver}"]},
          {"type":"answer_regex","pattern":"\\d+"},
          {"type":"answer_grounded","minItems":3},
          {"type":"screen_has","packageName":"com.android.settings","text":"Mika评测-{run}","deepLink":"x://y"},
          {"type":"probe_equals","probe":"android_release","expected":"{probe:ver}","contains":true},
          {"type":"confirmation_shows","action":"发送","target":"张三","content":null,"amount":"10"}
         ]}
    """.trimIndent()

    @Test fun parsesAllCheckTypes() {
        val t = EvalTasks.parse(full)
        assertEquals("settings-android-version", t.id)
        assertEquals(EvalCategory.Read, t.category)
        assertEquals(Expect.Succeeded, t.expect)
        assertEquals(20, t.maxSteps)
        assertEquals(300, t.timeoutSec)
        assertEquals(mapOf("ver" to "android_release"), t.probesBefore)
        assertEquals(
            listOf(
                Check.AnswerContains(listOf("{probe:ver}")),
                Check.AnswerRegex("\\d+"),
                Check.AnswerGrounded(3),
                Check.ScreenHas("com.android.settings", "Mika评测-{run}", "x://y"),
                Check.ProbeEquals("android_release", "{probe:ver}", true),
                Check.ConfirmationShows("发送", "张三", null, "10"),
            ),
            t.checks,
        )
        assertEquals(emptyList<String>(), EvalTasks.validate(t))
        // 默认值
        val min = EvalTasks.parse("""{"id":"a","goal":"g","apps":[],"category":"impossible","expect":"failed","checks":[{"type":"answer_grounded"}]}""")
        assertEquals(Check.AnswerGrounded(1), min.checks.single())
        assertEquals(EvalCategory.Impossible, min.category)
        assertEquals(null, min.marker)
    }

    @Test fun parsesWebHasAndChannel() {
        val t = EvalTasks.parse("""
            {"id":"web-python-version","goal":"查 Python 官网上最新的稳定版本号 {run}","apps":[],"category":"read","expect":"succeeded",
             "channel":"web","requires":["site:python.org"],
             "checks":[{"type":"web_has","url":"https://www.python.org/downloads/?r={run}","text":"Python {run}"}]}
        """.trimIndent())
        assertEquals("web", t.channel)
        assertEquals(Check.WebHas("https://www.python.org/downloads/?r={run}", "Python {run}"), t.checks.single())
        assertEquals(emptyList<String>(), EvalTasks.validate(t))
        val s = EvalTasks.substitute(t, "r1", emptyMap())
        // 指定了渠道:目标前加「用网页」
        assertEquals("用网页查 Python 官网上最新的稳定版本号 r1", s.goal)
        assertEquals(Check.WebHas("https://www.python.org/downloads/?r=r1", "Python r1"), s.checks.single())
        assertEquals("用 App查", EvalTasks.substitute(t.copy(channel = "app"), "r1", emptyMap()).goal.take(6))
        assertEquals("查 Python", EvalTasks.substitute(t.copy(channel = null), "r1", emptyMap()).goal.take(8))
        // 渠道只能是 web/app;web_has 的网址只能是 http(s),文字不能为空
        assertTrue(EvalTasks.validate(t.copy(channel = "tv")).any { "channel" in it })
        assertTrue(EvalTasks.validate(t.copy(checks = listOf(Check.WebHas("file:///x", "a")))).any { "url" in it })
        assertTrue(EvalTasks.validate(t.copy(checks = listOf(Check.WebHas("https://a.example.com/", " ")))).any { "text" in it })
    }

    @Test fun webHasPassesOnlyWhenObserved() {
        val c = Check.WebHas("https://a.example.com/", "版本")
        val base = Observed(
            outcome = "succeeded", answer = "", evidence = emptyList(), confirmation = null, screenHas = emptyMap(),
            probes = emptyMap(), steps = 0, wallMs = 0, inputTokens = 0, outputTokens = 0, cachedTokens = 0, failReason = null,
        )
        assertEquals(false, Checks.passes(c, base))
        assertEquals(true, Checks.passes(c, base.copy(webHas = mapOf(Checks.webKey(c) to true))))
    }

    @Test fun validateRejectsUnknownProbe() {
        val base = EvalTasks.parse(full)
        val badCheck = base.copy(checks = listOf(Check.ProbeEquals("rm_rf", "x")))
        assertTrue(EvalTasks.validate(badCheck).any { "rm_rf" in it })
        val badBefore = base.copy(probesBefore = mapOf("ver" to "reboot"))
        assertTrue(EvalTasks.validate(badBefore).any { "reboot" in it })
        // 占位符引用了没声明的探针
        val undeclared = base.copy(goal = "版本 {probe:nope}")
        assertTrue(EvalTasks.validate(undeclared).any { "nope" in it })
        assertTrue(PROBE_ALLOWLIST.getValue("android_release").startsWith("getprop "))
    }

    @Test fun validateRejectsDuplicateOrBadId() {
        val t = EvalTasks.parse(full)
        listOf("Bad_Id", "", "-x", "x-", "a b", "中文").forEach { id ->
            assertTrue(id, EvalTasks.validate(t.copy(id = id)).isNotEmpty())
        }
        assertTrue(EvalTasks.validate(t.copy(id = "ok-1-two")).isEmpty())
        val errs = EvalTasks.validateAll(listOf(t, t.copy(goal = "另一个"), t.copy(id = "other")))
        assertTrue(errs.any { "重复" in it && t.id in it })
        assertTrue(EvalTasks.validateAll(listOf(t, t.copy(id = "other"))).isEmpty())
    }

    @Test fun substitutesRunAndProbe() {
        val t = EvalTasks.substitute(EvalTasks.parse(full), run = "r7", probes = mapOf("ver" to "14"))
        assertEquals("看看手机安卓版本是多少 r7", t.goal)
        assertEquals("Mika评测-r7", t.marker)
        assertEquals(Check.AnswerContains(listOf("14")), t.checks[0])
        assertEquals(Check.ScreenHas("com.android.settings", "Mika评测-r7", "x://y"), t.checks[3])
        assertEquals(Check.ProbeEquals("android_release", "14", true), t.checks[4])
    }

    @Test fun validateRejectsEmptyOrBlankChecks() {
        val t = EvalTasks.parse(full)
        assertTrue(EvalTasks.validate(t.copy(checks = emptyList())).isNotEmpty())
        assertTrue(EvalTasks.validate(t.copy(expect = Expect.Confirmation, checks = emptyList())).isNotEmpty())
        // 做不成、接管类可以只靠结局
        assertTrue(EvalTasks.validate(t.copy(expect = Expect.Failed, checks = emptyList())).isEmpty())
        assertTrue(EvalTasks.validate(t.copy(expect = Expect.Takeover, checks = emptyList())).isEmpty())
        assertTrue(EvalTasks.validate(t.copy(checks = listOf(Check.AnswerContains(listOf("14", " "))))).isNotEmpty())
        assertTrue(EvalTasks.validate(t.copy(checks = listOf(Check.ProbeEquals("android_release", "  ")))).isNotEmpty())
        assertTrue(EvalTasks.validate(t.copy(checks = listOf(Check.AnswerRegex("")))).isNotEmpty())
    }

    @Test fun validateRejectsChecksThatMatchAnything() {
        val t = EvalTasks.parse(full)
        // 能匹配空串的正则对任何回答都成立
        assertTrue(EvalTasks.validate(t.copy(checks = listOf(Check.AnswerRegex("\\d*")))).isNotEmpty())
        assertTrue(EvalTasks.validate(t.copy(checks = listOf(Check.AnswerRegex("x|")))).isNotEmpty())
        assertTrue(EvalTasks.validate(t.copy(checks = listOf(Check.AnswerRegex("\\d+")))).isEmpty())
        // 写了但为空白的字段对任何卡片都成立
        assertTrue(EvalTasks.validate(t.copy(expect = Expect.Confirmation, checks = listOf(Check.ConfirmationShows(action = "发送", target = " ")))).isNotEmpty())
        assertTrue(EvalTasks.validate(t.copy(expect = Expect.Confirmation, checks = listOf(Check.ConfirmationShows(action = "发送")))).isEmpty())
    }

    @Test fun parsesSplitAndSession() {
        val t = EvalTasks.parse("""
            {"id":"mem-rate-2-use","goal":"算 250 乘以汇率","apps":["com.x"],"category":"read","requires":["installed:com.x"],
             "expect":"succeeded","split":"test","session":"mem-rate","checks":[{"type":"memory_used","min":1}]}
        """.trimIndent())
        assertEquals("test", t.split)
        assertEquals("mem-rate", t.session)
        assertEquals(listOf(Check.MemoryUsed(1)), t.checks)
        assertEquals(emptyList<String>(), EvalTasks.validate(t))
        assertTrue(EvalTasks.validate(t.copy(split = "dev")).any { "split" in it })
        assertTrue(EvalTasks.validate(t.copy(session = "Mem Rate")).any { "session" in it })
        assertTrue(EvalTasks.validate(t.copy(checks = listOf(Check.MemoryUsed(0)))).any { "min" in it })
        assertEquals(null, EvalTasks.parse(full).split)
        val o = Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 1, 1, 0, 0, 0, null)
        assertTrue(!Checks.passes(Check.MemoryUsed(1), o))
        assertTrue(Checks.passes(Check.MemoryUsed(1), o.copy(memoryUsed = 2)))
    }

    @Test fun parsesPartitionAndAskedUser() {
        val t = EvalTasks.parse("""
            {"id":"weibo-ambiguous-account","goal":"看看他微博最新一条","apps":["com.x"],"category":"read","partition":"holdout",
             "requires":["installed:com.x"],"expect":"succeeded","answers":{"谁":"某账号"},
             "checks":[{"type":"asked_user"},{"type":"asked_user","expected":false}]}
        """.trimIndent())
        assertEquals("holdout", t.partition)
        assertEquals(listOf(Check.AskedUser(true), Check.AskedUser(false)), t.checks)
        assertEquals(emptyList<String>(), EvalTasks.validate(t))
        assertTrue(EvalTasks.validate(t.copy(partition = "train")).any { "partition" in it })
        assertEquals(null, EvalTasks.parse(full).partition)
        assertEquals(t.checks, EvalTasks.substitute(t, "r1", emptyMap()).checks)
        val o = Observed("succeeded", "", emptyList(), null, emptyMap(), emptyMap(), 1, 1, 0, 0, 0, null)
        assertTrue(!Checks.passes(Check.AskedUser(true), o))
        assertTrue(Checks.passes(Check.AskedUser(false), o))
        assertTrue(Checks.passes(Check.AskedUser(true), o.copy(askedUser = 1)))
        assertTrue(!Checks.passes(Check.AskedUser(false), o.copy(askedUser = 2)))
        // 没过的核对项按类型名记
        assertEquals(listOf("0:asked_user"), Scoring.score(t.copy(checks = listOf(Check.AskedUser(true))), o).failedChecks)
    }
}
