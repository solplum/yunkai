// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.cap.sandbox

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.wangzan101.mika.chat.SharedFile
import com.wangzan101.mika.core.workspace.GatedFile
import com.wangzan101.mika.core.workspace.SandboxError
import com.wangzan101.mika.core.cap.sandbox.ShareRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream

class FileTooLarge(val bytes: Long) : Exception("file is larger than ${ShareRules.MAX_BYTES shr 20} MB")

/** 把工作区里的文件存到手机的「下载/Yunkai」(MediaStore,不需要存储权限)。 */
class ShareExporter(private val context: Context) {
    /**
     * [file] 已过关口核实(不跟随链接打开一次,按句柄确认在它自己的工作区里、是普通文件、只有一个硬链接);
     * 这里再看大小不超过 100 MB,然后从这同一个句柄复制。检查之后被换成链接或别的文件也读不到外面的东西。
     * 返回导出的文件,其中 savedAs 是 "Download/Yunkai/<实际名字>"(重名时系统加序号);不会把 Mika 的私有路径交出去。
     */
    suspend fun export(file: GatedFile, requestedName: String): SharedFile = withContext(Dispatchers.IO) {
        if (!ShareRules.plainName(requestedName)) throw SandboxError("invalid_arguments", "name: must be a plain file name", recoverable = true)
        val name = ShareRules.fitName(requestedName)
        if (ShareRules.refusedType(name) || ShareRules.refusedType(file.name)) {
            throw SandboxError("share_refused_type", "App installation packages cannot be shared.", recoverable = false)
        }
        if (file.size > ShareRules.MAX_BYTES) throw FileTooLarge(file.size)
        copyOut(file.input(), name)
    }

    private suspend fun copyOut(input: InputStream, name: String): SharedFile {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/Yunkai/")
            mimeOf(name)?.let { put(MediaStore.Downloads.MIME_TYPE, it) }
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
        var size = 0L
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    // 会话关了(任务取消、命令结束)就不再写下去
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    size += n
                    // 检查之后文件又变大了:同样按上限算
                    if (size > ShareRules.MAX_BYTES) throw FileTooLarge(size)
                    out.write(buf, 0, n)
                }
            }
            currentCoroutineContext().ensureActive()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        val actual = displayName(uri) ?: name
        return SharedFile(actual, size, "Download/Yunkai/$actual", uri.toString())
    }

    /**
     * 删掉本 App 在「下载/Yunkai」里留下的、没写完的条目(写到一半进程被杀会留下 IS_PENDING 的空壳)。启动时调用。
     */
    fun cleanPending() {
        val resolver = context.contentResolver
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.IS_PENDING} = 1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${android.os.Environment.DIRECTORY_DOWNLOADS}/Yunkai/%", context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val ids = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }.orEmpty()
        for (id in ids) runCatching { resolver.delete(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun mimeOf(name: String): String? =
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
}
