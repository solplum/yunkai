package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.gate.ConfirmationGate
import com.wangzan101.mika.core.guard.OperationGuards
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenPort
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mock-only outcome contracts: real composite runner, query engine and guarded executor. */
class YunkaiPhoneOutcomeTest {
    @Test fun openAppFindExactTargetAndReadItsDetail() = runTest {
        val phone = FixturePhone()
        val h = Harness(phone)
        val result = h.batch("""
            {"op":"launch","packageName":"com.example.yunkai.fixture"},
            {"op":"tap","targetText":"DEMO TARGET"},
            {"op":"assert","targetText":"Detail: Demo target"}
        """)
        assertEquals("true", result.s("ok"))
        assertEquals("3", result.s("completed"))
        assertEquals(listOf("launch", "tap:Demo target"), phone.writes)
        assertEquals(listOf("Detail: Demo target"), result.screenNames())
        assertEquals("com.example.yunkai.fixture", result["screen"]!!.jsonObject.s("currentApp"))
        // Search includes a similar label, but the real runner chooses the exact one.
        assertTrue(h.events.filterIsInstance<RunEvent.ToolResult>().any { it.tool == "phone_query" })
    }

    @Test fun inputAcrossTwoPagesMustReadBackBothValuesOnReviewPage() = runTest {
        val phone = FixturePhone(page = "form1")
        val result = Harness(phone).batch("""
            {"op":"type","resourceId":"fixture:id/title","text":"Sample title"},
            {"op":"tap","targetText":"Next"},
            {"op":"type","resourceId":"fixture:id/note","text":"Sample note"},
            {"op":"tap","targetText":"Review"},
            {"op":"assert","targetText":"Title: Sample title"},
            {"op":"assert","targetText":"Note: Sample note"}
        """)
        assertEquals("true", result.s("ok"))
        assertEquals("6", result.s("completed"))
        assertEquals(listOf("type:Sample title", "tap:Next", "type:Sample note", "tap:Review"), phone.writes)
        assertEquals(listOf("Title: Sample title", "Note: Sample note"), result.screenNames())
    }

    @Test fun acknowledgedTypingWithoutVisibleEffectCannotPassReadback() = runTest {
        val phone = FixturePhone(page = "form1", discardInput = true)
        val result = Harness(phone).batch("""
            {"op":"type","resourceId":"fixture:id/title","text":"Sample title"},
            {"op":"tap","targetText":"Next"},
            {"op":"type","resourceId":"fixture:id/note","text":"Sample note"},
            {"op":"tap","targetText":"Review"},
            {"op":"assert","targetText":"Title: Sample title"},
            {"op":"tap","targetText":"Send"}
        """)
        assertStopped(result, "condition_unmet", 4)
        assertFalse(result.screenNames().contains("Title: Sample title"))
        assertFalse(phone.writes.any { it == "tap:Send" })
    }

    @Test fun unexpectedDialogStopsBeforeNextInputAndReturnsEvidenceForReplan() = runTest {
        val phone = FixturePhone(page = "form1", interruptWithDialog = true)
        val result = Harness(phone).batch("""
            {"op":"type","resourceId":"fixture:id/title","text":"Sample title"},
            {"op":"tap","targetText":"Next"},
            {"op":"type","resourceId":"fixture:id/note","text":"Sample note"},
            {"op":"tap","targetText":"Review"}
        """)
        assertStopped(result, "target_not_found", 2)
        assertEquals(listOf("type:Sample title", "tap:Next"), phone.writes)
        assertEquals(listOf("Unexpected permission dialog", "Cancel"), result.screenNames())
        assertEquals("false", result["steps"]!!.jsonArray.last().jsonObject.s("executed"))
    }

    @Test fun zeroMatchesAndDuplicateTargetsStopWithoutTapping() = runTest {
        for ((page, reason) in listOf("missing" to "target_not_found", "duplicate" to "ambiguous_target")) {
            val phone = FixturePhone(page = page)
            val result = Harness(phone).batch("""
                {"op":"tap","targetText":"Demo target"},
                {"op":"type","text":"Must not type"}
            """)
            assertStopped(result, reason, 0)
            assertTrue(phone.writes.isEmpty())
            assertEquals("false", result["steps"]!!.jsonArray.single().jsonObject.s("executed"))
        }
    }

    @Test fun missingExactTargetStopsEvenWhenLoneSubstringAndCoordinatesExist() = runTest {
        for (match in listOf("", ",\"textMatch\":\"exact\"")) {
            val phone = FixturePhone(page = "similarOnly")
            val result = Harness(phone).batch("""
                {"op":"tap","targetText":"Demo target","x":100,"y":100$match}
            """)
            assertStopped(result, "target_not_found", 0)
            assertTrue(phone.writes.isEmpty())
            assertEquals(listOf("Demo target archive"), result.screenNames())
        }
    }

