package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.knowledge.RECOMMEND_MIN_SCORE
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.verify.AnswerSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.net.UrlHost

/**
 * 起草技能的全部输入:[events] 是这次运行的事件,[toolNames] 是 Mika 当前的工具名,
 * [tools] 按名字查工具做的动作。
 */
/** [answer] 是交给用户的最终回答,[sources] 是代码找到的回答来源(见 [AnswerSource])。 */
data class DraftInput(
    val runId: String, val goal: String, val answer: String, val sources: List<AnswerSource>, val events: List<RunEvent>,
    val toolNames: Set<String>, val tools: ToolIndex,
)

/** 做事的动作(手机、网页、沙箱);读屏、查技能、收尾这类不算步数。 */
private val ACTION_VERBS = setOf(Verb.Tap, Verb.Scroll, Verb.Swipe, Verb.TypeText, Verb.ClearText, Verb.Back, Verb.PressKey, Verb.LaunchApp, Verb.ShareText,
    Verb.Navigate, Verb.Upload, Verb.Tabs, Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile)

/** 什么时候值得起草;开关与评测模式由调用方判断。 */
object Eligibility {
    const val MIN_ACTIONS = 5

    /** null = 可以起草;否则是不起草的原因。[verified]:任务结局核实过(只从核实过的任务学)。 */
    fun check(events: List<RunEvent>, followedPersonalSkill: String?, tools: ToolIndex, verified: Boolean): String? {
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        return when {
            ended?.outcome != "succeeded" -> "not_succeeded"
            !verified -> "not_verified"
            // 出错的动作不算步数(结果可能被截短,按原文判)
            events.count { it is RunEvent.ToolResult && tools.verb(it.tool) in ACTION_VERBS && !it.resultJson.contains("\"ok\":false") } < MIN_ACTIONS -> "too_few_actions"
            events.any { it is RunEvent.Takeover && it.outcome == "skipped" } -> "takeover_skipped"
            // 用户自己办完的:那段路线 Mika 没走过
            events.any { it is RunEvent.Takeover && it.outcome == "user_finished" } -> "takeover_user_finished"
            events.any { it is RunEvent.Confirmation && it.decision in DECLINED } -> "confirmation_declined"
            followedPersonalSkill != null -> "followed_personal_skill"
            else -> null
        }
    }

    private val DECLINED = setOf("declined", "declined_before")
}

/**
 * 动作清单的一行:只有工具、目标元素名字、所在 App 或网站([where],网站写成「网站 主机名」)、是否生效、错误码。
 * [fromPage]:目标名字取自网页读屏,是网页内容(可能带个人信息),起草时当原文查泄漏。[typed]:这一步是输入文字。
 */
data class ActionLine(
    val step: Int, val tool: String, val target: String?, val where: String?, val changed: Boolean?, val error: String?,
    val fromPage: Boolean = false, val typed: Boolean = false,
)

/** 从运行事件里取精简的动作清单。不取屏幕全文;`type_text` 的文字不取(渲染成 `<输入内容>`)。 */
object ActionDigest {
    private val ELEMENT = Regex("\"ref\":\"(@e\\d+)\",\"role\":\"[^\"]*\",\"name\":(\"(?:[^\"\\\\]|\\\\.)*\")")
    private val CURRENT_APP = Regex("\"currentApp\":\"([^\"]+)\"")
    private val CURRENT_SITE = Regex("\"currentSite\":\"([^\"]+)\"")
    private val READS = setOf(Verb.Observe, Verb.ListElements)

