package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 对话页的附件:「＋」面板开关、面板里的照片、输入框上方待发的那一排、发出去的消息怎么画。 */
@RunWith(AndroidJUnit4::class)
class ChatAttachmentsTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 假的图:每张都是一块 40×30 的空图,不读手机。 */
    private val images = AttachImages { _, _ -> ImageBitmap(40, 30) }

    private fun composer(attachments: List<PendingAttachment> = emptyList(), text: String = "", onSend: () -> Unit = {}) = compose.setContent {
        MikaTheme {
            CompositionLocalProvider(LocalAttachImages provides images) {
                var t by remember { mutableStateOf(text) }
                Box(Modifier.fillMaxSize()) {
                    Composer(
                        Modifier, t, { t = it }, ComposerMode.Idle, hasHistory = false, onSend = onSend, onStop = {},
                        attachPanel = { Box(Modifier.fillMaxSize().testTag("panel-body")) },
                        attachments = attachments,
                    )
                }
            }
        }
    }

    private fun waitGone(tag: String) = compose.waitUntil(3_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    @Test fun plusOpensThePanelAndTurnsIntoClose() {
        composer()
        compose.onAllNodesWithTag("panel-body").assertCountEquals(0)
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        compose.onNodeWithTag("panel-body").assertIsDisplayed()
        // 再点(这时是 ×)收起
        compose.onNodeWithContentDescription(AttachCopy.CLOSE).performClick()
        waitGone("panel-body")
        compose.onNodeWithContentDescription(AttachCopy.ADD).assertIsDisplayed()
    }

    @Test fun tappingTheFieldClosesThePanel() {
        composer()
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        compose.onNodeWithTag("panel-body").assertIsDisplayed()
        compose.onNodeWithTag("composer-field").performClick()
        waitGone("panel-body")
    }

    @Test fun backClosesThePanelFirst() {
        composer()
        compose.onNodeWithContentDescription(AttachCopy.ADD).performClick()
        compose.onNodeWithTag("panel-body").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitGone("panel-body")
        assertEquals(false, compose.activity.isFinishing)
    }

    @Test fun trayShowsPhotosAndFileCardsAndAttachmentsAloneCanBeSent() {
        var sent = 0
        composer(
            listOf(
                PendingAttachment("content://m/1", "a.jpg", 1000),
                PendingAttachment("content://m/2", "b.png", 1000),
                PendingAttachment("content://f/1", "租房合同.pdf", 1200 * 1024),
            ),
            onSend = { sent++ },
        )
        compose.onAllNodesWithTag("tray-photo").assertCountEquals(2)
        compose.onAllNodesWithTag("tray-file").assertCountEquals(1)
        compose.onNodeWithText("租房合同.pdf").assertIsDisplayed()
        compose.onNodeWithText("1.2 MB").assertIsDisplayed()
        compose.onNodeWithContentDescription(AttachCopy.remove("a.jpg")).assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(1, sent)
    }

    @Test fun sendIsOffWithNothingToSend() {
        composer()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    @Test fun removingFromTheTrayCallsBack() {
        val removed = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    PendingTray(listOf(PendingAttachment("content://f/9", "a.pdf", 10)), { removed += it })
                }
            }
        }
        compose.onNodeWithContentDescription(AttachCopy.remove("a.pdf")).performClick()
        assertEquals(listOf("content://f/9"), removed)
    }

    private fun panel(access: PhotoAccess, recent: List<PendingAttachment>?, selected: List<PendingAttachment> = emptyList(), onGrant: () -> Unit = {}) =
        compose.setContent {
            MikaTheme {
                CompositionLocalProvider(LocalAttachImages provides images) {
                    AttachPanel(access, recent, selected, {}, {}, {}, {}, onGrant)
                }
            }
        }

    @Test fun panelWithoutPhotoAccessExplainsAndOffersToAllow() {
        var granted = 0
        panel(PhotoAccess.None, emptyList(), onGrant = { granted++ })
        compose.onNodeWithText(AttachCopy.CAMERA).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.ALBUM).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.FILES).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.NEED_ACCESS).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.GRANT).performClick()
        assertEquals(1, granted)
        compose.onAllNodesWithTag("recent-photo").assertCountEquals(0)
    }

    @Test fun panelListsRecentPhotosWithNumberedSelection() {
        val recent = (1..6).map { PendingAttachment("content://m/$it", "p$it.jpg", 1, photo = true) }
        panel(PhotoAccess.Full, recent, selected = listOf(recent[4], recent[1]))
        compose.onNodeWithText(AttachCopy.RECENT).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.ALL).assertIsDisplayed()
        compose.onAllNodesWithTag("recent-photo").assertCountEquals(6)
        compose.onAllNodes(hasTestTag("recent-photo") and isSelected()).assertCountEquals(2)
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
    }

    @Test fun partialAccessOffersToChangeTheSelection() {
        panel(PhotoAccess.Partial, listOf(PendingAttachment("content://m/1", "p.jpg", 1, photo = true)))
        compose.onNodeWithText(AttachCopy.RECENT_PARTIAL).assertIsDisplayed()
        compose.onNodeWithText(AttachCopy.CHANGE).assertIsDisplayed()
    }

    private fun bubble(text: String) = compose.setContent {
        MikaTheme {
            CompositionLocalProvider(LocalAttachImages provides images) {
                Box(Modifier.width(360.dp)) { UserBubble(text, "c-test") }
            }
        }
    }

    @Test fun sentMessageShowsPhotosFilesAndWordsButNoPaths() {
        bubble(
            "帮我看看这些\n附件:/workspace/inbox/a.jpg(1 KB)\n附件:/workspace/inbox/b.jpg(1 KB)\n" +
                "附件:/workspace/inbox/c.webp(1 KB)\n附件:/workspace/inbox/租房合同 (1).pdf(48 KB)",
        )
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(3)
        compose.onAllNodesWithTag("sent-file").assertCountEquals(1)
        compose.onNodeWithText("租房合同 (1).pdf").assertIsDisplayed()
        compose.onNodeWithText("48 KB").assertIsDisplayed()
        compose.onNodeWithText("帮我看看这些").assertIsDisplayed()
        compose.onAllNodesWithText("/workspace", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("附件:", substring = true).assertCountEquals(0)
    }

    @Test fun singlePhotoAloneHasNoWordsBubble() {
        bubble("附件:/workspace/inbox/a.jpg(1 KB)")
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(1)
        compose.onAllNodesWithText("附件", substring = true).assertCountEquals(0)
    }

    @Test fun plainMessageIsUnchanged() {
        bubble("明天早上 8 点提醒我带伞")
        compose.onNodeWithText("明天早上 8 点提醒我带伞").assertIsDisplayed()
        compose.onAllNodesWithTag("sent-photo").assertCountEquals(0)
        compose.onAllNodesWithTag("sent-file").assertCountEquals(0)
    }
}
