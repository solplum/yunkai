package com.wangzan101.mika.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** 英文(默认)和中文两份界面文字要一一对上:同样的条目、同样的占位符;英文里不该留着没翻的中文。 */
class StringResourcesTest {
    private val en = ResFileTexts.english
    private val zh = ResFileTexts.chinese

    /** 标了 translatable="false" 的条目只在默认文件里。 */
    private val fixed: Set<String> = ResFileTexts.resFiles("values").flatMap { f ->
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("translatable") == "false" }.map { it.getAttribute("name") }
    }.toSet()

    @Test
    fun everyStringHasBothLanguages() {
        assertEquals(en.strings.keys - fixed, zh.strings.keys)
        assertEquals(en.plurals.keys, zh.plurals.keys)
        assertTrue(en.strings.size > 300)
    }

    @Test
    fun placeholdersMatch() {
        // 日期两种语言取的字段不同(中文写月份数字,英文写月份名)
        for (name in zh.strings.keys - setOf("date_month_day", "date_year_month_day")) assertEquals(name, args(en.strings.getValue(name)), args(zh.strings.getValue(name)))
        for (name in zh.plurals.keys) {
            val expected = args(zh.plurals.getValue(name).getValue("other"))
            en.plurals.getValue(name).values.forEach { assertEquals(name, expected, args(it)) }
        }
    }

    @Test
    fun englishHasNoChineseLeftOver() {
        // 例子里的博主名本来就是中文
        val allowed = fixed + "chat_example_creator"
        val cjk = Regex("[\\u4e00-\\u9fff]")
        val left = en.strings.filter { (k, v) -> k !in allowed && cjk.containsMatchIn(v) }.keys +
            en.plurals.filter { (_, forms) -> forms.values.any(cjk::containsMatchIn) }.keys
        assertEquals(emptySet<String>(), left)
    }

    @Test
    fun languageFromTag() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(""))
        assertEquals(AppLanguage.Chinese, AppLanguage.fromTag("zh-Hans"))
        assertEquals(AppLanguage.Chinese, AppLanguage.fromTag("zh-TW"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("fr-FR"))
    }

    private fun args(s: String): Set<String> = Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value }.toSet()
}
