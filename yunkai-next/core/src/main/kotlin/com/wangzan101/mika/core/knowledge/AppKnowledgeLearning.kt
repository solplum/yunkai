package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.memory.SensitiveFilter
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.skills.ActionDigest
import com.wangzan101.mika.core.skills.SkillRedactor
import com.wangzan101.mika.core.tool.ToolIndex
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
enum class AppKnowledgeStage { Draft, Trial, Verified, Archived }

@Serializable
data class AppKnowledgeDraft(val id: String, val appName: String, val packageName: String,
    val markdown: String, val sourceRun: String, val stage: AppKnowledgeStage, val createdAt: Long,
    val trialOutcomes: List<Boolean> = emptyList())

interface AppKnowledgeDraftStore {
    fun all(): List<AppKnowledgeDraft>
    fun putPending(draft: AppKnowledgeDraft): Boolean
    fun approve(id: String): Boolean
    fun reject(id: String): Boolean
    fun recordTrial(appName: String, verified: Boolean): AppKnowledgeDraft?
    fun notes(): Map<String, String>
}

/** 只取已核实运行的精简动作清单；原始界面与输入内容不进模型请求。 */
class AppKnowledgeDrafter(private val model: ChatModel) {
    suspend fun draft(runId: String, goal: String, answer: String, events: List<RunEvent>,
        tools: ToolIndex, app: KnowledgeEntry, id: String, atMs: Long): AppKnowledgeDraft? {
        if (app.surface != Surface.Android || app.packages.isEmpty()) return null
        val actions = ActionDigest.of(events, tools).filter { it.where == app.packages.first() && it.error == null }
        if (actions.size < 5) return null
        val keepDigits = Regex("\\d{2,}").findAll(app.packages.first()).map { it.value }.toSet()
        val literals = SkillRedactor.literals(goal, listOf(answer)) + SkillRedactor.textLiterals(listOf(answer))
        val request = SkillRedactor.scrubNumbers(SkillRedactor.redact(buildString {
            appendLine("App:" + app.display + " (" + app.packages.first() + ")")
            appendLine("目标:" + goal)
            appendLine("结果:" + answer)
            appendLine("实际动作:")
            append(ActionDigest.render(actions))
        }, literals), keepDigits)
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.Mika)), emptyList())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        val raw = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
        val markdown = SkillRedactor.scrubNumbers(SkillRedactor.redact(raw, literals), keepDigits)
        if (SensitiveFilter.hit(markdown) != null || markdown.length > 5000 || markdown.length < 80) return null
        val (meta, body) = FrontMatter.parse(markdown)
        if (meta["name"] != app.name || app.packages.first() !in (meta["packages"] as? List<*>).orEmpty()) return null
        if (SECTIONS.any { "## $it" !in body }) return null
        if (Regex("`([a-z][a-z0-9_]*)`").findAll(markdown).any { it.groupValues[1] !in tools.names }) return null
        return AppKnowledgeDraft(id, app.name, app.packages.first(), markdown, runId, AppKnowledgeStage.Draft, atMs)
    }

    companion object {
        /** 正文必须有的小节(`## 已知现象` 可选)。 */
        val SECTIONS = listOf("是什么", "界面", "功能")
        const val PROMPT = "根据已核实的一次 App 操作起草可复用的 APP.md。只写动作清单实际支持的入口、界面和功能，写成事实说明，不写行为规定，不能编造页面或结果。" +
            "以 YAML 头部开始，name 使用输入的 App 名称，packages 只列输入的包名；正文必须有 ## 是什么、## 界面、## 功能，容易混的入口或常见弹窗可写在 ## 已知现象。" +
            "不要写用户的个人信息、具体输入值、订单号或任何凭据。只输出 Markdown。"
    }
}
