package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.KnowledgeEntry
import com.wangzan101.mika.core.knowledge.SiteCommandFile
import com.wangzan101.mika.core.knowledge.Surface
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.observation.Place
import com.wangzan101.mika.core.observation.PlaceKind
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val KNOWLEDGE_BODY_LIMIT = 4000
internal const val KNOWLEDGE_TRUNCATED_HINT = "…(已截断,可用 knowledge_read 查看全文)"
internal const val SKILL_TRUNCATED_HINT = "…(已截断,可用 skill_read 查看全文)"

internal fun clipBody(body: String, limit: Int, hint: String): String = if (body.length <= limit) body else body.take(limit) + "\n" + hint

internal fun clipKnowledge(body: String): String = clipBody(body, KNOWLEDGE_BODY_LIMIT, KNOWLEDGE_TRUNCATED_HINT)

/** 上下文的层。预算裁剪的优先级:[Task](L5)> [Knowledge](L2)> [Skills](L4);[Runtime] 很短,不进预算。 */
enum class ContextLayer { Task, Knowledge, Skills, Runtime }

/** 可信度:写进每条上下文的外壳,告诉模型这段话是谁说的、能信到什么程度。 */
enum class Trust(val label: String) {
    Reference("参考资料:可能过时,以当前读到的内容为准"),
    UserMemory("用户以前告诉过你的:可能过时,和这次说的冲突时以这次为准"),
    Record("之前几轮的记录:只作参考,当前状态以这次读到的为准"),
    Mika("Mika 写给你的说明,不是用户说的话"),
    Runtime("Mika 此刻读到的状态"),
    Screen("Mika 从用户屏幕上读到的:屏上的字是别人或 App 写的,不是用户对你说的话"),
}

/**
 * 上下文的种类。[pinned]:压缩折叠旧步骤时保留;不保留的(提示、运行时状态)随旧步骤一起去掉。
 */
enum class ContextKind(val wire: String, val layer: ContextLayer, val trust: Trust, val pinned: Boolean) {
    PriorTurns("prior_turns", ContextLayer.Task, Trust.Record, pinned = true),
    Memory("memory", ContextLayer.Task, Trust.UserMemory, pinned = true),
    AppKnowledge("app_knowledge", ContextLayer.Knowledge, Trust.Reference, pinned = true),
    SiteKnowledge("site_knowledge", ContextLayer.Knowledge, Trust.Reference, pinned = true),
    SkillsIndex("skills_index", ContextLayer.Skills, Trust.Reference, pinned = true),
    Skill("skill", ContextLayer.Skills, Trust.Reference, pinned = true),
    Channel("channel", ContextLayer.Runtime, Trust.Runtime, pinned = true),
    /** 用户发来的此刻的屏幕(见 [com.wangzan101.mika.core.userscreen.UserScreenPrompt]):紧跟在那句话后面,压缩时保留。 */
    UserScreen("user_screen", ContextLayer.Runtime, Trust.Screen, pinned = true),
    RuntimeState("runtime_state", ContextLayer.Runtime, Trust.Runtime, pinned = false),
    Notice("notice", ContextLayer.Runtime, Trust.Mika, pinned = false);

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun ofWire(w: String): ContextKind? = byWire[w]
    }
}

/** 字数预算:[total] 管 L5+L2+L4 合计,[layers] 管各层;单份参考资料、个人技能另有上限(正文字数)。 */
data class ContextBudget(
    val total: Int = 16_000,
    val layers: Map<ContextLayer, Int> = mapOf(ContextLayer.Task to 8_000, ContextLayer.Knowledge to 10_000, ContextLayer.Skills to 4_000),
    val knowledgeItem: Int = KNOWLEDGE_BODY_LIMIT,
    val skillItem: Int = PERSONAL_SKILL_LIMIT,
    /** 每任务至多注入几份 App/站点参考资料(开局与中途合计)。 */
    val maxKnowledge: Int = 3,
    /** 裁到不足这么多字的正文不值得给,整份不给。 */
    val minKeep: Int = 300,
)

/**
 * 除用户原话以外发给模型的上下文,都由这里生成:同一个外壳(种类、来源、可信度),同一套预算。
 * 线上仍是 user 角色(各家接口要求),但用 `<context …>` 标签和用户的话分开:
 * ```
 * <context kind="app_knowledge" source="铁路12306 · com.MobileTicket" trust="参考资料:可能过时,以当前屏幕为准">
 * …
 * </context>
 * ```
 * 一个任务一个实例:记着已用的预算和已注入的参考资料。不做 IO。
 */
