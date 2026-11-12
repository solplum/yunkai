package com.wangzan101.mika.core.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** README.md、README.en.md 里生成的段落(内置知识、服务商预设)和源头一致;不一致就跑 `./gradlew updateReadme`。 */
class ReadmeSectionsTest {
    private val root = File("..").canonicalFile

    @Test fun readmeMatchesGenerated() {
        val readme = File(root, ReadmeSections.README).readText()
        val expected = ReadmeSections.render(readme, ReadmeSections.sections(root))
        assertEquals("README.md 里生成的段落过期了,运行 ./gradlew updateReadme", expected, readme)
    }

    @Test fun englishReadmeMatchesGenerated() {
        val readme = File(root, ReadmeSections.README_EN).readText()
        val expected = ReadmeSections.render(readme, ReadmeSections.sections(root, english = true))
        assertEquals("README.en.md 里生成的段落过期了,运行 ./gradlew updateReadme", expected, readme)
    }

    @Test fun renderReplacesOnlyBetweenMarkers() {
        val text = "前\n${ReadmeSections.start("x")}\n旧\n${ReadmeSections.end("x")}\n后\n"
        assertEquals(
            "前\n${ReadmeSections.start("x")}\n新\n${ReadmeSections.end("x")}\n后\n",
            ReadmeSections.render(text, mapOf("x" to "新")),
        )
    }

    @Test fun missingMarkerFails() {
        val failed = runCatching { ReadmeSections.render("没有标记", mapOf("x" to "新")) }.isFailure
        assertTrue(failed)
    }

    @Test fun readsPresetsFromSource() {
        val src = """
            ProviderPreset("甲", ProviderConfig.Kind.OpenAiCompat, "https://a.example/v1", caps()),
            ProviderPreset("乙", ProviderConfig.Kind.Gemini, "https://b.example", caps(vision = true)),
            ProviderPreset("自定义", ProviderConfig.Kind.OpenAiCompat, "", caps()),
        """.trimIndent()
        assertEquals(
            listOf(
                ReadmeSections.Preset("甲", "OpenAiCompat", "https://a.example/v1"),
                ReadmeSections.Preset("乙", "Gemini", "https://b.example"),
                ReadmeSections.Preset("自定义", "OpenAiCompat", ""),
            ),
            ReadmeSections.presets(src),
        )
    }

    /** 自定义能接的接口不写 Responses:它只给 OpenAI 预设和模型列表写明的模型用。 */
    @Test fun customKindsLeaveOutResponses() {
        val presets = listOf(
            ReadmeSections.Preset("甲", "OpenAiCompat", "https://a.example/v1"),
            ReadmeSections.Preset("乙", "OpenAiResponses", "https://b.example/v1"),
            ReadmeSections.Preset("丙", "Gemini", "https://c.example"),
            ReadmeSections.Preset("自定义", "OpenAiCompat", ""),
        )
        assertTrue(ReadmeSections.providers(presets).endsWith("内置 甲、乙、丙 等服务商预设；其他服务商选「自定义」，OpenAI 兼容、Gemini 接口都能接。"))
        assertTrue(ReadmeSections.providersEn(presets).endsWith("which takes any OpenAI-compatible or Gemini endpoint."))
    }
}
