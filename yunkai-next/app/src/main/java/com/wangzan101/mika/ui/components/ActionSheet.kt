package com.wangzan101.mika.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType

/** 操作面板里的一项;[destructive] 为真时文字用出错红。 */
class SheetAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * 点一行后从底部升起的操作面板:顶上一行说明是在对什么操作,下面每项一行。
 * 点任何一项都会先执行它、再关掉面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            if (!title.isNullOrEmpty()) {
                Text(
                    title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MikaType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            actions.forEachIndexed { i, a ->
                ListRow(
                    null, a.label, first = i == 0,
                    titleColor = if (a.destructive) c.bad else c.ink,
                    onClick = { a.onClick(); onDismiss() },
                )
            }
        }
    }
}
