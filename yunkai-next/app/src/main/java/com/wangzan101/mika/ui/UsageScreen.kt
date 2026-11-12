package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.core.usage.ModelUsage
import com.wangzan101.mika.core.usage.ProviderUsage
import com.wangzan101.mika.core.usage.UsageBucket
import com.wangzan101.mika.core.usage.UsageLedger
import com.wangzan101.mika.core.usage.UsageQuery
import com.wangzan101.mika.core.usage.UsageRange
import com.wangzan101.mika.core.usage.UsageReport
import com.wangzan101.mika.core.usage.UsageStep
import com.wangzan101.mika.core.usage.UsageTotals
import com.wangzan101.mika.settings.Provider
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.IconTile
import com.wangzan101.mika.ui.components.MikaCard
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.cardSurface
import com.wangzan101.mika.ui.theme.MikaColors
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import com.wangzan101.mika.i18n.uiLocale
import kotlin.math.roundToInt

/** 用量页顶上的五段:前四段是固定的范围,[Custom] 打开日期选择。 */
enum class UsagePreset(private val labelRes: Int) {
    Day(R.string.usage_24h), Week(R.string.usage_7d), Month(R.string.usage_30d), Quarter(R.string.usage_90d), Custom(R.string.usage_custom);

    val label: String get() = tr(labelRes)

    /** 这一段对应的范围;[Custom] 要给出 [from]..[to](本地日期)。 */
    fun range(from: LocalDate?, to: LocalDate?): UsageRange = when (this) {
        Day -> UsageRange.Last24Hours
        Week -> UsageRange.LastDays(7)
        Month -> UsageRange.LastDays(30)
        Quarter -> UsageRange.LastDays(90)
        Custom -> if (from != null && to != null) UsageRange.Custom(from, to) else UsageRange.LastDays(7)
    }
}

/** 按模型排行里的一行:[share] 是占这一排(全部服务商,或选中的那家)的比例。 */
data class ModelRank(val provider: ProviderUsage, val model: ModelUsage, val share: Double)

/** Token 用量的文字、数字写法和图表用的小规则。 */
object UsageCopy {
    val TITLE: String get() = tr(R.string.usage_title)
    val NONE: String get() = tr(R.string.usage_none)
    val EMPTY_TITLE: String get() = tr(R.string.usage_empty_title)
    val EMPTY_BODY: String get() = tr(R.string.usage_empty_body)
    val NOTE: String get() = tr(R.string.usage_note)
    val TOTAL: String get() = tr(R.string.usage_total)
    val TREND: String get() = tr(R.string.usage_trend)
    val BY_PROVIDER: String get() = tr(R.string.usage_by_provider)
    val BY_PROVIDER_HINT: String get() = tr(R.string.usage_by_provider_hint)
    val BY_MODEL: String get() = tr(R.string.usage_by_model)
    val ALL: String get() = tr(R.string.usage_all)
    val ALL_PROVIDERS: String get() = tr(R.string.usage_all_providers)
    val OTHERS: String get() = tr(R.string.usage_others)
    val DELETED: String get() = tr(R.string.usage_deleted)
    val CHANGE_DATES: String get() = tr(R.string.usage_change_dates)
    const val UNIT = "Token"

    /** 图表里有自己颜色的服务商最多几家(主题里 [MikaColors.series] 的个数),其余在趋势图里归为「其他」。 */
    const val COLORED = 3

    /**
     * token 数:一万以下照写;往上按万、亿,保留三位有效数字(四舍五入,去掉末尾的 0),
     * 例如 8765、1.23 万、12.3 万、123 万、1.5 亿。
     */
    fun tokens(n: Long): String {
        if (n < 10_000) return n.toString()
        val wan = round3(BigDecimal(n).divide(BigDecimal(10_000)))
        if (tr(R.string.ui_locale).startsWith("zh")) {
            if (wan < BigDecimal(10_000)) return "${wan.stripTrailingZeros().toPlainString()} 万"
            val yi = round3(BigDecimal(n).divide(BigDecimal(100_000_000)))
            return "${yi.stripTrailingZeros().toPlainString()} 亿"
        }
        // 英文按千、百万、十亿写:12.3K、1.5M
        for ((unit, size) in listOf("K" to 1_000L, "M" to 1_000_000L, "B" to 1_000_000_000L)) {
            val v = round3(BigDecimal(n).divide(BigDecimal(size)))
            if (v < BigDecimal(1_000) || unit == "B") return "${v.stripTrailingZeros().toPlainString()}$unit"
        }
        return n.toString()
    }

