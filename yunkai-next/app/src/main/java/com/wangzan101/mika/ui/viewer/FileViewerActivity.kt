package com.wangzan101.mika.ui.viewer

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.i18n.AppLanguage
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.animationsOff

/**
 * App 里的文件查看页:网页、Markdown、PDF、图片和文本类文件在这里直接看,不用另找 App。
 * 自成一个 Activity,对话里的文件卡片之外,别处(悬浮胶囊、通知)也能用 [FileViewer.open] 打开。
 */
class FileViewerActivity : ComponentActivity() {
    // 安卓 12 及以前套上 App 里选的语言;13 起系统自己套
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri == null) { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment.orEmpty()
        val kind = FileViewer.kindOf(this, uri, name)
        setContent {
            MikaTheme(reduceMotion = animationsOff(this)) {
                FileViewerScreen(uri, name, kind, onBack = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
    }
}

/** 打开文件的入口:能在 App 里看的进 [FileViewerActivity],其余交给系统里能打开它的 App。 */
object FileViewer {
    fun kindOf(context: Context, uri: Uri, name: String): ViewerKind? =
        ViewerKinds.of(runCatching { context.contentResolver.getType(uri) }.getOrNull(), name)

    fun open(context: Context, uri: Uri, name: String) {
        if (kindOf(context, uri, name) == null) { openElsewhere(context, uri, name); return }
        val intent = Intent(context, FileViewerActivity::class.java).setData(uri).putExtra(FileViewerActivity.EXTRA_NAME, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 用其他应用打开:系统的选择面板,没有能打开的 App 时提示一句。 */
    fun openElsewhere(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(context, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, Intent.createChooser(intent, name), tr(R.string.viewer_no_open_app))
    }

    /** 分享:把文件发给别的 App(带读取授权)。 */
    fun share(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_SEND).setType(mimeOf(context, uri)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, name), tr(R.string.viewer_no_share_app))
    }

    private fun mimeOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"

    private fun start(context: Context, chooser: Intent, failure: String) {
        try {
            context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
        }
    }
}
