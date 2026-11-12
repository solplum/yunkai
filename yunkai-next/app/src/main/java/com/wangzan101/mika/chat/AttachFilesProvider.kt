package com.wangzan101.mika.chat

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * 对话页附件用的 content:// 地址(见 res/xml/attach_files.xml),不对外导出,只在用到时临时授权:
 * - camera/:系统相机拍的照片写到这里(拍照时授权相机 App 写这一个文件),发送时再复制进对话工作区;
 * - viewing/:点开已发出的照片、文件时从工作区复制出来的一份,交给 App 里的查看页(或用户选的别的 App)只读打开。
 * 工作区本身不开放。除 camera/ 外只读;不许删、不许插入。
 */
class AttachFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" && uri.pathSegments.firstOrNull() != CAMERA) throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** 和 attach_files.xml 里的 name 一致:地址的第一段。 */
        const val CAMERA = "camera"

        fun authority(packageName: String) = "$packageName.attachfiles"
    }
}
