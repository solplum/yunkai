package com.wangzan101.mika.core.memory

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.HandBack
import com.wangzan101.mika.core.tool.HandBackOutcome
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wangzan101.mika.core.assembly.toolDispatcher

private class MemPersistence : MemoryPersistence {
    override fun load() = emptyList<MemoryItem>()
    override fun save(items: List<MemoryItem>) {}
}

internal fun memStore(): MemoryStore {
    var seq = 0
    return MemoryStore(MemPersistence(), clock = { 1L }, ids = { "id${++seq}" })
}

private var callSeq = 0
internal fun memCall(name: String, args: JsonObject = JsonObject(emptyMap())) = Part.ToolCall("mc${++callSeq}", name, args.toString())
internal fun toolReply(c: Part.ToolCall) = ModelReply(listOf(c), StopReason.ToolUse, null)
internal fun saveArgs(kind: String, content: String, quote: String) = buildJsonObject {
    put("kind", kind); put("content", content); put("quote", quote); putJsonArray("keywords") { add(kotlinx.serialization.json.JsonPrimitive("外卖")) }
}
internal val finishReply = com.wangzan101.mika.core.testing.answerReply("ok")

@OptIn(ExperimentalCoroutinesApi::class)
class MemoryToolsTest {
    private fun dispatcher(store: MemoryStore?, utterances: UserUtterances?, screen: ScreenPort = FakeScreen()) =
        toolDispatcher(screen, memory = store, utterances = utterances)

    private fun ToolDispatcher.run(name: String, args: JsonObject) = runBlocking { execute(memCall(name, args)).content }
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    @Test fun saveFactActiveProcedurePending() {
        val store = memStore()
        val u = UserUtterances().apply { add("以后点外卖都不要香菜,先比价再下单") }
        val d = dispatcher(store, u)
        val fact = d.run("memory_save", saveArgs("preference", "用户点外卖不要香菜", "以后点外卖都不要香菜"))
        assertEquals("true", fact.s("ok")); assertEquals("active", fact.s("status"))
        val proc = d.run("memory_save", saveArgs("procedure", "点外卖先比价再下单", " 先比价再下单 "))
        assertEquals("pending", proc.s("status"))
        assertEquals(listOf(fact.s("id"), proc.s("id")), store.items.value.map { it.id })
        assertEquals("先比价再下单", store.items.value[1].source.quote!!.trim())
    }

    @Test fun quoteFromScreenRejected() {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = ScreenSnapshot("s1", "com.x",
                listOf(UiElement("@e1", "text", "收货地址:浦东张江路100号", 540, 120, listOf(0, 0, 999, 100), null, true, false, false)), 1080, 2400)
        }
        val store = memStore()
        val d = dispatcher(store, UserUtterances().apply { add("帮我看看收货地址") }, screen)
        assertTrue(d.run("phone_observe", JsonObject(emptyMap())).toString().contains("浦东张江路100号"))
        val r = d.run("memory_save", saveArgs("fact", "用户住在浦东张江路100号", "浦东张江路100号"))
        assertEquals("false", r.s("ok")); assertEquals("quote_not_from_user", r.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    // 网页上读到的字也不算用户说的话
    @Test fun quoteFromWebPageRejected() {
        val web = com.wangzan101.mika.core.cap.web.FakeWebPort()
        web.page = web.page.copy(text = "收货人 张三 地址 浦东张江路100号")
        val store = memStore()
        val d = toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances().apply { add("帮我看看订单") }, web = web)
        d.run("web_navigate", buildJsonObject { put("url", "https://shop.example.com/order") })
        assertTrue(d.run("web_read_page", JsonObject(emptyMap())).toString().contains("浦东张江路100号"))
        val r = d.run("memory_save", saveArgs("fact", "用户住在浦东张江路100号", "浦东张江路100号"))
        assertEquals("quote_not_from_user", r.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    @Test fun quoteFromInputAnswerAccepted() = runTest {
        val store = memStore()
        val u = UserUtterances()
        val model = FakeModel(
            toolReply(memCall("ask_user", buildJsonObject { put("prompt", "寄到哪里?") })),
            toolReply(memCall("memory_save", saveArgs("fact", "用户家在张江路100号", "寄到我家张江路100号"))),
            finishReply,
        )
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = store, utterances = u), "B", memory = store)
        val run = async { loop.run("帮我寄个快递") }
        runCurrent()
        assertTrue(loop.answerInput("寄到我家张江路100号,以后都寄这里"))
        run.await()
        assertEquals(listOf("用户家在张江路100号"), store.items.value.map { it.content })
        assertTrue("寄到我家张江路100号,以后都寄这里" in u.all)
    }

