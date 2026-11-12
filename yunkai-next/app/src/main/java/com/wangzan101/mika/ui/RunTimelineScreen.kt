package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.trPlural
import androidx.compose.ui.res.stringResource
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.RowChevron
import com.wangzan101.mika.ui.components.RowValue
import com.wangzan101.mika.ui.components.StepItem
import com.wangzan101.mika.ui.components.StepState
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.components.ValueTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次运行的时间线(只读):顶部是目标、标准、一行小结(点开看时间去向)和结局(模型调用次数这类细节收在「更多详情」里),
 * 下面是竖线步骤,每步一行,点有画面的步骤看当时的画面。
 */
@Composable
fun RunTimelineScreen(vm: RunTimelineViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ui by produceState<TimelineUi>(TimelineUi.Loading, vm) { value = withContext(Dispatchers.IO) { vm.load() } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.timeline_title), left = MikaIcons.Back, leftLabel = stringResource(R.string.back), onLeft = onBack)
            when (val u = ui) {
                TimelineUi.Loading -> Unit
                TimelineUi.Expired -> EmptyNote(TimelineUi.EXPIRED_TEXT, Modifier.padding(top = 80.dp))
                is TimelineUi.Ready -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                ) {
                    item("header") { HeaderGroup(u.header) }
                    if (u.steps.isNotEmpty()) {
                        item("label") { GroupLabel(trPlural(R.plurals.timeline_total_steps, u.steps.size)) }
                        item("steps") {
                            GroupCard {
                                Column(Modifier.padding(start = 16.dp)) {
                                    u.steps.forEachIndexed { i, s ->
                                        TimelineRow(s, first = i == 0, last = i == u.steps.lastIndex, vm)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderGroup(h: TimelineHeader) {
    val c = MikaTheme.colors
    var more by rememberSaveable { mutableStateOf(false) }
    GroupCard {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Text(h.goal, style = MikaType.Title, color = c.ink)
            if (h.criteria.isNotEmpty()) {
                Text(stringResource(R.string.timeline_criteria), Modifier.padding(top = 10.dp), style = MikaType.Label, color = c.ink3)
                h.criteria.forEachIndexed { i, text ->
                    Text(
                        if (h.criteria.size > 1) "${i + 1}. $text" else text, Modifier.padding(top = 3.dp),
                        style = MikaType.Caption, color = c.ink2,
                    )
                }
            }
        }
        h.metrics?.let { RunMetricsSummary(it) }
        ListRow(null, stringResource(R.string.timeline_outcome)) { RowValue(h.outcome, if (h.outcome == stringResource(R.string.outcome_succeeded)) ValueTone.Ok else ValueTone.Plain) }
        if (h.verdict != stringResource(R.string.verify_none)) ListRow(null, stringResource(R.string.timeline_check)) { RowValue(h.verdict) }
        // 小结那一行已经写了用时
        if (h.metrics == null) h.duration?.let { d -> ListRow(null, stringResource(R.string.timeline_duration)) { RowValue(d) } }
        ListRow(null, stringResource(R.string.timeline_more), onClick = { more = !more }) {
            RowValue(if (more) stringResource(R.string.collapse) else stringResource(R.string.expand))
            RowChevron()
        }
        if (more) {
            ListRow(null, stringResource(R.string.timeline_model_calls)) { RowValue(trPlural(R.plurals.times, h.modelCalls)) }
            if (h.usedMemory.isNotEmpty()) {
                ListRow(null, stringResource(R.string.timeline_memory_used)) { RowValue(trPlural(R.plurals.items, h.usedMemory.size)) }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    h.usedMemory.forEach { memory ->
                        Text("· $memory", Modifier.padding(top = 3.dp), style = MikaType.Hint, color = c.ink2)
                    }
                }
            }
            Text(
                stringResource(R.string.timeline_timing_note),
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                style = MikaType.Hint, color = c.ink3,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineRow(s: TimelineStep, first: Boolean, last: Boolean, vm: RunTimelineViewModel) {
    val c = MikaTheme.colors
    var open by rememberSaveable(s.step) { mutableStateOf(false) }
    val shot = s.screenshotId
    val failed = s.result?.startsWith(stringResource(R.string.result_error_prefix)) == true
    StepItem(
        if (failed) StepState.Failed else StepState.Done, first, last,
        Modifier.then(if (shot != null) Modifier.clickable { open = !open } else Modifier),
        dotY = 20.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(end = 16.dp, top = 11.dp, bottom = 11.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(stringResource(R.string.step_number, s.step), style = MikaType.Label, color = c.ink3)
                Text(
                    if (s.activities.isEmpty()) listOfNotNull(s.tool, s.detail).joinToString(" · ") else "",
                    Modifier.weight(1f).padding(start = 8.dp),
                    style = MikaType.Body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                s.result?.let {
                    Text(
                        it, Modifier.padding(start = 8.dp).widthIn(max = 120.dp), style = MikaType.Caption,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when {
                            failed -> c.bad
                            it == stringResource(R.string.result_effective) -> c.ok
                            else -> c.ink3
                        },
                    )
                }
            }
            Text(RunTimelineViewModel.timingLabel(s.timing), Modifier.padding(top = 2.dp), style = MikaType.Hint, color = c.ink3)
            if (s.said.isNotEmpty()) {
                Text(s.said, Modifier.padding(top = 3.dp), style = MikaType.Caption, color = c.ink2)
            }
            s.activities.forEach { a ->
                val action = if (a.count > 1) trPlural(R.plurals.timeline_viewed_times, a.count) else a.label
                val effect = when (a.effect) {
                    "changed", "done", "place_changed" -> stringResource(R.string.result_effective)
                    "small_change" -> stringResource(R.string.effect_small_change)
                    "unchanged", "no_effect" -> stringResource(R.string.result_unchanged)
                    "completed" -> stringResource(R.string.result_completed)
                    null -> ""
                    // 错误码只对开发有意义,给人看的是没成功
                    else -> stringResource(R.string.step_failed)
                }
                Text(
                    listOfNotNull(action, a.target?.takeIf(String::isNotBlank), effect.takeIf(String::isNotBlank)).joinToString(" · "),
                    Modifier.padding(top = 3.dp), style = MikaType.Caption, color = c.ink2,
                )
            }
            if (s.tags.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.tags.forEach { TagChip(it) }
                }
            }
            if (shot != null) {
                Text(
                    if (open) stringResource(R.string.timeline_hide_screen) else stringResource(R.string.timeline_show_screen), Modifier.padding(top = 6.dp),
                    style = MikaType.Label, color = c.accent,
                )
                if (open) Shot(vm, shot, s.step)
            }
        }
    }
}

@Composable
private fun TagChip(t: Tag) {
    val c = MikaTheme.colors
    val (bg, fg) = when (t.tone) {
        TagTone.Ok -> c.okSoft to c.ok
        TagTone.Warn -> c.warnSoft to c.warn
        TagTone.Bad -> c.badSoft to c.bad
    }
    Text(
        t.text, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MikaType.Hint, fontWeight = FontWeight.SemiBold, color = fg,
    )
}

/** 当时的画面:在后台线程解码;文件已被清理时只说一句。 */
@Composable
private fun Shot(vm: RunTimelineViewModel, id: Int, step: Int) {
    val image by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) { vm.screen(id)?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(12.dp)
    image?.let {
        Image(
            it, stringResource(R.string.timeline_step_screen, step),
            Modifier.padding(top = 8.dp).widthIn(max = 220.dp).clip(shape).background(MikaTheme.colors.fill, shape),
            contentScale = ContentScale.FillWidth,
        )
    }
}
