package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiProviderSseTest {
    @Test
    fun parsesChatCompletionTextDelta() {
        val event = OpenAiChatSseParser.parseLine(
            """data: {"choices":[{"delta":{"content":"Bonjour"}}]}""",
        )

        assertEquals(OpenAiChatSseEvent.Delta(content = "Bonjour"), event)
    }

    @Test
    fun recognizesDoneAndIgnoresRoleOnlyChunks() {
        assertSame(
            OpenAiChatSseEvent.Done,
            OpenAiChatSseParser.parseLine("data: [DONE]"),
        )
        assertSame(
            OpenAiChatSseEvent.Ignored,
            OpenAiChatSseParser.parseLine(
                """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
            ),
        )
    }

    @Test
    fun preservesReasoningForToolReplayWithoutTreatingItAsVisibleContent() {
        val event = OpenAiChatSseParser.parseLine(
            """data: {"choices":[{"delta":{"reasoning_content":"Thinking"}}]}""",
        ) as OpenAiChatSseEvent.Delta
        assertEquals("Thinking", event.reasoningContent)
        assertEquals(null, event.content)
    }

    @Test
    fun parsesProviderErrorWithoutThrowingAwayMessage() {
        val event = OpenAiChatSseParser.parseLine(
            """data: {"error":{"message":"rate limited"}}""",
        )

        assertEquals(OpenAiChatSseEvent.Error("rate limited"), event)
    }

    @Test
    fun reassemblesInterleavedToolCallAndContentFragments() {
        val events = listOf(
            OpenAiChatSseParser.parseLine(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-real","function":{"name":"take_","arguments":""}},{"index":1,"function":{"name":"take_","arguments":"{"}}]}}]}""",
            ),
            OpenAiChatSseParser.parseLine(
                """data: {"choices":[{"delta":{"content":"Let me ","tool_calls":[{"index":0,"function":{"name":"photo","arguments":"{"}},{"index":1,"function":{"name":"photo","arguments":"}"}}]}}]}""",
            ),
            OpenAiChatSseParser.parseLine(
                """data: {"choices":[{"delta":{"content":"look.","tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}""",
            ),
        ).map { event -> event as OpenAiChatSseEvent.Delta }
        val accumulator = OpenAiToolCallAccumulator()

        events.forEach { event -> accumulator.append(event.toolCalls) }

        assertEquals(listOf("Let me ", "look."), events.mapNotNull { event -> event.content })
        assertEquals("tool_calls", events.last().finishReason)
        assertEquals(
            listOf(
                AssistantToolCall("call-real", TAKE_PHOTO_TOOL_NAME, "{}"),
                AssistantToolCall("call_1", TAKE_PHOTO_TOOL_NAME, "{}"),
            ),
            accumulator.completeCalls(),
        )
        assertTrue(events[1].content != null && events[1].toolCalls.isNotEmpty())
    }

    @Test
    fun compatibleProviderDoesNotEmitTextReset() = runTest {
        val events = OpenAiCompatProvider(
            preset = ProviderCatalog.openRouter,
            apiClient = RecordingCompatClient(),
            apiKeyConfigured = { false },
            toolRegistry = unusedToolRegistry(),
            supportsVision = { false },
        ).streamEvents(ChatRequest(userText = "Hello")).toList()

        assertFalse(events.any { it is AiProviderEvent.TextReset })
    }

    @Test
    fun compatibleProviderStripsInlineThinkContentFromDeltasAndDoneMessage() = runTest {
        val provider = OpenAiCompatProvider(
            preset = ProviderCatalog.openRouter,
            apiClient = RecordingCompatClient(
                listOf("<th", "ink>secret", " reasoning</thi", "nk>\n\nAnswer"),
            ),
            apiKeyConfigured = { true },
            toolRegistry = unusedToolRegistry(),
            supportsVision = { false },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Hello")).toList()
        val deltas = events.filterIsInstance<AiProviderEvent.TextDelta>()
            .map { event -> event.delta }
        val done = events.filterIsInstance<AiProviderEvent.MessageDone>().single()

        assertEquals(listOf("\n\nAnswer"), deltas)
        assertEquals("Answer", done.message.content)
        assertFalse(deltas.any { delta -> delta.contains("secret") || delta.contains("think") })
    }

    @Test
    fun nonVisionProviderDropsAllPhotosAndNotesCurrentAttachment() = runTest {
        val client = RecordingCompatClient()
        val provider = OpenAiCompatProvider(
            preset = ProviderCatalog.deepSeek,
            apiClient = client,
            apiKeyConfigured = { true },
            toolRegistry = unusedToolRegistry(),
            supportsVision = { false },
        )

        provider.streamEvents(
            ChatRequest(
                userText = "What is this?",
                history = listOf(
                    ChatMessage(
                        role = "user",
                        content = "What was there?",
                        photos = listOf(PhotoAttachment("image/jpeg", "history-bytes")),
                    ),
                ),
                photos = listOf(PhotoAttachment("image/jpeg", "current-bytes")),
            ),
        ).toList()

        val captured = client.request
        val serialized = captured.toChatCompletionMessages().toString()
        assertTrue(captured.userText.endsWith(PHOTO_UNSUPPORTED_NOTE))
        assertTrue(captured.photos.isEmpty())
        assertTrue(captured.history.all { it.photos.isEmpty() })
        assertFalse(serialized.contains("image_url"))
        assertFalse(serialized.contains("history-bytes"))
        assertFalse(serialized.contains("current-bytes"))
    }

    @Test
    fun openRouterEffortIsIncludedOnlyWhenConfigured() {
        val withEffort = OpenAiCompatApiClient(
            preset = ProviderCatalog.openRouter,
            apiKeyProvider = { "key" },
            effortProvider = { "high" },
        ).requestBody(ChatRequest(userText = "Hello"), "free-text/model")
        val withoutEffort = OpenAiCompatApiClient(
            preset = ProviderCatalog.openRouter,
            apiKeyProvider = { "key" },
            effortProvider = { "" },
        ).requestBody(ChatRequest(userText = "Hello"), "free-text/model")

        assertEquals("free-text/model", withEffort.getString("model"))
        assertEquals("high", withEffort.getJSONObject("reasoning").getString("effort"))
        assertFalse(withoutEffort.has("reasoning"))
    }

    @Test
    fun baseUrlOverrideIsUsedForChatCompletionsEndpoint() {
        val client = OpenAiCompatApiClient(
            preset = ProviderCatalog.custom,
            apiKeyProvider = { "key" },
            baseUrlProvider = { " https://example.test/openai/v1/ " },
        )

        assertEquals(
            "https://example.test/openai/v1/chat/completions",
            client.endpointUrl(),
        )
    }

    private class RecordingCompatClient(
        private val deltas: List<String> = listOf("done"),
    ) : OpenAiCompatChatClient {
        lateinit var request: ChatRequest

        override fun streamChat(request: OpenAiCompatChatRequest) = flowOf(
            *deltas.map { delta -> OpenAiChatSseEvent.Delta(content = delta) }.toTypedArray(),
        ).also {
            this.request = request.request
        }

        override fun cancel(requestId: String) = Unit
    }
}
