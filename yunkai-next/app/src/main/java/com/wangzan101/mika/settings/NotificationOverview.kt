package com.wangzan101.mika.settings

import android.app.NotificationManager
import com.wangzan101.mika.task.TaskService

/** 一个通知频道现在的样子(只看系统里的重要程度)。 */
enum class ChannelLevel {
    /** 频道被关掉了(或总开关关着)。 */
    Off,
    /** 会在屏幕上弹出。 */
    HeadsUp,
    /** 只安静地进通知栏。 */
    Quiet,
}

/** 通知页里一个频道的一行:[warn] 为真表示和期望不一致(该弹出的没弹出),要标出来。 */
data class ChannelRow(val channelId: String, val level: ChannelLevel, val warn: Boolean)

/** 通知的总体情况,显示在设置里「通知」一行的右侧。 */
sealed interface NotificationSummary {
    data object Normal : NotificationSummary
    /** [count] 个频道该弹出却没弹出。 */
    data class Lowered(val count: Int) : NotificationSummary
    /** App 的通知总开关关着。 */
    data object AllOff : NotificationSummary
}

data class NotificationOverview(
    val appAllowed: Boolean,
    val attention: ChannelRow,
    val result: ChannelRow,
    val progress: ChannelRow,
    val summary: NotificationSummary,
) {
    companion object {
        /**
         * 由总开关和三个频道的重要程度得出每行状态和总体状态。重要程度为空表示频道还没建,按建立时的级别算。
         * 「需要你处理」「任务结果」应当弹出,不是「会弹出」就标警告(已关闭也算);「任务进度」安静或关闭都不影响任务,不标。
         * 总开关关着时三行都是「已关闭」、不标警告(问题在总开关)。
         */
        fun of(appAllowed: Boolean, attention: Int?, result: Int?, progress: Int?): NotificationOverview {
            fun row(id: String, importance: Int?, shouldPopUp: Boolean): ChannelRow {
                val level = if (!appAllowed) ChannelLevel.Off else levelOf(importance ?: initialImportance(id))
                return ChannelRow(id, level, warn = appAllowed && shouldPopUp && level != ChannelLevel.HeadsUp)
            }
            val a = row(TaskService.CHANNEL_ATTENTION, attention, shouldPopUp = true)
            val r = row(TaskService.CHANNEL_RESULT, result, shouldPopUp = true)
            val p = row(TaskService.CHANNEL_PROGRESS, progress, shouldPopUp = false)
            val lowered = listOf(a, r, p).count { it.warn }
            val summary = when {
                !appAllowed -> NotificationSummary.AllOff
                lowered > 0 -> NotificationSummary.Lowered(lowered)
                else -> NotificationSummary.Normal
            }
            return NotificationOverview(appAllowed, a, r, p, summary)
        }

        fun levelOf(importance: Int): ChannelLevel = when {
            importance == NotificationManager.IMPORTANCE_NONE -> ChannelLevel.Off
            importance >= NotificationManager.IMPORTANCE_HIGH -> ChannelLevel.HeadsUp
            else -> ChannelLevel.Quiet
        }

        private fun initialImportance(id: String): Int =
            TaskService.CHANNEL_IMPORTANCE[id] ?: NotificationManager.IMPORTANCE_DEFAULT
    }
}
