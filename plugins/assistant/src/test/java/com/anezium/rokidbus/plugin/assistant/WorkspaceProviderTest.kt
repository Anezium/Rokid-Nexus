package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceProviderTest {
    @Test
    fun `all presets and detected Custom-Hermes receive identical first-request excerpts without searching`() = runTest {
        for ((preset, backend) in ProviderCatalog.presets.map { it to it.backend } +
            (ProviderCatalog.custom to ProviderBackend.HERMES)
        ) {
            val access = FakeWorkspaceSearchAccess()
            val tool = SearchWorkspaceTool { access }
            val registry = AssistantToolRegistry(listOf(tool))
            val features = SearchWorkspaceToolTest.FEATURES.copy(supportsWorkspaceSearch = backend != ProviderBackend.HERMES)
            val available = registry.availableDefinitions(features)
            val prompt = prompt(available.map { it.name })
            val client = RecordingClient()
            val provider = OpenAiCompatProvider(preset, client, { true }, registry,
                supportsVision = { false }, backendProvider = { backend })
            provider.streamEvents(ChatRequest(userText = "What is the notice period?", systemPrompt = prompt)).toList()
            assertEquals(preset.id, 1, client.requests.size)
            assertEquals(prompt, client.requests.single().messages.getJSONObject(0).getString("content"))
            assertTrue(client.requests.single().request.systemPrompt!!.endsWith("$MEMORY\n\n$EXCERPTS"))
            assertEquals(0, access.executions)
            if (backend == ProviderBackend.HERMES) {
                assertTrue(client.requests.single().toolDefinitions.isEmpty())
                assertFalse(prompt.contains(SEARCH_WORKSPACE_TOOL_NAME))
                assertFalse(prompt.contains(COMPAT_TEXT_TOOL_REQUEST_TOKEN))
            } else assertEquals(listOf(SEARCH_WORKSPACE_TOOL_NAME), client.requests.single().toolDefinitions.map { it.name })
        }
    }

    @Test
    fun `Codex OAuth carries bounded memory and excerpts in its first instructions request`() = runTest {
        val access = FakeWorkspaceSearchAccess()
        val transport = RecordingCodexTransport(listOf(textResponse()))
        val provider = codex(transport, access)
        val prompt = prompt(listOf(SEARCH_WORKSPACE_TOOL_NAME))
        provider.streamEvents(ChatRequest(userText = "What is the notice period?", systemPrompt = prompt)).toList()
        assertEquals(1, transport.requests.size)
        assertEquals(prompt, JSONObject(transport.requests.single().body).getString("instructions"))
        assertEquals(0, access.executions)
        assertTrue(MEMORY.length + EXCERPTS.length + 2 <= WorkspaceLimits.MAX_PERSONAL_CONTEXT_CHARS)
    }

    @Test
    fun `two model-requested searches consume one execution and one existing final round`() = runTest {
        val access = FakeWorkspaceSearchAccess().apply { result = WorkspaceSearchResult(EXCERPTS, 1) }
        val calls = listOf("one", "two").map { id -> JSONObject().put("type", "response.output_item.done")
            .put("item", JSONObject().put("type", "function_call").put("call_id", id)
                .put("name", SEARCH_WORKSPACE_TOOL_NAME).put("arguments", "{\"query\":\"notice\"}")).toString() }
        val transport = RecordingCodexTransport(listOf(calls + COMPLETED, textResponse()))
        codex(transport, access).streamEvents(ChatRequest(userText = "What does it say?", systemPrompt = prompt(listOf(SEARCH_WORKSPACE_TOOL_NAME)))).toList()
        assertEquals(1, access.executions)
        assertEquals(2, transport.requests.size)
        val replay = JSONObject(transport.requests[1].body)
        val replayTools = replay.getJSONArray("tools")
        assertFalse((0 until replayTools.length()).any { replayTools.getJSONObject(it).optString("type") == "function" })
        val inputs = replay.getJSONArray("input")
        val results = (0 until inputs.length()).map { inputs.getJSONObject(it) }
            .filter { it.optString("type") == "function_call_output" }
        assertEquals(2, results.size)
        assertEquals(EXCERPTS, JSONObject(results[0].getString("output")).getString("excerpts"))
        assertEquals(TOOL_ERROR_ALREADY_USED, JSONObject(results[1].getString("output")).getString("code"))
    }

    @Test
    fun `empty block adds no dummy fence and Memory remains verbatim in every combination`() {
        for (synced in listOf("", "x".repeat(6_000))) {
            for (notes in listOf("", "y".repeat(4_000))) {
                val memory = combineAccountContextForPrompt(true, synced, notes)
                val block = retriever().search("notice", workspacePromptBudget(memory)).excerpts
                val prompt = NexusAgentPolicy.buildSystemPrompt(memory = memory, workspace = block,
                    workspaceEnabled = true, availableToolNames = emptyList())
                if (memory.isNotEmpty()) assertTrue(prompt.contains("What the user has told you about themselves:\n$memory"))
                assertTrue(memory.length + block.length + (if (memory.isNotEmpty() && block.isNotEmpty()) 2 else 0) <= 10_002)
                if (memory.length == 10_002) {
                    assertEquals("", block)
                    assertFalse(prompt.contains("```"))
                }
            }
        }
    }

    private fun codex(transport: RecordingCodexTransport, access: WorkspaceSearchAccess) = ChatGptCodexProvider(
        ChatGptCodexApiClient(tokenProvider = { CodexChatGptOAuthTokenBundle("token", "id", "refresh", "account", "plus", "person@example.test") },
            refreshTokens = { error("Unexpected token refresh") }, transport = transport, sessionId = "workspace-fixture"),
        oauthConfigured = { true }, toolRegistry = AssistantToolRegistry(listOf(SearchWorkspaceTool { access })),
    )

    private class RecordingClient : OpenAiCompatChatClient {
        val requests = mutableListOf<OpenAiCompatChatRequest>()
        override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> = flow {
            requests += request
            emit(OpenAiChatSseEvent.Delta(content = "According to notice.txt, two months."))
        }
        override fun cancel(requestId: String) = Unit
    }

    private class RecordingCodexTransport(responses: List<List<String>>) : ChatGptCodexHttpTransport {
        private val responses = ArrayDeque(responses)
        val requests = mutableListOf<ChatGptCodexHttpRequest>()
        override suspend fun execute(requestId: String, request: ChatGptCodexHttpRequest,
            consumeData: suspend (String) -> Boolean): ChatGptCodexHttpResponse {
            requests += request
            for (event in responses.removeFirst()) if (!consumeData(event)) break
            return ChatGptCodexHttpResponse(200)
        }
        override fun cancel(requestId: String) = Unit
    }

    companion object {
        const val MEMORY = "Synced account context.\n\nManual notes."
        const val COMPLETED = "{\"type\":\"response.completed\",\"response\":{\"output\":[]}}"
        val EXCERPTS = retriever().search("notice").excerpts
        private fun retriever() = WorkspaceRetriever(listOf(WorkspaceDocument(WorkspaceEntry("notice", "notice.txt"),
            listOf(WorkspaceChunk(0, "The notice period is two months.")))))
        private fun prompt(names: List<String>) = NexusAgentPolicy.buildSystemPrompt(memory = MEMORY,
            workspace = EXCERPTS, workspaceEnabled = true, availableToolNames = names)
        private fun textResponse() = listOf("{\"type\":\"response.output_text.delta\",\"delta\":\"Two months, according to notice.txt.\"}",
            "{\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\"}}", COMPLETED)
    }
}
