package com.wangzan101.mika.core.knowledge

import org.junit.Assert.assertEquals
import org.junit.Test

class FrontMatterTest {
    @Test fun parsesListsAndQuotes() {
        val (m, body) = FrontMatter.parse("---\nname: wechat\nupdated: \"2026-10-08\"\naliases: [WeChat, 'we, chat', \"x\"]\n---\n正文\n第二行\n")
        assertEquals("wechat", m["name"])
        assertEquals("2026-10-08", m["updated"])
        assertEquals(listOf("WeChat", "we, chat", "x"), m["aliases"])
        assertEquals("正文\n第二行", body)
    }

    @Test fun noFrontMatterIsAllBody() {
        val (m, body) = FrontMatter.parse("只有正文")
        assertEquals(emptyMap<String, Any>(), m); assertEquals("只有正文", body)
    }
}
