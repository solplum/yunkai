// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.knowledge

import com.wangzan101.mika.core.agent.KNOWLEDGE_BODY_LIMIT
import com.wangzan101.mika.core.agent.KNOWLEDGE_TRUNCATED_HINT
import com.wangzan101.mika.core.agent.clipKnowledge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.agent.GuideRecommender
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.tool.FakeScreen
import java.io.File
import com.wangzan101.mika.core.assembly.toolDispatcher

/** 随 App 打包的知识与技能:构建期校验在测试里再跑一遍,并核对运行时读到的内容。 */
class BundledKnowledgeTest {
    private val bundled by lazy { KnowledgeStore.loadBundled() }
    private val catalog by lazy { GuideCatalog.loadBundled() }
    private val resources = File("src/main/resources")

    @Test fun bundledTreesPassValidationAndIndexIsCurrent() {
        val r = KnowledgeIndex.build(resources)
        assertTrue(r.errors.joinToString("\n"), r.errors.isEmpty())
        assertEquals(mapOf("app" to 18, "site" to 12, "skill" to 6), r.entries.groupingBy { it.kind }.eachCount())
        // 类路径上的索引就是按当前文件生成的那份
        assertEquals(r.entries, catalog.rows)
    }

    @Test fun sitePaceFollowsTheSiteKnowledge() {
        assertEquals("careful", catalog.sitePace("www.zhipin.com"))
        assertEquals(null, catalog.sitePace("example.com"))
    }

    @Test fun everyEntryHasABody() {
        assertEquals(30, bundled.size)
        assertTrue(bundled.all { !it.body.isNullOrBlank() && it.body!!.length <= KnowledgeIndex.BODY_LIMIT })
        assertTrue(bundled.map { it.name }.containsAll(listOf("wechat", "xianyu", "android-settings", "zhihu.com", "weixin.sogou.com")))
    }

    @Test fun onlyDocumentsAndManifestListedScripts() {
        val roots = listOf("knowledge/apps", "knowledge/sites", "skills").map { File(resources, it) }
        val others = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension !in setOf("md", "json") }.toList() }
        assertTrue(others.toString(), others.all { it.parentFile.name == "scripts" && File(it.parentFile, "manifest.json").isFile })
        // 英文副本不再随 App 打包
        assertTrue(roots.none { r -> r.walkTopDown().any { it.name.endsWith(".en.md") } })
    }

    @Test fun aliasMapsToItsOwnPackage() {
        val store = KnowledgeStore(bundled)
        assertEquals("com.taobao.idlefish", store.packageFor("闲鱼"))
        assertEquals("com.tencent.mm", store.packageFor("微信"))
    }

    @Test fun longBodyTruncatedWithHint() {
        val clipped = clipKnowledge("字".repeat(KNOWLEDGE_BODY_LIMIT + 500))
        assertTrue(clipped.startsWith("字".repeat(KNOWLEDGE_BODY_LIMIT)))
        assertTrue(clipped.endsWith(KNOWLEDGE_TRUNCATED_HINT))
        assertEquals(KNOWLEDGE_BODY_LIMIT + 1 + KNOWLEDGE_TRUNCATED_HINT.length, clipped.length)
        assertEquals("短", clipKnowledge("短"))
    }

    @Test fun findSkillsMatchesTaskSkill() {
        val hits = catalog.find("发消息")
        assertTrue(hits.size <= 10)
        assertTrue(hits.any { it.kind == "skill" && it.name == "messaging" })
        assertTrue(catalog.find("微信", kind = "app").all { it.kind == "app" })
        assertTrue(catalog.linkedSkills("wechat").any { it.name == "messaging" })
        assertTrue(catalog.linkedSkills("zhihu.com").any { it.name == "info-research" })
    }

    @Test fun skillViewReturnsAttachment() {
        val v = catalog.view("site:weixin.sogou.com", "references/article-links.md") as GuideView.Found
        assertEquals("references/article-links.md", v.file)
        assertTrue(v.content.isNotBlank() && !v.truncated)
        val app = catalog.view("wechat") as GuideView.Found
        assertEquals("APP.md", app.file)
        assertTrue(app.content.contains("微信"))
        assertTrue(app.content.startsWith(UNAVAILABLE_TOOLS_NOTE + "\n"))
        assertFalse(app.content.contains("\n---\n"))
        val site = catalog.view("site:weixin.sogou.com") as GuideView.Found
        assertTrue("references/article-links.md" in site.info.files)
        assertTrue((catalog.view("weixin.sogou.com", "references/article-links.md") as GuideView.Found).content.isNotBlank())
        assertEquals("SKILL.md", (catalog.view("skill:messaging") as GuideView.Found).file)
    }

    @Test fun onlyAppSiteAndSkillKindsAnywhere() {
        assertEquals(setOf("app", "site", "skill"), catalog.rows.map { it.kind }.toSet())
        val defs = toolDispatcher(FakeScreen(), skills = catalog, sandbox = FakeSandbox()).definitions().joinToString("\n")
        val index = File(GuideCatalog::class.java.classLoader.getResource(KnowledgeIndex.RESOURCE)!!.toURI()).readText()
        val lines = GuideRecommender.recommend(catalog, "帮我回消息,顺便查下天气").let { r -> listOfNotNull(r.index, r.personal?.let { it.head + it.body }).joinToString("\n") }
        for (text in listOf(defs, index, lines)) for (old in listOf("domain:", "domains/", "domain/", "\"domain\"", "device:", "devices/", "\"base\"")) {
            assertFalse("found $old", text.contains(old))
        }
        assertTrue(lines.contains("- skill:"))
        assertTrue(catalog.scriptFiles().keys.all { it.substringBefore('/') in setOf("apps", "sites", "skills") })
    }

    @Test fun skillViewUnknownIsError() {
        assertEquals("skill_not_found", (catalog.view("nope-nothing") as GuideView.Failed).error)
        assertEquals("skill_not_found", (catalog.view("site:wechat") as GuideView.Failed).error)
        assertEquals("file_not_found", (catalog.view("app:wechat", "../x.md") as GuideView.Failed).error)
    }

    @Test fun skillsAreNeverInjectedByTaskMatch() {
        val hit = KnowledgeStore(bundled).matchTask("帮我发微信", 10)
        assertTrue(hit.all { e -> catalog.items.any { it.kind in setOf("app", "site") && it.name == e.name } })
    }
}
