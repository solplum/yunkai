// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.tool

import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.knowledge.ScriptEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import com.wangzan101.mika.core.cap.sandbox.SandboxTools
import com.wangzan101.mika.core.cap.sandbox.SearchLimits
import com.wangzan101.mika.core.assembly.toolDispatcher

private var seq = 0
private fun call(name: String, args: JsonObject) = Part.ToolCall("c${++seq}", name, args.toString())
private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
private val SANDBOX_NAMES = setOf("run_command", "run_bundled_script", "read_file", "write_file", "search_files", "share_file")

class ToolDispatcherSandboxTest {
    private val sb = FakeSandbox()
    private fun dispatcher(vision: Boolean = false) = toolDispatcher(FakeScreen(), vision, sandbox = sb, workspace = { "conv1" })
    private val ws get() = sb.workspaceDir("conv1")

    @Test fun toolsOfferedWhenDeviceSupported() = runTest {
        assertTrue(toolDispatcher(FakeScreen()).definitions().none { it.name in SANDBOX_NAMES })
        sb.status = SandboxStatus.Unsupported("x86")
        assertTrue(dispatcher().definitions().none { it.name in SANDBOX_NAMES })
        val unsupported = dispatcher().execute(call("run_command", buildJsonObject { put("command", "ls") })).content
        assertEquals("sandbox_unavailable", unsupported.s("error"))
        assertEquals("false", unsupported.s("recoverable"))
        assertEquals(0, sb.waits)
        // 还在准备、没开始、失败过:工具照样提供,模型可以先计划
        for (s in listOf(SandboxStatus.NotInstalled, SandboxStatus.Installing(40, "解包"), SandboxStatus.Broken("坏了"), SandboxStatus.Ready(false))) {
            sb.status = s
            assertEquals("$s", SANDBOX_NAMES, dispatcher().definitions().map { it.name }.filter { it in SANDBOX_NAMES }.toSet())
        }
    }

    @Test fun toolWaitsForPreparation() = runTest {
        var preparing = 0
        val d = toolDispatcher(FakeScreen(), false, sandbox = sb, workspace = { "conv1" }, onSandboxPreparing = { preparing++ })
        sb.status = SandboxStatus.Installing(10, "解包")
        sb.afterWait = SandboxStatus.Ready(network = false)
        val r = d.execute(call("run_command", buildJsonObject { put("command", "ls") })).content
        assertEquals("true", r.s("ok"))
        assertEquals(1, sb.waits)
        assertEquals(1, preparing)
        assertEquals(1, sb.runs.size)
        // 已就绪:不再等
        d.execute(call("run_command", buildJsonObject { put("command", "ls") }))
        assertEquals(1, sb.waits)
    }

    @Test fun preparationNotDoneIsRecoverableError() = runTest {
        sb.status = SandboxStatus.NotInstalled
        sb.afterWait = SandboxStatus.Installing(60, "解包")
        val r = dispatcher().execute(call("run_command", buildJsonObject { put("command", "ls") })).content
        assertEquals("sandbox_unavailable", r.s("error"))
        assertEquals("true", r.s("recoverable"))
        assertTrue(r.s("message"), "not ready yet" in r.s("message"))
        assertTrue(sb.runs.isEmpty())
    }

