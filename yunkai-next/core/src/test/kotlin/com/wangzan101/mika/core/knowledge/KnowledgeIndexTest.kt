package com.wangzan101.mika.core.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KnowledgeIndexTest {
    private fun errors(vararg files: Pair<String, String>): List<String> {
        val root = Files.createTempDirectory("kn").toFile().apply { deleteOnExit() }
        files.forEach { (p, t) -> File(root, p).apply { parentFile.mkdirs() }.writeText(t) }
        return KnowledgeIndex.build(root).errors
    }

    private fun assertError(part: String, vararg files: Pair<String, String>) {
        val e = errors(*files)
        assertTrue("expected an error with '$part', got $e", e.any { part in it })
    }

    @Test fun validTreeIndexesAllKinds() {
        val loader = fixtureLoader(mapOf(
            appMd("shop", listOf("com.shop"), sites = listOf("shop.com"), extra = "package_aliases: [\"小店 | com.shop\"]\n"),
            siteMd("shop.com", apps = listOf("shop"), body = "看 `references/a.md`", domains = listOf("shop.com", "www.shop.cn")),
            "knowledge/sites/shop.com/references/a.md" to "附件",
            skillMd("buy", apps = listOf("shop"), sites = listOf("shop.com"), extra = "terms: [购物]\n"),
        ))
        val rows = GuideCatalog.loadBundled(loader).rows
        assertEquals(listOf("app:shop", "site:shop.com", "skill:buy"), rows.map { "${it.kind}:${it.name}" })
        assertEquals(mapOf("小店" to "com.shop"), rows[0].packageAliases)
        assertEquals(listOf("购物"), rows[2].terms)
        assertEquals(listOf("shop.com", "shop.cn"), rows[1].domains)
        assertEquals(listOf("references/a.md"), rows[1].files)
    }

    @Test fun requiredFieldsAndLimits() {
        assertError("缺少头部", "knowledge/apps/a/APP.md" to "没有头部")
        assertError("必须与目录名", appMd("a", listOf("p.a")).let { (p, t) -> p.replace("/a/", "/b/") to t })
        assertError("缺少 packages", appMd("a", emptyList()))
        assertError("不认得的键 assistent", appMd("a", listOf("p.a"), extra = "assistent: 小助手\n"))
        // 迁移来的元数据键已去掉,写了就报
        listOf("risk: read", "status: draft", "updated: 2026-10-24", "verified_on: x")
            .forEach { assertError("不认得的键 ${it.substringBefore(':')}", appMd("a", listOf("p.a"), extra = "$it\n")) }
        assertError("terms 只有技能能写", appMd("a", listOf("p.a"), extra = "terms: [购物]\n"))
        assertError("terms 只有技能能写", siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "mode: desktop\nterms: [购物]\n") })
        assertError("description 超过", appMd("a", listOf("p.a")).let { (p, t) -> p to t.replace("description: a 的说明", "description: " + "长".repeat(61)) })
        assertError("超过 2500", appMd("a", listOf("p.a"), body = "字".repeat(2501)))
        assertError("mode 只能是", siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "") })
        // pace:只有网站能写,只认 careful
        assertError("pace 只能是", siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "mode: desktop\npace: slow\n") })
        assertError("pace 只有网站能写", appMd("a", listOf("p.a"), extra = "pace: careful\n"))
        val careful = siteMd("a.com").let { (p, t) -> p to t.replace("mode: desktop\n", "mode: desktop\npace: careful\n") }
        assertTrue(errors(careful).isEmpty())
        assertError("要在 domains 里", siteMd("a.com", domains = listOf("b.com")))
        assertError("缺少 SKILL.md", "skills/x/notes.md" to "x")
        assertError("links 里的", appMd("a", listOf("p.a"), extra = "links: [\"文件 | file:///sdcard/a\"]\n"))
        // 技能正文没有上限
        assertTrue(errors(skillMd("long", body = "字".repeat(5000))).isEmpty())
    }

    @Test fun uniqueNamesPackagesAndDomains() {
        assertError("包名 p.a 被多个 App 声明", appMd("a", listOf("p.a")), appMd("b", listOf("p.a")))
        assertError("域名 a.com 被多个站点声明", siteMd("a.com"), siteMd("b.com", domains = listOf("b.com", "a.com")))
        assertError("名字 dup 重复", appMd("dup", listOf("p.a")), skillMd("dup"))
    }

    @Test fun linksMustExistBothWays() {
        assertError("sites 里的 a.com 不存在", appMd("a", listOf("p.a"), sites = listOf("a.com")))
        assertError("apps 里缺少 a", appMd("a", listOf("p.a"), sites = listOf("a.com")), siteMd("a.com"))
        assertError("apps 里的 nope 不存在", skillMd("s", apps = listOf("nope")))
    }

    @Test fun referencedAttachmentsMustExist() {
        assertError("附件 references/x.md 不存在", appMd("a", listOf("p.a"), body = "读 `references/x.md`"))
        // 点名另一个条目时到那个条目里找
        assertTrue(errors(appMd("a", listOf("p.a"), body = "读 `skill:s` 的 `references/x.md`"), skillMd("s"), "skills/s/references/x.md" to "x").isEmpty())
        assertError("提到的 skill:t 不存在", appMd("a", listOf("p.a"), body = "见 skill:t"))
        assertError("可运行的 scripts/run.py 不存在", skillMd("s"), "skills/s/scripts/manifest.json" to """[{"file":"scripts/run.py","runnable":true}]""")
    }

    @Test fun localPathsRejectedExceptLicenseFiles() {
        assertTrue(errors(appMd("a", listOf("p.a"), body = "见 /Users/me/x")).any { "本机路径" in it })
        assertTrue(errors(appMd("a", listOf("p.a")), "knowledge/apps/a/assets/LICENSE.md" to "/Users/me/x").isEmpty())
    }
}
