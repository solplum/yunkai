package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.cap.sandbox.CommandResult
import com.wangzan101.mika.core.cap.sandbox.FakeSandbox
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.testing.Screen
import com.wangzan101.mika.core.testing.ScriptedScreen
import com.wangzan101.mika.core.testing.label
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * 30 步的假任务(读屏、点、跑命令轮流),经真的 OpenAI 兼容适配器发到假服务端,按线上字节比对相邻两次请求:
 * 系统提示词与工具只在展开一组之后的那次请求变(第 1 步读屏展开手机、第 3 步跑命令展开沙箱,每组一次),
 * 变的时候工具列表里排在那一组前面的部分逐字不变,其余请求逐字相同;
 * 历史只追加:估算没越过压缩触发点时一次也不改写,只有压缩的那一次请求改写,
 * 且第一条改写的消息就是被缩短(或折进摘要)的那条。
 */
class PrefixStabilityTest {
    private val servers = mutableListOf<MockWebServer>()
    private val pad = "第几项内容".repeat(30)

    @After fun tearDown() = servers.forEach { it.shutdown() }

    /** 假服务端:按请求末尾运行时状态里的步数决定这是第几步(折叠后助手消息会变少),照剧本回;[fail] 里的步第一次回 429。 */
    private inner class Wire(val fail: Set<Int> = emptySet()) {
        val bodies = CopyOnWriteArrayList<JsonObject>()
        private val tried = mutableSetOf<Int>()
        val server = MockWebServer().also { servers += it }.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    bodies += body
                    val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
                    val step = STEP.findAll(msgs.last().toString()).last().groupValues[1].toInt()
                    if (step in fail && tried.add(step)) return MockResponse().setResponseCode(429).setHeader("retry-after", "0")
                    return MockResponse().setBody(reply(step, msgs).toString())
                }
            }
            start()
        }

        fun model(vision: Boolean, contextTokens: Int) =
            OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", Capabilities(true, vision, contextTokens, false), OkHttpClient())
    }

    private fun reply(step: Int, msgs: List<JsonObject>): JsonObject {
        // 最后一步不带调用,只回文字:就是最终回答
        if (step > STEPS) return buildJsonObject {
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("finish_reason", "stop")
                    putJsonObject("message") { put("content", "看完了") }
                })
            }
            putJsonObject("usage") { put("prompt_tokens", 1000); put("completion_tokens", 10) }
        }
        val (name, args) = when {
            step % 3 == 1 -> "phone_observe" to buildJsonObject {}
            step % 3 == 2 -> lastRead(msgs).let { (ref, snap) -> "phone_tap" to buildJsonObject { put("ref", ref); put("snapshotId", snap) } }
            else -> "run_command" to buildJsonObject { put("command", "cat part$step.txt") }
        }
        return buildJsonObject {
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("finish_reason", "tool_calls")
                    putJsonObject("message") {
                        put("content", JsonPrimitive(null as String?))
                        putJsonArray("tool_calls") {
                            add(buildJsonObject {
                                put("id", "call_$step"); put("type", "function")
                                putJsonObject("function") { put("name", name); put("arguments", args.toString()) }
                            })
                        }
                    }
                })
            }
            putJsonObject("usage") { put("prompt_tokens", 1000); put("completion_tokens", 10) }
        }
    }

    /** 最近一次读屏里第一个元素的编号与读屏编号(带差异屏幕时编号会重排,不能写死)。 */
    private fun lastRead(msgs: List<JsonObject>): Pair<String, String> {
        val c = msgs.filter { it.str("role") == "tool" }.map { it.str("content").orEmpty() }.last { "\"elements\":[{" in it }
        return c.substringAfter("\"ref\":\"").substringBefore('"') to c.substringAfter("\"snapshotId\":\"").substringBefore('"')
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private class Setup(val vision: Boolean = false, val inline: Boolean = false, val fail: Set<Int> = emptySet(),
                        val light: Boolean = false, val contextTokens: Int = 1_000_000,
                        /** 上一轮用过的组:开局就展开。 */
                        val prior: List<String> = emptyList())

    /** 跑一遍,返回各个服务端收到的请求(主模型在前)。 */
    private fun run(s: Setup): List<List<JsonObject>> {
        val screens = (1..40).map { n -> Screen("com.x", (1..12).map { label("@e$it", "第${n}屏第${it}项$pad", 100 + it * 50) }) }
        val scripted = ScriptedScreen(screens)
        val port: ScreenPort = if (!s.vision) scripted else object : ScreenPort by scripted { override suspend fun screenshotPng() = byteArrayOf(1, 2, 3) }
        val sb = FakeSandbox().apply { next = { r -> CommandResult(0, false, false, 10, "输出${r.argv}" + "行".repeat(6000), 0, false) } }
        val main = Wire(s.fail)
        val light = if (s.light) Wire() else null
        val mainModel = main.model(s.vision, s.contextTokens)
        val lightModel = light?.model(false, s.contextTokens)
        runTest {
            AgentLoop(mainModel, toolDispatcher(port, sandbox = sb, workspace = { "w" }, inlineScreen = s.inline, visionEnabled = s.vision),
                SystemPrompt.identity(), STEPS + 5, clock = { 0L }, zone = ZoneOffset.UTC,
                flags = SpeedFlags(inlineScreen = s.inline, lightSteps = s.light), router = lightModel?.let { ModelRouter(mainModel, it) },
            ).run("把每一屏都看一遍", if (s.prior.isEmpty()) emptyList() else listOf(Turn("上一轮", "好", "succeeded", groups = s.prior))).also { assertTrue("$it", it is TaskState.Succeeded) }
        }
        return listOfNotNull(main, light).map { it.bodies.toList() }
    }

    /** 线上一次请求:区 0(系统提示词、工具)与逐条消息,各自是发出的原样字节;[tools] 是逐个工具的原样字节。 */
    private class Req(val zone0: String, val msgs: List<String>, val raw: List<JsonObject>, val tools: List<String>)

    private fun wire(body: JsonObject): Req {
        val msgs = body["messages"]!!.jsonArray.map { it.jsonObject }
        return Req(msgs.first().toString() + body["tools"].toString(), msgs.drop(1).map { it.toString() }, msgs.drop(1),
            body["tools"]!!.jsonArray.map { it.toString() })
    }

    /** 线上一个工具定义的名字。 */
    private fun nameOf(tool: String) = Json.parseToJsonElement(tool).jsonObject["function"]!!.jsonObject.str("name")

    /** 收着时也给的入口工具:展开时第一个变的工具只能是它们之一(或排在最后的新工具)。 */
    private val entries = setOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command")

    private val markers = listOf(HistoryPruner.OMITTED, HistoryPruner.OMITTED_READ, "(更早的画面已省略", HistoryPruner.OMITTED_IMAGE, ContextCompressor.HEADER)

    /**
     * 线上字节里相邻两次请求的公共前缀:Σ 公共前缀长 ÷ Σ 请求长(按字符)。展开一组的那几次([skip],前缀按设计失效一次)不计,
     * 另由 [check] 查它们只改了那一组起的部分。
     */
    private fun prefixShare(reqs: List<Req>, skip: List<Int> = emptyList()): Double {
        val full = reqs.map { it.zone0 + it.msgs.joinToString("") }
        val pairs = full.zipWithNext().withIndex().filter { (i, _) -> i + 1 !in skip }.map { it.value }
        val common = pairs.sumOf { (a, b) -> a.commonPrefixWith(b).length.toLong() }
        return common.toDouble() / pairs.sumOf { (_, b) -> b.length.toLong() }
    }

    private class Result(val pairs: Int, val changed: List<Int>, val share: Double, val opened: List<Int>)

    /** [appendOnly]:没越过触发点,一次也不该改写;压缩时由调用方给出确切的期望。 */
    private fun check(bodies: List<JsonObject>, label: String, appendOnly: Boolean = true): Result {
        val reqs = bodies.map(::wire)
        // 区 0 只在展开一组时变,至多两次(手机、沙箱);变的时候前面的工具逐字不变,第一个变的是那一组的入口
        val opened = (1 until reqs.size).filter { reqs[it].zone0 != reqs[it - 1].zone0 }
        assertTrue("$label:区 0 变了 $opened 次", opened.size <= 2)
        for (i in opened) {
            val (a, b) = reqs[i - 1].tools to reqs[i].tools
            val same = a.zip(b).takeWhile { (x, y) -> x == y }.size
            assertTrue("$label 第 ${i + 1} 次请求:展开时改了一组前面的工具 ${a.getOrNull(same)?.let(::nameOf)}", same == a.size || nameOf(a[same]) in entries)
            assertTrue("$label 第 ${i + 1} 次请求:展开只加不减", b.size > a.size)
        }
        val changed = mutableListOf<Int>()
        for (i in 1 until reqs.size) {
            val (a, b) = reqs[i - 1] to reqs[i]
            val same = a.msgs.zip(b.msgs).takeWhile { (x, y) -> x == y }.size
            if (same == a.msgs.size) continue
            changed += i
            // 第一条改写的就是被裁剪线裁掉(或折进摘要)的那条
            val first = b.msgs[same]
            assertTrue("$label 第 ${i + 1} 次请求第 $same 条改写不是压缩:$first", markers.any { it in first && it !in a.msgs[same] } || ContextCompressor.HEADER in first)
        }
        if (appendOnly) assertEquals("$label:没越过触发点却改写了前缀", emptyList<Int>(), changed)
        return Result(reqs.size - 1, changed, prefixShare(reqs, opened), opened)
    }

    // 读屏、大结果、画面都只追加:30 步里一次也不改写
    @Test fun textOnly() {
        val r = check(run(Setup()).single(), "text")
        // 第 1 步读屏之后的那次请求展开手机,第 3 步跑命令之后的展开沙箱
        assertEquals(listOf(1, 3), r.opened)
        assertTrue("${r.share}", r.share > 0.95)
    }

    // 上一轮用过手机和沙箱:开局就展开,30 步里区 0 一次也不变
    @Test fun preloadedGroupsNeverChangeZoneZero() {
        val r = check(run(Setup(prior = listOf("phone", "sandbox"))).single(), "preloaded")
        assertEquals(emptyList<Int>(), r.opened)
        assertTrue("${r.share}", r.share > 0.95)
    }

    @Test fun withVisionImages() {
        val r = check(run(Setup(vision = true)).single(), "vision")
        assertTrue("${r.share}", r.share > 0.95)
    }

    @Test fun withInlineScreens() {
        val r = check(run(Setup(inline = true)).single(), "inline")
        assertTrue("${r.share}", r.share > 0.95)
    }

    @Test fun retriedRequestsAreIdentical() {
        val bodies = run(Setup(fail = setOf(5, 14))).single()
        // 重试的那次和被拒的那次逐字相同
        assertEquals(33, bodies.size)
        check(bodies, "retry")
    }

    @Test fun lightModelRouting() {
        val (main, light) = run(Setup(light = true))
        assertTrue("轻量模型应当接过几步", light.isNotEmpty())
        check(main, "main")
        check(light, "light")
    }

    @Test fun compaction() {
        val bodies = run(Setup(contextTokens = 40_000)).single()
        val r = check(bodies, "compaction", appendOnly = false)
        assertTrue(bodies.any { ContextCompressor.HEADER in it.toString() })
        // 越过触发点的那一次压缩一次(改写一次),之后只追加,再越过时才再压:30 步里只改写两次
        assertEquals(listOf(15, 27), r.changed)
        assertTrue("${r.share}", r.share > 0.85)
    }

    private companion object {
        const val STEPS = 30
        val STEP = Regex("第 (\\d+) 步,上限")
    }
}
