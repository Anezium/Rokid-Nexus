package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.shared.plugin.PluginCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatToolLoopTest {
    @Test
    fun `photo tool call replays output and attached image without redeclaring tools`() = runTest {
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content = "<think>hidden first pass</think>Looking",
                    ),
                    takePhotoCallDelta(callId = "call-photo"),
                ),
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content = "<think>hidden second pass</think>The label says 42.",
                    ),
                ),
            ),
        )
        val executed = mutableListOf<AssistantToolCall>()
        val provider = provider(
            client = client,
            toolExecutor = { call ->
                executed += call
                AssistantToolResult.Image("image/jpeg", "AQID")
            },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Read this")).toList()

        assertEquals(1, executed.size)
        assertEquals(TAKE_PHOTO_TOOL_NAME, executed.single().name)
        assertTrue(events.any { event -> event is AiProviderEvent.TextReset })
        assertEquals(
            listOf("Looking", "The label says 42."),
            events.filterIsInstance<AiProviderEvent.TextDelta>().map { event -> event.delta },
        )
        assertFalse(
            events.filterIsInstance<AiProviderEvent.TextDelta>()
                .any { event -> event.delta.contains("hidden") || event.delta.contains("think") },
        )
        assertEquals(
            "The label says 42.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )

        assertEquals(2, client.requests.size)
        val firstBody = bodyFor(client.requests[0])
        val tools = firstBody.getJSONArray("tools")
        assertEquals(1, tools.length())
        val declaration = tools.getJSONObject(0)
        assertEquals("function", declaration.getString("type"))
        val function = declaration.getJSONObject("function")
        assertEquals(TAKE_PHOTO_TOOL_NAME, function.getString("name"))
        assertEquals(
            TAKE_PHOTO_TOOL_DESCRIPTION,
            function.getString("description"),
        )
        val parameters = function.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
        assertFalse(parameters.getBoolean("additionalProperties"))
        assertFalse(firstBody.has("tool_choice"))

        val secondBody = bodyFor(client.requests[1])
        assertFalse(secondBody.has("tools"))
        assertFalse(secondBody.has("tool_choice"))
        val messages = secondBody.getJSONArray("messages")
        assertEquals(4, messages.length())

        val assistant = messages.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        assertEquals("Looking", assistant.getString("content"))
        val echoedCall = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call-photo", echoedCall.getString("id"))
        assertEquals("function", echoedCall.getString("type"))
        assertEquals(TAKE_PHOTO_TOOL_NAME, echoedCall.getJSONObject("function").getString("name"))
        assertEquals("{}", echoedCall.getJSONObject("function").getString("arguments"))

        val tool = messages.getJSONObject(2)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call-photo", tool.getString("tool_call_id"))
        assertToolContent(
            tool.getString("content"),
            "status" to "captured",
            "note" to "The photo is attached to the next user message.",
        )

        val photoMessage = messages.getJSONObject(3)
        assertEquals("user", photoMessage.getString("role"))
        val photoParts = photoMessage.getJSONArray("content")
        assertEquals(2, photoParts.length())
        assertEquals("text", photoParts.getJSONObject(0).getString("type"))
        assertEquals(
            "Photo just taken through the glasses camera for the current question. " +
                "The image is attached now; do not request another photo.",
            photoParts.getJSONObject(0).getString("text"),
        )
        assertEquals("image_url", photoParts.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,AQID",
            photoParts.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `Hermes control line split across token and json never leaks onto the hud`() = runTest {
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(content = "<think>Need vision</think>  [[NEXUS_"),
                    OpenAiChatSseEvent.Delta(content = "TOOL]]{\"na"),
                    OpenAiChatSseEvent.Delta(content = "me\":\"take_ph"),
                    OpenAiChatSseEvent.Delta(content = "oto\",\"arguments\":"),
                    OpenAiChatSseEvent.Delta(content = "{}}\n"),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "I can see a green door.")),
            ),
        )
        var executionCount = 0
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = {
                executionCount += 1
                AssistantToolResult.Image("image/jpeg", "AQID")
            },
        )

        val events = provider.streamEvents(ChatRequest(userText = "What do you see?")).toList()

        assertEquals(1, executionCount)
        assertTrue(events.any { event -> event is AiProviderEvent.TextReset })
        assertEquals(
            listOf("I can see a green door."),
            events.filterIsInstance<AiProviderEvent.TextDelta>().map { event -> event.delta },
        )
        assertFalse(events.any { event -> event.toString().contains(COMPAT_TEXT_TOOL_REQUEST_TOKEN) })
        assertEquals(2, client.requests.size)
        assertTrue(client.requests[0].toolDefinitions.isEmpty())
        val replayMessages = client.requests[1].messages
        // The results ride back as plain user text: no structured tool transcript.
        assertEquals(3, replayMessages.length())
        assertEquals("user", replayMessages.getJSONObject(1).getString("role"))
        assertTrue(
            replayMessages.getJSONObject(1).getString("content").contains("take_photo"),
        )
        assertEquals("user", replayMessages.getJSONObject(2).getString("role"))
        val photoParts = replayMessages.getJSONObject(2).getJSONArray("content")
        assertEquals(
            "data:image/jpeg;base64,AQID",
            photoParts.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `generic OpenAI compatible provider does not interpret the Hermes control line`() = runTest {
        val controlLine =
            "$COMPAT_TEXT_TOOL_REQUEST_TOKEN{\"name\":\"take_photo\",\"arguments\":{}}"
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(content = controlLine),
                ),
            ),
        )
        var executionCount = 0
        val provider = provider(
            client = client,
            toolExecutor = {
                executionCount += 1
                AssistantToolResult.Image("image/jpeg", "AQID")
            },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Say the token")).toList()

        assertEquals(0, executionCount)
        assertEquals(1, client.requests.size)
        assertEquals(
            listOf(controlLine),
            events.filterIsInstance<AiProviderEvent.TextDelta>().map(AiProviderEvent.TextDelta::delta),
        )
    }

    @Test
    fun `Hermes normal answers beginning with brackets are streamed intact`() = runTest {
        listOf("[ordinary answer", "[[ordinary answer").forEach { answer ->
            val client = RecordingCompatClient(
                listOf(
                    StubResponse.Events(
                        answer.map { character ->
                            OpenAiChatSseEvent.Delta(content = character.toString())
                        },
                    ),
                ),
            )
            val provider = provider(
                client = client,
                preset = ProviderCatalog.hermes,
                toolExecutor = { error("Tool must not execute") },
            )

            val events = provider.streamEvents(ChatRequest(userText = "Repeat this")).toList()

            assertEquals(
                answer,
                events.filterIsInstance<AiProviderEvent.TextDelta>()
                    .joinToString("") { event -> event.delta },
            )
            assertEquals(
                answer,
                events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
            )
        }
    }

    @Test
    fun `Hermes single object executes and replays its json result`() = runTest {
        val executed = mutableListOf<AssistantToolCall>()
        val takeNote = TestAssistantTool(
            name = TAKE_NOTE_TOOL_NAME,
            executor = { call, _ ->
                executed += call
                AssistantToolResult.Json("""{"ok":true,"id":"n_12345678"}""")
            },
        )
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content =
                            "$COMPAT_TEXT_TOOL_REQUEST_TOKEN" +
                                "{\"name\":\"take_note\",\"arguments\":{}}",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Saved.")),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(takeNote),
        )

        val events = provider.streamEvents(ChatRequest(userText = "Save a note")).toList()

        assertEquals(listOf(TAKE_NOTE_TOOL_NAME), executed.map(AssistantToolCall::name))
        assertEquals(2, client.requests.size)
        assertTrue(client.requests.all { request -> request.toolDefinitions.isEmpty() })
        val replayMessages = client.requests[1].messages
        assertEquals(2, replayMessages.length())
        val results = replayMessages.getJSONObject(1)
        assertEquals("user", results.getString("role"))
        assertTrue(
            results.getString("content")
                .contains("$TAKE_NOTE_TOOL_NAME: {\"ok\":true,\"id\":\"n_12345678\"}"),
        )
        assertEquals(
            "Saved.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `Hermes array executes every call in order in one round`() = runTest {
        val executedNames = mutableListOf<String>()
        val tools = listOf(LIST_NOTES_TOOL_NAME, SET_TIMER_TOOL_NAME).map { name ->
            TestAssistantTool(
                name = name,
                executor = { call, _ ->
                    executedNames += call.name
                    AssistantToolResult.Json("""{"ok":true,"tool":"${call.name}"}""")
                },
            )
        }
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content =
                            "$COMPAT_TEXT_TOOL_REQUEST_TOKEN" +
                                "[{\"name\":\"list_notes\",\"arguments\":{}}," +
                                "{\"name\":\"set_timer\",\"arguments\":{}}]",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Both done.")),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = tools,
        )

        provider.streamEvents(ChatRequest(userText = "Do both")).toList()

        assertEquals(listOf(LIST_NOTES_TOOL_NAME, SET_TIMER_TOOL_NAME), executedNames)
        assertEquals(2, client.requests.size)
        val replayMessages = client.requests[1].messages
        assertEquals(2, replayMessages.length())
        val results = replayMessages.getJSONObject(1).getString("content")
        // Both results in one plain-text message, in the order the model asked for them.
        assertTrue(
            results.indexOf(LIST_NOTES_TOOL_NAME) < results.indexOf(SET_TIMER_TOOL_NAME),
        )
    }

    @Test
    fun `Hermes unknown tool is rejected as malformed without executing it`() = runTest {
        var executionCount = 0
        val renderTool = TestAssistantTool(
            name = RENDER_INK_PAGE_TOOL_NAME,
            executor = { _, _ ->
                executionCount += 1
                AssistantToolResult.Json("{}")
            },
        )
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content =
                            "$COMPAT_TEXT_TOOL_REQUEST_TOKEN" +
                                "{\"name\":\"render_ink_page\",\"arguments\":{}}",
                    ),
                ),
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(content = "I could not complete that tool request."),
                ),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(renderTool),
        )

        val events = provider.streamEvents(ChatRequest(userText = "Draw it")).toList()

        assertEquals(0, executionCount)
        val replayError = client.requests[1].messages.getJSONObject(1)
        assertEquals("user", replayError.getString("role"))
        assertTrue(replayError.getString("content").contains("malformed"))
        assertFalse(replayError.getString("content").contains(RENDER_INK_PAGE_TOOL_NAME))
        assertFalse(events.any { event -> event is AiProviderEvent.Failed })
    }

    @Test
    fun `Hermes render template draws the card and replays its result`() = runTest {
        val capabilities = FakeBridgeInkCapabilities()
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content = COMPAT_TEXT_TOOL_REQUEST_TOKEN +
                            """{"name":"render_template","arguments":{"template":"weather",""" +
                            """"title":"Paris","data":{"temperature":"21 C","condition":"Clear",""" +
                            """"hourly":[{"label":"10:00","temp":20},{"label":"11:00","temp":21}]}}}""",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "21 degrees and clear.")),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(renderTemplateTool(capabilities)),
            sessionContext = { inkGrantedSession() },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Weather in Paris")).toList()

        assertEquals(1, capabilities.showCalls)
        assertEquals("<page>weather</page>", capabilities.shownPage)
        val shown = checkNotNull(capabilities.shownData)
        assertEquals("Paris", shown.getString("title"))
        assertEquals(2, shown.getJSONArray("hourly").length())
        assertTrue(
            client.requests[1].messages
                .getJSONObject(1)
                .getString("content")
                .contains("$RENDER_TEMPLATE_TOOL_NAME: {\"status\":\"shown\"}"),
        )
        assertTrue(events.filterIsInstance<AiProviderEvent.TextDelta>().none { event ->
            event.delta.contains(RENDER_TEMPLATE_TOOL_NAME)
        })
        assertEquals(
            "21 degrees and clear.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `Hermes render template accepts the schema's JSON encoded data string`() = runTest {
        val capabilities = FakeBridgeInkCapabilities()
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content = COMPAT_TEXT_TOOL_REQUEST_TOKEN +
                            """{"name":"render_template","arguments":{"template":"metrics",""" +
                            """"title":null,"data":"{\"cells\":[{\"label\":\"CPU\",\"value\":\"42%\"},""" +
                            """{\"label\":\"RAM\",\"value\":\"61%\"}]}"}}""",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "CPU is at 42 percent.")),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(renderTemplateTool(capabilities)),
            sessionContext = { inkGrantedSession() },
        )

        provider.streamEvents(ChatRequest(userText = "Show the load")).toList()

        assertEquals(1, capabilities.showCalls)
        assertEquals("<page>metrics</page>", capabilities.shownPage)
        assertEquals(2, checkNotNull(capabilities.shownData).getJSONArray("cells").length())
    }

    @Test
    fun `Hermes render template with unusable data fails safe and still answers`() = runTest {
        val capabilities = FakeBridgeInkCapabilities()
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        // Weather without an hourly curve or forecast periods has nothing to draw.
                        content = COMPAT_TEXT_TOOL_REQUEST_TOKEN +
                            """{"name":"render_template","arguments":{"template":"weather",""" +
                            """"data":{"temperature":"21 C","condition":"Clear"}}}""",
                    ),
                ),
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(content = "It is 21 degrees and clear in Paris."),
                ),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(renderTemplateTool(capabilities)),
            sessionContext = { inkGrantedSession() },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Weather in Paris")).toList()

        assertEquals(0, capabilities.showCalls)
        assertTrue(
            client.requests[1].messages
                .getJSONObject(1)
                .getString("content")
                .contains(
                    "$RENDER_TEMPLATE_TOOL_NAME: failed with error code " +
                        "$TOOL_ERROR_INVALID_TEMPLATE_DATA.",
                ),
        )
        assertFalse(events.any { event -> event is AiProviderEvent.Failed })
        assertEquals(
            "It is 21 degrees and clear in Paris.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `Hermes render template stays unavailable without the ink surface grant`() = runTest {
        val capabilities = FakeBridgeInkCapabilities()
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content = COMPAT_TEXT_TOOL_REQUEST_TOKEN +
                            """{"name":"render_template","arguments":{"template":"weather",""" +
                            """"data":{"temperature":"21 C","condition":"Clear",""" +
                            """"hourly":[{"label":"10:00","temp":20},{"label":"11:00","temp":21}]}}}""",
                    ),
                ),
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(content = "I could not complete that tool request."),
                ),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(renderTemplateTool(capabilities)),
        )

        val events = provider.streamEvents(ChatRequest(userText = "Weather in Paris")).toList()

        assertEquals(0, capabilities.showCalls)
        val replayError = client.requests[1].messages.getJSONObject(1)
        assertTrue(replayError.getString("content").contains("malformed"))
        assertFalse(events.any { event -> event is AiProviderEvent.Failed })
    }

    @Test
    fun `Hermes call without an arguments key still runs the tool`() = runTest {
        val executed = mutableListOf<AssistantToolCall>()
        val listNotes = TestAssistantTool(
            name = LIST_NOTES_TOOL_NAME,
            executor = { call, _ ->
                executed += call
                AssistantToolResult.Json("""{"notes":[]}""")
            },
        )
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content = "$COMPAT_TEXT_TOOL_REQUEST_TOKEN{\"name\":\"list_notes\"}",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Nothing saved yet.")),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(listNotes),
        )

        provider.streamEvents(ChatRequest(userText = "Read my notes")).toList()

        assertEquals(listOf(LIST_NOTES_TOOL_NAME), executed.map(AssistantToolCall::name))
        assertEquals("{}", executed.single().argumentsJson)
    }

    @Test
    fun `Hermes malformed json follows the spoken safe error path`() = runTest {
        var executionCount = 0
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content =
                            "$COMPAT_TEXT_TOOL_REQUEST_TOKEN" +
                                "{\"name\":\"take_photo\",\"arguments\":",
                    ),
                ),
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(content = "I could not complete that tool request."),
                ),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = {
                executionCount += 1
                AssistantToolResult.Image("image/jpeg", "AQID")
            },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Take a photo")).toList()

        assertEquals(0, executionCount)
        assertTrue(
            client.requests[1].messages.getJSONObject(1).getString("content").contains("malformed"),
        )
        assertFalse(events.any { event -> event is AiProviderEvent.Failed })
        assertEquals(
            "I could not complete that tool request.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `Hermes tool errors replay plain text with the tool name and code`() = runTest {
        val takeNote = TestAssistantTool(
            name = TAKE_NOTE_TOOL_NAME,
            executor = { _, _ -> AssistantToolResult.Error(TOOL_ERROR_INVALID_CALL) },
        )
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        content =
                            "$COMPAT_TEXT_TOOL_REQUEST_TOKEN" +
                                "{\"name\":\"take_note\",\"arguments\":{}}",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "I could not save it.")),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(takeNote),
        )

        provider.streamEvents(ChatRequest(userText = "Save it")).toList()

        assertTrue(
            client.requests[1].messages
                .getJSONObject(1)
                .getString("content")
                .contains("$TAKE_NOTE_TOOL_NAME: failed with error code $TOOL_ERROR_INVALID_CALL."),
        )
    }

    @Test
    fun `Hermes final replay control line ends without a deeper loop`() = runTest {
        val takeNote = TestAssistantTool(name = TAKE_NOTE_TOOL_NAME)
        val controlLine =
            "$COMPAT_TEXT_TOOL_REQUEST_TOKEN{\"name\":\"take_note\",\"arguments\":{}}"
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = controlLine)),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = controlLine)),
            ),
        )
        val provider = provider(
            client = client,
            preset = ProviderCatalog.hermes,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(takeNote),
        )

        val events = provider.streamEvents(ChatRequest(userText = "Save it")).toList()

        assertEquals(2, client.requests.size)
        assertTrue(events.filterIsInstance<AiProviderEvent.TextDelta>().isEmpty())
        assertEquals(
            "The selected model could not use the Nexus phone tool result.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `only explicit or detected Hermes carries a safe conversation session header`() {
        val conversationId = "7baf8322-8919-4b20-95dc-5337ad5b6769"
        val request = OpenAiCompatChatRequest(
            request = ChatRequest(userText = "Hello", conversationId = conversationId),
            modelId = "hermes-agent",
            messages = ChatRequest(userText = "Hello").toChatCompletionMessages(),
            toolDefinitions = emptyList(),
        )
        val hermesClient = OpenAiCompatApiClient(
            preset = ProviderCatalog.hermes,
            apiKeyProvider = { "key" },
            baseUrlProvider = { "https://hermes.test/v1" },
        )
        val genericCustomClient = OpenAiCompatApiClient(
            preset = ProviderCatalog.custom,
            apiKeyProvider = { "key" },
            baseUrlProvider = { "https://custom.test/v1" },
        )
        val detectedCustomClient = OpenAiCompatApiClient(
            preset = ProviderCatalog.custom,
            apiKeyProvider = { "key" },
            baseUrlProvider = { "https://hermes.test/v1" },
            backendProvider = { ProviderBackend.HERMES },
        )
        val openAiClient = OpenAiCompatApiClient(
            preset = ProviderCatalog.openAi,
            apiKeyProvider = { "key" },
        )

        assertEquals(conversationId, hermesClient.hermesSessionIdHeader(request))
        assertEquals(null, genericCustomClient.hermesSessionIdHeader(request))
        assertEquals(conversationId, detectedCustomClient.hermesSessionIdHeader(request))
        assertEquals(null, openAiClient.hermesSessionIdHeader(request))
        assertEquals(
            null,
            hermesClient.hermesSessionIdHeader(
                request.copy(request = request.request.copy(conversationId = "bad\nsession")),
            ),
        )
    }

    @Test
    fun `tool error replays compact error without photo message`() = runTest {
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(takePhotoCallDelta(callId = "call-error")),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Please try again.")),
            ),
        )
        val provider = provider(
            client = client,
            toolExecutor = {
                AssistantToolResult.Error(TOOL_ERROR_CAMERA_BUSY)
            },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Look")).toList()

        val messages = client.requests[1].messages
        assertEquals(3, messages.length())
        val tool = messages.getJSONObject(2)
        assertEquals("tool", tool.getString("role"))
        assertToolContent(
            tool.getString("content"),
            "status" to "error",
            "code" to "camera_busy",
        )
        assertFalse(
            (0 until messages.length()).any { index ->
                val message = messages.getJSONObject(index)
                message.optString("role") == "user" && message.opt("content") !is String
            },
        )
        assertEquals(
            "Please try again.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `test only second tool is declared dispatched and replayed as json`() = runTest {
        val lookupTool = TestAssistantTool(
            name = "lookup_note",
            description = "Look up a saved note.",
            executor = { _, _ ->
                AssistantToolResult.Json("""{"ok":true,"title":"Groceries"}""")
            },
        )
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        toolCalls = listOf(
                            OpenAiChatToolCallDelta(
                                index = 0,
                                id = "call-note",
                                nameFragment = "lookup_note",
                                argumentsFragment = "{}",
                            ),
                        ),
                        finishReason = "tool_calls",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Milk and bread.")),
            ),
        )
        val provider = provider(
            client = client,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(lookupTool),
        )

        provider.streamEvents(ChatRequest(userText = "Read my groceries note")).toList()

        val tools = bodyFor(client.requests[0]).getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals(
            TAKE_PHOTO_TOOL_NAME,
            tools.getJSONObject(0).getJSONObject("function").getString("name"),
        )
        assertEquals(
            "lookup_note",
            tools.getJSONObject(1).getJSONObject("function").getString("name"),
        )
        val replayMessages = client.requests[1].messages
        assertEquals(3, replayMessages.length())
        assertEquals("tool", replayMessages.getJSONObject(2).getString("role"))
        assertEquals(
            """{"ok":true,"title":"Groceries"}""",
            replayMessages.getJSONObject(2).getString("content"),
        )
    }

    @Test
    fun `only first of two valid tool calls reaches executor`() = runTest {
        var executionCount = 0
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Events(
                    OpenAiChatSseEvent.Delta(
                        toolCalls = listOf(
                            completeToolDelta(index = 0, callId = "call-first"),
                            completeToolDelta(index = 1, callId = "call-second"),
                        ),
                        finishReason = "tool_calls",
                    ),
                ),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Done.")),
            ),
        )
        val provider = provider(
            client = client,
            toolExecutor = {
                executionCount += 1
                AssistantToolResult.Image("image/png", "cGhvdG8=")
            },
        )

        provider.streamEvents(ChatRequest(userText = "Look twice")).toList()

        assertEquals(1, executionCount)
        val messages = client.requests[1].messages
        assertToolContent(
            messages.getJSONObject(2).getString("content"),
            "status" to "captured",
            "note" to "The photo is attached to the next user message.",
        )
        assertToolContent(
            messages.getJSONObject(3).getString("content"),
            "status" to "error",
            "code" to "already_used",
        )
        assertEquals("user", messages.getJSONObject(4).getString("role"))
    }

    @Test
    fun `non vision model sends no tool declaration`() = runTest {
        val client = RecordingCompatClient(
            listOf(StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Text only."))),
        )
        val provider = provider(
            client = client,
            supportsVision = false,
            toolExecutor = { error("Tool must not execute") },
        )

        val events = provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        assertEquals(1, client.requests.size)
        assertTrue(client.requests.single().toolDefinitions.isEmpty())
        assertFalse(bodyFor(client.requests.single()).has("tools"))
        assertFalse(events.any { event -> event is AiProviderEvent.TextReset })
        assertEquals(
            "Text only.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `non vision model still declares an available non vision tool`() = runTest {
        val client = RecordingCompatClient(
            listOf(StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Text only."))),
        )
        val lookupTool = TestAssistantTool(
            name = "lookup_note",
            description = "Look up a saved note.",
        )
        val provider = provider(
            client = client,
            supportsVision = false,
            toolExecutor = { error("Photo tool must not execute") },
            additionalTools = listOf(lookupTool),
        )

        provider.streamEvents(ChatRequest(userText = "Hello")).toList()

        val tools = bodyFor(client.requests.single()).getJSONArray("tools")
        assertEquals(1, tools.length())
        assertEquals(
            "lookup_note",
            tools.getJSONObject(0).getJSONObject("function").getString("name"),
        )
    }

    @Test
    fun `four hundred response with tools retries once without tools`() = runTest {
        val client = RecordingCompatClient(
            listOf(
                StubResponse.Failure(OpenAiCompatHttpException(422, "tools unsupported")),
                StubResponse.Events(OpenAiChatSseEvent.Delta(content = "Fallback answer.")),
            ),
        )
        val provider = provider(
            client = client,
            toolExecutor = { error("Tool must not execute") },
        )
        val request = ChatRequest(userText = "Hello")

        val events = provider.streamEvents(request).toList()

        assertEquals(2, client.requests.size)
        assertEquals(
            listOf(true, false),
            client.requests.map { it.toolDefinitions.isNotEmpty() },
        )
        assertTrue(client.requests.all { it.requestId == request.requestId })
        assertEquals(
            listOf("Fallback answer."),
            events.filterIsInstance<AiProviderEvent.TextDelta>().map { event -> event.delta },
        )
        assertFalse(events.any { event -> event is AiProviderEvent.Failed })
        assertEquals(
            "Fallback answer.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    private fun provider(
        client: RecordingCompatClient,
        toolExecutor: suspend (AssistantToolCall) -> AssistantToolResult,
        supportsVision: Boolean = true,
        additionalTools: List<AssistantToolDefinition> = emptyList(),
        preset: ProviderPreset = ProviderCatalog.openAi,
        sessionContext: () -> AssistantToolSessionContext = {
            AssistantToolSessionContext(active = true)
        },
    ) = OpenAiCompatProvider(
        preset = preset,
        apiClient = client,
        apiKeyConfigured = { true },
        toolRegistry = testToolRegistry(
            toolExecutor,
            *additionalTools.toTypedArray(),
            sessionContext = sessionContext,
        ),
        supportsVision = { supportsVision },
        backendProvider = { preset.backend },
    )

    private fun renderTemplateTool(
        capabilities: InkPageToolCapabilities,
    ): RenderTemplateTool = RenderTemplateTool(
        runtime = InkPageToolRuntime(capabilities) { AssistantVisualAnswers.FREE_PAGES },
        templateLoader = InkTemplateLoader { template -> "<page>${template.wireValue}</page>" },
    )

    private fun inkGrantedSession(): AssistantToolSessionContext = AssistantToolSessionContext(
        active = true,
        grantedCapabilities = setOf(PluginCapability.INK_SURFACE.wireValue),
    )

    private fun assertToolContent(content: String, vararg fields: Pair<String, String>) {
        val json = JSONObject(content)
        assertEquals(fields.size, json.length())
        fields.forEach { (key, value) -> assertEquals(value, json.getString(key)) }
    }

    private fun bodyFor(request: OpenAiCompatChatRequest): JSONObject =
        OpenAiCompatApiClient(
            preset = ProviderCatalog.openAi,
            apiKeyProvider = { "key" },
        ).requestBody(request)

    private fun takePhotoCallDelta(callId: String): OpenAiChatSseEvent.Delta =
        OpenAiChatSseEvent.Delta(
            toolCalls = listOf(completeToolDelta(index = 0, callId = callId)),
            finishReason = "tool_calls",
        )

    private fun completeToolDelta(index: Int, callId: String) =
        OpenAiChatToolCallDelta(
            index = index,
            id = callId,
            nameFragment = TAKE_PHOTO_TOOL_NAME,
            argumentsFragment = "{}",
        )

    private sealed interface StubResponse {
        data class Events(val events: List<OpenAiChatSseEvent>) : StubResponse {
            constructor(vararg events: OpenAiChatSseEvent) : this(events.toList())
        }

        data class Failure(val error: Throwable) : StubResponse
    }

    private class FakeBridgeInkCapabilities : InkPageToolCapabilities {
        var showCalls = 0
        var shownPage: String? = null
        var shownData: JSONObject? = null

        override fun currentSession(): InkPageToolSession = InkPageToolSession("request", 1L)

        override fun isSessionActive(session: InkPageToolSession): Boolean = true

        override fun supportsInkSurface(): Boolean = true

        override suspend fun showInkPage(
            session: InkPageToolSession,
            page: String,
            data: JSONObject?,
        ): InkPageShowResult {
            showCalls += 1
            shownPage = page
            shownData = data
            return InkPageShowResult.Shown
        }

        override fun markInkShown(session: InkPageToolSession): Boolean = true
    }

    private class RecordingCompatClient(
        responses: List<StubResponse>,
    ) : OpenAiCompatChatClient {
        private val remainingResponses = ArrayDeque(responses)
        val requests = mutableListOf<OpenAiCompatChatRequest>()

        override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> {
            requests += request
            val response = remainingResponses.removeFirst()
            return flow {
                when (response) {
                    is StubResponse.Events -> response.events.forEach { event -> emit(event) }
                    is StubResponse.Failure -> throw response.error
                }
            }
        }

        override fun cancel(requestId: String) = Unit
    }
}
