package com.wangzan101.mika.core.prompt

import com.wangzan101.mika.core.agent.BatchRunner
import com.wangzan101.mika.core.testing.PromptSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.wangzan101.mika.core.testing.BATCHABLE
import com.wangzan101.mika.core.assembly.allTools

/** prompts/ 的分层规矩(见 prompts/README.md):高层不点工具名、每个工具一份说明、没有多余的文件、反馈自成一体;各层大小写进金样。 */
class PromptLayoutTest {
    private val root = File("src/main/resources/prompts")
    private fun files(layer: String) = File(root, layer).listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
    private fun size(layer: String) = File(root, layer).listFiles().orEmpty().filter { it.isFile }.sumOf { it.readBytes().size }

    private val toolNames = allTools().map { it.name } + BatchRunner.spec(BATCHABLE).name
    /** 也是普通英文词的工具名:system/ 里照常当词用,不算点名。 */
    private val plainWords = setOf("phone_screenshot", "phone_back", "wait", "phone_tap", "phone_scroll", "phone_swipe")

    @Test fun systemLayerNamesNoTools() {
        for (f in files("system")) {
            val text = File(root, "system/$f").readText()
            val named = toolNames.filter { it !in plainWords && Regex("(?<![A-Za-z_])${Regex.escape(it)}(?![A-Za-z_])").containsMatchIn(text) }
            assertTrue("system/$f names tools $named; tool rules belong in capabilities/ or tools/", named.isEmpty())
        }
    }

    /** 通用工具的总规则一直都在,不点名任何能力的工具(没有那种能力时也照样出现)。 */
    @Test fun generalNamesNoCapabilityTool() {
        val text = File(root, "capabilities/general.md").readText()
        val named = allTools().filter { it.capability != null }.map { it.name }
            .filter { Regex("(?<![A-Za-z_])${Regex.escape(it)}(?![A-Za-z_])").containsMatchIn(text) }
        assertTrue("capabilities/general.md names capability tools $named; put them in that capability's section", named.isEmpty())
    }

    @Test fun everyToolHasExactlyOneDescriptionFile() {
        val used = allTools().associate { it.name to it.promptFile } + (BatchRunner.spec(BATCHABLE).name to "do_steps")
        val defs = allTools().associate { it.name to it.description } + (BatchRunner.spec(BATCHABLE).name to BatchRunner.spec(BATCHABLE).description)
        for ((tool, file) in used) {
            assertTrue("$tool: missing prompts/tools/$file.md", File(root, "tools/$file.md").isFile)
            // 说明确实出自这份文件:模板里的占位符换成「任意文字」后能对上
            val template = File(root, "tools/$file.md").readText().trim()
            val pattern = template.split(Regex("\\{\\{[a-z0-9_]+}}")).joinToString(".+") { Regex.escape(it) }
            assertTrue("$tool does not load prompts/tools/$file.md", Regex(pattern).matches(defs.getValue(tool)))
        }
        assertEquals("orphan or missing files in prompts/tools", used.values.map { "$it.md" }.toSet(), files("tools"))
    }

