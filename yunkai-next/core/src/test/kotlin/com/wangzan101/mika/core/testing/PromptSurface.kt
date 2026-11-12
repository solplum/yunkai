package com.wangzan101.mika.core.testing

import com.wangzan101.mika.core.model.ToolSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.security.MessageDigest

/**
 * 提示词表面的金样:全文写出(不是摘要),审改动时直接看得到差在哪。
 * 金样在 core/src/test/resources/prompt-surface/;重新生成:
 *   ./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots
 */
object PromptSurface {
    private val dir = File(System.getProperty("mika.promptSurfaceDir") ?: "src/test/resources/prompt-surface")
    private val update = System.getProperty("mika.updateSnapshots") == "true"

    fun check(name: String, actual: String) {
        val file = File(dir, "$name.txt")
        if (update) {
            file.parentFile.mkdirs()
            file.writeText(actual)
            return
        }
        if (!file.exists()) fail("没有金样 ${file.path};用 ./gradlew :core:test --tests '*PromptSurface*' -PupdateSnapshots 生成")
        assertEquals("提示词表面 $name 变了;确认是有意的改动后用 -PupdateSnapshots 重新生成", file.readText(), actual)
    }

    /** 一种能力组合:系统提示词全文,再列发给模型的工具(名字 + 定义摘要,定义全文在 tools 里)。 */
    fun system(system: String, tools: List<ToolSpec>): String = buildString {
        appendLine(system)
        appendLine()
        appendLine("==== tools (${tools.size}) ====")
        tools.forEach { appendLine("${it.name} #${hash(spec(it))}") }
    }

    /** 一次请求的全貌:系统提示词全文,再按发给模型的顺序列每个工具的定义全文。 */
    fun layer(system: String, tools: List<ToolSpec>): String = buildString {
        appendLine(system)
        appendLine()
        appendLine("==== tools (${tools.size}) ====")
        tools.forEach { append(spec(it)); appendLine() }
    }

    fun tools(all: List<ToolSpec>): String = buildString {
        all.forEach { append(spec(it)); appendLine() }
    }

    private fun spec(t: ToolSpec) = "## ${t.name}\n${t.description}\n${t.parametersJsonSchema}\n"

    /** 内部角色(标准、核查、描述、定位等)的提示词全文。 */
    fun roles(): String = buildString {
        for (name in ROLES) {
            appendLine("## $name")
            appendLine(role(name))
        }
    }

    private val ROLES = listOf("describe", "intent_gate", "locate", "memory_extract", "route_match", "skill_draft", "verifier", "stop_summary")

    private fun role(name: String): String =
        checkNotNull(PromptSurface::class.java.getResourceAsStream("/prompts/roles/$name.md")) { "missing role prompt $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** 一组「标签 → 发给模型的字」逐条写出。 */
    fun entries(items: List<Pair<String, String>>): String = buildString {
        items.forEach { (k, v) -> appendLine("### $k"); appendLine(v) }
    }

    fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
}
