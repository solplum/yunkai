package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.tool.placeOf
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ToolSpec
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.testing.verb
import com.wangzan101.mika.core.testing.SANDBOX_TOOLS

private class NamedModel(override val name: String, toolCalls: Boolean = true, vision: Boolean = false) : ChatModel {
    override val capabilities = Capabilities(toolCalls = toolCalls, vision = vision, contextTokens = 64_000, reasoning = false)
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("not called")
}

class ModelRouterTest {
    private val main = NamedModel("main")
    private val light = NamedModel("light")
    private fun clean(step: Int) = StepContext(step, lastOk = true, lastHadGuard = false, lastChanged = true, lastWasRecovery = false, sameAppAsLast = true)

    @Test fun firstStepUsesMain() {
        assertSame(main, ModelRouter(main, light).pick(clean(1)))
    }

    @Test fun lightAfterCleanStep() {
        val r = ModelRouter(main, light)
        assertSame(light, r.pick(clean(2)))
        assertSame(light, r.pick(clean(3)))
        // 读屏一类不报画面变化,不算没变
        assertSame(light, r.pick(clean(4).copy(lastChanged = null)))
    }

    @Test fun eachConditionKeepsMain() {
        val r = ModelRouter(main, light)
        assertSame(main, r.pick(clean(5).copy(lastWasRecovery = true)))
        assertSame(main, r.pick(clean(5).copy(sameAppAsLast = false)))
        // 不看图的轻量模型收不了截图
        assertSame(main, r.pick(clean(5).copy(hasImages = true)))
        val seeing = NamedModel("seeing", vision = true)
        assertSame(seeing, ModelRouter(main, seeing).pick(clean(5).copy(hasImages = true)))
    }

    @Test fun troubleForcesMainForTwoSteps() {
        for (trouble in listOf(clean(3).copy(lastOk = false), clean(3).copy(lastHadGuard = true), clean(3).copy(lastChanged = false))) {
            val r = ModelRouter(main, light)
            assertSame(main, r.pick(trouble))
            assertSame(main, r.pick(clean(4)))
            assertSame(light, r.pick(clean(5)))
        }
        val r = ModelRouter(main, light)
        r.noteTrouble()
        assertSame(main, r.pick(clean(6)))
        assertSame(main, r.pick(clean(7)))
        assertSame(light, r.pick(clean(8)))
    }

    @Test fun disabledRouterAlwaysMain() {
        val r = ModelRouter(main, light)
        r.disable()
        assertSame(main, r.pick(clean(5)))
        org.junit.Assert.assertFalse(r.active)
    }

    @Test fun markRecoveryMakesNextStepRecovery() {
        val t = StepTrack()
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        t.after(verb("phone_observe"), ok = true, guard = false, changed = null, recovery = false, app = "com.x")
        assertSame(light, ModelRouter(main, light).pick(t.context(3, hasImages = false)))
        t.markRecovery()
        assertSame(main, ModelRouter(main, light).pick(t.context(3, hasImages = false)))
    }

    @Test fun noToolCapableLightMeansMain() {
        val r = ModelRouter(main, NamedModel("plain", toolCalls = false))
        assertSame(main, r.pick(clean(2)))
        assertSame(main, ModelRouter(main, null).pick(clean(2)))
    }

    @Test fun sandboxToolsAreMainOnlyAndNotBatchable() {
        assertTrue(SANDBOX_TOOLS.all { ModelRouter.mainOnly(it, verb(it)) })
        assertTrue(SANDBOX_TOOLS.none { it in BATCHABLE })
    }
}

/** 「在哪」分手机 App 与网站:换到网页、打开网址都算换了地方。 */
class PlaceTrackingTest {
    @Test fun placeOfSeparatesAppsAndSites() {
        fun o(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject
        org.junit.Assert.assertEquals(Place.app("com.x"), placeOf(o("""{"currentApp":"com.x"}""")))
        org.junit.Assert.assertEquals(Place.site("a.com"), placeOf(o("""{"currentSite":"a.com"}""")))
        org.junit.Assert.assertEquals(Place.site("a.com"), placeOf(o("""{"ok":true,"screen":{"currentSite":"a.com"}}""")))
        org.junit.Assert.assertNull(placeOf(o("""{"ok":true}""")))
    }

    @Test fun navigatingIsASwitch() {
        val t = StepTrack()
        t.after(verb("phone_tap"), true, false, true, false, "网站 a.com")
        t.after(verb("phone_tap"), true, false, true, false, "网站 a.com")
        org.junit.Assert.assertTrue(t.context(3, false).sameAppAsLast)
        t.after(verb("web_navigate"), true, false, true, false, "网站 a.com")
        org.junit.Assert.assertFalse(t.context(4, false).sameAppAsLast)
    }
}