    @Test fun typeAndClearDoNotUseASimilarEditableTarget() = runTest {
        for (op in listOf("type", "clear")) {
            val phone = FixturePhone(page = "similarField")
            val result = Harness(phone).batch("""
                {"op":"$op","targetText":"Recipient","text":"Synthetic value"}
            """)
            assertStopped(result, "target_not_found", 0)
            assertTrue(phone.writes.isEmpty())
            assertEquals(listOf("Recipient backup"), result.screenNames())
        }
    }

    @Test fun substringSelectionRequiresExplicitOptIn() = runTest {
        val phone = FixturePhone(page = "variableLabel")
        val result = Harness(phone).batch("""
            {"op":"tap","targetText":"Show details","textMatch":"contains"}
        """)
        assertEquals("true", result.s("ok"))
        assertEquals(listOf("tap:Show details (2)"), phone.writes)
        assertEquals(listOf("Detail: Demo target"), result.screenNames())
    }

    @Test fun invalidTextMatchNeverExecutesTheCommand() = runTest {
        for (match in listOf("\"fuzzy\"", "\"\"", "true", "null")) {
            val phone = FixturePhone(page = "home")
            val result = Harness(phone).batch("""
                {"op":"tap","targetText":"Demo target","textMatch":$match}
            """)
            assertEquals("false", result.s("ok"))
            assertTrue(phone.writes.isEmpty())
        }
    }

    @Test fun invalidOrMisplacedModeInLaterCommandRejectsBeforeAnyDeviceWrite() = runTest {
        val laterCommands = listOf(
            """{"op":"tap","targetText":"Demo target","textMatch":"fuzzy"}""",
            """{"op":"wait","targetText":"Demo target","textMatch":"exact"}""",
            """{"op":"tap","role":"button","textMatch":"contains"}""",
        )
        for (later in laterCommands) {
            val phone = FixturePhone()
            val result = Harness(phone).batch("""
                {"op":"launch","packageName":"com.example.yunkai.fixture"},
                {"op":"tap","targetText":"Demo target"},
                $later
            """)
            assertEquals("false", result.s("ok"))
            assertTrue("Invalid later options must not leave a partly executed batch", phone.writes.isEmpty())
        }
    }

    @Test fun roleOnlySelectionRetainsExistingSemantics() = runTest {
        val phone = FixturePhone(page = "similarOnly")
        val result = Harness(phone).batch("""{"op":"tap","role":"button"}""")
        assertEquals("true", result.s("ok"))
        assertEquals(listOf("tap:Demo target archive"), phone.writes)
    }

    @Test fun expiredReferenceCannotActOnReplacementScreen() = runTest {
        val phone = FixturePhone(page = "home")
        val tools = toolDispatcher(phone, stableRefs = true)
        val before = tools.execute(Part.ToolCall("before", "phone_observe", "{}")).content
        val target = before["elements"]!!.jsonArray.map { it.jsonObject }.single { it.s("name") == "Demo target" }
        phone.page = "missing"
        tools.execute(Part.ToolCall("after", "phone_observe", "{}"))
        val result = tools.execute(Part.ToolCall("stale", "phone_tap",
            """{"ref":"${target.s("ref")}","snapshotId":"${before.s("snapshotId")}"}""")).content
        assertEquals("false", result.s("ok"))
        assertEquals("ref_expired", result.s("error"))
        assertTrue(phone.writes.isEmpty())
    }

    @Test fun uncertainSendStopsWithoutRetryOrSuccessReceipt() = runTest {
        val phone = FixturePhone(page = "compose")
        val result = Harness(phone).batch("""
            {"op":"tap","targetText":"Send"},
            {"op":"assert","targetText":"Sent receipt"},
            {"op":"tap","targetText":"Send"}
        """)
        assertStopped(result, "input_uncertain", 0)
        assertEquals(listOf("tap:Send"), phone.writes)
        assertEquals(1, result["steps"]!!.jsonArray.size)
        assertFalse(result.screenNames().contains("Sent receipt"))
    }

    private fun assertStopped(result: JsonObject, reason: String, completed: Int) {
        assertEquals("false", result.s("ok"))
        assertEquals("phone_run_stopped", result.s("error"))
        assertEquals(reason, result.s("reason"))
        assertEquals(completed.toString(), result.s("completed"))
        assertEquals("true", result.s("recoverable"))
        assertTrue("A stop must return a screen for re-observation/replanning", result["screen"] is JsonObject)
    }