    private fun round3(v: BigDecimal): BigDecimal = v.setScale(
        when {
            v < BigDecimal.TEN -> 2
            v < BigDecimal(100) -> 1
            else -> 0
        },
        RoundingMode.HALF_UP,
    )

    /** 设置首页那一行的值:近 7 天的合计,没有时写「还没有」。 */
    fun settingsValue(weekTokens: Long): String = if (weekTokens <= 0) NONE else tr(R.string.usage_last_7d, tokens(weekTokens))

    fun calls(n: Long) = trPlural(R.plurals.times, n.toInt())

    /** 占比:不到 1% 但不是 0 时写「<1%」。 */
    fun percent(share: Double): String {
        val p = (share * 100).roundToInt()
        return if (p == 0 && share > 0) "<1%" else "$p%"
    }

    fun missing(n: Long) = trPlural(R.plurals.usage_missing, n.toInt())

    fun providerName(p: ProviderUsage) = if (p.deleted) p.name + DELETED else p.name

    fun stepLabel(step: UsageStep) = when (step) {
        UsageStep.Hour -> tr(R.string.usage_hourly)
        UsageStep.Day -> tr(R.string.usage_daily)
        UsageStep.Week -> tr(R.string.usage_weekly)
    }

    /** 本地日期:今年的不写年份。 */
    fun date(d: LocalDate, today: LocalDate): String =
        if (d.year == today.year) tr(R.string.date_month_day, d.monthValue, d.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), d.dayOfMonth)
        else tr(R.string.date_year_month_day, d.year, d.monthValue, d.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), d.dayOfMonth)

    /** 范围选择下面那一行,写出实际是哪几天。 */
    fun rangeText(range: UsageRange, today: LocalDate): String = when (range) {
        UsageRange.Last24Hours -> tr(R.string.usage_past_24h)
        is UsageRange.LastDays -> tr(R.string.usage_until_today, date(UsageQuery.days(range, today).first, today))
        is UsageRange.Custom -> UsageQuery.days(range, today).let { (from, to) ->
            if (from == to) date(from, today) else "${date(from, today)} – ${date(to, today)}"
        }
    }

    /** 趋势图下面的刻度:按小时写「18:00」(此刻那一格写「现在」),按天、按周写「10/27」(今天写「今天」)。 */
    fun axis(b: UsageBucket, step: UsageStep, today: LocalDate, last: Boolean = false): String = when {
        step == UsageStep.Hour && last -> tr(R.string.usage_now)
        step == UsageStep.Hour -> "%d:%02d".format(b.start.hour, b.start.minute)
        b.start.toLocalDate() == today -> tr(R.string.usage_today)
        else -> "${b.start.monthValue}/${b.start.dayOfMonth}"
    }

    /** 点中一根柱子时说的是哪段时间。 */
    fun period(b: UsageBucket, step: UsageStep, today: LocalDate): String = when (step) {
        UsageStep.Hour -> "%d:%02d".format(b.start.hour, b.start.minute)
        UsageStep.Day -> date(b.start.toLocalDate(), today)
        UsageStep.Week -> {
            val first = b.start.toLocalDate()
            val last = b.lastDay ?: first
            if (first == last) date(first, today) else "${date(first, today)} – ${date(last, today)}"
        }
    }

    /** 这家服务商在图表里用第几种颜色;排在 [COLORED] 家之后的为 null(灰色、趋势图里归「其他」)。 */
    fun colorSlot(p: ProviderUsage): Int? = p.order.takeIf { it < COLORED }

    /** 网格线的顶:不小于 [max] 的 1、2、4、6、8 乘 10 的幂,一半处的刻度也是整数。 */
    fun niceMax(max: Long): Long {
        if (max <= 0) return 1
        var scale = 1L
        while (scale * 10 < max) scale *= 10
        return listOf(1L, 2L, 4L, 6L, 8L, 10L).map { it * scale }.first { it >= max }
    }

    /** 按模型排行:选了服务商只列这家的,没选列全部服务商的;按 token 从多到少,占比按这一排的合计。 */
    fun modelRanking(report: UsageReport, providerId: String?): List<ModelRank> {
        val from = providerId?.let { id -> listOfNotNull(report.provider(id)) } ?: report.providers
        val rows = from.flatMap { p -> p.models.map { p to it } }
            .sortedWith(compareByDescending<Pair<ProviderUsage, ModelUsage>> { it.second.totals.tokens }.thenByDescending { it.second.totals.calls })
        val total = rows.sumOf { it.second.totals.tokens }
        return rows.map { (p, m) -> ModelRank(p, m, if (total <= 0) 0.0 else m.totals.tokens.toDouble() / total) }
    }
}

