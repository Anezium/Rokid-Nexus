package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        controller.search("quarterly chart")
        controller.search("sprint plan")
        val phase = AssistantToolRegistry(listOf(ViewWorkspacePageTool { controller }))
            .newExecutionPhase(VISION, controller.searchVersion())
        val result = phase.execute(call("""{"file":"reports/sales.pdf","page":2}"""))
        assertEquals("jpeg:PDF:2:Quarterly chart.", decode(result))
        val image = AssistantToolRegistry(listOf(ViewWorkspacePageTool { controller }))
            .newExecutionPhase(VISION, controller.searchVersion()).execute(call("""{"file":"WHITEBOARD.JPG"}"""))
        assertEquals("jpeg:IMAGE:1:Sprint plan", decode(image))
        assertEquals("jpeg:IMAGE:1:Sprint plan",
            controller.viewPage("whiteboard.jpg${WorkspaceRetriever.VISUAL_MARK}", null)?.let { String(it) })
        assertEquals("jpeg:PDF:2:Quarterly chart.",
            controller.viewPage("reports/sales.pdf › page 2", 2)?.let { String(it) })
    }

    @Test
    fun `unknown text unpaged changed or out-of-range files are refused`() = fixture { controller, gateway ->
        controller.search("summary quarterly chart sales notes")
        assertNull(controller.viewPage("missing.pdf", 1))
        assertNull(controller.viewPage("notes.txt", 1))
        assertNull(controller.viewPage("reports/sales.pdf", null))
        assertNull(controller.viewPage("reports/sales.pdf", 9))
        gateway.entries["sales"] = gateway.entries.getValue("sales").copy(modifiedAtMs = 99)
        assertNull(controller.viewPage("reports/sales.pdf", 1))
    }

    @Test
    fun `only pages the current question's excerpts cited can be viewed`() = fixture { controller, _ ->
        assertNull(controller.viewPage("reports/sales.pdf", 2))
        assertNull(controller.viewPage("whiteboard.jpg", null))
        controller.contextForQuestion("quarterly chart", "")
        assertEquals("jpeg:PDF:2:Quarterly chart.", controller.viewPage("reports/sales.pdf", 2)?.let { String(it) })
        assertNull(controller.viewPage("reports/sales.pdf", 1))
        assertNull(controller.viewPage("whiteboard.jpg", null))
        controller.search("sprint plan")
        assertEquals("jpeg:IMAGE:1:Sprint plan", controller.viewPage("whiteboard.jpg", null)?.let { String(it) })
        controller.contextForQuestion("sprint plan", "")
        assertNull(controller.viewPage("reports/sales.pdf", 2))
    }

    @Test
    fun `a stuck render releases the question at its deadline and refuses overlapping views`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        gateway.put("sales", "sales.pdf", listOf("Summary.", "Quarterly chart.").joinToString(FakePageReader.PAGE_BREAK))
        val gate = CountDownLatch(1)
        val fake = FakePageReader()
        val stuck = object : WorkspacePageReader by fake {
            override fun render(type: WorkspaceFileType, bytes: ByteArray, page: Int): ByteArray? {
                gate.await()
                return fake.render(type, bytes, page)
            }
        }
        WorkspaceIndexer(store, gateway, clock = { 1_000 }, pageReader = fake).refresh()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val controller = WorkspaceController(store, gateway, scope, pageReader = stuck, viewTimeoutMs = 200)
            controller.search("quarterly chart")
            val started = System.nanoTime()
            assertNull(controller.viewPage("sales.pdf", 2))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            val opens = gateway.opens.getValue("sales")
            assertNull(controller.viewPage("sales.pdf", 2))
            assertEquals(opens, gateway.opens.getValue("sales"))
            gate.countDown()
            withTimeout(2_000) { while (controller.viewPage("sales.pdf", 2) == null) delay(20) }
        } finally {
            gate.countDown()
            scope.cancel()
        }
    }

    @Test
    fun `a file name containing the citation separator still resolves`() = runBlocking {
        val store = WorkspaceStore(temporary.newFolder())
        store.selectTree(WorkspaceStoreTest.TREE, "Documents", enable = true)
        val gateway = FakeWorkspaceGateway()
        gateway.put("draft", "sales › draft.pdf", "Quarterly chart.")
        val reader = FakePageReader()
        WorkspaceIndexer(store, gateway, clock = { 1_000 }, pageReader = reader).refresh()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val controller = WorkspaceController(store, gateway, scope, pageReader = reader)
            controller.search("quarterly chart")
            assertEquals("jpeg:PDF:1:Quarterly chart.", controller.viewPage("sales › draft.pdf", 1)?.let { String(it) })
            assertEquals("jpeg:PDF:1:Quarterly chart.",
                controller.viewPage("sales › draft.pdf › page 1", 1)?.let { String(it) })
        } finally {
            scope.cancel()
        }
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

    @Test
    fun `workspace and photo tool schemas are accepted by strict function calling`() {
        listOf(SearchWorkspaceTool { null }.parametersSchema, ViewWorkspacePageTool { null }.parametersSchema,
            TAKE_PHOTO_PARAMETERS_SCHEMA).forEach { assertTrue(it.text, it.isStrictCompatible()) }
    }

    @Test
    fun `strict compatibility catches optional, open, and nested loose schemas`() {
        fun strict(json: String) = AssistantToolJsonSchema(json).isStrictCompatible()
        assertTrue(strict("""{"type":"object","properties":{},"additionalProperties":false}"""))
        assertFalse(strict("""{"type":"object","properties":{"a":{"type":"string"}},"additionalProperties":false}"""))
        assertFalse(strict("""{"type":"object","properties":{"a":{"type":"string"}},"required":["a"]}"""))
        assertFalse(strict("""{"type":"object","properties":{"a":{"type":"array","items":{"type":"object",""" +
            """"properties":{"b":{"type":"string"}},"additionalProperties":false}}},"required":["a"],""" +
            """"additionalProperties":false}"""))
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
