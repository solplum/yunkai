package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 有的安卓版本给 App 装的 seccomp 策略对新系统调用(io_uring、clone3、faccessat2、epoll_pwait2、rseq……)
 * 发 SIGSYS,有的直接放行。两种平台上沙箱里的程序都不能被信号杀掉:被拦下的调用收到「不支持」(ENOSYS)
 * 走自己的退路,放行的调用拿到内核自己的结果;带路径的调用无论哪种都必须按沙箱内的路径判断。
 * 全部经产品路径 AndroidSandbox.run → mikarun → PRoot。
 */
@RunWith(AndroidJUnit4::class)
class SandboxSyscallFallbackDeviceTest {
    private val sb = AndroidSandbox(SandboxSmokeTest.testDirs, { SandboxStatus.Ready(network = false) }, { AndroidSandbox.dns(context) }, bridges = true)

    private fun sh(command: String, timeoutMs: Long = 60_000): CommandResult =
        runBlocking { sb.run("dev-sigsys", listOf("/bin/sh", "-lc", command), null, timeoutMs) {} }

    @Test fun nodeEvaluatesJs() {
        val r = sh("node -e 'console.log(1+1)'")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("2", r.output.trim())
    }

    @Test fun nodeFsAndTimers() {
        val script = """
            const fs = require('fs');
            fs.writeFileSync('sigsys-node.txt', 'written');
            setTimeout(async () => {
              const text = await fs.promises.readFile('sigsys-node.txt', 'utf8');
              fs.unlinkSync('sigsys-node.txt');
              setImmediate(() => console.log('read:' + text));
            }, 50);
        """.trimIndent()
        val r = sh("cat > sigsys-node.js <<'EOF'\n$script\nEOF\nnode sigsys-node.js; rc=\$?; rm -f sigsys-node.js; exit \$rc")
        assertEquals(r.output, 0, r.exitCode)
        assertEquals("read:written", r.output.trim())
    }

    /** musl 的 faccessat(…, AT_EACCESS) 先试 faccessat2;答案必须和真实权限一致。 */
    @Test fun effectiveAccessChecks() {
        val script = """
            import os
            open('sigsys-acc.txt', 'w').close()
            os.chmod('sigsys-acc.txt', 0o644)
            print(os.access('sigsys-acc.txt', os.R_OK, effective_ids=True),
                  os.access('sigsys-acc.txt', os.X_OK, effective_ids=True),
                  os.access('/bin/sh', os.X_OK, effective_ids=True),
                  os.access('sigsys-missing', os.F_OK, effective_ids=True))
            os.remove('sigsys-acc.txt')
        """.trimIndent()
        val r = sh(
            "cat > sigsys-acc.py <<'EOF'\n$script\nEOF\npython3 sigsys-acc.py; rc=\$?; rm -f sigsys-acc.py; " +
                "touch sigsys-t; [ -r sigsys-t ] && echo test-r-ok; rm -f sigsys-t; exit \$rc",
        )
        assertEquals(r.output, 0, r.exitCode)
        assertEquals(listOf("True False True False", "test-r-ok"), r.output.trim().lines())
    }

    /**
     * 进程活着,每个调用都返回 -1:平台拦下的答 ENOSYS;平台放行的由内核按这里的无效参数报错(EINVAL)。
     * io_uring_setup 由 PRoot 自己答 ENOSYS(io_uring 的路径操作不经系统调用,PRoot 无法转换),与平台无关。
     */
    @Test fun trappedSyscallsReturnEnosys() {
        // io_uring_setup, clone3, epoll_pwait2, rseq(arm64 编号)
        val script = """
            import ctypes, errno
            libc = ctypes.CDLL(None, use_errno=True)
            for nr in (425, 435, 441, 293):
                ctypes.set_errno(0)
                rc = libc.syscall(nr, 0, 0, 0, 0, 0, 0)
                print(nr, rc, errno.errorcode.get(ctypes.get_errno()))
        """.trimIndent()
        val r = sh("cat > sigsys-nr.py <<'EOF'\n$script\nEOF\npython3 sigsys-nr.py; rc=\$?; rm -f sigsys-nr.py; exit \$rc")
        assertEquals(r.output, 0, r.exitCode)
        val lines = r.output.trim().lines()
        assertEquals(r.output, 4, lines.size)
        assertEquals("425 -1 ENOSYS", lines[0])
        for ((line, nr) in lines.drop(1).zip(listOf(435, 441, 293))) {
            assertTrue(r.output, line == "$nr -1 ENOSYS" || line == "$nr -1 EINVAL")
        }
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun prepareRootfs() {
            SandboxSmokeTest.prepareTestSandbox()
        }
    }
}
