package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.ui.components.keepsKeyboard
import com.wangzan101.mika.ui.components.KeyboardDismissArea
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.memory.MemoryKind
import com.wangzan101.mika.ui.components.ActionSheet
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.GroupLabel
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.MikaSwitch
import com.wangzan101.mika.ui.components.RowActions
import com.wangzan101.mika.ui.components.SearchField
import com.wangzan101.mika.ui.components.SegmentedControl
import com.wangzan101.mika.ui.components.SheetAction
import com.wangzan101.mika.ui.components.SubPage
import com.wangzan101.mika.ui.components.TileTone
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

private val SEGMENT_TAGS = Segment.entries.map { "segment-${it.name}" }

/** 记忆按类别分组的顺序和小标题。 */
private val KIND_GROUPS get() = listOf(MemoryKind.fact to tr(R.string.memory_about_you), MemoryKind.preference to tr(R.string.memory_preferences), MemoryKind.procedure to tr(R.string.memory_procedures))
private val KIND_NAMES get() = listOf(tr(R.string.memory_fact), tr(R.string.memory_preferences), tr(R.string.memory_procedures))

/** 设置 →「记忆与技能」:看得见、改得了、删得掉。分三段:记忆、技能(内容在 [SkillsSection])、待确认。 */
@Composable
fun MemoryScreen(vm: MemoryViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ui by vm.ui.collectAsState()
    var editing by remember { mutableStateOf<Row?>(null) }
    var acting by remember { mutableStateOf<Row?>(null) }
    var adding by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    val searchable = ui.segment != Segment.Skills

    SubPage(
        stringResource(R.string.memory_and_skills), onBack,
        right = if (searchable) ({
            BarIconButton(MikaIcons.Search, stringResource(R.string.search), { searching = !searching; if (!searching) vm.search("") })
        }) else null,
    ) {
        ui.brokenNotice?.let {
            GroupCard { Text(stringResource(R.string.memory_unreadable), Modifier.padding(16.dp), style = MikaType.Caption, color = MikaTheme.colors.warn) }
        }

        val pendingLabel = if (ui.pendingCount > 0) stringResource(R.string.memory_pending_n, ui.pendingCount) else stringResource(R.string.memory_pending)
        SegmentedControl(
            listOf(stringResource(R.string.memory_memory), stringResource(R.string.memory_skills), pendingLabel), Segment.entries.indexOf(ui.segment), { vm.select(Segment.entries[it]) },
            tags = SEGMENT_TAGS,
        )
        if (searchable && (searching || ui.query.isNotEmpty())) {
            SearchField(ui.query, vm::search, stringResource(R.string.search), Modifier.testTag("memory-search"))
        }

        when (ui.segment) {
            Segment.Memories -> {
                if (ui.rows.isEmpty()) {
                    GroupLabel("")
                    EmptyNote(if (ui.query.isBlank()) stringResource(R.string.memory_empty) else stringResource(R.string.not_found))
                }
                KIND_GROUPS.forEach { (kind, label) ->
                    val rows = ui.rows.filter { it.kind == kind }
                    if (rows.isNotEmpty()) {
                        GroupLabel(label)
                        GroupCard {
                            rows.forEachIndexed { i, r ->
                                MemoryRow(r, first = i == 0, onClick = { acting = r })
                            }
                        }
                    }
                }
                GroupLabel("")
                GroupCard {
                    ListRow(MikaIcons.Plus, stringResource(R.string.memory_add), first = true, titleColor = MikaTheme.colors.accent, tone = TileTone.Accent, onClick = { adding = true })
                }
            }
            Segment.Skills -> SkillsSection(vm, ui.skills, pending = false)
            Segment.Pending -> {
                if (ui.pendingCount == 0) {
                    GroupLabel("")
                    EmptyNote(stringResource(R.string.memory_nothing_pending))
                }
                if (ui.pending.isNotEmpty()) {
                    GroupLabel(stringResource(R.string.memory_pending_title))
                    GroupCard {
                        ui.pending.forEachIndexed { i, r ->
                            Column {
                                MemoryRow(r, first = i == 0, onClick = null)
                                RowActions {
                                    MikaButton(stringResource(R.string.ok_yes), { vm.approve(r.id) }, size = ButtonSize.Small)
                                    MikaButton(stringResource(R.string.no_thanks), { vm.reject(r.id) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
                                }
                            }
                        }
                    }
                }
                SkillsSection(vm, ui.skills, pending = true)
            }
        }

        GroupLabel(stringResource(R.string.memory_switches))
        GroupCard {
            SwitchRow(stringResource(R.string.memory_use), stringResource(R.string.memory_use_sub), ui.switches.useMemory, first = true) { on -> vm.setSwitches { it.copy(useMemory = on) } }
            SwitchRow(stringResource(R.string.memory_auto), stringResource(R.string.memory_auto_sub), ui.switches.autoRemember) { on -> vm.setSwitches { it.copy(autoRemember = on) } }
            SwitchRow(stringResource(R.string.memory_learn), stringResource(R.string.memory_learn_sub), ui.switches.learnSkills) { on -> vm.setSwitches { it.copy(learnSkills = on) } }
        }
        GroupCard {
            ListRow(MikaIcons.Close, stringResource(R.string.routes_clear_all), stringResource(R.string.memory_clear_sub), first = true, titleColor = MikaTheme.colors.bad, tone = TileTone.Bad, onClick = vm::requestClear)
        }
    }

    acting?.let { r ->
        ActionSheet(
            r.content,
            listOf(
                SheetAction(stringResource(R.string.edit_it)) { editing = r },
                SheetAction(if (r.pinned) stringResource(R.string.unpin) else stringResource(R.string.pin)) { vm.setPinned(r.id, !r.pinned) },
                SheetAction(stringResource(R.string.confirm_delete), destructive = true) { vm.delete(r.id) },
            ),
            onDismiss = { acting = null },
        )
    }
    editing?.let { r ->
        EditDialog(stringResource(R.string.edit_it), r.content, r.keywords, onDismiss = { editing = null }) { content, keywords, _ -> vm.edit(r.id, content, keywords); editing = null }
    }
    if (adding) {
        EditDialog(stringResource(R.string.memory_add), "", emptyList(), onDismiss = { adding = false }, withKind = true) { content, keywords, kind -> vm.add(kind, content, keywords); adding = false }
    }
    ui.message?.let { m ->
        AlertDialog(onDismissRequest = vm::dismissMessage, text = { Text(m) }, confirmButton = { TextButton(vm::dismissMessage) { Text(stringResource(R.string.ok_got_it)) } })
    }
    if (ui.clearStage > 0) {
        AlertDialog(
            onDismissRequest = vm::cancelClear,
            title = { Text(if (ui.clearStage == 1) stringResource(R.string.memory_clear_q) else stringResource(R.string.memory_clear_sure)) },
            text = { Text(if (ui.clearStage == 1) stringResource(R.string.memory_clear_body) else stringResource(R.string.memory_clear_final)) },
            confirmButton = { TextButton(vm::confirmClear, Modifier.testTag("confirm-clear")) { Text(if (ui.clearStage == 1) stringResource(R.string.chat_continue) else stringResource(R.string.routes_clear_all)) } },
            dismissButton = { TextButton(vm::cancelClear) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, on: Boolean, first: Boolean = false, onChange: (Boolean) -> Unit) {
    ListRow(null, title, subtitle, first = first) { MikaSwitch(on, onChange) }
}

@Composable
private fun MemoryRow(r: Row, first: Boolean, onClick: (() -> Unit)?) {
    val subtitle = listOfNotNull(
        (if (r.pinned) stringResource(R.string.memory_pinned_prefix) else "") + r.source,
        r.replaces?.let { stringResource(R.string.memory_was, it) },
    ).joinToString("\n")
    androidx.compose.foundation.layout.Box(Modifier.testTag("memory-row")) {
        ListRow(null, r.content, subtitle, first = first, onClick = onClick)
    }
}

@Composable
internal fun Link(text: String, onClick: () -> Unit) {
    Text(text, Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 2.dp), style = MikaType.Hint, fontWeight = FontWeight.Medium, color = MikaTheme.colors.accent)
}

@Composable
private fun EditDialog(
    title: String, content: String, keywords: List<String>, onDismiss: () -> Unit,
    withKind: Boolean = false, onSave: (String, List<String>, MemoryKind) -> Unit,
) {
    var kind by remember { mutableIntStateOf(0) }
    var text by remember { mutableStateOf(content) }
    var words by remember { mutableStateOf(keywords.joinToString(" ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            KeyboardDismissArea { Column {
                if (withKind) SegmentedControl(KIND_NAMES, kind, { kind = it }, Modifier.padding(bottom = 8.dp), tags = listOf("memory-kind-fact", "memory-kind-preference", "memory-kind-procedure"))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().keepsKeyboard().testTag("memory-content"), label = { Text(stringResource(R.string.memory_content)) })
                OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth().padding(top = 8.dp).keepsKeyboard().testTag("memory-keywords"), label = { Text(stringResource(R.string.memory_keywords)) })
            } }
        },
        confirmButton = { TextButton({ onSave(text, words.split(Regex("\\s+")).filter { it.isNotBlank() }, KIND_GROUPS[kind].first) }, Modifier.testTag("memory-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
