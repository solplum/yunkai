package com.wangzan101.mika.ui.viewer

import com.wangzan101.mika.R
import androidx.compose.ui.res.stringResource
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.wangzan101.mika.ui.MarkdownText
import com.wangzan101.mika.ui.components.BarIconButton
import com.wangzan101.mika.ui.components.ButtonKind
import com.wangzan101.mika.ui.components.ButtonSize
import com.wangzan101.mika.ui.components.MikaButton
import com.wangzan101.mika.ui.components.PageBackground
import com.wangzan101.mika.ui.components.TopBar
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaShapes
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.MikaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 文本类和 Markdown 最多读这么多字节,再多只显示前面一部分。 */
private const val TEXT_CAP = 1 shl 20

/** 图片解码的最长边,超过就按比例缩小,免得大图占满内存。 */
private const val IMAGE_MAX_SIDE = 4096

/** 每段文本的行数:长文本分段放进懒加载列表,只排看得见的几段。 */
private const val LINES_PER_CHUNK = 200

private val MonoText = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)

/** 读进来的内容;PDF 不在这里,它按页现读现画。 */
private sealed interface Loaded {
    data class Text(val text: String, val truncated: Boolean) : Loaded
    data class Html(val html: String) : Loaded
    class Picture(val bitmap: Bitmap) : Loaded
}

/**
 * 文件查看页:顶栏是返回、文件名和「分享」「用其他应用打开」;下面按 [kind] 显示内容。
 * [kind] 为 null(这里不认识的类型)时只说一句,并给「用其他应用打开」。
 */
@Composable
fun FileViewerScreen(uri: Uri, name: String, kind: ViewerKind?, onBack: () -> Unit) {
    val context = LocalContext.current
    val openElsewhere = { FileViewer.openElsewhere(context, uri, name) }
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(name, MikaIcons.Back, onBack, stringResource(R.string.back), right = {
                BarIconButton(MikaIcons.Share, stringResource(R.string.share), { FileViewer.share(context, uri, name) })
                BarIconButton(MikaIcons.External, stringResource(R.string.viewer_open_elsewhere), openElsewhere)
            })
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                when (kind) {
                    null -> Failure(stringResource(R.string.viewer_unsupported), openElsewhere, Modifier.testTag("viewer.unsupported"))
                    ViewerKind.Pdf -> PdfView(uri, openElsewhere)
                    else -> LoadedView(uri, kind, openElsewhere)
                }
            }
        }
    }
}

@Composable
private fun LoadedView(uri: Uri, kind: ViewerKind, openElsewhere: () -> Unit) {
    val context = LocalContext.current
    val result by produceState<Result<Loaded>?>(null, uri, kind) {
        value = withContext(Dispatchers.IO) { runCatching { load(context, uri, kind) } }
    }
    val loaded = result
    when {
        loaded == null -> Loading()
        loaded.isFailure -> Failure(stringResource(R.string.viewer_read_error), openElsewhere)
        else -> when (val l = loaded.getOrThrow()) {
            is Loaded.Html -> HtmlView(l.html)
            is Loaded.Picture -> PictureView(l.bitmap)
            is Loaded.Text -> if (kind == ViewerKind.Markdown) MarkdownView(l) else PlainTextView(l)
        }
    }
}

private fun load(context: Context, uri: Uri, kind: ViewerKind): Loaded = when (kind) {
    ViewerKind.Html -> Loaded.Html(open(context, uri).use { it.readBytes() }.toString(Charsets.UTF_8))
    ViewerKind.Image -> Loaded.Picture(
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > IMAGE_MAX_SIDE) {
                val f = IMAGE_MAX_SIDE.toFloat() / longest
                decoder.setTargetSize((info.size.width * f).roundToInt().coerceAtLeast(1), (info.size.height * f).roundToInt().coerceAtLeast(1))
            }
        },
    )
    else -> {
        // 多读一个字节,用来判断是不是被截断了;截在多字节字符中间时解码会换成替代符,不影响前面的内容
        val bytes = open(context, uri).use { input ->
            val buf = ByteArray(TEXT_CAP + 1)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(n)
        }
        val truncated = bytes.size > TEXT_CAP
        val text = String(bytes, 0, minOf(bytes.size, TEXT_CAP), Charsets.UTF_8).removePrefix("﻿")
        Loaded.Text(text, truncated)
    }
}

