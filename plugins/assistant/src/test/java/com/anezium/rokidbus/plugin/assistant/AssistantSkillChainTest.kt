package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.shared.skills.SkillInvokeRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "When is the next tram at my favorite stop?" end to end on both structured providers, with a
 * scripted model and a hub stand-in: the favorite lookup and the departure query happen in
 * separate rounds, and the second carries exactly the reference the first returned.
 */
class AssistantSkillChainTest {
    private val stopRef = "r_CENTRALCENTRALCENTRAL"
    private val transport = FakeSkillTransport(
        entries = listOf(
            skillEntry("list_favorites"),
            skillEntry(
                "get_departures",
                """{"type":"object","properties":{"stop":{"type":"string","nexusRef":"stop"}},"required":["stop"]}""",
            ),
        ),
    )
    private val gateway = AssistantSkillGateway(transport).also { transport.gateway = it }
    private val memory = AssistantSkillMemory()

    init {
        transport.handler = { request ->
            when (request.alias) {
                "sk_transit__list_favorites" -> skillResult(
                    request,
                    data = JSONObject().put("stops", JSONArray().put(JSONObject().put("stop", stopRef).put("name", "Central"))),
                )
                else -> skillResult(
                    request,
                    data = JSONObject().put(
                        "departures",
                        JSONArray().put(JSONObject().put("line", "T3").put("time_local", "10:04").put("realtime", "live")),
                    ),
                )
            }
        }
    }

    private suspend fun registry(): AssistantToolRegistry {
        val tools = gateway.catalog().map { entry ->
            PluginSkillTool(entry, gateway, { "session-0001" }, { "turn-1" }, memory)
        }
        return AssistantToolRegistry(emptyList(), dynamicDefinitions = { tools })
    }

    private fun invokedAliases(): List<String> = transport.invocations.map(SkillInvokeRequest::alias)

    @Test
    fun `the OpenAI-compatible path chains favorites into departures`() = runTest {
        val client = ScriptedCompatClient { index, request ->
            when (index) {
                0 -> listOf(toolCall("call_fav", "sk_transit__list_favorites", "{}"))
                1 -> {
                    val result = request.messages.getJSONObject(request.messages.length() - 1).getString("content")
                    val ref = JSONObject(result).getJSONObject("data").getJSONArray("stops").getJSONObject(0).getString("stop")
                    listOf(toolCall("call_dep", "sk_transit__get_departures", """{"stop":"$ref"}"""))
                }
                else -> listOf(OpenAiChatSseEvent.Delta(content = "Next T3 at 10:04 from Central."))
            }
        }
        val provider = OpenAiCompatProvider(
            preset = ProviderCatalog.openAi,
            apiClient = client,
            apiKeyConfigured = { true },
            toolRegistry = registry(),
            supportsVision = { false },
        )

        val events = provider.streamEvents(ChatRequest(userText = "When is the next tram at my favorite stop?")).toList()

        assertEquals(listOf("sk_transit__list_favorites", "sk_transit__get_departures"), invokedAliases())
        assertEquals(stopRef, transport.invocations[1].arguments.getString("stop"))
        assertEquals(3, client.requests.size)
        assertTrue(client.requests.take(2).all { it.toolDefinitions.isNotEmpty() })
        assertEquals(
            "Next T3 at 10:04 from Central.",
            events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content,
        )
    }

    @Test
    fun `the ChatGPT Codex path chains favorites into departures`() = runTest {
        val bodies = mutableListOf<JSONObject>()
        val codexTransport = object : ChatGptCodexHttpTransport {
            override suspend fun execute(
                requestId: String,
                request: ChatGptCodexHttpRequest,
                consumeData: suspend (String) -> Boolean,
            ): ChatGptCodexHttpResponse {
                val body = JSONObject(request.body).also(bodies::add)
                val events = when (bodies.size) {
                    1 -> listOf(functionCall("call_fav", "sk_transit__list_favorites", "{}"))
                    2 -> {
                        val input = body.getJSONArray("input")
                        val output = JSONObject(input.getJSONObject(input.length() - 1).getString("output"))
                        val ref = output.getJSONObject("data").getJSONArray("stops").getJSONObject(0).getString("stop")
                        listOf(functionCall("call_dep", "sk_transit__get_departures", """{"stop":"$ref"}"""))
                    }
                    else -> listOf(
                        JSONObject().put("type", "response.output_text.delta").put("delta", "Next T3 at 10:04.").toString(),
                    )
                } + """{"type":"response.completed","response":{"output":[]}}"""
                for (data in events) if (!consumeData(data)) break
                return ChatGptCodexHttpResponse(200)
            }

            override fun cancel(requestId: String) = Unit
        }
        val provider = ChatGptCodexProvider(
            apiClient = ChatGptCodexApiClient(
                tokenProvider = {
                    CodexChatGptOAuthTokenBundle("token", "id", "refresh", "account", "plus", "person@example.com")
                },
                refreshTokens = { error("unexpected refresh") },
                transport = codexTransport,
                sessionId = "chain-test",
            ),
            oauthConfigured = { true },
            toolRegistry = registry(),
        )

        val events = provider.streamEvents(ChatRequest(userText = "When is the next tram at my favorite stop?")).toList()

        assertEquals(listOf("sk_transit__list_favorites", "sk_transit__get_departures"), invokedAliases())
        assertEquals(stopRef, transport.invocations[1].arguments.getString("stop"))
        val declared = bodies[1].getJSONArray("tools")
        val skillDeclaration = (0 until declared.length()).map { declared.getJSONObject(it) }
            .first { it.optString("name") == "sk_transit__get_departures" }
        assertEquals(false, skillDeclaration.getBoolean("strict"))
        assertEquals("Next T3 at 10:04.", events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content)
    }

    private fun toolCall(id: String, name: String, arguments: String) = OpenAiChatSseEvent.Delta(
        toolCalls = listOf(OpenAiChatToolCallDelta(index = 0, id = id, nameFragment = name, argumentsFragment = arguments)),
        finishReason = "tool_calls",
    )

    private fun functionCall(id: String, name: String, arguments: String): String = JSONObject()
        .put("type", "response.output_item.done")
        .put(
            "item",
            JSONObject()
                .put("type", "function_call")
                .put("status", "completed")
                .put("arguments", arguments)
                .put("call_id", id)
                .put("name", name),
        )
        .toString()

    private class ScriptedCompatClient(
        private val script: (index: Int, request: OpenAiCompatChatRequest) -> List<OpenAiChatSseEvent>,
    ) : OpenAiCompatChatClient {
        val requests = mutableListOf<OpenAiCompatChatRequest>()

        override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> {
            val index = requests.size
            requests += request
            return flow { script(index, request).forEach { emit(it) } }
        }

        override fun cancel(requestId: String) = Unit
    }
}
