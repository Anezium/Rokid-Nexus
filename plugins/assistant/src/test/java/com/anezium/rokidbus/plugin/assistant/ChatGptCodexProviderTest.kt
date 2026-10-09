package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class ChatGptCodexProviderTest {
    @Test
    fun shapesCodexRequestWithSubscriptionHeadersAndResponsesBody() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf("""{"type":"response.completed"}"""),
            ),
        )
        val client = client(
            transport = transport,
            initialTokens = tokens(accessToken = "oauth-access", accountId = "account-123"),
            sessionId = "session-456",
        )
        val request = ChatRequest(
            userText = "What is in this photo?",
            systemPrompt = "Answer for a glasses HUD.",
            history = listOf(ChatMessage(role = "assistant", content = "Ready.")),
            photos = listOf(PhotoAttachment("image/jpeg", "AQID")),
        )

        client.streamResponses(
            request = request,
            modelId = "unsupported-model",
            reasoningEffort = "xhigh",
            toolDefinitions = listOf(
                testTakePhotoTool { error("Tool was not expected.") },
            ),
        ).toList()

        val sent = transport.requests.single()
        assertEquals(ChatGptCodexApiClient.DEFAULT_ENDPOINT, sent.endpoint)
        assertEquals("Bearer oauth-access", sent.headers["Authorization"])
        assertEquals("account-123", sent.headers["chatgpt-account-id"])
        assertEquals("responses=experimental", sent.headers["OpenAI-Beta"])
        assertEquals("codex_cli_rs", sent.headers["originator"])
        assertEquals("session-456", sent.headers["session_id"])
        assertEquals("application/json", sent.headers["Content-Type"])
        assertEquals("text/event-stream", sent.headers["Accept"])

        val body = JSONObject(sent.body)
        assertEquals("gpt-6-luna", ChatGptCodexApiClient.DEFAULT_MODEL_ID)
        assertEquals(ChatGptCodexApiClient.DEFAULT_MODEL_ID, body.getString("model"))
        assertEquals("Answer for a glasses HUD.", body.getString("instructions"))
        assertEquals(1, Regex("\"tools\"\\s*:").findAll(sent.body).count())
        val tools = body.getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals("web_search", tools.getJSONObject(0).getString("type"))
        val takePhoto = tools.getJSONObject(1)
        assertEquals("function", takePhoto.getString("type"))
        assertEquals("take_photo", takePhoto.getString("name"))
        assertEquals(
            "Capture one current point-of-view photo from the user's Rokid glasses. " +
                "Call this only when answering the current request requires seeing the user's " +
                "current physical scene. Do not call it for web images, questions about camera " +
                "behavior or settings, discussion of a previous photo, or questions answerable " +
                "from text or web information.",
            takePhoto.getString("description"),
        )
        assertTrue(takePhoto.getBoolean("strict"))
        val parameters = takePhoto.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
        assertEquals(0, parameters.getJSONArray("required").length())
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertEquals("auto", body.getString("tool_choice"))
        assertFalse(body.getBoolean("parallel_tool_calls"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals(0, body.getJSONArray("include").length())
        assertEquals("xhigh", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", body.getJSONObject("reasoning").getString("summary"))

        val input = body.getJSONArray("input")
        assertEquals(2, input.length())
        assertEquals("message", input.getJSONObject(0).getString("type"))
        assertEquals("assistant", input.getJSONObject(0).getString("role"))
        val assistantContent = input.getJSONObject(0).getJSONArray("content")
        assertEquals("output_text", assistantContent.getJSONObject(0).getString("type"))
        val user = input.getJSONObject(1)
        assertEquals("user", user.getString("role"))
        val content = user.getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("What is in this photo?", content.getJSONObject(0).getString("text"))
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AQID",
            content.getJSONObject(1).getString("image_url"),
        )
        assertEquals("high", content.getJSONObject(1).getString("detail"))
    }

    @Test
    fun providerRequestUsesStoredReasoningEffortAndOnlySupportedModel() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf("""{"type":"response.completed"}"""),
            ),
        )
        val provider = ChatGptCodexProvider(
            apiClient = client(transport),
            oauthConfigured = { true },
            toolRegistry = unusedToolRegistry(),
            modelProvider = { "unsupported-model" },
            reasoningEffortProvider = { "medium" },
        )

        provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        val body = JSONObject(transport.requests.single().body)
        assertEquals("gpt-6-luna", body.getString("model"))
        assertEquals("medium", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("auto", body.getJSONObject("reasoning").getString("summary"))
    }

    @Test
    fun everyCurrentTierSurvivesToTheRequestBody() = runTest {
        listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra").forEach { modelId ->
            val transport = FakeTransport(
                StubResponse(
                    statusCode = 200,
                    sseData = listOf("""{"type":"response.completed"}"""),
                ),
            )
            val provider = ChatGptCodexProvider(
                apiClient = client(transport),
                oauthConfigured = { true },
                toolRegistry = unusedToolRegistry(),
                modelProvider = { modelId },
            )

            provider.streamEvents(ChatRequest(userText = "Hello")).toList()

            val body = JSONObject(transport.requests.single().body)
            assertEquals(modelId, body.getString("model"))
        }
    }

    @Test
    fun defaultModelIsLuna() {
        assertEquals("gpt-6-luna", ChatGptCodexApiClient.FAST_MODEL_ID)
        assertEquals("gpt-6.1-sol", ChatGptCodexApiClient.BALANCED_MODEL_ID)
        assertEquals("gpt-6-astra", ChatGptCodexApiClient.DEEP_MODEL_ID)
        assertEquals(ChatGptCodexApiClient.FAST_MODEL_ID, ChatGptCodexApiClient.DEFAULT_MODEL_ID)
        assertEquals(
            listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra"),
            ChatGptCodexApiClient.SUPPORTED_MODEL_IDS,
        )
        assertEquals("low", ChatGptCodexApiClient.DEFAULT_REASONING_EFFORT)
        assertEquals(
            "low",
            ChatGptCodexApiClient.supportedReasoningEffort(
                ChatGptCodexApiClient.DEFAULT_MODEL_ID,
                ChatGptCodexApiClient.DEFAULT_REASONING_EFFORT,
            ),
        )
    }

    @Test
    fun legacyModelMigrationKeepsEachSavedTier() {
        mapOf(
            "gpt-5.6-luna" to "gpt-6-luna",
            "gpt-5.6-terra" to "gpt-6.1-sol",
            "gpt-5.6-sol" to "gpt-6-astra",
            "gpt-6-sol" to "gpt-6.1-sol",
            " gpt-5.6-terra " to "gpt-6.1-sol",
            "gpt-6-luna" to "gpt-6-luna",
            "gpt-6.1-sol" to "gpt-6.1-sol",
            "gpt-6-astra" to "gpt-6-astra",
            "gpt-4o" to "gpt-6-luna",
            "unsupported-model" to "gpt-6-luna",
            "" to "gpt-6-luna",
        ).forEach { (saved, expected) ->
            assertEquals(saved, expected, ChatGptCodexApiClient.supportedModel(saved))
        }
    }

    @Test
    fun legacyModelMigrationReachesTheRequestBody() = runTest {
        mapOf(
            "gpt-5.6-luna" to "gpt-6-luna",
            "gpt-5.6-terra" to "gpt-6.1-sol",
            "gpt-5.6-sol" to "gpt-6-astra",
            "gpt-6-sol" to "gpt-6.1-sol",
        ).forEach { (saved, expected) ->
            val transport = FakeTransport(
                StubResponse(
                    statusCode = 200,
                    sseData = listOf("""{"type":"response.completed"}"""),
                ),
            )
            val provider = ChatGptCodexProvider(
                apiClient = client(transport),
                oauthConfigured = { true },
                toolRegistry = unusedToolRegistry(),
                modelProvider = { saved },
            )

            provider.streamEvents(ChatRequest(userText = "Hello")).toList()

            val body = JSONObject(transport.requests.single().body)
            assertEquals(saved, expected, body.getString("model"))
        }
    }

    @Test
    fun effortClampSendsOnlyEffortsTheModelAccepts() {
        val allowed = mapOf(
            "gpt-6-luna" to setOf("low", "medium", "high", "xhigh", "max"),
            "gpt-6.1-sol" to setOf("low", "medium", "high", "xhigh", "ultra"),
            "gpt-6-astra" to setOf("low", "medium", "high", "xhigh", "ultra"),
        )
        val efforts = listOf(
            "none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra",
            "LOW", " medium ", "", "bogus",
        ) + ChatGptCodexApiClient.SUPPORTED_REASONING_EFFORTS +
            ChatGptCodexApiClient.DEFAULT_REASONING_EFFORT
        assertEquals(allowed.keys.toList(), ChatGptCodexApiClient.SUPPORTED_MODEL_IDS)

        allowed.forEach { (model, modelEfforts) ->
            assertEquals(modelEfforts, ChatGptCodexApiClient.reasoningEffortsFor(model))
            efforts.forEach { effort ->
                val sent = ChatGptCodexApiClient.supportedReasoningEffort(model, effort)
                assertTrue("$model/$effort -> $sent", sent in modelEfforts)
                if (effort in modelEfforts) assertEquals("$model/$effort", effort, sent)
            }
            assertEquals("low", ChatGptCodexApiClient.supportedReasoningEffort(model, "none"))
        }
        assertEquals("max", ChatGptCodexApiClient.supportedReasoningEffort("gpt-6-luna", "ultra"))
        assertEquals("ultra", ChatGptCodexApiClient.supportedReasoningEffort("gpt-6.1-sol", "ultra"))
        assertEquals("ultra", ChatGptCodexApiClient.supportedReasoningEffort("gpt-6-astra", "ultra"))
        assertEquals("ultra", ChatGptCodexApiClient.supportedReasoningEffort("gpt-6.1-sol", "max"))
        assertEquals("ultra", ChatGptCodexApiClient.supportedReasoningEffort("gpt-6-astra", "max"))
        // A legacy saved model is clamped against the model it migrates to.
        assertEquals("max", ChatGptCodexApiClient.supportedReasoningEffort("gpt-5.6-luna", "ultra"))
        assertEquals("ultra", ChatGptCodexApiClient.supportedReasoningEffort("gpt-5.6-sol", "ultra"))
    }

    @Test
    fun effortClampReachesTheRequestBody() = runTest {
        listOf(
            Triple("gpt-6-luna", "ultra", "max"),
            Triple("gpt-6-luna", "none", "low"),
            Triple("gpt-6.1-sol", "ultra", "ultra"),
            Triple("gpt-6-astra", "xhigh", "xhigh"),
        ).forEach { (model, effort, expected) ->
            val transport = FakeTransport(
                StubResponse(
                    statusCode = 200,
                    sseData = listOf("""{"type":"response.completed"}"""),
                ),
            )
            val provider = ChatGptCodexProvider(
                apiClient = client(transport),
                oauthConfigured = { true },
                toolRegistry = unusedToolRegistry(),
                modelProvider = { model },
                reasoningEffortProvider = { effort },
            )

            provider.streamEvents(ChatRequest(userText = "Hello")).toList()

            val body = JSONObject(transport.requests.single().body)
            assertEquals(model, body.getString("model"))
            assertEquals("$model/$effort", expected, body.getJSONObject("reasoning").getString("effort"))
        }
    }

    @Test
    fun mapsSseDeltaAndDoneOntoProviderEvents() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """{"type":"response.output_text.delta","delta":"Bon"}""",
                    """{"type":"response.output_text.delta","delta":"jour"}""",
                    """{"type":"response.output_item.done","item":{"type":"message"}}""",
                    """{"type":"response.completed","response":{"output":[]}}""",
                ),
            ),
        )
        val provider = provider(transport)

        val events = provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertTrue(events[0] is AiProviderEvent.Started)
        assertEquals("Bon", (events[1] as AiProviderEvent.TextDelta).delta)
        assertEquals("jour", (events[2] as AiProviderEvent.TextDelta).delta)
        val done = events[3] as AiProviderEvent.MessageDone
        assertEquals("assistant", done.message.role)
        assertEquals("Bonjour", done.message.content)
    }

    @Test
    fun webSearchProgressFollowsObservedItemAndCallEvents() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """{"type":"response.output_item.added","item":{"id":"ws-added","type":"web_search_call","status":"in_progress"}}""",
                    """{"type":"response.web_search_call.in_progress","item_id":"ws-added"}""",
                    """{"type":"response.web_search_call.searching","item_id":"ws-added"}""",
                    """{"type":"response.output_item.done","item":{"id":"ws-added","type":"web_search_call","status":"completed"}}""",
                    """{"type":"response.web_search_call.in_progress","item_id":"ws-progress"}""",
                    """{"type":"response.web_search_call.searching","item_id":"ws-progress"}""",
                    """{"type":"response.web_search_call.completed","item_id":"ws-progress"}""",
                    """{"type":"response.completed"}""",
                ),
            ),
        )

        val events = provider(transport)
            .streamEvents(ChatRequest(userText = "Search for this"))
            .toList()

        assertEquals(
            listOf(
                "Searching the web…",
                "Thinking…",
                "Searching the web…",
                "Thinking…",
            ),
            events.filterIsInstance<AiProviderEvent.Progress>().map { event -> event.message },
        )
        assertTrue(events.last() is AiProviderEvent.MessageDone)
    }

    @Test
    fun webSearchProgressIsClearedWhenTheTurnEndsOrFails() = runTest {
        val completedTransport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """{"type":"response.output_item.added","item":{"id":"ws-open","type":"web_search_call","status":"in_progress"}}""",
                    """{"type":"response.completed"}""",
                ),
            ),
        )

        val completedEvents = provider(completedTransport)
            .streamEvents(ChatRequest(userText = "Search"))
            .toList()

        assertEquals(
            listOf("Searching the web…", "Thinking…"),
            completedEvents.filterIsInstance<AiProviderEvent.Progress>().map { it.message },
        )
        assertTrue(completedEvents.last() is AiProviderEvent.MessageDone)

        val failedTransport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """{"type":"response.web_search_call.searching","item_id":"ws-failed"}""",
                    """{"type":"error","error":{"type":"invalid_request_error","code":"invalid_request","message":"search failed"}}""",
                ),
            ),
        )

        val failedEvents = provider(failedTransport)
            .streamEvents(ChatRequest(userText = "Search"))
            .toList()

        assertEquals(
            listOf("Searching the web…", "Thinking…"),
            failedEvents.filterIsInstance<AiProviderEvent.Progress>().map { it.message },
        )
        assertTrue(failedEvents.last() is AiProviderEvent.Failed)
    }

    @Test
    fun emitsTextDeltaBeforeTerminalSseIsConsumed() = runTest {
        val deltaObserved = CompletableDeferred<Unit>()
        val terminalConsumed = AtomicBoolean(false)
        val transport = object : ChatGptCodexHttpTransport {
            override suspend fun execute(
                requestId: String,
                request: ChatGptCodexHttpRequest,
                consumeData: suspend (String) -> Boolean,
            ): ChatGptCodexHttpResponse {
                consumeData("""{"type":"response.output_text.delta","delta":"Live"}""")
                withTimeout(5_000) { deltaObserved.await() }
                terminalConsumed.set(true)
                consumeData("""{"type":"response.completed"}""")
                return ChatGptCodexHttpResponse(statusCode = 200)
            }

            override fun cancel(requestId: String) = Unit
        }
        val provider = ChatGptCodexProvider(
            apiClient = ChatGptCodexApiClient(
                tokenProvider = { tokens() },
                refreshTokens = { error("Refresh was not expected.") },
                transport = transport,
            ),
            oauthConfigured = { true },
            toolRegistry = unusedToolRegistry(),
        )
        var deltaObservedBeforeTerminal = false

        provider.streamEvents(ChatRequest(userText = "Hello")).collect { event ->
            if (event is AiProviderEvent.TextDelta) {
                deltaObservedBeforeTerminal = !terminalConsumed.get()
                deltaObserved.complete(Unit)
            }
        }

        assertTrue(deltaObservedBeforeTerminal)
    }

    @Test
    fun mapsResponseFailedOntoProviderFailure() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """
                    {
                      "type":"response.failed",
                      "response":{"error":{"message":"model is unavailable"}}
                    }
                    """.trimIndent(),
                ),
            ),
        )
        val provider = provider(transport)

        val events = provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertTrue(events.first() is AiProviderEvent.Started)
        assertEquals(2, events.size)
        assertTrue((events.last() as AiProviderEvent.Failed).message.contains("model is unavailable"))
    }

    @Test
    fun refreshesAfterOneUnauthorizedResponseAndRetriesWithNewAccessToken() = runTest {
        val transport = FakeTransport(
            StubResponse(statusCode = 401, errorBody = """{"error":"expired"}"""),
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """{"type":"response.output_text.delta","delta":"ok"}""",
                    """{"type":"response.completed"}""",
                ),
            ),
        )
        var refreshCount = 0
        val client = ChatGptCodexApiClient(
            tokenProvider = { tokens(accessToken = "old-token") },
            refreshTokens = {
                refreshCount += 1
                tokens(accessToken = "new-token")
            },
            transport = transport,
            sessionId = "retry-session",
        )

        val deltas = client.streamResponses(
            ChatRequest(userText = "Hello"),
            ChatGptCodexApiClient.DEFAULT_MODEL_ID,
        ).toList()

        assertEquals(listOf("ok"), deltas)
        assertEquals(1, refreshCount)
        assertEquals(2, transport.requests.size)
        assertEquals("Bearer old-token", transport.requests[0].headers["Authorization"])
        assertEquals("Bearer new-token", transport.requests[1].headers["Authorization"])
    }

    @Test
    fun retriesUnauthorizedOnlyOnceAndTellsUserToSignInAgain() = runTest {
        val transport = FakeTransport(
            StubResponse(statusCode = 401, errorBody = """{"error":"expired"}"""),
            StubResponse(statusCode = 401, errorBody = """{"error":"still expired"}"""),
        )
        var refreshCount = 0
        val client = ChatGptCodexApiClient(
            tokenProvider = { tokens(accessToken = "old-token") },
            refreshTokens = {
                refreshCount += 1
                tokens(accessToken = "new-token")
            },
            transport = transport,
            sessionId = "retry-session",
        )
        val provider = ChatGptCodexProvider(
            apiClient = client,
            oauthConfigured = { true },
            toolRegistry = unusedToolRegistry(),
        )

        val events = provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertEquals(1, refreshCount)
        assertEquals(2, transport.requests.size)
        val failure = events.last() as AiProviderEvent.Failed
        assertTrue(failure.message.contains("401"))
        assertTrue(failure.message.contains("Sign in with ChatGPT again"))
        assertTrue(failure.message.contains("still expired"))
    }

    private fun provider(transport: FakeTransport): ChatGptCodexProvider =
        ChatGptCodexProvider(
            apiClient = client(transport),
            oauthConfigured = { true },
            toolRegistry = unusedToolRegistry(),
        )

    private fun client(
        transport: FakeTransport,
        initialTokens: CodexChatGptOAuthTokenBundle = tokens(),
        sessionId: String = "test-session",
    ): ChatGptCodexApiClient = ChatGptCodexApiClient(
        tokenProvider = { initialTokens },
        refreshTokens = { error("Refresh was not expected.") },
        transport = transport,
        sessionId = sessionId,
    )

    private data class StubResponse(
        val statusCode: Int,
        val errorBody: String = "",
        val sseData: List<String> = emptyList(),
    )

    private class FakeTransport(
        vararg responses: StubResponse,
    ) : ChatGptCodexHttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<ChatGptCodexHttpRequest>()
        val cancelledRequestIds = mutableListOf<String>()

        override suspend fun execute(
            requestId: String,
            request: ChatGptCodexHttpRequest,
            consumeData: suspend (String) -> Boolean,
        ): ChatGptCodexHttpResponse {
            requests += request
            val response = responses.removeFirst()
            if (response.statusCode in 200..299) {
                for (data in response.sseData) {
                    if (!consumeData(data)) break
                }
            }
            return ChatGptCodexHttpResponse(response.statusCode, response.errorBody)
        }

        override fun cancel(requestId: String) {
            cancelledRequestIds += requestId
        }
    }

    private companion object {
        fun tokens(
            accessToken: String = "access-token",
            accountId: String = "account-id",
        ) = CodexChatGptOAuthTokenBundle(
            accessToken = accessToken,
            idToken = "id-token",
            refreshToken = "refresh-token",
            accountId = accountId,
            planType = "plus",
            email = "person@example.com",
        )
    }
}
