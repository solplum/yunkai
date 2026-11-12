package com.wangzan101.mika.cap.sandbox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.SANDBOX_PACKAGES
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** 真安装(解自带的运行环境,不联网);装好后在沙箱里跑 Python、Node 与 `mika`。 */
@RunWith(AndroidJUnit4::class)
class InstallDeviceTest {
    private val sb = AndroidSandbox(context, SandboxState.get(context))

    private fun sh(command: String): CommandResult =
        runBlocking { sb.run("install-test", listOf("/bin/sh", "-lc", command), null, 60_000) {} }

    @Test
    fun fullInstallThenPythonAndNode() {
        assertEquals(SandboxStatus.Ready(network = false), SandboxState.get(context).status.value)
        sh("python3 -c 'print(1+1)'").let { assertEquals(it.output, 0, it.exitCode); assertEquals("2", it.output.trim()) }
        sh("node -e 'console.log(2+2)'").let { assertEquals(it.output, 0, it.exitCode); assertEquals("4", it.output.trim()) }
        sh("mika info").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            Json.parseToJsonElement(r.output.trim()).jsonObject // 是 JSON 对象
        }
    }

    /** 装的是自带的 Alpine;包都在 apk 的数据库里,数据库与文件一致(apk-tools 3 在 PRoot 下写不进数据库,所以用 3.22 的 apk-tools 2)。 */
    @Test
    fun pinnedReleaseAndPackagesRecorded() {
        sh("cat /etc/alpine-release").let { assertEquals(it.output, BundledRuntime.VERSION.substringBefore('-'), it.output.trim()) }
        sh("apk --version").let { assertEquals(it.output, 0, it.exitCode); assertTrue(it.output, it.output.startsWith("apk-tools 2.")) }
        sh("apk info -e ${SANDBOX_PACKAGES.joinToString(" ")} 2>/dev/null").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            assertEquals(r.output, SANDBOX_PACKAGES.toSet(), r.output.lines().filter(String::isNotBlank).toSet())
        }
        // 联网关着时 apk 会在标准错误抱怨读不到软件源缓存,只看标准输出
        // 解包器不带扩展属性(设计如此),所以只差扩展属性的条目(代码 x)不算;内容、权限、属主有差仍然算
        sh("apk audit --system 2>/dev/null").let { r ->
            assertEquals(r.output, 0, r.exitCode)
            val drift = r.output.lines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("x ") }
            assertEquals(r.output, emptyList<String>(), drift)
        }
    }

    @Test
    fun networkOffAfterInstall() {
        val r = sh("python3 -c 'import socket; socket.create_connection((\"1.1.1.1\", 443), timeout=5)'")
        assertNotEquals(r.output, 0, r.exitCode)
        assertTrue(r.output, r.output.contains("PermissionError") || r.output.contains("Permission denied"))
        assertEquals(false, r.network)
    }

    companion object {
        private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

        @BeforeClass
        @JvmStatic
        fun install() {
            val state = SandboxState.get(context)
            state.networkAllowed = false
            val installer = RootfsInstaller.create(context)
            runBlocking {
                installer.reset()
                installer.install { _, _ -> }
            }
            assertEquals(InstallStep.Done, state.completedStep())
        }
    }
}
