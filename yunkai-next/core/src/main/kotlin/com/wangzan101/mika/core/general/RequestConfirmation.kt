// Modified for Yunkai from Mika d68159366abaea523c5e4e6fd4dc83aad78f9698; see UPSTREAM.md and UPSTREAM_CHANGES.json at repository root.
package com.wangzan101.mika.core.general

import com.wangzan101.mika.core.action.ClassifiedRisk
import com.wangzan101.mika.core.gate.Irreversible
import com.wangzan101.mika.core.gate.ConfirmationRequest
import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.CONFIRM_ACTIONS
import com.wangzan101.mika.core.tool.CONFIRM_FIELD_MAX
import com.wangzan101.mika.core.tool.HINT_PAYMENT_TAKEOVER
import com.wangzan101.mika.core.tool.Outcome
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.capField
import com.wangzan101.mika.core.tool.fail
import com.wangzan101.mika.core.tool.okObj
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.uistate.confirmTarget
import com.wangzan101.mika.core.uistate.focus
import com.wangzan101.mika.core.uistate.lastSnapshot
import com.wangzan101.mika.core.uistate.session

/**
 * 付款、不可逆或未知风险的操作前请用户同意;非花钱的操作不虚构金额。付款本身不在这里,交给用户亲手付。
 * 申报的前台核对用此刻重新读的一屏(不登记编号,不动模型手里的 ref)。
 */
internal object RequestConfirmation : SurfaceAware {
    override val verb = Verb.RequestConfirmation
    override val label = "请你同意"
    override val risk = Risk.Read
    override val def = ToolDef("request_confirmation",
        Prompts.tool("request_confirmation"),
        mapOf(
            "action" to str("Kind of approval-requiring action (delete, other, or order for placing an order or booking).", CONFIRM_ACTIONS),
            "target" to str("The affected person, item, shop or service, as shown on screen. At most $CONFIRM_FIELD_MAX characters."),
            "content" to str("What will change or be removed; for purchases, item, spec and quantity. At most $CONFIRM_FIELD_MAX characters."),
            "amount" to str("Required for orders and spending: amount and currency shown on screen. Omit for nonpayment actions."),
            "app" to str(APP_NOTE),
        ), listOf("action", "target", "content", "app"))

    /** app 参数写什么按本任务的屏说:一块屏时写那块的写法,几块屏时各写一句。 */
    override fun def(ctx: ToolContext): ToolDef {
        val base = super.def(ctx)
        val surfaces = ctx.focus.surfaces
        val how = if (surfaces.size == 1) surfaces.single().targetNote
        else surfaces.joinToString("; ") { "on the ${it.id} screen, ${it.targetNote}" }
        return ToolDef(base.name, base.description, base.props + ("app" to str("$APP_NOTE: $how.")), base.required)
    }

    private const val APP_NOTE = "Where the action happens, as on screen now"

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        checkSurface(a, ctx)
        val action = a.str("action")!!.trim()
        val kind = IrrevAction.entries.firstOrNull { it.name == action }
            ?: throw ArgError("action", "must be one of ${CONFIRM_ACTIONS.joinToString("|")}")
        if (kind == IrrevAction.pay) {
            ctx.refusedPay = ConfirmationRequest(kind, capField(a.str("target", max = 10000)!!), capField(a.str("content", max = 10000)!!),
                a.str("amount", max = 10000)?.let(::capField), a.str("app")!!.trim())
            return fail("payment_requires_takeover", HINT_PAYMENT_TAKEOVER)
        }
        // 金额要求与执行前的风险判断一致;不可逆但不花钱的操作不需要金额。
        val screen = ctx.session.peek() ?: ctx.lastSnapshot
        val spending = ClassifiedRisk.assess(Verb.Tap, Irreversible(kind, "")) { screen }.payment
        val amount = a.str("amount", max = 10000)?.trim()?.takeIf { it.isNotEmpty() }
        if ((spending || amount != null) && amount?.any(Char::isDigit) != true) throw ArgError("amount",
            "required for orders and spending: the total the user will pay, as shown on screen; omit only for nonpayment actions")
        val app = ctx.confirmTarget(a.str("app")!!.trim())
        val req = ConfirmationRequest(
            kind, capField(a.str("target", max = 10000)!!), capField(a.str("content", max = 10000)!!),
            amount?.let(::capField), app,
        )
        val fg = screen?.packageName
        return ToolExecution(okObj { put("asked", true) }, outcome = Outcome.Confirm(req, fg))
    }
}
