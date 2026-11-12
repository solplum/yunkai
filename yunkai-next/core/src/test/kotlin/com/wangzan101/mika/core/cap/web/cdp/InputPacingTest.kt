package com.wangzan101.mika.core.cap.web.cdp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

class InputPacingTest {
    private val human = InputPacing.human(Random(42))
    private val lim = human.limits

    // ---------- 生成器 ----------

    @Test fun instantAddsNothing() {
        val p = InputPacing.Instant
        assertFalse(p.paced)
        assertEquals(listOf(100.0 to 50.0), p.path(0.0 to 0.0, 100.0 to 50.0))
        assertEquals(10.0 to 20.0, p.origin(10.0, 20.0))
        assertEquals(listOf(320.0), p.notches(320.0))
        assertEquals(0L, p.dwell() + p.press() + p.keyHold() + p.keyGap() + p.modifierGap() + p.moveStep() + p.wheelGap())
    }

    @Test fun pathEndsOnTargetAndStaysNearTheLine() {
        repeat(200) { i ->
            val r = Random(i)
            val from = r.nextDouble(0.0, 1280.0) to r.nextDouble(0.0, 800.0)
            val to = r.nextDouble(0.0, 1280.0) to r.nextDouble(0.0, 800.0)
            val pts = InputPacing.human(Random(i)).path(from, to)
            assertEquals(to, pts.last())
            val dist = hypot(to.first - from.first, to.second - from.second)
            if (dist < 3.0) {
                assertEquals(1, pts.size)
                return@repeat
            }
            assertTrue(pts.size in lim.moveSteps)
            val maxOff = minOf(dist * lim.bowFraction, lim.maxBowPx)
            for ((x, y) in pts) {
                // 到起终点连线的距离不超过弧线的最大偏离
                val off = abs((to.second - from.second) * x - (to.first - from.first) * y + to.first * from.second - to.second * from.first) / dist
                assertTrue("off $off > $maxOff", off <= maxOff + 1e-6)
            }
            // 沿连线方向单调前进(不会来回抖)
            val proj = pts.map { (x, y) -> ((x - from.first) * (to.first - from.first) + (y - from.second) * (to.second - from.second)) / dist }
            assertTrue(proj.zipWithNext().all { (a, b) -> b >= a - 1e-6 })
        }
    }

    @Test fun originIsNearbyAndOnScreen() {
        repeat(100) {
            val (x, y) = human.origin(30.0, 30.0)
            assertTrue(x >= 0 && y >= 0)
            assertTrue(hypot(x - 30, y - 30) <= lim.approachPx.last + 1e-6)
        }
    }

    @Test fun delaysStayInTheirRanges() {
        repeat(500) {
            assertTrue(human.dwell() in lim.dwellMs)
            assertTrue(human.press() in lim.pressMs)
            assertTrue(human.keyHold() in lim.keyHoldMs)
            assertTrue(human.keyGap() in lim.keyGapMs)
            assertTrue(human.moveStep() in lim.moveStepMs)
            assertTrue(human.wheelGap() in lim.wheelGapMs)
        }
    }

    @Test fun wheelNotchesAddUpToTheRequestAndAreCapped() {
        assertEquals(List(6) { 53.0 } + 2.0, human.notches(320.0).map { Math.round(it * 1e6) / 1e6 })
        assertEquals(listOf(-53.0, -7.0), human.notches(-60.0).map { Math.round(it * 1e6) / 1e6 })
        assertEquals(listOf(106.0 / 2, 53.0), human.notches(106.0))
        for (d in listOf(1.0, 52.9, 53.0, 999.0, 100_000.0, -4321.0)) {
            val n = human.notches(d)
            assertEquals(d, n.sum(), 1e-6)
            assertTrue(n.size <= lim.maxNotches)
            assertTrue(n.all { it * d > 0 })
        }
        // 很远时格数封顶,每格加大,总时长有界
        assertEquals(List(lim.maxNotches) { 100_000.0 / lim.maxNotches }, human.notches(100_000.0))
        assertTrue(human.notches(0.0).isEmpty())
    }

    @Test fun typingRuns() {
        assertEquals(
            listOf(TypingRuns.Run("hello", true), TypingRuns.Run("@", false), TypingRuns.Run("Example", true), TypingRuns.Run(".", false), TypingRuns.Run("com", true)),
            TypingRuns.split("hello@Example.com"),
        )
        assertEquals(listOf(TypingRuns.Run("北京", false),TypingRuns.Run(" 2 ", true), TypingRuns.Run("\n", false)), TypingRuns.split("北京 2 \n"))
        assertTrue(TypingRuns.split("").isEmpty())
    }

