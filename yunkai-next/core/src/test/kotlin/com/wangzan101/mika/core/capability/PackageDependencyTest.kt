package com.wangzan101.mika.core.capability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 依赖方向:能力 → 核心;核心不引用任何能力,能力之间不互相引用;只有装配层同时认识几种能力。
 * 工具引擎(core/tool)不引用界面状态(core/uistate):界面状态属于有界面的能力,引擎只经 SurfaceLayer 用到它。
 * App 里 cap/ 下各能力的目录同样互不引用(core 里对应能力的包除外)。按源码里的 import 和写全的名字查,整行注释不算。
 */
class PackageDependencyTest {
    private val core = File("src/main/kotlin/com/wangzan101/mika/core")
    private val app = File("../app/src/main/java/com/wangzan101/mika")
    private val ref = Regex("""com\.wangzan101\.mika\.(core\.)?(cap\.([a-z0-9_]+)|assembly|uistate)\b""")

    private class Use(val file: File, val line: Int, val text: String, val target: String)

    /** [root] 下源码里引用到的能力包与装配层([Use.target] 是 cap.<能力> 或 core.assembly 一类)。 */
    private fun uses(root: File): List<Use> = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
        f.readLines().asSequence().mapIndexedNotNull { i, line ->
            val t = line.trim()
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("package ")) null else Triple(i, line, t)
        }.flatMap { (i, line, _) ->
            ref.findAll(line).map { m -> Use(f, i + 1, line.trim(), (m.groupValues[1] + m.groupValues[2]).removeSuffix(".")) }
        }.toList()
    }.toList()

    /** [f] 所在的能力目录名;不在 cap/ 下为 null。 */
    private fun capOf(root: File, f: File): String? {
        val rel = f.relativeTo(root).invariantSeparatorsPath
        return if (rel.startsWith("cap/")) rel.removePrefix("cap/").substringBefore('/') else null
    }

    private fun report(bad: List<Use>, root: File) = bad.joinToString("\n") { "${it.file.relativeTo(root)}:${it.line}: ${it.text}" }

    @Test fun sourcesAreFound() {
        assertTrue("找不到源码目录(工作目录应为 core/)", core.isDirectory && File(core, "cap").isDirectory)
    }

    @Test fun coreDoesNotKnowCapabilities() {
        val bad = uses(core).filter { u ->
            val rel = u.file.relativeTo(core).invariantSeparatorsPath
            !rel.startsWith("cap/") && !rel.startsWith("assembly/") && (u.target.startsWith("core.cap.") || u.target == "core.assembly")
        }
        assertTrue("核心引用了能力或装配层:\n" + report(bad, core), bad.isEmpty())
    }

    @Test fun toolEngineDoesNotKnowInterfaceState() {
        val bad = uses(core).filter { u -> u.file.relativeTo(core).invariantSeparatorsPath.startsWith("tool/") && u.target == "core.uistate" }
        assertTrue("工具引擎引用了界面状态:\n" + report(bad, core), bad.isEmpty())
    }

    @Test fun capabilitiesDoNotKnowEachOther() {
        val bad = uses(core).filter { u ->
            val own = capOf(core, u.file) ?: return@filter false
            u.target == "core.assembly" || (u.target.startsWith("core.cap.") && u.target != "core.cap.$own")
        }
        assertTrue("能力之间互相引用,或引用了装配层:\n" + report(bad, core), bad.isEmpty())
    }

    @Test fun appCapabilitiesDoNotKnowEachOther() {
        if (!File(app, "cap").isDirectory) return
        val bad = uses(app).filter { u ->
            val own = capOf(app, u.file) ?: return@filter false
            val other = u.target.removePrefix("core.").removePrefix("cap.")
            u.target.contains("cap.") && other != own
        }
        assertTrue("App 里的能力目录互相引用:\n" + report(bad, app), bad.isEmpty())
    }
}
