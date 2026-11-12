package com.wangzan101.mika.cap.sandbox

import android.content.Context
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File
import java.security.MessageDigest
import com.wangzan101.mika.core.workspace.SandboxTree

/**
 * 沙箱在宿主上的目录:`files/sandbox/` 下的根文件系统、工作区、技能、/proc 替身;PRoot 的临时目录在缓存里;
 * 可执行文件只在 [nativeDir](安卓 10 起数据目录里的文件不能 execve)。
 */
class SandboxDirs internal constructor(
    val root: File, val tmp: File, val nativeDir: File,
    /** 宿主侧读写这些目录(沙箱碰得到)都过它;手机上是 [AndroidFdSys.gate],JVM 测试传按路径的那份。 */
    val gate: WorkspaceFileGate,
    /** 往这棵树里写都先拿它的许可;手机上是进程里那一份([SandboxOwner]),测试自己给一份。不设默认:私有的一份锁不住别人。 */
    val tree: SandboxTree,
) {
    constructor(context: Context) : this(
        File(context.filesDir, "sandbox"), File(context.cacheDir, "proot-tmp"), File(context.applicationInfo.nativeLibraryDir),
        AndroidFdSys.gate, SandboxOwner.of(context).sandboxTree,
    )

    val rootfs: File get() = File(root, "rootfs")
    val work: File get() = File(root, "work")
    val skills: File get() = File(root, "skills")
    val procStandIns: File get() = File(root, "proc")

    /** 一个对话一个工作区:简单名字的 ID 为 c-<ID>,其余为 h-<哈希>(不让 ID 拼出别的路径,两类也不会撞名)。 */
    fun workspace(conversationId: String): File = File(work, dirName(conversationId))

    fun lib(name: String): File = File(nativeDir, name)

    /**
     * 绑定前把要绑的目录都建好:源目录不存在时 PRoot 会往标准错误写一句警告,
     * 而标准错误并进了命令输出,模型会看到。技能目录还没同步时就是空目录。
     */
    fun ensureBindSources(workspace: File) {
        for (d in listOf(workspace, skills, tmp)) d.mkdirs()
    }

    private fun dirName(id: String): String =
        if (SAFE_ID.matches(id)) "c-$id"
        else "h-" + MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,100}$")
    }
}
