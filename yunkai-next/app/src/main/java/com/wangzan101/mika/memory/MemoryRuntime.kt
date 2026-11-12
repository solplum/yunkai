package com.wangzan101.mika.memory

import android.content.Context
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.memory.MemoryItem
import com.wangzan101.mika.core.memory.MemoryNote
import com.wangzan101.mika.core.memory.MemoryStore
import com.wangzan101.mika.core.memory.ProfileStore
import com.wangzan101.mika.core.skills.SkillNote
import com.wangzan101.mika.settings.KeystoreKeyCipher
import com.wangzan101.mika.settings.MemorySettings
import com.wangzan101.mika.skills.PersonalSkillFiles
import com.wangzan101.mika.knowledge.AppKnowledgeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** 一次运行结束后结果卡片下的提示;按运行编号挂到那一问一答上。 */
data class RunNotes(
    val conversationId: String,
    val runId: String,
    val memoryNotes: List<MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<SkillNote> = emptyList(),
)

/** 打开「记忆与技能」的请求(结果卡片上点「看看」):[draftId] 是要看的技能草稿。 */
data class MemoryOpen(val draftId: String? = null)

/**
 * 记忆存储、个人技能、开关与结果卡片提示。由 App 建一份、经 [Owner] 给出:并行的几个任务共用同一个 [MemoryStore],
 * 写入由它自己的锁串行。第一次取用会读盘解密,别在主线程上调。
 */
class MemoryRuntime(context: Context) {
    private val app = context.applicationContext

    private var file: EncryptedMemoryFile? = null
    private var store: MemoryStore? = null
    private var skills: PersonalSkillFiles? = null
    private var settings: MemorySettings? = null
    private var profile: ProfileStore? = null
    private var appKnowledge: AppKnowledgeFiles? = null

    /** 任务收尾后的学习在这里跑:不随服务退出而取消。 */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _notes = MutableStateFlow<Map<String, RunNotes>>(emptyMap())
    /** 运行编号 → 提示;界面据此在结果卡片下显示。 */
    val notes: StateFlow<Map<String, RunNotes>> = _notes.asStateFlow()

    private val _open = MutableStateFlow<MemoryOpen?>(null)
    val openRequest: StateFlow<MemoryOpen?> = _open.asStateFlow()

    fun requestOpen(o: MemoryOpen?) { _open.value = o }

    fun publish(n: RunNotes) = _notes.update { it + (n.runId to n) }

    /**
     * 记忆条目,给结果卡片现取提示文字。第一次取用时在后台读盘解密,读出之前是空的;
     * 之后跟着存储变。
     */
    fun items(): StateFlow<List<MemoryItem>> {
        val out = MutableStateFlow<List<MemoryItem>>(emptyList())
        scope.launch(Dispatchers.IO) { runCatching { store() }.getOrNull()?.items?.collect { out.value = it } }
        return out.asStateFlow()
    }

    @Synchronized fun file(): EncryptedMemoryFile =
        file ?: EncryptedMemoryFile(app.filesDir, KeystoreKeyCipher(KEY_ALIAS), System::currentTimeMillis).also { file = it }

    @Synchronized fun store(): MemoryStore =
        store ?: MemoryStore(file(), System::currentTimeMillis, { UUID.randomUUID().toString() }).also { store = it }

    @Synchronized fun skills(): PersonalSkillFiles =
        skills ?: PersonalSkillFiles(app.filesDir).also { skills = it }

    @Synchronized fun profile(): ProfileStore =
        profile ?: ProfileStore(FileProfileBackend(File(app.filesDir, "memory/profile.bin"), KeystoreKeyCipher(KEY_ALIAS)), ids = { UUID.randomUUID().toString() }).also { profile = it }

    @Synchronized fun appKnowledge(): AppKnowledgeFiles =
        appKnowledge ?: AppKnowledgeFiles(app.filesDir).also { appKnowledge = it }

    @Synchronized fun settings(): MemorySettings =
        settings ?: MemorySettings(File(app.filesDir, "memory_settings.json")).also { settings = it }

    /** 「全部清除」:记忆、另存的坏文件、个人技能与草稿都删掉,并重建记忆密钥;对话里的记忆提示也去掉。 */
    @Synchronized fun wipeAll() {
        store().clearAll()
        file().wipe()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
        File(app.filesDir, "skills").deleteRecursively()
        File(app.filesDir, "knowledge/app-drafts").deleteRecursively()
        stripMemory(null)
        _notes.update { m -> m.mapValues { (_, n) -> n.copy(skillNotes = emptyList()) } }
    }

    /** 删掉了这些记忆([ids] 为 null = 全部):结果卡片和对话文件里不再提它们。 */
    fun stripMemory(ids: Set<String>?) {
        fun keep(id: String) = ids != null && id !in ids
        _notes.update { m -> m.mapValues { (_, n) -> n.copy(memoryNotes = n.memoryNotes.filter { keep(it.memoryId) }, usedMemory = n.usedMemory.filter(::keep)) } }
        runCatching { ConversationStore.shared(app).stripMemory(ids) }
    }

    /** 给出进程里那一份的 Application。 */
    interface Owner {
        val memory: MemoryRuntime
    }

    companion object {
        const val KEY_ALIAS = "mika-memory"

        fun of(context: Context): MemoryRuntime = (context.applicationContext as? Owner)?.memory
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement MemoryRuntime.Owner to give the memory runtime")
    }
}
