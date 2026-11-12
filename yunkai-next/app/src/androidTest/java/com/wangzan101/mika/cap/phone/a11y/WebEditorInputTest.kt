package com.wangzan101.mika.cap.phone.a11y

import android.app.UiAutomation
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.a11y.WebEditorTestActivity
import java.io.File
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机测试:网页富文本编辑区。无障碍的 SET_TEXT 只改了网页里的文字,编辑器收不到 input 事件,
 * App 会以为没输入(例如「完成」按钮不亮、退出时不保存)。打字必须让编辑器收到真正的输入。
 */
@RunWith(AndroidJUnit4::class)
class WebEditorInputTest {
    private val agent = DeviceSupport.newAgent()
    private val screen get() = agent.screen

    @After
    fun tearDown() = agent.release()

    private fun snapUntil(timeoutMs: Long = 8_000, test: (ScreenSnapshot) -> Boolean): ScreenSnapshot = runBlocking {
        val end = System.currentTimeMillis() + timeoutMs
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.elements.map { it.name }}", test(s))
        s
    }

    private fun status(s: ScreenSnapshot) = s.elements.firstOrNull { it.name.startsWith("inputs=") }?.name

    @Test
    fun typingReachesTheWebEditor() = runBlocking<Unit> {
        DeviceSupport.startTestActivity(agent.display.displayId, WebEditorTestActivity::class.java)
        val s = snapUntil { snap -> snap.elements.any { it.editable } }
        val field = s.elements.first { it.editable }

        val r = screen.typeText(field.ref, s.snapshotId, "你好 Mika")
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
        val after = snapUntil { snap -> status(snap)?.endsWith("text=你好 Mika") == true }
        assertTrue(status(after)!!, !status(after)!!.startsWith("inputs=0"))

        // 再打一次是替换,不是接在后面
        val again = screen.typeText(after.elements.first { it.editable }.ref, after.snapshotId, "第二次")
        assertEquals(Verification.MATCH, (again as ActionResult.Ok).verification)
        val replaced = snapUntil { snap -> status(snap)?.endsWith("text=第二次") == true }

        val cleared = screen.clearText(replaced.elements.first { it.editable }.ref, replaced.snapshotId)
        assertTrue("$cleared", cleared is ActionResult.Ok)
        snapUntil { snap -> status(snap)?.endsWith("text=") == true }
    }

    /**
     * 用户原来复制的内容(带自己的标签)在打字后原样还回去。Mika 的界面开在主屏上,这样测试进程读得到剪贴板;
     * 还原后截一张主屏图(files/shots/clip-restored.png),人工确认没有弹「已复制」浮层。
     */
    @Test
    fun pastingRestoresTheUsersClipboard() = runBlocking<Unit> {
        val instr = InstrumentationRegistry.getInstrumentation()
        val ctx = instr.targetContext
        ActivityScenario.launch(MainActivity::class.java).use {
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            instr.runOnMainSync { cm.setPrimaryClip(ClipData.newPlainText("user-label", "用户原来复制的 123")) }

            DeviceSupport.startTestActivity(agent.display.displayId, WebEditorTestActivity::class.java)
            val s = snapUntil { snap -> snap.elements.any { it.editable } }
            val r = screen.typeText(s.elements.first { it.editable }.ref, s.snapshotId, "粘贴测试")
            // 还原刚发生:「已复制」浮层若会弹,此刻就在主屏上
            val shot = instr.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
            File(ctx.getExternalFilesDir(null), "shots").mkdirs()
            File(ctx.getExternalFilesDir(null), "shots/clip-restored.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 80, it) }
            assertEquals(Verification.MATCH, (r as ActionResult.Ok).verification)
            snapUntil { snap -> status(snap)?.endsWith("text=粘贴测试") == true }

            Thread.sleep(1500)
            var clip: ClipData? = null
            instr.runOnMainSync { clip = cm.primaryClip }
            assertEquals("user-label", clip?.description?.label?.toString())
            assertEquals("用户原来复制的 123", clip?.getItemAt(0)?.text?.toString())

        }
    }
}
