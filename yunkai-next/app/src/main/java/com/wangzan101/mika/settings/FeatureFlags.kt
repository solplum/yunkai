package com.wangzan101.mika.settings

import android.content.Context
import android.content.SharedPreferences
import com.wangzan101.mika.capsule.CapsuleDock
import com.wangzan101.mika.capsule.CardDocking
import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.capsule.DockSide
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.task.ParallelCapability
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** 功能开关。提速开关存成 [SpeedFlags.describe] 的格式,评测跑器可用参数覆盖。 */
class FeatureFlags(private val prefs: SharedPreferences) {
    /** 旧设置升级一次后启用分级读屏和连续操作；升级之后用户手动关掉就保持关闭。 */
    fun speedFlags(): SpeedFlags {
        val flags = prefs.getString(SPEED_FLAGS, null)?.let { runCatching { SpeedFlags.parse(it) }.getOrNull() } ?: SpeedFlags.OFF
        if (prefs.getBoolean(SPEED_DEFAULTS_APPLIED, false)) return flags
        val upgraded = flags.copy(inlineScreen = true, batchSteps = true, adaptiveVision = true)
        prefs.edit().putString(SPEED_FLAGS, upgraded.describe()).putBoolean(SPEED_DEFAULTS_APPLIED, true).commit()
        return upgraded
    }

    fun setSpeed(flags: SpeedFlags) {
        prefs.edit().putString(SPEED_FLAGS, flags.describe()).putBoolean(SPEED_DEFAULTS_APPLIED, true).commit()
    }

    /** 并行任务时最多同时跑几个(用户设定,1–3);真正的上限还要看设备,见 ParallelCapability。 */
    fun parallelMax(): Int = prefs.getInt(PARALLEL_MAX, ParallelCapability.DEFAULT_MAX)

    fun setParallelMax(max: Int) {
        prefs.edit().putInt(PARALLEL_MAX, max).commit()
    }

    /** 干活时屏幕保持常亮;默认开,用户可以关。 */
    fun keepScreenOn(): Boolean = prefs.getBoolean(KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(on: Boolean) {
        prefs.edit().putBoolean(KEEP_SCREEN_ON, on).commit()
    }

    /** 干活时把被操作的 App 静音;默认开,用户可以关。 */
    fun muteTarget(): Boolean = prefs.getBoolean(MUTE_TARGET, true)

    fun setMuteTarget(on: Boolean) {
        prefs.edit().putBoolean(MUTE_TARGET, on).commit()
    }

    /** 悬浮胶囊:关闭、有任务时显示(默认)、一直显示。 */
    fun capsuleMode(): CapsuleMode = CapsuleMode.parse(prefs.getString(CAPSULE_MODE, null))

    fun setCapsuleMode(mode: CapsuleMode) {
        prefs.edit().putString(CAPSULE_MODE, mode.name).commit()
    }

    /** 胶囊开关的当前值,之后每次改动再发一次。 */
    fun capsuleModeChanges(): Flow<CapsuleMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // 清空全部设置时 key 为 null
            if (key == null || key == CAPSULE_MODE) trySend(capsuleMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** 胶囊停在哪:左边还是右边、高度占可摆范围的比例;没拖过时在右上角。 */
    fun capsuleDock(): CapsuleDock = CapsuleDock(
        if (prefs.getString(CAPSULE_SIDE, null) == DockSide.Left.name) DockSide.Left else DockSide.Right,
        prefs.getFloat(CAPSULE_HEIGHT, 0f).coerceIn(0f, 1f),
    )

    fun setCapsuleDock(dock: CapsuleDock) {
        prefs.edit().putString(CAPSULE_SIDE, dock.side.name).putFloat(CAPSULE_HEIGHT, dock.fraction).apply()
    }

    /**
     * 展开的卡片停在哪:上边在可摆范围里的比例(见 [com.wangzan101.mika.capsule.CardDocking.release]);没记过时最高的卡片在正中。
     * 以前按固定高的卡片记的比例正好就是它的上边比例,照样读。
     */
    fun capsuleCardFraction(): Float = prefs.getFloat(CAPSULE_CARD_HEIGHT, CardDocking.DEFAULT_FRACTION).coerceIn(0f, 1f)

    fun setCapsuleCardFraction(fraction: Float) {
        prefs.edit().putFloat(CAPSULE_CARD_HEIGHT, fraction.coerceIn(0f, 1f)).apply()
    }

    companion object {
        private const val PREFS = "feature_flags"
        private const val SPEED_FLAGS = "speed_flags"
        private const val SPEED_DEFAULTS_APPLIED = "speed_defaults_applied_v2"
        private const val PARALLEL_MAX = "parallel_max"
        private const val KEEP_SCREEN_ON = "keep_screen_on"
        private const val MUTE_TARGET = "mute_target"
        private const val CAPSULE_MODE = "capsule_mode"
        private const val CAPSULE_SIDE = "capsule_side"
        private const val CAPSULE_HEIGHT = "capsule_height"
        private const val CAPSULE_CARD_HEIGHT = "capsule_card_height"

        fun get(context: Context) = FeatureFlags(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