/**
 * 设置 › Token 用量:一个页面。顶上选时间和这段时间的总用量,下面三张图:用量趋势(按服务商叠起来)、
 * 按服务商排行(点一家选中,再点取消)、按模型排行(可切服务商)。选中的服务商只影响图里亮哪家、模型列哪家的,
 * 总用量始终是全部。账本每记一笔([UsageLedger.version])就重新统计;本地时区在统计时换算。
 */
@Composable
fun UsageScreen(ledger: UsageLedger, providers: StateFlow<List<Provider>>, onBack: () -> Unit) {
    var preset by rememberSaveable { mutableStateOf(UsagePreset.Week) }
    // 自定义范围的两头,存成纪元日(rememberSaveable 能存的类型)
    var customFrom by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTo by rememberSaveable { mutableStateOf<Long?>(null) }
    var picking by remember { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val version by ledger.version.collectAsState()
    val live by providers.collectAsState()
    val range = preset.range(customFrom?.let(LocalDate::ofEpochDay), customTo?.let(LocalDate::ofEpochDay))
    val zone = ZoneId.systemDefault()
    val report by produceState<UsageReport?>(null, version, range, live) {
        value = withContext(Dispatchers.Default) {
            // 连着的服务商按连接先后传进去,配色按这个先后固定
            UsageQuery.report(ledger.snapshot(), range, Instant.now(), zone, live.associate { it.id to it.name })
        }
    }
    // 换了时间后选中的服务商在这段里没有用量了,就退回全部
    LaunchedEffect(report) {
        val r = report ?: return@LaunchedEffect
        if (selected?.let(r::provider) == null) selected = null
    }

    UsageContent(
        report = report, preset = preset, today = LocalDate.now(zone), selected = selected,
        onPreset = { p -> if (p == UsagePreset.Custom) picking = true else preset = p },
        onPickCustom = { picking = true },
        onSelect = { selected = it },
        onBack = onBack,
    )

    if (picking) {
        UsageRangePicker(
            today = LocalDate.now(zone),
            from = customFrom?.let(LocalDate::ofEpochDay), to = customTo?.let(LocalDate::ofEpochDay),
            onPicked = { from, to ->
                customFrom = from.toEpochDay(); customTo = to.toEpochDay()
                preset = UsagePreset.Custom
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/**
 * 不带状态的用量页,便于测试。[report] 为 null 表示还在统计,只显示范围选择。
 * [onPreset] 收到 [UsagePreset.Custom] 时由调用方打开日期选择;已经是自定义时点「改日期」也打开([onPickCustom])。
 * [selected] 是选中的服务商(null 为全部),点排行或胶囊经 [onSelect] 改。
 */
@Composable
fun UsageContent(
    report: UsageReport?,
    preset: UsagePreset,
    today: LocalDate,
    selected: String?,
    onPreset: (UsagePreset) -> Unit,
    onPickCustom: () -> Unit,
    onSelect: (String?) -> Unit,
    onBack: () -> Unit,
) {
    val c = MikaTheme.colors
    PageScaffold(UsageCopy.TITLE, onBack) {
        SegmentedControl(
            UsagePreset.entries.map { it.label }, preset.ordinal, { onPreset(UsagePreset.entries[it]) },
            tags = UsagePreset.entries.map { "usage-range-${it.name}" },
        )
        if (report == null) return@PageScaffold
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.Center) {
            Text(UsageCopy.rangeText(report.range, today), style = MikaType.Hint, color = c.ink3)
            if (preset == UsagePreset.Custom) {
                Text(" · ", style = MikaType.Hint, color = c.ink3)
                Text(
                    UsageCopy.CHANGE_DATES, Modifier.clickable(role = Role.Button, onClick = onPickCustom).testTag("usage-change-dates"),
                    style = MikaType.Hint, color = c.accent, fontWeight = FontWeight.Medium,
                )
            }
        }
        if (report.providers.isEmpty()) {
            EmptyCard()
            return@PageScaffold
        }
        val pick = selected?.takeIf { report.provider(it) != null }
        TotalsCard(report.totals)

        ChartCard(UsageCopy.TREND, UsageCopy.stepLabel(report.step)) {
            TrendChart(report, pick, today)
        }

        ChartCard(UsageCopy.BY_PROVIDER, UsageCopy.BY_PROVIDER_HINT) {
            val max = report.providers.maxOf { it.totals.tokens }.coerceAtLeast(1)
            Column(Modifier.padding(top = 6.dp)) {
                report.providers.forEach { p ->
                    RankRow(
                        UsageCopy.providerName(p), mono = false, muted = p.deleted, color = seriesColor(c, p),
                        fraction = p.totals.tokens.toFloat() / max, tokens = p.totals.tokens, share = p.share,
                        dim = pick != null && pick != p.id, bold = pick == p.id,
                        onClick = { onSelect(if (pick == p.id) null else p.id) }, tag = "usage-rank-provider-${p.id}",
                    )
                }
            }
        }

        ChartCard(UsageCopy.BY_MODEL, pick?.let(report::provider)?.let(UsageCopy::providerName) ?: UsageCopy.ALL_PROVIDERS) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UsageChip(UsageCopy.ALL, pick == null, { onSelect(null) }, tag = "usage-chip-all")
                report.providers.sortedBy { it.order }.forEach { p ->
                    UsageChip(p.name, pick == p.id, { onSelect(p.id) }, deleted = p.deleted, tag = "usage-chip-${p.id}")
                }
            }
            val models = UsageCopy.modelRanking(report, pick)
            val max = models.maxOfOrNull { it.model.totals.tokens }?.coerceAtLeast(1) ?: 1
            models.forEach { m ->
                RankRow(
                    m.model.model, mono = true, muted = false, color = seriesColor(c, m.provider),
                    fraction = m.model.totals.tokens.toFloat() / max, tokens = m.model.totals.tokens, share = m.share,
                    tag = "usage-rank-model-${m.provider.id}-${m.model.model}",
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        if (report.totals.callsWithoutUsage > 0) {
            FooterNote(UsageCopy.missing(report.totals.callsWithoutUsage), Modifier.testTag("usage-missing"))
        }
        FooterNote(UsageCopy.NOTE)
    }
}

private fun seriesColor(c: MikaColors, p: ProviderUsage): Color = UsageCopy.colorSlot(p)?.let { c.series.getOrNull(it) } ?: c.seriesOther

/** 总用量卡:大字是输入加输出,下面一排输入、输出、缓存命中、调用次数。 */
@Composable
private fun TotalsCard(t: UsageTotals) {
    val c = MikaTheme.colors
    MikaCard {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 16.dp)) {
            Text(UsageCopy.TOTAL, style = MikaType.Caption, color = c.ink3)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.Bottom) {
                Text(UsageCopy.tokens(t.tokens), Modifier.testTag("usage-total"), style = TotalStyle, color = c.ink)
                Text(
                    UsageCopy.UNIT, Modifier.padding(start = 4.dp, bottom = 6.dp),
                    style = MikaType.Value, fontWeight = FontWeight.Medium, color = c.ink2,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat(tr(R.string.usage_input), UsageCopy.tokens(t.input), Modifier.weight(1f))
                Stat(tr(R.string.usage_output), UsageCopy.tokens(t.output), Modifier.weight(1f))
                Stat(tr(R.string.usage_cached), UsageCopy.tokens(t.cached), Modifier.weight(1f))
                Stat(tr(R.string.usage_calls), UsageCopy.calls(t.calls), Modifier.weight(1f))
            }
        }
    }
}

private val TotalStyle = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MikaType.Hint, color = MikaTheme.colors.ink3, maxLines = 1)
        Text(
            value, Modifier.padding(top = 2.dp), style = MikaType.Value, fontWeight = FontWeight.SemiBold,
            color = MikaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 一张图的卡片:左边标题,右边一句小字。 */
@Composable
private fun ChartCard(title: String, hint: String, content: @Composable () -> Unit) {
    val c = MikaTheme.colors
    MikaCard(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = CardTitle, color = c.ink)
                Text(hint, style = MikaType.Hint, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

private val CardTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

/** 没有用量时:一张居中的说明卡。 */
@Composable
private fun EmptyCard() {
    val c = MikaTheme.colors
    MikaCard(Modifier.testTag("usage-empty")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconTile(MikaIcons.Chart)
            Text(UsageCopy.EMPTY_TITLE, Modifier.padding(top = 12.dp), style = MikaType.BodyStrong, color = c.ink)
            Text(UsageCopy.EMPTY_BODY, Modifier.padding(top = 6.dp), style = MikaType.Caption, color = c.ink3, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 排行的一行:名字、横条(颜色是服务商的)、用量和占比。[dim] 时整行变淡(选了别的服务商),[bold] 时名字加粗(选中的);
 * [muted] 的名字用辅助文字色(已删除的服务商)。
 */
@Composable
private fun RankRow(
    name: String, mono: Boolean, muted: Boolean, color: Color, fraction: Float, tokens: Long, share: Double,
    dim: Boolean = false, bold: Boolean = false, onClick: (() -> Unit)? = null, tag: String,
) {
    val c = MikaTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(RowShape).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (dim) 0.4f else 1f)
            .padding(vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            name, Modifier.width(118.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = (if (mono) RankMono else RankText).let { if (bold) it.copy(fontWeight = FontWeight.SemiBold) else it },
            color = if (muted) c.ink3 else c.ink,
        )
        Box(Modifier.weight(1f).height(14.dp)) {
            Canvas(Modifier.fillMaxWidth().fillMaxHeight()) {
                val w = (size.width * fraction.coerceIn(0f, 1f)).coerceAtLeast(2.dp.toPx())
                val r = 4.dp.toPx().coerceAtMost(w / 2)
                val path = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, w, size.height, topRightCornerRadius = CornerRadius(r), bottomRightCornerRadius = CornerRadius(r)))
                }
                drawPath(path, color)
            }
        }
        Column(Modifier.width(74.dp), horizontalAlignment = Alignment.End) {
            Text(UsageCopy.tokens(tokens), style = RankValue, color = c.ink, maxLines = 1)
            Text(UsageCopy.percent(share), style = RankShare, color = c.ink3, maxLines = 1)
        }
    }
}

private val RowShape = RoundedCornerShape(8.dp)
private val RankText = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp)
private val RankMono = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
private val RankValue = TextStyle(fontSize = 13.sp, lineHeight = 17.sp)
private val RankShare = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)

/** 切换服务商的胶囊:选中时强调色实底;[deleted] 的名字变淡、后面写「（已删除）」。 */
@Composable
private fun UsageChip(text: String, selected: Boolean, onClick: () -> Unit, deleted: Boolean = false, tag: String) {
    val c = MikaTheme.colors
    val fg = when {
        selected -> c.onAccent
        deleted -> c.ink3
        else -> c.ink
    }
    Box(
        Modifier.height(34.dp)
            .then(if (selected) Modifier.background(c.accent, MikaShapes.Pill) else Modifier.cardSurface(c, MikaShapes.Pill))
            .clip(MikaShapes.Pill)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (deleted) text + UsageCopy.DELETED else text, maxLines = 1, color = fg, style = ChipText)
    }
}

private val ChipText = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)