    @Test fun runCommandWrapsInShell() = runTest {
        sb.next = { CommandResult(0, false, false, 1200, "hi\n", 0, false) }
        val r = dispatcher().execute(call("run_command", buildJsonObject {
            put("command", "echo hi | cat"); put("timeoutSec", 5); put("cwd", "sub/dir")
        })).content
        val run = sb.runs.single()
        assertEquals(listOf("/bin/sh", "-lc", "echo hi | cat"), run.argv)
        assertEquals("conv1", run.workspace)
        assertEquals("sub/dir", run.cwd)
        assertEquals(5000L, run.timeoutMs)
        assertEquals("true", r.s("ok"))
        assertEquals("0", r.s("exitCode"))
        assertEquals("hi\n", r.s("output"))
        assertEquals("false", r.s("outputTruncated"))
        assertEquals("off", r.s("network"))
        assertNull(r["fullOutputPath"])
        // 默认 60 秒,不在工作区下的 cwd 不跑
        dispatcher().execute(call("run_command", buildJsonObject { put("command", "true") }))
        assertEquals(60_000L, sb.runs.last().timeoutMs)
        assertNull(sb.runs.last().cwd)
        val out = dispatcher().execute(call("run_command", buildJsonObject { put("command", "ls"); put("cwd", "../..") })).content
        assertEquals("path_outside_workspace", out.s("error"))
        assertEquals(2, sb.runs.size)
        // 退出码不为 0 或超时:ok=false
        sb.next = { CommandResult(124, true, false, 5000, "", 0, true) }
        val t = dispatcher().execute(call("run_command", buildJsonObject { put("command", "sleep 9") })).content
        assertEquals("false", t.s("ok")); assertEquals("true", t.s("timedOut")); assertEquals("on", t.s("network"))
    }

    @Test fun scriptArgsNotShellInterpreted() = runTest {
        File(sb.skillsDir, "skills/report/scripts").mkdirs()
        File(sb.skillsDir, "skills/report/scripts/tabulate.py").writeText("print(1)")
        sb.manifests["skills/report"] = listOf(ScriptEntry("scripts/tabulate.py", runnable = true))
        val args = listOf("报表 (1).csv", "a;rm -rf /", "\"q\"", "\$(whoami)")
        val r = dispatcher().execute(call("run_bundled_script", buildJsonObject {
            put("source", "skills/report"); put("script", "scripts/tabulate.py"); put("args", JsonArray(args.map(::JsonPrimitive)))
        })).content
        assertEquals(r.toString(), "true", r.s("ok"))
        assertEquals(listOf("/usr/bin/python3", "/opt/mika/skills/skills/report/scripts/tabulate.py") + args, sb.runs.single().argv)
        assertEquals(1, sb.prepares)
    }

    @Test fun tamperedScriptIsRefused() = runTest {
        File(sb.skillsDir, "skills/report/scripts").mkdirs()
        File(sb.skillsDir, "skills/report/scripts/tabulate.py").writeText("import os")
        sb.manifests["skills/report"] = listOf(ScriptEntry("scripts/tabulate.py", runnable = true))
        sb.tampered += "skills/report/scripts/tabulate.py"
        val r = dispatcher().execute(call("run_bundled_script", buildJsonObject { put("source", "skills/report"); put("script", "scripts/tabulate.py") })).content
        assertEquals("script_unavailable", r.s("error"))
        assertTrue(sb.runs.isEmpty())
    }

    @Test fun networkChangeIsNotedOnce() = runTest {
        val d = dispatcher()
        fun cmd() = call("run_command", buildJsonObject { put("command", "true") })
        assertNull(d.execute(cmd()).content["networkNote"])
        sb.status = SandboxStatus.Ready(network = true)
        assertTrue(d.execute(cmd()).content.s("networkNote").contains("turned on"))
        assertNull(d.execute(cmd()).content["networkNote"])
        sb.status = SandboxStatus.Ready(network = false)
        assertTrue(d.execute(call("read_file", buildJsonObject { put("path", "nope.txt") })).content.s("networkNote").contains("turned off"))
    }

    // 沙箱就绪时看个人技能:照常返回正文,不列脚本(个人技能不在沙箱的技能目录里)
    @Test fun personalSkillViewWithSandboxReady() = runTest {
        val catalog = com.wangzan101.mika.core.knowledge.GuideCatalog.withPersonal(
            com.wangzan101.mika.core.knowledge.GuideCatalog.loadBundled(),
            com.wangzan101.mika.core.skills.InMemoryPersonalSkills(com.wangzan101.mika.core.skills.personalSkill("my-trip")),
        )
        val d = toolDispatcher(FakeScreen(), false, catalog, sandbox = sb, workspace = { "conv1" })
        val r = d.execute(call("skill_read", buildJsonObject { put("name", "my-trip") })).content
        assertEquals("true", r.s("ok"))
        assertNull(r["scripts"])
        val bundled = d.execute(call("knowledge_read", buildJsonObject { put("name", "site:zhihu.com") })).content
        assertEquals("true", bundled.s("ok"))
    }

