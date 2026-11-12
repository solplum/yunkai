package com.wangzan101.mika.ui

import android.content.Context

/** 首次上手的三步。 */
enum class OnboardingStep { Welcome, Model, Phone }

/**
 * 首次打开时走哪几步(纯函数,便于测试)。和手机引导一样按「看过没有」判断,不每次都弹:
 * - 欢迎和配置模型:还没有主模型、也没在上手流程里配过模型时才走。已经配过模型的(包括老用户)直接跳过。
 * - 手机操作:系统够新、手机操作还没就绪、引导没看过时走。
 */
object Onboarding {
    data class Inputs(
        val hasMainModel: Boolean,
        val modelSetupDone: Boolean,
        /** 系统够新且手机操作还没就绪。 */
        val phoneNeeded: Boolean,
        val phoneGuideSeen: Boolean,
    )

    fun start(i: Inputs): OnboardingStep? = when {
        !i.hasMainModel && !i.modelSetupDone -> OnboardingStep.Welcome
        i.phoneNeeded && !i.phoneGuideSeen -> OnboardingStep.Phone
        else -> null
    }

    /** [step] 做完后的下一步;没有了为 null(进对话)。 */
    fun next(step: OnboardingStep, i: Inputs): OnboardingStep? = when (step) {
        OnboardingStep.Welcome -> OnboardingStep.Model
        OnboardingStep.Model -> if (i.phoneNeeded && !i.phoneGuideSeen) OnboardingStep.Phone else null
        OnboardingStep.Phone -> null
    }

    /** 底部分页点:三步里的第几步。 */
    fun dot(step: OnboardingStep): Int = step.ordinal
    const val DOTS = 3
}

/** 上手流程里「配置模型」做完没有;存在本机。 */
class OnboardingState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun modelSetupDone(): Boolean = prefs.getBoolean(MODEL_DONE, false)

    fun markModelSetupDone() {
        prefs.edit().putBoolean(MODEL_DONE, true).apply()
    }

    private companion object {
        const val PREFS = "onboarding"
        const val MODEL_DONE = "model_setup_done"
    }
}