/** 趋势图里的一段:有自己颜色的服务商各一段([slot] 是颜色序号),其余合成「其他」([slot] 为 null)。 */
private data class StackPart(val slot: Int?, val tokens: Long)

/**
 * 用量趋势:每格一根柱子,按服务商叠起来(有颜色的在下、「其他」在最上),段与段、柱与柱之间留 2dp,每根柱子顶上圆角 4dp。
 * 右边留一列写两条虚线网格的刻度(整数的顶和一半)。点一根在上方写出这一格的合计和各家的用量,再点一次收起;
 * 选了服务商时别家的段淡到四分之一。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrendChart(report: UsageReport, selected: String?, today: LocalDate) {
    val c = MikaTheme.colors
    val series = report.series
    if (series.isEmpty()) return
    val byId = report.providers.associateBy { it.id }
    val colored = report.providers.filter { UsageCopy.colorSlot(it) != null }.sortedBy { it.order }
    val hasOthers = report.providers.any { UsageCopy.colorSlot(it) == null && it.totals.tokens > 0 }
    val selectedSlot = selected?.let(byId::get)?.let { UsageCopy.colorSlot(it) ?: -1 }
    // 自下而上:有颜色的按先后,「其他」在最上
    val slots: List<Int?> = colored.map(UsageCopy::colorSlot) + if (hasOthers) listOf(null) else emptyList()
    val stacks = series.map { b ->
        val bySlot = b.byProvider.entries.groupBy { byId[it.key]?.let(UsageCopy::colorSlot) }
        slots.map { s -> StackPart(s, bySlot[s].orEmpty().sumOf { it.value }) }.filter { it.tokens > 0 }
    }
    val nice = UsageCopy.niceMax(series.maxOf { it.tokens })
    var tapped by remember(series) { mutableStateOf<Int?>(null) }
    val gutter = 38.dp

    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        colored.forEach { p -> LegendItem(c.series[UsageCopy.colorSlot(p)!!], UsageCopy.providerName(p)) }
        if (hasOthers) LegendItem(c.seriesOther, UsageCopy.OTHERS)
    }
    Box(Modifier.fillMaxWidth().padding(top = 12.dp).height(132.dp)) {
        Canvas(
            Modifier.fillMaxSize().testTag("usage-chart")
                .semantics { contentDescription = "${UsageCopy.TREND}，${UsageCopy.stepLabel(report.step)}" }
                .pointerInput(series) {
                    detectTapGestures { o ->
                        val plot = size.width - gutter.toPx()
                        if (o.x > plot) return@detectTapGestures
                        val i = (o.x / (plot / series.size)).toInt().coerceIn(0, series.lastIndex)
                        tapped = if (tapped == i) null else i
                    }
                },
        ) {
            val plot = size.width - gutter.toPx()
            val h = size.height
            val gap = 2.dp.toPx()
            val n = series.size
            val colW = ((plot - gap * (n - 1)) / n).coerceAtLeast(1f)
            val step = if (n > 1) (plot - colW) / (n - 1) else 0f
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            listOf(0f, 0.5f).forEach { y -> drawLine(c.grid, Offset(0f, h * y), Offset(plot, h * y), 1.dp.toPx(), pathEffect = dash) }
            drawLine(c.grid, Offset(0f, h), Offset(plot, h), 1.dp.toPx())
            stacks.forEachIndexed { i, segs ->
                val x = i * step
                var bottom = h
                segs.forEachIndexed { k, s ->
                    val top = k == segs.lastIndex
                    val segH = h * s.tokens / nice
                    val drawn = if (top) segH else (segH - gap).coerceAtLeast(0.5f)
                    val base = if (s.slot != null) c.series.getOrNull(s.slot) ?: c.seriesOther else c.seriesOther
                    val color = if (selectedSlot != null && selectedSlot != (s.slot ?: -1)) base.copy(alpha = base.alpha * 0.25f) else base
                    if (top) {
                        val r = 4.dp.toPx().coerceAtMost(colW / 2).coerceAtMost(drawn)
                        drawPath(
                            Path().apply {
                                addRoundRect(RoundRect(x, bottom - drawn, x + colW, bottom, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r)))
                            },
                            color,
                        )
                    } else {
                        drawRect(color, Offset(x, bottom - drawn), Size(colW, drawn))
                    }
                    bottom -= segH
                }
            }
        }
        // 网格刻度写在右边那一列
        GridLabel(UsageCopy.tokens(nice), Modifier.align(Alignment.TopEnd).offset(y = (-7).dp))
        GridLabel(UsageCopy.tokens(nice / 2), Modifier.align(Alignment.CenterEnd).offset(y = (-7).dp))
        tapped?.let { i -> TrendTip(series[i], report, i, gutter, today) }
    }
    Box(Modifier.fillMaxWidth().padding(top = 6.dp, end = gutter)) {
        val style = MikaType.Hint
        Text(UsageCopy.axis(series.first(), report.step, today), Modifier.align(Alignment.CenterStart), style = style, color = c.ink3)
        if (series.size > 2) {
            Text(UsageCopy.axis(series[series.size / 2], report.step, today), Modifier.align(Alignment.Center), style = style, color = c.ink3)
        }
        if (series.size > 1) {
            Text(UsageCopy.axis(series.last(), report.step, today, last = true), Modifier.align(Alignment.CenterEnd), style = style, color = c.ink3)
        }
    }
}

@Composable
private fun LegendItem(color: Color, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(9.dp).background(color, LegendShape))
        Text(name, style = MikaType.Hint, color = MikaTheme.colors.ink2, maxLines = 1)
    }
}

private val LegendShape = RoundedCornerShape(3.dp)

@Composable
private fun GridLabel(text: String, modifier: Modifier) {
    Text(text, modifier.width(34.dp), style = GridText, color = MikaTheme.colors.ink3, textAlign = TextAlign.End, maxLines = 1)
}

private val GridText = TextStyle(fontSize = 10.5.sp, lineHeight = 14.sp)

/** 点中一根柱子时的说明:哪段时间、合计,下面各家服务商各一行(按用量从多到少);横向对着那根柱子,不出图的左右边。 */
@Composable
private fun TrendTip(b: UsageBucket, report: UsageReport, index: Int, gutter: Dp, today: LocalDate) {
    val c = MikaTheme.colors
    val rows = b.byProvider.entries.sortedByDescending { it.value }
    Column(
        Modifier.layout { m, cons ->
            val p = m.measure(cons.copy(minWidth = 0, minHeight = 0))
            layout(cons.maxWidth, p.height) {
                val plot = cons.maxWidth - gutter.roundToPx()
                val center = plot / report.series.size.toFloat() * (index + 0.5f)
                p.place((center - p.width / 2f).toInt().coerceIn(0, (cons.maxWidth - p.width).coerceAtLeast(0)), 0)
            }
        }.background(c.ink, TipShape).padding(horizontal = 9.dp, vertical = 6.dp).testTag("usage-chart-tip"),
    ) {
        Text(
            "${UsageCopy.period(b, report.step, today)} · ${UsageCopy.tokens(b.tokens)}",
            style = TipText, fontWeight = FontWeight.SemiBold, color = c.bg, maxLines = 1,
        )
        rows.forEach { (id, v) ->
            val name = report.provider(id)?.let(UsageCopy::providerName) ?: id
            Text("$name　${UsageCopy.tokens(v)}", style = TipText, color = c.bg, maxLines = 1)
        }
    }
}

private val TipShape = RoundedCornerShape(8.dp)
private val TipText = TextStyle(fontSize = 11.5.sp, lineHeight = 18.sp)

/** 自定义范围的日期选择:只能选账本还留着的日子(最近 [UsageLedger.KEEP_DAYS] 天)到今天,两头都选了才可查看。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageRangePicker(today: LocalDate, from: LocalDate?, to: LocalDate?, onPicked: (LocalDate, LocalDate) -> Unit, onDismiss: () -> Unit) {
    // 日期选择器的日期是 UTC 零点的毫秒数
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun date(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val earliest = today.minusDays(UsageLedger.KEEP_DAYS.toLong())
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = (from ?: today.minusDays(6)).let(::millis),
        initialSelectedEndDateMillis = (to ?: today).let(::millis),
        yearRange = earliest.year..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = date(utcTimeMillis).let { it >= earliest && it <= today }
            override fun isSelectableYear(year: Int) = year in earliest.year..today.year
        },
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({ if (start != null && end != null) onPicked(date(start), date(end)) }, enabled = start != null && end != null) {
                Text(tr(R.string.view))
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(tr(R.string.cancel)) } },
    ) {
        DateRangePicker(state, Modifier.weight(1f), showModeToggle = false)
    }
}
