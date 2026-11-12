package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.GroupCard
import com.wangzan101.mika.ui.components.EmptyNote
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaType

/** 一次答复的来源:每条是「第几步 · 在哪 · 标题」,下面是当时的画面;没有画面的(后台读的网页正文、文件、命令输出)是摘录原文。 */
@Composable
fun SourceScreen(vm: SourceViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ui by produceState<SourceUi?>(null, vm) { value = withContext(Dispatchers.IO) { vm.load() } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val c = MikaTheme.colors
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.source_label), left = MikaIcons.Back, leftLabel = stringResource(R.string.back), onLeft = onBack)
            ui?.let { source ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                ) {
                    source.legacyText?.takeIf(String::isNotBlank)?.let { legacy ->
                        item("legacy") { GroupCard { Text(legacy, Modifier.padding(16.dp), style = MikaType.Body, color = c.ink) } }
                    }
                    source.entries.forEachIndexed { index, entry ->
                        item("source-$index") {
                            GroupCard {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                                    Text(
                                        listOfNotNull(stringResource(R.string.step_number, entry.step), entry.place, entry.title).joinToString(" · "),
                                        style = MikaType.BodyStrong, color = c.ink,
                                    )
                                    // 有画面就只给画面:识别出的文字和画面重复;没有画面时摘录就是唯一的依据
                                    if (entry.screenshots.isEmpty()) {
                                        entry.text?.let { Text(it, Modifier.padding(top = 6.dp), style = MikaType.Caption, color = c.ink2) }
                                    }
                                    entry.screenshots.forEach { id -> SourceShot(vm, id) }
                                }
                            }
                        }
                    }
                    if (source.legacyText.isNullOrBlank() && source.entries.isEmpty()) {
                        item("empty") { EmptyNote(stringResource(R.string.source_none)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceShot(vm: SourceViewModel, id: Int) {
    val image by produceState<ImageBitmap?>(null, vm, id) {
        value = withContext(Dispatchers.IO) { vm.screen(id)?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap() }
    }
    var zoomed by remember { mutableStateOf(false) }
    image?.let {
        Image(it, stringResource(R.string.source_image), Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = stringResource(R.string.zoom_in)) { zoomed = true },
            contentScale = ContentScale.FillWidth)
        if (zoomed) ZoomedShot(it) { zoomed = false }
    }
}

/** 全屏看一张画面,点一下或按返回收起。 */
@Composable
private fun ZoomedShot(image: ImageBitmap, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = stringResource(R.string.collapse), onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Image(image, stringResource(R.string.source_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}
