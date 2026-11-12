package com.wangzan101.mika.browser

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import com.wangzan101.mika.i18n.uiLocale
import com.wangzan101.mika.core.cap.web.WebUrlPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 「最近打开」的一行:搜索写成「搜索：<词>」(副标题「必应」),网页是标题(读不到时用网站名)和网站名。
 * [host] 是网页的主机名(画图标用),搜索为 null。
 */
data class RecentItem(val url: String, val title: String, val subtitle: String, val host: String?, val search: Boolean, val openedAtMs: Long)

/** 输入时下面的一条联想:[search] 为真是用必应搜输入的这句,否则打开 [url]。 */
data class Suggestion(val title: String, val subtitle: String, val url: String, val search: Boolean, val host: String?)

/** 起始页上的排法:最近打开、输入联想、相对时间。都是纯函数,名字由调用方按主机名给(见 [SiteLooks.face])。 */
object StartPageModel {
    val SEARCH_ENGINE: String get() = tr(R.string.browser_engine)
    val RECENT_SOURCE: String get() = tr(R.string.browser_recent_source)
    /** 联想里最近打开的至多几条。 */
    const val MAX_RECENT_SUGGESTIONS = 5

    /** 最近打开排成行:同一网址(不计 # 后面)只留最近的一条,最近的在前。 */
    fun recentRows(pages: List<RecentPage>, nameOf: (String) -> String): List<RecentItem> {
        val seen = HashSet<String>()
        return pages.sortedByDescending { it.openedAtMs }.filter { seen.add(it.url.trim().substringBefore('#')) }.map { p ->
            val q = AddressInput.searchQuery(p.url)
            if (q != null) {
                RecentItem(p.url, tr(R.string.browser_search_colon, q), SEARCH_ENGINE, null, true, p.openedAtMs)
            } else {
                val host = WebUrlPolicy.host(p.url)
                val site = host?.let(nameOf) ?: p.url
                RecentItem(p.url, p.title.ifBlank { site }, site, host, false, p.openedAtMs)
            }
        }
    }

    /**
     * 输入时的联想:第一条是这句话本身——像网址的打开它,其余用必应搜;后面是最近打开里标题或网址包含这句话的(不分大小写)。
     * 空输入没有联想。
     */
    fun suggestions(input: String, recent: List<RecentItem>): List<Suggestion> {
        val q = input.trim()
        if (q.isEmpty()) return emptyList()
        val target = AddressInput.resolve(q) ?: return emptyList()
        val first = if (AddressInput.searchQuery(target) != null) Suggestion(tr(R.string.browser_search_q, q), SEARCH_ENGINE, target, true, null)
        else Suggestion(q, tr(R.string.browser_open_url), target, false, WebUrlPolicy.host(target))
        val hits = recent.filter { it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
            .filter { it.url != target }
            .take(MAX_RECENT_SUGGESTIONS)
            .map { Suggestion(it.title, RECENT_SOURCE, it.url, false, it.host) }
        return listOf(first) + hits
    }

    /** 相对时间:「刚刚」「10 分钟前」「今天 11:20」「昨天」「10 月 21 日」,不在今年的带年份。 */
    fun relativeTime(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val diff = nowMs - atMs
        if (diff in 0 until 60_000) return tr(R.string.browser_just_now)
        if (diff in 60_000 until 3_600_000) return trPlural(R.plurals.browser_minutes_ago, (diff / 60_000).toInt())
        val then = Instant.ofEpochMilli(atMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> tr(R.string.browser_today_at, "%02d:%02d".format(then.hour, then.minute))
            days == 1L -> tr(R.string.drawer_yesterday)
            then.year == now.year -> tr(R.string.date_month_day, then.monthValue, then.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), then.dayOfMonth)
            else -> tr(R.string.date_year_month_day, then.year, then.monthValue, then.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), then.dayOfMonth)
        }
    }
}
