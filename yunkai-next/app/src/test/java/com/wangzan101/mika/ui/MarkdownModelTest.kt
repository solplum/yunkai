package com.wangzan101.mika.ui

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownModelTest {
    private fun para(md: String) = (MarkdownModel.parse(md).single() as MdBlock.Para).text

    @Test
    fun inlineStyles() {
        val t = para("a **粗** *斜* ~~删~~ `code` b")
        assertEquals("a 粗 斜 删 code b", t.text)
        fun styleOf(word: String) = t.spanStyles.single { t.text.substring(it.start, it.end) == word }.item
        assertEquals(FontWeight.SemiBold, styleOf("粗").fontWeight)
        assertEquals(FontStyle.Italic, styleOf("斜").fontStyle)
        assertEquals(TextDecoration.LineThrough, styleOf("删").textDecoration)
        assertEquals(FontFamily.Monospace, styleOf("code").fontFamily)
    }

    @Test
    fun linksOnlyForOpenableSchemes() {
        val t = para("[官网](https://example.com) 和 [脚本](javascript:alert(1))")
        assertEquals("官网 和 脚本", t.text)
        val links = t.getLinkAnnotations(0, t.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com", (links.single().item as LinkAnnotation.Url).url)
        assertEquals("官网", t.text.substring(links.single().start, links.single().end))
    }

    @Test
    fun lineBreaksKept() {
        assertEquals("第一行\n第二行\n第三行", para("第一行\n第二行  \n第三行").text)
    }

    @Test
    fun headingsCodeQuoteRule() {
        val b = MarkdownModel.parse("# 标题\n\n## 小标题\n\n```kotlin\nval x = 1\n```\n\n> 引用\n\n---\n")
        assertEquals(MdBlock.Heading::class, b[0]::class)
        assertEquals(1, (b[0] as MdBlock.Heading).level)
        assertEquals(2, (b[1] as MdBlock.Heading).level)
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), b[2])
        assertEquals("引用", ((b[3] as MdBlock.Quote).blocks.single() as MdBlock.Para).text.text)
        assertEquals(MdBlock.Rule, b[4])
    }

    @Test
    fun nestedLists() {
        val l = MarkdownModel.parse("1. 一\n   - 甲\n   - 乙\n2. 二\n").single() as MdBlock.ListBlock
        assertEquals(1, l.start)
        assertTrue(l.tight)
        assertEquals(2, l.items.size)
        val inner = l.items[0][1] as MdBlock.ListBlock
        assertNull(inner.start)
        assertEquals(listOf("甲", "乙"), inner.items.map { (it.single() as MdBlock.Para).text.text })
        assertEquals(3, (MarkdownModel.parse("3. 三\n4. 四").single() as MdBlock.ListBlock).start)
    }

    @Test
    fun tableRowsPaddedToHeader() {
        val t = MarkdownModel.parse("| 名称 | 价格 |\n|---|---|\n| **苹果** | 5 |\n| 梨 |\n").single() as MdBlock.Table
        assertEquals(listOf("名称", "价格"), t.header.map { it.text })
        assertEquals(listOf(listOf("苹果", "5"), listOf("梨", "")), t.rows.map { r -> r.map { it.text } })
    }

    /** 流式输出写到一半、或格式写坏了,都照常出结果,不抛异常。 */
    @Test
    fun partialAndMalformedInputNeverThrows() {
        val code = MarkdownModel.parse("看这里:\n```py\nprint(1)").last() as MdBlock.Code
        assertEquals("print(1)", code.code)
        assertEquals("**没写完", para("**没写完").text)
        assertEquals("| a | b |\n|--", para("| a | b |\n|--").text)
        assertEquals(emptyList<MdBlock>(), MarkdownModel.parse(""))
        val deep = MarkdownModel.parse(">".repeat(500) + " 很深")
        assertTrue(deep.isNotEmpty())
        MarkdownModel.parse("[x](  \n ![](<) <div> \u0000 ` `` *_*_ \\")
    }

    @Test
    fun streamingPreviewFormatsUnclosedInlineSyntax() {
        val bold = (MarkdownModel.parseStreaming("先说 **重点").single() as MdBlock.Para).text
        assertEquals("先说 重点", bold.text)
        assertEquals(FontWeight.SemiBold, bold.spanStyles.single().item.fontWeight)
        assertEquals("先说", (MarkdownModel.parseStreaming("先说 **").single() as MdBlock.Para).text.text)

        val code = (MarkdownModel.parseStreaming("用 `command").single() as MdBlock.Para).text
        assertEquals("用 command", code.text)
        assertEquals(FontFamily.Monospace, code.spanStyles.single().item.fontFamily)

        val strike = (MarkdownModel.parseStreaming("~~旧内容").single() as MdBlock.Para).text
        assertEquals("旧内容", strike.text)
        assertEquals(TextDecoration.LineThrough, strike.spanStyles.single().item.textDecoration)
    }

    @Test
    fun streamingPreviewHidesIncompleteLinksAndKeepsBlockLayout() {
        val link = (MarkdownModel.parseStreaming("看 [官网](https://exam").single() as MdBlock.Para).text
        assertEquals("看 官网", link.text)
        assertTrue(link.getLinkAnnotations(0, link.length).isEmpty())
        assertEquals("看 官网", (MarkdownModel.parseStreaming("看 [官网").single() as MdBlock.Para).text.text)

        val heading = MarkdownModel.parseStreaming("# 标题\n\n- 列表项")
        assertTrue(heading[0] is MdBlock.Heading)
        assertTrue(heading[1] is MdBlock.ListBlock)
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), MarkdownModel.parseStreaming("```kotlin\nval x = 1").single())
        assertEquals(MdBlock.Code("", "[x](https://unfinished"), MarkdownModel.parseStreaming("```\n[x](https://unfinished").single())

        val pendingTable = (MarkdownModel.parseStreaming("| 名称 | 价格 |\n|---").single() as MdBlock.Para).text
        assertEquals("名称 · 价格", pendingTable.text)
        assertTrue(MarkdownModel.parseStreaming("| 名称 | 价格 |\n|---|---|\n| 苹果 | 5 |").single() is MdBlock.Table)
    }
}
