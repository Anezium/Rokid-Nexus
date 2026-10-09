package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

class ViewWorkspacePageToolTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `only vision backends with a viewable workspace file are offered the tool`() = fixture { controller, _ ->
        val tool = ViewWorkspacePageTool { controller }
        val version = controller.searchVersion()
        val registry = AssistantToolRegistry(listOf(tool))
        assertFalse(tool.sideEffecting)
        assertEquals(listOf(tool.name), registry.availableDefinitions(VISION, version).map { it.name })
        assertTrue(registry.availableDefinitions(VISION.copy(supportsVision = false), version).isEmpty())
        assertTrue(registry.availableDefinitions(VISION.copy(supportsTools = false), version).isEmpty())
        assertTrue(registry.availableDefinitions(VISION.copy(supportsWorkspaceSearch = false), version).isEmpty())
        assertTrue(registry.availableDefinitions(VISION, version.first to version.second + 1).isEmpty())
        assertTrue(AssistantToolRegistry(listOf(tool), sessionContext = { AssistantToolSessionContext(false) })
            .availableDefinitions(VISION, version).isEmpty())
    }

    @Test
    fun `a text-only workspace never offers the tool`() = fixture(withPaged = false) { controller, _ ->
        assertFalse(controller.hasViewablePages())
        assertTrue(AssistantToolRegistry(listOf(ViewWorkspacePageTool { controller }))
            .availableDefinitions(VISION, controller.searchVersion()).isEmpty())
    }

    @Test
    fun `the cited pdf page or image is attached as a jpeg`() = fixture { controller, _ ->
        val phase = AssistantToolRegistry(listOf(ViewWorkspacePageTool { controller }))
            .newExecutionPhase(VISION, controller.searchVersion())
        val result = phase.execute(call("""{"file":"reports/sales.pdf","page":2}"""))
        assertEquals("jpeg:PDF:2:Quarterly chart.", decode(result))
        val image = AssistantToolRegistry(listOf(ViewWorkspacePageTool { controller }))
            .newExecutionPhase(VISION, controller.searchVersion()).execute(call("""{"file":"WHITEBOARD.JPG"}"""))
        assertEquals("jpeg:IMAGE:1:Sprint plan", decode(image))
    }

    @Test
    fun `unknown text unpaged changed or out-of-range files are refused`() = fixture { controller, gateway ->
        assertNull(controller.viewPage("missing.pdf", 1))
        assertNull(controller.viewPage("notes.txt", 1))
        assertNull(controller.viewPage("reports/sales.pdf", null))
        assertNull(controller.viewPage("reports/sales.pdf", 9))
        gateway.entries["sales"] = gateway.entries.getValue("sales").copy(modifiedAtMs = 99)
        assertNull(controller.viewPage("reports/sales.pdf", 1))
    }

    @Test
    fun `malformed calls never reach the workspace`() = fixture { controller, _ ->
        val tool = ViewWorkspacePageTool { controller }
        val invalid = listOf("not-json", "{}", """{"file":""}""", """{"file":"a.pdf","page":0}""",
            """{"file":"a.pdf","page":"2"}""", """{"file":"a.pdf","page":1.5}""",
            """{"file":"a.pdf","uri":"content://secret"}""", JSONObject().put("file", "x".repeat(161)).toString())
        invalid.forEach { assertTrue(it, tool.validate(it) is AssistantToolValidation.Invalid) }
        assertTrue(tool.validate("""{"file":"board.jpg","page":null}""") is AssistantToolValidation.Valid)
    }

    @Test
    fun `the schema lists every property as required for strict providers`() {
        val schema = JSONObject(ViewWorkspacePageTool { null }.parametersSchema.text)
        val required = schema.getJSONArray("required").let { array -> List(array.length()) { array.getString(it) } }
        assertEquals(schema.getJSONObject("properties").keys().asSequence().toSet(), required.toSet())
        assertFalse(schema.getBoolean("additionalProperties"))
    }

    private fun decode(result: AssistantToolResult): String {
        val image = result as AssistantToolResult.Image
        assertEquals("image/jpeg", image.mimeType)
        return String(Base64.getDecoder().decode(image.base64))
    }

    private fun call(arguments: String) = AssistantToolCall("view-1", VIEW_WORKSPACE_PAGE_TOOL_NAME, arguments)

    private fun fixture(
        withPaged: Boolean = true,
        test: suspend (WorkspaceController, FakeWorkspaceGateway) -> Unit,
    ) = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        gateway.put("notes", "notes.txt", "Notes about sales.")
        if (withPaged) {
            gateway.put("sales", "sales.pdf", listOf("Summary.", "Quarterly chart.").joinToString(FakePageReader.PAGE_BREAK))
            gateway.put("board", "whiteboard.jpg", "Sprint plan")
            gateway.entries["reports"] = WorkspaceEntry("reports", "reports", directory = true)
            gateway.childrenByParent["root"] = listOf("notes", "reports", "board")
            gateway.childrenByParent["reports"] = listOf("sales")
        }
        val reader = FakePageReader()
        WorkspaceIndexer(store, gateway, clock = { 1_000 }, pageReader = reader).refresh()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            test(WorkspaceController(store, gateway, scope, pageReader = reader), gateway)
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        val VISION = AssistantProviderFeatures(supportsTools = true, supportsVision = true)
    }
}
