package com.wangzan101.mika.core.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptManifestTest {
    @Test fun parsesAndPicksInterpreter() {
        val list = ScriptManifest.parse("""[
            {"file":"scripts/palettes.py","runnable":true,"needs":["apk:py3-pillow"],"extra":1},
            {"file":"scripts/fetch.mjs","runnable":true,"network":true},
            {"file":"scripts/login.py","runnable":false,"reason":"需要账号授权"},
            {"file":"../../etc/passwd","runnable":true},
            {"file":"/bin/sh","runnable":true}
        ]""")
        assertEquals(listOf("scripts/palettes.py", "scripts/fetch.mjs", "scripts/login.py"), list.map { it.file })
        assertEquals(listOf("apk:py3-pillow"), list[0].needs)
        assertEquals("/usr/bin/python3", ScriptManifest.interpreterFor("scripts/palettes.py"))
        assertEquals("/usr/bin/node", ScriptManifest.interpreterFor("a.mjs"))
        assertEquals("/usr/bin/node", ScriptManifest.interpreterFor("a.JS"))
        assertEquals("/bin/sh", ScriptManifest.interpreterFor("run.sh"))
        assertNull(ScriptManifest.interpreterFor("tool.rb"))
        assertTrue(ScriptManifest.parse("not json").isEmpty())
        assertTrue(ScriptManifest.parse("""{"file":"x"}""").isEmpty())
    }

    @Test fun networkScriptBlockedWhenOffline() {
        val net = ScriptEntry("scripts/fetch.mjs", runnable = true, network = true)
        assertEquals(false to ScriptManifest.NEEDS_NETWORK, ScriptManifest.effectiveRunnable(net, networkAllowed = false))
        assertEquals(true to null, ScriptManifest.effectiveRunnable(net, networkAllowed = true))
        assertEquals(false to "需要账号授权", ScriptManifest.effectiveRunnable(ScriptEntry("scripts/a.py", false, reason = "需要账号授权"), true))
        assertEquals(false, ScriptManifest.effectiveRunnable(ScriptEntry("scripts/a.rb", true), true).first)
    }
}
