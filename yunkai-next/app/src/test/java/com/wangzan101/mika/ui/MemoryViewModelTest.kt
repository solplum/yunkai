// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.ui

import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.core.memory.MemorySource
import com.wangzan101.mika.settings.MemorySettings
import com.wangzan101.mika.task.memoryStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModelTest {
    @get:Rule val texts = ChineseTexts()

    @get:Rule val tmp = TemporaryFolder()
    private val said = MemorySource("user_said", "以后点外卖都不要香菜", "c1", 1_761_177_600_000L)
    private val store = memoryStore().apply {
        add(MemoryKind.fact, "用户住在张江", listOf("家"), MemorySource("manual", atMs = 1)).getOrThrow()
        add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), said).getOrThrow()
        add(MemoryKind.preference, "用户喝咖啡不加糖", listOf("咖啡"), said).getOrThrow()
        add(MemoryKind.procedure, "点外卖先比价", listOf("外卖"), said).getOrThrow()
    }
    private var wiped = 0
    private val broken = MutableStateFlow<String?>(null)
    private lateinit var settingsFile: File

    private fun TestScope.vm(): MemoryViewModel {
        settingsFile = File(tmp.newFolder(), "memory_settings.json")
        return MemoryViewModel(store, MemorySettings(settingsFile), null, backgroundScope, broken, wipe = { wiped++ }).also { runCurrent() }
    }

    @Test fun memoriesListAllKindsWithKind() = runTest {
        val vm = vm()
        assertEquals(
            setOf("用户住在张江", "用户点外卖不要香菜", "用户喝咖啡不加糖"),
            vm.ui.value.rows.map { it.content }.toSet(),
        )
        assertEquals(MemoryKind.fact, vm.ui.value.rows.first { it.content == "用户住在张江" }.kind)
        assertEquals("你手写的", vm.ui.value.rows.first { it.content == "用户住在张江" }.source)
        assertEquals("你在 10 月 23 日说:『以后点外卖都不要香菜』", vm.ui.value.rows.first { it.kind == MemoryKind.preference }.source)
    }

    @Test fun pendingCollectsProceduresAndCounts() = runTest {
        val vm = vm()
        vm.select(Segment.Pending); runCurrent()
        assertEquals(listOf("点外卖先比价"), vm.ui.value.pending.map { it.content })
        assertEquals(1, vm.ui.value.pendingCount)
        assertEquals(3, vm.ui.value.rows.size)
        vm.approve(vm.ui.value.pending.single().id); runCurrent()
        assertTrue(vm.ui.value.pending.isEmpty())
        assertEquals(0, vm.ui.value.pendingCount)
        assertTrue("点外卖先比价" in vm.ui.value.rows.map { it.content })
    }

    @Test fun searchUsesIndex() = runTest {
        val vm = vm()
        vm.search("咖啡"); runCurrent()
        assertEquals(listOf("用户喝咖啡不加糖"), vm.ui.value.rows.map { it.content })
        vm.search(""); runCurrent()
        // 空查询按更新时间倒序
        assertEquals(3, vm.ui.value.rows.size)
    }

    @Test fun editPersists() = runTest {
        val vm = vm()
        val id = vm.ui.value.rows.first { it.kind == MemoryKind.fact }.id
        vm.edit(id, "用户住在张江高科", listOf("家", "住址")); runCurrent()
        val row = vm.ui.value.rows.first { it.id == id }
        assertEquals("用户住在张江高科", row.content)
        assertEquals(listOf("家", "住址"), row.keywords)
        assertEquals("用户住在张江", row.replaces)
        vm.setPinned(id, true); runCurrent()
        assertTrue(vm.ui.value.rows.first { it.id == id }.pinned)
        vm.delete(id); runCurrent()
        assertTrue(vm.ui.value.rows.none { it.id == id })
    }

    @Test fun manualAddSensitiveShowsMessage() = runTest {
        val vm = vm()
        vm.add(MemoryKind.fact, "我的支付密码是123456", emptyList()); runCurrent()
        assertEquals("这类信息我不会记", vm.ui.value.message)
        assertEquals(3, vm.ui.value.rows.size)
        vm.dismissMessage()
        vm.add(MemoryKind.fact, "用户的妈妈叫老妈", emptyList()); runCurrent()
        assertEquals(4, vm.ui.value.rows.size)
        assertEquals("你手写的", vm.ui.value.rows.first { it.content == "用户的妈妈叫老妈" }.source)
    }

    @Test fun clearAllNeedsTwoConfirms() = runTest {
        val vm = vm()
        vm.requestClear(); runCurrent()
        assertEquals(1, vm.ui.value.clearStage)
        vm.confirmClear(); runCurrent()
        assertEquals(2, vm.ui.value.clearStage)
        assertEquals(0, wiped)
        vm.confirmClear(); runCurrent()
        assertEquals(1, wiped)
        assertEquals(0, vm.ui.value.clearStage)
        vm.requestClear(); vm.cancelClear(); runCurrent()
        assertEquals(1, wiped)
    }

    @Test fun brokenNoticeShown() = runTest {
        val vm = vm()
        broken.value = "memory.bin.broken-20261023120000"; runCurrent()
        assertEquals("memory.bin.broken-20261023120000", vm.ui.value.brokenNotice)
    }

    @Test fun switchesPersist() = runTest {
        val vm = vm()
        assertTrue(vm.ui.value.switches.useMemory && vm.ui.value.switches.autoRemember)
        assertFalse(vm.ui.value.switches.learnSkills)
        vm.setSwitches { it.copy(autoRemember = false, learnSkills = true) }; runCurrent()
        assertFalse(vm.ui.value.switches.autoRemember)
        val reread = MemorySettings(settingsFile).switches.value
        assertFalse(reread.autoRemember); assertTrue(reread.learnSkills); assertTrue(reread.useMemory)
    }

    private fun skillMd(name: String, body: String) = "---\nname: $name\ndisplay: 发微信\ndescription: 给联系人发微信\n---\n$body"
    private fun fullMd(name: String, steps: String) = skillMd(name, "## 入口\n打开微信\n## 步骤\n$steps\n## 容易出错的地方\n同名群\n## 怎样算办成(核实证据)\n消息出现\n## 什么时候停下交给用户\n要登录时")

    private fun TestScope.skillsVm(skills: com.wangzan101.mika.task.MapSkills): MemoryViewModel =
        MemoryViewModel(store, MemorySettings(File(tmp.newFolder(), "s.json")), skills, backgroundScope).also { it.select(Segment.Skills); runCurrent() }

    @Test fun approveWithEdit() = runTest {
        val skills = com.wangzan101.mika.task.MapSkills()
        skills.putPending("d1", com.wangzan101.mika.core.skills.PersonalSkill("wechat-msg", skillMd("wechat-msg", "## 步骤\n1. 点搜索"), com.wangzan101.mika.core.skills.SkillStatus.pending, "r1", null, 0, emptyList()))
        val vm = skillsVm(skills)
        assertEquals(listOf("d1"), vm.ui.value.skills.drafts.map { it.id })
        // 改过的全文同样过校验:缺小节、带敏感信息、提到没有的工具都不收
        vm.approveSkill("d1", skillMd("wechat-msg", "## 步骤\n1. 点搜索框")); runCurrent()
        assertTrue(vm.ui.value.message!!.contains("五个小节"))
        vm.dismissMessage()
        vm.approveSkill("d1", fullMd("wechat-msg", "支付密码是123456")); runCurrent()
        assertEquals("这类信息我不会记", vm.ui.value.message)
        vm.dismissMessage()
        vm.approveSkill("d1", fullMd("wechat-msg", "用 `open_chat` 打开")); runCurrent()
        assertEquals("里面提到了 云开 没有的工具", vm.ui.value.message)
        assertEquals(1, vm.ui.value.skills.drafts.size)
        vm.approveSkill("d1", fullMd("renamed", "1. 用 `phone_tap` 点搜索框")); runCurrent()
        assertTrue(vm.ui.value.skills.drafts.isEmpty())
        val got = skills.get("wechat-msg")!!
        assertTrue(got.markdown.contains("点搜索框"))
        assertTrue(got.markdown.startsWith("---\nname: wechat-msg\n"))
        assertEquals(listOf("wechat-msg"), vm.ui.value.skills.installed.map { it.name })
    }

    @Test fun updateDraftShowsChangedLines() = runTest {
        val old = com.wangzan101.mika.core.skills.PersonalSkill("wechat-msg", skillMd("wechat-msg", "## 步骤\n1. 点搜索\n2. 点发送"), com.wangzan101.mika.core.skills.SkillStatus.active, "r0", null, 0, emptyList())
        val skills = com.wangzan101.mika.task.MapSkills(old)
        skills.putPending("d2", old.copy(markdown = skillMd("wechat-msg", "## 步骤\n1. 点搜索\n2. 长按发送"), replacesDraftOf = "wechat-msg"))
        val vm = skillsVm(skills)
        assertEquals(listOf("+ 2. 长按发送", "- 2. 点发送"), vm.ui.value.skills.drafts.single().changedLines)
    }

    @Test fun restoreSuspended() = runTest {
        val s = com.wangzan101.mika.core.skills.PersonalSkill("wechat-msg", skillMd("wechat-msg", "## 步骤\n1. 点搜索"), com.wangzan101.mika.core.skills.SkillStatus.suspended, "r0", null, 0, listOf("failed", "verified", "unverified"))
        val skills = com.wangzan101.mika.task.MapSkills(s)
        val vm = skillsVm(skills)
        val row = vm.ui.value.skills.installed.single()
        assertTrue(row.suspended)
        assertEquals(listOf("没办成", "核查通过", "没能核实"), row.recent)
        vm.restoreSkill("wechat-msg"); runCurrent()
        assertEquals(com.wangzan101.mika.core.skills.SkillStatus.active, skills.get("wechat-msg")!!.status)
        assertTrue(skills.get("wechat-msg")!!.outcomes.isEmpty())
        assertTrue(!vm.ui.value.skills.installed.single().suspended)
        vm.suspendSkill("wechat-msg"); runCurrent()
        assertTrue(vm.ui.value.skills.installed.single().suspended)
        vm.deleteSkill("wechat-msg"); runCurrent()
        assertTrue(vm.ui.value.skills.installed.isEmpty())
    }
}
