package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchChooserTest {
    private fun el(i: Int, name: String, clickable: Boolean = true) =
        UiElement("@e$i", "LinearLayout", name, 0, 0, listOf(100, 300 + i * 100, 900, 380 + i * 100), null, clickable, false, false)

    @Test fun optionsNamingTheAppAreAChoiceAndTheCloneIsMarked() {
        val window = listOf(el(1, "选择要打开的应用", clickable = false), el(2, "Chat"), el(3, "Chat (分身应用)"), el(4, "取消"))
        val options = LaunchChooser.options("Chat", window, profiles = listOf("分身应用"))!!
        assertEquals(listOf(ChoiceOption("Chat", clone = false), ChoiceOption("Chat (分身应用)", clone = true)), options)
        // 不知道另一份用户叫什么:名字正好是 App 名的是本来那份,其余看不出
        assertEquals(listOf(false, null), LaunchChooser.options("Chat", window, emptyList())!!.map { it.clone })
        // 两项名字一模一样(只靠角标区分):都列出来,不说哪个是本来那份
        assertEquals(listOf(null, null), LaunchChooser.options("Chat", listOf(el(1, "Chat"), el(2, "Chat")), listOf("分身应用"))!!.map { it.clone })
    }

    @Test fun windowsThatOnlyMentionTheAppAreNotAChoice() {
        // 权限框的说明文字提到 App 名,按钮不带:不是在问打开哪一个
        val permission = listOf(el(1, "要允许 Chat 发送通知吗?", clickable = false), el(2, "允许"), el(3, "不允许"))
        assertNull(LaunchChooser.options("Chat", permission, emptyList()))
        assertNull(LaunchChooser.options("", listOf(el(1, "Chat")), emptyList()))
    }

    @Test fun parsesUsersAndPackages() {
        val users = AppProfiles.parseUsers("Users:\n\tUserInfo{0:机主:c13} running\n\tUserInfo{128:分身应用:1030} running\n\tUserInfo{10:Guest:404}\n")
        assertEquals(listOf(UserProfile(0, "机主", true), UserProfile(128, "分身应用", true), UserProfile(10, "Guest", false)), users)
        assertEquals(setOf("com.example.chat", "com.example.pay"), AppProfiles.parsePackages("package:com.example.chat\npackage:com.example.pay\r\n\nWARNING: x\n"))
        val clone = users[1]
        assertEquals(mapOf("com.example.chat" to listOf("分身应用")),
            AppProfiles.alsoIn(mapOf(clone to setOf("com.example.chat"))))
        assertEquals(listOf("user 11"), AppProfiles.alsoIn(mapOf(UserProfile(11, "", true) to setOf("a.b")))["a.b"])
    }

    @Test fun launchWithChooserIsSuccessWithTheChoice() = runTest {
        val choice = LaunchChoice("android", listOf(ChoiceOption("Chat", false), ChoiceOption("Chat (分身应用)", true)))
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok(chooser = choice)
        }
        val r = toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", """{"package":"com.example.chat"}"""))
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        val c = r.content["chooser"]!!.jsonObject
        assertEquals("android", c["package"]!!.jsonPrimitive.content)
        val options = c["options"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Chat", "Chat (分身应用)"), options.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(listOf("false", "true"), options.map { it["clone"]!!.jsonPrimitive.content })
        val hint = r.content["hint"]!!.jsonPrimitive.content
        assertTrue(hint, Feedback.format("launch.chooser", "pkg" to choice.packageName) in hint)
        assertFalse("app_switched" in r.content)
    }

    @Test fun listAppsMarksAppsInOtherProfiles() = runTest {
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun listApps() = listOf("com.example.chat" to "Chat", "com.example.notes" to "Notes")
            override suspend fun otherProfiles() = mapOf("com.example.chat" to listOf("分身应用"))
        }
        val r = toolDispatcher(screen).executeRead(Part.ToolCall("a", "phone_list_apps", "{}"))
        val apps = r.content["apps"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("分身应用"), apps[0]["alsoIn"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse("alsoIn" in apps[1])
    }
}
