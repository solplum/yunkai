package com.wangzan101.mika.cap.phone.shell

import android.content.Intent

/** 文字分享:安卓的 ACTION_SEND,类型 text/plain,文字放在 EXTRA_TEXT,只交给一个包。 */
object ShareIntent {
    const val TYPE = "text/plain"

    fun of(packageName: String, text: String): Intent = Intent(Intent.ACTION_SEND).setType(TYPE).putExtra(Intent.EXTRA_TEXT, text)
        .setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 系统接口用不了时 `am start` 的参数(不经 shell 解释,文字原样作一个参数)。[component] 是这个包只有一个收分享的页面时
     * 它的短名,直接指定;有几个时只限定包,由系统问用哪一个。
     */
    fun amArgs(displayId: Int, packageName: String, text: String, component: String?): List<String> =
        listOf("am", "start", "--display", "$displayId", "-a", Intent.ACTION_SEND, "-t", TYPE, "--es", Intent.EXTRA_TEXT, text) +
            (component?.let { listOf("-n", it) } ?: listOf("-p", packageName))
}
