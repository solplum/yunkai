package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.runAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.knowledge.ScriptManifest
import com.wangzan101.mika.core.cap.sandbox.SandboxStatus
import com.wangzan101.mika.core.uistate.ScreenPort
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy
import com.wangzan101.mika.core.assembly.toolDispatcher

/**
 * 随 App 打包的可运行脚本在真沙箱里经 run_bundled_script 跑一遍(和模型用的是同一条路)。
 * 需要沙箱已装好;没装就跳过。
 */
@RunWith(AndroidJUnit4::class)
class SkillScriptsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ws = "skill-scripts-device-test"
    private val sb = AndroidSandbox(context, SandboxState.get(context))

    /** run_bundled_script 不碰屏幕;任何屏幕调用都算测试写错了。 */
    private val noScreen = Proxy.newProxyInstance(ScreenPort::class.java.classLoader, arrayOf(ScreenPort::class.java)) { _, m, _ ->
        throw AssertionError("screen not expected: ${m.name}")
    } as ScreenPort
    private val tools = toolDispatcher(noScreen, sandbox = sb, workspace = { ws })

    @Before fun setUp() {
        assumeTrue("sandbox not installed", sb.status() is SandboxStatus.Ready)
        SandboxDirs(context).workspace(ws).apply { deleteRecursively(); mkdirs() }
    }

    private fun runScript(skill: String, script: String, vararg args: String): JsonObject = runBlocking {
        tools.runAction(Part.ToolCall("c", "run_bundled_script", buildJsonObject {
            put("source", skill); put("script", script); put("args", JsonArray(args.map(::JsonPrimitive)))
        }.toString())).content
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private fun assertRan(r: JsonObject) {
        assertEquals(r.toString(), "true", r.s("ok"))
        assertEquals(r.toString(), "0", r.s("exitCode"))
    }

    /** 两页、每页一行文字的最小 PDF(Helvetica,无压缩),由测试当场生成。 */
    private fun samplePdf(vararg pages: String): ByteArray {
        val objs = mutableListOf<String>()
        val kids = pages.indices.joinToString(" ") { "${3 + it * 2} 0 R" }
        objs += "<< /Type /Catalog /Pages 2 0 R >>"
        objs += "<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>"
        val font = 3 + pages.size * 2
        pages.forEachIndexed { i, text ->
            val stream = "BT /F1 18 Tf 72 720 Td ($text) Tj ET"
            objs += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 $font 0 R >> >> /Contents ${4 + i * 2} 0 R >>"
            objs += "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
        }
        objs += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = objs.mapIndexed { i, o -> out.length.also { out.append("${i + 1} 0 obj\n$o\nendobj\n") } }
        val xref = out.length
        out.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { out.append(String.format("%010d 00000 n \n", it)) }
        out.append("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    @Test fun extractPdfGetsText() {
        File(SandboxDirs(context).workspace(ws), "inbox/sample.pdf").apply { parentFile!!.mkdirs() }
            .writeBytes(samplePdf("Hello from Mika page one", "Second page text"))
        val r = runScript("skills/documents", "scripts/extract_pdf.py", "inbox/sample.pdf")
        assertRan(r)
        assertTrue(r.toString(), r.s("output")!!.contains("--- page 1 ---\nHello from Mika page one"))
        assertTrue(r.toString(), r.s("output")!!.contains("Second page text"))
        val meta = runScript("skills/documents", "scripts/extract_pdf.py", "inbox/sample.pdf", "--metadata")
        assertRan(meta)
        assertTrue(meta.toString(), meta.s("output")!!.contains("\"pages\": 2"))
    }

    @Test fun authScriptIsUnavailable() {
        val r = runScript("sites/github.com", "scripts/api.mjs", "GET", "/user")
        assertEquals(r.toString(), "script_unavailable", r.s("error"))
        assertTrue(r.toString(), r.s("message")!!.contains("需要账号授权,Mika 暂不支持"))
    }
}
