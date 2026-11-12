// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
// Modified for Yunkai Next isolated prototype (2026-10-30); see repository-root UPSTREAM.md.
package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ClipboardManager
import com.wangzan101.mika.about.CheckStatus
import com.wangzan101.mika.about.MikaLinks
import com.wangzan101.mika.about.ReleaseInfo
import com.wangzan101.mika.about.UpdateState
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.SystemStatus
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val r020 = ReleaseInfo(
        "0.2.0", "Mika 0.2.0", "### 新增\n- 附件面板", "2026-11-05",
        "https://github.com/wangzan101/Mika/releases/tag/v0.2.0", "https://github.com/x/mika-0.2.0.apk", "https://pan.example.cn/s/abc",
    )

    private class Calls {
        val urls = mutableListOf<String>()
        var checks = 0
        var saves = 0
        var licenses = 0
        var asks = 0
    }

    private val sampleDiagnostics = "云开诊断信息 / Yunkai diagnostics\n\n[App]\nversion: 0.1.0 (build abc1234)\n"

    private fun about(update: UpdateState = UpdateState()): Calls {
        val calls = Calls()
        compose.setContent {
            MikaTheme {
                AboutScreen(
                    versionName = "0.1.0", build = "abc1234", beta = true, update = update, issueUrl = "issue://prefilled",
                    onCheck = { calls.checks++ }, onOpenUrl = { calls.urls += it },
                    onSaveQr = { calls.saves++; true }, onAskMikaToJoin = { calls.asks++ }, onOpenLicense = { calls.licenses++ }, onBack = {},
                    diagnostics = { sampleDiagnostics },
                )
            }
        }
        return calls
    }

    private fun scrollTo(text: String) {
        compose.awaitOne(text).performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun showsHeroRowsNoteAndFooter() {
        about()
        compose.onNodeWithText("云开").assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.TAGLINE).assertIsDisplayed()
        compose.onNodeWithText("版本 0.1.0").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithText("· 构建 abc1234").assertIsDisplayed()
        for (t in listOf("复制诊断信息", "Mika 原始开源仓库", "Mika 原版使用说明", AboutCopy.DATA_NOTE, AboutCopy.AUTHOR)) {
            scrollTo(t)
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        // 许可证只在页脚,不再单独一行
        compose.onAllNodesWithText("开源许可").assertCountEquals(0)
    }

    @Test
    fun externalRowsOpenLinks() {
        val calls = about()
        for (t in listOf("Mika 原始开源仓库", "Mika 原版使用说明")) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf(MikaLinks.REPO, MikaLinks.README), calls.urls)
    }

    @Test
    fun footerOpensLicense() {
        val calls = about()
        compose.onNodeWithTag("about-license").performScrollTo().performClick()
        assertEquals(1, calls.licenses)
    }

    @Test
    fun forkDoesNotOfferUpstreamSupportOrAutomaticContact() {
        val calls = about()
        for (text in listOf("反馈问题", "加入内测群", "关注作者")) {
            compose.onAllNodesWithText(text).assertCountEquals(0)
        }
        compose.onAllNodesWithTag("group-ask-mika").assertCountEquals(0)
        assertEquals(0, calls.asks)
        assertEquals(0, calls.saves)
        assertTrue(calls.urls.isEmpty())
    }

    /** 「复制诊断信息」先打开预览:整段文字看得见,点「复制」才进剪贴板。 */
    @Test
    fun feedbackDiagnosticsPreviewsThenCopies() {
        about()
        compose.onNodeWithText("复制诊断信息").performClick()
        compose.awaitOne("不包含 API Key、聊天内容和屏幕内容")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(sampleDiagnostics).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("diagnostics-text").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-share").assertExists()
        compose.onNodeWithTag("diagnostics-copy").performClick()
        compose.waitForIdle()
        var clip: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
            clip = cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(sampleDiagnostics, clip)
    }

    @Test
    fun forkDoesNotOfferUpstreamReleaseOrInstallEvenWithStaleState() {
        val calls = about(UpdateState(latest = r020, update = r020, status = CheckStatus.Done))
        compose.onAllNodesWithText("检查更新").assertCountEquals(0)
        compose.onAllNodesWithText("更新记录").assertCountEquals(0)
        compose.onAllNodesWithTag("update-sheet").assertCountEquals(0)
        compose.onAllNodesWithTag("update-spinner").assertCountEquals(0)
        compose.onAllNodesWithTag("update-dot", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("从 GitHub 下载").assertCountEquals(0)
        compose.onAllNodesWithText("国内备用下载").assertCountEquals(0)
        assertEquals(0, calls.checks)
        assertTrue(calls.urls.isEmpty())
    }

    /** 侧栏最底下一行两个:设置靠左、关于云开 靠右(红点在最右边);有新版时关于带红点。 */
    @Test
    fun drawerFooterHasSettingsAndAbout() {
        val calls = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {}, onBrowser = {},
                    onSettings = { calls += "settings" }, onOpen = {},
                    onAbout = { calls += "about" }, aboutDot = true,
                )
            }
        }
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText("关于云开").assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
        val footer = compose.onNodeWithTag("drawer-footer").getUnclippedBoundsInRoot()
        // 量文字本身(未合并的树):合并后的可点行节点比文字宽,量它判断不了左右半边
        val settings = compose.onNodeWithText("设置", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val about = compose.onNodeWithText("关于云开", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val dot = compose.onNodeWithTag("update-dot", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val mid = (footer.left + footer.right) / 2
        assertTrue(settings.right < mid)
        assertTrue(about.left > mid)
        // 关于那一项贴着右边:红点离行的右边只隔两层内边距(行 12dp + 项 12dp)
        assertTrue(footer.right - dot.right <= 25.dp)
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("关于云开").performClick()
        assertEquals(listOf("settings", "about"), calls)
    }

    @Test
    fun drawerFooterNoDotWithoutUpdate() {
        compose.setContent {
            MikaTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {}, onBrowser = {}, onSettings = {}, onOpen = {},
                )
            }
        }
        compose.onNodeWithText("关于云开").assertIsDisplayed()
        compose.onAllNodesWithTag("update-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    /** 设置首页「关于云开」一行:有新版写「x 可更新」带红点。 */
    @Test
    fun settingsAboutRowShowsUpdate() {
        compose.setContent {
            MikaTheme {
                SettingsContent(
                    status = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true),
                    androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                    versionName = "0.1.0", updateVersion = "0.2.0",
                )
            }
        }
        scrollTo("0.2.0 可更新")
        compose.onNodeWithText("0.2.0 可更新").assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
    }
}
