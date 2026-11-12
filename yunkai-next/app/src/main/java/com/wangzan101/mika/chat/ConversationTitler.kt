package com.wangzan101.mika.chat

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 对话的标题:第一句话发出时请模型起一次,之后不再改。模型只拿到第一句话的文字(不带屏幕、历史和别的内容);
 * 起不出来(没模型、出错、超时、回得不像标题)就什么都不记,界面照旧用第一句话。
 */
object ConversationTitler {
    const val TIMEOUT_MS = 8_000L
    private const val MAX_TITLE = 16
    private const val MAX_INPUT = 400

    /**
     * 对话 [id] 还没有标题、也还没存过任何一轮时,按 [first] 起一个记下。[models] 依次试(轻量模型在前,主模型兜底)。
     * 只发了屏幕、没有文字时不起。从不抛(取消照常往外抛)。
     */
    suspend fun ensure(store: ConversationStore, id: String, first: String, models: () -> List<ChatModel>) {
        if (store.title(id) != null || store.turns(id).isNotEmpty()) return
        val title = generate(models(), first) ?: return
        store.setTitle(id, title)
    }

    suspend fun generate(models: List<ChatModel>, first: String, timeoutMs: Long = TIMEOUT_MS): String? {
        val words = UserScreenMessage.parse(first).words.take(MAX_INPUT)
        if (words.isBlank()) return null
        val input = "<message>\n" + words.replace(TAG, "") + "\n</message>"
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (m in models.distinct()) {
            val title = try {
                withTimeoutOrNull(timeoutMs) {
                    val reply = m.complete(Prompts.role("conversation_title"), messages, emptyList())
                    clean(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (title != null) return title
        }
        return null
    }

    private val TAG = Regex("</?\\s*message\\s*>", RegexOption.IGNORE_CASE)
    private val WRAP = "\"'“”‘’「」『』《》`*#".toSet()
    private val TAIL = "。.!！?？,，;；:：、 ".toSet()

    /** 回复里第一行有字的当标题:去掉两头的引号和结尾标点、「标题:」前缀,过长截断;空的为 null。 */
    fun clean(raw: String): String? {
        var t = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        t = t.replace(Regex("^(标题|title)\\s*[:：]\\s*", RegexOption.IGNORE_CASE), "")
        t = t.trim { it in WRAP || it.isWhitespace() }.trimEnd { it in TAIL }.trim { it in WRAP || it.isWhitespace() }
        return t.take(MAX_TITLE).takeIf(String::isNotBlank)
    }
}
