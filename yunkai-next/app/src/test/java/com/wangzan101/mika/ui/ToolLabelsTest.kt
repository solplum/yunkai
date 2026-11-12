package com.wangzan101.mika.ui

import com.wangzan101.mika.core.assembly.knownTools
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.i18n.ResFileTexts
import com.wangzan101.mika.i18n.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ToolLabelsTest {
    @get:Rule val texts = ChineseTexts()

    @Test
    fun toolLabelsHasBrowserTools() {
        assertEquals("打开网页", ToolLabels(knownTools()).label("browser_navigate"))
        assertEquals("读网页", ToolLabels(knownTools()).label("browser_read_page"))
        assertEquals("读取网页数据", ToolLabels(knownTools()).label("browser_evaluate_js"))
        assertEquals("切换网页标签", ToolLabels(knownTools()).label("browser_tabs"))
        assertEquals("查看下载", ToolLabels(knownTools()).label("browser_downloads"))
        assertEquals("上传文件", ToolLabels(knownTools()).label("browser_upload"))
    }

    /** 每个声明了 label 的工具都有界面文字,中文和声明一字不差;英文另有一份。 */
    @Test
    fun everyDeclaredLabelIsLocalized() {
        val tools = knownTools()
        val labels = ToolLabels(tools)
        val declared = tools.names.mapNotNull { n -> tools[n]?.label?.let { n to it } }
        assertTrue(declared.size > 40)
        for ((name, label) in declared) {
            assertTrue("$name: $label", label in ToolLabels.TOOL_LABELS)
            assertEquals(name, label, labels.label(name))
        }
        UiText.source = ResFileTexts.english
        assertEquals("Tap", labels.label("tap"))
        assertEquals("Open web page", labels.label("browser_navigate"))
    }
}
