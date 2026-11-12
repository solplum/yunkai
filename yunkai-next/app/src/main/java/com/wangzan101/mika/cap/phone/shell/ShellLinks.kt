package com.wangzan101.mika.cap.phone.shell

import com.wangzan101.mika.core.tool.LinkPolicy

/** 外壳服务打开链接前的两项判断,不依赖安卓类,单元测试直接调。 */
internal object ShellLinks {
    /** 链接不允许打开时返回 [ShellService.START_INVALID_LINK],允许为 null。分发器已查过,这里再查一次。 */
    fun refuse(action: String?, uri: String?, pkg: String?): Int? =
        if (LinkPolicy.check(action, uri, pkg) != null) ShellService.START_INVALID_LINK else null

    /** 能打开链接的 App 里有一个在主屏有任务就算在用;一个都解析不出时看不清,也按在用处理。 */
    fun inUse(candidates: List<String>, onMainScreen: Set<String>): Boolean =
        candidates.isEmpty() || candidates.any { it in onMainScreen }
}
