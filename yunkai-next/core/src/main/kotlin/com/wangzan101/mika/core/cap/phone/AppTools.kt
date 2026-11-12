package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.tool.Entry
import com.wangzan101.mika.core.observation.ObsKind
import com.wangzan101.mika.core.observation.Observation
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.Verb
import com.wangzan101.mika.core.tool.argStr
import com.wangzan101.mika.core.tool.bool
import com.wangzan101.mika.core.tool.ok
import com.wangzan101.mika.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.uistate.action
import com.wangzan101.mika.core.uistate.session

/** 列出、打开手机上的 App:永远作用于手机。 */
internal object ListApps : PhoneTool {
    override val verb = Verb.ListApps
    override val label = "查看已装的 App"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val def = ToolDef("phone_list_apps", Prompts.tool("phone_list_apps"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val apps = ctx.phone.screen.listApps()
        val profiles = ctx.phone.screen.otherProfiles()
        return ok {
            put("packages", JsonArray(apps.map { JsonPrimitive(it.first) }))
            put("apps", JsonArray(apps.map { (p, l) -> buildJsonObject {
                put("package", p); put("label", l)
                // 也装在分身、工作资料一类的另一份用户里:打开时系统可能先问打开哪一个
                profiles[p]?.takeIf { it.isNotEmpty() }?.let { put("alsoIn", JsonArray(it.map(::JsonPrimitive))) }
            } }))
        }
    }

    /** 列出的 App 名字不单独作证据,记下来给之后打开 App 的事实用。 */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        (r.content["apps"] as? JsonArray)?.forEach { a ->
            val o2 = a as? JsonObject ?: return@forEach
            val p = (o2["package"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            (o2["label"] as? JsonPrimitive)?.contentOrNull?.let { log.appLabels[p] = it }
        }
        return emptyList()
    }
}

internal object LaunchApp : PhoneTool {
    override val verb = Verb.LaunchApp
    override val batchable = true
    override val routable = true
    override val label = "打开 App"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_launch_app", Prompts.tool("phone_launch_app"),
        mapOf("package" to str("Exact package name, e.g. com.example.app."), "cold" to bool("Restart the app first (default false).")),
        listOf("package"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        // 换了 App,之前被拒的付款申报就不是这次的了
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.openApp(p, a.bool("cold", false)))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return unreadable(ctx, p, r)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("package")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            Observation(ObsKind.Fact, "$name 打开了 $p" + (log.appLabels[p]?.let { "(App 名字「$it」)" } ?: ""), source = PHONE)
        })
    }
}

/**
 * 把一段文字分享给一个 App(ACTION_SEND text/plain),在 Mika 的屏上打开它的分享页:收件人、发出都在那一页上点,
 * 这里不发出任何东西。读不到的界面里打不进字时,经分享把字带进去。
 */
internal object ShareText : PhoneTool {
    override val verb = Verb.ShareText
    override val label = "分享文字到 App"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_share_text", Prompts.tool("phone_share_text"),
        mapOf(
            "package" to str("Exact package name of the app to share the text to, e.g. com.example.app."),
            "text" to buildJsonObject { put("type", "string"); put("description", "The text to share, 1-10000 characters."); put("minLength", 1); put("maxLength", 10000) },
            "reason" to str("Optional: why you share it, e.g. the message the user asked to send to a contact."),
        ),
        listOf("package", "text"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val text = a.str("text", max = 10000)!!
        // 换了 App,之前被拒的付款申报就不是这次的了
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.shareText(p, text))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        // 系统先问用哪一个(装了两份、分享页不止一个)时提示已经说了怎么选;到了分享页再说下一步
        val opened = if (r.content["chooser"] != null) r else withHint(r, Feedback["share.opened"])
        return unreadable(ctx, p, opened)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("package")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            Observation(ObsKind.Fact, "$name 打开了 $p 的分享页(还没发出)" + (log.appLabels[p]?.let { "(App 名字「$it」)" } ?: ""), source = PHONE)
        })
    }
}

private fun withHint(r: ToolExecution, hint: String): ToolExecution {
    val all = listOfNotNull((r.content["hint"] as? JsonPrimitive)?.content, hint).joinToString(" ")
    return ToolExecution(JsonObject(r.content + ("hint" to JsonPrimitive(all))), changed = r.changed)
}

/** 到了前台却读不到窗口(安全窗口、启动画面……):照实说,免得模型以为没打开又去启动。 */
private suspend fun unreadable(ctx: ToolContext, p: String, r: ToolExecution): ToolExecution {
    val s = ctx.session.peek()
    if (s?.unreadableApp != p || s.elements.any { it.meaningful }) return r
    return withHint(ToolExecution(JsonObject(r.content + ("readable" to JsonPrimitive(false))), changed = r.changed), Feedback.format("unreadable_window", "app" to p))
}
