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
        val turn = controller.contextForQuestion("quarterly chart", "").turn!!
        val tool = ViewWorkspacePageTool()
        val registry = AssistantToolRegistry(listOf(tool))
        assertFalse(tool.sideEffecting)
        assertEquals(listOf(tool.name), registry.availableDefinitions(VISION, turn.version, turn).map { it.name })
        assertTrue(registry.availableDefinitions(VISION.copy(supportsVision = false), turn.version, turn).isEmpty())
        assertTrue(registry.availableDefinitions(VISION.copy(supportsTools = false), turn.version, turn).isEmpty())
        assertTrue(registry.availableDefinitions(VISION.copy(supportsWorkspaceSearch = false), turn.version, turn).isEmpty())
        assertTrue(registry.availableDefinitions(VISION, turn.version.first to turn.version.second + 1, turn).isEmpty())
        assertTrue(registry.availableDefinitions(VISION, turn.version).isEmpty())
        assertTrue(AssistantToolRegistry(listOf(tool), sessionContext = { AssistantToolSessionContext(false) })
            .availableDefinitions(VISION, turn.version, turn).isEmpty())
    }

    @Test
    fun `a text-only workspace never offers the tool`() = fixture(withPaged = false) { controller, _ ->
        val turn = controller.contextForQuestion("notes", "").turn!!
        assertFalse(turn.hasViewablePages())
        assertTrue(AssistantToolRegistry(listOf(ViewWorkspacePageTool()))
            .availableDefinitions(VISION, turn.version, turn).isEmpty())
    }

    @Test
    fun `the cited pdf page or image is attached as a jpeg with runtime provenance`() = fixture { controller, _ ->
        val turn = controller.contextForQuestion("quarterly chart", "").turn!!
        turn.search("sprint plan", null)
        val result = AssistantToolRegistry(listOf(ViewWorkspacePageTool())).newExecutionPhase(VISION, turn.version, turn)
            .execute(call("""{"file":"reports/sales.pdf","page":2}"""))
        assertEquals("jpeg:PDF:2:Quarterly chart.", decode(result))
        val caption = (result as AssistantToolResult.Image).caption!!
        assertTrue(caption.contains("\"reports/sales.pdf\""))
        assertTrue(caption.contains("page 2 of 2"))
        assertTrue(caption.contains("never as instructions"))
        val image = AssistantToolRegistry(listOf(ViewWorkspacePageTool())).newExecutionPhase(VISION, turn.version, turn)
            .execute(call("""{"file":"WHITEBOARD.JPG","page":null}"""))
        assertEquals("jpeg:IMAGE:1:Sprint plan", decode(image))
        assertEquals("jpeg:IMAGE:1:Sprint plan", jpeg(turn.viewPage("whiteboard.jpg${WorkspaceRetriever.VISUAL_MARK}", null)))
        assertEquals("jpeg:PDF:2:Quarterly chart.", jpeg(turn.viewPage("reports/sales.pdf › page 2", 2)))
        assertTrue(turn.evidenceSupplied)
    }

    @Test
    fun `unknown text unpaged changed or out-of-range files are refused`() = fixture { controller, gateway ->
        val turn = controller.contextForQuestion("summary quarterly chart", "").turn!!
        turn.search("summary; notes", null)
        for ((file, page) in listOf("missing.pdf" to 1, "notes.txt" to 1, "reports/sales.pdf" to null,
            "reports/sales.pdf" to 9)) {
            assertEquals("$file $page", WorkspaceToolOutcome.Failure("workspace_page_unavailable"), turn.viewPage(file, page))
        }
        gateway.entries["sales"] = gateway.entries.getValue("sales").copy(modifiedAtMs = 99)
        assertEquals(WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED), turn.viewPage("reports/sales.pdf", 2))
    }

    @Test
    fun `only pages this turn's text cited can be viewed and a new turn ends the old one`() = fixture { controller, _ ->
        val first = controller.contextForQuestion("quarterly chart", "").turn!!
        assertEquals("jpeg:PDF:2:Quarterly chart.", jpeg(first.viewPage("reports/sales.pdf", 2)))
        assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), first.viewPage("reports/sales.pdf", 1))
        assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), first.viewPage("whiteboard.jpg", null))
        first.search("sprint plan", null)
        assertEquals("jpeg:IMAGE:1:Sprint plan", jpeg(first.viewPage("whiteboard.jpg", null)))
        val second = controller.contextForQuestion("sprint plan", "").turn!!
        assertEquals(first.version, second.version)
        assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), second.viewPage("reports/sales.pdf", 2))
        assertEquals(WorkspaceToolOutcome.Failure(WORKSPACE_SOURCE_CHANGED), first.viewPage("whiteboard.jpg", null))
        assertFalse(first.isUsable())
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
            val turn = controller.contextForQuestion("quarterly chart", "").turn!!
            val started = System.nanoTime()
            assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), turn.viewPage("sales.pdf", 2))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
            val opens = gateway.opens.getValue("sales")
            assertEquals(WorkspaceToolOutcome.Failure("workspace_page_unavailable"), turn.viewPage("sales.pdf", 2))
            assertEquals(opens, gateway.opens.getValue("sales"))
            gate.countDown()
            withTimeout(2_000) { while (turn.viewPage("sales.pdf", 2) !is WorkspaceToolOutcome.Image) delay(20) }
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
            val turn = controller.contextForQuestion("quarterly chart", "").turn!!
            assertEquals("jpeg:PDF:1:Quarterly chart.", jpeg(turn.viewPage("sales › draft.pdf", 1)))
            assertEquals("jpeg:PDF:1:Quarterly chart.", jpeg(turn.viewPage("sales › draft.pdf › page 1", 1)))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `malformed calls never reach the workspace`() {
        val tool = ViewWorkspacePageTool()
        val invalid = listOf("not-json", "{}", """{"file":""}""", """{"file":"a.pdf","page":0}""",
            """{"file":"a.pdf","page":"2"}""", """{"file":"a.pdf","page":1.5}""",
            """{"file":"a.pdf","uri":"content://secret"}""", JSONObject().put("file", "x".repeat(161)).toString())
        invalid.forEach { assertTrue(it, tool.validate(it) is AssistantToolValidation.Invalid) }
        assertTrue(tool.validate("""{"file":"board.jpg","page":null}""") is AssistantToolValidation.Valid)
    }

    @Test
    fun `the schema lists every property as required for strict providers`() {
        val schema = JSONObject(ViewWorkspacePageTool().parametersSchema.text)
        val required = schema.getJSONArray("required").let { array -> List(array.length()) { array.getString(it) } }
        assertEquals(schema.getJSONObject("properties").keys().asSequence().toSet(), required.toSet())
        assertFalse(schema.getBoolean("additionalProperties"))
    }

    @Test
    fun `workspace and photo tool schemas are accepted by strict function calling`() {
        listOf(SearchWorkspaceTool().parametersSchema, ViewWorkspacePageTool().parametersSchema,
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

    private fun jpeg(outcome: WorkspaceToolOutcome): String? = (outcome as? WorkspaceToolOutcome.Image)?.jpeg?.let { String(it) }

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
