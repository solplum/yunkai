package com.wangzan101.mika.cap.phone.a11y

/** 前台换成了别的包,算不算「被切走」。 */
internal object AppSwitch {
    /** [byTarget]:新前台在目标 App 自己的任务里,是它打开的(如分享到另一个 App),措辞上不当成打断。 */
    data class Switch(val packageName: String, val byTarget: Boolean)

    /**
     * 不算被切走:系统的选择器、文件/照片选择器、权限框([partOfTask]),以及目标 App 在自己的任务里
     * 打开的系统设置页面([settingsPage],如由系统设置提供的城市选择页)。目标任务里的其他 App
     * (包括预装的浏览器、应用商店)仍报,但标 byTarget。
     * [sameTask] 只在包名不同、又不是选择器时才查。
     */
    fun switchedTo(
        target: String?,
        current: String?,
        sameTask: () -> Boolean,
        partOfTask: Set<String>,
        settingsPage: (String) -> Boolean,
    ): Switch? {
        if (target == null || current == null || current == target || current in partOfTask) return null
        if (!sameTask()) return Switch(current, byTarget = false)
        return if (settingsPage(current)) null else Switch(current, byTarget = true)
    }
}
