package com.wangzan101.mika.settings

import com.wangzan101.mika.core.docs.ReadmeSections
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** README 的服务商预设段按源码逐条读出;这里拿真对象对照,源码写法变了、读法跟不上时失败。 */
class ReadmePresetsTest {
    @Test fun sourceReadingMatchesProviderPresets() {
        val source = File("../${ReadmeSections.PRESETS_SOURCE}").readText()
        assertEquals(
            ProviderPresets.all.map { ReadmeSections.Preset(it.label, it.kind.name, it.baseUrl) },
            ReadmeSections.presets(source),
        )
    }
}
