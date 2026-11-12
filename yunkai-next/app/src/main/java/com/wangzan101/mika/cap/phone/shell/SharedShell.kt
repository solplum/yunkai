package com.wangzan101.mika.cap.phone.shell

import android.os.Handler
import android.os.Looper

/**
 * 进程内共用一条 shell 连接,按引用计数绑定/解绑;由 [com.wangzan101.mika.cap.phone.PhoneServices] 建一份。
 * 解绑会撤掉 shell 一侧的虚拟屏等改动,所以任务和启动恢复不能各自绑一条再各自解绑。[create] 第一次要用时建连接。
 */
class SharedShell(private val create: () -> ShellBinding) {
    private var connection: ShellBinding? = null
    private var users = 0
    private val main = Handler(Looper.getMainLooper())

    @Synchronized
    fun acquire(): ShellBinding {
        val c = connection ?: create().also { connection = it }
        if (users++ == 0) onMain { c.bind() }
        return c
    }

    @Synchronized
    fun release() {
        check(users > 0) { "release without acquire" }
        if (--users == 0) {
            val c = connection
            onMain { synchronized(this) { if (users == 0) c?.unbind() } }
        }
    }

    /** 「重新连接」:已绑定却没有助手时请看门狗马上再起一次;没人绑着就什么也不做。 */
    @Synchronized
    fun reconnect() {
        connection?.takeIf { users > 0 }?.reconnect()
    }

    // 绑定/解绑统一放到主线程,避免与连接回调交错
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