    @Test fun unknownScriptIsUnavailable() = runTest {
        File(sb.skillsDir, "skills/report/scripts").mkdirs()
        File(sb.skillsDir, "skills/report/scripts/fetch.mjs").writeText("")
        File(sb.skillsDir, "skills/report/scripts/login.py").writeText("")
        sb.manifests["skills/report"] = listOf(
            ScriptEntry("scripts/fetch.mjs", runnable = true, network = true),
            ScriptEntry("scripts/login.py", runnable = false, reason = "需要账号授权"),
        )
        fun script(s: String) = call("run_bundled_script", buildJsonObject { put("source", "skills/report"); put("script", s) })
        val d = dispatcher()
        val missing = d.execute(script("scripts/nope.py")).content
        assertEquals("script_unavailable", missing.s("error"))
        val auth = d.execute(script("scripts/login.py")).content
        assertEquals("script_unavailable", auth.s("error"))
        assertTrue(auth.s("message").contains("需要账号授权"))
        // 断网时需要联网的脚本不可运行
        val net = d.execute(script("scripts/fetch.mjs")).content
        assertEquals("script_unavailable", net.s("error"))
        val escape = d.execute(script("../../../../bin/sh")).content
        assertEquals("script_unavailable", escape.s("error"))
        assertTrue(sb.runs.isEmpty())
        sb.status = SandboxStatus.Ready(network = true)
        assertEquals("true", d.execute(script("scripts/fetch.mjs")).content.s("ok"))
        assertEquals(listOf("/usr/bin/node", "/opt/mika/skills/skills/report/scripts/fetch.mjs"), sb.runs.single().argv)
    }

    @Test fun writeOutsideWorkspaceRejected() = runTest {
        val d = dispatcher()
        fun write(p: String) = call("write_file", buildJsonObject { put("path", p); put("content", "x") })
        for (p in listOf("/etc/passwd", "../x", "/opt/mika/skills/app/a/SKILL.md")) {
            assertEquals(p, "path_outside_workspace", d.execute(write(p)).content.s("error"))
        }
        val outside = File(sb.root, "outside").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(ws, "link").toPath(), outside.toPath())
        assertEquals("path_outside_workspace", d.execute(write("link/a.txt")).content.s("error"))
        assertFalse(File(outside, "a.txt").exists())
        assertEquals("path_outside_workspace", d.execute(call("read_file", buildJsonObject { put("path", "link/a.txt") })).content.s("error"))

