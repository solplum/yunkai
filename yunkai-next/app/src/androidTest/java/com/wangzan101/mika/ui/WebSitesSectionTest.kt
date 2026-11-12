package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.cap.web.SiteEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 设置页「网站登录」:列表、单站退出(带「可能仍在」提示)、全部退出要二次确认。 */
@RunWith(AndroidJUnit4::class)
class WebSitesSectionTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val calls = mutableListOf<String>()

    private fun show(open: ((String) -> Unit)? = { calls += "open $it" }) {
        compose.setContent {
            Column {
                WebSitesSection(
                    WebSitesState(
                        sites = listOf(SiteEntry("shop.example.com", 1_000, true), SiteEntry("news.example.org", 2_000, false)),
                        onOpenLogin = open,
                        onLogout = { calls += "logout $it"; 2 },
                        onLogoutAll = { calls += "all" },
                    ),
                )
            }
        }
    }

    @Test
    fun listsSitesAndDataNote() {
        show()
        compose.onNodeWithText("shop.example.com").assertIsDisplayed()
        compose.onNodeWithText("news.example.org").assertIsDisplayed()
        compose.onNodeWithText(WEB_DATA_NOTE).assertIsDisplayed()
    }

    @Test
    fun logoutOneSiteWarnsItMayPersist() {
        show()
        compose.awaitOne("shop.example.com").performClick()
        compose.clickInDialog("退出这个网站")
        assertEquals(listOf("logout shop.example.com"), calls)
        compose.onNodeWithText("已退出 shop.example.com。$WEB_LOGOUT_PARTIAL").assertIsDisplayed()
    }

    @Test
    fun openLoginFromTheSiteDialog() {
        show()
        compose.awaitOne("news.example.org").performClick()
        compose.clickInDialog("打开登录")
        assertEquals(listOf("open news.example.org"), calls)
    }

    @Test
    fun logoutAllNeedsConfirmation() {
        show()
        compose.awaitOne("全部退出并清除网页数据").performClick()
        assertEquals(emptyList<String>(), calls)
        compose.clickInDialog("全部退出")
        assertEquals(listOf("all"), calls)
    }
}
