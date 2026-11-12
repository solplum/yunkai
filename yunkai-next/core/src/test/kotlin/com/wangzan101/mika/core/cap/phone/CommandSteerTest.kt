package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.SystemCommandResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSteerTest {
    @Test fun recognisesCommandsThatEmulateDedicatedTools() {
        assertEquals(CommandSteer.Purpose.Gesture, CommandSteer.purpose("input -d \"\$MIKA_DISPLAY_ID\" tap 500 800"))
        assertEquals(CommandSteer.Purpose.Gesture, CommandSteer.purpose("input touchscreen -d 7 swipe 1 2 3 4 300"))
        assertEquals(CommandSteer.Purpose.Type, CommandSteer.purpose("input -d 7 tap 500 900; input -d 7 text hello"))
        assertEquals(CommandSteer.Purpose.Key, CommandSteer.purpose("sleep 1 && input -d 7 keyevent KEYCODE_ENTER"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("am start --display 7 -n com.example/.Main"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("monkey -p com.example -c android.intent.category.LAUNCHER 1"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("cmd activity start-activity -n com.example/.Main"))
        // 分享文字:动作写全或简写都认;分享文件按一般的启动算
        assertEquals(CommandSteer.Purpose.Share, CommandSteer.purpose("am start --display 7 -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'hi' -p com.example.chat"))
        assertEquals(CommandSteer.Purpose.Share, CommandSteer.purpose("am start -a SEND -t text/plain --es android.intent.extra.TEXT hi"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("am start -a android.intent.action.SENDTO -d smsto:10086"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("am start -a android.intent.action.SEND -t image/png --eu android.intent.extra.STREAM content://x/1"))
        // 查询类命令照常
        assertNull(CommandSteer.purpose("pm list packages"))
        assertNull(CommandSteer.purpose("dumpsys input_method | grep mInputShown"))
        assertNull(CommandSteer.purpose("settings get system screen_off_timeout"))
    }

    private fun screen(vararg codes: Int) = object : ScreenPort by FakeScreen() {
        private val queue = ArrayDeque(codes.toList())
        override suspend fun systemCommand(command: String) = SystemCommandResult(queue.removeFirstOrNull() ?: 0, "out")
    }

    private suspend fun ToolDispatcher.run(command: String): ToolExecution =
        PhoneCommand.execute(Args(PhoneCommand.def, buildJsonObject { put("command", command) }), ctx)

    private fun ToolExecution.hint() = content["hint"]?.jsonPrimitive?.content

    private class Counting : ScreenPort by FakeScreen() {
        val ran = mutableListOf<String>()
        override suspend fun systemCommand(command: String): SystemCommandResult { ran += command; return SystemCommandResult(0, "out") }
    }

    private fun ToolExecution.ok() = content["ok"]!!.jsonPrimitive.content == "true"
    private fun ToolExecution.error() = content["error"]?.jsonPrimitive?.content

    @Test fun emulatingCommandsAreSteeredButStillRun() = runTest {
        val d = toolDispatcher(screen(0))
        val r = d.run("input -d 7 text hi")
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        assertEquals(Feedback["system_command.use_type"], r.hint())
        assertNull(d.run("pm list packages").hint())
    }

    @Test fun afterTheFirstHintEmulatingCommandsAreRefused() = runTest {
        val screen = Counting()
        val d = toolDispatcher(screen)
        assertTrue(d.run("input -d 7 tap 500 900").ok())
        // 提醒过了:换一类模仿的命令也不执行,只说该用哪个工具
        for ((command, key) in listOf(
            "input -d 7 text hi" to "system_command.use_type",
            "input -d 7 keyevent 66" to "system_command.use_key",
            "am start --display 7 -n com.example/.Main" to "system_command.use_launch",
        )) {
            val r = d.run(command)
            assertFalse(command, r.ok())
            assertEquals("use_phone_tool", r.error())
            assertEquals(Feedback.format("system_command.refused", "use" to Feedback[key]), r.hint())
        }
        // 查状态、读设置的命令照常
        assertTrue(d.run("settings get system screen_off_timeout").ok())
        assertTrue(d.run("dumpsys window | grep mCurrentFocus").ok())
        assertEquals(listOf("input -d 7 tap 500 900", "settings get system screen_off_timeout", "dumpsys window | grep mCurrentFocus"), screen.ran)
    }

    @Test fun textSharesAreAlwaysPointedToTheShareTool() = runTest {
        val screen = Counting()
        val r = toolDispatcher(screen).run("am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT hi -p com.example.chat")
        assertFalse(r.ok())
        assertEquals("use_phone_tool", r.error())
        val hint = r.hint()!!
        assertTrue(hint, Feedback["system_command.use_share"] in hint && "phone_share_text" in hint)
        assertTrue(screen.ran.isEmpty())
    }

    @Test fun refusalStateIsPerRun() = runTest {
        toolDispatcher(Counting()).run("input -d 7 tap 1 1")
        // 新任务新的计数:第一次照样执行、只提醒
        val fresh = toolDispatcher(Counting()).run("input -d 7 tap 1 1")
        assertTrue(fresh.ok())
        assertEquals(Feedback["system_command.use_tap"], fresh.hint())
    }

    @Test fun repeatedFailuresAreSteeredUntilOneSucceeds() = runTest {
        val d = toolDispatcher(screen(1, 2, 0, 1))
        assertNull(d.run("pm path x").hint())
        assertEquals(Feedback["system_command.failed_again"], d.run("pm path y").hint())
        // 成功一次清零
        assertNull(d.run("pm path z").hint())
        assertNull(d.run("pm path w").hint())
        // 模仿专门工具又失败了:两句都说(第一次就执行;之后的不执行)
        val d2 = toolDispatcher(screen(1, 1))
        d2.run("pm path x")
        val both = d2.run("input -d 7 keyevent 66").hint()!!
        assertTrue(both, Feedback["system_command.use_key"] in both && Feedback["system_command.failed_again"] in both)
        assertFalse(Feedback["system_command.use_type"] in both)
    }
}