        val ok = d.execute(call("write_file", buildJsonObject { put("path", "out/notes.txt"); put("content", "第一行\n") })).content
        assertEquals(ok.toString(), "true", ok.s("ok"))
        d.execute(call("write_file", buildJsonObject { put("path", "/workspace/out/notes.txt"); put("content", "第二行\n"); put("append", true) }))
        assertEquals("第一行\n第二行\n", File(ws, "out/notes.txt").readText())
        val read = d.execute(call("read_file", buildJsonObject { put("path", "out/notes.txt") })).content
        assertEquals("第一行\n第二行\n", read.s("content"))
        val found = d.execute(call("search_files", buildJsonObject { put("pattern", "第二") })).content
        val m = found["matches"]!!.jsonArray.single().jsonObject
        assertEquals("/workspace/out/notes.txt", m.s("file")); assertEquals("2", m.s("line"))
        val shared = d.execute(call("share_file", buildJsonObject { put("path", "out/notes.txt"); put("name", "笔记.txt") })).content
        assertEquals("Download/Yunkai/笔记.txt", shared.s("savedAs"))
        assertEquals(File(ws, "out/notes.txt").canonicalFile, sb.shared.single().first.canonicalFile)
    }

    @Test fun truncatedOutputSavedAndPathReturned() = runTest {
        val long = "A".repeat(4000) + "B".repeat(10_000) + "C".repeat(12_000)
        sb.next = { CommandResult(0, false, false, 50, long, 0, false) }
        val r = dispatcher().execute(call("run_command", buildJsonObject { put("command", "cat big") })).content
        assertEquals("true", r.s("outputTruncated"))
        val path = r.s("fullOutputPath")
        assertTrue(path, path.startsWith("/workspace/.mika/out-"))
        assertEquals(long, File(ws, path.removePrefix("/workspace/")).readText())
        assertTrue(r.s("output").contains("省略 10000 字"))
        assertTrue(r.s("output").length < 16_100)
        // 1 MB 之外丢掉的也算截断
        sb.next = { CommandResult(0, false, false, 50, "short", 2048, false) }
        val d = dispatcher().execute(call("run_command", buildJsonObject { put("command", "yes") })).content
        assertEquals("true", d.s("outputTruncated"))
        assertEquals("2048", d.s("droppedBytes"))
    }

    @Test fun storageLowRefuses() = runTest {
        sb.free = 400L shl 20
        val r = dispatcher().execute(call("run_command", buildJsonObject { put("command", "ls") })).content
        assertEquals("storage_low", r.s("error"))
        assertTrue(sb.runs.isEmpty())
        sb.free = 10L shl 30
        sb.used = 5L shl 30
        val w = dispatcher().execute(call("write_file", buildJsonObject { put("path", "a.txt"); put("content", "x") })).content
        assertEquals("storage_low", w.s("error"))
        assertFalse(File(ws, "a.txt").exists())
        sb.used = 3L shl 30
        val hinted = dispatcher().execute(call("run_command", buildJsonObject { put("command", "ls") })).content
        assertEquals("true", hinted.s("ok"))
        assertTrue(hinted.s("hint").contains("2 GB"))
    }

    @Test fun binaryReadIsNotText() = runTest {
        File(ws, "data.bin").writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0, 0, -1, -2, 7))
        File(ws, "latin1.txt").writeBytes(byteArrayOf(0x63, 0x61, 0x66, 0xe9.toByte(), 0x0a))
        val png = byteArrayOf(-119, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0)
        File(ws, "chart.png").writeBytes(png)
        val d = dispatcher()
        suspend fun read(p: String, disp: ToolDispatcher = d) = disp.execute(call("read_file", buildJsonObject { put("path", p) }))
        val bin = read("data.bin").content
        assertEquals("not_text", bin.s("error")); assertEquals("9", bin.s("size")); assertTrue(bin.containsKey("type"))
        assertEquals("not_text", read("latin1.txt").content.s("error"))
        // 看不了图的模型:图片也只给大小和类型
        val img = read("chart.png")
        assertEquals("not_text", img.content.s("error")); assertEquals("image/png", img.content.s("type"))
        assertNull(img.image)
        val seen = read("chart.png", dispatcher(vision = true))
        assertEquals("true", seen.content.s("ok"))
        assertArrayEquals(png, seen.image)
        assertEquals("file_not_found", read("none.txt").content.s("error"))
    }

    @Test fun readFileUsesByteOffsetAndLimit() = runTest {
        File(ws, "t.txt").writeText("你好世界abc")
        val d = dispatcher()
        // 第 4 个字节落在「好」中间:跳过残字节,截断处的半个字留给下一次
        val r = d.execute(call("read_file", buildJsonObject { put("path", "t.txt"); put("offset", 4); put("limit", 7) })).content
        assertEquals("世", r.s("content"))
        assertEquals("false", r.s("eof"))
        assertEquals("9", r.s("nextOffset"))
    }

    @Test fun outputNotSavedThroughSymlinkedMikaDir() = runTest {
        val outside = File(sb.root, "outside-mika").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(ws, ".mika").toPath(), outside.toPath())
        sb.next = { CommandResult(0, false, false, 50, "x".repeat(20_000), 0, false) }
        val r = dispatcher().execute(call("run_command", buildJsonObject { put("command", "cat big") })).content
        assertEquals("true", r.s("ok"))
        assertEquals("true", r.s("outputTruncated"))
        assertNull(r["fullOutputPath"])
        assertTrue(outside.list()!!.isEmpty())
    }

    @Test fun specialFilesAreNotAFile() = runTest {
        val fifo = File(ws, "pipe")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.path).start().waitFor())
        val d = dispatcher()
        assertEquals("not_a_file", d.execute(call("read_file", buildJsonObject { put("path", "pipe") })).content.s("error"))
        assertEquals("not_a_file", d.execute(call("write_file", buildJsonObject { put("path", "pipe"); put("content", "x") })).content.s("error"))
        assertEquals("not_a_file", d.execute(call("share_file", buildJsonObject { put("path", "pipe") })).content.s("error"))
        File(ws, "dir").mkdirs()
        assertEquals("not_a_file", d.execute(call("read_file", buildJsonObject { put("path", "dir") })).content.s("error"))
        assertEquals("not_a_file", d.execute(call("write_file", buildJsonObject { put("path", "dir"); put("content", "x") })).content.s("error"))
        // 搜索跳过 FIFO,不会卡住
        assertEquals("true", d.execute(call("search_files", buildJsonObject { put("pattern", "x") })).content.s("ok"))
    }

    @Test fun shareNameMustBePlain() = runTest {
        File(ws, "a.txt").writeText("a")
        val d = dispatcher()
        for (n in listOf("   ", "a\u0000b", "a\nb", "a\u007fb", "x/y", "..", "report\u202Efdp.exe", "a\u200Bb", "a\u2028b", "a\u0085b")) {
            val r = d.execute(call("share_file", buildJsonObject { put("path", "a.txt"); put("name", n) })).content
            assertEquals(n, "invalid_arguments", r.s("error"))
        }
        assertTrue(sb.shared.isEmpty())
    }

    @Test fun searchStopsOnSlowRegexAndLimits() = runTest {
        // 嵌套的懒惰重复:回溯量随长度多项式爆炸(JDK 对 (a+)+ 一类已有优化,用这种)
        File(ws, "evil.txt").writeText((1..60).joinToString(","))
        val tools = SandboxTools(sb, { "conv1" }, false, SearchLimits(timeMs = 200))
        val def = com.wangzan101.mika.core.cap.sandbox.SearchFiles.def
        val started = System.nanoTime()
        val slow = Args(def, buildJsonObject { put("pattern", "(.*?,){15}P") })
        val r = tools.run { ws, _ -> searchFiles(slow, ws) }.content
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        assertEquals("true", r.s("truncated"))
        assertTrue(r.s("hint").contains("seconds"))
        for (i in 1..5) File(ws, "f$i.txt").writeText("hit")
        val few = SandboxTools(sb, { "conv1" }, false, SearchLimits(maxFiles = 2))
        val hit = Args(def, buildJsonObject { put("pattern", "hit") })
        val capped = few.run { ws, _ -> searchFiles(hit, ws) }.content
        assertEquals("true", capped.s("truncated"))
        assertTrue(capped["matches"]!!.jsonArray.size <= 2)
    }

    @Test fun sandboxErrorsBecomeToolErrors() = runTest {
        sb.next = { throw com.wangzan101.mika.core.workspace.SandboxError("sandbox_network_isolation_unavailable", "seccomp missing") }
        val r = dispatcher().execute(call("run_command", buildJsonObject { put("command", "ls") })).content
        assertEquals("sandbox_network_isolation_unavailable", r.s("error"))
        assertEquals("false", r.s("recoverable"))
    }

    @Test fun installPackagesAreNotShared() = runTest {
        File(ws, "app.apk").writeText("PK")
        File(ws, "notes.txt").writeText("n")
        val d = dispatcher()
        assertEquals("share_refused_type", d.execute(call("share_file", buildJsonObject { put("path", "app.apk") })).content.s("error"))
        assertEquals("share_refused_type", d.execute(call("share_file", buildJsonObject { put("path", "notes.txt"); put("name", "x.APKS") })).content.s("error"))
        assertTrue(sb.shared.isEmpty())
    }

    @Test fun longNamesFitInBytesAndKeepExtension() {
        val long = "长".repeat(200) + ".csv"
        val fit = com.wangzan101.mika.core.cap.sandbox.ShareRules.fitName(long)
        assertTrue(fit.endsWith(".csv"))
        assertTrue(fit.toByteArray().size <= com.wangzan101.mika.core.cap.sandbox.ShareRules.NAME_MAX_BYTES)
        assertEquals("short.txt", com.wangzan101.mika.core.cap.sandbox.ShareRules.fitName("short.txt"))
        val noExt = com.wangzan101.mika.core.cap.sandbox.ShareRules.fitName("名".repeat(200))
        assertTrue(noExt.toByteArray().size <= com.wangzan101.mika.core.cap.sandbox.ShareRules.NAME_MAX_BYTES)
        assertEquals("abc.d", com.wangzan101.mika.core.cap.sandbox.ShareRules.fitName("abcdefgh.d", maxChars = 5))
    }

    /** 沙箱工具产出的事实(命令输出、读到的文件、写入与导出的路径)进核查证据;命令进动作清单。 */
    @Test fun sandboxFactsFeedTheVerifierEvidence() = runTest {
        val d = dispatcher()
        sb.next = { CommandResult(0, false, false, 10, "1287 data.csv\n", 0, false) }
        d.execute(call("run_command", buildJsonObject { put("command", "wc -l data.csv") }))
        d.execute(call("write_file", buildJsonObject { put("path", "out/r.txt"); put("content", "报告正文") }))
        d.execute(call("read_file", buildJsonObject { put("path", "out/r.txt") }))
        d.execute(call("share_file", buildJsonObject { put("path", "out/r.txt") }))
        val e = d.evidence()
        // 命令是助手写的:记在来处里,不当证据
        assertTrue(e.ledger.all.toString(), e.ledger.all.any { it.where.orEmpty().contains("wc -l data.csv") && "1287 data.csv" in it.text && "wc -l" !in it.text })
        // 写进去又读回来的是助手自己写的字:只记写入的路径,读回的内容不进证据
        assertTrue(e.read(ObsKind.Output).any { "/workspace/out/r.txt" in it })
        assertFalse(e.read(ObsKind.Output).toString(), e.read(ObsKind.Output).any { "报告正文" in it })
        assertTrue(e.read(ObsKind.Output).any { "Download/Yunkai/r.txt" in it })
        assertTrue(e.actions.toString(), e.actions.any { it.contains("run_command(wc -l data.csv)") })
        assertTrue(e.actions.any { it.contains("share_file(out/r.txt)") })
        assertFalse(e.nothingRead)
        // 很长的输出按头尾截短
        sb.next = { CommandResult(0, false, false, 10, "头" + "x".repeat(20_000) + "尾", 0, false) }
        d.execute(call("run_command", buildJsonObject { put("command", "cat big") }))
        val last = d.evidence().read(ObsKind.Output).last()
        assertTrue(last.length < 4_200 && "尾" in last)
    }

    /** 工具往工作区写(write_file、存完整输出)要拿和沙箱同一把树锁:重置/安装(exclusive)进行中不能交错。 */
    @Test fun workspaceWritesWaitForTheSandboxTreeLock() = kotlinx.coroutines.runBlocking {
        sb.next = { CommandResult(0, false, false, 50, "A".repeat(30_000), 0, false) }
        val calls = listOf(
            call("write_file", buildJsonObject { put("path", "locked.txt"); put("content", "x") }),
            call("run_command", buildJsonObject { put("command", "cat big") }),
        )
        for (c in calls) {
            val held = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val holder = launch(kotlinx.coroutines.Dispatchers.Default) {
                sb.tree.exclusive { held.complete(Unit); release.await() }
            }
            held.await()
            val tool = async(kotlinx.coroutines.Dispatchers.Default) { dispatcher().execute(c) }
            kotlinx.coroutines.delay(300)
            assertFalse("${c.name} must wait for the tree lock", tool.isCompleted)
            release.complete(Unit)
            kotlinx.coroutines.withTimeout(5_000) { tool.await(); holder.join() }
        }
        assertEquals("x", File(ws, "locked.txt").readText())
    }
}
