package com.wangzan101.mika.core.action

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 执行入口的边界:[com.wangzan101.mika.core.tool.ActionPass] 的构造函数是 internal,App 模块造不出来;
 * core 里由这里守着只有 core.action 造它、只有 core.action 调不可逆判定与同意核对。
 * 包依赖只能单向:core.action 和 core.guard 不依赖调度它们的 core.agent。
 */
class SingleEntryTest {
    private val action = File("src/main/kotlin/com/wangzan101/mika/core/action").canonicalPath + File.separator
    private val roots = listOf(File("src/main/kotlin"), File("../app/src/main/java"))

    /** 源码里的代码行(整行注释不算;行内的字符串、网址照常算)。 */
    private fun codeLines(): List<Triple<File, Int, String>> = roots.filter { it.isDirectory }.flatMap { root ->
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else Triple(f, i + 1, line)
            }
        }.toList()
    }

    private fun outsideAction(f: File) = !f.canonicalPath.startsWith(action)

    private fun offenders(pattern: Regex) = codeLines()
        .filter { (f, _, line) -> outsideAction(f) && pattern.containsMatchIn(line) }
        .map { (f, n, line) -> "${f.path}:$n: ${line.trim()}" }

    @Test fun sourcesAreFound() {
        assertTrue("找不到源码目录(工作目录应为 core/)", roots.first().isDirectory)
        assertTrue(codeLines().any { (f, _, line) -> !outsideAction(f) && "ActionPass()" in line })
    }

    @Test fun onlyTheExecutorHoldsThePass() {
        val bad = offenders(Regex("""\bActionPass\s*\(""")).filterNot { "class ActionPass" in it }
        assertTrue("这些地方自己造执行凭证,绕过了执行入口:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun onlyTheExecutorDecides() {
        val bad = offenders(Regex(
            """IrreversibleDetector\s*\.\s*(needsCheck|classify|hasLabelAt|unresolved)\b|\.\s*permits\s*\(|\.\s*noteWrite\s*\(|\bIrreversibleDetector\s*[,)]"""
        ))
        assertTrue("这些地方绕过执行入口直接判定:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun actionAndGuardDoNotDependOnTheLoop() {
        val bad = listOf("action", "guard").flatMap { pkg ->
            File("src/main/kotlin/com/wangzan101/mika/core/$pkg").walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                f.readLines().filter { it.startsWith("import com.wangzan101.mika.core.agent.") }.map { "${f.path}: $it" }
            }.toList()
        }
        assertTrue("执行入口和守卫反过来依赖了主循环:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
