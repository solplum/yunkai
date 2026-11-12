package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.about.Diagnostics
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 诊断信息的预览:整段文字先给用户看,点「复制」才进剪贴板,点「分享」才交给系统的分享面板;什么都不会自己发出去。
 * [load] 收集并写出那段文字(要读盘,放在后台线程跑);出错时只显示标题和异常类别。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(load: () -> String, onDismiss: () -> Unit) {
    val c = MikaTheme.colors
    val context = LocalContext.current
    // 打开那一刻收一次;之后状态再变也不换,用户看到的就是要复制的
    val text by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching(load).getOrElse { Diagnostics.sanitize("${Diagnostics.HEADER}\n(failed to collect: ${it.javaClass.simpleName})\n") }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag("diagnostics-sheet")) {
            Text(AboutCopy.DIAGNOSTICS_TITLE, style = MikaType.Title, color = c.ink)
            Text(AboutCopy.DIAGNOSTICS_NOTE, Modifier.padding(top = 2.dp), style = MikaType.Caption, color = c.ink3)
            Box(
                Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)
                    .background(c.fill, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(
                    text ?: AboutCopy.DIAGNOSTICS_LOADING, Modifier.testTag("diagnostics-text"),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = if (text == null) c.ink3 else c.ink2,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val ready = text != null
                MikaButton(
                    AboutCopy.DIAGNOSTICS_COPY, { text?.let { copyDiagnostics(context, it) } },
                    Modifier.weight(1f).testTag("diagnostics-copy"), enabled = ready,
                )
                MikaButton(
                    AboutCopy.DIAGNOSTICS_SHARE, { text?.let { shareDiagnostics(context, it) } },
                    Modifier.weight(1f).testTag("diagnostics-share"), kind = ButtonKind.Tonal, enabled = ready,
                )
            }
        }
    }
}

private fun copyDiagnostics(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(Diagnostics.HEADER, text))
    Toast.makeText(context, AboutCopy.DIAGNOSTICS_COPIED, Toast.LENGTH_SHORT).show()
}

private fun shareDiagnostics(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, AboutCopy.DIAGNOSTICS_TITLE)) }
}
