package com.wangzan101.mika.cap.sandbox

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SkillsSyncTest {
    @get:Rule val texts = ChineseTexts()

    private val tmp: File = Files.createTempDirectory("skills-sync").toFile()
    private val dirs = SandboxDirs(File(tmp, "files/sandbox"), File(tmp, "cache"), File(tmp, "lib"), JVM_GATE, SandboxTree())

    private val v1 = mapOf(
        "skills/report/scripts/manifest.json" to """[{"file":"scripts/tabulate.py","runnable":true}]""".toByteArray(),
        "skills/report/scripts/tabulate.py" to "print(1)".toByteArray(),
    )

    @Test fun copiesOnceAndReplacesWhenContentChanges() = runBlocking {
        SkillsSync.sync(dirs, v1)
        val py = File(dirs.skills, "skills/report/scripts/tabulate.py")
        assertEquals("print(1)", py.readText())
        // 打包的脚本变了:整体换上新的,旧的不留
        val v2 = mapOf("skills/report/scripts/manifest.json" to "[]".toByteArray(), "skills/other/scripts/x.sh" to "echo".toByteArray())
        SkillsSync.sync(dirs, v2)
        assertFalse(py.exists())
        assertTrue(File(dirs.skills, "skills/other/scripts/x.sh").isFile)
    }

    @Test fun tamperedFilesAreResynced() = runBlocking {
        SkillsSync.sync(dirs, v1)
        val py = File(dirs.skills, "skills/report/scripts/tabulate.py")
        // 沙箱里的命令改了脚本:下次同步换回原样
        py.writeText("import os; os.system('evil')")
        SkillsSync.sync(dirs, v1)
        assertEquals("print(1)", py.readText())
        // 多出来的文件也清掉
        val extra = File(dirs.skills, "skills/report/scripts/extra.py").apply { writeText("x") }
        SkillsSync.sync(dirs, v1)
        assertFalse(extra.exists())
        // 换成链接的也算不一致
        py.delete()
        java.nio.file.Files.createSymbolicLink(py.toPath(), File(tmp, "elsewhere").toPath())
        SkillsSync.sync(dirs, v1)
        assertTrue(java.nio.file.Files.isRegularFile(py.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("print(1)", py.readText())
    }

    @Test fun pathsOutsideSkillsAreSkipped() = runBlocking {
        SkillsSync.sync(dirs, mapOf("../../escape.txt" to "x".toByteArray(), "skills/a/scripts/manifest.json" to "[]".toByteArray()))
        assertFalse(File(dirs.root, "escape.txt").exists())
        assertFalse(File(tmp, "files/escape.txt").exists())
        assertTrue(File(dirs.skills, "skills/a/scripts/manifest.json").isFile)
    }

    @Test fun manifestAndScriptsCheckedAgainstBundledCopy() = runBlocking {
        val synced = mutableListOf<Int>()
        val sb = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() },
            sync = { synced += 1; SkillsSync.sync(dirs, v1) }, skillFiles = { v1 })
        sb.prepare()
        assertEquals(listOf("scripts/tabulate.py"), sb.scriptManifest("skills/report").map { it.file })
        assertTrue(sb.scriptManifest("skills/none").isEmpty())
        assertTrue(sb.scriptManifest("../../../etc").isEmpty())
        // 沙箱里改了清单:不起作用,读的是打包的那份
        File(dirs.skills, "skills/report/scripts/manifest.json").writeText("""[{"file":"scripts/evil.py","runnable":true}]""")
        assertEquals(listOf("scripts/tabulate.py"), sb.scriptManifest("skills/report").map { it.file })
        // 改过的脚本:重新同步后与打包的一致,照常可跑
        File(dirs.skills, "skills/report/scripts/tabulate.py").writeText("evil")
        assertTrue(sb.verifyScript("skills/report", "scripts/tabulate.py"))
        assertEquals("print(1)", File(dirs.skills, "skills/report/scripts/tabulate.py").readText())
        // 恢复不了(同步不起作用)就拒绝;打包里没有的脚本也拒绝
        val stuck = AndroidSandbox(dirs, { SandboxStatus.Ready(false) }, { emptyList() }, skillFiles = { v1 })
        File(dirs.skills, "skills/report/scripts/tabulate.py").writeText("evil")
        assertFalse(stuck.verifyScript("skills/report", "scripts/tabulate.py"))
        assertFalse(sb.verifyScript("skills/report", "scripts/other.py"))
    }

    @Test fun cardLineForSandboxSteps() {
        assertEquals("正在处理文件:python3 sum.py", com.wangzan101.mika.ui.StatusCopy.sandboxCommand("python3   sum.py", network = false))
        assertEquals("正在处理文件:${"x".repeat(40)}… · 可联网", com.wangzan101.mika.ui.StatusCopy.sandboxCommand("x".repeat(50), network = true))
        assertEquals("正在运行脚本", com.wangzan101.mika.ui.StatusCopy.sandboxScript(network = false))
        assertEquals("第 3/10 页", com.wangzan101.mika.ui.StatusCopy.sandboxProgress("第 3/10 页", network = false))
        // 命令里的零宽字符、改写方向的字符不上卡片
        assertEquals("正在处理文件:rm a.txt", com.wangzan101.mika.ui.StatusCopy.sandboxCommand("rm \u202Ea\u200B.txt", network = false))
    }
}
