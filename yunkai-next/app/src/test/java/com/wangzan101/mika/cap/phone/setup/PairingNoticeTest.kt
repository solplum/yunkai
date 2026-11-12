package com.wangzan101.mika.cap.phone.setup

import android.app.NotificationManager
import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.core.shell.BuiltinShellFailure
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.core.shell.PairingChannelHealth
import com.wangzan101.mika.core.shell.PairingDiscovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNoticeTest {
    @get:Rule val texts = ChineseTexts()

    @Test
    fun codeEntryAppearsOnlyOnceThePairingWindowIsFound() {
        assertNull(PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Searching))
        assertEquals(PairingNotice.EnterCode(37001), PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Found(37001)))
    }

    @Test
    fun enterCodeThenPairingThenSuccess() {
        val found = PairingDiscovery.Found(1)
        assertEquals(PairingNotice.EnterCode(1), PairingNotice.of(BuiltinShellStatus.NotPaired, found))
        assertEquals(PairingNotice.Working, PairingNotice.of(BuiltinShellStatus.Connecting, found))
        assertEquals(PairingNotice.Done, PairingNotice.of(BuiltinShellStatus.Running, PairingDiscovery.Idle))
    }

    @Test
    fun pairingFailuresKeepTheInputWithHowToRetry() {
        assertEquals(
            PairingNotice.EnterCode(5, "配对码不对或已失效：在配对窗口重新点「使用配对码配对设备」拿新的码，再填一次"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), PairingDiscovery.Found(5)),
        )
        // 窗口关了(找不到配对窗口)也留着输入框,说同样的办法
        val closed = PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed), PairingDiscovery.Searching)
        closed as PairingNotice.EnterCode
        assertNull(closed.port)
        assertTrue(closed.error!!.endsWith("拿新的码，再填一次"))
        // 配对之后的失败请用户回 Mika
        assertEquals(
            PairingNotice.Problem("连上了,但没能准备好"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), PairingDiscovery.Found(5)),
        )
    }

    @Test
    fun codeEntryPopsAtOnceWithoutAccessibilityAndWaitsForAutoRead() {
        val enter = PairingNotice.EnterCode(5)
        assertEquals(0L, PairingNotice.delayBefore(enter, autoRead = false))
        assertEquals(PairingNotice.AUTO_READ_GRACE_MS, PairingNotice.delayBefore(enter, autoRead = true))
        assertEquals(0L, PairingNotice.delayBefore(PairingNotice.Working, autoRead = true))
    }

    @Test
    fun realertsAreCappedAtThree() {
        val enter = PairingNotice.EnterCode(5)
        val waits = generateSequence(0) { it + 1 }.map { PairingNotice.realertAfter(enter, it) }.takeWhile { it != null }.toList()
        assertEquals(3, waits.size)
        assertEquals(PairingNotice.MAX_REALERTS, waits.size)
        // 越往后隔得越久
        assertEquals(waits.filterNotNull().sorted(), waits)
        // 填错后的提示也会再提醒;别的状态不提醒
        assertEquals(PairingNotice.REALERT_MS[0], PairingNotice.realertAfter(PairingNotice.EnterCode(5, "x"), 0))
        listOf(PairingNotice.Working, PairingNotice.Done, PairingNotice.Problem("x")).forEach { assertNull(PairingNotice.realertAfter(it, 0)) }
    }

    @Test
    fun channelIsCreatedHighWhenMissing() {
        assertEquals(ChannelPlan.Create(PairingChannel.IDS.last()), PairingChannel.plan(emptyList()))
        assertEquals(PairingChannelHealth.Ready, PairingChannel.plan(emptyList()).health)
    }

    @Test
    fun channelKeepsWhatWorksMigratesOldOnesAndRespectsTheUser() {
        val old = PairingChannel.IDS.first()
        val new = PairingChannel.IDS.last()
        // 旧版本建的、够高:接着用,不换 id
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Ready),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_HIGH, userSet = false))),
        )
        // 旧版本建得不够高、用户没动过:换新 id 重建
        assertEquals(
            ChannelPlan.Create(new),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = false))),
        )
        // 用户自己调低:不改回去,提示
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = true))),
        )
        // 用户关掉:发不出来
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Off),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_NONE, userSet = true))),
        )
        // 最新的 id 也不够高、又没有更新的可换:只能提示
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_LOW, userSet = false))),
        )
        // 新旧都在时看新的
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Ready),
            PairingChannel.plan(
                listOf(
                    ChannelSnapshot(old, NotificationManager.IMPORTANCE_LOW, userSet = true),
                    ChannelSnapshot(new, NotificationManager.IMPORTANCE_HIGH, userSet = false),
                ),
            ),
        )
    }
}
