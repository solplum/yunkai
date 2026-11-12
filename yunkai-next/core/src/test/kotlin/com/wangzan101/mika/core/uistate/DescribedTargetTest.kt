package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.testing.button
import com.wangzan101.mika.core.testing.field
import com.wangzan101.mika.core.testing.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DescribedTargetTest {
    private val screen = listOf(
        label("@e1", "登录"),
        button("@e2", "登录", y = 300),
        button("@e3", "关注", y = 400),
        button("@e4", "关注", y = 500),
        button("@e5", "Sign in with email", y = 600),
        field("@e6", "搜索商品", y = 700).copy(placeholder = "搜索商品"),
        field("@e7", "张三", y = 800, hint = false).copy(placeholder = "收件人"),
        UiElement("@e8", "link", "关注", 540, 2200, listOf(0, 880, 999, 960), null, true, false, false),
    )

    private fun match(text: String, role: String? = null, field: Boolean = false) = DescribedTarget.match(screen, ElementQuery(text, role, field))

    @Test fun uniqueExactNameIsFound() {
        assertEquals("@e5", (match("sign in with EMAIL") as DescribedMatch.Found).element.ref)
    }

    @Test fun sameTextOnALabelAndAButtonPicksTheOneThatCanBeTapped() {
        assertEquals("@e2", (match("登录") as DescribedMatch.Found).element.ref)
    }

    @Test fun severalTappableMatchesAreAmbiguous() {
        val m = match("关注") as DescribedMatch.Ambiguous
        assertEquals(listOf("@e3", "@e4", "@e8"), m.candidates.map { it.ref })
    }

    @Test fun roleNarrowsSameNamedElements() {
        assertEquals("@e8", (match("关注", role = "LINK") as DescribedMatch.Found).element.ref)
        assertTrue(match("关注", role = "button") is DescribedMatch.Ambiguous)
    }

    @Test fun exactMatchWinsOverPartialMatches() {
        val els = listOf(button("@e1", "提交"), button("@e2", "提交订单"))
        assertEquals("@e1", (DescribedTarget.match(els, ElementQuery("提交")) as DescribedMatch.Found).element.ref)
    }

    @Test fun partialTextIsUsedWhenNothingMatchesExactly() {
        assertEquals("@e5", (match("Sign in") as DescribedMatch.Found).element.ref)
    }

    @Test fun exactnessIsReported() {
        assertEquals(true, (match("sign in with email") as DescribedMatch.Found).exact)
        assertEquals(false, (match("Sign in") as DescribedMatch.Found).exact)
    }

    @Test fun disabledElementsDoNotCountAsActionable() {
        val off = button("@e1", "下一步").copy(states = setOf(UiStates.DISABLED))
        val on = button("@e2", "下一步", y = 300)
        assertEquals("@e2", (DescribedTarget.match(listOf(off, on), ElementQuery("下一步")) as DescribedMatch.Found).element.ref)
    }

    @Test fun substringOnlyMatchesActionableElements() {
        val els = listOf(label("@e1", "请先阅读用户协议"), button("@e2", "同意用户协议并继续", y = 300))
        // 「用户协议」只是正文里的一段:包含这一层只认能点的
        assertEquals("@e2", (DescribedTarget.match(els, ElementQuery("用户协议")) as DescribedMatch.Found).element.ref)
        assertEquals(DescribedMatch.None, DescribedTarget.match(listOf(els[0]), ElementQuery("用户协议")))
    }

    @Test fun ambiguousListsActionableCandidatesFirst() {
        val els = listOf(label("@e1", "关注"), label("@e2", "关注", y = 300))
        assertEquals(listOf("@e1", "@e2"), (DescribedTarget.match(els, ElementQuery("关注")) as DescribedMatch.Ambiguous).candidates.map { it.ref })
        val mixed = listOf(label("@e1", "关注"), button("@e2", "关注", y = 300), button("@e3", "关注", y = 400))
        assertEquals(listOf("@e2", "@e3", "@e1"), (DescribedTarget.match(mixed, ElementQuery("关注")) as DescribedMatch.Ambiguous).candidates.map { it.ref })
    }

    @Test fun neighbourIsTheClosestOtherText() {
        val els = listOf(label("@e1", "张三", y = 290), button("@e2", "关注", y = 300), label("@e3", "李四", y = 600))
        assertEquals("张三", DescribedTarget.neighbour(els[1], els))
    }

    @Test fun nothingMatching() {
        assertEquals(DescribedMatch.None, match("购物车"))
        assertEquals(DescribedMatch.None, match("   "))
        assertEquals(DescribedMatch.None, match("关注", role = "checkbox"))
    }

    @Test fun typingLooksOnlyAtInputFieldsAndTheirPlaceholders() {
        assertEquals("@e7", (match("收件人", field = true) as DescribedMatch.Found).element.ref)
        assertEquals("@e6", (match("搜索", field = true) as DescribedMatch.Found).element.ref)
        assertEquals(DescribedMatch.None, match("登录", field = true))
    }
}