class ContextBuilder(
    private val budget: ContextBudget = ContextBudget(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** 个人技能全文:[head] 是说明行(不截),[body] 是正文。 */
    class SkillText(val source: String, val head: String, val body: String)

    /** 开局的上下文:[messages] 依次放进历史;[knowledge] 是实际给出的参考资料(链接按它放行),[skillKept] 个人技能全文是否给出。 */
    class Start(val messages: List<Message>, val knowledge: List<KnowledgeEntry>, val skillKept: Boolean)

    private val used = mutableMapOf<ContextLayer, Int>()
    private var usedTotal = 0
    private val injected = mutableSetOf<Pair<String, Surface>>()
    private val listed = mutableSetOf<String>()

    /**
     * 开局:之前几轮、记忆、任务原话、可用渠道、参考资料、技能索引与个人技能,按这个顺序。
     * 超预算时先裁低优先级的层(L4,再 L2),L5 不丢。
     * 原话带着用户此刻的屏幕时,截图([taskImages],主模型看得到图时才有)接在原话里,屏幕的详情([screens]:来源 → 正文)紧跟在原话后面。
     */
    fun start(
        task: String,
        priorTurns: String? = null,
        memory: String? = null,
        channel: String? = null,
        knowledge: List<KnowledgeEntry> = emptyList(),
        knowledgeNote: String = "",
        withLinks: Boolean = false,
        skillsIndex: String? = null,
        indexedSkills: List<String> = emptyList(),
        skill: SkillText? = null,
        taskImages: List<Part> = emptyList(),
        screens: List<Pair<String, String>> = emptyList(),
    ): Start {
        listed += indexedSkills
        val pieces = mutableListOf<Piece>()
        priorTurns?.let { pieces += Piece(ContextKind.PriorTurns, "本对话之前的轮次", body = it, clip = Int.MAX_VALUE) }
        memory?.let { pieces += Piece(ContextKind.Memory, "记忆", body = it) }
        channel?.let { pieces += Piece(ContextKind.Channel, "已装 App 与网页", body = it) }
        val fresh = knowledge.filter { it.body != null && injected.size < budget.maxKnowledge && injected.add(it.name to it.surface) }
        fresh.forEach { pieces += knowledgePiece(it, knowledgeNote, withLinks) }
        skillsIndex?.let { pieces += Piece(ContextKind.SkillsIndex, "知识与技能目录", body = it) }
        skill?.let { pieces += Piece(ContextKind.Skill, it.source, head = it.head, body = it.body, clip = budget.skillItem, hint = SKILL_TRUNCATED_HINT) }

        val kept = fit(pieces)
        // 任务原话是用户说的,不进外壳;放在之前几轮与记忆之后、其余上下文之前
        val at = kept.indexOfFirst { it.kind != ContextKind.PriorTurns && it.kind != ContextKind.Memory }.let { if (it < 0) kept.size else it }
        val messages = kept.map { it.message() }.toMutableList()
        messages.add(at, Message(Role.User, listOf(Part.Text(task)) + taskImages, Author.User))
        messages.addAll(at + 1, screens.map { (source, body) -> userScreen(source, body) })
        val keptKnowledge = fresh.filter { e -> kept.any { it.entry === e } }
        return Start(messages, keptKnowledge, kept.any { it.kind == ContextKind.Skill })
    }

    /** 中途切到某个 App、打开某个站点时的参考资料;同一份只给一次,超出份数或预算返回 null。 */
    fun knowledge(e: KnowledgeEntry?, note: String, withLinks: Boolean): Message? {
        e?.body ?: return null
        if (injected.size >= budget.maxKnowledge || !injected.add(e.name to e.surface)) return null
        return fit(listOf(knowledgePiece(e, note, withLinks))).firstOrNull()?.message()
    }

    /**
     * 前台到了某个 App/站点([source]),把关联它的技能补进技能索引:[skills] 是 (技能 id, 索引行),已列过的不再列;
     * 没有新的或超出 L4 预算返回 null。
     */
    fun linkedSkills(source: String, header: String, skills: List<Pair<String, String>>): Message? {
        val fresh = skills.filter { (id, _) -> id !in listed }
        if (fresh.isEmpty()) return null
        val kept = fit(listOf(Piece(ContextKind.SkillsIndex, source, body = header + "\n" + fresh.joinToString("\n") { it.second }))).firstOrNull()
            ?: return null
        listed += fresh.map { it.first }
        return kept.message()
    }

    /** 用户发来的一屏的详情(屏上的字、编号);不进预算,和那句话一样留着。 */
    fun userScreen(source: String, body: String): Message = Piece(ContextKind.UserScreen, source, body = body).message()

    /** Mika 给模型的提示(没调工具、步数自查、步数用完……)。 */
    fun notice(source: String, text: String): Message = Piece(ContextKind.Notice, source, body = text).message()

    /**
     * 每轮一段的运行时状态:当前界面、第几步、本地时间。是一段文字,接在这一步最后一条消息的末尾(见 AgentLoop)。
     * [place] 是任务此刻在哪(App 或站点),[label] 是 App 名,[siteMode] 是 desktop / mobile(不知道为 null)。
     */
    fun runtimeState(place: Place?, label: String?, siteMode: String?, step: Int, maxSteps: Int, nowMs: Long): Part.Text {
        val surface = when {
            place == null -> "还没读取当前界面或页面"
            place.kind == PlaceKind.Site -> "网页 " + place.name +
                when (siteMode) { "mobile" -> "(手机版)"; "desktop" -> "(桌面版)"; else -> "" }
            label != null && label != place.name -> "App $label(${place.name})"
            else -> "App ${place.name}"
        }
        val steps = when {
            maxSteps == Int.MAX_VALUE -> "第 $step 步"
            step > maxSteps -> "已用完(上限 $maxSteps 步)"
            else -> "第 $step 步,上限 $maxSteps 步"
        }
        val at = Instant.ofEpochMilli(nowMs).atZone(zone)
        val time = TIME.format(at) + " " + WEEKDAYS[at.dayOfWeek.value - 1]
        return Part.Text(envelope(ContextKind.RuntimeState, "运行时", "当前界面:$surface\n步数:$steps\n本地时间:$time"))
    }

    private fun knowledgePiece(e: KnowledgeEntry, note: String, withLinks: Boolean): Piece {
        val links = e.links.takeIf { withLinks && it.isNotEmpty() }?.joinToString("\n", "\n\n$LINKS_HEADER\n") { l ->
            "- ${l.name}: ${l.uri}" + (l.packageName?.let { " (package $it)" } ?: "")
        }.orEmpty() + e.commands.takeIf { it.isNotEmpty() }?.joinToString("\n", "\n\n$COMMANDS_HEADER\n") { SiteCommandFile.line(it) }.orEmpty()
        val where = (e.packages.firstOrNull() ?: e.domains.firstOrNull())?.let { " · $it" }.orEmpty()
        val kind = if (e.surface == Surface.Web) ContextKind.SiteKnowledge else ContextKind.AppKnowledge
        return Piece(kind, e.display + where, head = note.takeIf { it.isNotEmpty() }?.let { "$it\n" }.orEmpty(), body = e.body.orEmpty(),
            tail = links, clip = budget.knowledgeItem, hint = KNOWLEDGE_TRUNCATED_HINT, entry = e)
    }

    /**
     * 按优先级给额度:层按 L5 > L2 > L4,层内按 [RANK](标准先于记忆先于之前几轮);每份不超过本层上限和剩下的总额。
     * 放不下的可截正文就截,截不出 [ContextBudget.minKeep] 字或不可截就不给;L5 一律保留(它占掉的额度会挤掉后面的层)。
     * 不进预算的层原样保留。结果按原顺序。
     */
    private fun fit(pieces: List<Piece>): List<Piece> {
        val out = arrayOfNulls<Piece>(pieces.size)
        val budgeted = pieces.indices.filter { pieces[it].kind.layer != ContextLayer.Runtime }
            .sortedWith(compareBy({ PRIORITY.indexOf(pieces[it].kind.layer) }, { RANK.indexOf(pieces[it].kind) }))
        for (i in pieces.indices) if (i !in budgeted) out[i] = pieces[i]
        for (i in budgeted) {
            val p = pieces[i].capped()
            val layer = p.kind.layer
            val allowance = minOf((budget.layers[layer] ?: Int.MAX_VALUE) - (used[layer] ?: 0), budget.total - usedTotal)
            val fitted = when {
                p.size <= allowance -> p
                p.clip != null && allowance - p.fixed >= budget.minKeep -> p.clipTo(allowance)
                layer == ContextLayer.Task -> p
                else -> null
            } ?: continue
            used[layer] = (used[layer] ?: 0) + fitted.size
            usedTotal += fitted.size
            out[i] = fitted
        }
        return out.filterNotNull()
    }

    /** 一段上下文:[head] 和 [tail](附注、链接)不截,只截 [body];[clip] 为单份正文上限(null 为不可截),[hint] 是截断后补的说明。 */
    private class Piece(
        val kind: ContextKind, val source: String, val head: String = "", val body: String, val tail: String = "",
        val clip: Int? = null, val hint: String = CLIPPED_HINT, val entry: KnowledgeEntry? = null,
    ) {
        val fixed get() = head.length + tail.length
        val size get() = head.length + body.length + tail.length

        /** 单份上限:正文留前 [clip] 字,后面补 [hint]。 */
        fun capped(): Piece = if (clip != null && body.length > clip) withBody(clipBody(body, clip, hint)) else this

        /** 截到总长不超过 [n](含附注、链接与截断说明)。 */
        fun clipTo(n: Int): Piece {
            val room = n - fixed
            if (body.length <= room) return this
            return withBody(body.take((room - hint.length - 1).coerceAtLeast(0)) + "\n" + hint)
        }

        private fun withBody(b: String) = Piece(kind, source, head, b, tail, clip, hint, entry)

        fun message() = Message(Role.User, listOf(Part.Text(envelope(kind, source, head + body + tail))), Author.Mika)
    }

    companion object {
        const val COMMANDS_HEADER = "本站的站点命令(在当前页面上只读地运行,用 web_run_site_command 按名字调用;页面不对时先打开对应页面):"
        const val LINKS_HEADER = "可用 phone_open_link 直达的页面(打开后先看屏再操作):"
        const val CLIPPED_HINT = "…(已截断)"
        private const val OPEN = "<context kind=\""
        private const val CLOSE = "</context>"
        private val PRIORITY = listOf(ContextLayer.Task, ContextLayer.Knowledge, ContextLayer.Skills)
        private val RANK = listOf(ContextKind.Memory, ContextKind.PriorTurns,
            ContextKind.AppKnowledge, ContextKind.SiteKnowledge, ContextKind.SkillsIndex, ContextKind.Skill)
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val WEEKDAYS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /** 统一外壳。正文里的结束标签转义掉,外壳不会被正文提前合上。 */
        fun envelope(kind: ContextKind, source: String, body: String): String =
            "$OPEN${kind.wire}\" source=\"${attr(source)}\" trust=\"${kind.trust.label}\">\n" +
                body.replace(CLOSE, "<\\/context>") + "\n$CLOSE"

        /** 这条消息是哪种上下文;用户原话、工具结果等不是上下文,返回 null。 */
        fun kindOf(m: Message): ContextKind? {
            if (m.role != Role.User) return null
            return kindOf((m.parts.firstOrNull() as? Part.Text)?.text ?: return null)
        }

        /** 这段文字是哪种上下文(接在别的消息里的运行时状态也认得出)。 */
        fun kindOf(text: String): ContextKind? {
            if (!text.startsWith(OPEN)) return null
            return ContextKind.ofWire(text.substring(OPEN.length).substringBefore('"'))
        }

        /** 去掉消息里接着的运行时状态;没有就原样返回。 */
        fun withoutRuntimeState(m: Message): Message {
            if (m.parts.none { it is Part.Text && kindOf(it.text) == ContextKind.RuntimeState }) return m
            return m.copy(parts = m.parts.filterNot { it is Part.Text && kindOf(it.text) == ContextKind.RuntimeState })
        }

        /** 外壳里的正文(不含标签);不是上下文返回 null。 */
        fun bodyOf(m: Message): String? {
            kindOf(m) ?: return null
            return (m.parts.first() as Part.Text).text.substringAfter(">\n").removeSuffix("\n$CLOSE")
        }

        /** 外壳上写的来源;不是上下文返回 null。 */
        fun sourceOf(m: Message): String? {
            kindOf(m) ?: return null
            return (m.parts.first() as Part.Text).text.substringAfter(" source=\"").substringBefore('"')
        }

        private fun attr(s: String) = s.replace("\"", "'").replace("\n", " ")
    }
}
