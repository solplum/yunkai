package com.wangzan101.mika.cap.sandbox

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.core.cap.sandbox.SandboxPort
import com.wangzan101.mika.core.cap.sandbox.PhoneCommandHandler
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.knowledge.ScriptEntry
import com.wangzan101.mika.core.knowledge.ScriptManifest
import com.wangzan101.mika.core.cap.sandbox.ShareRules
import com.wangzan101.mika.core.workspace.WorkspacePathCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val EXIT_TIMEOUT = 124
private const val EXIT_LAUNCHER_ERROR = 125
private const val EXIT_SIGKILL = 128 + 9
/** 启动器自己有时限;Kotlin 侧多等这么久仍没结束就强杀。 */
private const val FALLBACK_GRACE_MS = 10_000L
private const val DESTROY_GRACE_MS = 3_000L
private const val READER_GRACE_MS = 2_000L
private const val TIMEOUT_SLACK_MS = 500L
private const val SIZE_CACHE_MS = 60_000L
internal val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
private const val TAG = "AndroidSandbox"
internal const val SHARE_MAX_FILES = 20
internal const val SHARE_MAX_BYTES = 500L shl 20

/**
 * 一个任务(一个 AndroidSandbox)的导出额度,share_file 与 `mika share` 共用。
 * 复制前按文件大小预留,复制完按实际字节结算;导出失败不占额度。
 */
internal class ShareQuota(private val maxFiles: Int = SHARE_MAX_FILES, private val maxBytes: Long = SHARE_MAX_BYTES) {
    private var files = 0
    private var bytes = 0L

    @Synchronized fun reserve(size: Long) {
        if (files + 1 > maxFiles || bytes + size > maxBytes) {
            throw SandboxError("share_limit", "One task can share at most $maxFiles files and ${maxBytes shr 20} MB in total.", recoverable = false)
        }
        files++
        bytes += size
    }

    @Synchronized fun settle(reserved: Long, actual: Long) { bytes += actual - reserved }

    @Synchronized fun release(reserved: Long) {
        files--
        bytes -= reserved
    }
}

/**
 * 启动沙箱进程的专用线程,进程存活期间一直在。启动器设了 PR_SET_PDEATHSIG,
 * 它跟的是 fork 出它的那个线程:线程池回收空闲线程时,那条线程起的命令会被当成「Mika 死了」而杀掉。
 * 协程调度器把 start() 的异常交回调用方,线程本身不会因异常退出。
 */
private val launcherThread = Executors.newSingleThreadExecutor { r -> Thread(r, "mika-sandbox-launcher").apply { isDaemon = true } }
    .asCoroutineDispatcher()

