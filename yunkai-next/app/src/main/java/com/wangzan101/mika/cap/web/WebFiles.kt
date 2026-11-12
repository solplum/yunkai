package com.wangzan101.mika.cap.web

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.wangzan101.mika.core.workspace.WorkspaceFileGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 一个任务在网页上的文件:下载落到 [workspaceDir](本对话工作区)的 downloads/,上传只交这个工作区里的文件。
 * 上传不把工作区文件本身交出去:经关口复制到 App 私有的中转区([UploadStaging]),FileProvider 只开放中转区。
 * 选择框取走后副本留 [keepMs](页面提交表单时才读),之后、或任务结束时删掉;没取走的马上删。
 */
class WebFiles(
    private val context: Context,
    val workspaceDir: File,
    val downloads: WebDownloads,
    gate: WorkspaceFileGate,
    private val scope: CoroutineScope,
    private val keepMs: Long = 10 * 60_000L,
) {
    private val staging = UploadStaging(File(context.cacheDir, UploadStaging.DIR), gate)
    private val live = mutableMapOf<UploadStaging.Staged, Job?>()

    sealed interface Upload {
        class Ok(val uris: List<Uri>, val names: List<String>, val staged: UploadStaging.Staged) : Upload
        /** [error] 为 file_not_found、path_outside_workspace、linked_file 或 file_too_large。 */
        class Refused(val error: String, val path: String) : Upload
    }

    fun prepareUpload(paths: List<String>): Upload {
        val staged = when (val r = staging.stage(workspaceDir, paths)) {
            is UploadStaging.Result.Refused -> return Upload.Refused(r.error, r.path)
            is UploadStaging.Result.Ok -> r.staged
        }
        val uris = try {
            staged.files.map { FileProvider.getUriForFile(context, "${context.packageName}.webfiles", it) }
        } catch (e: IllegalArgumentException) {
            staging.delete(staged)
            return Upload.Refused("file_not_found", paths.first())
        }
        synchronized(live) { live[staged] = null }
        return Upload.Ok(uris, staged.files.map { it.name }, staged)
    }

    /** 上传那一下结束:选择框取走了([consumed])就过 [keepMs] 再删,没取走马上删。 */
    fun release(staged: UploadStaging.Staged, consumed: Boolean) {
        if (!consumed) return drop(staged)
        val job = scope.launch {
            delay(keepMs)
            drop(staged)
        }
        synchronized(live) { if (staged in live) live[staged] = job else job.cancel() }
    }

    private fun drop(staged: UploadStaging.Staged) {
        synchronized(live) { live.remove(staged) }?.cancel()
        staging.delete(staged)
    }

    /** 任务结束:还留着的副本都删掉。 */
    fun close() {
        val all = synchronized(live) { live.keys.toList() }
        all.forEach(::drop)
    }

    companion object {
        /** 启动时清掉上次进程留下的副本。 */
        fun clearStale(context: Context, gate: WorkspaceFileGate) {
            UploadStaging(File(context.cacheDir, UploadStaging.DIR), gate).clear()
        }
    }
}
