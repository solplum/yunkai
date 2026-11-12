package com.wangzan101.mika.task

import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.gate.ConfirmationRequest

/**
 * 评测跑器用的钩子。只在测试里设置;产品代码里全是默认值,行为不变。
 * 进程里一份(见 [TaskEnvironment]),同时跑的几个任务共用(自动回答只看提问本身,对哪个任务都一样)。
 */
class EvalHooks {
    /** 追问(ask_user)的自动回答;返回 null 或空白表示不答,任务一直等到评测超时。 */
    @Volatile var autoAnswer: ((prompt: String, options: List<String>?) -> String?)? = null

    /** 遇到接管时评测跑器怎么做:cancel(取消任务,记为 takeover)或 wait(等人处理)。由跑器读取。 */
    @Volatile var onTakeover: String = "cancel"

    /** 单题步数上限;null 用 Agent 的默认值。 */
    @Volatile var maxSteps: Int? = null

    /**
     * 确认关卡的自动回答;非空时不等用户,直接用它的结果(评测里恒为 false:绝不真的发送、删除、下单)。
     * 关卡照常记事件,卡片内容留给评测核对。
     */
    @Volatile var autoDecideConfirmation: ((ConfirmationRequest) -> Boolean)? = null

    /** 起草出的技能草稿直接收下(评测的训练轮)。 */
    @Volatile var autoApproveSkillDrafts: Boolean = false

    /** 覆盖「从办成的任务里学技能」开关;null 用设置里的。评测默认关(EvalRunTest 设 false),只有训练轮打开。 */
    @Volatile var learnSkills: Boolean? = null

    /** 覆盖「自动记住我说过的事」开关;null 用设置里的。评测默认关,只有记忆题集打开。 */
    @Volatile var rememberMemory: Boolean? = null

    fun reset() {
        autoApproveSkillDrafts = false
        learnSkills = null
        rememberMemory = null
        autoAnswer = null
        onTakeover = "cancel"
        maxSteps = null
        autoDecideConfirmation = null
    }

    internal fun answerFor(state: TaskState): String? {
        val s = state as? TaskState.WaitingInput ?: return null
        return autoAnswer?.invoke(s.prompt, s.options)?.trim()?.takeIf { it.isNotEmpty() }
    }

    internal fun decisionFor(state: TaskState): Boolean? {
        val s = state as? TaskState.WaitingConfirmation ?: return null
        return autoDecideConfirmation?.invoke(s.request)
    }
}