private fun open(context: Context, uri: Uri) =
    context.contentResolver.openInputStream(uri) ?: error("打不开 $uri")

// ---------- 各类内容 ----------

@Composable
private fun MarkdownView(l: Loaded.Text) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("viewer.markdown"),
    ) {
        if (l.truncated) TruncatedNote(Modifier.padding(bottom = 12.dp))
        MarkdownText(l.text, MikaType.Body)
    }
}

/** 等宽、可选中;按行分段放进懒加载列表,长文件也只排看得见的部分。长行自动折行。 */
@Composable
private fun PlainTextView(l: Loaded.Text) {
    val c = MikaTheme.colors
    val chunks = remember(l.text) { l.text.lines().chunked(LINES_PER_CHUNK).map { it.joinToString("\n") } }
    SelectionContainer {
        LazyColumn(
            Modifier.fillMaxSize().testTag("viewer.text"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        ) {
            if (l.truncated) item { TruncatedNote(Modifier.padding(bottom = 12.dp)) }
            itemsIndexed(chunks) { _, chunk -> Text(chunk, style = MonoText, color = c.ink) }
            item { Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
        }
    }
}

/** 网页:一个新的 WebView,脚本打开、可双指缩放、按宽视口排版;点链接交给系统处理。 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlView(html: String) {
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setBackgroundColor(android.graphics.Color.WHITE)
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).testTag("viewer.html"),
        onRelease = { it.destroy() },
    )
}

/** 图片:铺满可见区域(按比例),双指缩放,放大后单指拖动,双击放大或还原。 */
@Composable
private fun PictureView(bitmap: Bitmap) {
    val zoom = remember { Zoom() }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        image, null,
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).testTag("viewer.image")
            .pointerInput(zoom) { detectTapGestures(onDoubleTap = { zoom.toggle() }) }
            .zoomable(zoom, panWithOneFinger = true),
        contentScale = ContentScale.Fit,
    )
}

// ---------- PDF ----------

/** 打开的 PDF:各页的原始尺寸先读好(排版占位用),画页时一页一页来(渲染器同时只能开一页)。 */
private class PdfDoc(private val fd: ParcelFileDescriptor) {
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    val sizes: List<IntSize> = (0 until renderer.pageCount).map { i -> renderer.openPage(i).use { IntSize(it.width, it.height) } }

    suspend fun render(index: Int, widthPx: Int): Bitmap = lock.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(index).use { page ->
                val height = (widthPx.toFloat() * page.height / page.width).roundToInt().coerceAtLeast(1)
                Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(android.graphics.Color.WHITE)
                    page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
    }

    /** 等手上这一页画完再关。 */
    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            lock.withLock {
                runCatching { renderer.close() }
                runCatching { fd.close() }
            }
        }
    }
}

/** PDF:页按屏宽一列排开,只画看得见的页(滑走的页回收位图);双指缩放整列;底部「3 / 12」。 */
@Composable
private fun PdfView(uri: Uri, openElsewhere: () -> Unit) {
    val context = LocalContext.current
    val doc by produceState<Result<PdfDoc>?>(null, uri) {
        val opened = withContext(Dispatchers.IO) {
            runCatching { PdfDoc(context.contentResolver.openFileDescriptor(uri, "r") ?: error("打不开 $uri")) }
        }
        value = opened
        try { awaitCancellation() } finally { opened.getOrNull()?.close() }
    }
    val d = doc
    when {
        d == null -> Loading()
        d.isFailure -> Failure(stringResource(R.string.viewer_read_error), openElsewhere)
        else -> PdfPages(d.getOrThrow())
    }
}

