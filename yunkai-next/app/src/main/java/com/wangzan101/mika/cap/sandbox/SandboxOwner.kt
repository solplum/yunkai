package com.wangzan101.mika.cap.sandbox

import android.content.Context
import com.wangzan101.mika.core.workspace.SandboxTree

/** 沙箱在进程里的那一份状态与树锁:由 App 给出,设置页、安装、Agent 看到的是同一份。 */
interface SandboxOwner {
    val sandboxState: SandboxState
    val sandboxTree: SandboxTree

    companion object {
        fun of(context: Context): SandboxOwner = context.applicationContext as? SandboxOwner
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement SandboxOwner to give the sandbox's shared state")
    }
}
