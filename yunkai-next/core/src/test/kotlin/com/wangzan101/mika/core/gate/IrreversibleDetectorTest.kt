package com.wangzan101.mika.core.gate

import com.wangzan101.mika.core.risk.IrrevAction
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun el(
    ref: String, name: String, bounds: List<Int>, res: String? = null, clickable: Boolean = true,
    editable: Boolean = false, focused: Boolean = false, scrollable: Boolean = false, hint: Boolean = false,
) = UiElement(ref, if (editable) "edit" else "button", name, 0, 0, bounds, res, clickable, editable, scrollable, hint = hint, focused = focused)

private fun snap(vararg e: UiElement, id: String = "s1") = ScreenSnapshot(id, "com.chat", e.toList(), 1080, 2400)
private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

class IrreversibleDetectorTest {
    private val d = IrreversibleDetector

    @Test fun chineseSendButton() {
        val s = snap(el("@e1", "发送", listOf(800, 900, 990, 990)), el("@e2", "发送(1)", listOf(0, 0, 100, 100)), el("@e3", "确认支付", listOf(0, 200, 100, 300)))
        assertEquals(Irreversible(IrrevAction.send, "发送"), d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), s))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), s)?.action)
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s)?.action)
        assertNull(d.matchName("发送给朋友的那张照片在哪里"))
        for ((w, a) in listOf("删除" to IrrevAction.delete, "退出群聊" to IrrevAction.delete, "提交订单" to IrrevAction.order,
            "立即购买" to IrrevAction.order, "转账" to IrrevAction.pay, "发 布" to IrrevAction.send, "报名" to IrrevAction.order)) {
            assertEquals(w, a, d.matchName(w))
        }
    }

    @Test fun englishAndCoordinateTaps() {
        val s = snap(
            el("@e1", "", listOf(0, 0, 999, 999), clickable = false),
            el("@e2", "Place order", listOf(100, 800, 900, 900)),
            el("@e3", "Buy Now", listOf(100, 600, 900, 700)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"x":500,"y":850}"""), s)?.action)
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"x":[400,600],"y":650}"""), s)?.action)
        assertNull(d.classify("phone_tap", args("""{"x":500,"y":300}"""), s))
        for ((w, a) in listOf("Send" to IrrevAction.send, "post" to IrrevAction.send, "Pay now" to IrrevAction.pay,
            "Delete" to IrrevAction.delete, "Clear all" to IrrevAction.delete, "Checkout" to IrrevAction.order)) {
            assertEquals(w, a, d.matchName(w))
        }
        val r = snap(el("@e1", "", listOf(0, 0, 100, 100), res = "com.chat:id/btnSend"))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), r)?.action)
    }

    @Test fun iconOnlyButtonFallsBackToTargetLabel() {
        val icon = snap(el("@e1", "", listOf(800, 900, 990, 990), res = "com.chat:id/iv_icon"))
        assertNull(d.classify("phone_tap", args("""{"x":900,"y":950}"""), icon))
        assertEquals(Irreversible(IrrevAction.send, "发送"), d.classify("phone_tap", args("""{"x":900,"y":950,"target":"发送"}"""), icon))
        val box = snap(el("@e1", "", listOf(800, 900, 990, 990)), el("@e2", "删除", listOf(820, 920, 980, 980), clickable = false))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), box)?.action)
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"x":1,"y":1,"target":"删除"}"""), null)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e9","snapshotId":"old","target":"Send"}"""), icon)?.action)
        assertTrue(d.unresolved("phone_tap", args("""{"ref":"@e1","snapshotId":"old"}"""), icon))
        assertTrue(d.unresolved("phone_tap", args("""{"x":1,"y":1}"""), null))
        assertFalse(d.unresolved("phone_tap", args("""{"x":900,"y":950}"""), icon))
    }

    // M6:看图点的位置上没有带名字的元素(自绘界面、没名字的图标)时,按同一行的文字判
    @Test fun unnamedSpotJudgedByRowText() {
        val s = snap(
            el("@e1", "", listOf(0, 0, 999, 999), clickable = false),
            el("@e2", "删除", listOf(800, 400, 990, 460), clickable = false),
            el("@e3", "", listOf(50, 400, 150, 460)),
            el("@e4", "张三", listOf(0, 600, 999, 660), clickable = false),
            el("@e5", "删除记录说明", listOf(0, 0, 999, 300), clickable = false),
        )
        assertEquals(Irreversible(IrrevAction.delete, "删除"), d.classify("phone_tap", args("""{"x":100,"y":430}"""), s))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"x":400,"y":430}"""), s)?.action)
        assertNull(d.classify("phone_tap", args("""{"x":400,"y":630}"""), s))
        // 太高的元素(容器、大段文字)不算「同一行」
        assertNull(d.classify("phone_tap", args("""{"x":400,"y":200}"""), s))
        // 点中带名字的元素时只按它判
        assertNull(d.classify("phone_tap", args("""{"x":500,"y":640}"""), s))
    }

    // 一点就替用户联系别人、投申请的按钮按提交算;已有的对话、记录、客服页、只打开写消息的地方不算
    @Test fun contactAndApplyButtonsAreSubmissions() {
        for (w in listOf("立即沟通", "沟通", "打招呼", "立即打招呼", "一键投递", "投递简历", "投简历", "立即申请", "申请职位", "应聘",
            "联系TA", "发起聊天", "立即沟通 >", "Say hi", "say  hello!", "Easy Apply", "Apply now", "Contact recruiter")) {
            assertEquals(w, IrrevAction.submit, d.matchName(w))
        }
        for (w in listOf("继续沟通", "已沟通", "沟通记录", "已投递", "投递记录", "联系客服", "联系我们", "消息", "私信", "申请", "申请权限",
            "Apply", "Contact", "Contact us", "Message", "Messages", "沟通技巧有哪些")) {
            assertNull(w, d.matchName(w))
        }
        // 按钮在职位详情里(列表右侧):按编号点它要确认
        val s = snap(el("@e1", "AI Agent 工程师", listOf(300, 100, 999, 160), clickable = false), el("@e2", "立即沟通", listOf(800, 180, 990, 230)),
            el("@e3", "感兴趣", listOf(600, 180, 780, 230)))
        assertEquals(Irreversible(IrrevAction.submit, "立即沟通"), d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), s))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s))
    }

    @Test fun submitOnlyExactMatch() {
        assertEquals(IrrevAction.submit, d.matchName("提交"))
        assertEquals(IrrevAction.submit, d.matchName("确认提交"))
        assertEquals(IrrevAction.submit, d.matchName(" Submit "))
        assertNull(d.matchName("提交搜索"))
        assertNull(d.matchName("Submitted"))
        val s = snap(el("@e1", "", listOf(0, 0, 100, 100), res = "com.x:id/search_submit"))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), s))
    }

    @Test fun searchEnterIsNotSend() {
        val s = snap(el("@e1", "搜索商品", listOf(0, 0, 999, 100), res = "com.shop:id/search_input", editable = true))
        assertNull(d.classify("phone_type_text", args("""{"text":"耳机","pressEnter":true}"""), s))
        assertNull(d.classify("phone_type_text", args("""{"text":"耳机","pressEnter":true,"ref":"@e1","snapshotId":"s1"}"""), s))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), s))
        val focusedSearch = snap(el("@e1", "Search", listOf(0, 0, 999, 100), editable = true, focused = true, hint = true), el("@e2", "备注", listOf(0, 200, 999, 300), editable = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"66"}"""), focusedSearch))
    }

    @Test fun chatEnterIsSend() {
        val s = snap(el("@e1", "说点什么...", listOf(0, 900, 800, 999), editable = true))
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":"你好","pressEnter":true}"""), s)?.action)
        assertNull(d.classify("phone_type_text", args("""{"text":"你好"}"""), s))
        val r = snap(el("@e1", "", listOf(0, 900, 800, 999), res = "com.chat:id/chat_input", editable = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), r)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":"hi","pressEnter":true,"ref":"@e1","snapshotId":"s1"}"""), r)?.action)
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_DEL"}"""), r))
    }

    @Test fun readOnlyToolsNeverMatch() {
        val s = snap(el("@e1", "发送", listOf(0, 0, 999, 999)))
        for (t in listOf("phone_observe", "phone_list_elements", "phone_screenshot", "phone_list_apps", "phone_diagnose", "wait", "knowledge_search", "knowledge_read", "skill_search", "skill_read",
            "phone_back", "phone_clear_text", "phone_launch_app", "ask_user", "request_confirmation", "finish", "end_not_done")) {
            assertNull(t, d.classify(t, args("""{"ref":"@e1","snapshotId":"s1","x":500,"y":500,"target":"发送"}"""), s))
        }
    }

    // C1:坐标点击按此刻重新读的一屏判,不按模型上次看到的
    @Test fun coordinateTapUsesFreshRead() {
        val old = snap(el("@e1", "+", listOf(800, 900, 990, 990)))
        val now = snap(el("@e1", "发送", listOf(800, 900, 990, 990)), id = "fresh")
        assertNull(d.classify("phone_tap", args("""{"x":900,"y":950}"""), old))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"x":900,"y":950}"""), old, now)?.action)
        // ref:原读屏或此刻同位置的元素,任一命中就算
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), old, now)?.action)
    }

    // C2:按键码判(数字写法、空格),输入框默认按发送,只放过搜索框;焦点按钮按词表
    @Test fun activatingKeysAndFocus() {
        val plain = snap(el("@e1", "备注", listOf(0, 0, 999, 100), editable = true, focused = true))
        for (k in listOf("066", "KEYCODE_NUMPAD_ENTER", "23")) assertEquals(k, IrrevAction.send, d.classify("phone_press_key", args("""{"key":"$k"}"""), plain)?.action)
        assertNull("空格在输入框里只是打字", d.classify("phone_press_key", args("""{"key":"KEYCODE_SPACE"}"""), plain))
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":"x","pressEnter":true}"""), plain)?.action)
        val button = snap(el("@e1", "发送", listOf(0, 0, 999, 100), focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_SPACE"}"""), button)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_DPAD_CENTER"}"""), button)?.action)
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), snap(el("@e1", "返回", listOf(0, 0, 999, 100), focused = true))))
        // 看不出焦点、或屏上没有输入框:算命中
        assertNotNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), snap(el("@e1", "标题", listOf(0, 0, 999, 100), clickable = false))))
        assertNotNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), null))
        assertNotNull(d.classify("phone_type_text", args("""{"text":"x","pressEnter":true}"""), snap(el("@e1", "备注", listOf(0, 0, 999, 100), editable = true))))
    }

    // I2/M1:强词、通用确认、英文短语、排除词、计数入口
    @Test fun wordMatchingExamples() {
        val hits = mapOf(
            "删除聊天记录" to IrrevAction.delete, "撤回" to IrrevAction.delete, "转发给朋友" to IrrevAction.send,
            "发红包" to IrrevAction.pay, "申请退款" to IrrevAction.order, "取消订单" to IrrevAction.order, "确认收货" to IrrevAction.order,
            "去结算(3)" to IrrevAction.order, "使用支付宝支付" to IrrevAction.pay, "Send message" to IrrevAction.send,
            "Post comment" to IrrevAction.send, "Delete conversation" to IrrevAction.delete, "DELETE" to IrrevAction.delete,
            "评论" to IrrevAction.send, "回复:" to IrrevAction.send, "立即预约" to IrrevAction.order, "确认报名" to IrrevAction.order,
            "张三 删除" to IrrevAction.delete,
        )
        for ((w, a) in hits) assertEquals(w, a, d.matchName(w))
        for (w in listOf("评论(23)", "回复 3", "支付宝", "支付方式", "支付设置", "预约挂号指南", "Postpone", "Sender", "Payment history",
            "提交搜索", "确定", "OK", "这是一段很长的聊天消息里面提到了删除两个字")) {
            assertNull(w, d.matchName(w))
        }
        // 通用确认:同屏有强词或金额才算
        val dialog = snap(el("@e1", "确定删除这条消息?", listOf(0, 300, 999, 400), clickable = false), el("@e2", "确定", listOf(500, 500, 999, 600)))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), dialog)?.action)
        val sheet = snap(el("@e1", "实付 ¥ 39.90", listOf(0, 300, 999, 400), clickable = false), el("@e2", "确认", listOf(500, 500, 999, 600)))
        // 只有金额、没有付款词:按下单(同意时金额要和「实付」对得上)
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), sheet)?.action)
        val plain = snap(el("@e1", "已保存", listOf(0, 300, 999, 400), clickable = false), el("@e2", "完成", listOf(500, 500, 999, 600)))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), plain))
        // 资源 ID 里的 post 不算
        assertNull(d.classify("phone_tap", args("""{"ref":"@e1","snapshotId":"s1"}"""), snap(el("@e1", "", listOf(0, 0, 99, 99), res = "com.x:id/post_list"))))
    }

    // I3:够长的横滑从列表行起手 = 滑动删除
    @Test fun longHorizontalSwipeOnRowIsDelete() {
        val s = snap(el("@e1", "张三 昨天", listOf(0, 300, 999, 400)), el("@e2", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true))
        assertEquals(Irreversible(IrrevAction.delete, "swipe on 张三 昨天"),
            d.classify("phone_swipe", args("""{"startX":900,"startY":350,"endX":300,"endY":350}"""), null, s))
        assertNull("太短", d.classify("phone_swipe", args("""{"startX":900,"startY":350,"endX":600,"endY":350}"""), null, s))
        assertNull("竖滑", d.classify("phone_swipe", args("""{"startX":500,"startY":800,"endX":500,"endY":200}"""), null, s))
        assertNull("不在行上", d.classify("phone_swipe", args("""{"startX":900,"startY":700,"endX":300,"endY":700}"""), null, s))
        assertEquals(IrrevAction.delete, d.classify("phone_scroll", args("""{"direction":"left","amount":5,"x":500,"y":350}"""), null, s)?.action)
        assertNull("默认幅度不够长", d.classify("phone_scroll", args("""{"direction":"left","x":500,"y":350}"""), null, s))
        assertNull(d.classify("phone_scroll", args("""{"direction":"down","amount":10}"""), null, s))
    }

    // 第二轮 3:金额弹窗压在商品页上,按付款算,不能借「下单」放行
    @Test fun amountOverOrderPageIsOrderPayWordIsPay() {
        // 商品页上选规格:立即购买 + 价格 + 确定,没有付款词 → 下单(可同意,并按金额绑定),不是付款
        val s = snap(
            el("@e1", "立即购买", listOf(0, 900, 999, 999)),
            el("@e2", "¥25.00", listOf(0, 500, 999, 560), clickable = false),
            el("@e3", "确认", listOf(500, 600, 999, 700)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s)?.action)
        // 付款面板:¥25.00 + 确认 + 支付 → 付款
        val sheet = snap(
            el("@e1", "支付", listOf(0, 300, 999, 360), clickable = false),
            el("@e2", "¥25.00", listOf(0, 400, 999, 460), clickable = false),
            el("@e3", "确认", listOf(500, 600, 999, 700)),
        )
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), sheet)?.action)
        // 同屏有付款词:付款优先
        val both = snap(el("@e1", "删除", listOf(0, 0, 99, 99)), el("@e2", "付款", listOf(0, 200, 99, 299)), el("@e3", "确定", listOf(500, 600, 999, 700)))
        assertEquals(IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), both)?.action)
        // 删除确认框里有价格,仍是删除
        val del = snap(el("@e1", "确定删除这件商品?", listOf(0, 300, 999, 400), clickable = false), el("@e2", "¥25.00", listOf(0, 0, 99, 99), clickable = false), el("@e3", "确定", listOf(500, 600, 999, 700)))
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), del)?.action)
    }

    // 第二轮 4:订单状态、记录一类的标签不是动作
    @Test fun statusLabelsAreNotActions() {
        for (w in listOf("待付款", "待支付", "付款成功", "已支付", "退款/售后", "购买记录", "微信红包", "支付明细", "删除记录列表")) {
            assertNull(w, d.matchName(w))
        }
        for ((w, a) in listOf("发红包" to IrrevAction.pay, "塞钱进红包" to IrrevAction.pay, "红包" to IrrevAction.pay,
            "申请退款" to IrrevAction.order, "确认付款" to IrrevAction.pay)) {
            assertEquals(w, a, d.matchName(w))
        }
        // 状态标签也不给通用「确认」当上下文
        val tabs = snap(el("@e1", "待付款", listOf(0, 0, 99, 99)), el("@e2", "确认", listOf(500, 600, 999, 700)))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), tabs))
    }

    // 第二轮 5:搜索框里回车不算发送
    @Test fun searchFieldsRecognized() {
        // 只显示推荐词的搜索框,靠资源 ID 认出
        val hinted = snap(UiElement("@e1", "edit", "iPhone 16 Pro", 0, 0, listOf(0, 50, 800, 120), "search_edit_frame", true, true, false, hint = true, focused = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), hinted))
        // 结果页上留着上次的搜索词,同一行有「搜索」按钮
        val results = snap(el("@e1", "耳机", listOf(0, 50, 800, 120), editable = true, focused = true), el("@e2", "搜索", listOf(820, 60, 990, 110)))
        assertNull(d.classify("phone_type_text", args("""{"text":"蓝牙耳机","pressEnter":true}"""), results))
        assertNull(d.classify("phone_press_key", args("""{"key":"66"}"""), results))
        // 「搜索」按钮在别的行:仍按发送
        val chat = snap(el("@e1", "耳机", listOf(0, 900, 800, 999), editable = true, focused = true), el("@e2", "搜索", listOf(820, 60, 990, 110)))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"66"}"""), chat)?.action)
    }

    // 可选:起手点在矮的可滚动区(轮播)里不算滑动删除
    @Test fun swipeInsideCarouselIsNotDelete() {
        val s = snap(el("@e1", "推荐 新品", listOf(0, 300, 999, 400)), el("@e2", "", listOf(0, 250, 999, 450), clickable = false, scrollable = true))
        assertNull(d.classify("phone_swipe", args("""{"startX":900,"startY":350,"endX":300,"endY":350}"""), null, s))
    }

    // 0b-1:输入框里打的字不是提示语,里面的「搜索」「search」不算搜索框
    @Test fun searchWordsInTypedTextAreNotSearch() {
        val chat = snap(el("@e1", "帮我搜索一下", listOf(0, 900, 800, 999), editable = true, focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), chat)?.action)
        val en = snap(el("@e1", "research this", listOf(0, 900, 800, 999), editable = true, focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_type_text", args("""{"text":" please","pressEnter":true}"""), en)?.action)
        // 提示语里的「搜索」仍算
        val hinted = snap(el("@e1", "搜索商品", listOf(0, 0, 999, 100), editable = true, focused = true, hint = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), hinted))
    }

    // 0b-3:可滚动区里的价格不算上下文;只有金额没有付款词的确认按下单算
    @Test fun amountsInsideScrollablesAreNotContext() {
        // 筛选面板:可滚动的价格列表 + 底部「确定」
        val filter = snap(
            el("@e1", "", listOf(0, 200, 999, 800), clickable = false, scrollable = true),
            el("@e2", "¥0-50", listOf(0, 250, 999, 300)),
            el("@e3", "¥50-100", listOf(0, 320, 999, 370)),
            el("@e4", "确定", listOf(500, 850, 999, 950)),
        )
        assertNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), filter))
        // 列表里的价格在滚动区里,滚动区外的金额才算
        val sizes = snap(
            el("@e1", "", listOf(0, 0, 999, 700), clickable = false, scrollable = true),
            el("@e2", "¥99.00", listOf(0, 100, 999, 160), clickable = false),
            el("@e3", "立即购买", listOf(0, 900, 999, 999)),
            el("@e4", "确定", listOf(500, 750, 999, 850)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), sizes)?.action)
        assertTrue(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), sizes)?.action != IrrevAction.pay)
    }

    // 修复 1 C1:付款面板整个在可滚动区里、点的确认也在里面,金额照样算上下文;合计一类总算
    @Test fun sheetInsideScrollableStillHasContext() {
        val sheet = snap(
            el("@e0", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true),
            el("@e1", "实付 ¥ 39.90", listOf(0, 300, 999, 400), clickable = false),
            el("@e2", "确认", listOf(500, 500, 999, 600)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), sheet)?.action)
        val plainPrice = snap(
            el("@e0", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true),
            el("@e1", "¥ 39.90", listOf(0, 300, 999, 400), clickable = false),
            el("@e2", "确认", listOf(500, 500, 999, 600)),
        )
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), plainPrice))
        // 合计在滚动区里、按钮在外面:合计仍算
        val total = snap(
            el("@e0", "", listOf(0, 0, 999, 800), clickable = false, scrollable = true),
            el("@e1", "合计 ¥88", listOf(0, 300, 999, 400), clickable = false),
            el("@e2", "确定", listOf(500, 850, 999, 950)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), total)?.action)
    }

    // 修复 1 I2:有金额时付款方式、英文 Pay、任意长度段里的支付/付款都按付款;新的付款强词
    @Test fun paySheetWordingWithAmountIsPay() {
        // 底部付款面板:金额、付款方式、确认按钮都在下半屏
        fun sheet(word: String) = snap(
            el("@e1", "¥25.00", listOf(0, 600, 999, 660), clickable = false),
            el("@e2", word, listOf(0, 700, 999, 760), clickable = false),
            el("@e3", "确认", listOf(500, 850, 999, 950)),
        )
        for (w in listOf("支付方式", "付款方式", "支付宝", "零钱", "账户余额", "招商银行卡(1234)", "收银台", "免密支付已开启", "使用指纹", "请输入支付密码",
            "Pay with card", "Payment method", "Checkout total", "本次将从你的账户中支付这笔款项给商家完成订单")) {
            assertEquals(w, IrrevAction.pay, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), sheet(w))?.action)
        }
        for (w in listOf("收银台", "买单", "充值", "提现", "转出", "打赏", "缴费")) assertEquals(w, IrrevAction.pay, d.matchName(w))
        for (w in listOf("待付款", "充值记录", "提现明细")) assertNull(w, d.matchName(w))
        // 没有金额时付款方式字样不算
        val noAmount = snap(el("@e1", "零钱", listOf(0, 400, 999, 460), clickable = false), el("@e2", "确认", listOf(500, 600, 999, 700)))
        assertNull(d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), noAmount))
    }

    // 修复 1 M9:资源 ID 里的 search 按单词认
    @Test fun searchResourceIdOnWordBoundary() {
        val research = snap(el("@e1", "", listOf(0, 900, 800, 999), res = "com.x:id/research_input", editable = true, focused = true))
        assertEquals(IrrevAction.send, d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), research)?.action)
        val camel = snap(el("@e1", "", listOf(0, 0, 800, 99), res = "com.x:id/etSearch", editable = true, focused = true))
        assertNull(d.classify("phone_press_key", args("""{"key":"KEYCODE_ENTER"}"""), camel))
    }

    // 修复 2-1:金额拆成几段(实付 | ¥ | 39.90)也算上下文;合计一类字样没带数也算
    @Test fun splitAmountRowIsContext() {
        fun row(scrolled: Boolean) = snap(
            *listOfNotNull(
                if (scrolled) el("@e0", "", listOf(0, 0, 999, 999), clickable = false, scrollable = true) else null,
                el("@e1", "实付", listOf(0, 700, 200, 760), clickable = false),
                el("@e2", "¥", listOf(210, 700, 240, 760), clickable = false),
                el("@e3", "39.90", listOf(250, 700, 400, 760), clickable = false),
                el("@e4", "确认", listOf(500, 850, 999, 950)),
            ).toTypedArray(),
        )
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), row(false)))
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), row(true)))
        val noLabel = snap(
            el("@e2", "¥", listOf(210, 700, 240, 760), clickable = false),
            el("@e3", "39.90", listOf(250, 700, 400, 760), clickable = false),
            el("@e4", "确认", listOf(500, 850, 999, 950)),
        )
        assertNotNull(d.classify("phone_tap", args("""{"ref":"@e4","snapshotId":"s1"}"""), noLabel))
    }

    // 修复 2-2:介绍付款方式的字样不算付款面板;新的付款词只认按钮本身
    @Test fun paymentOptionAdsAreNotPaySheets() {
        val picker = snap(
            el("@e1", "支持花呗付款", listOf(0, 600, 999, 660), clickable = false),
            el("@e2", "¥99.00", listOf(0, 700, 999, 760), clickable = false),
            el("@e3", "确定", listOf(500, 850, 999, 950)),
        )
        assertEquals(IrrevAction.order, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), picker)?.action)
        val cod = snap(
            el("@e1", "货到付款", listOf(0, 300, 999, 360), clickable = false),
            el("@e2", "¥99.00", listOf(0, 200, 999, 260), clickable = false),
            el("@e3", "确定", listOf(500, 850, 999, 950)),
        )
        assertTrue(d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), cod)?.action != IrrevAction.pay)
        // 页面上半部的「余额」不是付款面板
        val page = snap(
            el("@e1", "账户余额", listOf(0, 100, 999, 160), clickable = false),
            el("@e2", "¥99.00", listOf(0, 200, 999, 260), clickable = false),
            el("@e3", "确定", listOf(500, 850, 999, 950)),
        )
        assertTrue(d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), page)?.action != IrrevAction.pay)
        for (w in listOf("充值中心", "话费充值", "生活缴费", "优惠买单", "收款码", "提现入口")) assertNull(w, d.matchName(w))
        for (w in listOf("立即充值", "确认缴费", "去买单", "充值")) assertEquals(w, IrrevAction.pay, d.matchName(w))
    }

    // 改输入框里的字不是不可逆操作:清空搜索框、点进有字的输入框、清空文字工具都不拦;之后的提交照常判
    @Test fun editingTextInAFieldIsNeverIrreversible() {
        val field = el("@e1", "删除聊天记录怎么恢复", listOf(100, 100, 800, 160), editable = true)
        val s = snap(
            field,
            el("@e2", "清空", listOf(810, 100, 880, 160)),
            el("@e3", "", listOf(740, 110, 790, 150), res = "com.app:id/iv_clear"),
            el("@e4", "搜索", listOf(890, 100, 990, 160)),
            el("@e5", "清空", listOf(100, 900, 300, 960)),
            el("@e6", "", listOf(700, 110, 735, 150)),
        )
        for (ref in listOf("@e1", "@e2", "@e3", "@e6")) {
            assertNull(ref, d.classify("phone_tap", args("""{"ref":"$ref","snapshotId":"s1","target":"清空搜索框"}"""), s))
        }
        // 坐标点在清空键上
        assertNull(d.classify("phone_tap", args("""{"x":845,"y":130}"""), s))
        // 清空文字、只打字不回车:不判
        assertFalse(d.needsCheck("phone_clear_text", args("""{"ref":"@e1","snapshotId":"s1"}""")))
        assertNull(d.classify("phone_type_text", args("""{"ref":"@e1","snapshotId":"s1","text":"删除"}"""), s))
        // 离输入框很远的「清空」(清空购物车、清空历史)照样是删除
        assertEquals(IrrevAction.delete, d.classify("phone_tap", args("""{"ref":"@e5","snapshotId":"s1"}"""), s)?.action)
    }

    @Test fun namedSendInsideAnEditorStillCounts() {
        // 网页编辑区整块可编辑,发送键画在里面:有名字的照常判
        val s = snap(el("@e1", "", listOf(0, 800, 999, 990), editable = true), el("@e2", "发送", listOf(850, 900, 990, 980)),
            el("@e3", "", listOf(700, 900, 840, 980), res = "com.app:id/btn_send"))
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e2","snapshotId":"s1"}"""), s)?.action)
        assertEquals(IrrevAction.send, d.classify("phone_tap", args("""{"ref":"@e3","snapshotId":"s1"}"""), s)?.action)
    }

    @Test fun browserInteractKeysAndDrags() {
        val tool = "web_interact"
        val s = snap(
            el("@e1", "留言", listOf(0, 100, 999, 200), editable = true, focused = true),
            el("@e2", "删除", listOf(800, 900, 990, 990)),
            el("@e3", "滑动支付", listOf(0, 500, 999, 600)),
            el("@e4", "卡片", listOf(0, 300, 999, 400)),
        )
        for (k in listOf("Enter", "Control+Enter", "Shift+Return")) {
            assertTrue(k, d.needsCheck(tool, args("""{"action":"key","key":"$k"}""")))
            assertEquals(k, IrrevAction.send, d.classify(tool, args("""{"action":"key","key":"$k"}"""), s)?.action)
        }
        // 空格在按钮上和点一下一样;在输入框里只是打字
        assertTrue(d.needsCheck(tool, args("""{"action":"key","key":"Space"}""")))
        assertNull(d.classify(tool, args("""{"action":"key","key":"Space"}"""), s))
        val onButton = snap(el("@e2", "删除", listOf(800, 900, 990, 990), focused = true))
        assertEquals(IrrevAction.delete, d.classify(tool, args("""{"action":"key","key":"Space"}"""), onButton)?.action)
        // 激活键(回车、空格及带修饰键的形式)按点焦点判:焦点在「删除」上就拦,输入框里的空格是打字
        for (k in listOf("Shift+Space", "Control+Space", "Control+Enter")) {
            val a = args("""{"action":"key","key":"$k"}""")
            assertTrue(k, d.needsCheck(tool, a))
            assertEquals(k, IrrevAction.delete, d.classify(tool, a, onButton)?.action)
        }
        assertNull(d.classify(tool, args("""{"action":"key","key":"Shift+Space"}"""), s))
        // 挪焦点、关闭不判,焦点在「删除」上也一样;方向键只对选择控件
        for (k in listOf("Tab", "Shift+Tab", "Escape", "ArrowDown", "End")) {
            assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), onButton))
        }
        // 输入框里的键都是编辑
        for (k in listOf("Tab", "Escape", "Control+A", "ArrowDown", "d", "Backspace", "Delete", "Control+D")) {
            assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), s))
        }
        assertFalse(d.needsCheck(tool, args("""{"action":"key","key":"bogus"}""")))
    }

    @Test fun browserInteractSelectionKeysJudgedByTheControl() {
        val tool = "web_interact"
        fun ui(ref: String, role: String, name: String, b: List<Int>, focused: Boolean = false) =
            UiElement(ref, role, name, 0, 0, b, null, true, false, false, focused = focused)
        val list = snap(
            ui("@e1", "listbox", "操作", listOf(0, 100, 999, 400), focused = true),
            ui("@e2", "option", "归档", listOf(0, 100, 999, 200)),
            ui("@e3", "option", "删除", listOf(0, 200, 999, 300)),
        )
        val radios = snap(
            ui("@e1", "radio", "普通配送", listOf(0, 100, 500, 200), focused = true),
            ui("@e2", "radio", "立即支付", listOf(500, 100, 999, 200)),
        )
        val city = snap(ui("@e1", "combobox", "城市", listOf(0, 100, 999, 200), focused = true), ui("@e2", "button", "删除", listOf(0, 500, 999, 600)))
        val button = snap(ui("@e1", "button", "删除", listOf(0, 100, 999, 200), focused = true))
        for (k in listOf("ArrowDown", "ArrowUp", "Home", "End", "PageDown", "d", "Shift+D")) {
            val a = args("""{"action":"key","key":"$k"}""")
            assertEquals(k, IrrevAction.delete, d.classify(tool, a, list)?.action)
            assertEquals(k, IrrevAction.pay, d.classify(tool, a, radios)?.action)
            // 控件和它能选到的项都无害
            assertNull(k, d.classify(tool, a, city))
        }
        // 不是选择控件的按钮不会被方向键激活(字母在按钮上是命令键,见 browserInteractCommandKeys)
        for (k in listOf("ArrowDown", "ArrowUp", "Home", "End", "PageDown")) assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), button))
        // 选择控件上:挪焦点、关闭不判,带 Ctrl 的字母不是按首字跳选
        for (k in listOf("Tab", "Escape", "Control+D")) assertNull(k, d.classify(tool, args("""{"action":"key","key":"$k"}"""), list))
    }

    @Test fun browserInteractCommandKeys() {
        val tool = "web_interact"
        fun ui(ref: String, name: String, b: List<Int>, focused: Boolean = false, keys: List<String> = emptyList()) =
            UiElement(ref, "button", name, 0, 0, b, null, true, false, false, focused = focused, keyShortcuts = keys)
        fun hit(k: String, s: ScreenSnapshot) = d.classify(tool, args("""{"action":"key","key":"$k"}"""), s)
        // 焦点在元素上:删除、退格、单个字母数字、Ctrl/Alt/Meta+字母都按点它判
        val onTrash = snap(ui("@e1", "删除", listOf(0, 0, 999, 100), focused = true))
        val onCard = snap(ui("@e1", "卡片", listOf(0, 0, 999, 100), focused = true))
        for (k in listOf("Delete", "Backspace", "d", "7", "Shift+D", "Control+D", "Alt+1", "Meta+Shift+K")) {
            assertTrue(k, d.needsCheck(tool, args("""{"action":"key","key":"$k"}""")))
            assertEquals(k, IrrevAction.delete, hit(k, onTrash)?.action)
            assertNull(k, hit(k, onCard))
        }
        // 没有焦点元素(页面本身):删除、退格作用在页面的选中项上,一律拦
        val page = snap(ui("@e1", "卡片", listOf(0, 0, 999, 100)))
        assertEquals(Irreversible(IrrevAction.delete, "Delete:焦点不明"), hit("Delete", page))
        assertEquals(IrrevAction.delete, hit("Backspace", page)?.action)
        // 其余命令键:页面没声明就放行
        for (k in listOf("d", "Control+K", "Alt+s")) assertNull(k, hit(k, page))
        // 页面声明了快捷键:按那个控件判(aria-keyshortcuts、accesskey 换成的 Alt+键)
        val declared = snap(
            ui("@e1", "删除邮件", listOf(0, 0, 999, 100), keys = listOf("Control+Shift+K", "#")),
            ui("@e2", "发送", listOf(0, 200, 999, 300), keys = listOf("Alt+s")),
            ui("@e3", "刷新", listOf(0, 400, 999, 500), keys = listOf("r")),
        )
        assertEquals(IrrevAction.delete, hit("Shift+Control+K", declared)?.action)
        assertNull(hit("Control+K", declared))
        assertEquals(IrrevAction.send, hit("Alt+S", declared)?.action)
        assertNull(hit("r", declared))
        // 焦点在无害元素上,但按的是页面声明给「发送」的键
        val focusedElsewhere = snap(ui("@e1", "卡片", listOf(0, 0, 999, 100), focused = true), ui("@e2", "发送", listOf(0, 200, 999, 300), keys = listOf("Alt+s")))
        assertEquals(IrrevAction.send, hit("Alt+s", focusedElsewhere)?.action)
    }

    @Test fun browserInteractDragsAndSelects() {
        val tool = "web_interact"
        val s = snap(
            el("@e1", "留言", listOf(0, 100, 999, 200), editable = true, focused = true),
            el("@e2", "删除", listOf(800, 900, 990, 990)),
            el("@e3", "滑动支付", listOf(0, 500, 999, 600)),
            el("@e4", "卡片", listOf(0, 300, 999, 400)),
        )
        // 拖到删除区、拖动「滑动支付」;拖卡片到空白处不算
        assertEquals(Irreversible(IrrevAction.delete, "drag:删除"),
            d.classify(tool, args("""{"action":"drag","ref":"@e4","snapshotId":"s1","toRef":"@e2"}"""), s))
        assertEquals(IrrevAction.pay, d.classify(tool, args("""{"action":"drag","ref":"@e3","snapshotId":"s1","toX":990,"toY":550}"""), s)?.action)
        assertEquals(IrrevAction.delete, d.classify(tool, args("""{"action":"drag","x":500,"y":350,"toX":900,"toY":950}"""), s)?.action)
        assertNull(d.classify(tool, args("""{"action":"drag","ref":"@e4","snapshotId":"s1","toX":500,"toY":700}"""), s))
        // 悬停不判
        assertFalse(d.needsCheck(tool, args("""{"action":"hover","ref":"@e2","snapshotId":"s1"}""")))
        // 下拉选择按下拉框本身和选中的那一项判
        val form = snap(el("@e1", "城市", listOf(0, 100, 999, 200)), el("@e2", "提交方式", listOf(0, 300, 999, 400)), el("@e3", "删除", listOf(0, 500, 999, 600)))
        assertTrue(d.needsCheck(tool, args("""{"action":"select","ref":"@e1","snapshotId":"s1","option":"北京"}""")))
        assertNull(d.classify(tool, args("""{"action":"select","ref":"@e1","snapshotId":"s1","option":"北京"}"""), form))
        assertEquals(Irreversible(IrrevAction.delete, "option:删除账户"),
            d.classify(tool, args("""{"action":"select","ref":"@e1","snapshotId":"s1","option":"删除账户"}"""), form))
        assertEquals(IrrevAction.delete, d.classify(tool, args("""{"action":"select","ref":"@e3","snapshotId":"s1","option":"x"}"""), form)?.action)
    }
}
