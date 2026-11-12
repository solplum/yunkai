package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.os.Build
import com.wangzan101.mika.core.cap.sandbox.RootfsExtractor
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.DigestInputStream
import java.security.MessageDigest

/** 随安装包自带的运行环境:assets 里的 tar.xz,版本与 SHA-256 由 tools/sandbox/build-runtime.py 生成。 */
class RuntimeBundle(
    val version: String,
    val sha256: String,
    /** 解出的普通文件总字节数与条目数上限(防止坏包把空间灌满)。 */
    val maxBytes: Long,
    val maxEntries: Int,
    val open: () -> InputStream,
) {
    companion object {
        fun bundled(context: Context): RuntimeBundle = RuntimeBundle(
            BundledRuntime.VERSION, BundledRuntime.SHA256,
            BundledRuntime.FILE_BYTES + BundledRuntime.FILE_BYTES / 4 + (64L shl 20), BundledRuntime.ENTRIES + 1000,
        ) { context.assets.open(BundledRuntime.ASSET) }
    }
}

/** 通知与设置页上显示的步骤名。 */
internal val InstallStep.label: String get() = when (this) {
    InstallStep.Check -> tr(R.string.install_check)
    InstallStep.Extract -> tr(R.string.install_extract)
    InstallStep.Configure -> tr(R.string.install_configure)
    InstallStep.Skills -> tr(R.string.install_skills)
    InstallStep.Done -> tr(R.string.result_completed)
}

/**
 * 准备运行环境:检查 → 解出自带的根文件系统 → 配置 → 同步技能 → 完成。不联网。
 * 每步完成记进 [SandboxState],下次从记录那一步的下一步开始;失败时状态为 Broken(原因)并抛出。
 * 自带的版本变了(Mika 升级)就重新解包:解到新目录,校验通过后换上,工作区不动。
 *
 * 参数除 [dirs]、[state]、[bundle] 外是测试用的接缝,默认值即真机行为。
 */
