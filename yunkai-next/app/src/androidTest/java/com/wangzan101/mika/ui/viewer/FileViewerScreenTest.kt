package com.wangzan101.mika.ui.viewer

import com.wangzan101.mika.ChineseUi
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileViewerScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun file(name: String, content: String): Uri =
        Uri.fromFile(File(context.cacheDir, name).apply { writeText(content) })

    private fun show(uri: Uri, name: String) {
        val kind = FileViewer.kindOf(context, uri, name)
        compose.setContent { MikaTheme { FileViewerScreen(uri, name, kind, onBack = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.loading").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun markdownFileIsRendered() {
        show(file("notes.md", "# 周报\n\n本周完成了 **三件事**。\n\n- 第一项\n- 第二项\n"), "notes.md")
        compose.onNodeWithTag("viewer.markdown").assertIsDisplayed()
        compose.onNodeWithText("周报").assertIsDisplayed()
        compose.onNodeWithText("第一项").assertIsDisplayed()
        compose.onNodeWithText("notes.md").assertIsDisplayed()
    }

    @Test fun textFileIsShownAsIs() {
        show(file("run.log", "line one\nline two: ok\n"), "run.log")
        compose.onNodeWithTag("viewer.text").assertIsDisplayed()
        compose.onNodeWithText("line two: ok", substring = true).assertIsDisplayed()
    }

    @Test fun unsupportedFileFallsBackToOtherApps() {
        val uri = file("archive.zip", "PK")
        assertNull(FileViewer.kindOf(context, uri, "archive.zip"))
        show(uri, "archive.zip")
        compose.onNodeWithTag("viewer.unsupported").assertIsDisplayed()
        compose.onNodeWithText("用其他应用打开").assertIsDisplayed()
    }
}
