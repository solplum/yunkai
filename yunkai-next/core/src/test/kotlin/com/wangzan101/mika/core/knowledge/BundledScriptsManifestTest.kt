package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.cap.sandbox.SANDBOX_PACKAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 打包的知识与技能文档里提到的脚本与各自目录的 `scripts/manifest.json` 对得上。 */
class BundledScriptsManifestTest {
    /** 资源根目录(其下是 knowledge/apps、knowledge/sites、skills)。 */
    private val root = File(GuideCatalog::class.java.classLoader.getResource(KnowledgeIndex.RESOURCE)!!.toURI()).parentFile.parentFile

    /** 文件所在的条目目录,如 `knowledge/sites/zhihu.com`、`skills/research`。 */
    private fun entryDir(rel: String): String = rel.split('/').let { p -> p.take(if (p[0] == "knowledge") 3 else 2).joinToString("/") }

    private fun entryFiles(): Sequence<File> = sequenceOf("knowledge/apps", "knowledge/sites", "skills")
        .flatMap { File(root, it).walkTopDown().filter(File::isFile) }
    private val ref = Regex("scripts/[A-Za-z0-9_./-]+\\.(py|sh|js|ts|mjs)")

    /** 条目目录 → 清单;清单文件读不懂时这里就失败。 */
    private val manifests: Map<String, List<ScriptEntry>> by lazy {
        entryFiles().filter { it.name == "manifest.json" && it.parentFile.name == "scripts" }.associate { f ->
            val dir = f.parentFile.parentFile.relativeTo(root).invariantSeparatorsPath
            val text = f.readText()
            val entries = ScriptManifest.parse(text)
            assertTrue("$dir: manifest is empty or unreadable", entries.isNotEmpty())
            // parse 会丢掉不安全的路径;这里要求一项都没丢
            assertEquals("$dir: every entry must be a safe relative path", Regex("\"file\"").findAll(text).count(), entries.size)
            dir to entries
        }
    }

    /** 条目目录 → 文档里提到的脚本(LICENSE 开头的许可证文件不算)。 */
    private fun references(): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        entryFiles().filter { it.extension == "md" && !it.name.startsWith("LICENSE") }.forEach { f ->
            val dir = entryDir(f.relativeTo(root).invariantSeparatorsPath)
            ref.findAll(f.readText()).forEach { out.getOrPut(dir) { mutableSetOf() } += it.value }
        }
        return out
    }

    @Test fun everyReferencedScriptHasManifestEntry() {
        val refs = references()
        // 提到脚本的目录和有清单的目录是同一批
        assertEquals(manifests.keys, refs.keys)
        for ((dir, scripts) in refs) {
            val listed = manifests[dir]?.map { it.file }.orEmpty()
            for (s in scripts) assertTrue("$dir mentions $s but its scripts/manifest.json does not list it", s in listed)
        }
        // 反过来:清单里列的脚本都是文档里提到的
        for ((dir, entries) in manifests) {
            for (e in entries) assertTrue("$dir lists ${e.file} that no document mentions", e.file in refs[dir].orEmpty())
        }
    }

    @Test fun runnableScriptsExist() {
        val runnable = manifests.flatMap { (dir, es) -> es.filter { it.runnable }.map { dir to it } }
        // 随包的脚本文件(索引里各目录 scripts/ 下除清单外的附件)正好是清单里标为可运行的那些
        val shipped = GuideCatalog.loadBundled().rows.flatMap { r ->
            r.files.filter { it.startsWith("scripts/") && it != "scripts/manifest.json" }.map { "${KnowledgeIndex.dirOf(r.kind)}/${r.name}" to it }
        }.toSet()
        assertEquals(shipped, runnable.map { (dir, e) -> dir to e.file }.toSet())
        for ((dir, e) in runnable) {
            val f = File(root, "$dir/${e.file}")
            assertTrue("$dir/${e.file} is marked runnable but missing", f.isFile)
            assertNotNull("$dir/${e.file}: no interpreter for this type", ScriptManifest.interpreterFor(e.file))
            val head = f.readLines().take(3).joinToString("\n")
            assertTrue("$dir/${e.file} needs an Apache-2.0 header", "SPDX-License-Identifier: Apache-2.0" in head)
            assertTrue("$dir/${e.file} needs --help", "Usage:" in f.readText())
        }
        // 不可运行的给出原因;要账号授权的统一说法
        for ((dir, es) in manifests) for (e in es.filter { !it.runnable }) {
            assertTrue("$dir/${e.file} has no reason", !e.reason.isNullOrBlank())
            if ("auth" in e.needs) assertEquals("$dir/${e.file}", AUTH_REASON, e.reason)
        }
        // 脚本目录里只有清单和标为可运行的文件
        for ((dir, es) in manifests) {
            val files = File(root, "$dir/scripts").listFiles()!!.map { "scripts/${it.name}" }.toSet()
            assertEquals(dir, es.filter { it.runnable }.map { it.file }.toSet() + "scripts/manifest.json", files)
        }
    }

    /** 每个随包的脚本都有测试真的跑它:某个测试文件里同时出现它的目录(run_bundled_script 的 source)和脚本路径。 */
    @Test fun everyShippedScriptHasATest() {
        val catalog = GuideCatalog.loadBundled()
        val tests = listOf("src/test", "../app/src/test", "../app/src/androidTest").map(::File)
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "BundledScriptsManifestTest.kt" }.toList() }
            .map { it.readText() }
        assertTrue("no test sources found", tests.isNotEmpty())
        val shipped = catalog.runnableScripts()
        assertEquals(shipped, catalog.scriptFiles().keys.filter { !it.endsWith("manifest.json") }.toSet())
        for (path in shipped) {
            val script = "scripts/" + path.substringAfter("/scripts/")
            val skill = path.substringBefore("/scripts/")
            assertTrue("$path has no test that runs it", tests.any { "\"$skill\"" in it && "\"$script\"" in it })
        }
    }

    @Test fun manifestNeedsAreInstalled() {
        for ((dir, es) in manifests) for (e in es) {
            // 联网与否:needs 里的 net 与 network 字段一致(run_bundled_script 按 network 拦)
            assertEquals("$dir/${e.file}: needs 'net' must match network", "net" in e.needs, e.network)
            if (!e.runnable) continue
            for (n in e.needs) {
                assertTrue("$dir/${e.file}: runnable scripts may only need apk: packages or net, not $n", n == "net" || n.startsWith("apk:"))
                if (n.startsWith("apk:")) assertTrue("$dir/${e.file} needs $n, which SANDBOX_PACKAGES lacks", n.removePrefix("apk:") in SANDBOX_PACKAGES)
            }
        }
    }

    private companion object {
        const val AUTH_REASON = "需要账号授权,Mika 暂不支持"
    }
}
