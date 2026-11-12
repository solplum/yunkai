// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.action

import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.guard.GuardVerdict
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.tool.ToolExecution
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一次动作走完执行入口的结局。 */
sealed interface ActionOutcome {
    /** 执行前的守卫结论。 */
    val before: List<GuardVerdict>

    /**
     * 没有执行。[code]:guard_stopped / guard_warning / confirmation_required / confirmation_mismatch / approval_unavailable / target_unresolved / own_app。
     * [content] 是交给这次调用方的结果(脚本子步、模型单步照它回;批和回放按 [code] 自己说明)。
     */
    data class Refused(
        val code: String, val content: JsonObject, override val before: List<GuardVerdict>, val hit: Irreversible? = null,
    ) : ActionOutcome

    /**
     * 执行了。[exec] 是最终结果(模型单步可能已换成等用户之后的结果或核查退回);[after] 是执行后的守卫结论
     * (页面脚本的子步结论排在前面);[content] 是交给模型、也记进运行记录的内容。
     * [shotId]、[picture]:[Frames.Caller] 时留给调用方存的画面;[seen] 是记路线锚点时动作后读的一屏(没读为 null)。
     */
    data class Ran(
        val exec: ToolExecution, override val before: List<GuardVerdict>, val after: List<GuardVerdict>, val content: JsonObject,
        val durationMs: Long, val shotId: Int? = null, val picture: ByteArray? = null, val seen: ScreenSnapshot? = null,
    ) : ActionOutcome {
        val verdicts: List<GuardVerdict> get() = before + after
    }

    /** 等用户时任务被取消了。 */
    data class Cancelled(override val before: List<GuardVerdict>) : ActionOutcome
}

/** 这次调用对调用方的结果:执行了就是执行结果,没执行就是拒绝说明。 */
fun ActionOutcome.execution(): ToolExecution = when (this) {
    is ActionOutcome.Ran -> exec
    is ActionOutcome.Refused -> ToolExecution(content)
    is ActionOutcome.Cancelled -> ToolExecution(buildJsonObject { put("ok", false); put("error", "cancelled"); put("executed", false) })
}

/** 被拦下时交给调用方的说明,按来源的说法写。 */
internal object Refusals {
    const val SINGLE_USE = "一次同意只放行紧接着的下一个匹配操作,无论它成功与否都会用掉。"
    const val OWN_APP = "own_app"
    const val PAYMENT_TAKEOVER = "付款由用户亲手完成,不用 request_confirmation:打开付款页后调用 hand_to_user(reason=payment)," +
        "item 写商品(规格、数量),amount 写屏上显示的应付金额,address 写收货地址。"
    private val ACTION_LABELS = mapOf(
        IrrevAction.send to "发送", IrrevAction.pay to "付款", IrrevAction.delete to "删除",
        IrrevAction.order to "下单", IrrevAction.submit to "提交", IrrevAction.other to "其他",
    )

    fun content(code: String, style: RefusalStyle, before: List<GuardVerdict>, hit: Irreversible?, reason: String?): JsonObject = when (style) {
        // 模型单步被守卫叫停时任务就此结束,用不到说明
        RefusalStyle.Model -> if (hit == null) notRun(code, if (code == OWN_APP) Feedback["own_app"] else if (code == "target_unresolved") "The target cannot be identified; observe the screen again before acting. Do not request approval for an unknown target." else code) else {
            val blocked = if (reason != null) mismatch(hit, reason) else required(hit)
            if (before.isEmpty()) blocked else JsonObject(blocked + ("guard" to JsonArray(before.map { it.toJson() })))
        }
        RefusalStyle.Script -> {
            val message = when (code) {
                "guard_stopped" -> "Mika's operation guard stopped the task."
                "guard_warning" -> "Mika's operation guard flagged this action; it was not run."
                "confirmation_required" -> "this step requires approval (${hit?.matched})"
                "confirmation_mismatch" -> "this approval-requiring step (${hit?.matched}) does not match what the user approved ($reason)"
                "approval_unavailable" -> "this step requires approval and this run cannot get the user's approval; the action was not run"
                "target_unresolved" -> "that ref is not on the latest snapshot; call mika.snapshot() again"
                OWN_APP -> Feedback["own_app"]
                else -> error("unknown refusal $code")
            }
            notRun(code, message) { hit?.let { put("action", it.action.name); put("matched", it.matched) } }
        }
        RefusalStyle.Code -> notRun(code, code)
    }

    private fun required(hit: Irreversible) = buildJsonObject {
        put("ok", false); put("error", "confirmation_required")
        put("action", hit.action.name); put("matched", hit.matched)
        put("message", if (hit.action == IrrevAction.pay) "这一步是付款,没有执行。$PAYMENT_TAKEOVER"
            else "这一步需要同意(${ACTION_LABELS[hit.action]}),没有执行。先调用 request_confirmation 如实说明在哪、影响哪个对象、做什么;下单或花钱时必须填写屏上金额,非花钱操作不填金额。用户同意后再做。$SINGLE_USE")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun mismatch(hit: Irreversible, reason: String) = buildJsonObject {
        put("ok", false); put("error", "confirmation_mismatch")
        put("action", hit.action.name); put("matched", hit.matched); put("reason", reason)
        put("message", "这一步和用户同意的操作对不上($reason),那次同意已经作废,这一步没有执行。" +
            "如果仍要做,重新调用 request_confirmation 如实说明这一步,用户同意后马上做。")
        put("recoverable", true); put("counts_as_failure", false)
    }

    private fun notRun(error: String, message: String, extra: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("ok", false); put("error", error); put("message", message); put("executed", false)
        extra()
    }
}
