package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
            val access = FakeWorkspaceTurn()
            val tool = SearchWorkspaceTool()
            val registry = AssistantToolRegistry(listOf(tool))
            val features = SearchWorkspaceToolTest.FEATURES.copy(supportsWorkspaceSearch = backend != ProviderBackend.HERMES)
            val available = registry.availableDefinitions(features, access.version, access)
            val prompt = prompt(available.map { it.name })
            val client = RecordingClient()
            val provider = OpenAiCompatProvider(preset, client, { true }, registry,
                supportsVision = { false }, backendProvider = { backend })
            provider.streamEvents(ChatRequest(userText = "What is the notice period?", systemPrompt = prompt, workspaceVersion = access.version, workspaceTurn = access)).toList()
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
        val access = FakeWorkspaceTurn()
        val transport = RecordingCodexTransport(listOf(textResponse()))
        val provider = codex(transport, access)
        val prompt = prompt(listOf(SEARCH_WORKSPACE_TOOL_NAME))
        provider.streamEvents(ChatRequest(userText = "What is the notice period?", systemPrompt = prompt, workspaceVersion = access.version, workspaceTurn = access)).toList()
        assertEquals(1, transport.requests.size)
        assertEquals(prompt, JSONObject(transport.requests.single().body).getString("instructions"))
        assertEquals(0, access.executions)
        assertTrue(MEMORY.length + EXCERPTS.length + 2 <= WorkspaceLimits.MAX_PERSONAL_CONTEXT_CHARS)
    }

    @Test
    fun `two identical model-requested searches execute once within the shared tool loop`() = runTest {
        val access = FakeWorkspaceTurn().apply { result = WorkspaceSearchResult(EXCERPTS, 1) }
        val calls = listOf("one", "two").map { id -> JSONObject().put("type", "response.output_item.done")
            .put("item", JSONObject().put("type", "function_call").put("call_id", id)
                .put("name", SEARCH_WORKSPACE_TOOL_NAME).put("arguments", "{\"query\":\"notice\"}")).toString() }
        val transport = RecordingCodexTransport(listOf(calls + COMPLETED, textResponse()))
        codex(transport, access).streamEvents(ChatRequest(userText = "What does it say?", systemPrompt = prompt(listOf(SEARCH_WORKSPACE_TOOL_NAME)), workspaceVersion = access.version, workspaceTurn = access)).toList()
        assertEquals(1, access.executions)
        assertEquals(2, transport.requests.size)
        val replay = JSONObject(transport.requests[1].body)
        val replayTools = replay.getJSONArray("tools")
        assertTrue((0 until replayTools.length()).any {
            replayTools.getJSONObject(it).optString("name") == SEARCH_WORKSPACE_TOOL_NAME
        })
        val inputs = replay.getJSONArray("input")
        val results = (0 until inputs.length()).map { inputs.getJSONObject(it) }
            .filter { it.optString("type") == "function_call_output" }
        assertEquals(2, results.size)
        assertEquals(EXCERPTS, JSONObject(results[0].getString("output")).getString("excerpts"))
        // An identical retry returns the first result instead of searching again.
        assertEquals(results[0].getString("output"), results[1].getString("output"))
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

    @Test
    fun `Off while a phone tool waits prevents compat and Hermes follow-up requests`() = runTest {
        for ((preset, backend) in listOf(ProviderCatalog.openAi to ProviderBackend.OPENAI_COMPAT,
            ProviderCatalog.hermes to ProviderBackend.HERMES, ProviderCatalog.custom to ProviderBackend.HERMES)
        ) {
            val access = FakeWorkspaceTurn()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val tool = TestAssistantTool(LIST_NOTES_TOOL_NAME, executor = { _, _ ->
                started.complete(Unit)
                finish.await()
                AssistantToolResult.Json("{\"ok\":true}")
            })
            val client = ScriptedClient { index ->
                check(index == 1) { "Workspace excerpts were sent again" }
                if (backend == ProviderBackend.HERMES) listOf(OpenAiChatSseEvent.Delta(
                    content = "$COMPAT_TEXT_TOOL_REQUEST_TOKEN{\"name\":\"${tool.name}\",\"arguments\":{}}\n"))
                else listOf(OpenAiChatSseEvent.Delta(toolCalls = listOf(OpenAiChatToolCallDelta(
                    index = 0, id = "phone", nameFragment = tool.name, argumentsFragment = "{}"))))
            }
            val provider = OpenAiCompatProvider(preset, client, { true }, AssistantToolRegistry(listOf(tool)),
                supportsVision = { false }, backendProvider = { backend })
            val response = async { provider.streamEvents(guardedRequest(access, listOf(tool.name))).toList() }
            started.await()
            access.available = false
            finish.complete(Unit)
            assertWorkspaceRevoked(response.await())
            assertEquals(preset.id, 1, client.requests.size)
            assertTrue(client.requests.single().messages.getJSONObject(0).getString("content").contains(EXCERPTS))
        }
    }

    @Test
    fun `Off while a Codex phone tool waits prevents the final request`() = runTest {
        val access = FakeWorkspaceTurn()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val tool = TestAssistantTool(LIST_NOTES_TOOL_NAME, executor = { _, _ ->
            started.complete(Unit)
            finish.await()
            AssistantToolResult.Json("{\"ok\":true}")
        })
        val call = JSONObject().put("type", "response.output_item.done").put("item", JSONObject()
            .put("type", "function_call").put("call_id", "phone").put("name", tool.name)
            .put("arguments", "{}")).toString()
        val transport = RecordingCodexTransport(listOf(listOf(call, COMPLETED)))
        val response = async {
            codex(transport, access, listOf(tool)).streamEvents(guardedRequest(access, listOf(tool.name))).toList()
        }
        started.await()
        access.available = false
        finish.complete(Unit)
        assertWorkspaceRevoked(response.await())
        assertEquals(1, transport.requests.size)
        assertEquals(prompt(listOf(tool.name)), JSONObject(transport.requests.single().body).getString("instructions"))
    }

    @Test
    fun `a changed workspace blocks the compat retry without tool declarations`() = runTest {
        val access = FakeWorkspaceTurn()
        val client = ScriptedClient {
            access.generation++
            throw OpenAiCompatHttpException(400, "Tools rejected")
        }
        val provider = OpenAiCompatProvider(ProviderCatalog.openAi, client, { true },
            AssistantToolRegistry(listOf(SearchWorkspaceTool())), supportsVision = { false })
        assertWorkspaceRevoked(provider.streamEvents(guardedRequest(access)).toList())
        assertEquals(1, client.requests.size)
    }

    @Test
    fun `Off prevents Codex unauthorized and transient stream retries`() = runTest {
        for (unauthorized in listOf(true, false)) {
            val access = FakeWorkspaceTurn()
            val events = if (unauthorized) emptyList() else listOf(
                "{\"type\":\"response.failed\",\"response\":{\"error\":{\"type\":\"server_error\",\"message\":\"Retry\"}}}")
            val transport = RecordingCodexTransport(listOf(events), listOf(if (unauthorized) 401 else 200)) {
                access.available = false
            }
            var refreshCount = 0
            val provider = codex(transport, access, refreshTokens = { refreshCount++; tokens() })
            assertWorkspaceRevoked(provider.streamEvents(guardedRequest(access)).toList())
            assertEquals(1, transport.requests.size)
            assertEquals(if (unauthorized) 1 else 0, refreshCount)
        }
    }

    private fun guardedRequest(access: FakeWorkspaceTurn, names: List<String> = listOf(SEARCH_WORKSPACE_TOOL_NAME),
        carriesEvidence: Boolean = true): ChatRequest =
        ChatRequest(userText = "What is the notice period?", systemPrompt = prompt(names),
            workspaceVersion = access.version, workspaceTurn = access).forCurrentWorkspace(carriesEvidence) {
            error("A usable turn keeps its prompt")
        }

    private fun assertWorkspaceRevoked(events: List<AiProviderEvent>) {
        assertTrue(events.filterIsInstance<AiProviderEvent.Failed>().single().message.contains("Workspace changed"))
        assertTrue(events.none { it is AiProviderEvent.MessageDone })
    }

    private fun codex(transport: RecordingCodexTransport, @Suppress("UNUSED_PARAMETER") access: FakeWorkspaceTurn,
        tools: List<AssistantToolDefinition> = listOf(SearchWorkspaceTool()),
        refreshTokens: suspend () -> CodexChatGptOAuthTokenBundle = { error("Unexpected token refresh") },
    ) = ChatGptCodexProvider(
        ChatGptCodexApiClient(tokenProvider = { tokens() },
            refreshTokens = refreshTokens, transport = transport, sessionId = "workspace-fixture"),
        oauthConfigured = { true }, toolRegistry = AssistantToolRegistry(tools),
    )

    private class ScriptedClient(private val response: suspend (Int) -> List<OpenAiChatSseEvent>) : OpenAiCompatChatClient {
        val requests = mutableListOf<OpenAiCompatChatRequest>()
        override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> = flow {
            requests += request
            response(requests.size).forEach { emit(it) }
        }
        override fun cancel(requestId: String) = Unit
    }

    private class RecordingClient : OpenAiCompatChatClient {
        val requests = mutableListOf<OpenAiCompatChatRequest>()
        override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> = flow {
            requests += request
            emit(OpenAiChatSseEvent.Delta(content = "According to notice.txt, two months."))
        }
        override fun cancel(requestId: String) = Unit
    }

    private class RecordingCodexTransport(responses: List<List<String>>,
        statuses: List<Int> = responses.map { 200 }, private val afterResponse: () -> Unit = {},
    ) : ChatGptCodexHttpTransport {
        private val responses = ArrayDeque(responses)
        private val statuses = ArrayDeque(statuses)
        val requests = mutableListOf<ChatGptCodexHttpRequest>()
        override suspend fun execute(requestId: String, request: ChatGptCodexHttpRequest,
            consumeData: suspend (String) -> Boolean): ChatGptCodexHttpResponse {
            requests += request
            for (event in responses.removeFirst()) if (!consumeData(event)) break
            afterResponse()
            return ChatGptCodexHttpResponse(statuses.removeFirst())
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
        private fun tokens() = CodexChatGptOAuthTokenBundle("token", "id", "refresh", "account", "plus", "person@example.test")
    }
}
