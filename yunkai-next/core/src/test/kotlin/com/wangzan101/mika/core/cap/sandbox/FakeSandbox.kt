// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.cap.sandbox

import java.io.File
import java.nio.file.Files
import com.wangzan101.mika.core.workspace.NioFdSys
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.knowledge.ScriptEntry
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.workspace.SandboxTree

/** 宿主目录放在临时目录里的沙箱;命令不真跑,按 [next] 回结果并记下收到的参数。 */
class FakeSandbox(var status: SandboxStatus = SandboxStatus.Ready(network = false)) : SandboxPort {
    class Run(val workspace: String, val argv: List<String>, val cwd: String?, val timeoutMs: Long)

    override val tree = SandboxTree()
    val root: File = Files.createTempDirectory("fake-sandbox").toFile()
    val skillsDir = File(root, "skills").apply { mkdirs() }
    val runs = mutableListOf<Run>()
    var phoneHandler: PhoneCommandHandler? = null
    val shared = mutableListOf<Pair<File, String>>()
    var next: (Run) -> CommandResult = { CommandResult(0, false, false, 10, "", 0, false) }
    var free: Long = 10L shl 30
    var used: Long = 0
    val manifests = mutableMapOf<String, List<ScriptEntry>>()
    private var outputs = 0

    fun workspaceDir(ws: String) = File(root, "work/$ws").apply { mkdirs() }

    override fun status() = status

    /** awaitReady 之后的状态(模拟后台准备好了或失败了);null = 不变。 */
    var afterWait: SandboxStatus? = null
    var waits = 0

    override suspend fun awaitReady(timeoutMs: Long): SandboxStatus {
        waits++
        afterWait?.let { status = it }
        return status
    }

    override suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit): CommandResult {
        val r = Run(workspace, argv, cwd, timeoutMs)
        runs += r
        return next(r)
    }

    override suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit, phone: PhoneCommandHandler?): CommandResult {
        phoneHandler = phone
        return run(workspace, argv, cwd, timeoutMs, onProgress)
    }

    override fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File? {
        val p = SandboxPaths.normalize(sandboxPath) ?: return null
        return when {
            SandboxPaths.isWritable(p) -> SandboxPaths.resolveHost(workspaceDir(workspace), p.removePrefix(SandboxPaths.WORKSPACE).trimStart('/'))
            forWrite -> null
            else -> SandboxPaths.resolveHost(skillsDir, p.removePrefix(SandboxPaths.SKILLS).trimStart('/'))
        }
    }

    override val files = WorkspaceFileGate(NioFdSys)

    override fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>? = when {
        SandboxPaths.isWritable(normalized) -> workspaceDir(workspace) to normalized.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        forWrite -> null
        else -> skillsDir to normalized.removePrefix(SandboxPaths.SKILLS).trimStart('/')
    }

    override suspend fun share(file: File, name: String): String { shared += file to name; return "Download/Yunkai/$name" }
    override fun freeBytes() = free
    override fun workspaceBytes(workspace: String) = used

    override fun saveOutput(workspace: String, text: String): String {
        val rel = ".mika/out-${++outputs}.txt"
        val f = SandboxPaths.resolveHost(workspaceDir(workspace), rel) ?: throw java.io.IOException("$rel escapes the workspace")
        f.parentFile.mkdirs()
        f.writeText(text)
        return "${SandboxPaths.WORKSPACE}/.mika/out-$outputs.txt"
    }

    override fun scriptManifest(skillPath: String): List<ScriptEntry> = manifests[skillPath].orEmpty()

    var prepares = 0
    /** 与打包的那份不一致(且恢复不了)的脚本:"<技能>/<文件>"。 */
    val tampered = mutableSetOf<String>()
    override suspend fun prepare() { prepares++ }
    override suspend fun verifyScript(skillPath: String, file: String) = "$skillPath/$file" !in tampered
}
