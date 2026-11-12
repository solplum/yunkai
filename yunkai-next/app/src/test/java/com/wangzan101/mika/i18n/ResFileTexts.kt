package com.wangzan101.mika.i18n

import com.wangzan101.mika.R
import org.junit.rules.ExternalResource
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * JVM 单测里的界面文字:直接读 `src/main/res/<folder>/` 下的资源文件,按安卓的规则去掉转义,
 * 免得为了取文字起一个安卓环境。[folder] 是 `values`(英文)或 `values-zh`(中文)。
 */
class ResFileTexts(folder: String, private val locale: Locale) : UiTexts {
    val strings: Map<String, String>
    val plurals: Map<String, Map<String, String>>

    init {
        val s = mutableMapOf<String, String>()
        val p = mutableMapOf<String, Map<String, String>>()
        resFiles(folder).forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                val name = e.getAttribute("name")
                when (e.tagName) {
                    "string" -> s[name] = unescape(e.textContent)
                    "plurals" -> {
                        val items = e.getElementsByTagName("item")
                        p[name] = (0 until items.length).associate { j ->
                            val item = items.item(j) as Element
                            item.getAttribute("quantity") to unescape(item.textContent)
                        }
                    }
                }
            }
        }
        strings = s
        plurals = p
    }

    override fun string(id: Int, args: Array<out Any?>): String {
        val name = stringNames[id] ?: error("no R.string with id $id")
        val raw = strings[name] ?: fallback?.strings?.get(name) ?: error("no string $name")
        return if (args.isEmpty()) raw else String.format(locale, raw, *args)
    }

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String {
        val name = pluralNames[id] ?: error("no R.plurals with id $id")
        val forms = plurals[name] ?: error("no plurals $name")
        // 英文:1 用 one;中文只有 other
        val raw = (if (count == 1) forms["one"] else null) ?: forms.getValue("other")
        return String.format(locale, raw, *(if (args.isEmpty()) arrayOf(count) else args))
    }

    /** 不翻译的字(translatable="false")只在默认文件里。 */
    private val fallback: ResFileTexts? = if (folder == "values") null else english

    companion object {
        val chinese by lazy { ResFileTexts("values-zh", Locale.SIMPLIFIED_CHINESE) }
        val english: ResFileTexts by lazy { ResFileTexts("values", Locale.ENGLISH) }

        fun resFiles(folder: String): List<File> =
            (File("src/main/res/$folder").listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") } ?: emptyArray())
                .sortedBy { it.name }

        private fun names(cls: Class<*>): Map<Int, String> = cls.fields.associate { it.getInt(null) to it.name }
        private val stringNames by lazy { names(R.string::class.java) }
        private val pluralNames by lazy { names(R.plurals::class.java) }

        /** 安卓资源文字的转义:引号里原样保留空白,引号外连续空白并成一个;反斜杠转义。 */
        fun unescape(raw: String): String {
            val out = StringBuilder()
            var quoted = false
            var i = 0
            var space = false
            while (i < raw.length) {
                val c = raw[i]
                when {
                    c == '\\' && i + 1 < raw.length -> {
                        val n = raw[i + 1]
                        out.append(
                            when (n) {
                                'n' -> '\n'
                                't' -> '\t'
                                'u' -> raw.substring(i + 2, i + 6).toInt(16).toChar().also { i += 4 }
                                else -> n
                            },
                        )
                        i += 2
                        space = false
                        continue
                    }
                    c == '"' -> quoted = !quoted
                    !quoted && c.isWhitespace() -> {
                        if (!space) out.append(' ')
                        space = true
                        i++
                        continue
                    }
                    else -> out.append(c)
                }
                space = false
                i++
            }
            return if (raw.trim().startsWith('"')) out.toString() else out.toString().trim(' ')
        }
    }
}

/** 测试里把界面文字固定成中文(断言写的是中文)。 */
class ChineseTexts : ExternalResource() {
    private var previous: UiTexts? = null
    override fun before() {
        previous = UiText.source
        UiText.source = ResFileTexts.chinese
    }

    override fun after() {
        UiText.source = previous
    }
}

/** 测试里把界面文字换成英文。 */
class EnglishTexts : ExternalResource() {
    private var previous: UiTexts? = null
    override fun before() {
        previous = UiText.source
        UiText.source = ResFileTexts.english
    }

    override fun after() {
        UiText.source = previous
    }
}
