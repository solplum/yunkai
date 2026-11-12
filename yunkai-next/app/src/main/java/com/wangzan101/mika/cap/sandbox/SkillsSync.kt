package com.wangzan101.mika.cap.sandbox

import androidx.annotation.VisibleForTesting
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.cap.sandbox.RootfsExtractor
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/**
 * 把随 App 打包的技能脚本(清单 + 清单列出的文件)同步到 `files/sandbox/skills/`,沙箱里是 `/opt/mika/skills`。
 * 这个目录在沙箱里写得动(PRoot 的绑定是可写的),所以每次都按磁盘上实际的内容核对:和打包的一模一样才不动;
 * 多出、缺少、改过的文件,或出现链接等非普通文件,都先在旁边写一份新的再整体换上。
 *
 * 并行任务:同步由 [lock] 串行;换目录的那一刻,另一个任务里正在启动的脚本可能找不到文件而报错(照实返回),
 * 已经在跑的解释器早把脚本读进去了,不受影响。run_bundled_script 跑之前还会再逐字节核对一次([matches])。
 */
object SkillsSync {
    private val lock = Mutex()
    private val packaged by lazy { GuideCatalog.loadBundled().scriptFiles() }

    /** 只给真机测试用:临时加一个测试技能。正式代码不设它。 */
    @VisibleForTesting
    @Volatile var extraForTests: Map<String, ByteArray> = emptyMap()

    val bundled: Map<String, ByteArray> get() = if (extraForTests.isEmpty()) packaged else packaged + extraForTests

    /**
     * 和安装、重置经 [SandboxTree] 互斥(安装里的这一步就在安装持有的许可里跑);[proceed] 在拿到许可后再看一次:
     * 等许可期间运行环境被重置了(不再就绪)就不写,免得在刚删空的目录里又建出技能目录。
     */
    suspend fun sync(dirs: SandboxDirs, files: Map<String, ByteArray> = bundled, proceed: () -> Boolean = { true }) = dirs.tree.shared { lock.withLock {
        withContext(Dispatchers.IO) {
            if (!proceed()) return@withContext
            if (onDisk(dirs)?.let(::hashOf) == hashOf(files)) return@withContext
            val staging = File(dirs.root, "skills.new")
            RootfsExtractor.deleteTree(staging)
            staging.mkdirs()
            for ((rel, bytes) in files) {
                // 路径来自打包的清单;照样按规则解析,不让它写到目录外面
                val f = SandboxPaths.resolveHost(staging, rel) ?: continue
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
            // 技能目录沙箱写得动:整棵删要按句柄走,中途被换成链接的目录不跟进去
            dirs.gate.deleteTree(dirs.root, dirs.skills.name)
            check(staging.renameTo(dirs.skills)) { "could not move synced skills into place" }
            // 旧版本按标记文件判断,已不再使用
            File(dirs.root, "skills.sha256").delete()
        }
    } }

    /** [rel] 在磁盘上(经关口读,不跟随链接)与打包的 [expected] 逐字节相同。 */
    internal fun matches(dirs: SandboxDirs, rel: String, expected: ByteArray): Boolean = try {
        dirs.gate.openRead(dirs.skills, rel).use { g -> g.size == expected.size.toLong() && g.read(0, expected.size).contentEquals(expected) }
    } catch (e: SandboxError) {
        false
    } catch (e: IOException) {
        false
    }

    /** 目录里所有文件(相对路径 → 内容);有链接、特殊文件、过大或读不了时为 null(一律当作不一致)。列目录只为找名字,内容经关口读。 */
    private fun onDisk(dirs: SandboxDirs): Map<String, ByteArray>? {
        val base = dirs.skills.toPath()
        if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) return null
        val out = HashMap<String, ByteArray>()
        var total = 0L
        return try {
            Files.walk(base).use { paths ->
                for (p in paths) {
                    if (p == base || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue
                    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) return null
                    val rel = base.relativize(p).joinToString("/")
                    out[rel] = dirs.gate.openRead(dirs.skills, rel).use { g ->
                        total += g.size
                        if (total > MAX_ON_DISK) return null
                        g.read(0, g.size.toInt())
                    }
                }
            }
            out
        } catch (e: SandboxError) {
            null
        } catch (e: IOException) {
            null
        } catch (e: java.io.UncheckedIOException) {
            null
        }
    }

    /** 打包的技能脚本远小于此;沙箱里塞进来的大文件不读进内存,直接当不一致重新同步。 */
    private const val MAX_ON_DISK = 64L shl 20

    internal fun hashOf(files: Map<String, ByteArray>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for ((rel, bytes) in files.toSortedMap()) {
            md.update(rel.toByteArray(Charsets.UTF_8)); md.update(0)
            md.update(bytes.size.toString().toByteArray()); md.update(0)
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
