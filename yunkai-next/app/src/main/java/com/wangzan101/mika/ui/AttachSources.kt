package com.wangzan101.mika.ui

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.wangzan101.mika.cap.sandbox.SandboxDirs
import com.wangzan101.mika.chat.AttachFilesProvider
import com.wangzan101.mika.core.workspace.SandboxPaths
import com.wangzan101.mika.ui.viewer.FileViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 对话页附件要读的东西:选中文件的名字大小、相机落点、点开已发出的附件。读盘的都在后台线程。 */
object AttachFiles {
    /** 选中文件(内容 URI 字符串)的名字和大小;读不到名字时用地址最后一段,读不到大小为 0。 */
    suspend fun describe(context: Context, uris: List<String>): List<PendingAttachment> = withContext(Dispatchers.IO) {
        uris.map { s ->
            val uri = Uri.parse(s)
            var name: String? = null
            var size = 0L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            PendingAttachment(s, name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment ?: "attachment", size)
        }
    }

    /** 相机拍的照片写到哪儿:缓存里 camera/ 下的新文件(一天前的旧照片顺手删掉),交给系统相机的地址。 */
    fun newCameraUri(context: Context): Uri {
        val dir = File(context.cacheDir, "attach/camera").apply { mkdirs() }
        val stale = System.currentTimeMillis() - 24 * 3600_000L
        dir.listFiles()?.filter { it.lastModified() < stale }?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        var f = File(dir, "IMG_$stamp.jpg")
        var n = 2
        while (f.exists()) f = File(dir, "IMG_${stamp}_${n++}.jpg")
        return FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), f)
    }

    /** 拍照没成(取消了):把空文件删掉。 */
    fun discardCamera(context: Context, uri: Uri) {
        val name = uri.lastPathSegment ?: return
        File(context.cacheDir, "attach/camera/$name").takeIf { it.name == name }?.delete()
    }

    /** 工作区里的路径(`/workspace/inbox/…`)换成相对工作区的路径;不在工作区里为 null。 */
    fun relative(path: String): String? =
        path.removePrefix(SandboxPaths.WORKSPACE + "/").takeIf { it != path && it.isNotEmpty() }

    /**
     * 点开已发出的附件:从对话工作区(经文件闸门)复制一份到缓存里的 viewing/(先清掉上一次的),
     * 交给 App 里的查看页;看不了的类型交给系统里能打开它的 App。读不到时返回 false。
     */
    suspend fun openSent(context: Context, conversationId: String, path: String): Boolean {
        val rel = relative(path) ?: return false
        val name = path.substringAfterLast('/')
        val copy = withContext(Dispatchers.IO) {
            runCatching {
                val root = File(context.cacheDir, "attach/viewing")
                root.deleteRecursively()
                val f = File(root, UUID.randomUUID().toString().take(8) + "/" + name).apply { parentFile?.mkdirs() }
                val dirs = SandboxDirs(context)
                dirs.gate.openRead(dirs.workspace(conversationId), rel).use { g -> f.outputStream().use { g.input().copyTo(it) } }
                f
            }.getOrNull()
        } ?: return false
        FileViewer.open(context, FileProvider.getUriForFile(context, AttachFilesProvider.authority(context.packageName), copy), name)
        return true
    }
}

/** 照片能不能直接读:全部、只给了部分(安卓 14 起)、没给。 */
enum class PhotoAccess { Full, Partial, None }

/** 附件面板里「最近的照片」:权限和媒体库里的照片。 */
object RecentPhotos {
    private const val PREFS = "attach"
    private const val ASKED = "photos_asked"

    /** 要申请的权限:安卓 14 起连同「部分照片」一起要,13 要读照片,12 及以前要读外部存储。 */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun access(context: Context): PhotoAccess {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.Full
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.Partial
            Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> PhotoAccess.Full
            else -> PhotoAccess.None
        }
    }

    /** 第一次打开面板时问一次;之后只在用户点「允许访问照片」时再问。 */
    fun asked(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

    fun markAsked(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()

    /** 最近加进媒体库的照片,新的在前;读不到(没权限)为空。 */
    suspend fun query(context: Context, limit: Int = 120): List<PendingAttachment> = withContext(Dispatchers.IO) {
        val base = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE)
        runCatching {
            context.contentResolver.query(base, projection, args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(base, c.getLong(0)).toString()
                        add(PendingAttachment(uri, c.getString(1) ?: "photo.jpg", if (c.isNull(2)) 0L else c.getLong(2), photo = true))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }
}

/** 附件里的一张图:还没发出的(内容地址)或已发出的(对话工作区里的路径)。 */
sealed interface AttachImage {
    data class Picked(val uri: String) : AttachImage
    data class Sent(val conversationId: String, val path: String) : AttachImage
}

/** 按要的边长(像素)读一张缩小的图;读不到为 null。界面测试换成假的。 */
fun interface AttachImages {
    suspend fun load(image: AttachImage, px: Int): ImageBitmap?

    /** 已经解过的直接给(列表来回滑时不闪占位);没有为 null。 */
    fun cached(image: AttachImage, px: Int): ImageBitmap? = null
}

/** 给了就用它读附件的图(测试);没给时读手机上的。 */
val LocalAttachImages = staticCompositionLocalOf<AttachImages?> { null }

/** 手机上的附件图:缩小着解(不解整张),在后台线程,解过的按字节数记着。 */
private class StoredAttachImages(private val context: Context) : AttachImages {
    override fun cached(image: AttachImage, px: Int): ImageBitmap? = cache.get("$image@$px")

    override suspend fun load(image: AttachImage, px: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$image@$px"
        cache.get(key)?.let { return@withContext it }
        val bmp = runCatching {
            when (image) {
                is AttachImage.Picked -> context.contentResolver.loadThumbnail(Uri.parse(image.uri), Size(px, px), null).asImageBitmap()
                is AttachImage.Sent -> sentBytes(image)?.let { decodeSmall(it, px) }
            }
        }.getOrNull()
        bmp?.also { cache.put(key, it) }
    }

    private fun sentBytes(image: AttachImage.Sent): ByteArray? {
        val rel = AttachFiles.relative(image.path) ?: return null
        val dirs = SandboxDirs(context)
        return dirs.gate.openRead(dirs.workspace(image.conversationId), rel).use { f ->
            if (f.size > MAX_BYTES) null else f.read(0, f.size.toInt())
        }
    }

    companion object {
        /** 一张图超过这么大就不解了(只显示占位)。 */
        const val MAX_BYTES = 40L shl 20
        val cache = object : LruCache<String, ImageBitmap>(24 shl 20) {
            override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
        }
    }
}

/** 解成短边不小于 [px] 的缩小图(按 2 的倍数缩),照片的方向按 EXIF 摆正。 */
private fun decodeSmall(bytes: ByteArray, px: Int): ImageBitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
        val short = minOf(info.size.width, info.size.height)
        var sample = 1
        while (short / (sample * 2) >= px) sample *= 2
        d.setTargetSampleSize(sample)
    }.asImageBitmap()

/** 读一张附件图(在后台);还没读到、读不到时为 null。 */
@Composable
fun rememberAttachImage(image: AttachImage, px: Int): ImageBitmap? {
    val context = LocalContext.current
    val given = LocalAttachImages.current
    val images = remember(given, context) { given ?: StoredAttachImages(context.applicationContext) }
    val bmp by produceState(images.cached(image, px), image, px) {
        value = images.cached(image, px)
        if (value == null) value = runCatching { images.load(image, px) }.getOrNull()
    }
    return bmp
}
