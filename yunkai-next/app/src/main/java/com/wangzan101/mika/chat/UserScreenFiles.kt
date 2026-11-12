package com.wangzan101.mika.chat

import android.content.Context
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.core.userscreen.CapturedScreen
import com.wangzan101.mika.core.userscreen.UserScreen
import com.wangzan101.mika.core.userscreen.UserScreenJson
import com.wangzan101.mika.core.userscreen.UserScreenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 用户发来的屏幕跟着对话存:放在那个对话的工作区里(`/workspace/screens/<编号>.jpg` 和 `.json`),和附件同一处,
 * 对话的工作区删掉时一起没了;沙箱里的命令也读得到这张图。读写都经工作区的文件闸门(沙箱里的命令换成链接也读写不到别处),
 * 写时拿着树锁的许可(和重置互斥)。
 */
class UserScreenFiles(private val dirs: SandboxDirs) {
    constructor(context: Context) : this(SandboxDirs(context))

    /** 存进对话 [conversationId],返回带编号的说明。 */
    suspend fun save(conversationId: String, shot: CapturedScreen): UserScreen = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val id = "s" + UUID.randomUUID().toString().replace("-", "").take(12)
            val screen = shot.screen.copy(id = id)
            val ws = dirs.workspace(conversationId)
            ws.mkdirs()
            shot.jpeg?.let { dirs.gate.replace(ws, "$DIR/$id.jpg", it) }
            dirs.gate.replace(ws, "$DIR/$id.json", UserScreenJson.encode(screen).toByteArray())
            screen
        }
    }

    suspend fun screen(conversationId: String, id: String): UserScreen? = read(conversationId, "$id.json")?.let { UserScreenJson.decode(String(it)) }

    suspend fun image(conversationId: String, id: String): ByteArray? = read(conversationId, "$id.jpg")

    /** 这个对话里的屏幕,交给任务按编号取。 */
    fun source(conversationId: String): UserScreenSource = object : UserScreenSource {
        override suspend fun screen(id: String) = this@UserScreenFiles.screen(conversationId, id)
        override suspend fun image(id: String) = this@UserScreenFiles.image(conversationId, id)
    }

    private suspend fun read(conversationId: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!SAFE.matches(name)) return@withContext null
        runCatching {
            dirs.gate.openRead(dirs.workspace(conversationId), "$DIR/$name").use { f ->
                if (f.size > MAX_BYTES) null else f.read(0, f.size.toInt())
            }
        }.getOrNull()
    }

    private companion object {
        const val DIR = "screens"
        const val MAX_BYTES = 20L shl 20
        val SAFE = Regex("^[A-Za-z0-9_-]{1,64}\\.(jpg|json)$")
    }
}
