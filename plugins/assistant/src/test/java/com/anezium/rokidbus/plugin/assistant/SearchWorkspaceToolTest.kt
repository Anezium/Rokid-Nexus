package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchWorkspaceToolTest {
    @Test
    fun `only a ready active workspace turn on a structured-tool backend is advertised`() {
        val turn = FakeWorkspaceTurn()
        val tool = SearchWorkspaceTool()
        assertFalse(tool.sideEffecting)
        val registry = AssistantToolRegistry(listOf(tool))
        assertTrue(registry.availableDefinitions(FEATURES).isEmpty())
        assertTrue(registry.availableDefinitions(FEATURES, turn.version).isEmpty())
        assertEquals(listOf(tool.name), registry.availableDefinitions(FEATURES, turn.version, turn).map { it.name })
        turn.available = false
        assertTrue(registry.availableDefinitions(FEATURES, turn.version, turn).isEmpty())
        turn.available = true
        assertTrue(registry.availableDefinitions(FEATURES.copy(supportsTools = false), turn.version, turn).isEmpty())
        assertTrue(registry.availableDefinitions(FEATURES.copy(supportsWorkspaceSearch = false), turn.version, turn).isEmpty())
        assertTrue(AssistantToolRegistry(listOf(tool), sessionContext = { AssistantToolSessionContext(false) })
            .availableDefinitions(FEATURES, turn.version, turn).isEmpty())
        assertTrue(AssistantToolRegistry(listOf(SearchWorkspaceTool { false }))
            .availableDefinitions(FEATURES, turn.version, turn).isEmpty())
        assertFalse(SEARCH_WORKSPACE_TOOL_NAME in HERMES_TEXT_TOOL_NAMES)
        assertFalse(VIEW_WORKSPACE_PAGE_TOOL_NAME in HERMES_TEXT_TOOL_NAMES)
    }

    @Test
    fun `a folder with no searchable text never advertises global search`() {
        val turn = FakeWorkspaceTurn().apply { searchable = false; viewable = true }
        assertTrue(AssistantToolRegistry(listOf(SearchWorkspaceTool())).availableDefinitions(FEATURES, turn.version, turn)
            .isEmpty())
        assertEquals(listOf(VIEW_WORKSPACE_PAGE_TOOL_NAME), AssistantToolRegistry(listOf(SearchWorkspaceTool(),
            ViewWorkspacePageTool())).availableDefinitions(FEATURES.copy(supportsVision = true), turn.version, turn)
            .map { it.name })
    }

    @Test
    fun `identical retries reuse the first result and do not reach the turn again`() = runTest {
        val turn = FakeWorkspaceTurn()
        val tool = SearchWorkspaceTool()
        val other = TestAssistantTool("other")
        val phase = AssistantToolRegistry(listOf(tool, other)).newExecutionPhase(FEATURES, turn.version, turn)
        val first = phase.execute(call("one"))
        assertEquals(first, phase.execute(call("one", "different")))
        assertEquals(first, phase.execute(call("two")))
        assertEquals(1, turn.executions)
        for (execution in 3..8) {
            assertTrue(phase.execute(AssistantToolCall("other-$execution", "other", "{}")) is AssistantToolResult.Json)
        }
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED),
            phase.execute(AssistantToolCall("over-budget", "other", "{}")))
    }

    @Test
    fun `invalid arguments never consume the search allocation and a null or named file is accepted`() = runTest {
        val turn = FakeWorkspaceTurn()
        val tool = SearchWorkspaceTool()
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(FEATURES, turn.version, turn)
        val invalid = listOf("not-json", "{}", "{\"query\":null}", "{\"query\":42}",
            "{\"query\":\"   \"}", "{\"query\":\"notice\",\"uri\":\"secret\"}", "{\"query\":\"notice\",\"file\":\"\"}",
            "{\"query\":\"notice\",\"file\":7}", JSONObject().put("query", "notice").put("file", "x".repeat(161)).toString(),
            JSONObject().put("query", "x".repeat(241)).toString())
        invalid.forEachIndexed { index, json ->
            assertEquals(json, AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL),
                phase.execute(AssistantToolCall("invalid$index", tool.name, json)))
        }
        assertEquals(0, turn.executions)
        assertTrue(phase.execute(call("valid")) is AssistantToolResult.Json)
        assertTrue(phase.execute(AssistantToolCall("named", tool.name,
            """{"query":"lieu","file":"orion.pdf"}""")) is AssistantToolResult.Json)
        assertEquals(listOf(null, "orion.pdf"), turn.files)
    }

    @Test
    fun `empty success and access lost before or during execution stay distinct`() = runTest {
        val turn = FakeWorkspaceTurn()
        val tool = SearchWorkspaceTool()
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(FEATURES, turn.version, turn)
        val empty = phase.execute(call("empty")) as AssistantToolResult.Json
        assertTrue(JSONObject(empty.text).getBoolean("ok"))
        assertEquals("no_match", JSONObject(empty.text).getString("status"))
        assertEquals("", JSONObject(empty.text).getString("excerpts"))
        assertEquals(0, JSONObject(empty.text).getInt("matchCount"))
        val secondPhase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(FEATURES, turn.version, turn)
        turn.available = false
        assertEquals(AssistantToolResult.Error(WORKSPACE_SOURCE_CHANGED), secondPhase.execute(call("revoked", "other")))
        val during = FakeWorkspaceTurn().apply { invalidateDuringSearch = true }
        assertEquals(AssistantToolResult.Error(WORKSPACE_SOURCE_CHANGED),
            AssistantToolRegistry(listOf(tool)).newExecutionPhase(FEATURES, during.version, during).execute(call("during")))
    }

    @Test
    fun `the result carries the shared fenced excerpts and cancellation propagates`() = runTest {
        val turn = FakeWorkspaceTurn()
        turn.result = WorkspaceRetriever(listOf(WorkspaceDocument(WorkspaceEntry("notice", "notice.txt"),
            listOf(WorkspaceChunk(0, "Notice is two months."))))).search("notice")
        val phase = AssistantToolRegistry(listOf(SearchWorkspaceTool())).newExecutionPhase(FEATURES, turn.version, turn)
        val result = phase.execute(call("query")) as AssistantToolResult.Json
        assertEquals(turn.result.excerpts, JSONObject(result.text).getString("excerpts"))
        assertEquals(1, JSONObject(result.text).getInt("matchCount"))
        assertTrue(result.text.length < 3_000)
        turn.cancel = true
        val next = AssistantToolRegistry(listOf(SearchWorkspaceTool())).newExecutionPhase(FEATURES, turn.version, turn)
        assertTrue(runCatching { next.execute(call("cancel", "fresh")) }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `an index change during search cannot return excerpts from the previous version`() = runTest {
        val turn = FakeWorkspaceTurn().apply { changeVersionDuringSearch = true }
        val phase = AssistantToolRegistry(listOf(SearchWorkspaceTool())).newExecutionPhase(FEATURES, turn.version, turn)
        assertEquals(AssistantToolResult.Error(WORKSPACE_SOURCE_CHANGED), phase.execute(call("changed")))
        assertEquals(1, turn.executions)
    }

    @Test
    fun `folder replacement index refresh and Off-On after advertisement reject the delayed search`() = runTest {
        for (change in listOf("folder", "index", "off-on")) {
            val turn = FakeWorkspaceTurn()
            val phase = AssistantToolRegistry(listOf(SearchWorkspaceTool())).newExecutionPhase(FEATURES, turn.version, turn)
            assertEquals(listOf(SEARCH_WORKSPACE_TOOL_NAME), phase.availableDefinitions.map { it.name })
            when (change) {
                "folder" -> turn.generation++
                "index" -> turn.revision++
                "off-on" -> turn.generation += 2
            }
            assertEquals(change, AssistantToolResult.Error(WORKSPACE_SOURCE_CHANGED), phase.execute(call("delayed")))
            assertEquals(change, 0, turn.executions)
        }
    }

    @Test
    fun `provider preparation cannot replace the identity captured by the requesting turn`() {
        val turn = FakeWorkspaceTurn()
        val request = ChatRequest(userText = "Notice?", workspaceVersion = turn.version, workspaceTurn = turn)
        turn.generation++
        val phase = AssistantToolRegistry(listOf(SearchWorkspaceTool()))
            .newExecutionPhase(FEATURES, request.workspaceVersion, request.workspaceTurn)
        assertTrue(phase.availableDefinitions.isEmpty())
        assertTrue(AssistantToolRegistry(listOf(SearchWorkspaceTool()))
            .newExecutionPhase(FEATURES, 0L to 1L, FakeWorkspaceTurn()).availableDefinitions.isEmpty())
        assertEquals(0, turn.executions)
    }

    @Test
    fun `the nullable file schema is strict and lists every property`() {
        val schema = SearchWorkspaceTool().parametersSchema
        assertTrue(schema.isStrictCompatible())
        val json = schema.toJsonObject()
        assertEquals(setOf("query", "file"), json.getJSONArray("required").let { a -> List(a.length()) { a.getString(it) } }.toSet())
        val type = json.getJSONObject("properties").getJSONObject("file").getJSONArray("type")
        assertEquals(listOf("string", "null"), List(type.length()) { type.getString(it) })
        assertFalse(SearchWorkspaceTool().description.contains("are themselves split"))
        assertTrue(SearchWorkspaceTool().description.contains("semicolon"))
    }

    private fun call(id: String, query: String = "notice") = AssistantToolCall(id, SEARCH_WORKSPACE_TOOL_NAME,
        JSONObject().put("query", query).put("file", JSONObject.NULL).toString())

    companion object {
        internal val FEATURES = AssistantProviderFeatures(supportsTools = true, supportsVision = false)
    }
}
