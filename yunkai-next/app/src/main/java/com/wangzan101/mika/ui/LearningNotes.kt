package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 结果卡片下的小字:记住了什么、学会或停用了哪项技能,各带按钮。 */
internal fun LazyListScope.learningNotes(index: Int, ex: Exchange, vm: ChatViewModel) {
    val runId = ex.runId ?: return
    if (ex.memoryNotes.isEmpty() && ex.skillNotes.isEmpty()) return
    item("m$index") {
        Column(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 8.dp).testTag("learning-notes")) {
            ex.memoryNotes.forEach { n ->
                NoteLine(vm.noteText(n)) {
                    if (n.pendingId != null) {
                        Link(stringResource(R.string.ok_yes)) { vm.approveNote(runId, n) }
                        Link(stringResource(R.string.no_thanks)) { vm.rejectNote(runId, n) }
                    } else if (n.undoToken != null) Link(stringResource(R.string.undo)) { vm.undoNote(runId, n) }
                }
            }
            ex.skillNotes.forEach { n ->
                NoteLine(n.text) {
                    if (n.draftId != null) Link(stringResource(R.string.take_a_look)) { vm.viewSkill(n) }
                    if (n.suspended != null) {
                        Link(stringResource(R.string.restore)) { vm.restoreSkill(runId, n) }
                        Link(stringResource(R.string.confirm_delete)) { vm.deleteSkill(runId, n) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteLine(text: String, buttons: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = MikaType.Hint, color = MikaTheme.colors.ink3)
        buttons()
    }
}

