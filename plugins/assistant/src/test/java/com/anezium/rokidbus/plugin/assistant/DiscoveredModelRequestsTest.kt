package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveredModelRequestsTest {
    @Test
    fun futureOpenAiGenerationUsesResponsesAndAdvertisedEfforts() {
        val client = OpenAiCompatApiClient(ProviderCatalog.openAi, { "key" }, effortProvider = { "new-effort" },
            modelInfoProvider = { AvailableModel(it, efforts = listOf("new-effort")) })
        assertTrue(client.endpointUrl("gpt-7-example").endsWith("/responses"))
        val body = client.requestBody(ChatRequest(userText = "Hello"), "gpt-7-example")
        assertEquals("gpt-7-example", body.getString("model"))
        assertEquals("new-effort", body.getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun compatibleProvidersUseTheirOwnReasoningWireFormat() {
        for (preset in listOf(ProviderCatalog.openRouter, ProviderCatalog.deepSeek, ProviderCatalog.minimax)) {
            val client = OpenAiCompatApiClient(preset, { "key" }, effortProvider = { "max" },
                modelInfoProvider = { AvailableModel(it, efforts = listOf("max")) })
            val body = client.requestBody(ChatRequest(userText = "Hello"), "future-model")
            assertEquals("future-model", body.getString("model"))
            if (preset.id == "openrouter") assertEquals("max", body.getJSONObject("reasoning").getString("effort"))
            else assertEquals("max", body.getString("reasoning_effort"))
            if (preset.id == "deepseek") assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
        }
    }

    @Test
    fun deepSeekUsesNonThinkingModeUntilAnEffortIsExplicitlySelected() {
        val client = OpenAiCompatApiClient(ProviderCatalog.deepSeek, { "key" })
        val body = client.requestBody(ChatRequest(userText = "Hello"), "deepseek-flash")
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertFalse(body.has("reasoning_effort"))
    }

    @Test
    fun newCodexModelAndItsEffortReachTheActualRequest() = runTest {
        val bodies = mutableListOf<JSONObject>()
        var model = AvailableModel("future-model", efforts = listOf("high", "max"), defaultEffort = "high")
        val client = ChatGptCodexApiClient(
            tokenProvider = { CodexChatGptOAuthTokenBundle("access", "id", null, "account", "free", null) },
            refreshTokens = { error("Unexpected refresh") },
            modelsProvider = { listOf(model) },
            transport = object : ChatGptCodexHttpTransport {
                override suspend fun execute(requestId: String, request: ChatGptCodexHttpRequest,
                    consumeData: suspend (String) -> Boolean): ChatGptCodexHttpResponse {
                    bodies += JSONObject(request.body)
                    consumeData("""{"type":"response.completed"}""")
                    return ChatGptCodexHttpResponse(200)
                }
                override fun cancel(requestId: String) = Unit
            },
        )
        client.streamResponses(ChatRequest(userText = "Hello"), model.id, "max").toList()
        assertEquals("future-model", bodies.last().getString("model"))
        assertEquals("max", bodies.last().getJSONObject("reasoning").getString("effort"))
        model = model.copy(efforts = emptyList())
        client.streamResponses(ChatRequest(userText = "Hello"), model.id).toList()
        assertFalse(bodies.last().has("reasoning"))
    }
}