    @Test fun takeoverNoteAccepted() = runTest {
        val store = memStore()
        val model = FakeModel(
            toolReply(memCall("hand_to_user", buildJsonObject { put("reason", "login"); put("message", "请登录") })),
            toolReply(memCall("memory_save", saveArgs("preference", "用户的外卖账号用手机号登录", "外卖账号用手机号登录"))),
            finishReply,
        )
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances()), "B", memory = store)
        val run = async { loop.run("登录一下") }
        runCurrent()
        loop.resumeAfterTakeover(HandBack(HandBackOutcome.complete, note = "外卖账号用手机号登录就行"))
        run.await()
        assertEquals(1, store.items.value.size)
    }

    @Test fun sensitiveRejected() {
        val store = memStore()
        val d = dispatcher(store, UserUtterances().apply { add("记住我的支付密码是123456") })
        val r = d.run("memory_save", saveArgs("fact", "用户的支付密码是123456", "支付密码是123456"))
        assertEquals("sensitive", r.s("error"))
        assertTrue(store.items.value.isEmpty())
    }

    @Test fun memoryFullReported() {
        val store = MemoryStore(MemPersistence(), { 1L }, { "x" }, limit = 0)
        val d = dispatcher(store, UserUtterances().apply { add("以后外卖都不要香菜") })
        assertEquals("memory_full", d.run("memory_save", saveArgs("fact", "外卖不要香菜", "外卖都不要香菜")).s("error"))
    }

    @Test fun searchExcludesPending() {
        val store = memStore()
        store.add(MemoryKind.preference, "点外卖不要香菜", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow()
        store.add(MemoryKind.procedure, "点外卖先比价", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow()
        val d = dispatcher(store, UserUtterances())
        val r = d.run("memory_search", buildJsonObject { put("query", "外卖") })
        val found = r["memories"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("点外卖不要香菜"), found.map { it.s("content") })
        assertEquals(setOf("id", "kind", "content"), found.single().keys)
    }

    @Test fun noStoreNoTools() {
        val d = toolDispatcher(FakeScreen())
        assertFalse(d.definitions().any { it.name.startsWith("memory_") })
        assertEquals("unknown_tool", d.run("memory_save", saveArgs("fact", "x", "x")).s("error"))
        // 只给其中一样也不提供
        assertFalse(dispatcher(memStore(), null).definitions().any { it.name.startsWith("memory_") })
        assertTrue(dispatcher(memStore(), UserUtterances()).definitions().map { it.name }.containsAll(listOf("memory_search", "memory_save")))
    }

    @Test fun utterancesNormalizeWidthAndShortQuotes() {
        val u = UserUtterances().apply { add("  我的ＱＱ号码以后都用这个  ") }
        assertTrue(u.contains("QQ号码"))
        assertTrue(u.contains(" ＱＱ号码 "))
        assertFalse(u.contains("我"))
        assertFalse(u.contains("别人的号码"))
    }

    @Test fun runRecordHasNoMemoryText() = runTest {
        val store = memStore()
        store.add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), MemorySource("manual", atMs = 1)).getOrThrow()
        val events = mutableListOf<RunEvent>()
        val model = FakeModel(
            toolReply(memCall("memory_search", buildJsonObject { put("query", "外卖") })),
            toolReply(memCall("memory_save", saveArgs("fact", "用户的公司在漕河泾开发区", "公司在漕河泾开发区"))),
            toolReply(memCall("hand_to_user", buildJsonObject { put("reason", "login"); put("message", "请登录") })),
            finishReply,
        )
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances()), "B",
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = store)
        val run = async { loop.run("帮我订外卖送到公司,公司在漕河泾开发区") }
        runCurrent()
        loop.resumeAfterTakeover(HandBack(HandBackOutcome.complete, note = "账号是我老婆的手机号"))
        run.await()
        assertEquals(2, store.items.value.size)
        val tools = events.filter { it is RunEvent.ModelReply || it is RunEvent.ToolResult }.joinToString("\n") { RunEvent.encode(it) }
        for (secret in listOf("不要香菜", "漕河泾", "老婆的手机号")) assertFalse(secret, tools.contains(secret))
        assertTrue(tools.contains("preference")) // 编号、种类照记
    }

    @Test fun sourceCarriesConversationAndStoreClock() {
        val store = MemoryStore(MemPersistence(), clock = { 4242L }, ids = { "s1" })
        val d = toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances().apply { add("以后外卖都不要香菜") }, conversationId = "conv-9")
        d.run("memory_save", saveArgs("preference", "用户外卖不要香菜", "外卖都不要香菜"))
        val src = store.items.value.single().source
        assertEquals("conv-9", src.conversationId)
        assertEquals(4242L, src.atMs)
    }

    @Test fun contentDetailsFromScreenRejected() {
        val store = memStore()
        val d = dispatcher(store, UserUtterances().apply { add("帮我把这个地址记下来,以后都寄这里") })
        val r = d.run("memory_save", saveArgs("fact", "用户的收货地址是浦东张江路100号", "以后都寄这里"))
        assertEquals("content_not_from_user", r.s("error"))
        assertTrue(store.items.value.isEmpty())
        // 用户自己说出的具体值可以
        val ok = dispatcher(store, UserUtterances().apply { add("以后都寄到张江路100号") })
            .run("memory_save", saveArgs("fact", "用户的收货地址是浦东张江路100号", "以后都寄到张江路100号"))
        assertEquals("true", ok.s("ok"))
    }

    private fun saveWith(said: String, content: String, quote: String, keywords: List<String> = emptyList()): JsonObject {
        val args = buildJsonObject {
            put("kind", "fact"); put("content", content); put("quote", quote)
            putJsonArray("keywords") { keywords.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        return dispatcher(memStore(), UserUtterances().apply { add(said) }).run("memory_save", args)
    }

    @Test fun separatedAndChineseDigitsChecked() {
        assertEquals("true", saveWith("我电话是13800138000", "用户的电话是 138 0013 8000", "我电话是13800138000").s("ok"))
        assertEquals("content_not_from_user", saveWith("记住我的电话", "用户的电话是 138-0013-8001", "记住我的电话").s("error"))
        assertEquals("content_not_from_user", saveWith("记住我的门牌号", "用户的门牌号是一零零八", "记住我的门牌号").s("error"))
        assertEquals("true", saveWith("我家门牌号是1008", "用户家门牌号是一零零八", "我家门牌号是1008").s("ok"))
        assertEquals("true", saveWith("我家门牌号是一零零八", "用户家门牌号是1008", "我家门牌号是一零零八").s("ok"))
    }

    @Test fun placeNamesAndKeywordsChecked() {
        assertEquals("content_not_from_user", saveWith("以后都寄到我家", "用户家在汤臣一品苑", "以后都寄到我家").s("error"))
        assertEquals("content_not_from_user", saveWith("以后都寄到我家", "用户的收货地址在家里", "以后都寄到我家", listOf("张江路100号")).s("error"))
        // 第三人称转述本身不算从屏幕抄来的
        assertEquals("true", saveWith("我家里有只猫", "用户家里有猫", "我家里有只猫").s("ok"))
    }

    @Test fun utterancesClearedAtRunStart() = runTest {
        val u = UserUtterances().apply { add("上一个任务里说的话") }
        val store = memStore()
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = store, utterances = u), "B", memory = store).run("这次的任务")
        assertEquals(listOf("这次的任务"), u.all)
    }

    @Test fun savedEventEmitted() = runTest {
        val store = memStore()
        val events = mutableListOf<RunEvent>()
        val model = FakeModel(toolReply(memCall("memory_save", saveArgs("procedure", "点外卖先比价", "点外卖先比价"))), finishReply)
        AgentLoop(model, toolDispatcher(FakeScreen(), memory = store, utterances = UserUtterances()), "B",
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = store).run("记住:点外卖先比价")
        assertEquals(listOf(RunEvent.MemorySaved(0, "id1", "procedure", "pending")), events.filterIsInstance<RunEvent.MemorySaved>().map { it.copy(atMs = 0) })
    }
}
