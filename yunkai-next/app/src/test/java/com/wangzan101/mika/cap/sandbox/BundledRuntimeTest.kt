package com.wangzan101.mika.cap.sandbox

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.cap.sandbox.SANDBOX_PACKAGES
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** 仓库里的自带运行环境与生成的常量、软件包清单、构建脚本彼此一致,并且真能解开。单元测试的工作目录是 app/。 */
class BundledRuntimeTest {
    @get:Rule val texts = ChineseTexts()

    private val asset = File("src/main/assets/${BundledRuntime.ASSET}")
    private val manifest = Json.parseToJsonElement(File("src/main/assets/runtime/packages.json").readText()).jsonObject

    @Test
    fun assetMatchesGeneratedConstants() {
        assertEquals(BundledRuntime.BYTES, asset.length())
        val sha = MessageDigest.getInstance("SHA-256").digest(asset.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("rerun tools/sandbox/build-runtime.py", BundledRuntime.SHA256, sha)
        assertEquals(BundledRuntime.VERSION, manifest.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun buildScriptInstallsSandboxPackages() {
        val script = File("../tools/sandbox/build-runtime.py").readText()
        val want = Regex("""^WANT = \[(.*)]""", RegexOption.MULTILINE).find(script)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }
        assertEquals(SANDBOX_PACKAGES, want)
        val names = manifest.getValue("packages").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        SANDBOX_PACKAGES.forEach { assertTrue("$it missing from packages.json", it in names) }
    }

    @Test
    fun bundledRuntimeInstalls() = runBlocking {
        val tmp = Files.createTempDirectory("bundled-runtime").toFile()
        val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache/proot-tmp"), File(tmp, "lib"), JVM_GATE, SandboxTree())
        val state = SandboxState(dirs, File(tmp, "files/sandbox-settings.json")) { listOf("arm64-v8a") }
        val bundle = RuntimeBundle(
            BundledRuntime.VERSION, BundledRuntime.SHA256,
            BundledRuntime.FILE_BYTES + BundledRuntime.FILE_BYTES / 4 + (64L shl 20), BundledRuntime.ENTRIES + 1000,
        ) { asset.inputStream() }
        RootfsInstaller(
            dirs, state, bundle, abis = { listOf("arm64-v8a") }, freeBytes = { 8L shl 30 }, selfTest = { 0 to "mikarun ok\n" },
            procReadable = { true }, prepareDev = {}, clientScript = { "#!/bin/sh\n".toByteArray() },
        ).install { _, _ -> }
        assertEquals(SandboxStatus.Ready(network = false), state.status.value)
        for (p in listOf("bin/sh", "usr/bin/python3", "usr/bin/node", "usr/local/bin/mika", "etc/apk/world")) {
            assertTrue(p, Files.exists(File(dirs.rootfs, p).toPath(), LinkOption.NOFOLLOW_LINKS))
        }
        val installed = File(dirs.rootfs, "lib/apk/db/installed").readText()
        SANDBOX_PACKAGES.forEach { assertTrue(it, installed.contains("\nP:$it\n")) }
        assertEquals(BundledRuntime.VERSION.substringBefore('-'), File(dirs.rootfs, "etc/alpine-release").readText().trim())
        com.wangzan101.mika.core.cap.sandbox.RootfsExtractor.deleteTree(tmp)
    }
}
