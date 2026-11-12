package com.wangzan101.mika.capsule

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mika 自己的界面(对话页、网页接管页等任一 Activity)此刻是否看得见:数已经 onStart、还没 onStop 的 Activity。
 * 转屏这类重建时旧的先停、新的再起,中间不算离开前台。由 App 在启动时登记,只在主线程回调。
 */
class AppForeground : Application.ActivityLifecycleCallbacks {
    private var started = 0
    /** 因为重建而停下、新实例还没起来的个数。 */
    private var recreating = 0
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    override fun onActivityStarted(activity: Activity) {
        if (recreating > 0) recreating-- else started++
        _visible.value = started > 0
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity.isChangingConfigurations) recreating++ else started = (started - 1).coerceAtLeast(0)
        _visible.value = started > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
