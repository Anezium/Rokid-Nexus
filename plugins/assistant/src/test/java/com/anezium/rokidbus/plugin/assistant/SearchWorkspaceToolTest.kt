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
    fun `only a ready active workspace on a structured-tool backend is advertised`() {
        val access = FakeWorkspaceSearchAccess()
        val tool = SearchWorkspaceTool { access }
        assertFalse(tool.sideEffecting)
        val registry = AssistantToolRegistry(listOf(tool))
        assertTrue(registry.availableDefinitions(FEATURES).isEmpty())
        assertEquals(listOf(tool.name), registry.availableDefinitions(FEATURES, access.searchVersion()).map { it.name })
        access.available = false
        assertTrue(registry.availableDefinitions(FEATURES, access.searchVersion()).isEmpty())
        access.available = true
        assertTrue(registry.availableDefinitions(FEATURES.copy(supportsTools = false), access.searchVersion()).isEmpty())
        assertTrue(registry.availableDefinitions(FEATURES.copy(supportsWorkspaceSearch = false), access.searchVersion()).isEmpty())
        assertTrue(AssistantToolRegistry(listOf(tool), sessionContext = { AssistantToolSessionContext(false) })
            .availableDefinitions(FEATURES, access.searchVersion()).isEmpty())
        assertFalse(SEARCH_WORKSPACE_TOOL_NAME in HERMES_TEXT_TOOL_NAMES)
    }

    @Test
    fun `one distinct search executes and duplicate IDs reuse the result within the global budget`() = runTest {
        val access = FakeWorkspaceSearchAccess()
        val tool = SearchWorkspaceTool { access }
        val other = TestAssistantTool("other")
        val phase = AssistantToolRegistry(listOf(tool, other)).newExecutionPhase(FEATURES, access.searchVersion())
        val first = phase.execute(call("one"))
        assertEquals(first, phase.execute(call("one", "different")))
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED), phase.execute(call("two")))
        assertEquals(1, access.executions)
        assertTrue(phase.execute(AssistantToolCall("third", "other", "{}")) is AssistantToolResult.Json)
        assertTrue(phase.execute(AssistantToolCall("fourth", "other", "{}")) is AssistantToolResult.Json)
        assertEquals(AssistantToolResult.Error(TOOL_ERROR_ALREADY_USED),
            phase.execute(AssistantToolCall("fifth", "other", "{}")))
    }

    @Test
    fun `invalid queries never consume the valid search allocation`() = runTest {
        val access = FakeWorkspaceSearchAccess()
        val tool = SearchWorkspaceTool { access }
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(FEATURES, access.searchVersion())
        val invalid = listOf("not-json", "{}", "{\"query\":null}", "{\"query\":42}",
            "{\"query\":\"   \"}", "{\"query\":\"notice\",\"uri\":\"secret\"}",
            JSONObject().put("query", "x".repeat(241)).toString())
        invalid.forEachIndexed { index, json ->
            assertEquals(AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL),
                phase.execute(AssistantToolCall("invalid$index", tool.name, json)))
        }
        assertEquals(0, access.executions)
        assertTrue(phase.execute(call("valid")) is AssistantToolResult.Json)
        assertEquals(1, access.executions)
    }

    @Test
    fun `empty success and access lost before or during execution stay distinct`() = runTest {
        val access = FakeWorkspaceSearchAccess()
        val tool = SearchWorkspaceTool { access }
        val phase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(FEATURES, access.searchVersion())
        val empty = phase.execute(call("empty")) as AssistantToolResult.Json
        assertTrue(JSONObject(empty.text).getBoolean("ok"))
        assertEquals("", JSONObject(empty.text).getString("excerpts"))
        assertEquals(0, JSONObject(empty.text).getInt("matchCount"))
        val secondPhase = AssistantToolRegistry(listOf(tool)).newExecutionPhase(FEATURES, access.searchVersion())
        access.available = false
        assertEquals(AssistantToolResult.Error("workspace_unavailable"), secondPhase.execute(call("revoked")))
        access.available = true
        access.invalidateDuringSearch = true
        assertEquals(AssistantToolResult.Error("workspace_unavailable"),
            AssistantToolRegistry(listOf(tool)).newExecutionPhase(FEATURES, access.searchVersion()).execute(call("during")))
    }

    @Test
    fun `fallback returns exactly the shared fenced excerpts and cancellation propagates`() = runTest {
        val access = FakeWorkspaceSearchAccess()
        access.result = WorkspaceRetriever(listOf(WorkspaceDocument(WorkspaceEntry("notice", "notice.txt"),
            listOf(WorkspaceChunk(0, "Notice is two months."))))).search("notice")
        val phase = AssistantToolRegistry(listOf(SearchWorkspaceTool { access })).newExecutionPhase(FEATURES, access.searchVersion())
        val result = phase.execute(call("query")) as AssistantToolResult.Json
        assertEquals(access.result.excerpts, JSONObject(result.text).getString("excerpts"))
        assertEquals(1, JSONObject(result.text).getInt("matchCount"))
        assertTrue(result.text.length < 3_000)
        access.cancel = true
        val next = AssistantToolRegistry(listOf(SearchWorkspaceTool { access })).newExecutionPhase(FEATURES, access.searchVersion())
        assertTrue(runCatching { next.execute(call("cancel")) }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `an index change during search cannot return excerpts from the previous version`() = runTest {
        val access = FakeWorkspaceSearchAccess().apply { changeVersionDuringSearch = true }
        val phase = AssistantToolRegistry(listOf(SearchWorkspaceTool { access })).newExecutionPhase(FEATURES, access.searchVersion())
        assertEquals(AssistantToolResult.Error("workspace_unavailable"), phase.execute(call("changed")))
        assertTrue(access.available)
        assertEquals(1, access.executions)
    }

    @Test
    fun `folder replacement index refresh and Off-On after advertisement reject the delayed search`() = runTest {
        for (change in listOf("folder", "index", "off-on")) {
            val access = FakeWorkspaceSearchAccess()
            val requestingVersion = access.searchVersion()
            val phase = AssistantToolRegistry(listOf(SearchWorkspaceTool { access }))
                .newExecutionPhase(FEATURES, requestingVersion)
            assertEquals(listOf(SEARCH_WORKSPACE_TOOL_NAME), phase.availableDefinitions.map { it.name })
            when (change) {
                "folder" -> access.generation++
                "index" -> access.revision++
                "off-on" -> access.generation += 2
            }
            assertTrue(access.available)
            assertEquals(change, AssistantToolResult.Error("workspace_unavailable"), phase.execute(call("delayed")))
            assertEquals(change, 0, access.executions)
        }
    }

    @Test
    fun `provider preparation cannot replace the identity captured by the requesting turn`() {
        val access = FakeWorkspaceSearchAccess()
        val request = ChatRequest(userText = "Notice?", workspaceVersion = access.searchVersion())
        access.generation++
        val phase = AssistantToolRegistry(listOf(SearchWorkspaceTool { access }))
            .newExecutionPhase(FEATURES, request.workspaceVersion)
        assertTrue(phase.availableDefinitions.isEmpty())
        assertEquals(0, access.executions)
    }

    private fun call(id: String, query: String = "notice") = AssistantToolCall(id, SEARCH_WORKSPACE_TOOL_NAME,
        JSONObject().put("query", query).toString())

    companion object {
        internal val FEATURES = AssistantProviderFeatures(supportsTools = true, supportsVision = false)
    }
}

internal class FakeWorkspaceSearchAccess : WorkspaceSearchAccess {
    var available = true
    var executions = 0
    var result = WorkspaceSearchResult()
    var invalidateDuringSearch = false
    var cancel = false
    var revision = 0L
    var generation = 0L
    var changeVersionDuringSearch = false
    override fun isSearchAvailable() = available
    override fun searchVersion(): Pair<Long, Long> = generation to revision
    override suspend fun search(query: String): WorkspaceSearchResult? {
        executions++
        if (cancel) throw CancellationException("fixture cancelled")
        if (invalidateDuringSearch) available = false
        if (changeVersionDuringSearch) revision++
        return result
    }
}
