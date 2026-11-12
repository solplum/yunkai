package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在安装后的 APK 中实际加载两套本地模型识别中英文。 */
@RunWith(AndroidJUnit4::class)
class PhoneOcrTest {
    @Test fun bundledModelsRecognizeChineseAndEnglish() = runBlocking {
        val bitmap = Bitmap.createBitmap(1080, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 96f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        canvas.drawText("中文测试", 80f, 170f, paint)
        canvas.drawText("HELLO OCR", 80f, 350f, paint)

        val lines = PhoneOcr.read(bitmap)
        assertTrue(lines.toString(), lines.any { "中文" in it.text })
        assertTrue(lines.toString(), lines.any { "HELLO" in it.text.uppercase() })
    }
}
