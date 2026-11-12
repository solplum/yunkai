package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeJsonTest {
    private fun leaf(ref: String, text: String) = UiTreeNode(
        role = "Button", bounds = listOf(0, 0, 500, 100), ref = ref, text = text,
        clickable = true, checkable = true, checked = false,
    )

    private fun screen(second: String = "查看", extra: Boolean = false): ScreenSnapshot {
        val children = listOf(leaf("@e1", "午餐"), leaf("@e2", second)) +
            if (extra) listOf(leaf("@e3", "新增")) else emptyList()
        val root = UiTreeNode("RecyclerView", listOf(0, 0, 999, 999), scrollable = true, children = children)
        val window = UiWindowTree(7, "application", 2, "订单", "com.example", true, true, listOf(root))
        val elements = children.mapIndexed { i, n ->
            UiElement(n.ref!!, "Button", n.text!!, 100, 100 + i * 100, n.bounds, null, true, false, false)
        }
        return ScreenSnapshot("port", "com.example", elements, 1080, 2400, windows = listOf(window))
    }

    @Test fun completeHierarchyAndStatesAreVisible() {
        val out = UiTreeJson.windows(screen()).single().jsonObject
        assertEquals("订单", out["title"]!!.jsonPrimitive.content)
        val root = out["nodes"]!!.jsonArray.single().jsonObject
        assertEquals("true", root["scrollable"]!!.jsonPrimitive.content)
        val children = root["children"]!!.jsonArray
        assertEquals(2, children.size)
        assertEquals("@e2", children[1].jsonObject["ref"]!!.jsonPrimitive.content)
        assertEquals("false", children[1].jsonObject["checked"]!!.jsonPrimitive.content)
    }

    @Test fun changedChildDoesNotRepeatUnchangedSibling() {
        val before = screen()
        val after = screen(second = "已查看", extra = true)
        val change = UiTreeJson.changes(before, after)
        val updated = change["updated"]!!.jsonArray
        assertEquals(listOf("w7/0/1", "w7/0/2"), updated.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertEquals("已查看", updated[0].jsonObject["node"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("新增", updated[1].jsonObject["node"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(change["removed"]!!.jsonArray.isEmpty())
    }

    @Test fun actionResultCarriesTreeChangeWithStablePath() {
        val stabilizer = RefStabilizer("p")
        val before = stabilizer.next(screen())
        val next = screen()
        val changedWindows = next.windows.map { window ->
            window.copy(nodes = window.nodes.map { root ->
                root.copy(children = root.children.mapIndexed { i, child ->
                    if (i == 1) child.copy(checked = true) else child
                })
            })
        }
        val after = stabilizer.next(next.copy(windows = changedWindows))
        val result = InterfaceUpdate.between(before, after, baseVisible = true, maxElements = Int.MAX_VALUE)
        assertEquals("false", result["full"]!!.jsonPrimitive.content)
        val updated = result["treeChanges"]!!.jsonObject["updated"]!!.jsonArray.single().jsonObject
        assertEquals("w7/0/1", updated["path"]!!.jsonPrimitive.content)
        assertEquals("true", updated["node"]!!.jsonObject["checked"]!!.jsonPrimitive.content)
    }

    @Test fun stableRefsAlsoMapIntoTree() {
        val port = screen().copy(elements = screen().elements.mapIndexed { i, e -> e.copy(ref = "@e${i + 8}") },
            windows = screen().windows.map { w -> w.copy(nodes = w.nodes.map { root ->
                root.copy(children = root.children.mapIndexed { i, n -> n.copy(ref = "@e${i + 8}") })
            }) })
        val model = RefStabilizer("p").next(port)
        val output = UiTreeJson.windows(model.screen).single().jsonObject["nodes"]!!.jsonArray.single().jsonObject["children"]!!.jsonArray
        assertEquals(model.elements.map { it.ref }, output.map { it.jsonObject["ref"]!!.jsonPrimitive.content })
        assertFalse(output[0].jsonObject.containsKey("children"))
    }
}
