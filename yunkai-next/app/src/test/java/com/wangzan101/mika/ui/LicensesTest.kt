// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LicensesTest {
    /** 单元测试的工作目录是 app/;仓库根目录在上一级。 */
    private val notices = File("../THIRD_PARTY_NOTICES.md").takeIf { it.isFile } ?: File("THIRD_PARTY_NOTICES.md")

    @Test
    fun everyNoticeEntryShown() {
        val headings = notices.readLines().filter { it.startsWith("## ") }.map { it.removePrefix("## ").trim() }
        assertTrue("notices has headings", headings.size >= 16)
        val shown = Licenses.ENTRIES.map { it.name }
        headings.forEach { assertTrue("missing on licenses screen: $it", it in shown) }
        assertEquals("Mika", shown.first())
    }

    @Test
    fun gplSourceOfferPresent() {
        val byName = Licenses.ENTRIES.associateBy { it.name }
        assertEquals("Apache-2.0", byName.getValue("Mika").license)
        assertEquals("GPL-2.0-or-later", byName.getValue("PRoot").license)
        assertEquals("LGPL-3.0-or-later", byName.getValue("talloc").license)
        assertEquals("Apache-2.0", byName.getValue("Apache Commons Compress").license)
        listOf("OkHttp", "Okio", "kotlinx.coroutines", "kotlinx.serialization", "Kotlin 标准库", "AndroidX(含 Jetpack Compose)")
            .forEach { assertEquals(it, "Apache-2.0", byName.getValue(it).license) }
        assertTrue(Licenses.GPL_SOURCE_OFFER.contains("yunkai-mika-0.1.10-corresponding-sources.tar.gz"))
        assertTrue(Licenses.GPL_SOURCE_OFFER.contains("PRoot") && Licenses.GPL_SOURCE_OFFER.contains("talloc"))
        assertTrue(byName.getValue("Alpine Linux").note.contains("corresponding-sources.tar.gz"))
        assertEquals("MIT", byName.getValue("云开图标与内置技能").license)
        assertTrue(File("src/main/assets/licenses/Yunkai-MIT.txt").readText().contains("Copyright (c) 2026 zhuolin"))
        assertEquals("0BSD", byName.getValue("XZ for Java").license)
    }

    /** 「开源许可」页逐个列出自带运行环境里的软件包,每个都有许可证。 */
    @Test
    fun runtimePackagesListed() {
        val manifest = File("src/main/assets/runtime/packages.json")
        val entries = Licenses.runtimePackages(manifest.readText())
        assertTrue(entries.size > 40)
        listOf("busybox", "musl", "python3", "nodejs", "py3-pillow", "py3-pypdf").forEach { n -> assertTrue(n, entries.any { it.name == n }) }
        entries.forEach { assertTrue(it.name, it.license.isNotBlank() && it.note.startsWith("版本 ")) }
    }
}
