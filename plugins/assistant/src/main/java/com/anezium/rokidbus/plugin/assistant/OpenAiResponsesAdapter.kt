package com.anezium.rokidbus.plugin.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Converts the existing conversation/tool loop at the OpenAI transport boundary. */
internal object OpenAiResponsesAdapter {
    fun body(request: OpenAiCompatChatRequest, requestedEffort: String): JSONObject {
        val input = JSONArray()
        val instructions = mutableListOf<String>()
        for (index in 0 until request.messages.length()) {
            val message = request.messages.getJSONObject(index)
            val role = message.getString("role")
            when (role) {
                "system", "developer" -> instructions += message.optString("content")
                "tool" -> input.put(JSONObject().put("type", "function_call_output")
                    .put("call_id", message.getString("tool_call_id")).put("output", message.getString("content")))
                else -> {
                    val calls = message.optJSONArray("tool_calls")
                    calls?.let {
                        for (callIndex in 0 until it.length()) {
                            request.responseReasoning[it.getJSONObject(callIndex).getString("id")]?.forEach { reasoning ->
                                if (reasoning.optString("encrypted_content").isNotBlank()) input.put(reasoning)
                            }
                        }
                    }
                    val content = content(message, role)
                    if (content.length() > 0) input.put(JSONObject().put("role", role).put("content", content))
                    calls?.let {
                        for (callIndex in 0 until it.length()) {
                            val call = it.getJSONObject(callIndex)
                            val function = call.getJSONObject("function")
                            input.put(JSONObject().put("type", "function_call").put("call_id", call.getString("id"))
                                .put("name", function.getString("name")).put("arguments", function.getString("arguments")))
                        }
                    }
                }
            }
        }
        // Public API values are independent of the ChatGPT backend's legacy aliases and caps.
        val effort = when {
            requestedEffort == "ultra" -> "max"
            requestedEffort == "none" && request.modelId != "gpt-6-luna" && request.modelId != "gpt-6-sol" -> "low"
            requestedEffort in setOf("none", "low", "medium", "high", "xhigh", "max") -> requestedEffort
            request.modelId == "gpt-6-luna" || request.modelId == "gpt-6-sol" -> "none"
            else -> "low"
        }
        return JSONObject().put("model", request.modelId).put("stream", true).put("store", false)
            .put("instructions", instructions.joinToString("\n\n")).put("input", input)
            .put("reasoning", JSONObject().put("effort", effort))
            .put("include", JSONArray().put("reasoning.encrypted_content"))
            .apply {
                if (request.toolDefinitions.isNotEmpty()) {
                    put("parallel_tool_calls", false)
                    put("tools", JSONArray().apply {
                        request.toolDefinitions.forEach { definition ->
                            put(JSONObject().put("type", "function").put("name", definition.name)
                                .put("description", definition.description).put("parameters", definition.parametersSchema.toJsonObject())
                                .put("strict", definition.strictSchema && definition.parametersSchema.isStrictCompatible()))
                        }
                    })
                }
            }
    }

    private fun content(message: JSONObject, role: String): JSONArray {
        val textType = if (role == "assistant") "output_text" else "input_text"
        val result = JSONArray()
        when (val raw = message.opt("content")) {
            is String -> if (raw.isNotEmpty()) result.put(JSONObject().put("type", textType).put("text", raw))
            is JSONArray -> for (index in 0 until raw.length()) {
                val part = raw.getJSONObject(index)
                when (part.getString("type")) {
                    "text" -> result.put(JSONObject().put("type", textType).put("text", part.getString("text")))
                    "image_url" -> {
                        val image = part.getJSONObject("image_url")
                        result.put(JSONObject().put("type", "input_image").put("image_url", image.getString("url"))
                            .put("detail", image.optString("detail", "auto")))
                    }
                    else -> error("Unsupported OpenAI message content type.")
                }
            }
        }
        return result
    }

    fun event(payload: String): OpenAiChatSseEvent {
        val json = runCatching { JSONObject(payload) }.getOrNull()
        if (json?.optString("type") == "response.incomplete") return OpenAiChatSseEvent.Error("OpenAI response was incomplete.")
        return when (val event = ChatGptCodexSseParser.parseData(payload)) {
            is ChatGptCodexSseEvent.TextDelta -> OpenAiChatSseEvent.Delta(content = event.text)
            is ChatGptCodexSseEvent.OutputItemDone -> if (event.item.optString("type") == "function_call") {
                OpenAiChatSseEvent.Delta(toolCalls = listOf(OpenAiChatToolCallDelta(
                    index = json?.optInt("output_index", 0) ?: 0,
                    id = event.item.getString("call_id"), nameFragment = event.item.getString("name"),
                    argumentsFragment = event.item.getString("arguments"),
                )), responseItem = event.item)
            } else if (event.item.optString("type") == "reasoning") {
                OpenAiChatSseEvent.Delta(responseItem = event.item)
            } else OpenAiChatSseEvent.Ignored
            is ChatGptCodexSseEvent.StreamError -> OpenAiChatSseEvent.Error(event.message)
            ChatGptCodexSseEvent.Completed -> OpenAiChatSseEvent.Done
            else -> OpenAiChatSseEvent.Ignored
        }
    }
}