    fun of(events: List<RunEvent>, tools: ToolIndex, maxLines: Int = 60): List<ActionLine> {
        val args = HashMap<Int, JsonObject>()
        var names = emptyMap<String, String>()
        var namesFromPage = false
        // 手机上在哪个 App、网页上在哪个站点;onWeb 是此刻在哪一边
        var app: String? = null
        var site: String? = null
        var onWeb = false
        val out = mutableListOf<ActionLine>()
        for (e in events) {
            when (e) {
                is RunEvent.ModelReply -> e.argsJson?.let(::parse)?.let { args[e.step] = it }
                is RunEvent.Observed -> if (e.kind == ObsKind.Read.wire) when (e.place?.kind) {
                    PlaceKind.Site -> { site = e.place.name; onWeb = true }
                    PlaceKind.Page -> UrlHost.of(e.place.name)?.let { site = it; onWeb = true }
                    PlaceKind.App -> { app = e.place.name; onWeb = false }
                    else -> Unit
                }
                is RunEvent.ToolResult -> {
                    // 读屏结果可能被截短成不完整的 JSON,逐个元素按文本取 ref→名字,只用来给目标命名
                    val verb = tools.verb(e.tool)
                    if (verb in READS) {
                        names = ELEMENT.findAll(e.resultJson).associate { m -> m.groupValues[1] to unquote(m.groupValues[2]) }
                        CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                        namesFromPage = onWeb
                    } else {
                        // 动作结果里带的 screen 也报在哪
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                            ?: CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                    }
                    if (verb !in ACTION_VERBS || out.size >= maxLines) continue
                    val a = args[e.step]
                    fun arg(k: String) = (a?.get(k) as? JsonPrimitive)?.contentOrNull
                    if (verb == Verb.LaunchApp || verb == Verb.ShareText) arg("package")?.let { app = it; onWeb = false }
                    if (verb == Verb.Navigate) arg("url")?.let(UrlHost::of)?.let { site = it; onWeb = true }
                    val fromRead = verb !in NO_REF_TARGET
                    val target = when (verb) {
                        // 只记分享给了哪个 App,不记文字:可能带个人信息
                        Verb.LaunchApp, Verb.ShareText -> arg("package")
                        Verb.PressKey -> arg("key") ?: arg("keys")
                        // 只记站点,不记网址:查询串里可能有个人信息
                        Verb.Navigate -> arg("url")?.let(UrlHost::of)
                        Verb.Tabs -> arg("action")
                        // 命令、路径、上传的文件可能带个人信息:不记
                        Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile, Verb.Upload -> null
                        else -> arg("ref")?.let { names[it] }?.takeIf { it.isNotBlank() }
                    }
                    val result = parse(e.resultJson)
                    val error = (result?.get("error") as? JsonPrimitive)?.contentOrNull
                    val where = if (onWeb) site?.let { "网站 $it" } else app
                    out += ActionLine(e.step, e.tool, target, where, e.changed, error, fromPage = fromRead && namesFromPage && onWeb && target != null,
                        typed = verb == Verb.TypeText)
                }
                else -> Unit
            }
        }
        return out
    }

    /** 目标不取自读屏的动作。 */
    private val NO_REF_TARGET = setOf(Verb.LaunchApp, Verb.ShareText, Verb.PressKey, Verb.Navigate, Verb.Tabs, Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile, Verb.Upload)

    fun render(lines: List<ActionLine>): String = lines.joinToString("\n") { l ->
        buildString {
            append(l.step).append(". ").append(l.tool)
            l.target?.let { append(" 「").append(it).append("」") }
            if (l.typed) append(" 输入 <输入内容>")
            l.where?.let { append(" 在 ").append(it) }
            when {
                l.error != null -> append(" 出错:").append(l.error)
                l.changed == true -> append(" 生效")
                l.changed == false -> append(" 画面没变")
            }
        }
    }

    private fun parse(s: String): JsonObject? = try {
        Json.parseToJsonElement(s) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun unquote(s: String) = try {
        (Json.parseToJsonElement(s) as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (e: Exception) {
        ""
    }
}

sealed interface DraftResult {
    /** [isUpdateOf]:与已有个人技能相近,作为它的更新起草。 */
    data class Draft(val skill: PersonalSkill, val isUpdateOf: String?) : DraftResult
    data class Dropped(val reason: String) : DraftResult
}

/**
 * 一次单独的模型调用(不带工具)把办成的任务写成技能草稿,再由代码校验;不过就丢弃,不打扰用户。
 * 发给模型的只有脱敏后的目标、最终回答、回答的来源(哪一步在哪儿读的)和动作清单,不含读到的原文和输入的文字。
 */
class SkillDrafter(private val model: ChatModel) {
    suspend fun draft(input: DraftInput, existing: PersonalSkills, catalog: GuideCatalog): DraftResult {
        val goalLiterals = SkillRedactor.literals(input.goal, listOf(input.answer))
        // 发给模型的请求:目标和回答里的值、这次输入的文字换掉,数字一律换掉;回答整段只在草稿里换
        val typed = SkillRedactor.textLiterals(typedTexts(input.events, input.tools))
        val keepDigits = catalogDigits(catalog)
        val request = SkillRedactor.scrubNumbers(SkillRedactor.redact(request(input), goalLiterals + typed), keepDigits)
        // 网页上点到的元素名字是网页内容,和输入的文字、回答一样当原文查泄漏
        val pageTargets = ActionDigest.of(input.events, input.tools).filter { it.fromPage }.mapNotNull { it.target }
        val sources = typedTexts(input.events, input.tools) + input.answer + pageTargets
        val literals = goalLiterals + typed + SkillRedactor.textLiterals(sources)
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.Mika)), emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DraftResult.Dropped("model_error")
        }
        val redacted = SkillRedactor.redact(stripFence(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }), literals)
        // 先按原样查一遍:草稿里写了密码、卡号这类东西,说明起草走偏了,数字换掉也不收
        if (SensitiveFilter.hit(redacted) != null) return DraftResult.Dropped("sensitive")
        // 替换没换干净:草稿里还留着屏幕原文或输入文字的片段(4 个字以上、目标和技能目录里都没有的)
        if (leaks(redacted, sources, input.goal, catalog)) return DraftResult.Dropped("screen_text_leak")
        val md = SkillRedactor.scrubNumbers(redacted, keepDigits)