    /** 入口工具(能力那一组收着时也提供的)各有一份短说明,没有多余的;短说明只点名本任务登记的工具。 */
    @Test fun everyEntryToolHasShortDescription() {
        val entries = allTools().filter { it.entry != null }.map { it.name }
        assertEquals(setOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command"), entries.toSet())
        assertEquals(entries.map { "$it.md" }.toSet(), files("entries"))
        for (e in entries) {
            val text = File(root, "entries/$e.md").readText()
            val named = Regex("(?<![A-Za-z_])(?:phone|web)_[a-z_]+").findAll(text).map { it.value }.filter { it !in toolNames }.toList()
            assertTrue("entries/$e.md names unknown tools $named", named.isEmpty())
        }
    }

    @Test fun noOrphanFiles() {
        assertEquals(setOf("identity.md", "principles.md"), files("system"))
        assertEquals((listOf("phone", "batch", "web", "sandbox", "general", "inline-screen", "channels", "groups", "handover")).map { "$it.md" }.toSet(), files("capabilities"))
        assertEquals(Feedback.FILES.map { "$it.md" }.toSet(), files("feedback"))
        val roles = setOf("describe", "intent_gate", "locate", "memory_extract", "route_match", "skill_draft", "verifier", "outcome_judge", "user_screen", "stop_summary", "conversation_title")
        assertEquals(roles.map { "$it.md" }.toSet(), files("roles"))
        roles.forEach { assertTrue(Prompts.role(it).isNotBlank()) }
        assertEquals(setOf("README.md"), root.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet())
    }

    /** 反馈目录里的每个键都有代码在用(按错误码的说明整段按前缀取)。 */
    @Test fun noOrphanFeedbackKeys() {
        val code = File("src/main/kotlin").walk().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val unused = Feedback.keys.filter { k -> "\"$k\"" !in code && !(k.startsWith("failure.") && "\"failure.\"" in code) }
        assertTrue("feedback keys not used by any code: $unused", unused.isEmpty())
    }

    /**
     * 每条反馈自成一体:首尾不带空白(读取时首尾的换行本来就会去掉,靠它们分隔的拼法会悄悄丢字),
     * 代码也不拿一条反馈和别的字用 + 或字符串模板拼;要拼就在模板里写好分隔,用 {{占位符}} 填。
     */
    @Test fun feedbackEntriesAreSelfContained() {
        for (f in Feedback.FILES) for ((k, v) in Feedback.parse(File(root, "feedback/$f.md").readText())) {
            assertTrue("feedback $k starts or ends with whitespace", v == v.trim())
        }
        val problems = mutableListOf<String>()
        for (file in File("src/main/kotlin").walk().filter { it.extension == "kt" }) {
            val s = file.readText()
            for (m in Regex("""Feedback(\[|\.format\()""").findAll(s)) {
                val end = closing(s, m.range.last)
                val before = s.substring(0, m.range.first).trimEnd()
                val after = s.substring(end + 1).trimStart()
                if (before.endsWith("+") || after.startsWith("+") || before.endsWith("\${")) problems += "${file.name}: ${s.substring(m.range.first, end + 1).take(60)}"
            }
            // 取反馈的名字(val X get() = Feedback…、fun x(…) = Feedback.format…)也不能拿去拼
            val names = Regex("""val ([A-Z_0-9]+): String get\(\) = Feedback""").findAll(s).map { it.groupValues[1] } +
                Regex("""fun ([a-zA-Z0-9]+)\([^)]*\) = Feedback\.format""").findAll(s).map { it.groupValues[1] }
            for (n in names.toSet()) {
                val used = Regex("""(\+\s*$n\b)|(\b$n\s*\+)|(\$$n\b)|(\$\{$n\b)""")
                // 别的文件也可能用
                File("src/main/kotlin").walk().filter { it.extension == "kt" }.forEach { g ->
                    used.find(g.readText())?.let { problems += "${g.name}: ${it.value} concatenates feedback $n" }
                }
            }
        }
        assertTrue("feedback used as part of a concatenation: $problems", problems.isEmpty())
    }

    /** 从开括号 [open] 起找配对的闭括号;跳过字符串。 */
    private fun closing(s: String, open: Int): Int {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '"' -> { i++; while (s[i] != '"') { if (s[i] == '\\') i++; i++ } }
                '(', '[' -> depth++
                ')', ']' -> if (--depth == 0) return i
            }
            i++
        }
        error("unbalanced at $open")
    }

    /**
     * 各层的大小(字节,UTF-8)写进提示词表面的金样(prompt-surface/sizes.txt):不设硬上限,
     * 增长在提交的差异里看得到,审的时候判断该不该。
     */
    @Test fun layerSizesRendered() {
        val text = buildString {
            for (layer in listOf("system", "capabilities", "tools", "entries", "feedback", "roles")) {
                appendLine("$layer: ${size(layer)} bytes, ${files(layer).size} files")
            }
        }
        print(text)
        PromptSurface.check("sizes", text)
    }
}
