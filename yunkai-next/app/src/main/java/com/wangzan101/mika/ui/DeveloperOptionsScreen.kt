package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.ui.components.FooterNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.PageScaffold
import com.wangzan101.mika.ui.components.RowChevron

/** 一个工程开关:[id] 用作测试标签,[change] 算出改过这一项之后的整组开关。 */
data class DevSwitch(
    val id: String, val title: String, val subtitle: String, val checked: Boolean, val change: (Boolean) -> SpeedFlags,
)

/** 开发者选项的两组:速度(已验证、默认多半开着)和实验(默认关)。开关的值与存储([SpeedFlags])不变。 */
object DeveloperSwitches {
    fun speed(flags: SpeedFlags): List<DevSwitch> = listOf(
        DevSwitch("adaptive-vision", tr(R.string.dev_adaptive), tr(R.string.dev_adaptive_sub), flags.adaptiveVision) { flags.copy(adaptiveVision = it) },
        DevSwitch("inline-screen", tr(R.string.dev_inline), tr(R.string.dev_inline_sub), flags.inlineScreen) { flags.copy(inlineScreen = it) },
        DevSwitch("batch-steps", tr(R.string.dev_batch), tr(R.string.dev_batch_sub), flags.batchSteps) { flags.copy(batchSteps = it) },
        DevSwitch("deep-links", tr(R.string.dev_deep_links), tr(R.string.dev_deep_links_sub), flags.deepLinks) { flags.copy(deepLinks = it) },
        DevSwitch("light-steps", tr(R.string.dev_light), tr(R.string.dev_light_sub), flags.lightSteps) { flags.copy(lightSteps = it) },
    )

    fun experimental(flags: SpeedFlags): List<DevSwitch> = listOf(
        DevSwitch("replay", tr(R.string.dev_replay), tr(R.string.dev_replay_sub), flags.replay) { flags.copy(replay = it) },
        DevSwitch("parallel-tasks", tr(R.string.dev_parallel), tr(R.string.dev_parallel_sub), flags.parallelTasks) { flags.copy(parallelTasks = it) },
        DevSwitch("structured-compaction", tr(R.string.dev_compaction), tr(R.string.dev_compaction_sub), flags.structuredCompaction) { flags.copy(structuredCompaction = it) },
    )
}

/** 「做事方式」一组:询问 / 自主的全局开关。 */
object AutonomyCopy {
    val GROUP: String get() = tr(R.string.dev_group)
    val TITLE: String get() = tr(R.string.dev_autonomy)
    val SUBTITLE: String get() = tr(R.string.dev_autonomy_sub)
}

val DEVELOPER_NOTE: String get() = tr(R.string.dev_note)

/** 当前看图模型的「看图定位已验证」:[model] 是它的模型名。 */
data class GroundingSetting(val model: String, val verified: Boolean)

object GroundingCopy {
    val GROUP: String get() = tr(R.string.cap_vision)
    val TITLE: String get() = tr(R.string.dev_grounding)

    fun subtitle(model: String) = tr(R.string.dev_grounding_sub, model)
}

/**
 * 设置 › 开发者选项:最前面是「自主模式」([autonomous],默认关即询问);原来「速度」下的八个开关,分成速度和实验两组;有看图模型时另有它的「看图定位已验证」
 * ([grounding] 为 null 时不显示);最后是「运行统计」的入口([onOpenRunStats] 为 null 时不显示)。
 */
@Composable
fun DeveloperOptionsScreen(
    speed: SpeedFlags,
    onSpeed: (SpeedFlags) -> Unit,
    onBack: () -> Unit,
    autonomous: Boolean = false,
    onAutonomous: (Boolean) -> Unit = {},
    grounding: GroundingSetting? = null,
    onGrounding: (Boolean) -> Unit = {},
    onOpenRunStats: (() -> Unit)? = null,
) {
    PageScaffold(tr(R.string.settings_developer), onBack) {
        GroupLabel(AutonomyCopy.GROUP)
        GroupCard {
            ListRow(null, AutonomyCopy.TITLE, AutonomyCopy.SUBTITLE, first = true, onClick = { onAutonomous(!autonomous) }) {
                MikaSwitch(autonomous, onAutonomous, Modifier.testTag("dev-autonomous"))
            }
        }
        GroupLabel(tr(R.string.dev_speed))
        SwitchGroup(DeveloperSwitches.speed(speed), onSpeed)
        GroupLabel(tr(R.string.dev_experiments))
        SwitchGroup(DeveloperSwitches.experimental(speed), onSpeed)
        if (grounding != null) {
            GroupLabel(GroundingCopy.GROUP)
            GroupCard {
                ListRow(null, GroundingCopy.TITLE, GroundingCopy.subtitle(grounding.model), first = true, onClick = { onGrounding(!grounding.verified) }) {
                    MikaSwitch(grounding.verified, onGrounding, Modifier.testTag("dev-grounding-verified"))
                }
            }
        }
        if (onOpenRunStats != null) {
            GroupLabel(tr(R.string.dev_run_log))
            GroupCard {
                ListRow(null, RunMetricsCopy.STATS_TITLE, RunMetricsCopy.STATS_SUBTITLE, first = true, onClick = onOpenRunStats) { RowChevron() }
            }
        }
        FooterNote(DEVELOPER_NOTE)
    }
}

@Composable
private fun SwitchGroup(switches: List<DevSwitch>, onSpeed: (SpeedFlags) -> Unit) {
    GroupCard {
        switches.forEachIndexed { i, s ->
            // 整行都能点:开关小,行大
            ListRow(null, s.title, s.subtitle, first = i == 0, onClick = { onSpeed(s.change(!s.checked)) }) {
                MikaSwitch(s.checked, { onSpeed(s.change(it)) }, Modifier.testTag("dev-${s.id}"))
            }
        }
    }
}
