package com.wangzan101.mika.core.eval

import com.wangzan101.mika.core.record.RunEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.TOOLS

class RunFactsTest {
    private fun screen(step: Int, vararg names: String, app: String? = "com.x") = com.wangzan101.mika.core.testing.legacyScreenText(step.toLong(), step, app, names.toList())
    private fun typed(step: Int, text: String) = RunEvent.ModelReply(step.toLong(), step, "", "phone_type_text", """{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}""", null, null, null)
    private fun launch(step: Int, pkg: String) = RunEvent.ModelReply(step.toLong(), step, "", "phone_launch_app", """{"package":"$pkg"}""", null, null, null)

    @Test fun ledgerKeepsEveryScreenWhole() {
        val long = (1..500).map { "第 $it 条标题" }.toTypedArray()
        val ev = listOf(screen(1, "旧"), screen(2, "甲"), screen(3, "乙"), screen(4, *long))
        // 不再只留最后 3 次:最早读的那屏也在账本里
        val l = RunFacts.ledger(ev, TOOLS).all
        assertEquals(listOf("旧", "甲", "乙", long.joinToString("\n").take(com.wangzan101.mika.core.verify.EvidenceLedger.READ_CHARS)), l.map { it.text })
        assertEquals(listOf(1, 2, 3, 4), l.map { it.step })
        assertEquals("com.x", l.first().where)
    }

    @Test fun typedEchoExcludedOnlyWhenWholeElement() {
        val ev = listOf(typed(1, "时光 回溯"), screen(2, "时光回溯", "搜索结果:时光回溯 无匹配", "设置"))
        // 输入框回显(整条等于打进去的字)去掉;别处包含这几个字的元素留着
        assertEquals(listOf("搜索结果:时光回溯 无匹配\n设置"), RunFacts.evidence(ev, TOOLS, "", "时光回溯"))
    }

    @Test fun namesWithQuotesAndNewlinesKept() {
        val ev = listOf(screen(1, "他说\"你好\"", "第一行\n第二行", "反斜杠\\"))
        assertEquals(listOf("他说\"你好\"\n第一行\n第二行\n反斜杠\\"), RunFacts.evidence(ev, TOOLS, "", "x"))
    }

    // 工具结果里写给干活模型的提示不是读到的材料;失败的结果也不算
    @Test fun ledgerTakesReadResultsWithoutHints() {
        fun result(step: Int, tool: String, json: String) = RunEvent.ToolResult(step.toLong(), step, tool, json, null, null)
        val ev = listOf(
            result(1, "web_read_page", """{"ok":true,"url":"https://a.example.com/","text":"长江全长 6300 公里","hint":"Mika 提示 9999 公里"}"""),
            result(2, "web_read_page", """{"ok":false,"error":"timeout","message":"超时 8888 公里"}"""),
        )
        val l = RunFacts.ledger(ev, TOOLS).all.filter { it.kind == com.wangzan101.mika.core.observation.ObsKind.Read }
        assertEquals(1, l.size)
        assertEquals("https://a.example.com/", l.single().where)
        assertTrue("6300" in l.single().text && "9999" !in l.single().text)
    }

    @Test fun appsSeenFromScreensAndLaunches() {
        val ev = listOf(launch(1, "com.android.settings"), screen(2, "a", app = "com.android.settings"), screen(3, "b", app = "com.example.health"), screen(4, "c", app = null))
        assertEquals(listOf("com.android.settings", "com.example.health"), RunFacts.appsSeen(ev, TOOLS))
    }

    // 修复 1 M6:停在关卡 = 最后一次确认是拒绝,之后任务自己结束了
    @Test fun stoppedAtGateOnlyWhenDeclineIsLastAndRunEnded() {
        fun c(d: String?) = RunEvent.Confirmation(1, "delete", "x", "y", null, d)
        val end = RunEvent.TaskEnded(9, "failed", "没同意")
        assertEquals(true, RunFacts.stoppedAtGate(listOf(c(null), c("declined"), end)))
        assertEquals(true, RunFacts.stoppedAtGate(listOf(c("declined_before"), end)))
        // 拒绝后又申报并被同意:不算
        assertEquals(false, RunFacts.stoppedAtGate(listOf(c("declined"), c(null), c("approved"), end)))
        // 拒绝后没结束(超时被评测器取消)不算
        assertEquals(false, RunFacts.stoppedAtGate(listOf(c("declined"))))
        assertEquals(false, RunFacts.stoppedAtGate(listOf(c("declined"), RunEvent.TaskEnded(9, "cancelled", "已取消"))))
        assertEquals(false, RunFacts.stoppedAtGate(listOf(c("required"), end)))
    }
}

