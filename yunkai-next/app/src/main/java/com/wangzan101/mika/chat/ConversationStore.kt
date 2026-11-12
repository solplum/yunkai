package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.core.agent.Turn
import com.wangzan101.mika.core.memory.MemoryNote
import com.wangzan101.mika.core.memory.NoteKind
import com.wangzan101.mika.core.skills.SkillNote
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/** 每个对话一个小 JSON 文件,放在 App 私有目录;重启后仍在。 */
class ConversationStore(private val dir: File) {
    constructor(context: Context) : this(File(context.filesDir, "conversations"))

    fun newConversation(): String = UUID.randomUUID().toString()

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * 写过的对话编号:存进一轮、改了某一轮、去掉记忆提示时各发一次,不管是谁写的(对话页、悬浮胶囊、定时任务)。
     * 对话页和侧栏据此重读,屏上的就是存下的那份。
     */
    val changes: SharedFlow<String> = _changes.asSharedFlow()

    /**
     * 对话列表,最近改过的在前;[ConversationSummary.title] 是标题(用户改的或起好的),没有时是第一句话;
     * [ConversationSummary.pinnedAt] 是置顶的时间。没有内容的对话不列。
     */
    @Synchronized
    fun list(): List<ConversationSummary> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                val id = f.name.removeSuffix(".json")
                val first = turns(id).firstOrNull() ?: return@mapNotNull null
                ConversationSummary(id, title(id) ?: first.userText, f.lastModified(), pinnedAt(id))
            }

    /** 对话起好的标题(见 [ConversationTitler]);没有、读不到为 null。标题放在对话文件旁边的 `.title` 文件里,不动对话文件的格式。 */
    @Synchronized
    fun title(conversationId: String): String? =
        runCatching { titleFile(conversationId).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()?.takeIf(String::isNotEmpty)

    /**
     * 删掉过的对话。删之后才到的「补写」(后台起好的标题、任务收尾后补的结局和交代)不再把它写回来;
     * 再往里存进一轮([append])就是重新开始用这个编号,从这里拿掉。只记在进程里。
     */
    private val deleted = mutableSetOf<String>()

    /**
     * 删掉一个对话存下的一切:对话文件、标题、置顶,以及写到一半的临时文件和挪到一边的损坏文件。删了什么都发一次 [changes]。
     * 对话的工作区、定时任务、在跑的任务不归这里管,见 [ConversationDeleter]。
     */
    @Synchronized
    fun delete(conversationId: String) {
        deleted += safe(conversationId)
        val main = file(conversationId).name
        dir.listFiles { f -> f.isFile && (f.name == main || f.name.startsWith("$main.") || f.name == titleFile(conversationId).name || f.name == pinFile(conversationId).name) }
            .orEmpty().forEach { it.delete() }
        _changes.tryEmit(conversationId)
    }

    /**
     * 给对话记下起好的标题;已经有标题(起好的或用户改的)就不动(标题只起一次)。写进去了返回 true。
     * 所以后台起标题晚到时,用户先改的名字不会被盖掉。
     */
    @Synchronized
    fun setTitle(conversationId: String, title: String): Boolean {
        if (title(conversationId) != null) return false
        return writeTitle(conversationId, title)
    }

    /** 用户给对话改名:不管有没有标题都换成 [title];空的不改。写进去了返回 true。 */
    @Synchronized
    fun rename(conversationId: String, title: String): Boolean = writeTitle(conversationId, title)

    private fun writeTitle(conversationId: String, title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || safe(conversationId) in deleted) return false
        dir.mkdirs()
        val ok = runCatching { titleFile(conversationId).writeText(t) }.isSuccess
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** 对话置顶的时间;没置顶、读不到为 null。放在对话文件旁边的 `.pin` 文件里。 */
    @Synchronized
    fun pinnedAt(conversationId: String): Long? =
        runCatching { pinFile(conversationId).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() }.getOrNull()

    /** 置顶([pinned] 为真,记下时间 [at])或取消置顶。删掉的对话不动。改了返回 true。 */
    @Synchronized
    fun setPinned(conversationId: String, pinned: Boolean, at: Long = System.currentTimeMillis()): Boolean {
        if (safe(conversationId) in deleted) return false
        val f = pinFile(conversationId)
        val ok = if (pinned) {
            dir.mkdirs()
            runCatching { f.writeText(at.toString()) }.isSuccess
        } else !f.exists() || f.delete()
        if (ok) _changes.tryEmit(conversationId)
        return ok
    }

    /** 文件损坏时当作空对话,不让它挡住新任务。 */
    @Synchronized
    fun turns(conversationId: String): List<Turn> = records(conversationId).map { it.first }

    /** 每一轮连同这一轮导出的文件(文件卡片)。 */
    @Synchronized
    fun records(conversationId: String): List<Pair<Turn, List<SharedFile>>> {
        val f = file(conversationId)
        if (!f.isFile) return emptyList()
        return read(f).orEmpty()
    }

    /** 读不懂(不是 JSON 数组)时返回 null。 */
    private fun read(f: File): List<Pair<Turn, List<SharedFile>>>? = try {
        (Json.parseToJsonElement(f.readText()) as? JsonArray)?.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val turn = Turn(
                field("userText") ?: return@mapNotNull null, field("outcome").orEmpty(), field("status") ?: return@mapNotNull null, field("note"), field("runId"),
                memoryNotes = objects(o, "memoryNotes").mapNotNull { n ->
                    val kind = NoteKind.entries.find { it.name == str(n, "kind") } ?: return@mapNotNull null
                    MemoryNote(kind, str(n, "memoryId") ?: return@mapNotNull null, str(n, "undoToken"), str(n, "pendingId"))
                },
                usedMemory = (o["usedMemory"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                skillNotes = objects(o, "skillNotes").map { n -> SkillNote(str(n, "text").orEmpty(), str(n, "draftId"), str(n, "suspended")) },
                unconfirmed = (o["unconfirmed"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                result = field("result"),
                steers = (o["steers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                groups = (o["groups"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
            )
            turn to (o["files"] as? JsonArray).orEmpty().mapNotNull(::fileOf)
        }
    } catch (e: SerializationException) {
        null
    }

    private fun fileOf(e: kotlinx.serialization.json.JsonElement): SharedFile? {
        val o = e as? JsonObject ?: return null
        fun field(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return SharedFile(field("name") ?: return null, field("size")?.toLongOrNull() ?: 0, field("savedAs") ?: return null, field("uri") ?: return null)
    }

    @Synchronized
    fun append(conversationId: String, turn: Turn, files: List<SharedFile> = emptyList()) {
        deleted -= safe(conversationId)
        val f = file(conversationId)
        val prior = if (f.isFile) read(f) else emptyList()
        if (prior == null) {
            // 损坏的文件挪到一边留着,不拿新内容覆盖掉
            f.renameTo(File(dir, "${f.name}.corrupt-${System.currentTimeMillis()}"))
        }
        write(conversationId, prior.orEmpty() + (turn to files))
    }

    /** 改运行编号为 [runId] 的那一轮(任务结束后补上判的结局、记忆与技能提示);找不到就不动。 */
    @Synchronized
    fun updateTurn(conversationId: String, runId: String, transform: (Turn) -> Turn) {
        val f = file(conversationId)
        if (!f.isFile) return
        val records = read(f) ?: return
        if (records.none { it.first.runId == runId }) return
        write(conversationId, records.map { (t, fs) -> (if (t.runId == runId) transform(t) else t) to fs })
    }

    /** 记忆删了或全部清除时:去掉指向这些条目的提示和「用到」记录;[ids] 为 null 时去掉全部。 */
    @Synchronized
    fun stripMemory(ids: Set<String>?) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { f ->
            val records = read(f) ?: return@forEach
            fun keep(id: String) = ids != null && id !in ids
            val stripped = records.map { (t, fs) -> t.copy(memoryNotes = t.memoryNotes.filter { keep(it.memoryId) }, usedMemory = t.usedMemory.filter(::keep)) to fs }
            if (stripped != records) write(f.name.removeSuffix(".json"), stripped)
        }
    }

    private fun write(conversationId: String, all: List<Pair<Turn, List<SharedFile>>>) {
        dir.mkdirs()
        val json = JsonArray(all.map { (it, fs) ->
            buildJsonObject {
                put("userText", it.userText); put("outcome", it.outcome); put("status", it.status)
                it.note?.let { n -> put("note", n) }; it.runId?.let { r -> put("runId", r) }; it.result?.let { r -> put("result", r) }
                // 只存种类和编号:记忆内容只在加密的记忆文件里,界面现取
                if (it.memoryNotes.isNotEmpty()) put("memoryNotes", JsonArray(it.memoryNotes.map { n ->
                    buildJsonObject {
                        put("kind", n.kind.name); put("memoryId", n.memoryId)
                        n.undoToken?.let { t -> put("undoToken", t) }; n.pendingId?.let { p -> put("pendingId", p) }
                    }
                }))
                if (it.usedMemory.isNotEmpty()) put("usedMemory", JsonArray(it.usedMemory.map(::JsonPrimitive)))
                if (it.unconfirmed.isNotEmpty()) put("unconfirmed", JsonArray(it.unconfirmed.map(::JsonPrimitive)))
                if (it.steers.isNotEmpty()) put("steers", JsonArray(it.steers.map(::JsonPrimitive)))
                if (it.groups.isNotEmpty()) put("groups", JsonArray(it.groups.map(::JsonPrimitive)))
                if (it.skillNotes.isNotEmpty()) put("skillNotes", JsonArray(it.skillNotes.map { n ->
                    buildJsonObject { put("text", n.text); n.draftId?.let { d -> put("draftId", d) }; n.suspended?.let { x -> put("suspended", x) } }
                }))
                if (fs.isNotEmpty()) put("files", JsonArray(fs.map { f ->
                    buildJsonObject { put("name", f.name); put("size", f.size); put("savedAs", f.savedAs); put("uri", f.uri) }
                }))
            }
        })
        // 先写临时文件再改名,写到一半被杀不会留下半个文件
        val tmp = File(dir, "${file(conversationId).name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file(conversationId))) {
            file(conversationId).writeText(json.toString())
            tmp.delete()
        }
        _changes.tryEmit(conversationId)
    }

    private fun objects(o: JsonObject, k: String) = (o[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull

    companion object {
        /** App 进程里共用的那一份(读写都在它的锁里串行)。 */
        fun shared(context: Context): ConversationStore = (context.applicationContext as com.wangzan101.mika.MikaApp).conversations
    }

    // id 来自 newConversation 或外部传入,只留安全字符,免得拼出目录外的路径
    private fun file(id: String) = File(dir, "${safe(id)}.json")
    private fun titleFile(id: String) = File(dir, "${safe(id)}.title")
    private fun pinFile(id: String) = File(dir, "${safe(id)}.pin")
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long, val pinnedAt: Long? = null)
