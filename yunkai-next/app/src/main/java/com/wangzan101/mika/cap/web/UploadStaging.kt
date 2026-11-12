package com.wangzan101.mika.cap.web

import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import java.io.File
import java.util.UUID

/**
 * 网页上传的中转区:工作区里的文件经 [gate] 打开一次、在句柄上核实,从这个句柄复制到 [root]/<随机>/ 下。
 * [root] 在 App 私有的缓存目录里,沙箱碰不到;交给页面的只有这份副本,检查之后工作区里再怎么换文件也影响不到。
 */
class UploadStaging(
    private val root: File,
    private val gate: WorkspaceFileGate,
    private val maxFileBytes: Long = 100L shl 20,
    private val maxTotalBytes: Long = 200L shl 20,
) {
    /** 一次上传的副本:[dir] 整个目录用完即删。 */
    class Staged(val dir: File, val files: List<File>)

    sealed interface Result {
        class Ok(val staged: Staged) : Result
        /** [error]:path_outside_workspace、file_not_found、linked_file、file_too_large。 */
        class Refused(val error: String, val path: String) : Result
    }

    fun stage(workspaceDir: File, paths: List<String>): Result {
        val dir = File(root, UUID.randomUUID().toString())
        val out = mutableListOf<File>()
        var total = 0L
        try {
            for ((i, p) in paths.withIndex()) {
                val rel = SandboxPaths.normalize(p)?.takeIf(SandboxPaths::isWritable)?.removePrefix(SandboxPaths.WORKSPACE)?.trimStart('/')
                    ?.takeIf { it.isNotEmpty() } ?: return refuse(dir, "path_outside_workspace", p)
                val g = try { gate.openRead(workspaceDir, rel) } catch (e: SandboxError) { return refuse(dir, codeOf(e), p) }
                g.use {
                    if (g.size > maxFileBytes || total + g.size > maxTotalBytes) return refuse(dir, FILE_TOO_LARGE, p)
                    // 每个文件一层目录:不同目录里的同名文件各自保留原名
                    val target = File(File(dir, i.toString()).apply { mkdirs() }, g.name)
                    var copied = 0L
                    target.outputStream().use { o ->
                        val input = g.input()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            copied += n
                            // 核实之后文件又变大了:照样按上限算
                            if (copied > maxFileBytes || total + copied > maxTotalBytes) return refuse(dir, FILE_TOO_LARGE, p)
                            o.write(buf, 0, n)
                        }
                    }
                    total += copied
                    out += target
                }
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
        return Result.Ok(Staged(dir, out))
    }

    fun delete(staged: Staged) {
        staged.dir.deleteRecursively()
    }

    /** 删掉全部副本(启动时清上次进程留下的)。 */
    fun clear() {
        root.deleteRecursively()
    }

    private fun refuse(dir: File, error: String, path: String): Result {
        dir.deleteRecursively()
        return Result.Refused(error, path)
    }

    private fun codeOf(e: SandboxError) = when (e.code) {
        WorkspaceFileGate.OUTSIDE -> "path_outside_workspace"
        WorkspaceFileGate.LINKED_FILE -> WorkspaceFileGate.LINKED_FILE
        else -> "file_not_found"
    }

    companion object {
        const val FILE_TOO_LARGE = "file_too_large"
        /** 缓存目录下的中转区;FileProvider 只开放这一个目录(res/xml/web_files.xml)。 */
        const val DIR = "webupload"
    }
}
