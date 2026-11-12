package com.wangzan101.mika.ui

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.core.userscreen.UserScreen
import com.wangzan101.mika.core.userscreen.UserScreenMessage
import com.wangzan101.mika.ui.theme.MikaIcons
import com.wangzan101.mika.ui.theme.MikaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 用户发来的屏幕在界面上的说法(对话页、卡片、截屏按钮共用)。 */
object ScreenCopy {
    val TAG: String get() = tr(R.string.screen_tag)
    val BLOCKED: String get() = tr(R.string.screen_blocked)
    val CAMERA: String get() = tr(R.string.screen_camera)
    val REMOVE: String get() = tr(R.string.screen_remove)
    val SEND_SCREEN: String get() = tr(R.string.screen_send)

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = CLOCK.format(Instant.ofEpochMilli(at).atZone(zone))

    /** 截图下面那一行:「你此刻的屏幕 · 设置 · 19:42」。 */
    fun tag(screen: UserScreen): String = "$TAG · ${screen.app} · ${clock(screen.takenAt)}"

    /** 附件条上的第二行:「设置 · 19:42」。 */
    fun detail(screen: UserScreen): String = "${screen.app} · ${clock(screen.takenAt)}"

    /** 用这句话称呼对话、写在列表里:附言;只发了屏幕时是「你此刻的屏幕」。不带屏幕的原样返回。 */
    fun plain(text: String): String {
        val p = UserScreenMessage.parse(text)
        return if (!p.hasScreen) text else p.words.ifEmpty { TAG }
    }
}

/** 界面上要画的一屏:说明和缩略图(读不到图时为 null)。 */
class ShownScreen(val screen: UserScreen, val thumb: ImageBitmap?)

/** 按对话和编号读一屏;读不到为 null。界面测试换成假的。 */
fun interface ScreenShots {
    suspend fun load(conversationId: String, id: String): ShownScreen?
}

/** 给了就用它读屏幕(测试);没给时读 App 里存下的那份。 */
val LocalScreenShots = staticCompositionLocalOf<ScreenShots?> { null }

/** App 里存下的屏幕:说明从对话的工作区读,缩略图按要的宽度缩小后解码,解过的记着。 */
private class StoredScreenShots(private val context: Context) : ScreenShots {
    override suspend fun load(conversationId: String, id: String): ShownScreen? = withContext(Dispatchers.IO) {
        val key = "$conversationId/$id"
        cache.get(key)?.let { return@withContext it }
        val files = (context.applicationContext as MikaApp).userScreens
        val screen = files.screen(conversationId, id) ?: return@withContext null
        val thumb = if (screen.blocked) null else files.image(conversationId, id)?.let(::screenThumbnail)
        ShownScreen(screen, thumb).also { cache.put(key, it) }
    }

    companion object {
        val cache = LruCache<String, ShownScreen>(24)
    }
}

/** 缩略图解到这么宽(像素)就够:对话页里最宽 110dp。 */
private const val THUMB_PX = 360

/** 截图解成缩略图(按 [THUMB_PX] 缩小着解,不解整张);解不开为 null。在后台线程调。 */
fun screenThumbnail(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMB_PX) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** 读一屏(在后台);还没读到、读不到时为 null。 */
@Composable
fun rememberShownScreen(conversationId: String?, id: String): ShownScreen? {
    val context = LocalContext.current
    val shots = LocalScreenShots.current ?: StoredScreenShots(context)
    val shown by produceState<ShownScreen?>(null, conversationId, id) {
        value = conversationId?.let { runCatching { shots.load(it, id) }.getOrNull() }
    }
    return shown
}

/**
 * 用户的一句话:带着屏幕时是截图缩略图([thumbWidth] 宽)和下面一行「你此刻的屏幕 · App · 时刻」,附言在下面另起一个气泡([bubble]);
 * 不让截图的页面是一行「没截到」;截图已经不在时只有那一行字。不带屏幕时就是 [bubble]。
 */
@Composable
fun UserMessage(conversationId: String?, text: String, thumbWidth: Dp, modifier: Modifier = Modifier, bubble: @Composable (String) -> Unit) {
    val p = UserScreenMessage.parse(text)
    if (!p.hasScreen) {
        bubble(text)
        return
    }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        p.screens.forEach { id -> ScreenItem(rememberShownScreen(conversationId, id), thumbWidth) }
        if (p.words.isNotEmpty()) bubble(p.words)
    }
}

private val ThumbShape = RoundedCornerShape(10.dp)
private val TagText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)

/** 一屏:缩略图和下面那一行;还没读到时先占着位置。 */
@Composable
private fun ScreenItem(shown: ShownScreen?, width: Dp) {
    val c = MikaTheme.colors
    val screen = shown?.screen
    if (screen?.blocked == true) {
        Row(
            Modifier.testTag("screen-blocked").background(c.ink.copy(alpha = 0.05f), ThumbShape).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MikaIcons.Lock, null, Modifier.size(15.dp), tint = c.ink2)
            Text(ScreenCopy.BLOCKED, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = c.ink2)
        }
        return
    }
    val tag = screen?.let(ScreenCopy::tag) ?: ScreenCopy.TAG
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = tag }, horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenThumb(shown?.thumb, width, Modifier.testTag("screen-thumb"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(MikaIcons.Phone, null, Modifier.size(13.dp), tint = c.ink3)
            Text(tag, Modifier.testTag("screen-tag"), style = TagText, color = c.ink3)
        }
    }
}

/** 截图缩略图:按图的宽高比,圆角细边;没有图时是同样大小的一块淡灰。 */
@Composable
fun ScreenThumb(thumb: ImageBitmap?, width: Dp, modifier: Modifier = Modifier) {
    val c = MikaTheme.colors
    val ratio = thumb?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.3f, 1.8f) } ?: DEFAULT_RATIO
    Box(modifier.width(width).aspectRatio(ratio).clip(ThumbShape).border(1.dp, c.glassEdge, ThumbShape).background(c.ink.copy(alpha = 0.05f))) {
        thumb?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

/** 还没读到图时按常见手机屏的宽高比占位。 */
private const val DEFAULT_RATIO = 0.46f
