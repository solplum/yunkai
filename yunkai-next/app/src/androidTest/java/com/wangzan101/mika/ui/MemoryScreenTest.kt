package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemoryPersistence
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.core.memory.MemoryStatus
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.settings.MemorySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MemoryScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val store = MemoryStore(object : MemoryPersistence {
        override fun load() = emptyList<MemoryItem>()
        override fun save(items: List<MemoryItem>) {}
    }, System::currentTimeMillis, { java.util.UUID.randomUUID().toString() })

    private fun show() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val vm = MemoryViewModel(store, MemorySettings(File(dir, "memory-screen-test.json").apply { delete() }), null,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
        compose.setContent { MemoryScreen(vm) {} }
    }

    @Test fun addEditDeleteRoundTrip() {
        show()
        compose.onNodeWithText("手写一条").performClick()
        compose.onNodeWithTag("memory-content").performTextInput("用户住在张江")
        compose.onNodeWithTag("memory-save").performClick()
        // 点一行升起操作面板,选「改一改」
        compose.onNodeWithText("用户住在张江").performClick()
        compose.onNodeWithText("改一改").performClick()
        compose.onNodeWithTag("memory-content").performTextClearance()
        compose.onNodeWithTag("memory-content").performTextInput("用户住在张江高科")
        compose.onNodeWithTag("memory-save").performClick()
        compose.waitForIdle()
        assertEquals("用户住在张江高科", store.items.value.single().content)
        compose.onNodeWithText("用户住在张江高科").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.waitForIdle()
        assertTrue(store.items.value.isEmpty())
    }

    @Test fun approveProcedure() {
        store.add(MemoryKind.procedure, "点外卖先比价", emptyList(), MemorySource("user_said", "以后点外卖先比价", atMs = 1)).getOrThrow()
        show()
        compose.onNodeWithTag("segment-Pending").performClick()
        compose.onNodeWithText("好").performClick()
        compose.waitForIdle()
        assertEquals(MemoryStatus.active, store.items.value.single().status)
        // 批准后从「待确认」里拿走,出现在记忆列表里
        compose.onNodeWithText("没有等你确认的东西").assertExists()
        compose.onNodeWithTag("segment-Memories").performClick()
        assertEquals(1, compose.onAllNodesWithTag("memory-row").fetchSemanticsNodes().size)
    }
}
