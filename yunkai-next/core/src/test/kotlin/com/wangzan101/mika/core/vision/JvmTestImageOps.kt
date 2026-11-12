package com.wangzan101.mika.core.vision

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 测试用「假图」:前 8 字节是宽、高(大端 Int),后面每像素一个灰度字节。
 * 纯 JVM 解不了 PNG,测试只需要一个能被各操作一致处理的格式。
 */
object JvmTestImageOps : ImageOps {
    class Gray(val w: Int, val h: Int, val px: ByteArray) {
        operator fun get(x: Int, y: Int) = px[y * w + x].toInt() and 0xFF
    }

    fun encode(w: Int, h: Int, gray: (x: Int, y: Int) -> Int): ByteArray {
        val b = ByteBuffer.allocate(8 + w * h).putInt(w).putInt(h)
        for (y in 0 until h) for (x in 0 until w) b.put(gray(x, y).coerceIn(0, 255).toByte())
        return b.array()
    }

    fun decode(img: ByteArray): Gray {
        val b = ByteBuffer.wrap(img)
        val w = b.int; val h = b.int
        return Gray(w, h, img.copyOfRange(8, 8 + w * h))
    }

    private fun resample(g: Gray, w: Int, h: Int) = encode(w, h) { x, y -> g[x * g.w / w, y * g.h / h] }

    private fun toPx(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt().coerceIn(0, size - 1)

    override fun size(img: ByteArray) = decode(img).let { it.w to it.h }

    override fun aHash(img: ByteArray): Long {
        val small = decode(resample(decode(img), 8, 8))
        val mean = small.px.sumOf { it.toInt() and 0xFF } / 64.0
        var h = 0L
        for (i in 0 until 64) if ((small.px[i].toInt() and 0xFF) >= mean) h = h or (1L shl i)
        return h
    }

    override fun toJpeg(img: ByteArray, maxSide: Int, quality: Int): ByteArray {
        val g = decode(img)
        val f = maxSide.toDouble() / max(g.w, g.h)
        return if (f >= 1.0) img else scale(img, f)
    }

    override fun drawFrame(img: ByteArray, box: IntArray, expandPx: Int, strokePx: Int): ByteArray {
        require(box.size == 4) { "box must be [l, t, r, b]" }
        val g = decode(img)
        val l = toPx(box[0], g.w) - expandPx; val t = toPx(box[1], g.h) - expandPx
        val r = toPx(box[2], g.w) + expandPx; val b = toPx(box[3], g.h) + expandPx
        // 灰度图里用 255 代表红框
        return encode(g.w, g.h) { x, y ->
            val onX = x in l..r && (y in t until t + strokePx || y in b - strokePx + 1..b)
            val onY = y in t..b && (x in l until l + strokePx || x in r - strokePx + 1..r)
            if (onX || onY) 255 else g[x, y]
        }
    }

    override fun crop(img: ByteArray, box: IntArray): ByteArray {
        require(box.size == 4) { "box must be [l, t, r, b]" }
        val g = decode(img)
        val l = toPx(box[0], g.w); val t = toPx(box[1], g.h)
        val r = toPx(box[2], g.w); val b = toPx(box[3], g.h)
        return encode(r - l + 1, b - t + 1) { x, y -> g[l + x, t + y] }
    }

    override fun scale(img: ByteArray, factor: Double): ByteArray {
        val g = decode(img)
        return resample(g, max(1, (g.w * factor).roundToInt()), max(1, (g.h * factor).roundToInt()))
    }

    override fun pixels(img: ByteArray): IntArray =
        decode(img).px.map { val v = it.toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }.toIntArray()

    override fun isMostlyBlack(img: ByteArray): Boolean {
        val px = decode(img).px
        return px.count { (it.toInt() and 0xFF) < 16 } >= px.size * 0.98
    }
}
