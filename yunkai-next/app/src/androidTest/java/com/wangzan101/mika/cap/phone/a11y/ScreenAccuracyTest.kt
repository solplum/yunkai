package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.runAction
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.uistate.Verification
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真机测试:不重要节点能读到、焦点变化算变化、提示语有标记、输入控件被换掉后仍能核实。 */
@RunWith(AndroidJUnit4::class)
class ScreenAccuracyTest {
    private val agent = DeviceSupport.newAgent()
    private val screen get() = agent.screen

    @After
    fun tearDown() = agent.release()

    private suspend fun snapUntil(timeoutMs: Long = 5_000, test: (ScreenSnapshot) -> Boolean): ScreenSnapshot {
        val end = System.currentTimeMillis() + timeoutMs
        var s = screen.snapshot()
        while (!test(s) && System.currentTimeMillis() < end) {
            Thread.sleep(200)
            s = screen.snapshot()
        }
        assertTrue("screen never matched: ${s.packageName} ${s.elements.map { "${it.role}:${it.name}" }}", test(s))
        return s
    }

    private fun ok(r: ActionResult): ActionResult.Ok {
        assertTrue("expected Ok, got $r", r is ActionResult.Ok)
        return r as ActionResult.Ok
    }

    private suspend fun openFields(): ScreenSnapshot {
        DeviceSupport.startTestActivity(agent.display.displayId, FieldsTestActivity::class.java)
        return snapUntil { s -> s.packageName == TEST_PACKAGE && s.elements.any { it.name == "7" } }
    }

    private fun ScreenSnapshot.named(name: String): UiElement = elements.first { it.name == name }

    @Test
    fun notImportantResultTextIsReadAndItsChangeCounts() = runBlocking<Unit> {
        val s = openFields()
        assertTrue("result text not read: ${s.elements.map { it.name }}", s.elements.any { it.name == "0" })
        val r = ok(screen.tapRef(s.named("7").ref, s.snapshotId))
        assertEquals(true, r.changed)
        snapUntil { snap -> snap.elements.any { it.name == "07" } }
    }

    @Test
    fun focusingAFieldCountsAsChange() = runBlocking<Unit> {
        val s = openFields()
        val r = ok(screen.tapRef(s.named("空输入框").ref, s.snapshotId))
        assertEquals(true, r.changed)
    }

    @Test
    fun hintTextIsMarked() = runBlocking<Unit> {
        val s = openFields()
        val field = s.named("热词示例")
        assertTrue("$field", field.editable && field.hint)
        assertFalse(s.named("7").hint)
        assertEquals(Verification.MATCH, ok(screen.typeText(field.ref, s.snapshotId, "咖啡")).verification)
        val after = snapUntil { snap -> snap.elements.any { it.name == "咖啡" } }
        assertFalse(after.named("咖啡").hint)
    }

    @Test
    fun readbackFollowsAReplacedInputView() = runBlocking<Unit> {
        val s = openFields()
        val r = ok(screen.typeText(s.named("会换的框").ref, s.snapshotId, "天气"))
        assertEquals(Verification.MATCH, r.verification)
    }

    @Test
    fun typingIntoAFakeSearchBarTapsItAndUsesTheFocusedField() = runBlocking<Unit> {
        val s = openFields()
        val fake = s.named("假搜索框")
        assertFalse(fake.editable)
        val r = ok(screen.typeText(fake.ref, s.snapshotId, "咖啡"))
        assertEquals(Verification.MATCH, r.verification)
        assertTrue("${r.hint}", r.hint?.contains("tapped") == true)
        snapUntil { snap -> snap.elements.any { it.editable && it.name == "咖啡" } }
    }

    @Test
    fun typingIntoAButtonDoesNotTapIt() = runBlocking<Unit> {
        val s = openFields()
        assertEquals(ActionResult.Failed("not_editable"), screen.typeText(s.named("7").ref, s.snapshotId, "咖啡"))
        // 没有点下去:结果仍是 0
        assertTrue(screen.snapshot().elements.any { it.name == "0" })
    }

    /** scroll down 要看到更下面的内容:设置首页原来最下面那一项往上挪(或出现了原来没有的项)。 */
    @Test
    fun scrollDownShowsContentFurtherDown() = runBlocking<Unit> {
        ok(screen.openApp(DeviceSupport.SETTINGS, cold = true))
        val before = snapUntil { s -> s.elements.any { it.clickable && it.name.startsWith("WLAN") } }
        val tools = com.wangzan101.mika.core.assembly.toolDispatcher(screen)
        val r = tools.runAction(com.wangzan101.mika.core.model.Part.ToolCall("c1", "phone_scroll", """{"direction":"down"}""")).content
        assertEquals(r.toString(), "true", r["changed"].toString())
        assertEquals(r.toString(), "\"up\"", r["fingerMoved"].toString())
        val after = screen.snapshot()
        val newNames = after.elements.map { it.name }.toSet() - before.elements.map { it.name }.toSet()
        val wlan = after.elements.firstOrNull { it.name.startsWith("WLAN") }
        val wlanBefore = before.elements.first { it.name.startsWith("WLAN") }
        assertTrue("content did not move up: new=$newNames", newNames.isNotEmpty() && (wlan == null || wlan.centerY < wlanBefore.centerY))
    }

    /** 系统计算器(按 AOSP 的计算器类别找,没有就跳过):按数字后读得到显示区的值。只在虚拟屏上用。 */
    @Test
    fun calculatorDisplayIsRead() = runBlocking<Unit> {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pkg = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR)
            .let { ctx.packageManager.resolveActivity(it, 0)?.activityInfo?.packageName }
        assumeTrue("no calculator app", pkg != null)
        ok(screen.openApp(pkg!!, cold = true))
        var s = snapUntil(8_000) { snap -> snap.elements.any { it.clickable && it.name == "1" } }
        // 计算器会保留上次的输入,只要求按完后出现一个以 123 结尾、之前没有的显示值
        val before = s.elements.map { it.name }.toSet()
        for (digit in listOf("1", "2", "3")) {
            val key = s.elements.first { it.clickable && it.name == digit }
            val r = ok(screen.tapRef(key.ref, s.snapshotId))
            assertEquals("tap $digit", true, r.changed)
            s = screen.snapshot()
        }
        // 显示区可能带千分位等格式,只比数字
        snapUntil { snap -> snap.elements.any { e -> e.name !in before && e.name.filter(Char::isDigit).endsWith("123") } }
    }
}
