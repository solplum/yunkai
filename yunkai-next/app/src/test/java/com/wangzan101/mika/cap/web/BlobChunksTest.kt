package com.wangzan101.mika.cap.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class BlobChunksTest {
    @Test fun declaredLengthCheckedBeforeAnyAllocation() {
        // 20 MB 上限:报 Int.MAX_VALUE 的长度直接拒,不按它分配
        assertEquals("too_large", BlobChunks(Int.MAX_VALUE, 20L shl 20).refusal())
        assertEquals("unreadable", BlobChunks(-1, 20L shl 20).refusal())
        assertNull(BlobChunks((BlobChunks.maxBase64(20L shl 20)).toInt(), 20L shl 20).refusal())
    }

    @Test fun chunksAreCappedAndCannotExceedTheDeclaredLength() {
        val c = BlobChunks(8, 100, chunk = 4)
        assertEquals("unreadable", c.add("12345")) // 一段超长
        assertNull(c.add("aGVs"))
        assertNull(c.add("bG8="))
        assertEquals("unreadable", c.add("x")) // 超出报的长度
        assertArrayEquals("hello".toByteArray(), (c.finish() as BlobChunks.Outcome.Ok).bytes)
    }

    @Test fun decodedSizeStillChecked() {
        val b64 = Base64.getEncoder().encodeToString(ByteArray(12))
        val c = BlobChunks(b64.length, 10)
        assertNull(c.refusal())
        assertNull(c.add(b64))
        assertEquals("too_large", (c.finish() as BlobChunks.Outcome.Failed).error)
    }
}
