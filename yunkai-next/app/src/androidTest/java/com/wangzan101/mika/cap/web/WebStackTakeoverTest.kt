package com.wangzan101.mika.cap.web

import org.junit.Rule
import com.wangzan101.mika.ChineseUi
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.wangzan101.mika.cap.phone.a11y.DeviceSupport
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.takeover.HandoverActivity
import com.wangzan101.mika.takeover.Takeover
import com.wangzan101.mika.takeover.TakeoverPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import kotlin.coroutines.resume

/**
 * 真机测试:任务里真实的网页接管路径。接管状态机 → 任务的接管端口(和 TaskRun 里同样委托给 [WebStack])
 * → 标签锁住、同一个页面到主屏 → 用户点「处理好了」→ 交还状态机 → 标签放回、解锁、计数与登录标记交给 Agent。
 */
@RunWith(AndroidJUnit4::class)
class WebStackTakeoverTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var stack: WebStack
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val results: MutableList<HandBack> = Collections.synchronizedList(mutableListOf())
    private lateinit var takeover: Takeover

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        instr.runOnMainSync {
            val loader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(instr.context)).build()
            stack = WebStack.create(ctx, "run-1", bounds.width(), bounds.height(), ctx.resources.displayMetrics.densityDpi, listOf(loader))
        }
        // 和 TaskRun 的接管端口一样:网页部分交给 WebStack,按钮动作交回状态机
        val port = object : TakeoverPort {
            override suspend fun agentTopTask() = -1
            override suspend fun targetTask() = -1
            override fun targetPackage(): String? = null
            override suspend fun packageOf(taskId: Int): String? = null
            override suspend fun displayOf(taskId: Int) = -1
            override suspend fun moveToMain(taskId: Int) = false
            override suspend fun moveBack(taskId: Int) = false
            override fun setSound(packageName: String, on: Boolean) {}
            override fun watchInput(packageName: String) {}
            override suspend fun handoverActivity(): HandoverActivity? = null
            override fun stopWatching() {}
            override suspend fun resume(result: HandBack) { results += result }
            override fun cancel() {}
            override suspend fun showWeb(reason: String, key: Int): Boolean? = withContext(Dispatchers.Main) {
                stack.showForTakeover(reason, "请登录示例商城\n金额:9.90", key, actions = { a ->
                    scope.launch {
                        when (a) {
                            WebHandover.Action.HandBack, WebHandover.Action.Done -> takeover.handBack(key)
                            WebHandover.Action.FinishedMyself -> takeover.finishedMyself(key)
                            WebHandover.Action.Cancel -> takeover.cancel()
                            WebHandover.Action.PutAside -> takeover.putAside(key)
                        }
                    }
                }) { takeover.waitKey }
            }
            override suspend fun returnWeb(loggedIn: Boolean): HandoverActivity? = withContext(Dispatchers.Main) { stack.returnFromTakeover(loggedIn) }
        }
        takeover = Takeover(port, scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        instr.runOnMainSync { stack.close() }
    }

    private suspend fun js(tab: WebTab, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun displayOf(tab: WebTab): Int? {
        var id: Int? = null
        instr.runOnMainSync { id = tab.view.display?.displayId }
        return id
    }

    private fun click(tab: WebTab, label: String) {
        var target: Button? = null
        instr.runOnMainSync {
            fun walk(v: View) {
                if (v is Button && v.text == label) target = v
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(tab.view.rootView)
        }
        assertNotNull("no button $label", target)
        instr.runOnMainSync { target!!.performClick() }
    }

    @Test
    fun realTakeoverPathLocksMovesAndReturnsTheSamePage() = runBlocking<Unit> {
        val s = stack.screen
        s.navigate("https://appassets.androidplatform.net/assets/web-fixtures/inputs.html", newTab = false)
        val snap = s.snapshot()
        val plain = snap.elements.first { it.resourceId == "#plain" }
        assertTrue(s.typeText(plain.ref, snap.snapshotId, "填了一半") is ActionResult.Ok)
        val tab = withContext(Dispatchers.Main) { stack.pool.current("run-1") }!!

        takeover.onState(TaskState.WaitingTakeover("login", "请登录示例商城"))
        assertTrue(takeover.handOver())
        assertNotNull(DeviceSupport.pollUntil(5_000) { displayOf(tab)?.takeIf { it == Display.DEFAULT_DISPLAY } })
        assertTrue(withContext(Dispatchers.Main) { stack.pool.isLocked(tab.id) })
        // 用户手上的页面 Agent 动不了
        assertEquals(ActionResult.Failed("handed_over"), s.tapXY(10, 10))
        assertEquals("请登录示例商城\n金额:9.90", WebHandover.session.value?.message)
        assertEquals("\"填了一半\"", js(tab, "document.getElementById('plain').value"))

        DeviceSupport.pollUntil(3_000) { WebHandover.session.value?.url }
        click(tab, "处理好了")
        assertNotNull(DeviceSupport.pollUntil(5_000) { results.firstOrNull() })
        assertNotNull(DeviceSupport.pollUntil(5_000) { displayOf(tab)?.takeIf { it == stack.host.displayId } })
        assertEquals(HandBackOutcome.complete, results.single().outcome)
        assertEquals("inputs", results.single().stoppedAt)
        assertFalse(withContext(Dispatchers.Main) { stack.pool.isLocked(tab.id) })
        assertEquals("\"填了一半\"", js(tab, "document.getElementById('plain').value"))
        assertTrue(WebSites.registry(ctx).list().any { it.host == "appassets.androidplatform.net" && it.loggedInByUser })
        assertEquals(Takeover.Stage.None, takeover.stage.value)
    }

    @Test
    fun closingTheStackWhileHandedOverReturnsAndUnlocksFirst() = runBlocking<Unit> {
        stack.screen.navigate("https://appassets.androidplatform.net/assets/web-fixtures/host.html", newTab = false)
        takeover.onState(TaskState.WaitingTakeover("login", "请登录"))
        assertTrue(takeover.handOver())
        instr.runOnMainSync { stack.close() }
        instr.waitForIdleSync()
        assertEquals(null, WebHandover.session.value)
        assertTrue(stack.screen.tabs().isEmpty())
    }
}