@Composable
private fun PdfPages(doc: PdfDoc) {
    val c = MikaTheme.colors
    val list = rememberLazyListState()
    val zoom = remember { Zoom() }
    val current by remember(list) { derivedStateOf { currentPage(list) } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("viewer.pdf").zoomable(zoom, panWithOneFinger = false),
            state = list,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(doc.sizes) { index, size -> PdfPage(doc, index, size) }
        }
        if (doc.sizes.isNotEmpty()) {
            Text(
                "${current + 1} / ${doc.sizes.size}",
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp)
                    .background(c.ink.copy(alpha = 0.72f), MikaShapes.Pill).padding(horizontal = 12.dp, vertical = 4.dp),
                style = MikaType.Label, color = c.bg,
            )
        }
    }
}

/** 视口中线所在的那一页。 */
private fun currentPage(list: LazyListState): Int {
    val info = list.layoutInfo
    val middle = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.lastOrNull { it.offset <= middle }?.index ?: list.firstVisibleItemIndex
}

@Composable
private fun PdfPage(doc: PdfDoc, index: Int, size: IntSize) {
    val c = MikaTheme.colors
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(size.width.toFloat() / size.height.coerceAtLeast(1))
            .background(Color.White).border(0.5.dp, c.edge),
    ) {
        val widthPx = constraints.maxWidth
        val bitmap by produceState<Bitmap?>(null, doc, index, widthPx) {
            if (widthPx <= 0) return@produceState
            val b = runCatching { doc.render(index, widthPx) }.getOrNull() ?: return@produceState
            value = b
            // 滑出列表或尺寸变了:先撤下再回收,别让界面画到已回收的位图
            try { awaitCancellation() } finally { value = null; b.recycle() }
        }
        bitmap?.let { b ->
            val image = remember(b) { b.asImageBitmap() }
            Image(image, stringResource(R.string.viewer_page, index + 1), Modifier.fillMaxSize())
        }
    }
}

// ---------- 缩放 ----------

/** 缩放和平移的状态:1 到 5 倍,平移不超出放大后的边。 */
private class Zoom {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun apply(factor: Float, pan: Offset, size: IntSize) {
        scale = (scale * factor).coerceIn(1f, 5f)
        val maxX = (scale - 1f) * size.width / 2f
        val maxY = (scale - 1f) * size.height / 2f
        offset = Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
    }

    fun toggle() {
        scale = if (scale > 1f) 1f else 2.5f
        offset = Offset.Zero
    }
}

/**
 * 双指缩放、平移;[panWithOneFinger] 为真时放大后单指也能拖。
 * 在事件的第一轮里先拿到,两指时滚动列表就收不到,单指照常滚。
 */
private fun Modifier.zoomable(zoom: Zoom, panWithOneFinger: Boolean): Modifier = this
    .pointerInput(zoom) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val fingers = event.changes.count { it.pressed }
                if (fingers >= 2 || (panWithOneFinger && zoom.scale > 1f)) {
                    zoom.apply(event.calculateZoom(), event.calculatePan(), size)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    .graphicsLayer {
        scaleX = zoom.scale
        scaleY = zoom.scale
        translationX = zoom.offset.x
        translationY = zoom.offset.y
    }

// ---------- 状态 ----------

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize().testTag("viewer.loading"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = MikaTheme.colors.accent, strokeWidth = 2.5.dp)
    }
}

@Composable
private fun Failure(message: String, openElsewhere: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MikaType.Body, color = MikaTheme.colors.ink2, textAlign = TextAlign.Center)
        MikaButton(stringResource(R.string.viewer_open_elsewhere), openElsewhere, kind = ButtonKind.Secondary, size = ButtonSize.Medium)
    }
}

@Composable
private fun TruncatedNote(modifier: Modifier = Modifier) {
    Text(stringResource(R.string.viewer_truncated), modifier, style = MikaType.Caption, color = MikaTheme.colors.ink3)
}
