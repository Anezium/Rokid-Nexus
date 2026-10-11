package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

class ChatGptCodexToolLoopTest {
    @Test
    fun `text only response uses one request and never executes a tool`() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """{"type":"response.output_item.done","item":{"type":"web_search_call"}}""",
                    """{"type":"response.output_text.delta","delta":"Paris is sunny."}""",
                    """{"type":"response.output_item.done","item":{"type":"message"}}""",
                    """{"type":"response.completed","response":{"output":[]}}""",
                ),
            ),
        )
        var executorCalls = 0
        val provider = provider(transport) {
            executorCalls += 1
            AssistantToolResult.Error(TOOL_ERROR_CAPTURE_FAILED)
        }

        val events = provider.streamEvents(ChatRequest(userText = "Paris weather")).toList()

        assertEquals(0, executorCalls)
        assertEquals(1, transport.requests.size)
        assertEquals(
            "Paris is sunny.",
            (events.last() as AiProviderEvent.MessageDone).message.content,
        )
    }

    @Test
    fun `done output item drives photo loop and response completed output is ignored`() = runTest {
        val exactCallId = "call_verbatim-ABC_123"
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """{"type":"response.function_call_arguments.delta","delta":"{\"ignored\":true}"}""",
                    functionCallDone(exactCallId),
                    """{"type":"response.completed","response":{"output":[]}}""",
                ),
            ),
            completedText("The label says 42."),
        )
        val executed = mutableListOf<AssistantToolCall>()
        val provider = provider(transport) { call ->
            executed += call
            AssistantToolResult.Image("image/jpeg", "AQID")
        }

        val events = provider.streamEvents(ChatRequest(userText = "Read this")).toList()

        assertEquals(1, executed.size)
        assertEquals(exactCallId, executed.single().callId)
        assertEquals(TAKE_PHOTO_TOOL_NAME, executed.single().name)
        assertEquals("{}", executed.single().argumentsJson)
        assertEquals(2, transport.requests.size)

        val firstBody = JSONObject(transport.requests[0].body)
        assertEquals(2, firstBody.getJSONArray("tools").length())

        // The continuation keeps the tools while the turn has rounds left, so a result can
        // lead to a dependent call; the phase still refuses a second photo.
        val secondBody = JSONObject(transport.requests[1].body)
        val secondTools = secondBody.getJSONArray("tools")
        assertEquals(2, secondTools.length())
        assertEquals("web_search", secondTools.getJSONObject(0).getString("type"))

        val replay = secondBody.getJSONArray("input")
        assertEquals(3, replay.length())
        assertEquals("message", replay.getJSONObject(0).getString("type"))
        val replayedCall = replay.getJSONObject(1)
        assertEquals("function_call", replayedCall.getString("type"))
        assertEquals(exactCallId, replayedCall.getString("call_id"))
        assertEquals(TAKE_PHOTO_TOOL_NAME, replayedCall.getString("name"))
        assertEquals("{}", replayedCall.getString("arguments"))
        assertFalse(replayedCall.has("id"))

        val output = replay.getJSONObject(2)
        assertEquals("function_call_output", output.getString("type"))
        assertEquals(exactCallId, output.getString("call_id"))
        val imageOutput = output.getJSONArray("output")
        assertEquals(1, imageOutput.length())
        assertEquals("input_image", imageOutput.getJSONObject(0).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AQID",
            imageOutput.getJSONObject(0).getString("image_url"),
        )
        assertEquals("high", imageOutput.getJSONObject(0).getString("detail"))
        assertEquals(
            "The label says 42.",
            (events.last() as AiProviderEvent.MessageDone).message.content,
        )
    }

    @Test
    fun `all stable error codes serialize as compact tool output strings`() {
        val call = AssistantToolCall("call-1", TAKE_PHOTO_TOOL_NAME, "{}")

        listOf(
            TOOL_ERROR_NOT_AUTHORIZED,
            TOOL_ERROR_GLASSES_DISCONNECTED,
            TOOL_ERROR_CAMERA_BUSY,
            TOOL_ERROR_ALREADY_USED,
            TOOL_ERROR_CANCELLED,
            TOOL_ERROR_CAPTURE_FAILED,
            TOOL_ERROR_INVALID_CALL,
            "notes_failed",
        ).forEach { code ->
            val output = functionCallOutput(call, AssistantToolResult.Error(code))
            assertEquals("call-1", output.getString("call_id"))
            assertEquals(
                """{"ok":false,"code":"$code"}""",
                output.getString("output"),
            )
        }
    }

    @Test
    fun `json tool result replays as a function output string`() {
        val output = functionCallOutput(
            AssistantToolCall("call-json", "lookup_note", "{}"),
            AssistantToolResult.Json("""{"ok":true,"title":"Groceries"}"""),
        )

        assertEquals("call-json", output.getString("call_id"))
        assertEquals(
            """{"ok":true,"title":"Groceries"}""",
            output.getString("output"),
        )
    }

    @Test
    fun `test only second tool is declared dispatched and replayed`() = runTest {
        val lookupTool = TestAssistantTool(
            name = "lookup_note",
            description = "Look up a saved note.",
            executor = { _, _ ->
                AssistantToolResult.Json("""{"ok":true,"title":"Groceries"}""")
            },
        )
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    functionCallDone("call-note", name = lookupTool.name),
                    """{"type":"response.completed"}""",
                ),
            ),
            completedText("Milk and bread."),
        )
        val provider = provider(
            transport = transport,
            additionalTools = listOf(lookupTool),
        ) {
            error("Photo tool must not execute")
        }

        provider.streamEvents(ChatRequest(userText = "Read my groceries note")).toList()

        val declarations = JSONObject(transport.requests[0].body).getJSONArray("tools")
        assertEquals(3, declarations.length())
        assertEquals(TAKE_PHOTO_TOOL_NAME, declarations.getJSONObject(1).getString("name"))
        assertEquals("lookup_note", declarations.getJSONObject(2).getString("name"))
        val replay = JSONObject(transport.requests[1].body).getJSONArray("input")
        assertEquals(
            """{"ok":true,"title":"Groceries"}""",
            replay.getJSONObject(2).getString("output"),
        )
    }

    @Test
    fun `unknown tools and malformed arguments return invalid call without executor access`() =
        runTest {
            val invalidCalls = listOf(
                Triple("unknown_tool", "{}", "call-unknown"),
                Triple(TAKE_PHOTO_TOOL_NAME, "not-json", "call-malformed"),
                Triple(TAKE_PHOTO_TOOL_NAME, """{"unexpected":true}""", "call-properties"),
            )

            invalidCalls.forEach { (name, arguments, callId) ->
                val transport = FakeTransport(
                    StubResponse(
                        statusCode = 200,
                        sseData = listOf(
                            functionCallDone(callId, name, arguments),
                            """{"type":"response.completed","response":{"output":[]}}""",
                        ),
                    ),
                    completedText("I could not use that tool."),
                )
                var executorCalls = 0
                provider(transport) {
                    executorCalls += 1
                    AssistantToolResult.Error(TOOL_ERROR_CAPTURE_FAILED)
                }.streamEvents(ChatRequest(userText = "Hello")).toList()

                assertEquals(name, 0, executorCalls)
                val replay = JSONObject(transport.requests[1].body).getJSONArray("input")
                assertEquals(
                    """{"ok":false,"code":"invalid_call"}""",
                    replay.getJSONObject(2).getString("output"),
                )
            }
        }

    @Test
    fun `a second take photo call returns already used without a second capture`() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    functionCallDone("call-first"),
                    functionCallDone("call-second"),
                    """{"type":"response.completed","response":{"output":[]}}""",
                ),
            ),
            completedText("I used the single available photo."),
        )
        var executorCalls = 0
        val provider = provider(transport) {
            executorCalls += 1
            AssistantToolResult.Image("image/jpeg", "AQID")
        }

        provider.streamEvents(ChatRequest(userText = "Look twice")).toList()

        assertEquals(1, executorCalls)
        val replay = JSONObject(transport.requests[1].body).getJSONArray("input")
        assertEquals(5, replay.length())
        assertEquals("call-first", replay.getJSONObject(2).getString("call_id"))
        assertEquals("call-second", replay.getJSONObject(4).getString("call_id"))
        assertEquals(
            """{"ok":false,"code":"already_used"}""",
            replay.getJSONObject(4).getString("output"),
        )
    }

    @Test
    fun `request two 401 refresh retries only its HTTP call and reuses captured image`() = runTest {
        val transport = FakeTransport(
            toolResponse("call-photo"),
            StubResponse(statusCode = 401, errorBody = """{"error":"expired"}"""),
            completedText("Done after refresh."),
        )
        var executorCalls = 0
        var refreshCalls = 0
        val provider = provider(
            transport = transport,
            refreshTokens = {
                refreshCalls += 1
                tokens(accessToken = "new-token")
            },
        ) {
            executorCalls += 1
            AssistantToolResult.Image("image/jpeg", "AQID")
        }

        provider.streamEvents(ChatRequest(userText = "What is this?")).toList()

        assertEquals(1, executorCalls)
        assertEquals(1, refreshCalls)
        assertEquals(3, transport.requests.size)
        assertEquals("Bearer old-token", transport.requests[1].headers["Authorization"])
        assertEquals("Bearer new-token", transport.requests[2].headers["Authorization"])
        assertEquals(
            JSONObject(transport.requests[1].body).getJSONArray("input").toString(),
            JSONObject(transport.requests[2].body).getJSONArray("input").toString(),
        )
    }

    @Test
    fun `transient stream failure retries once and permanent failure does not`() = runTest {
        val transient = FakeTransport(
            streamError("server_error", "server_is_overloaded"),
            completedText("Recovered."),
        )

        val recoveredEvents = provider(transient) {
            error("No tool expected")
        }.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertEquals(2, transient.requests.size)
        assertEquals(
            "Recovered.",
            (recoveredEvents.last() as AiProviderEvent.MessageDone).message.content,
        )

        val permanent = FakeTransport(
            streamError("invalid_request_error", "invalid_request"),
        )
        val permanentEvents = provider(permanent) {
            error("No tool expected")
        }.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertEquals(1, permanent.requests.size)
        assertTrue(permanentEvents.last() is AiProviderEvent.Failed)

        val exhausted = FakeTransport(
            streamError("service_unavailable_error", "server_is_overloaded"),
            streamError("service_unavailable_error", "server_is_overloaded"),
        )
        val exhaustedEvents = provider(exhausted) {
            error("No tool expected")
        }.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertEquals(2, exhausted.requests.size)
        assertTrue(exhaustedEvents.last() is AiProviderEvent.Failed)
    }

    @Test
    fun `transient retry resets streamed text and final answer is not duplicated`() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """{"type":"response.output_text.delta","delta":"Recovered."}""",
                    streamErrorData("server_error", "server_is_overloaded"),
                ),
            ),
            completedText("Recovered."),
        )

        val events = provider(transport) {
            error("No tool expected")
        }.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertEquals(1, events.filterIsInstance<AiProviderEvent.TextReset>().size)
        assertEquals(
            listOf("Recovered.", "Recovered."),
            events.filterIsInstance<AiProviderEvent.TextDelta>().map { it.delta },
        )
        assertEquals(
            "Recovered.",
            (events.last() as AiProviderEvent.MessageDone).message.content,
        )
    }

    @Test
    fun `unauthorized retry resets streamed text without executing a tool`() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 401,
                errorBody = """{"error":"expired"}""",
                sseData = listOf(
                    """{"type":"response.output_text.delta","delta":"Authorized."}""",
                ),
            ),
            completedText("Authorized."),
        )
        var executorCalls = 0
        var refreshCalls = 0

        val events = provider(
            transport = transport,
            refreshTokens = {
                refreshCalls += 1
                tokens(accessToken = "new-token")
            },
        ) {
            executorCalls += 1
            AssistantToolResult.Error(TOOL_ERROR_CAPTURE_FAILED)
        }.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertEquals(0, executorCalls)
        assertEquals(1, refreshCalls)
        assertEquals(1, events.filterIsInstance<AiProviderEvent.TextReset>().size)
        assertEquals(
            "Authorized.",
            (events.last() as AiProviderEvent.MessageDone).message.content,
        )
    }

    @Test
    fun `request two transient retry reuses executor output`() = runTest {
        val transport = FakeTransport(
            toolResponse("call-photo"),
            streamError("service_unavailable_error", "server_is_overloaded"),
            completedText("Recovered with the same photo."),
        )
        var executorCalls = 0
        val provider = provider(transport) {
            executorCalls += 1
            AssistantToolResult.Image("image/jpeg", "AQID")
        }

        provider.streamEvents(ChatRequest(userText = "Read this")).toList()

        assertEquals(1, executorCalls)
        assertEquals(3, transport.requests.size)
        assertEquals(
            JSONObject(transport.requests[1].body).getJSONArray("input").toString(),
            JSONObject(transport.requests[2].body).getJSONArray("input").toString(),
        )
    }

    @Test
    fun `cancellation before function completion prevents executor`() = runTest {
        val transport = BlockingTransport()
        var executorCalls = 0
        val job = launch {
            provider(transport) {
                executorCalls += 1
                AssistantToolResult.Image("image/jpeg", "AQID")
            }.streamEvents(ChatRequest(userText = "Hello")).toList()
        }
        transport.started.await()

        job.cancelAndJoin()

        assertEquals(0, executorCalls)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `cancellation during executor prevents continuation request`() = runTest {
        val transport = FakeTransport(toolResponse("call-photo"))
        val executorStarted = CompletableDeferred<Unit>()
        val job = launch {
            provider(transport) {
                executorStarted.complete(Unit)
                awaitCancellation()
            }.streamEvents(ChatRequest(userText = "Look")).toList()
        }
        executorStarted.await()

        job.cancelAndJoin()

        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `tool turn resets streamed preamble before continuation text`() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 200,
                sseData = listOf(
                    """{"type":"response.output_text.delta","delta":"Let me look…"}""",
                    functionCallDone("call-photo"),
                    """{"type":"response.completed","response":{"output":[]}}""",
                ),
            ),
            completedText("Final visual answer."),
        )

        val events = mutableListOf<AiProviderEvent>()
        val provider = provider(transport) {
            assertEquals(1, events.filterIsInstance<AiProviderEvent.TextReset>().size)
            AssistantToolResult.Image("image/jpeg", "AQID")
        }

        provider.streamEvents(ChatRequest(userText = "Look")).collect(events::add)

        val streamedEvents = events.filter {
            it is AiProviderEvent.TextDelta || it is AiProviderEvent.TextReset
        }
        assertEquals(3, streamedEvents.size)
        assertTrue((streamedEvents[0] as AiProviderEvent.TextDelta).delta.startsWith("Let me look"))
        assertTrue(streamedEvents[1] is AiProviderEvent.TextReset)
        assertEquals(
            "Final visual answer.",
            (streamedEvents[2] as AiProviderEvent.TextDelta).delta,
        )
        assertEquals(1, events.filterIsInstance<AiProviderEvent.TextReset>().size)
        val done = events.last() as AiProviderEvent.MessageDone
        assertEquals("Final visual answer.", done.message.content)
        assertFalse(done.message.content.contains("Let me look"))
    }

    @Test
    fun `history replay passes attached image pixels and retains photo marker`() {
        val input = ChatRequest(
            userText = "Current",
            history = listOf(
                ChatMessage(
                    role = "user",
                    content = "Earlier",
                    photos = listOf(PhotoAttachment("image/jpeg", "OLD_PIXELS")),
                ),
            ),
            photos = listOf(PhotoAttachment("image/jpeg", "CURRENT_PIXELS")),
        ).toCodexResponsesInput()

        val historyContent = input.getJSONObject(0).getJSONArray("content")
        assertEquals(2, historyContent.length())
        assertEquals("Earlier\n[photo]", historyContent.getJSONObject(0).getString("text"))
        assertEquals("input_image", historyContent.getJSONObject(1).getString("type"))
        assertTrue(
            historyContent.getJSONObject(1).getString("image_url").contains("OLD_PIXELS"),
        )

        val currentContent = input.getJSONObject(1).getJSONArray("content")
        assertEquals(2, currentContent.length())
        assertTrue(currentContent.getJSONObject(1).getString("image_url").contains("CURRENT_PIXELS"))
    }

    @Test
    fun `history photo marker without pixels remains text only`() {
        val input = ChatRequest(
            userText = "Current",
            history = listOf(
                ChatMessage(
                    role = "user",
                    content = "Earlier",
                    photos = listOf(
                        PhotoAttachment(
                            OMITTED_HISTORY_PHOTO_MIME_TYPE,
                            OMITTED_HISTORY_PHOTO_BASE64,
                        ),
                    ),
                ),
            ),
        ).toCodexResponsesInput()

        val historyContent = input.getJSONObject(0).getJSONArray("content")
        assertEquals(1, historyContent.length())
        assertEquals("Earlier\n[photo]", historyContent.getJSONObject(0).getString("text"))
        assertFalse(input.getJSONObject(0).toString().contains(OMITTED_HISTORY_PHOTO_BASE64))
    }

    @Test
    fun `a refused tool declaration retries the answer without tools`() = runTest {
        val transport = FakeTransport(
            StubResponse(
                statusCode = 400,
                errorBody = """{"error":{"code":"invalid_function_parameters","param":"tools[1].parameters"}}""",
            ),
            completedText("The guest Wi-Fi password is Tamarin-82."),
        )
        val provider = provider(transport) { error("No tool may run.") }

        val events = provider.streamEvents(ChatRequest(userText = "Wi-Fi password?")).toList()

        assertEquals(2, transport.requests.size)
        assertTrue(JSONObject(transport.requests[0].body).getJSONArray("tools").length() > 1)
        val retried = JSONObject(transport.requests[1].body).optJSONArray("tools")
        assertTrue(retried == null || (0 until retried.length()).none {
            retried.getJSONObject(it).optString("type") == "function"
        })
        assertEquals(
            "The guest Wi-Fi password is Tamarin-82.",
            (events.last() as AiProviderEvent.MessageDone).message.content,
        )
    }

    @Test
    fun `a server failure is not retried without tools`() = runTest {
        val transport = FakeTransport(StubResponse(statusCode = 500, errorBody = "boom"))
        val provider = provider(transport) { error("No tool may run.") }

        val events = provider.streamEvents(ChatRequest(userText = "Wi-Fi password?")).toList()

        assertEquals(1, transport.requests.size)
        assertTrue(events.last() is AiProviderEvent.Failed)
    }

    @Test
    fun `a schema strict mode would refuse is declared loose instead`() = runTest {
        val loose = TestAssistantTool(
            name = "loose_tool",
            parametersSchema = AssistantToolJsonSchema(
                """{"type":"object","properties":{"a":{"type":"string"}},"additionalProperties":false}""",
            ),
        )
        val transport = FakeTransport(completedText("Done."))
        val provider = provider(transport, additionalTools = listOf(loose)) { error("No tool may run.") }

        provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        val tools = JSONObject(transport.requests.single().body).getJSONArray("tools")
        val declarations = (0 until tools.length()).map { tools.getJSONObject(it) }
            .filter { it.optString("type") == "function" }.associateBy { it.getString("name") }
        assertFalse(declarations.getValue("loose_tool").getBoolean("strict"))
        assertTrue(declarations.getValue(TAKE_PHOTO_TOOL_NAME).getBoolean("strict"))
    }

    private fun provider(
        transport: ChatGptCodexHttpTransport,
        refreshTokens: suspend () -> CodexChatGptOAuthTokenBundle = {
            error("Refresh was not expected.")
        },
        additionalTools: List<AssistantToolDefinition> = emptyList(),
        executor: suspend (AssistantToolCall) -> AssistantToolResult,
    ): ChatGptCodexProvider =
        ChatGptCodexProvider(
            apiClient = ChatGptCodexApiClient(
                tokenProvider = { tokens() },
                refreshTokens = refreshTokens,
                transport = transport,
                sessionId = "tool-loop-test",
            ),
            oauthConfigured = { true },
            toolRegistry = testToolRegistry(executor, *additionalTools.toTypedArray()),
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

        override suspend fun execute(
            requestId: String,
            request: ChatGptCodexHttpRequest,
            consumeData: suspend (String) -> Boolean,
        ): ChatGptCodexHttpResponse {
            requests += request
            val response = responses.removeFirst()
            if (response.sseData.isNotEmpty()) {
                for (data in response.sseData) {
                    if (!consumeData(data)) break
                }
            }
            return ChatGptCodexHttpResponse(response.statusCode, response.errorBody)
        }

        override fun cancel(requestId: String) = Unit
    }

    private class BlockingTransport : ChatGptCodexHttpTransport {
        val started = CompletableDeferred<Unit>()
        val requests = mutableListOf<ChatGptCodexHttpRequest>()

        override suspend fun execute(
            requestId: String,
            request: ChatGptCodexHttpRequest,
            consumeData: suspend (String) -> Boolean,
        ): ChatGptCodexHttpResponse {
            requests += request
            started.complete(Unit)
            awaitCancellation()
        }

        override fun cancel(requestId: String) = Unit
    }

    private companion object {
        fun completedText(text: String) = StubResponse(
            statusCode = 200,
            sseData = listOf(
                JSONObject()
                    .put("type", "response.output_text.delta")
                    .put("delta", text)
                    .toString(),
                """{"type":"response.output_item.done","item":{"type":"message"}}""",
                """{"type":"response.completed","response":{"output":[]}}""",
            ),
        )

        fun toolResponse(callId: String) = StubResponse(
            statusCode = 200,
            sseData = listOf(
                functionCallDone(callId),
                """{"type":"response.completed","response":{"output":[]}}""",
            ),
        )

        fun functionCallDone(
            callId: String,
            name: String = TAKE_PHOTO_TOOL_NAME,
            arguments: String = "{}",
        ): String =
            JSONObject()
                .put("type", "response.output_item.done")
                .put(
                    "item",
                    JSONObject()
                        .put("id", "fc-ignored")
                        .put("type", "function_call")
                        .put("status", "completed")
                        .put("arguments", arguments)
                        .put("call_id", callId)
                        .put("name", name),
                )
                .toString()

        fun streamError(type: String, code: String) = StubResponse(
            statusCode = 200,
            sseData = listOf(streamErrorData(type, code)),
        )

        fun streamErrorData(type: String, code: String): String =
            JSONObject()
                .put("type", "error")
                .put(
                    "error",
                    JSONObject()
                        .put("type", type)
                        .put("code", code)
                        .put("message", "stream failed"),
                )
                .toString()

        fun tokens(
            accessToken: String = "old-token",
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
