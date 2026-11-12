package com.wangzan101.mika.cap.phone.a11y

import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.InUse
import com.wangzan101.mika.cap.phone.a11y.MainScreenSpill.PullBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScreenSpillTest {
    private val since = 10_000L
    private val window = 6_000L
    private val home = setOf("com.home", "com.wangzan101.mika")

    private fun arrival(
        task: Int = 7, pkg: String? = "com.chat", at: Long = since + 500, from: Int = MainScreenSpill.NEW,
        sameTask: Boolean = false, standard: Boolean = true, now: Int = MainScreenSpill.MAIN, user: Int = 0,
    ) = MainScreenSpill.Arrival(task, pkg, user, at, from, sameTask, standard, now)

    private fun decide(vararg a: MainScreenSpill.Arrival, topBefore: Int = 3, taps: List<Long> = emptyList()) =
        MainScreenSpill.decide(a.toList(), since, window, topBefore, home, taps)

    @Test fun newTaskOnTheMainScreenRightAfterTheActionIsPulledBack() {
        assertEquals(listOf(PullBack(arrival())), decide(arrival()))
    }

    @Test fun backgroundTaskOnTheMainScreenBroughtToFrontIsPulledBackToo() {
        // 系统把那个 App 已有的后台任务拉到前台,不新建任务
        val a = arrival(from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(a)), decide(a))
    }

    @Test fun outsideTheWindowIsNotThisAction() {
        assertTrue(decide(arrival(at = since - 1)).isEmpty())
        assertTrue(decide(arrival(at = since + window + 1)).isEmpty())
    }

    @Test fun launcherMikaAndNonStandardPagesAreNotSpills() {
        assertTrue(decide(arrival(pkg = "com.home")).isEmpty())
        assertTrue(decide(arrival(pkg = "com.wangzan101.mika")).isEmpty())
        assertTrue(decide(arrival(standard = false)).isEmpty())
        assertTrue(decide(arrival(pkg = null)).isEmpty())
    }

    @Test fun taskMovedFromAnotherDisplayIsATakeoverNotASpill() {
        // 接管(本任务或别的任务)是把任务从 Mika 的屏挪到主屏
        assertTrue(decide(arrival(from = 5)).isEmpty())
    }

    @Test fun taskNoLongerOnTheMainScreenIsLeftAlone() {
        assertTrue(decide(arrival(now = 5)).isEmpty())
        assertTrue(decide(arrival(now = -1)).isEmpty())
    }

    @Test fun userTappingTheMainScreenMeansTheUserOpenedIt() {
        assertTrue(decide(arrival(at = since + 900), taps = listOf(since + 400)).isEmpty())
        // 点在出现之后(比如用户去点它):出现那一刻还没人碰,照样算
        assertEquals(1, decide(arrival(at = since + 900), taps = listOf(since + 1_500)).size)
        // 动作之前的点按不算
        assertEquals(1, decide(arrival(at = since + 900), taps = listOf(since - 100)).size)
    }

    @Test fun previousTopComingBackIsNotASpill() {
        // 挪走开出来的那个之后,原来最上面的又露出来
        assertTrue(decide(arrival(task = 3, from = MainScreenSpill.MAIN), topBefore = 3).isEmpty())
    }

    @Test fun newPageInTheAppTheUserIsUsingIsReportedNotMoved() {
        val a = arrival(task = 3, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(InUse(a)), decide(a, topBefore = 3))
    }

    @Test fun systemQuestionIsLeftForTheUserAndNothingElseIs() {
        val leave = MainScreenSpill.Handling.LeaveForUser
        val move = MainScreenSpill.Handling.Move
        // 系统的选择器,或系统包的窗口里的选项对得上装了两份的 App:留给用户选,从不挪、从不重开
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = true, optionsMatch = false))
        assertEquals(leave, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = true))
        // 系统设置一类的普通系统页面、普通 App:挪回
        assertEquals(move, MainScreenSpill.handling(systemPackage = true, systemPicker = false, optionsMatch = false))
        assertEquals(move, MainScreenSpill.handling(systemPackage = false, systemPicker = false, optionsMatch = true))
    }

    @Test fun appPickedByTheUserIsWhatCameUpAfterTheQuestionClosed() {
        val gone = since + 20_000
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val picked = arrival(task = 9, at = gone - 200)
        val pick = { list: List<MainScreenSpill.Arrival> -> MainScreenSpill.picked(list, 8, gone, 1_000, 3_000, 3, home) }
        assertEquals(picked, pick(listOf(chooser, picked)))
        // 用户刚点过选项也算(那一下就是选)
        assertEquals(picked, MainScreenSpill.picked(listOf(picked), 8, gone, 1_000, 3_000, 3, home))
        // 关掉选择框后露出来的原来那个不算:用户把问题关了
        assertEquals(null, pick(listOf(chooser, arrival(task = 3, at = gone, from = MainScreenSpill.MAIN))))
        // 选择框关掉很久之前冒出来的不是选出来的
        assertEquals(null, pick(listOf(arrival(task = 9, at = gone - 5_000))))
        // 本来就在主屏后台的那个 App 被拉到前台:就是选中的那份
        val existing = arrival(task = 12, at = gone + 400, from = MainScreenSpill.MAIN, user = 128)
        assertEquals(existing, pick(listOf(chooser, existing)))
    }

    @Test fun pickedAppNeedsTheUserForAPasswordOrASecureUnreadablePage() {
        assertTrue(MainScreenSpill.needsUser(passwordField = true, readable = true, secureWindow = false))
        assertTrue(MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = true))
        // 树本来就读不出东西的 App 不算要用户处理,照常挪回
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = false, secureWindow = false))
        assertEquals(false, MainScreenSpill.needsUser(passwordField = false, readable = true, secureWindow = false))
    }

    @Test fun oneVerdictPerTaskInOrder() {
        val chooser = arrival(task = 8, pkg = "android", at = since + 300)
        val first = arrival(task = 7, at = since + 200)
        val again = arrival(task = 7, at = since + 600, sameTask = true, from = MainScreenSpill.MAIN)
        assertEquals(listOf(PullBack(chooser), PullBack(again)), decide(again, first, chooser))
    }
}