    private class Harness(phone: FixturePhone) {
        val events = mutableListOf<RunEvent>()
        private val tools = toolDispatcher(phone, stableRefs = true)
        private val executor = ActionExecutor(tools, OperationGuards(), ConfirmationGate(), gate = {},
            recorder = ActionRecorder(object : RunSink {
                override fun event(e: RunEvent) { events += e }
            }))
        suspend fun batch(commands: String): JsonObject = executor.run(
            Part.ToolCall("outcome", "phone_run", """{"commands":[$commands]}"""), Origin.Model(1),
        ).execution().content
    }

    /** Simulates only the device boundary, never target selection, batching, or success evaluation. */
    private class FixturePhone(
        var page: String = "launcher",
        val discardInput: Boolean = false,
        val interruptWithDialog: Boolean = false,
    ) : ScreenPort by FakeScreen() {
        val writes = mutableListOf<String>()
        private var serial = 0
        private var title = ""
        private var note = ""
        private fun element(n: Int, name: String, editable: Boolean = false, id: String? = null) = UiElement(
            "@e$n", if (editable) "textbox" else "button", name,
            100, n * 100, listOf(0, n * 100, 300, n * 100 + 80), id, !editable, editable, false,
        )
        private fun elements(): List<UiElement> = when (page) {
            "launcher" -> listOf(element(1, "Fixture launcher"))
            "home" -> listOf(element(1, "Demo target archive"), element(2, "Demo target"))
            "detail" -> listOf(element(1, "Detail: Demo target"))
            "similarOnly" -> listOf(element(1, "Demo target archive"))
            "variableLabel" -> listOf(element(1, "Show details (2)"))
            "similarField" -> listOf(element(1, "Recipient backup", true, "fixture:id/recipient"))
            "archive" -> listOf(element(1, "Detail: archive (wrong target)"))
            "form1" -> listOf(element(1, title, true, "fixture:id/title"), element(2, "Next"))
            "form2" -> listOf(element(1, note, true, "fixture:id/note"), element(2, "Review"))
            "review" -> listOf(element(1, "Title: $title"), element(2, "Note: $note"))
            "dialog" -> listOf(element(1, "Unexpected permission dialog"), element(2, "Cancel"))
            "duplicate" -> listOf(element(1, "Demo target"), element(2, "Demo target"))
            "compose" -> listOf(element(1, "Synthetic draft"), element(2, "Send"))
            else -> listOf(element(1, "No matching results"))
        }
        private fun screen() = ScreenSnapshot("p$serial", "com.example.yunkai.fixture", elements(), 1080, 2400)
        override suspend fun snapshot(): ScreenSnapshot { serial++; return screen() }
        override suspend fun peek() = screen()
        override suspend fun screenshotPng(): ByteArray? = null
        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
            check(packageName == "com.example.yunkai.fixture")
            writes += "launch"; page = "home"
            return ActionResult.Ok(changed = true)
        }
        override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
            if (snapshotId != "p$serial") return ActionResult.Failed("stale_snapshot")
            val label = elements().single { it.ref == ref }.name
            writes += "tap:$label"
            page = when (label) {
                "Demo target", "Show details (2)" -> "detail"
                "Demo target archive" -> "archive"
                "Next" -> if (interruptWithDialog) "dialog" else "form2"
                "Review" -> "review"
                "Send" -> return ActionResult.Failed("input_uncertain")
                else -> error("Unplanned tap: $label")
            }
            return ActionResult.Ok(changed = true)
        }
        override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult {
            if (snapshotId != "p$serial") return ActionResult.Failed("stale_snapshot")
            check(elements().single { it.ref == ref }.editable)
            writes += "type:$text"
            if (!discardInput) when (page) {
                "form1" -> title = text
                "form2" -> note = text
                else -> error("Unplanned input on $page")
            }
            // Intentionally permits acknowledged-but-lost input, to test readback rather than API success.
            return ActionResult.Ok(changed = !discardInput)
        }
        override suspend fun clearText(ref: String?, snapshotId: String?): ActionResult {
            writes += "clear:$ref"
            return ActionResult.Ok(changed = true)
        }
        override suspend fun tapXY(x: Int, y: Int): ActionResult = error("Coordinate fallback forbidden in these contracts")
    }

    private fun JsonObject.s(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.screenNames() = this["screen"]!!.jsonObject["elements"]!!.jsonArray.map {
        it.jsonObject.s("name")
    }
}
