package com.wangzan101.mika.cap.sandbox

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.RootfsExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.tukaani.xz.XZInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 沙箱原生程序真机冒烟:启动器自检、PRoot 下跑 busybox 与 shell 管道。
 * 决定「nativeLibraryDir 里的 PRoot + 载入器」方案在真机上能否成立。根文件系统用随 APK 自带的那份。
 */
@RunWith(AndroidJUnit4::class)
class SandboxSmokeTest {

    @Test
    fun launcherSelfTestPasses() {
        val r = exec(listOf(lib("libmikarun.so"), "--self-test"))
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("mikarun ok", r.output.lines().first())
    }

    @Test
    fun busyboxEchoUnderProot() {
        val r = execInRootfs("/bin/busybox", "echo", "ok")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("ok", r.output.trim())
    }

    @Test
    fun shellPipelineWorks() {
        val r = execInRootfs("/bin/sh", "-c", "echo a | tr a b")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("b", r.output.trim())
    }

    private data class Result(val exitCode: Int, val output: String)

    private fun execInRootfs(vararg command: String): Result = exec(
        listOf(
            lib("libmikarun.so"), "--",
            lib("libproot.so"), "--kill-on-exit", "--link2symlink", "-0",
            "-r", rootfs.absolutePath,
        ) + ProotDevices.bindArgs() + listOf("-b", "/proc", "-b", "/sys", "-w", "/") + command,
    )

    private fun exec(cmd: List<String>): Result {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            clear()
            put("PROOT_LOADER", lib("libproot_loader.so"))
            put("PROOT_TMP_DIR", prootTmp.absolutePath)
            put("HOME", "/root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("LANG", "C.UTF-8")
            put("TMPDIR", "/tmp")
        }
        val p = pb.start()
        // 另起线程读输出,否则命令卡住时 readText 会一直阻塞,超时判断就失效了
        var out = ""
        val reader = thread { out = p.inputStream.bufferedReader().readText() }
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            fail("command timed out: $cmd")
        }
        reader.join()
        Log.i(TAG, "exit=${p.exitValue()} cmd=$cmd output=$out")
        return Result(p.exitValue(), out)
    }

    companion object {
        private const val TAG = "SandboxSmokeTest"
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
        internal val nativeLibDir get() = context.applicationInfo.nativeLibraryDir
        internal val rootfs get() = File(context.filesDir, "sandbox-smoke")
        internal val prootTmp get() = File(context.cacheDir, "proot-tmp")

        internal fun lib(name: String) = File(nativeLibDir, name).absolutePath

        /** 自己解根文件系统的设备测试用这套目录,跟真正装好的沙箱(files/sandbox)分开:测试不删也不改它。 */
        internal val testDirs get() = SandboxDirs(File(context.filesDir, "sandbox-test"), File(context.cacheDir, "proot-tmp-test"), File(nativeLibDir), AndroidFdSys.gate, SandboxOwner.of(context).sandboxTree)

        /** 准备 [testDirs]:解好根文件系统,建好要绑定的目录(技能目录缺了 PRoot 会往输出里写警告)。 */
        internal fun prepareTestSandbox(): SandboxDirs = testDirs.also {
            it.skills.mkdirs()
            it.tmp.mkdirs()
            ensureRootfs(it.rootfs)
        }

        @BeforeClass
        @JvmStatic
        fun prepareRootfs() {
            Log.i(TAG, "nativeLibraryDir=$nativeLibDir")
            File(nativeLibDir).listFiles()?.forEach { Log.i(TAG, "  ${it.name} ${it.length()} bytes") }
            prootTmp.mkdirs()
            ensureRootfs(rootfs)
        }

        /** 把随 APK 自带的运行环境解到 [dest](不联网);同一版本已解好的不重做。[dest] 不能是装好的沙箱。 */
        internal fun ensureRootfs(dest: File) {
            check(dest.canonicalPath != SandboxDirs(context).rootfs.canonicalPath) { "tests must not unpack over the installed sandbox" }
            val marker = File(dest, ".smoke-ready")
            if (marker.isFile && marker.readText() == BundledRuntime.VERSION) {
                ProotDevices.prepareRootfsDev(dest, AndroidFdSys.gate)
                return
            }
            if (dest.exists()) RootfsExtractor.deleteTree(dest)
            context.assets.open(BundledRuntime.ASSET).use { RootfsExtractor.extractTar(XZInputStream(it.buffered()), dest) }
            ProotDevices.prepareRootfsDev(dest, AndroidFdSys.gate)
            marker.writeText(BundledRuntime.VERSION)
        }
    }
}