class RootfsInstaller(
    private val dirs: SandboxDirs,
    private val state: SandboxState,
    private val bundle: RuntimeBundle,
    private val abis: () -> List<String> = { Build.SUPPORTED_ABIS.toList() },
    private val freeBytes: () -> Long = { dirs.root.parentFile?.usableSpace ?: 0L },
    /** 跑 `libmikarun.so --self-test`,返回(退出码, 输出)。 */
    private val selfTest: suspend () -> Pair<Int, String> = { runSelfTest(dirs) },
    /** 宿主上这个 /proc 文件能不能读;读不到的生成替身。 */
    private val procReadable: (String) -> Boolean = ::hostCanRead,
    private val prepareDev: (File) -> Unit = { ProotDevices.prepareRootfsDev(it, dirs.gate) },
    /** 桥客户端脚本(装到 /usr/local/bin/mika);null = 安装包里没有,安装失败。 */
    private val clientScript: () -> ByteArray? = { null },
    private val dnsServers: () -> List<String> = { emptyList() },
    private val syncSkills: suspend () -> Unit = {},
) {
    private val fresh get() = File(dirs.root, "rootfs.new")
    private val old get() = File(dirs.root, "rootfs.old")

    /** 和重置、技能同步、跑命令等一切写 `files/sandbox/` 的操作经 [SandboxTree] 互斥。 */
    suspend fun install(onProgress: (InstallStep, Int) -> Unit) = dirs.tree.exclusive {
        withContext(Dispatchers.IO) {
            val upToDate = state.installedVersion == bundle.version
            if (state.completedStep() == InstallStep.Done && state.status.value is SandboxStatus.Ready && upToDate) {
                onProgress(InstallStep.Done, 100)
                return@withContext
            }
            var step = state.completedStep()?.let { done -> STEPS.getOrNull(STEPS.indexOf(done) + 1) } ?: InstallStep.Check
            // 装好的是旧版本(Mika 升级了):从解包重来,旧的在新的解好之前照常留着
            if (state.completedStep() == InstallStep.Done && !upToDate) step = InstallStep.Check
            try {
                for (s in STEPS.drop(STEPS.indexOf(step))) {
                    onProgress(s, PERCENT.getValue(s))
                    state.installing(s.label, PERCENT.getValue(s))
                    run(s)
                    state.markStep(s, bundle.version)
                }
            } catch (e: CancellationException) {
                state.markBroken(tr(R.string.install_interrupted))
                throw e
            } catch (e: Exception) {
                state.markBroken(e.message ?: e.javaClass.simpleName)
                throw e
            } finally {
                state.cleared()
            }
        }
    }

    /** 删除整个 `files/sandbox/`(只读目录先改成可写,不跟随符号链接),回到未安装。 */
    suspend fun reset() = dirs.tree.exclusive {
        withContext(Dispatchers.IO) {
            state.resetting()
            try {
                // 这些目录沙箱里的命令碰得到:按句柄删,不跟进中途换成的链接
                deleteReachable(dirs.root)
                deleteReachable(dirs.tmp)
            } finally {
                state.cleared()
            }
        }
    }

    /** 删除所有对话的工作区。 */
    suspend fun clearWorkspaces() = dirs.tree.exclusive {
        withContext(Dispatchers.IO) { deleteReachable(dirs.work) }
    }

    private suspend fun run(s: InstallStep) {
        when (s) {
            InstallStep.Check -> check()
            InstallStep.Extract -> extractAndSwap()
            InstallStep.Configure -> configure()
            InstallStep.Skills -> syncSkills()
            InstallStep.Done -> deleteReachable(old)
        }
    }

    /**
     * 解到 `rootfs.new`,边解边算整个文件的 SHA-256;对得上才换上(旧的先改名再删),对不上全删。
     * 中途被杀:下次从解包重来,残留的 `rootfs.new` 先删掉。
     */
    private fun extractAndSwap() {
        RootfsExtractor.deleteTree(fresh)
        deleteReachable(old)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            DigestInputStream(BufferedInputStream(bundle.open(), 1 shl 16), digest).use { raw ->
                // 解包器读完会关流;这里还要把剩下的读完算校验,所以不让它关到 raw
                val keepOpen = object : FilterInputStream(raw) { override fun close() = Unit }
                RootfsExtractor.extractTar(XZInputStream(keepOpen), fresh, maxBytes = bundle.maxBytes, maxEntries = bundle.maxEntries)
                val buf = ByteArray(1 shl 16)
                while (raw.read(buf) >= 0) Unit // xz 尾部没读到的字节也要算进校验
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != bundle.sha256) throw IOException(tr(R.string.install_checksum))
        } catch (e: Exception) {
            RootfsExtractor.deleteTree(fresh)
            throw IOException(tr(R.string.install_unpack_failed, e.message), e)
        }
        if (Files.exists(dirs.rootfs.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Files.move(dirs.rootfs.toPath(), old.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        Files.move(fresh.toPath(), dirs.rootfs.toPath(), StandardCopyOption.ATOMIC_MOVE)
        deleteReachable(old)
    }

    private suspend fun check() {
        if ("arm64-v8a" !in abis()) throw IOException(tr(R.string.install_unsupported))
        val free = freeBytes()
        // 升级时新旧两份同时在盘上
        val need = 2 * bundle.maxBytes
        if (free < need) throw IOException(tr(R.string.install_no_space, need shr 20, free / (1 shl 20)))
        val (code, out) = selfTest()
        val lines = out.lines().map { it.trim() }
        if (code != 0 || lines.firstOrNull() != "mikarun ok") {
            throw IOException(tr(R.string.install_cannot_start, lines.lastOrNull { it.isNotEmpty() } ?: tr(R.string.install_exit_code, code)))
        }
        // 断网过滤器不可用不挡安装:联网关着时命令会被拒绝,这里只记下原因给设置页
        state.recordIsolation(lines.firstOrNull { it.startsWith("seccomp unavailable") }?.removePrefix("seccomp unavailable:")?.trim())
    }

    private fun configure() {
        writeInRootfs("etc/hosts", "127.0.0.1 localhost\n::1 localhost\n")
        writeInRootfs("etc/resolv.conf", dnsServers().ifEmpty { FALLBACK_DNS }.joinToString("") { "nameserver $it\n" })
        val client = clientScript() ?: throw IOException(tr(R.string.install_no_client))
        writeInRootfs("usr/local/bin/mika", client, executable = true)
        prepareDev(dirs.rootfs)
        writeProcStandIns()
    }

    /** 根文件系统里的文件:经关口在同一目录写好新的再整份换上;原来那一项是链接(哪怕悬空)、硬链接都只是被换掉。 */
    private fun writeInRootfs(rel: String, text: String) = writeInRootfs(rel, text.toByteArray(), executable = false)

    private fun writeInRootfs(rel: String, bytes: ByteArray, executable: Boolean) {
        try {
            dirs.gate.replace(dirs.rootfs, rel, bytes, mode = if (executable) 493 /* 0755 */ else 420 /* 0644 */)
        } catch (e: com.wangzan101.mika.core.workspace.SandboxError) {
            throw IOException("$rel cannot be written in the rootfs (${e.code})")
        }
    }

    /** 删掉 [dir] 整棵树(它的上一层是 Mika 自己的目录);经关口按句柄逐层走。 */
    private fun deleteReachable(dir: File) {
        val parent = dir.parentFile ?: return
        dirs.gate.deleteTree(parent, dir.name)
    }

    /** 安卓不让普通 App 读的 /proc 文件,生成格式正确的静态替身,运行时绑过去。 */
    private fun writeProcStandIns() {
        RootfsExtractor.deleteTree(dirs.procStandIns)
        dirs.procStandIns.mkdirs()
        for ((name, content) in procStandInContents()) {
            if (!procReadable("/proc/$name")) File(dirs.procStandIns, name).writeText(content)
        }
    }

    companion object {
        private val STEPS = InstallStep.entries.toList()
        private val PERCENT = mapOf(
            InstallStep.Check to 0, InstallStep.Extract to 5, InstallStep.Configure to 90, InstallStep.Skills to 95, InstallStep.Done to 100,
        )

        /** 真机用的安装器:自带的运行环境、assets 里的桥客户端、当前网络的 DNS。 */
        fun create(context: Context): RootfsInstaller {
            val app = context.applicationContext
            val dirs = SandboxDirs(app)
            return RootfsInstaller(
                dirs, SandboxState.get(app), RuntimeBundle.bundled(app),
                clientScript = { runCatching { app.assets.open("sandbox/mika").use { it.readBytes() } }.getOrNull() },
                dnsServers = { AndroidSandbox.dns(app) },
                syncSkills = { SkillsSync.sync(dirs) },
            )
        }

        private fun hostCanRead(path: String): Boolean = runCatching { File(path).inputStream().use { it.read() }; true }.getOrDefault(false)

        private suspend fun runSelfTest(dirs: SandboxDirs): Pair<Int, String> {
            val o = AndroidSandbox.launch(listOf(dirs.lib("libmikarun.so").path, "--self-test"), emptyMap(), 15_000)
            return o.exitCode to String(o.output, Charsets.UTF_8)
        }

        private fun procStandInContents(): Map<String, String> {
            val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val bootSec = System.currentTimeMillis() / 1000
            return linkedMapOf(
                "stat" to buildString {
                    append("cpu  0 0 0 0 0 0 0 0 0 0\n")
                    for (i in 0 until cpus) append("cpu$i 0 0 0 0 0 0 0 0 0 0\n")
                    append("intr 0\nctxt 0\nbtime $bootSec\nprocesses 1\nprocs_running 1\nprocs_blocked 0\nsoftirq 0 0 0 0 0 0 0 0 0 0 0\n")
                },
                "loadavg" to "0.00 0.00 0.00 1/1 1\n",
                "uptime" to "1.00 1.00\n",
                "version" to "Linux version ${System.getProperty("os.version") ?: "0"} (mika@sandbox) #1 SMP PREEMPT\n",
                "vmstat" to "nr_free_pages 0\nnr_inactive_anon 0\nnr_active_anon 0\nnr_inactive_file 0\nnr_active_file 0\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n",
            )
        }
    }
}
