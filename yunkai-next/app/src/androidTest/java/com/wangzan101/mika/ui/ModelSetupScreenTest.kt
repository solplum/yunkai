package com.wangzan101.mika.ui

import com.wangzan101.mika.ChineseUi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderPresets
import com.wangzan101.mika.settings.RoleChoice
import com.wangzan101.mika.settings.RoleChoices
import com.wangzan101.mika.ui.theme.MikaTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 欢迎页、配置模型一页(上手和设置共用)、选用途页、服务商详情、全部模型和「用作」面板(只用假数据,不联网)。 */
@RunWith(AndroidJUnit4::class)
class ModelSetupScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()
    private val calls = mutableListOf<String>()
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false)

    private val actions = ModelSetupActions(
        selectPreset = { calls += "preset $it" }, testAndSave = { calls += "test" }, openForm = { calls += "open form" },
        openProvider = { calls += "provider $it" }, pickRole = { calls += "role $it" }, next = { calls += "next" },
    )

    private val form = AddForm.of(ProviderPresets.all.first()).copy(apiKey = "sk")
    private val deepseek = Provider("p1", "DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com",
        listOf(ListedModel("deepseek-chat"), ListedModel("deepseek-coder", toolCalls = false)))
    private val qwen = Provider("p2", "通义千问", ProviderConfig.Kind.OpenAiCompat, "https://dashscope.aliyuncs.com/compatible-mode/v1",
        listOf("qwen-max", "qwen-plus", "qwen-turbo", "qwen-vl-max", "qwen-long").map { ListedModel(it) })
    private val main = ProviderConfig("c1", deepseek.kind, deepseek.baseUrl, "deepseek-chat", caps, deepseek.name, deepseek.id)
    private val vision = ProviderConfig("c2", qwen.kind, qwen.baseUrl, "qwen-vl-max", caps, qwen.name, qwen.id)

    private fun connected(roles: RoleChoices = RoleChoices(main = "c1")) = ModelSetupUi(
        form, providers = listOf(deepseek, qwen), configs = listOf(main, vision), roles = roles, keyed = setOf(deepseek.id, qwen.id),
    )

    private fun show(ui: ModelSetupUi, onboarding: Boolean = true) =
        compose.setContent { MikaTheme { ModelSetupContent(ui, onboarding = onboarding, actions = actions, onBack = {}) } }

    @Test fun welcomeStarts() {
        compose.setContent { MikaTheme { WelcomeScreen { calls += "start" } } }
        compose.onNodeWithText(WelcomeCopy.TITLE).assertIsDisplayed()
        compose.onNodeWithTag("welcome-start").performClick()
        assertEquals(listOf("start"), calls)
    }

    @Test fun firstVisitShowsFormAndNextIsDisabled() {
        show(ModelSetupUi(form))
        compose.onNodeWithTag("setup-next").assertIsNotEnabled()
        compose.onNodeWithTag("setup-test").assertIsEnabled().performClick()
        compose.onNodeWithTag("preset-Kimi").performClick()
        assertEquals(listOf("test", "preset Kimi"), calls)
    }

    @Test fun connectedProvidersAreListedAndNextEnables() {
        show(connected())
        compose.onNodeWithText(ModelsCopy.PROVIDERS).assertIsDisplayed()
        compose.onNodeWithText("已连接 · 2 个模型 · 主模型、轻量模型在用").assertIsDisplayed()
        // 轻量卡「用主模型」下面也写着主模型名,所以只在主模型卡里找
        compose.onNodeWithTag("role-main").assertTextContains("deepseek-chat", substring = true)
        // 表单收起,留一个入口
        compose.onNodeWithText(ModelsCopy.ADD_PROVIDER).performClick()
        compose.onNodeWithTag("provider-通义千问").performClick()
        compose.onNodeWithTag("role-vision").performScrollTo().performClick()
        compose.onNodeWithTag("setup-next").assertIsEnabled().performClick()
        assertEquals(listOf("open form", "provider p2", "role Vision", "next"), calls)
    }

    @Test fun settingsPageShowsAllThreeRolesWithoutBottomButton() {
        show(connected(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain)), onboarding = false)
        compose.onNodeWithText(ModelsCopy.TITLE).assertIsDisplayed()
        // 改动当场生效,左上角返回就行
        compose.onNodeWithTag("setup-next").assertDoesNotExist()
        ModelRole.entries.forEach { compose.onNodeWithText(ModelsCopy.roleLabel(it)).assertIsDisplayed() }
        compose.onNodeWithText(ModelsCopy.OFF).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.OFF_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.SAME_AS_MAIN).assertIsDisplayed()
        compose.onNodeWithTag("role-light").performClick()
        assertEquals(listOf("role Light"), calls)
    }

    @Test fun visionPickerOffersSameAsMainAndOffAndListsEveryModel() {
        val picked = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                RolePickerContent(
                    connected(RoleChoices(main = "c1", vision = RoleChoice.Off)), ModelRole.Vision,
                    RolePickerActions(pick = { p, m -> picked += "$p $m" }, choose = { picked += "$it" }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText(ModelsCopy.VISION_SAME_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.SAME_AS_MAIN).performClick()
        compose.onNodeWithText(ModelsCopy.OFF).performClick()
        compose.onNodeWithText("DeepSeek").assertIsDisplayed()
        // 不按能力置灰
        compose.onNodeWithText("deepseek-coder").assertIsEnabled().performClick()
        compose.onNodeWithText("qwen-long").performScrollTo().performClick()
        assertEquals(listOf("${RoleChoice.SameAsMain}", "${RoleChoice.Off}", "p1 deepseek-coder", "p2 qwen-long"), picked)
    }

    @Test fun lightPickerOffersOnlySameAsMain() {
        compose.setContent { MikaTheme { RolePickerContent(connected(), ModelRole.Light, RolePickerActions(), onBack = {}) } }
        compose.onNodeWithText(ModelsCopy.LIGHT_SAME_NOTE).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.OFF).assertDoesNotExist()
        compose.onNodeWithText(ModelsCopy.roleHint(ModelRole.Light)).assertIsDisplayed()
    }

    @Test fun pickerFiltersWhenThereAreManyModels() {
        val many = qwen.copy(models = (1..9).map { ListedModel("qwen-$it") })
        val ui = ModelSetupUi(form, providers = listOf(deepseek, many), configs = listOf(main), roles = RoleChoices(main = "c1"))
        compose.setContent { MikaTheme { RolePickerContent(ui, ModelRole.Main, RolePickerActions(), onBack = {}) } }
        compose.onNodeWithTag("model-filter").performTextInput("qwen-9")
        // 筛选框里也有这几个字,只看不是输入框的那一行
        compose.onNode(hasText("qwen-9") and !hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("qwen-1").assertDoesNotExist()
        compose.onNodeWithText("DeepSeek").assertDoesNotExist()
    }

    @Test fun providerDetailPreviewsInUseModelsFirstAndLinksToAll() {
        val got = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(
                    connected(RoleChoices(main = "c1", vision = RoleChoice.Model("c2"))), qwen,
                    ProviderDetailActions(showAll = { got += "all" }), onBack = {},
                )
            }
        }
        compose.onNodeWithText(ModelsCopy.CONNECTED).assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.KEY_SAVED).assertIsDisplayed()
        // 在用的排最前,只放 3 个
        compose.onNodeWithTag("model-qwen-vl-max").assertIsDisplayed()
        compose.onNodeWithText(ModelsCopy.roleName(ModelRole.Vision)).assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-max").assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-plus").assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-turbo").assertDoesNotExist()
        compose.onNodeWithText(ModelsCopy.viewAll(5)).performScrollTo().performClick()
        assertEquals(listOf("all"), got)
        // 删除前说清会影响哪个用途
        compose.onNodeWithText(ModelsCopy.DELETE).performScrollTo().performClick()
        compose.awaitOne(ModelsCopy.deleteBody(listOf(ModelRole.Vision)), inDialog = true).assertIsDisplayed()
    }

    @Test fun tappingAModelOpensUseAsSheetAndAssigns() {
        val used = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                ProviderDetailContent(connected(), deepseek, ProviderDetailActions(use = { role, m -> used += "$role $m" }), onBack = {})
            }
        }
        compose.onNodeWithTag("model-deepseek-coder").performClick()
        compose.awaitOne(ModelsCopy.useAsSubtitle("DeepSeek")).assertIsDisplayed()
        compose.awaitOne(ModelsCopy.roleName(ModelRole.Vision)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("Vision deepseek-coder"), used)
    }

    @Test fun useAsSheetMarksTheCurrentRole() {
        compose.setContent { MikaTheme { ProviderDetailContent(connected(), deepseek, ProviderDetailActions(), onBack = {}) } }
        compose.onNodeWithTag("model-deepseek-chat").performClick()
        compose.awaitOne(ModelsCopy.IN_USE).assertIsDisplayed()
        compose.awaitOne(ModelSetupCopy.CANCEL).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(ModelsCopy.IN_USE).assertDoesNotExist()
    }

    @Test fun allModelsSearchesAndRefreshes() {
        val got = mutableListOf<String>()
        compose.setContent {
            MikaTheme {
                AllModelsContent(
                    connected(RoleChoices(main = "c1", vision = RoleChoice.Model("c2"))), qwen,
                    ProviderDetailActions(refresh = { got += "refresh" }), onBack = {},
                )
            }
        }
        compose.onNodeWithText(ModelsCopy.allModelsTitle("通义千问")).assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-long").assertIsDisplayed()
        compose.onNodeWithTag("all-models-filter").performTextInput("plus")
        compose.onNodeWithTag("model-qwen-plus").assertIsDisplayed()
        compose.onNodeWithTag("model-qwen-long").assertDoesNotExist()
        compose.onNodeWithContentDescription(ModelsCopy.REFRESH).performClick()
        assertEquals(listOf("refresh"), got)
    }

    @Test fun allModelsShowsRefreshInProgress() {
        val running = connected().copy(listing = mapOf(qwen.id to ListState.Running))
        compose.setContent { MikaTheme { AllModelsContent(running, qwen, ProviderDetailActions(), onBack = {}) } }
        compose.onNodeWithText(ModelsCopy.LISTING).assertIsDisplayed()
        compose.onNodeWithContentDescription(ModelsCopy.REFRESH).assertIsNotEnabled()
    }
}
