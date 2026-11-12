package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityQueryTest {
    @Test
    fun argsCarryOnlyWhatIsGiven() {
        assertEquals(
            listOf("cmd", "package", "query-activities", "--brief", "--user", "current", "-a", "android.intent.action.SEND", "-t", "text/plain", "-p", "a.b"),
            ActivityQuery.args("android.intent.action.SEND", type = "text/plain", packageName = "a.b"),
        )
    }

    @Test
    fun parsesComponentsAndExpandsShortNames() {
        val out = """
            2 activities found:
              Activity #0:
                priority=0 preferredOrder=0 match=0x608000 specificIndex=-1 isDefault=true
                a.b/.ui.ShareUI
              Activity #1:
                priority=0 preferredOrder=0 match=0x608000 specificIndex=-1 isDefault=true
                a.b/c.d.SaveUI
        """.trimIndent()
        assertEquals(listOf("a.b" to "a.b.ui.ShareUI", "a.b" to "c.d.SaveUI"), ActivityQuery.parse(out))
    }

    @Test
    fun nothingFoundIsEmpty() {
        assertEquals(emptyList<Pair<String, String>>(), ActivityQuery.parse("No activities found"))
    }
}
