package com.wangzan101.mika.settings

import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.gate.ConfirmationGate

/**
 * 一个任务自己的审批设置:自主与否是开工时取全局的,之后跟着开发者选项里的「自主模式」变([set])。
 * 询问时只有付款先问;自主时连付款也不问。
 */
class RunApproval(startAutonomous: Boolean) : ApprovalSettings {
    /** 开工时就是自主:这个任务没有确认关卡,也不给 request_confirmation;中途关掉自主留给下一个任务。 */
    val startedAutonomous: Boolean = startAutonomous
    @Volatile private var auto = startAutonomous

    override fun autonomous(): Boolean = auto

    fun set(autonomous: Boolean) {
        auto = autonomous
    }

    /** 这个任务(和它的子任务)用的付款确认关卡:开工时是询问才有,每次一份新的。 */
    fun gate(): ConfirmationGate? = if (startedAutonomous) null else ConfirmationGate()
}

/**
 * 询问 / 自主是一个全局开关,存在 [ApprovalStore.autonomous],默认询问。每个任务开工时拿一份自己的([start]);
 * 换开关([setAutonomous]):存下来管之后的任务,正在跑的任务也跟着换(从询问换成自主,下一步起付款也不再问)。
 */
class ApprovalControl(private val store: ApprovalStore) {
    private val runs = mutableSetOf<RunApproval>()

    @Synchronized fun start(): RunApproval =
        RunApproval(store.autonomous.value).also { runs += it }

    @Synchronized fun end(run: RunApproval) {
        runs -= run
    }

    @Synchronized fun setAutonomous(on: Boolean) {
        store.setAutonomous(on)
        runs.forEach { it.set(on) }
    }
}
