package com.wangzan101.mika.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApprovalStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun unreadableFileFallsBackToAsking() {
        val f = tmp.root.resolve("a.json").apply { writeText("{not json") }
        assertFalse(ApprovalStore(f).autonomous.value)
    }

    @Test fun olderFilesKeepTheirAutonomousSwitch() {
        // 旧版本的文件里还有审批档位和长期授权:不认了,只读询问 / 自主
        val f = tmp.root.resolve("a.json").apply {
            writeText("""{"mode":"AUTO","autonomous":true,"grants":[{"action":"send","app":"com.chat","target":"张三","element":"发送"}]}""")
        }
        assertTrue(ApprovalStore(f).autonomous.value)
        ApprovalStore(f).setAutonomous(false)
        assertFalse("mode" in f.readText() || "grants" in f.readText())
    }

    private fun control() = ApprovalControl(ApprovalStore(tmp.root.resolve("a.json")))

    @Test fun autonomousIsOffByDefaultAndSurvivesARestart() {
        val f = tmp.root.resolve("a.json")
        assertFalse(ApprovalStore(f).autonomous.value)
        ApprovalControl(ApprovalStore(f)).setAutonomous(true)
        val again = ApprovalStore(f)
        assertTrue(again.autonomous.value)
        assertFalse(tmp.root.resolve("a.json.tmp").exists())
        ApprovalControl(again).setAutonomous(false)
        assertFalse(ApprovalStore(f).autonomous.value)
    }

    @Test fun runsInAskHaveAGateAndRunsInAutonomousDoNot() {
        val c = control()
        val ask = c.start()
        assertFalse(ask.startedAutonomous)
        assertFalse(ask.autonomous())
        assertNotNull(ask.gate())
        c.setAutonomous(true)
        val auto = c.start()
        assertTrue(auto.startedAutonomous)
        assertTrue(auto.autonomous())
        assertNull(auto.gate())
    }

    @Test fun switchingFollowsEveryRunningTask() {
        val c = control()
        val a = c.start()
        val b = c.start()
        c.setAutonomous(true)
        // 正在跑的任务从下一步起付款也不再问;它们开工时有关卡,这一点不变
        assertTrue(a.autonomous())
        assertTrue(b.autonomous())
        assertFalse(a.startedAutonomous)
        c.setAutonomous(false)
        assertFalse(a.autonomous())
        assertFalse(b.autonomous())
        // 结束后再换,不再碰那个任务;下一个任务按新的开关开工
        c.end(a)
        c.setAutonomous(true)
        assertFalse(a.autonomous())
        assertTrue(b.autonomous())
        assertTrue(c.start().startedAutonomous)
    }
}