/** [SandboxPort] 的安卓实现:启动器 + PRoot 跑命令,文件类操作直接读写宿主目录。 */
class AndroidSandbox internal constructor(
    private val dirs: SandboxDirs,
    private val currentStatus: () -> SandboxStatus,
    private val dnsServers: () -> List<String>,
    /** 每次运行开一个桥会话;JVM 测试里没有安卓套接字,传 false。 */
    private val bridges: Boolean = false,
    private val version: String = "",
    /** 存到「下载/Mika」:(已过关口核实的文件, 保存名) → 导出的文件;为空时不能导出。 */
    private val exporter: (suspend (GatedFile, String) -> SharedFile)? = null,
    /** 导出成功后告诉任务(文件卡片)。 */
    private val onShared: (SharedFile) -> Unit = {},
    /** 每次运行前做的准备(把技能目录核对成打包的那份)。 */
    private val sync: suspend () -> Unit = {},
    /** 随 App 打包的技能脚本(技能目录下的相对路径 → 内容);清单和脚本都以它为准。 */
    private val skillFiles: () -> Map<String, ByteArray> = { emptyMap() },
    /** 开始(或拿到正在进行的)运行环境准备;不需要准备为 null。 */
    private val startPreparing: () -> Job? = { null },
    /** 用户的联网设置;不给时按就绪状态里的。 */
    private val allowNetwork: (() -> Boolean)? = null,
    /** APK 内的桥客户端；已有运行环境也要随 APK 更新。 */
    private val clientScript: () -> ByteArray? = { null },
) : SandboxPort {
    override val tree get() = dirs.tree

    constructor(context: Context, state: SandboxState, onShared: (SharedFile) -> Unit = {}) : this(
        SandboxDirs(context), { state.status.value }, { dns(context) }, bridges = true, version = versionOf(context),
        exporter = ShareExporter(context)::export, onShared = onShared, sync = { SkillsSync.sync(SandboxDirs(context)) { state.status.value is SandboxStatus.Ready } },
        skillFiles = { SkillsSync.bundled },
        startPreparing = { RuntimePreparer.ensure(context, retryFailed = true) },
        allowNetwork = { state.networkAllowed },
        clientScript = { context.assets.open("sandbox/mika").use { it.readBytes() } },
    )

    private val quota = ShareQuota()

    private class SizeEntry(val bytes: Long, val at: Long)
    private val sizes = ConcurrentHashMap<String, SizeEntry>()

    override fun status(): SandboxStatus = currentStatus()

    override fun networkAllowed(): Boolean = allowNetwork?.invoke() ?: super.networkAllowed()

    override suspend fun awaitReady(timeoutMs: Long): SandboxStatus {
        val s = status()
        if (s is SandboxStatus.Ready || s is SandboxStatus.Unsupported) return s
        val job = startPreparing() ?: return status()
        withTimeoutOrNull(timeoutMs) { job.join() }
        return status()
    }

    override suspend fun run(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long,
        onProgress: (String) -> Unit): CommandResult = runWithPhone(workspace, argv, cwd, timeoutMs, onProgress, null)

    override suspend fun runWithPhone(workspace: String, argv: List<String>, cwd: String?, timeoutMs: Long, onProgress: (String) -> Unit,
        phone: PhoneCommandHandler?): CommandResult {
        val network = (status() as? SandboxStatus.Ready)?.network == true
        // 调用方给的 cwd 再核一遍:必须规范化后仍在工作区里
        val rel = cwd?.takeIf { it.isNotEmpty() }?.let { c ->
            val n = SandboxPaths.normalize(c)?.takeIf(SandboxPaths::isWritable)
                ?: throw SandboxError("path_outside_workspace", "cwd must be a directory inside /workspace.", recoverable = true)
            n.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        }.orEmpty()
        // 技能脚本没同步上不挡命令:脚本跑不了时 run_bundled_script 会照实报
        prepare()
        val timeoutSec = ((timeoutMs + 999) / 1000).coerceIn(1, 3600).toInt()
        // 建目录到命令结束都在许可里:命令也往工作区里写,重置要等它跑完再删
        val o = tree.shared {
            val ws = withContext(Dispatchers.IO) {
                dirs.workspace(workspace).also {
                    dirs.ensureBindSources(it)
                    syncClientScript()
                    // cwd 在工作区里不存在就建上(逐段、不跟随链接);建不了的不建,让 PRoot 自己报错
                    rel.takeIf { c -> c.isNotEmpty() }?.let { c -> runCatching { files.mkdirs(it, c) } }
                    if (network) writeResolvConf()
                }
            }
            val cmd = ProcRunner.argv(dirs, ws, rel, timeoutSec, network, standIns(), argv)
            // 命令结束(含超时、被杀、取消)就关掉桥:旧的名字和令牌不再能用
            val session = if (bridges) bridgeFor(workspace, network, onProgress, phone).open() else null
            try {
                launch(cmd, ProcRunner.env(dirs, session?.name, session?.token, workspace), timeoutSec * 1000L + FALLBACK_GRACE_MS)
            } finally {
                session?.close()
            }
        }
        sizes.remove(workspace)
        val text = String(o.output, Charsets.UTF_8)
        if (o.exitCode == EXIT_LAUNCHER_ERROR) {
            val reason = text.trimEnd().lineSequence().lastOrNull().orEmpty()
            if (reason.startsWith("mikarun: network isolation unavailable")) {
                throw SandboxError("sandbox_network_isolation_unavailable",
                    "The sandbox cannot cut off the network on this device, so commands are refused while network access is off. ($reason)")
            }
        }
        val timedOut = o.timedOut || launcherTimedOut(o.exitCode, o.durationMs, timeoutSec)
        return CommandResult(
            exitCode = if (o.timedOut) EXIT_TIMEOUT else o.exitCode, timedOut = timedOut,
            killedBySystem = !timedOut && o.exitCode == EXIT_SIGKILL, durationMs = o.durationMs,
            output = text, droppedBytes = o.droppedBytes, network = network,
        )
    }

    override fun hostPath(workspace: String, sandboxPath: String, forWrite: Boolean): File? {
        val p = SandboxPaths.normalize(sandboxPath) ?: return null
        return when {
            SandboxPaths.isWritable(p) -> SandboxPaths.resolveHost(dirs.workspace(workspace), p.removePrefix(SandboxPaths.WORKSPACE).trimStart('/'))
            forWrite -> null
            else -> SandboxPaths.resolveHost(dirs.skills, p.removePrefix(SandboxPaths.SKILLS).trimStart('/'))
        }
    }

    override val files: WorkspaceFileGate get() = dirs.gate

    // 只有写(调用方持有 SandboxTree 的许可)才建工作区目录:读和查路径不持有许可,建了会和重置的删除撞上
    override fun hostRoot(workspace: String, normalized: String, forWrite: Boolean): Pair<File, String>? = when {
        SandboxPaths.isWritable(normalized) -> dirs.workspace(workspace).also { if (forWrite) it.mkdirs() } to normalized.removePrefix(SandboxPaths.WORKSPACE).trimStart('/')
        forWrite -> null
        else -> dirs.skills to normalized.removePrefix(SandboxPaths.SKILLS).trimStart('/')
    }

    /** web_upload 的路径检查:只认这个工作区里过得了关口的普通文件;技能目录和工作区外都算越界。 */
    fun uploadCheck(workspace: String, sandboxPath: String): WorkspacePathCheck {
        hostPath(workspace, sandboxPath, forWrite = true) ?: return WorkspacePathCheck.Outside
        val n = SandboxPaths.normalize(sandboxPath) ?: return WorkspacePathCheck.Outside
        val (root, rel) = hostRoot(workspace, n, forWrite = false) ?: return WorkspacePathCheck.Outside
        return try {
            files.openRead(root, rel).close()
            WorkspacePathCheck.Ok
        } catch (e: SandboxError) {
            if (e.code == WorkspaceFileGate.OUTSIDE) WorkspacePathCheck.Outside else WorkspacePathCheck.NotFound
        }
    }

    /**
     * [file] 已由 ShareRules 按工作区检查过;导出时经关口打开一次(根目录是它所在的那个工作区),额度按句柄上的大小算,
     * 复制也从这个句柄读。意外的错误只记在 Mika 这边,交出去的只有错误码和一句话,不带 Mika 的私有路径。
     */
    override suspend fun share(file: File, name: String): String {
        val export = exporter ?: throw SandboxError("share_unavailable", "Saving files to Downloads is not available in this version.")
        val (within, rel) = withContext(Dispatchers.IO) { workspaceOf(file) }
            ?: throw SandboxError("path_outside_workspace", "Only files inside /workspace can be shared.", recoverable = true)
        val opened = withContext(Dispatchers.IO) { files.openRead(within, rel) }
        return opened.use { g ->
            val reserved = g.size
            quota.reserve(reserved)
            val shared = try {
                export(g, name)
            } catch (e: Throwable) {
                quota.release(reserved)
                throw mapExportError(e)
            }
            quota.settle(reserved, shared.size)
            onShared(shared)
            shared.savedAs
        }
    }

    private fun mapExportError(e: Throwable): Throwable = when (e) {
        is CancellationException, is SandboxError -> e
        is FileTooLarge -> SandboxError("file_too_large", "At most ${ShareRules.MAX_BYTES shr 20} MB can be shared.", recoverable = true)
        is Exception -> {
            Log.w(TAG, "export to Downloads failed", e)
            SandboxError("share_failed", "Saving to Downloads failed.", recoverable = true)
        }
        else -> e
    }

    /** 文件所在的那个工作区目录(work/ 下的第一层)与工作区里的相对路径;不在任何工作区里为 null。 */
    private fun workspaceOf(file: File): Pair<File, String>? {
        val work = dirs.work.canonicalFile
        val rel = file.canonicalFile.toRelativeString(work).takeIf { !it.startsWith("..") && it.isNotEmpty() && !File(it).isAbsolute } ?: return null
        val first = rel.substringBefore(File.separatorChar)
        return if (first == rel) null else File(work, first) to rel.substringAfter(File.separatorChar).replace(File.separatorChar, '/')
    }

    override fun freeBytes(): Long = dirs.root.takeIf { it.exists() }?.usableSpace ?: dirs.root.parentFile?.usableSpace ?: 0L

    /** 不跟随链接地累加;缓存一分钟,本工作区跑过命令后作废。 */
    override fun workspaceBytes(workspace: String): Long {
        val now = System.currentTimeMillis()
        sizes[workspace]?.takeIf { now - it.at < SIZE_CACHE_MS }?.let { return it.bytes }
        val bytes = treeBytes(dirs.workspace(workspace).toPath())
        sizes[workspace] = SizeEntry(bytes, now)
        return bytes
    }

    /** 经关口在 .mika/ 里新建(不覆盖已有的);.mika 被换成链接等时抛出,不写任何东西。 */
    @Synchronized
    override fun saveOutput(workspace: String, text: String): String {
        val root = dirs.workspace(workspace).apply { mkdirs() }
        var next = (files.list(root, ".mika").mapNotNull { OUT_NAME.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1
        repeat(100) {
            val rel = ".mika/out-$next.txt"
            try {
                files.createNew(root, rel, under = ".mika").use { it.write(text.toByteArray(Charsets.UTF_8)) }
                return "${SandboxPaths.WORKSPACE}/$rel"
            } catch (e: SandboxError) {
                if (e.code != WorkspaceFileGate.FILE_EXISTS) throw IOException("cannot save output: ${e.code}")
                next++
            }
        }
        throw IOException("cannot save output: too many files")
    }

    /** 读随 App 打包的那份清单:沙箱里的那份命令改得动,不能信。 */
    override fun scriptManifest(skillPath: String): List<ScriptEntry> {
        val bytes = skillFiles()["$skillPath/scripts/manifest.json"] ?: return emptyList()
        return ScriptManifest.parse(bytes.toString(Charsets.UTF_8))
    }

    override suspend fun prepare() {
        try { sync() } catch (e: CancellationException) { throw e } catch (e: Exception) { Log.w(TAG, "skill sync failed", e) }
    }

    private val bundledClient: ByteArray? by lazy(clientScript)

    /** 已安装的根文件系统版本可能没变，但 APK 中的桥客户端已经更新。 */
    private fun syncClientScript() {
        val expected = bundledClient ?: return
        val rel = "usr/local/bin/mika"
        val matches = runCatching {
            dirs.gate.openRead(dirs.rootfs, rel).use { it.size == expected.size.toLong() && it.read(0, expected.size).contentEquals(expected) }
        }.getOrDefault(false)
        if (!matches || !File(dirs.rootfs, rel).canExecute()) dirs.gate.replace(dirs.rootfs, rel, expected, mode = 493 /* 0755 */)
    }

    override suspend fun verifyScript(skillPath: String, file: String): Boolean {
        val rel = "$skillPath/$file"
        val expected = skillFiles()[rel] ?: return false
        if (withContext(Dispatchers.IO) { SkillsSync.matches(dirs, rel, expected) }) return true
        prepare()
        return withContext(Dispatchers.IO) { SkillsSync.matches(dirs, rel, expected) }
    }

    /** `mika share` 与 share_file 走同一套路径规则。 */
    private fun bridgeFor(workspace: String, network: Boolean, onProgress: (String) -> Unit, phone: PhoneCommandHandler?): SandboxBridge {
        return SandboxBridge(
        onProgress = onProgress,
        onShare = { path, name ->
            val (f, n) = withContext(Dispatchers.IO) { ShareRules.check(this@AndroidSandbox, workspace, path, name) }
            share(f, n)
        },
        info = {
            buildJsonObject { put("version", version); put("network", network); put("workspace", SandboxPaths.WORKSPACE) }
        },
        onPhone = phone?.let { handler -> { tool, args -> handler.call(tool, args) } },
        )
    }

    /**
     * 启动器超时退出码是 124,但命令自己也可能以 124 退出,启动器不另作标记。
     * 所以只有跑满了时限(留 [TIMEOUT_SLACK_MS] 余量)的 124 才算超时;提前以 124 退出的照实算命令的退出码。
     */
    internal fun launcherTimedOut(exitCode: Int, durationMs: Long, timeoutSec: Int): Boolean =
        exitCode == EXIT_TIMEOUT && durationMs >= timeoutSec * 1000L - TIMEOUT_SLACK_MS

    private fun standIns(): List<String> = dirs.procStandIns.list()?.sorted().orEmpty()

    /** 联网时把当前网络的 DNS 写进根文件系统:经关口整份换上(根文件系统里的东西命令改得了,原来是链接也只是被换掉)。 */
    private fun writeResolvConf() {
        val servers = runCatching(dnsServers).getOrNull().orEmpty().ifEmpty { FALLBACK_DNS }
        files.replace(dirs.rootfs, "etc/resolv.conf", servers.joinToString("") { "nameserver $it\n" }.toByteArray())
    }

    internal companion object {
        private val OUT_NAME = Regex("^out-(\\d+)\\.txt$")

        private fun versionOf(context: Context): String =
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

        internal fun dns(context: Context): List<String> {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
            val lp = cm.getLinkProperties(cm.activeNetwork ?: return emptyList()) ?: return emptyList()
            return lp.dnsServers.mapNotNull { it.hostAddress }
        }

        internal fun treeBytes(root: Path): Long {
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return 0
            var total = 0L
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: IOException) = FileVisitResult.CONTINUE
            })
            return total
        }

        /**
         * 在专用线程上 start(),其余在 IO 线程:读输出(上限 1 MB)、等结束。到 [limitMs] 仍没结束就强杀。
         * 协程取消时先 SIGTERM(启动器收到后清掉整个进程树),[DESTROY_GRACE_MS] 后还在就 SIGKILL。
         */
        internal suspend fun launch(
            cmd: List<String>, env: Map<String, String>, limitMs: Long,
            start: (ProcessBuilder) -> Process = ProcessBuilder::start,
        ): ProcOutcome {
            val begin = System.nanoTime()
            // 启动本身不可取消。withContext 把结果交回原协程时若已取消会丢掉结果,
            // 所以进程在块里就记下来:丢了结果也知道要杀谁
            var started: Process? = null
            try {
                withContext(launcherThread + NonCancellable) {
                    started = start(ProcessBuilder(cmd).redirectErrorStream(true).apply {
                        environment().clear()
                        environment().putAll(env)
                    })
                }
            } catch (e: CancellationException) {
                started?.let(::abandon)
                throw e
            }
            val p = started!!
            // 读输出用普通线程:阻塞的 read 取消不了,放在协程里会让取消一直等下去
            val collector = OutputCollector()
            val reader = thread(isDaemon = true, name = "mika-sandbox-output") { runCatching { p.inputStream.use(collector::drain) } }
            try {
                // 启动期间已被取消:在这里抛出,由下面的 catch 杀掉刚起的进程
                currentCoroutineContext().ensureActive()
                p.outputStream.close()
                val finished = runInterruptible(Dispatchers.IO) { p.waitFor(limitMs, TimeUnit.MILLISECONDS) }
                if (!finished) p.destroyForcibly()
                val code = runInterruptible(Dispatchers.IO) { p.waitFor() }
                // 正常时启动器退出前已清掉整个进程树,管道随之关闭;万一还有后代占着管道,不无限等
                runInterruptible(Dispatchers.IO) { reader.join(READER_GRACE_MS) }
                // 还有后代占着管道:关掉我们这一端,读线程随之结束
                if (reader.isAlive) runCatching { p.inputStream.close() }
                val (bytes, dropped) = collector.snapshot()
                return ProcOutcome(code, bytes, dropped, !finished, (System.nanoTime() - begin) / 1_000_000)
            } catch (e: CancellationException) {
                abandon(p)
                throw e
            }
        }

        /** 先 SIGTERM(启动器收到后清掉整个进程树),[DESTROY_GRACE_MS] 后还在就 SIGKILL。 */
        private fun abandon(p: Process) {
            p.destroy()
            thread(isDaemon = true, name = "mika-sandbox-reaper") { if (!p.waitFor(DESTROY_GRACE_MS, TimeUnit.MILLISECONDS)) p.destroyForcibly() }
        }
    }
}
