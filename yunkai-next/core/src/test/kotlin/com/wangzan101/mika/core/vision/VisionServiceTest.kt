package com.wangzan101.mika.core.vision

import com.wangzan101.mika.core.agent.FakeModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class VisionServiceTest {
    private val ops = JvmTestImageOps
    private val big = ops.encode(2000, 1000) { x, _ -> if (x < 1000) 0 else 200 }
    private val small = ops.encode(10, 10) { _, _ -> 128 }

    @Test fun sendsImagesWithoutTools() = runTest {
        val m = FakeModel(ModelReply(listOf(Part.Text("看到"), Part.Text("按钮")), StopReason.EndTurn, null))
        val answer = VisionService(m, ops).ask("SYS", "这是什么", listOf(big, small))
        assertEquals("看到按钮", answer)
        assertEquals("SYS", m.system)
        assertEquals(listOf(emptyList<String>()), m.offeredTools)
        val msg = m.requests.single().single()
        assertEquals(Role.User, msg.role)
        assertEquals(Part.Text("这是什么"), msg.parts[0])
        val imgs = msg.parts.drop(1).map { it as Part.Image }
        assertEquals(2, imgs.size)
        assertTrue(imgs.all { it.mime == "image/jpeg" })
        // 大图按长边 1280 缩小后再发
        assertEquals(1280 to 640, ops.size(Base64.getDecoder().decode(imgs[0].pngBase64)))
        assertEquals(10 to 10, ops.size(Base64.getDecoder().decode(imgs[1].pngBase64)))
    }

    @Test fun propagatesModelError() = runTest {
        val m = FakeModel(ModelError.RateLimited(null))
        try {
            VisionService(m, ops).ask("SYS", "看", listOf(small))
            fail("expected ModelError")
        } catch (e: ModelError.RateLimited) {
        }
    }

    @Test fun blankReplyIsBadResponse() = runTest {
        val m = FakeModel(ModelReply(listOf(Part.Text("  ")), StopReason.EndTurn, null))
        try {
            VisionService(m, ops).ask("SYS", "看", listOf(small))
            fail("expected ModelError")
        } catch (e: ModelError.BadResponse) {
        }
    }

    @Test fun hammingCountsDifferingBits() {
        assertEquals(0, hamming(0x5L, 0x5L))
        assertEquals(2, hamming(0b1010L, 0b0110L))
        assertEquals(64, hamming(0L, -1L))
    }

    @Test fun testOpsHashSeparatesDifferentFrames() {
        val left = ops.encode(100, 100) { x, _ -> if (x < 50) 0 else 255 }
        val top = ops.encode(100, 100) { _, y -> if (y < 50) 0 else 255 }
        assertEquals(0, hamming(ops.aHash(left), ops.aHash(left.copyOf())))
        assertTrue(hamming(ops.aHash(left), ops.aHash(top)) > 8)
    }
}