        validate(md, input.toolNames, input.tools.nameOf(Verb.RunBundledScript), catalog.runnableScripts())?.let { return DraftResult.Dropped(it) }
        val meta = FrontMatter.parse(md).first
        val name = meta["name"] as String
        val description = meta["description"] as String

        val surfaces = surfaces(meta)
        // 已有一份相近的草稿等用户处理:不再堆一份
        if (similar(description, surfaces, existing.pending(), catalog) != null) return DraftResult.Dropped("similar_draft_pending")
        // 已收下的(在用或停用的)相近技能:作为它的更新
        val updateOf = similar(description, surfaces, existing.list(), catalog)
        // 新起草的不能顶掉内置技能、已有个人技能或草稿;更新草稿一律用被更新那份的名字(收下时覆盖它,头部名字要对得上)
        if (updateOf == null && (existing.get(name) != null || existing.pending().any { it.name == name } || catalog.items.any { it.name == name }))
            return DraftResult.Dropped("name_taken")
        val finalName = updateOf ?: name
        val finalMd = PersonalSkills.withName(md, finalName)

        val skill = PersonalSkill(
            name = finalName, markdown = finalMd, status = SkillStatus.pending, learnedFrom = input.runId, replacesDraftOf = updateOf,
            createdAt = input.events.lastOrNull()?.atMs ?: 0L, outcomes = emptyList(),
            baseHash = updateOf?.let { name -> existing.get(name)?.markdown?.let { PersonalSkills.fingerprint(it) } },
        )
        return DraftResult.Draft(skill, updateOf)
    }

    /** [candidates] 里描述相近(检索得分过阈值)且 App/网站有交集的那份的名字。 */
    private fun similar(description: String, surfaces: Set<String>, candidates: List<PersonalSkill>, catalog: GuideCatalog): String? {
        if (candidates.isEmpty()) return null
        val byName = candidates.associateBy { it.name }
        // 停用的、草稿也要比:临时当作在用的放进目录里检索
        val view = GuideCatalog.withPersonal(catalog, ListedSkills(candidates.map { it.copy(status = SkillStatus.active) }))
        return view.find(description, kind = PERSONAL_KIND, minScore = RECOMMEND_MIN_SCORE).firstOrNull { hit ->
            byName[hit.name]?.let { surfaces(FrontMatter.parse(it.markdown).first).intersect(surfaces).isNotEmpty() } == true
        }?.name
    }

    private fun leaks(draft: String, sources: List<String>, goal: String, catalog: GuideCatalog): Boolean {
        if (sources.isEmpty()) return false
        val g = SkillRedactor.norm(goal)
        val known by lazy {
            catalog.items.filter { it.kind != PERSONAL_KIND }
                .joinToString("\n") { (listOf(it.name, it.display, it.description) + it.aliases).joinToString("\n") }.let(SkillRedactor::norm)
        }
        return sources.map(SkillRedactor::norm).any { t ->
            (0..t.length - LEAK_WINDOW).any { i ->
                val w = t.substring(i, i + LEAK_WINDOW)
                w.isNotBlank() && draft.contains(w) && !g.contains(w) && !known.contains(w)
            }
        }
    }

    /** 这次 type_text 输入的文字(密码框的已在记录里换成 ***)。 */
    private fun typedTexts(events: List<RunEvent>, tools: ToolIndex): List<String> = events.filterIsInstance<RunEvent.ModelReply>()
        .filter { tools.verb(it.tool) == Verb.TypeText }
        .mapNotNull { e ->
            val o = try { Json.parseToJsonElement(e.argsJson ?: return@mapNotNull null) as? JsonObject } catch (x: Exception) { null }
            (o?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it != "***" }
        }

    /** 内置技能名字、显示名、别名里自带的数字(如铁路 App 的名字),草稿里保留。 */
    private fun catalogDigits(catalog: GuideCatalog): Set<String> =
        catalog.items.filter { it.kind != PERSONAL_KIND }.flatMap { listOf(it.name, it.display) + it.aliases }
            .flatMap { Regex("\\d{2,}").findAll(SkillRedactor.norm(it)).map { m -> m.value }.toList() }.toSet()

    private fun request(input: DraftInput): String = buildString {
        appendLine("运行编号:${input.runId}")
        appendLine("目标:${input.goal}")
        appendLine("最终回答:${input.answer}")
        if (input.sources.isNotEmpty()) {
            appendLine("回答的来源:")
            input.sources.forEach { s -> appendLine("- 第 ${s.step} 步 ${s.kind} ${s.place.orEmpty()}" + s.title?.let { "「$it」" }.orEmpty()) }
        }
        appendLine("动作清单:")
        appendLine(ActionDigest.render(ActionDigest.of(input.events, input.tools)))
        append("可用工具:").append(input.toolNames.sorted().joinToString(", "))
    }

    /** 最后一次核查通过时,各条标准(标准, 引用的屏幕原文)。 */
    private fun surfaces(meta: Map<String, Any>): Set<String> =
        listOf("apps", "domains").flatMap { k ->
            when (val v = meta[k]) {
                is List<*> -> v.filterIsInstance<String>()
                is String -> listOf(v).filter { it.isNotBlank() }
                else -> emptyList()
            }
        }.toSet()

    private fun stripFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        return t.substringAfter('\n').removeSuffix("```").trim()
    }

    companion object {
        const val MAX_CHARS = 4000

        /**
         * 技能文档的格式与安全校验(起草的草稿、用户改过再收下的全文都过这一关);通过返回 null,否则返回原因:
         * 名字合法、有描述、五个小节齐全、不超过 [MAX_CHARS] 字、反引号里的工具名都在 [toolNames] 里、敏感过滤不命中;
         * 提到跑技能脚本的工具([scriptTool])时,文中的脚本路径至少有一个、且都得是 [runnableScripts](清单里标为可运行的)里的。
         */
        fun validate(md: String, toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String> = emptySet()): String? {
            val (meta, body) = FrontMatter.parse(md)
            val name = meta["name"] as? String
            if (name == null || !PersonalSkills.NAME.matches(name)) return "invalid_name"
            if ((meta["description"] as? String).isNullOrBlank()) return "missing_description"
            val headings = body.lines().filter { it.startsWith("## ") }
            if (SECTIONS.any { s -> headings.none { it.removePrefix("## ").trim().startsWith(s) } }) return "missing_section"
            if (md.length > MAX_CHARS) return "too_long"
            if (TOOL_MENTION.findAll(md).any { it.groupValues[1] !in toolNames }) return "unknown_tool"
            if (scriptTool != null && TOOL_MENTION.findAll(md).any { it.groupValues[1] == scriptTool }) {
                val scripts = SCRIPT_PATH.findAll(md).map { it.value }.toList()
                fun runnable(path: String) = runnableScripts.any { it == path || it.endsWith("/$path") }
                if (scripts.isEmpty() || !scripts.all(::runnable)) return "unrunnable_script"
            }
            if (SensitiveFilter.hit(md) != null) return "sensitive"
            return null
        }
        private const val LEAK_WINDOW = 4
        val SECTIONS = listOf("入口", "步骤", "容易出错的地方", "怎样算办成", "什么时候停下交给用户")
        /** 反引号里形如工具名的标识符。 */
        private val TOOL_MENTION = Regex("`([a-z][a-z0-9_]*)`")
        /** 文中的技能脚本路径,如 `scripts/extract_pdf.py` 或 `skills/documents/scripts/extract_pdf.py`。 */
        private val SCRIPT_PATH = Regex("[A-Za-z0-9_./-]*scripts/[A-Za-z0-9_./-]+\\.(?:py|mjs|js|sh)(?![A-Za-z0-9_])")

        val PROMPT: String by lazy { Prompts.role("skill_draft") }
    }
}

/** 只读的一组技能,给去重检索用。 */
private class ListedSkills(private val items: List<PersonalSkill>) : PersonalSkills {
    override fun list() = items
    override fun get(name: String) = items.find { it.name == name }
    override fun put(s: PersonalSkill) = throw UnsupportedOperationException()
    override fun remove(name: String) = throw UnsupportedOperationException()
    override fun pending() = emptyList<PersonalSkill>()
    override fun update(name: String, transform: (PersonalSkill) -> PersonalSkill?) = throw UnsupportedOperationException()
    override fun drafts() = emptyMap<String, PersonalSkill>()
    override fun putPending(id: String, s: PersonalSkill) = throw UnsupportedOperationException()
    override fun approve(id: String, editedMarkdown: String?) = throw UnsupportedOperationException()
    override fun reject(id: String) = throw UnsupportedOperationException()
}
