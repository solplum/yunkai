// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.about

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.wangzan101.mika.R

/** 作者微信二维码(随 App 打包,换群码只换 res/drawable-nodpi/author_wechat_qr.jpg)。 */
object QrImage {
    val RES = R.drawable.author_wechat_qr
    private const val FILE_NAME = "mika-author-wechat.jpg"

    /**
     * 存进相册的 Pictures/Yunkai。经 MediaStore 写自己新建的图片不要存储权限(安卓 10 起如此,Mika 最低支持安卓 11)。
     * 写完前标成待定,别的 App 看不到半张图;失败时删掉那一条。
     */
    fun saveToPictures(context: Context): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Yunkai")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }.getOrNull() ?: return false
        return runCatching {
            resolver.openOutputStream(uri)!!.use { out -> context.resources.openRawResource(RES).use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        }.getOrElse {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
