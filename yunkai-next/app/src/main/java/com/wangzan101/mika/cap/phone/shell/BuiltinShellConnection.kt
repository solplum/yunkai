package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.core.shell.HelperLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import com.wangzan101.mika.shell.IMikaShell

/**
 * 内置助手的连接。助手进程常驻,不跟着解绑结束:解绑时让它撤掉这次的改动([IMikaShell.releaseAll]),
 * 效果与服务结束相同;再绑定时直接用手里的 Binder。没有助手时请看门狗起一个。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BuiltinShellConnection(hub: HelperHub, need: () -> Unit) : ShellBinding {
    // 单线程:撤销和下一次发布按先后执行;撤销是同步 binder 调用,不放主线程
    private val link = HelperLink(
        hub.shell,
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
        release = { it.releaseAll() },
        need = need,
    )

    override val shell: StateFlow<IMikaShell?> get() = link.shell
    override val dropped: StateFlow<Boolean> get() = link.dropped
    override fun bind() = link.bind()
    override fun unbind() = link.unbind()
    override fun reconnect() = link.reconnect()
}
