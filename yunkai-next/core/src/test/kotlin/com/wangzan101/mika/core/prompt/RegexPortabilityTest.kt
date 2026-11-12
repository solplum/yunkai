package com.wangzan101.mika.core.prompt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 安卓的正则引擎比电脑上的 Java 严:没转义的 `{`、`}`(不是 {n}、{n,m} 这类次数)在电脑上能编译,
 * 装到手机上一加载就抛错。这里扫源码里所有写死的正则字面量,把这一类挡在电脑上。
 */
class RegexPortabilityTest {
    private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java"))
    // 只看紧跟在调用括号后的那个字面量;三引号原样,普通字符串按 Kotlin 转义还原
    private val call = Regex("""(?:Regex|Pattern\.compile)\(\s*(\"\"\"[\s\S]*?\"\"\"|"(?:[^"\\]|\\.)*")""")
    private val toRegex = Regex("""("(?:[^"\\]|\\.)*")\.toRegex\(""")

    @Test
    fun literalPatternsHaveNoBareBraces() {
        val offenders = roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val t = line.trim()
                    if (t.startsWith("//") || t.startsWith("*")) return@mapIndexedNotNull null
                    (call.findAll(line) + toRegex.findAll(line)).map { pattern(it.groupValues[1]) }
                        .firstOrNull { hasBareBrace(it) }
                        ?.let { "${f.path}:${i + 1}: $it" }
                }.toList()
            }
        }
        assertTrue("正则里有没转义的花括号,安卓上会编译失败:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun detectorCatchesTheClass() {
        assertTrue(hasBareBrace("""\{\{([a-z0-9_]+)}}"""))
        assertTrue(hasBareBrace("""\{\d+}"""))
        assertTrue(!hasBareBrace("""\{\{([a-z0-9_]+)\}\}"""))
        assertTrue(!hasBareBrace("""\p{L}{1,8}\s{2}[{}]"""))
    }

    private fun pattern(lit: String) =
        if (lit.startsWith("\"\"\"")) lit.removeSurrounding("\"\"\"")
        else lit.removeSurrounding("\"").replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")

    /** 去掉转义的花括号、\p{…} 这类属性、[…] 字符组和 {n}/{n,}/{n,m} 次数后,还剩花括号就算。 */
    private fun hasBareBrace(pattern: String): Boolean {
        var p = pattern.replace(Regex("""\\[pP]\{[A-Za-z_=]+\}"""), "")
        p = p.replace(Regex("""\\[{}\\]"""), "")
        p = p.replace(Regex("""\[(?:\\.|[^\]])*]"""), "")
        p = p.replace(Regex("""\{\d+(,\d*)?}"""), "")
        return '{' in p || '}' in p
    }
}
