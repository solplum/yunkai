package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.cap.phone.shell.ShellClip.Now
import com.wangzan101.mika.cap.phone.shell.ShellClip.Read
import com.wangzan101.mika.cap.phone.shell.ShellClip.Restore
import org.junit.Assert.assertEquals
import org.junit.Test

/** 粘贴输入换剪贴板、再还原:读不到绝不当成空的,剪贴板不是 Mika 的就不动。 */
class ShellClipTest {
    @Test fun readDistinguishesEmptyFromUnreadable() {
        assertEquals(Read.Has, ShellClip.read(clipPresent = true, hasPrimaryClip = true, descriptionPresent = true))
        assertEquals(Read.Empty, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = false))
        // 取到的是空,但系统说有内容:读不到,不是空的
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = true, descriptionPresent = false))
        assertEquals(Read.Unreadable, ShellClip.read(clipPresent = false, hasPrimaryClip = false, descriptionPresent = true))
    }

    @Test fun restoreOnlyTouchesOursOrEmpty() {
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Ours))
        assertEquals(Restore.Clear, ShellClip.restore(priorEmpty = true, now = Now.Ours))
        // 用户中途复制了别的、或此刻读不到:不动
        for (prior in listOf(true, false)) {
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Other))
            assertEquals(Restore.Leave, ShellClip.restore(prior, Now.Unreadable))
        }
    }

    @Test fun clearedByOthersGetsUsersClipBack() {
        // Mika 的内容被别人清掉了(比如系统定时清空):原来有内容就放回去,原来是空的就不用管
        assertEquals(Restore.PutBack, ShellClip.restore(priorEmpty = false, now = Now.Empty))
        assertEquals(Restore.Leave, ShellClip.restore(priorEmpty = true, now = Now.Empty))
    }
}
