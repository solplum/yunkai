// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.ui

import org.junit.Rule
import com.wangzan101.mika.i18n.ChineseTexts
import com.wangzan101.mika.chat.ConversationSummary
import com.wangzan101.mika.core.shell.BuiltinShellStatus
import com.wangzan101.mika.settings.AccessibilityState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.settings.SystemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ChatCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun greetingFollowsTheTimeOfDay() {
        assertEquals("早上好", ChatCopy.greeting(7))
        assertEquals("中午好", ChatCopy.greeting(12))
        assertEquals("下午好", ChatCopy.greeting(15))
        assertEquals("晚上好", ChatCopy.greeting(20))
        assertEquals("夜深了", ChatCopy.greeting(2))
        assertEquals("夜深了", ChatCopy.greeting(23))
    }

    @Test fun sixDistinctNonBlankExamples() {
        assertEquals(6, ChatCopy.examples.size)
        assertEquals(6, ChatCopy.examples.map { it.text }.filter { it.isNotBlank() }.toSet().size)
        assertEquals("打开微信，查看我最近的对话", ChatCopy.examples.first().text)
        assertEquals("帮我订明天早上9:40、9:50、10:00 的闹钟", ChatCopy.examples[4].text)
    }

    @Test fun titleIsTheFirstLineOfTheFirstMessage() {
        val first = Exchange("查车次\n附件:/workspace/inbox/a.pdf(1 KB)", emptyList(), ExchangeStatus.Working, live = true)
        assertEquals("查车次", ChatCopy.title(listOf(first, first.copy(goal = "再查"))))
        assertEquals("", ChatCopy.title(emptyList()))
        // 只发了附件:不拿路径当标题
        assertEquals("照片", ChatCopy.title(listOf(first.copy(goal = "附件:/workspace/inbox/a.jpg(1 KB)"))))
        assertEquals("a.pdf", ChatCopy.plainTitle("附件:/workspace/inbox/a.pdf(1 KB)"))
        assertEquals(ScreenCopy.TAG, ChatCopy.plainTitle("[[screen:s1]]"))
    }

    @Test fun subtitleSaysWhatIsGoingOn() {
        val working = Exchange("x", emptyList(), ExchangeStatus.Working, live = true)
        assertEquals("正在处理" to ChatCopy.Tone.Accent, ChatCopy.subtitle(working, null))
        assertEquals("正在操作设置" to ChatCopy.Tone.Accent, ChatCopy.subtitle(working, "设置"))
        assertEquals("等你处理" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.NeedsInput("?", null)), null))
        assertEquals("等你处理" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.Paused("why")), null))
        assertEquals("等你解锁手机" to ChatCopy.Tone.Warn, ChatCopy.subtitle(working.copy(status = ExchangeStatus.WaitingUnlock), null))
        assertNull(ChatCopy.subtitle(working.copy(status = ExchangeStatus.Done("好", null)), "设置"))
        assertNull(ChatCopy.subtitle(null, null))
    }

    @Test fun progressLineSaysPausedWhileWaitingForYou() {
        val ex = Exchange("x", emptyList(), ExchangeStatus.Working, live = true, line = "按评分排序")
        assertEquals("按评分排序", ChatCopy.progressLine(ex))
        assertEquals("已暂停", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.NeedsTakeover("login", "登录"))))
        assertEquals("已暂停", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.NeedsConfirmation("t", "b", 1))))
        assertEquals("已暂停", ChatCopy.progressLine(ex.copy(status = ExchangeStatus.WaitingUnlock)))
        assertEquals("正在处理", ChatCopy.progressLine(ex.copy(line = "", waitingForModel = true)))
    }

    @Test fun clockLabel() {
        assertEquals("0:00", ChatCopy.clock(0))
        assertEquals("0:24", ChatCopy.clock(24_900))
        assertEquals("12:05", ChatCopy.clock(725_000))
        assertEquals("1:02:03", ChatCopy.clock(3_723_000))
        assertEquals("0:00", ChatCopy.clock(-5))
    }

    @Test fun placeholderFollowsTheComposer() {
        assertEquals(ChatCopy.EMPTY_PROMPT, ChatCopy.placeholder(ComposerMode.Idle, hasHistory = false))
        assertEquals("继续说点什么…", ChatCopy.placeholder(ComposerMode.Idle, hasHistory = true))
        assertEquals("回答 云开 的问题", ChatCopy.placeholder(ComposerMode.Answer, hasHistory = true))
        assertEquals("补充或改要求…", ChatCopy.placeholder(ComposerMode.Busy, hasHistory = true))
    }

    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    @Test fun conversationTimeIsRelative() {
        // 2026-10-27 是星期二
        val now = at(2026, 10, 27, 15)
        assertEquals("9:05", conversationTime(at(2026, 10, 27, 9, 5), now, zone))
        assertEquals("昨天", conversationTime(at(2026, 10, 26, 23), now, zone))
        assertEquals("周五", conversationTime(at(2026, 10, 23), now, zone))
        assertEquals("10 月 1 日", conversationTime(at(2026, 10, 1), now, zone))
        assertEquals("2025 年 12 月 31 日", conversationTime(at(2025, 12, 31), now, zone))
    }

    @Test fun drawerListsRunningThenRecent() {
        val now = at(2026, 10, 27, 15)
        val all = listOf(
            ConversationSummary("a", "附近的川菜", at(2026, 10, 27, 14)),
            ConversationSummary("b", "明天的高铁票", at(2026, 10, 26)),
            ConversationSummary("c", "PDF 要点整理", at(2026, 10, 26)),
        )
        val current = CurrentConversation("a", running = true, title = "附近的川菜", subtitle = "正在操作外卖")
        val s = drawerSections(
            all, current, others = listOf("c", "d"), otherTitle = { if (it == "d") "第二个任务" else null },
            canSwitch = true, now = now, zone = zone,
        )
        assertEquals(listOf("a", "c", "d"), s.running.map { it.id })
        assertEquals(listOf("附近的川菜", "PDF 要点整理", "第二个任务"), s.running.map { it.title })
        assertEquals("正在操作外卖", s.running.first().subtitle)
        assertTrue(s.running.first().current)
        assertEquals(listOf("b"), s.recent.map { it.id })
        assertEquals("昨天", s.recent.single().time)
        assertEquals("", s.recent.single().subtitle)
        // 当前对话在列表里时,时间也写在标题右边
        assertEquals("14:00", s.running.first().time)
    }

    /** 置顶的对话在「置顶」一段,最近置顶的在前,不在「最近」里重复;有任务在跑的只列在进行中(带图钉)。 */
    @Test fun drawerListsPinnedAboveRecent() {
        val all = listOf(
            ConversationSummary("a", "一", 40L),
            ConversationSummary("b", "二", 30L, pinnedAt = 100L),
            ConversationSummary("c", "三", 20L),
            ConversationSummary("d", "四", 10L, pinnedAt = 200L),
            ConversationSummary("e", "五", 5L, pinnedAt = 300L),
        )
        val s = drawerSections(all, CurrentConversation("a", false, "一", ""), others = listOf("e"), { null }, canSwitch = true, now = 0L, zone = zone)
        assertEquals(listOf("e"), s.running.map { it.id })
        assertTrue(s.running.single().pinned)
        assertEquals(listOf("d", "b"), s.pinned.map { it.id })
        assertTrue(s.pinned.all { it.pinned })
        assertEquals(listOf("a", "c"), s.recent.map { it.id })
        assertTrue(s.recent.none { it.pinned })
        // 取消置顶:回到「最近」里按改动时间排的位置
        val unpinned = drawerSections(all.map { if (it.id == "b") it.copy(pinnedAt = null) else it }, CurrentConversation("a", false, "一", ""), emptyList(), { null }, true, 0L, zone)
        assertEquals(listOf("e", "d"), unpinned.pinned.map { it.id })
        assertEquals(listOf("a", "b", "c"), unpinned.recent.map { it.id })
    }

    /** 任务进行中、没开并行:只有当前对话能点。 */
    @Test fun drawerLocksOtherConversationsWhileBusy() {
        val all = listOf(ConversationSummary("a", "一", 0L), ConversationSummary("b", "二", 0L))
        val s = drawerSections(all, CurrentConversation("a", false, "一", ""), emptyList(), { null }, canSwitch = false, now = 0L, zone = zone)
        assertEquals(listOf(true, false), s.recent.map { it.enabled })
        assertEquals(listOf(true, false), s.recent.map { it.current })
        // 当前对话还没存进列表时(刚发出第一句)也列在进行中
        val fresh = drawerSections(emptyList(), CurrentConversation(null, true, "", "正在处理"), emptyList(), { null }, false, 0L, zone)
        assertEquals("新对话", fresh.running.single().title)
        assertTrue(fresh.running.single().enabled)
    }

    @Test fun readinessLine() {
        val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
        assertEquals("一切就绪" to true, readiness(Prerequisites.check(ready, hasMainModel = true)))
        assertEquals("还不能开始任务" to false, readiness(Prerequisites.check(ready, hasMainModel = false)))
        assertEquals(
            "手机操作还没准备好" to false,
            readiness(Prerequisites.check(ready.copy(accessibility = AccessibilityState.Off), hasMainModel = true)),
        )
    }
}
