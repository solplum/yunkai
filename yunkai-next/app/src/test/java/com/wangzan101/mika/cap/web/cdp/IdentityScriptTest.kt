package com.wangzan101.mika.cap.web.cdp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 在 node 里给补丁搭一个最小的手机浏览器环境(test resources 里的 identity-harness.js)跑一遍。
 * 机器上没有 node 时跳过。
 */
class IdentityScriptTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun node(): String? = listOf("node", "/opt/homebrew/bin/node", "/usr/local/bin/node", "/usr/bin/node").firstOrNull { n ->
        runCatching { ProcessBuilder(n, "--version").redirectErrorStream(true).start().let { it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0 } }.getOrDefault(false)
    }

    private fun run(): JsonObject {
        val node = node()
        assumeTrue("node not installed", node != null)
        val script = tmp.newFile("identity.js").apply { writeText(DesktopProfile.identityScript()) }
        val harness = tmp.newFile("harness.js").apply { writeText(IdentityScriptTest::class.java.getResource("/web/identity-harness.js")!!.readText()) }
        val p = ProcessBuilder(node, harness.path, script.path).redirectErrorStream(true).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.exitValue())
        return Json.parseToJsonElement(out.trim().lines().last()) as JsonObject
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test fun reportsTheDesktopIdentity() {
        val o = run()
        assertEquals(0, o.getValue("maxTouchPoints").jsonPrimitive.int)
        assertEquals(DesktopProfile.NAVIGATOR_PLATFORM, o.str("platform"))
        assertFalse(o.getValue("touchInWindow").jsonPrimitive.boolean)
        assertFalse(o.getValue("touchInElement").jsonPrimitive.boolean)
        assertEquals(listOf(1280, 800 + DesktopProfile.WINDOW_CHROME_PX), o.getValue("outer").jsonArray.map { it.jsonPrimitive.int })
        assertFalse(o.getValue("coarse").jsonPrimitive.boolean)
        assertTrue(o.getValue("fine").jsonPrimitive.boolean)
        assertTrue(o.getValue("hover").jsonPrimitive.boolean)
        assertTrue(o.str("createTouch").contains("is invalid"))
        assertEquals("object", o.str("chrome"))
    }

    @Test fun mediaListKeepsThePageQueryWithoutOwnProperties() {
        val o = run()
        assertEquals("(pointer: fine)", o.str("media"))
        assertEquals("(min-width: 100px)", o.str("otherQuery"))
        assertTrue(o.getValue("mediaOwnProps").jsonArray.isEmpty())
    }

    @Test fun patchedFunctionsBehaveLikeBuiltIns() {
        val o = run()
        // 接收者不对时照样报 Illegal invocation;缺参数时照样报内核的错
        for (k in listOf("getterIllegal", "outerIllegal", "mediaIllegal", "methodIllegal")) assertEquals(k, "Illegal invocation", o.str(k))
        assertEquals("1 argument required", o.str("methodNoArgs"))
        // length 与原来的一样
        assertEquals(listOf(1, 1, 0, 0), o.getValue("lengths").jsonArray.map { it.jsonPrimitive.int })
        assertEquals(
            listOf("function matchMedia() { [native code] }", "function get maxTouchPoints() { [native code] }", "function toString() { [native code] }"),
            o.getValue("sources").jsonArray.map { it.jsonPrimitive.content },
        )
        assertFalse(o.getValue("hasPrototype").jsonPrimitive.boolean)
    }

    @Test fun scriptCreatedFramesGetTheSameIdentityAndToStringHoldsAcrossFrames() {
        val o = run()
        assertEquals(0, o.getValue("childTouch").jsonPrimitive.int)
        assertEquals("function matchMedia() { [native code] }", o.str("crossSource"))
        assertEquals("function get platform() { [native code] }", o.str("crossSourceBack"))
        // 再跑一遍不会补第二层
        assertTrue(o.getValue("matchMediaStillOnce").jsonPrimitive.boolean)
    }

    @Test fun harnessFileIsPresent() {
        assertTrue(File(javaClass.getResource("/web/identity-harness.js")!!.toURI()).length() > 0)
    }
}
