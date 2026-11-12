package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 模型页的文字:用途卡、服务商一行、删除提示,以及列表排序。 */
class ModelsCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)
    private val ds = Provider("p1", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com",
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-reasoner"), ListedModel("deepseek-coder")))
    private val qw = Provider("p2", "通义千问", ProviderConfig.Kind.OpenAiCompat, "https://dashscope.aliyuncs.com/compatible-mode/v1",
        listOf(ListedModel("qwen-vl", vision = true)))
    private val main = ProviderConfig("c1", ds.kind, ds.baseUrl, "deepseek-reasoner", caps, ds.name, ds.id)
    private val vl = ProviderConfig("c2", qw.kind, qw.baseUrl, "qwen-vl", caps, qw.name, qw.id)

    private fun ui(roles: RoleChoices, providers: List<Provider> = listOf(ds, qw), configs: List<ProviderConfig> = listOf(main, vl)) =
        ModelSetupUi(AddForm.of(ProviderPresets.all.first()), providers = providers, configs = configs, roles = roles)

    @Test fun roleWordingLivesInOnePlace() {
        assertEquals("主模型 · 思考和办事", ModelsCopy.roleLabel(ModelRole.Main))
        assertEquals("看图模型 · 读屏时看截图", ModelsCopy.roleLabel(ModelRole.Vision))
        assertEquals("轻量模型 · 判断、记东西这类小事", ModelsCopy.roleLabel(ModelRole.Light))
        ModelRole.entries.forEach { assertTrue(ModelsCopy.roleHint(it).endsWith("。")) }
    }

    @Test fun roleLinesSaySameAsMainAndOffPlainly() {
        val u = ui(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain))
        assertEquals(ModelsCopy.RoleLine("deepseek-reasoner", "DeepSeek", isModel = true), ModelsCopy.roleLine(u, ModelRole.Main))
        assertEquals(ModelsCopy.RoleLine("不用", "只靠文字读屏"), ModelsCopy.roleLine(u, ModelRole.Vision))
        assertEquals(ModelsCopy.RoleLine("用主模型", "deepseek-reasoner"), ModelsCopy.roleLine(u, ModelRole.Light))
        val v = ui(RoleChoices(main = "c1", vision = RoleChoice.Model("c2")))
        assertEquals(ModelsCopy.RoleLine("qwen-vl", "通义千问", isModel = true), ModelsCopy.roleLine(v, ModelRole.Vision))
    }

    @Test fun unsetMainSaysWhatToDoNext() {
        val none = ui(RoleChoices(vision = RoleChoice.SameAsMain), providers = emptyList(), configs = emptyList())
        assertEquals(ModelsCopy.RoleLine("未设置", "先添加一个服务商", unset = true), ModelsCopy.roleLine(none, ModelRole.Main))
        assertEquals(ModelsCopy.RoleLine("用主模型", "主模型还没选"), ModelsCopy.roleLine(none, ModelRole.Vision))
        val connected = ui(RoleChoices(), configs = emptyList())
        assertEquals("点这里选一个", ModelsCopy.roleLine(connected, ModelRole.Main).subtitle)
    }

    @Test fun providerLineNamesTheRolesUsingIt() {
        val u = ui(RoleChoices(main = "c1", vision = RoleChoice.Model("c2"), light = RoleChoice.SameAsMain))
        assertEquals(listOf(ModelRole.Main, ModelRole.Light), u.rolesOf(ds))
        assertEquals("已连接 · 3 个模型 · 主模型、轻量模型在用", ModelsCopy.providerLine(ds, null, u.rolesOf(ds)))
        assertEquals("已连接 · 1 个模型 · 看图模型在用", ModelsCopy.providerLine(qw, ListState.Idle, u.rolesOf(qw)))
        assertEquals("列模型失败", ModelsCopy.providerLine(ds, ListState.Failed("x"), u.rolesOf(ds)))
        assertEquals("已连接 · 不提供模型列表", ModelsCopy.providerLine(ds.copy(models = emptyList()), null, emptyList()))
        assertEquals("已连接", ModelsCopy.providerLine(ds.copy(models = null), null, emptyList()))
    }

    @Test fun deleteWarningNamesRolesToRepick() {
        assertEquals("这家的 API Key 会一起删掉。主模型、轻量模型正在用它，删除后要重新选。",
            ModelsCopy.deleteBody(listOf(ModelRole.Main, ModelRole.Light)))
        assertEquals("这家的 API Key 会一起删掉。它没在给任何用途干活。", ModelsCopy.deleteBody(emptyList()))
    }

    @Test fun inUseModelsComeFirstAndPickedIgnoresSameAsMain() {
        val u = ui(RoleChoices(main = "c1", vision = RoleChoice.SameAsMain))
        assertEquals(listOf("deepseek-reasoner", "deepseek-chat", "deepseek-coder"), u.sortedOptions(ds).map { it.id })
        assertEquals(listOf(ModelRole.Main, ModelRole.Vision, ModelRole.Light), u.rolesOf(ds.id, "deepseek-reasoner"))
        assertTrue(u.picked(ModelRole.Main, ds.id, "deepseek-reasoner"))
        assertFalse("用主模型不算明确选了它", u.picked(ModelRole.Vision, ds.id, "deepseek-reasoner"))
    }

    @Test fun pickerFixedChoicesPerRole() {
        assertEquals(emptyList<RoleChoice>(), fixedChoices(ModelRole.Main))
        assertEquals(listOf(RoleChoice.SameAsMain, RoleChoice.Off), fixedChoices(ModelRole.Vision))
        assertEquals(listOf(RoleChoice.SameAsMain), fixedChoices(ModelRole.Light))
        assertEquals("主模型能看图时选这个", ModelsCopy.choiceNote(ModelRole.Vision, RoleChoice.SameAsMain))
        assertEquals("和主模型同一个，不用另外选", ModelsCopy.choiceNote(ModelRole.Light, RoleChoice.SameAsMain))
        assertEquals("只靠文字读屏", ModelsCopy.choiceNote(ModelRole.Vision, RoleChoice.Off))
    }

    @Test fun countsAndTitles() {
        assertEquals("查看全部 9 个模型", ModelsCopy.viewAll(9))
        assertEquals("通义千问 · 全部模型", ModelsCopy.allModelsTitle("通义千问"))
        assertEquals("通义千问 · 用作", ModelsCopy.useAsSubtitle("通义千问"))
        assertTrue(ModelsCopy.allModelsNote(9).startsWith("共 9 个，"))
    }
}
