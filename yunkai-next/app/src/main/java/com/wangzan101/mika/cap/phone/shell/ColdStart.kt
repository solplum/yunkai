package com.wangzan101.mika.cap.phone.shell

/**
 * launch_app cold=true 时能不能结束进程。结束进程会连带清掉这个 App 在任何屏上的状态,
 * 所以只要它在 Mika 的屏以外还有任务(哪怕在后台),就不结束,改为普通启动。
 */
enum class ColdStart {
    RESTART, WARM, IN_USE;

    companion object {
        fun decide(taskDisplays: List<Int>, agentDisplay: Int, visibleOnMain: Boolean): ColdStart = when {
            visibleOnMain -> IN_USE
            taskDisplays.any { it != agentDisplay } -> WARM
            else -> RESTART
        }
    }
}
