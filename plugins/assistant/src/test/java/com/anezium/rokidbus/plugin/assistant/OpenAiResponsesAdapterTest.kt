package com.anezium.rokidbus.plugin.assistant

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Collections

class OpenAiResponsesAdapterTest {
    @Test
    fun `OpenAI GPT6 uses Responses while other presets and legacy models keep Chat Completions`() {
        val openAi = OpenAiCompatApiClient(ProviderCatalog.openAi, { "key" })
        listOf("gpt-6-luna", "gpt-6.1-sol", "gpt-6-astra").forEach { model ->
            assertTrue(openAi.endpointUrl(model).endsWith("/responses"))
            val body = openAi.requestBody(ChatRequest(userText = "Hello"), model)
            assertFalse(body.has("messages"))
            assertEquals(if (model == "gpt-6-luna") "none" else "low", body.getJSONObject("reasoning").getString("effort"))
        }
        assertTrue(openAi.endpointUrl("gpt-4o").endsWith("/chat/completions"))
        val legacy = openAi.requestBody(ChatRequest(userText = "Hello"), "gpt-4o")
        assertTrue(legacy.has("messages"))
        assertFalse(legacy.has("input"))
        val withoutTools = openAi.requestBody(ChatRequest(userText = "Hello"), "gpt-6-luna")
        assertFalse(withoutTools.has("tools"))
        assertFalse(withoutTools.has("parallel_tool_calls"))
        assertTrue(OpenAiCompatApiClient(ProviderCatalog.openRouter, { "key" }).endpointUrl("openai/gpt-6.1-sol").endsWith("/chat/completions"))
        val proxy = OpenAiCompatApiClient(ProviderCatalog.openAi, { "key" }, baseUrlProvider = { "https://proxy.test/v1" })
        assertTrue(proxy.endpointUrl("gpt-6-luna").endsWith("/chat/completions"))
        assertTrue(proxy.requestBody(ChatRequest(userText = "Hello"), "gpt-6-luna").has("messages"))
        val deep = OpenAiCompatApiClient(ProviderCatalog.openAi, { "key" }, effortProvider = { "ultra" })
        assertEquals("max", deep.requestBody(ChatRequest(userText = "Hello"), "gpt-6-astra").getJSONObject("reasoning").getString("effort"))
    }

    @Test
    fun `real HTTP Responses stream executes a tool and replays its result and image`() = runTest {
        val bodies = Collections.synchronizedList(mutableListOf<JSONObject>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/responses") { exchange ->
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            bodies += body
            val events = if (bodies.size == 1) listOf(
                """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque-encrypted-state"}}""",
                """{"type":"response.output_text.delta","delta":"Looking."}""",
                """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","call_id":"photo-1","name":"take_photo","arguments":""}}""",
                """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","call_id":"photo-1","name":"take_photo","arguments":"{}"}}""",
                """{"type":"response.completed"}""",
            ) else listOf(
                """{"type":"response.output_text.delta","delta":"The label says 42."}""",
                """{"type":"response.completed"}""",
            )
            val bytes = events.joinToString("") { "data: $it\n\n" }.toByteArray()
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            var executions = 0
            val provider = OpenAiCompatProvider(
                preset = ProviderCatalog.openAi,
                apiClient = OpenAiCompatApiClient(ProviderCatalog.openAi, { "key" },
                    baseUrlProvider = { "http://127.0.0.1:${server.address.port}/v1" }, responsesOverride = true),
                apiKeyConfigured = { true },
                toolRegistry = testToolRegistry(executor = { executions++; AssistantToolResult.Image("image/jpeg", "AQID") }),
                supportsVision = { true },
            )
            val events = provider.streamEvents(ChatRequest(userText = "Read this", systemPrompt = "Use the photo.")).toList()
            assertEquals(1, executions)
            assertEquals("The label says 42.", events.filterIsInstance<AiProviderEvent.MessageDone>().single().message.content)
            assertEquals(2, bodies.size)
            assertEquals("Use the photo.", bodies[0].getString("instructions"))
            assertEquals("reasoning.encrypted_content", bodies[0].getJSONArray("include").getString(0))
            val tool = bodies[0].getJSONArray("tools").getJSONObject(0)
            assertEquals("take_photo", tool.getString("name"))
            assertTrue(tool.getBoolean("strict"))
            val replay = bodies[1].getJSONArray("input")
            val items = (0 until replay.length()).map(replay::getJSONObject)
            val reasoningIndex = items.indexOfFirst { it.optString("type") == "reasoning" }
            assertEquals("opaque-encrypted-state", items[reasoningIndex].getString("encrypted_content"))
            assertEquals("assistant", items[reasoningIndex + 1].getString("role"))
            assertEquals("Looking.", items[reasoningIndex + 1].getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals("function_call", items[reasoningIndex + 2].getString("type"))
            assertEquals("photo-1", items.single { it.optString("type") == "function_call" }.getString("call_id"))
            assertEquals("photo-1", items.single { it.optString("type") == "function_call_output" }.getString("call_id"))
            val photo = items.last().getJSONArray("content").getJSONObject(1)
            assertEquals("input_image", photo.getString("type"))
            assertEquals("data:image/jpeg;base64,AQID", photo.getString("image_url"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Responses errors and incomplete streams remain errors instead of silent answers`() {
        assertTrue(OpenAiResponsesAdapter.event("""{"type":"response.failed","response":{"error":{"message":"Rejected schema"}}}""") is OpenAiChatSseEvent.Error)
        assertTrue(OpenAiResponsesAdapter.event("""{"type":"response.incomplete"}""") is OpenAiChatSseEvent.Error)
    }

    @Test
    fun `a truncated HTTP Responses stream cannot be reported as a completed answer`() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/responses") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            val bytes = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Partial\"}\n\n".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val provider = OpenAiCompatProvider(ProviderCatalog.openAi,
                OpenAiCompatApiClient(ProviderCatalog.openAi, { "key" },
                    baseUrlProvider = { "http://127.0.0.1:${server.address.port}/v1" }, responsesOverride = true),
                apiKeyConfigured = { true }, toolRegistry = unusedToolRegistry(), supportsVision = { false })
            val events = provider.streamEvents(ChatRequest(userText = "Hello")).toList()
            assertTrue(events.any { it is AiProviderEvent.Failed })
            assertFalse(events.any { it is AiProviderEvent.MessageDone })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `reasoning references without encrypted state are not replayed in stateless requests`() {
        val messages = JSONArray().put(JSONObject().put("role", "assistant").put("content", "Looking.")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "photo-1").put("type", "function")
                .put("function", JSONObject().put("name", "take_photo").put("arguments", "{}")))))
        val request = OpenAiCompatChatRequest(ChatRequest(userText = "Read this"), "gpt-6-luna", messages, emptyList(),
            responseReasoning = mapOf("photo-1" to listOf(JSONObject().put("type", "reasoning").put("id", "rs_1"))))
        val input = OpenAiResponsesAdapter.body(request, "none").getJSONArray("input")
        assertEquals(2, input.length())
        assertEquals("assistant", input.getJSONObject(0).getString("role"))
        assertEquals("function_call", input.getJSONObject(1).getString("type"))
    }
}
