package com.wangzan101.mika.cap.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsNamingTest {
    @Test fun stripsSeparatorsKeepsChinese() {
        assertEquals("报表 (1).csv", Attachments.safeName("报表 (1).csv"))
        assertEquals("etcpasswd", Attachments.safeName("../etc/passwd"))
        assertEquals("ab.txt", Attachments.safeName("a\\b.txt"))
        assertEquals("ab.txt", Attachments.safeName("a\u0000\u0007\nb.txt"))
        // 改写方向与零宽字符:显示的名字和实际的会对不上
        assertEquals("invoicefdp.exe", Attachments.safeName("invoice‮fdp.exe"))
        assertEquals("ab", Attachments.safeName("a​ b"))
        assertEquals("attachment", Attachments.safeName("/../\u0001"))
        assertEquals("hidden", Attachments.safeName(".hidden"))
        // 最多 120 字,且 UTF-8 不超过 240 字节(给系统加序号留余地),扩展名保留
        val long = Attachments.safeName("长".repeat(300) + ".txt")
        assertTrue(long.toByteArray().size <= 240)
        assertTrue(long.endsWith(".txt"))
        assertEquals(120, Attachments.safeName("a".repeat(300)).length)
        val emoji = Attachments.safeName("😀".repeat(200))
        assertFalse(emoji.last().isHighSurrogate())
    }

    @Test fun numberedKeepsExtension() {
        assertEquals("a.csv", Attachments.numbered("a.csv", 1))
        assertEquals("a (2).csv", Attachments.numbered("a.csv", 2))
        assertEquals("README (3)", Attachments.numbered("README", 3))
        assertTrue(Attachments.numbered("x.tar.gz", 2).endsWith(".gz"))
    }

    @Test fun shareQuotaPerTask() {
        val q = ShareQuota(maxFiles = 2, maxBytes = 100)
        q.reserve(10); q.reserve(20)
        val third = runCatching { q.reserve(1) }.exceptionOrNull() as com.wangzan101.mika.core.workspace.SandboxError
        assertEquals("share_limit", third.code)
        // 失败的导出不占额度
        q.release(20)
        q.reserve(60)
        // 按实际复制的字节结算:比预留的少,省下的还能用
        q.settle(60, 10)
        val bytes = ShareQuota(maxBytes = 100)
        bytes.reserve(90); bytes.settle(90, 50)
        bytes.reserve(50)
        assertEquals("share_limit", (runCatching { bytes.reserve(1) }.exceptionOrNull() as com.wangzan101.mika.core.workspace.SandboxError).code)
    }
}
