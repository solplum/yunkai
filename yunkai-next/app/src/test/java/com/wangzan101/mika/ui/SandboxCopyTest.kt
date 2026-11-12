package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SandboxCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test
    fun resetSubtitleFollowsStatus() {
        assertEquals("正在准备", SandboxCopy.resetSubtitle(SandboxStatus.Installing(42, "解包")))
        assertEquals("准备没有完成:可用空间不足", SandboxCopy.resetSubtitle(SandboxStatus.Broken("可用空间不足")))
        assertEquals("出问题时用:删除后自动重新准备", SandboxCopy.resetSubtitle(SandboxStatus.Ready(false)))
    }

    /** 运行环境对用户是看不见的:设置页文案里没有技术名词。 */
    @Test
    fun plainWords() {
        val all = listOf(SandboxCopy.NETWORK_TITLE, SandboxCopy.NETWORK_SUBTITLE, SandboxCopy.NETWORK_EXPLAIN, SandboxCopy.ISOLATION_UNAVAILABLE,
            SandboxCopy.RESET_TITLE, SandboxCopy.RESET_BODY, SandboxCopy.resetSubtitle(SandboxStatus.Ready(false)))
        all.forEach { t -> listOf("沙箱", "rootfs", "PRoot", "proot", "Alpine", "Linux", "seccomp").forEach { assertFalse("$t has $it", t.contains(it)) } }
    }
}
