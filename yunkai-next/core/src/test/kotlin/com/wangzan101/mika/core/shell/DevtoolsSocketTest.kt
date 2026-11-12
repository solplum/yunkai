package com.wangzan101.mika.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DevtoolsSocketTest {
    @Test fun acceptsOnlyThePidForm() {
        assertEquals(1921845, DevtoolsSocket.pidOf("webview_devtools_remote_1921845"))
        assertEquals("webview_devtools_remote_42", DevtoolsSocket.name(42))
        listOf(
            "webview_devtools_remote_", "webview_devtools_remote_0", "webview_devtools_remote_012", "webview_devtools_remote_12a",
            "webview_devtools_remote_12 ", "xwebview_devtools_remote_12", "chrome_devtools_remote", "webview_devtools_remote_-1",
            "webview_devtools_remote_99999999999", "webview_devtools_remote_12/../1", "webview_devtools_remote_1\n2",
        ).forEach { assertNull(it, DevtoolsSocket.pidOf(it)) }
    }

    @Test fun readsRealUid() {
        val status = "Name:\tcom.example\nUmask:\t0077\nState:\tS (sleeping)\nUid:\t10117\t10117\t10117\t10117\nGid:\t10117\t10117\t10117\t10117\n"
        assertEquals(10117, DevtoolsSocket.procUid(status))
        assertEquals(2000, DevtoolsSocket.procUid("Uid:  2000 0 0 0"))
        assertNull(DevtoolsSocket.procUid("Name:\tx\nGid:\t1\n"))
        assertNull(DevtoolsSocket.procUid(""))
    }
}
