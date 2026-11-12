package com.wangzan101.mika.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageHostsTest {
    private val hosts = PageHosts<String, String>()
    private fun host(name: String) = PageHosts.Host<String, String>(name)

    @Test
    fun viewGoesToTheHostThatCameToFrontLast() {
        val under = host("under")
        val top = host("top")
        hosts.open(under)
        hosts.started(under)
        hosts.setContainer(under, "c-under")
        assertSame(under, hosts.target())
        // 又打开了一个界面,也停在浏览器页:视图到看得见的这个上
        hosts.open(top)
        hosts.started(top)
        hosts.setContainer(top, "c-top")
        assertSame(top, hosts.target())
        // 被盖住的那个后来才重建容器,也不抢
        hosts.setContainer(under, null)
        hosts.setContainer(under, "c-under-2")
        assertSame(top, hosts.target())
        // 回到原来那个
        hosts.started(under)
        assertSame(under, hosts.target())
    }

    @Test
    fun onlyTheLastHostLeavingClosesThePage() {
        val a = host("a")
        val b = host("b")
        hosts.open(a)
        hosts.open(b)
        hosts.started(a)
        hosts.started(b)
        hosts.setContainer(a, "ca")
        hosts.setContainer(b, "cb")
        assertFalse(hosts.close(b))
        assertNull(b.container)
        assertSame(a, hosts.target())
        assertTrue(hosts.close(a))
        assertNull(hosts.target())
    }

    @Test
    fun hostsWithoutContainerOrNotOpenAreSkipped() {
        val a = host("a")
        val b = host("b")
        // 容器先于登记建好:登记前不算
        hosts.setContainer(a, "ca")
        assertNull(hosts.target())
        hosts.open(a)
        assertSame(a, hosts.target())
        // 回到了前台但没有容器(起始页上):不算
        hosts.open(b)
        hosts.started(b)
        assertSame(a, hosts.target())
        // 都没回到过前台:后进来的优先
        val c = host("c")
        hosts.open(c)
        hosts.setContainer(c, "cc")
        assertSame(c, hosts.target())
        hosts.close(c)
        assertSame(a, hosts.target())
    }
}