    // ---------- 事件序列 ----------

    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    private fun server() = FakeDevTools().apply { onMessage = { c, m -> c.reply(m, JsonObject(emptyMap())) } }
    private fun FakeDevTools.sent() = connections.single().received.toList()
    private fun JsonObject.method() = getValue("method").jsonPrimitive.content
    private fun JsonObject.p(k: String) = getValue("params").jsonObject[k]?.jsonPrimitive?.contentOrNull

    private suspend fun <T> CdpSession.paced(block: suspend InputTrack.() -> T): T {
        val r = input(3_000, InputPacing.human(Random(7)), block)
        if (r !is InputOutcome.Done) throw AssertionError("expected Done, got $r")
        return r.value
    }

    @Test fun pacedClickMovesAlongAPathThenPressesOnTarget() = test {
        val s = server()
        val session = CdpSession.connect(s, "webview_devtools_remote_1", "/devtools/page/A")
        val started = System.currentTimeMillis()
        session.paced { mouseClick(400.0, 300.0) }
        val took = System.currentTimeMillis() - started
        val types = s.sent().map { it.p("type") }
        val moves = types.takeWhile { it == "mouseMoved" }.size
        assertTrue("moves $moves", moves in lim.moveSteps)
        assertEquals(listOf("mousePressed", "mouseReleased"), types.drop(moves))
        val press = s.sent()[moves].getValue("params").jsonObject
        assertEquals(400.0, press["x"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(300.0, press["y"]!!.jsonPrimitive.double, 1e-9)
        // 至少有停顿和按住;总时长有界
        assertTrue(took >= lim.dwellMs.first + lim.pressMs.first)
        assertTrue(took < 2_000)
        assertEquals(400.0 to 300.0, session.pointer)
        session.close()
    }

    @Test fun nextMoveStartsFromWhereThePointerIs() = test {
        val s = server()
        val session = CdpSession.connect(s, "webview_devtools_remote_1", "/devtools/page/A")
        session.paced { mouseClick(100.0, 100.0) }
        val before = s.sent().size
        session.paced { mouseMove(500.0, 100.0) }
        val moves = s.sent().drop(before).map { it.getValue("params").jsonObject["x"]!!.jsonPrimitive.double }
        // 从 100 往 500 走,不会从别处跳过来
        assertTrue(moves.all { it in 100.0..500.0 + 1e-6 })
        assertEquals(500.0, moves.last(), 1e-9)
        session.close()
    }

    @Test fun pacedTypingKeysLettersAndCommitsTheRest() = test {
        val s = server()
        val session = CdpSession.connect(s, "webview_devtools_remote_1", "/devtools/page/A")
        session.paced { typeText("Ab@1") }
        val sent = s.sent()
        val texts = sent.filter { it.method() == "Input.dispatchKeyEvent" && it.p("type") == "keyDown" }.map { it.p("text") }
        assertEquals(listOf("A", "b", "1"), texts)
        // 大写字母按着 Shift
        assertEquals("Shift", sent.first().p("key"))
        assertEquals(listOf("@"), sent.filter { it.method() == "Input.insertText" }.map { it.p("text") })
        // 每个按下都有抬起
        val downs = sent.count { it.p("type") == "keyDown" || it.p("type") == "rawKeyDown" }
        assertEquals(downs, sent.count { it.p("type") == "keyUp" })
        session.close()
    }

    @Test fun longOrUnpacedTextIsCommittedWhole() = test {
        val s = server()
        val session = CdpSession.connect(s, "webview_devtools_remote_1", "/devtools/page/A")
        val long = "a".repeat(lim.maxKeyedChars + 1)
        session.paced { typeText(long) }
        session.input(3_000) { typeText("abc") }
        assertEquals(listOf("Input.insertText", "Input.insertText"), s.sent().map { it.method() })
        assertEquals(listOf(long, "abc"), s.sent().map { it.p("text") })
        session.close()
    }

    @Test fun wheelScrollsInNotchesAtThePointer() = test {
        val s = server()
        val session = CdpSession.connect(s, "webview_devtools_remote_1", "/devtools/page/A")
        session.paced { mouseWheel(640.0, 400.0, -200.0) }
        val wheels = s.sent().filter { it.p("type") == "mouseWheel" }.map { it.getValue("params").jsonObject }
        assertEquals(4, wheels.size)
        assertEquals(-200.0, wheels.sumOf { it["deltaY"]!!.jsonPrimitive.double }, 1e-9)
        assertTrue(wheels.all { it["x"]!!.jsonPrimitive.double == 640.0 })
        session.close()
    }
}
