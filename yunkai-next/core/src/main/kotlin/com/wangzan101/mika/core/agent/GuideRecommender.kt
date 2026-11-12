package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.knowledge.KNOWLEDGE_KINDS
import com.wangzan101.mika.core.knowledge.PERSONAL_KIND
import com.wangzan101.mika.core.knowledge.RECOMMEND_MIN_SCORE
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.GuideInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.wangzan101.mika.core.tool.Verb

internal const val SKILL_INDEX_HEADER = "可参考的技能(需要时用 skill_read 看全文):"
internal const val KNOWLEDGE_INDEX_HEADER = "可参考的 App/网站知识(需要时用 knowledge_read 看全文):"
internal const val PERSONAL_SKILL_HEADER = "用户的个人技能(为这个用户的这类事写的,以当前屏幕为准):"
internal const val PERSONAL_SKILL_LIMIT = 3000
private const val RECOMMEND_COUNT = 3

/** 任务开始时推荐知识与技能:前几份的索引(知识、技能各列在自己的标题下),命中的在用个人技能另给一份全文(截断与预算由 [ContextBuilder] 管)。 */
internal object GuideRecommender {
    /** [ids] 是索引里列出的技能(`kind:name`),之后按前台补索引时不再重复列。 */
    class Recommendation(val index: String?, val personal: ContextBuilder.SkillText?, val personalName: String?, val ids: List<String> = emptyList())

    fun id(info: GuideInfo) = "${info.kind}:${info.name}"
    fun line(info: GuideInfo) = "- ${id(info)}(${info.display}):${info.description}"

    fun recommend(catalog: GuideCatalog, task: String): Recommendation {
        val hits = catalog.find(task, limit = RECOMMEND_COUNT, minScore = RECOMMEND_MIN_SCORE)
        if (hits.isEmpty()) return Recommendation(null, null, null)
        val (knowledge, skills) = hits.partition { it.kind in KNOWLEDGE_KINDS }
        val index = listOf(KNOWLEDGE_INDEX_HEADER to knowledge, SKILL_INDEX_HEADER to skills).filter { it.second.isNotEmpty() }
            .joinToString("\n") { (header, items) -> header + "\n" + items.joinToString("\n", transform = ::line) }
        val ids = hits.map(::id)
        // 每任务至多一份全文;坏掉的个人技能读不出正文,跳过
        val personal = hits.asSequence().filter { it.kind == PERSONAL_KIND }
            .mapNotNull { info -> catalog.fullText(info)?.let { info to it } }.firstOrNull()
            ?: return Recommendation(index, null, null, ids)
        val (info, body) = personal
        val text = ContextBuilder.SkillText("${info.kind}:${info.name}", "$PERSONAL_SKILL_HEADER${info.display}(${info.kind}:${info.name})\n", body)
        return Recommendation(index, text, info.name, ids)
    }

    /** skill_read 成功读了个人技能时给出技能名。 */
    fun viewedPersonal(verb: Verb?, content: JsonObject): String? {
        if (verb != Verb.SkillRead || content["ok"] != JsonPrimitive(true)) return null
        if ((content["kind"] as? JsonPrimitive)?.contentOrNull != PERSONAL_KIND) return null
        return (content["name"] as? JsonPrimitive)?.contentOrNull
    }
}
