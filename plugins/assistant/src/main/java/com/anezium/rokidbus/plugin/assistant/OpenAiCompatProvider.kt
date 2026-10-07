package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONTokener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class OpenAiCompatChatRequest(
    val request: ChatRequest,
    val modelId: String,
    val messages: JSONArray,
    val toolDefinitions: List<AssistantToolDefinition>,
    val requestId: String = request.requestId,
)

internal interface OpenAiCompatChatClient {
    fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent>

    fun cancel(requestId: String)
}

internal class OpenAiCompatApiClient(
    private val preset: ProviderPreset,
    private val apiKeyProvider: () -> String?,
    private val baseUrlProvider: () -> String = { preset.defaultBaseUrl },
    private val effortProvider: () -> String = { "" },
    private val backendProvider: () -> ProviderBackend = { preset.backend },
) : OpenAiCompatChatClient {
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()

    override fun streamChat(request: OpenAiCompatChatRequest): Flow<OpenAiChatSseEvent> = flow {
        request.request.beforeSend?.invoke()
        val connection = openConnection(request).apply {
            doOutput = true
            setRequestProperty("Accept", "text/event-stream")
            outputStream.use { output ->
                output.write(requestBody(request).toString().toByteArray(Charsets.UTF_8))
            }
        }
        activeConnections.put(request.requestId, connection)?.disconnect()
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw OpenAiCompatHttpException(
                    statusCode = status,
                    message =
                        "${preset.displayName} chat failed ($status): ${connection.safeErrorBody()}",
                )
            }
            readSse(connection) { event ->
                when (event) {
                    is OpenAiChatSseEvent.Delta -> {
                        emit(event)
                        true
                    }
                    is OpenAiChatSseEvent.Error -> throw IllegalStateException(event.message)
                    OpenAiChatSseEvent.Done -> false
                    OpenAiChatSseEvent.Ignored -> true
                }
            }
        } finally {
            activeConnections.remove(request.requestId, connection)
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    override fun cancel(requestId: String) {
        activeConnections.remove(requestId)?.disconnect()
    }

    internal fun endpointUrl(): String {
        val baseUrl = baseUrlProvider().trim().trimEnd('/')
        check(baseUrl.isNotEmpty()) { "${preset.displayName} base URL is not configured." }
        return "$baseUrl/chat/completions"
    }

    internal fun requestBody(request: OpenAiCompatChatRequest): JSONObject =
        JSONObject()
            .put("model", request.modelId)
            .put("stream", true)
            .put("messages", request.messages)
            .apply {
                val effort = effortProvider().trim()
                if (effort in preset.supportedEfforts) {
                    put("reasoning", JSONObject().put("effort", effort))
                }
                if (request.toolDefinitions.isNotEmpty()) {
                    put(
                        "tools",
                        JSONArray().apply {
                            request.toolDefinitions.forEach { definition ->
                                put(compatToolDeclaration(definition))
                            }
                        },
                    )
                }
            }

    internal fun requestBody(
        request: ChatRequest,
        modelId: String,
        toolDefinitions: List<AssistantToolDefinition> = emptyList(),
    ): JSONObject = requestBody(
        OpenAiCompatChatRequest(
            request = request,
            modelId = modelId,
            messages = request.toChatCompletionMessages(),
            toolDefinitions = toolDefinitions,
        ),
    )

    internal fun hermesSessionIdHeader(request: OpenAiCompatChatRequest): String? {
        if (backendProvider() != ProviderBackend.HERMES) return null
        return request.request.conversationId
            ?.trim()
            ?.takeIf { value ->
                value.isNotEmpty() &&
                    value.length <= MAX_HERMES_SESSION_ID_CHARS &&
                    value.none { character -> character == '\r' || character == '\n' || character == '\u0000' }
            }
    }

    private fun compatToolDeclaration(definition: AssistantToolDefinition): JSONObject {
        val parameters = definition.parametersSchema.toJsonObject().apply {
            if (optJSONArray("required")?.length() == 0) remove("required")
        }
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", definition.name)
                    .put("description", definition.description)
                    .put("parameters", parameters),
            )
    }

    private fun openConnection(request: OpenAiCompatChatRequest): HttpURLConnection =
        (URL(endpointUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKeyProvider().orEmpty()}")
            preset.extraHeaders.forEach { (name, value) ->
                setRequestProperty(name, value)
            }
            hermesSessionIdHeader(request)?.let { sessionId ->
                setRequestProperty(HERMES_SESSION_ID_HEADER, sessionId)
            }
        }

    private suspend fun readSse(
        connection: HttpURLConnection,
        consume: suspend (OpenAiChatSseEvent) -> Boolean,
    ) {
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
            val data = StringBuilder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                when {
                    line.isBlank() -> {
                        if (data.isNotEmpty()) {
                            val keepReading = consume(OpenAiChatSseParser.parseData(data.toString()))
                            data.clear()
                            if (!keepReading) return
                        }
                    }
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.removePrefix("data:").trim())
                    }
                }
            }
            if (data.isNotEmpty()) consume(OpenAiChatSseParser.parseData(data.toString()))
        }
    }

    private companion object {
        const val HERMES_SESSION_ID_HEADER = "X-Hermes-Session-Id"
        const val MAX_HERMES_SESSION_ID_CHARS = 256
    }
}

internal class OpenAiCompatHttpException(
    val statusCode: Int,
    message: String,
) : IllegalStateException(message)

internal class OpenAiToolCallAccumulator {
    private data class PartialCall(
        var id: String? = null,
        val name: StringBuilder = StringBuilder(),
        val arguments: StringBuilder = StringBuilder(),
    )

    private val partialCalls = sortedMapOf<Int, PartialCall>()

    fun append(deltas: List<OpenAiChatToolCallDelta>) {
        deltas.forEach { delta ->
            val partial = partialCalls.getOrPut(delta.index, ::PartialCall)
            if (partial.id.isNullOrBlank() && !delta.id.isNullOrBlank()) {
                partial.id = delta.id
            }
            delta.nameFragment?.let { fragment -> partial.name.append(fragment) }
            delta.argumentsFragment?.let { fragment -> partial.arguments.append(fragment) }
        }
    }

    fun completeCalls(): List<AssistantToolCall> = partialCalls.map { (index, partial) ->
        AssistantToolCall(
            callId = partial.id?.takeIf(String::isNotBlank) ?: "call_$index",
            name = partial.name.toString(),
            argumentsJson = partial.arguments.toString(),
        )
    }
}

private data class OpenAiCompatPassResult(
    val text: String,
    val toolCalls: List<AssistantToolCall>,
    val textToolPayload: String?,
)

/** Keeps a private control line off the HUD even when SSE splits it across deltas. */
private class ControlLineStreamFilter(
    private val token: String,
) {
    private val pending = StringBuilder()
    private var passThrough = false

    fun filter(delta: String): String {
        if (passThrough) return delta
        pending.append(delta)
        val candidate = pending.toString().trimStart()
        if (token.startsWith(candidate) || candidate.startsWith(token)) return ""
        passThrough = true
        return pending.toString().also { pending.clear() }
    }

    fun finish(): ControlLineFilterResult {
        if (passThrough) return ControlLineFilterResult(text = "", payload = null)
        val remaining = pending.toString()
        pending.clear()
        val candidate = remaining.trimStart()
        return if (candidate.startsWith(token)) {
            ControlLineFilterResult(
                text = "",
                payload = candidate.removePrefix(token).trim(),
            )
        } else {
            ControlLineFilterResult(text = remaining, payload = null)
        }
    }
}

private data class ControlLineFilterResult(
    val text: String,
    val payload: String?,
)

internal class OpenAiCompatProvider(
    private val preset: ProviderPreset,
    private val apiClient: OpenAiCompatChatClient,
    private val apiKeyConfigured: () -> Boolean,
    private val toolRegistry: AssistantToolRegistry,
    private val modelProvider: () -> String = { preset.defaultModel },
    private val supportsVision: () -> Boolean,
    private val backendProvider: () -> ProviderBackend = { preset.backend },
) : AiProvider {
    override val id: String = preset.id
    override val displayName: String = preset.displayName

    override fun streamEvents(request: ChatRequest): Flow<AiProviderEvent> = flow {
        val messageId = UUID.randomUUID().toString()
        emit(AiProviderEvent.Started(messageId))
        if (!apiKeyConfigured()) {
            emit(AiProviderEvent.Failed("${preset.displayName} API key is not configured."))
            return@flow
        }

        try {
            val visionSupported = supportsVision()
            val useTextToolBridge = backendProvider() == ProviderBackend.HERMES
            val toolPhase = toolRegistry.newExecutionPhase(
                AssistantProviderFeatures(
                    supportsTools = true,
                    supportsVision = visionSupported,
                    supportsWorkspaceSearch = !useTextToolBridge,
                ),
                request.workspaceVersion,
            )
            val textToolDefinitions = toolPhase.availableDefinitions.filter { definition ->
                definition.name in HERMES_TEXT_TOOL_NAMES
            }
            val textToolNames = textToolDefinitions.mapTo(mutableSetOf()) { definition ->
                definition.name
            }
            val effectiveRequest = request.forVisionSupport(visionSupported)
            val modelId = request.model ?: modelProvider()
            val originalMessages = effectiveRequest.toChatCompletionMessages()

            suspend fun streamPass(
                messages: JSONArray,
                toolDefinitions: List<AssistantToolDefinition>,
            ): OpenAiCompatPassResult {
                val response = StringBuilder()
                val toolCalls = OpenAiToolCallAccumulator()
                val thinkTagFilter = ThinkTagStreamFilter()
                val textToolFilter = if (useTextToolBridge) {
                    ControlLineStreamFilter(COMPAT_TEXT_TOOL_REQUEST_TOKEN)
                } else {
                    null
                }

                suspend fun appendVisible(delta: String) {
                    val visible = textToolFilter?.filter(delta) ?: delta
                    if (visible.isEmpty()) return
                    response.append(visible)
                    emit(AiProviderEvent.TextDelta(messageId, visible))
                }

                effectiveRequest.beforeSend?.invoke()
                apiClient.streamChat(
                    OpenAiCompatChatRequest(
                        request = effectiveRequest,
                        modelId = modelId,
                        messages = messages,
                        toolDefinitions = toolDefinitions,
                        requestId = request.requestId,
                    ),
                ).collect { event ->
                    when (event) {
                        is OpenAiChatSseEvent.Delta -> {
                            toolCalls.append(event.toolCalls)
                            val content = event.content.orEmpty()
                            if (content.isEmpty()) return@collect
                            val filteredDelta = thinkTagFilter.filter(content)
                            if (filteredDelta.isEmpty()) return@collect
                            appendVisible(filteredDelta)
                        }
                        is OpenAiChatSseEvent.Error ->
                            throw IllegalStateException(event.message)
                        OpenAiChatSseEvent.Done,
                        OpenAiChatSseEvent.Ignored,
                        -> Unit
                    }
                }
                val remaining = thinkTagFilter.finish()
                if (remaining.isNotEmpty()) appendVisible(remaining)
                val controlResult = textToolFilter?.finish()
                    ?: ControlLineFilterResult(text = "", payload = null)
                if (controlResult.text.isNotEmpty()) {
                    response.append(controlResult.text)
                    emit(AiProviderEvent.TextDelta(messageId, controlResult.text))
                }
                return OpenAiCompatPassResult(
                    text = cleanCompatResponse(response.toString()),
                    toolCalls = toolCalls.completeCalls(),
                    textToolPayload = controlResult.payload,
                )
            }

            val firstPassToolDefinitions = if (useTextToolBridge) {
                emptyList()
            } else {
                toolPhase.availableDefinitions
            }
            val firstPass = try {
                streamPass(originalMessages, toolDefinitions = firstPassToolDefinitions)
            } catch (error: OpenAiCompatHttpException) {
                if (
                    firstPassToolDefinitions.isEmpty() ||
                    error.statusCode !in 400..499
                ) {
                    throw error
                }
                streamPass(originalMessages, toolDefinitions = emptyList())
            }
            currentCoroutineContext().ensureActive()

            val finalText = if (!useTextToolBridge && firstPass.toolCalls.isNotEmpty()) {
                emit(AiProviderEvent.TextReset(messageId))
                val replayMessages = originalMessages.copyJsonArray()
                replayMessages.put(assistantToolCallMessage(firstPass.text, firstPass.toolCalls))
                var capturedPhoto: PhotoAttachment? = null
                firstPass.toolCalls.forEach { call ->
                    val result = toolPhase.execute(call)
                    currentCoroutineContext().ensureActive()
                    if (result is AssistantToolResult.Image) {
                        capturedPhoto = PhotoAttachment(result.mimeType, result.base64)
                    }
                    replayMessages.put(toolResultMessage(call, result))
                }
                capturedPhoto?.let { photo ->
                    replayMessages.put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "content",
                                chatCompletionContent(
                                    PHOTO_TAKEN_MESSAGE,
                                    listOf(photo),
                                ),
                            ),
                    )
                }
                streamPass(replayMessages, toolDefinitions = emptyList()).text
            } else if (useTextToolBridge && firstPass.textToolPayload != null) {
                emit(AiProviderEvent.TextReset(messageId))
                val replayMessages = originalMessages.copyJsonArray()
                val calls = parseTextToolCalls(firstPass.textToolPayload, textToolNames)
                if (calls == null) {
                    replayMessages.put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", TEXT_TOOL_CALL_MALFORMED_MESSAGE),
                    )
                } else {
                    // A backend without structured tool calls has no reason to accept the
                    // structured transcript either, so the results ride back as plain text.
                    var capturedPhoto: PhotoAttachment? = null
                    val results = StringBuilder(TEXT_TOOL_RESULTS_MESSAGE)
                    calls.forEach { call ->
                        val result = toolPhase.execute(call)
                        currentCoroutineContext().ensureActive()
                        if (result is AssistantToolResult.Image) {
                            capturedPhoto = PhotoAttachment(result.mimeType, result.base64)
                        }
                        results.append("\n- ").append(textToolResultLine(call, result))
                    }
                    replayMessages.put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", results.toString()),
                    )
                    capturedPhoto?.let { photo ->
                        replayMessages.put(
                            JSONObject()
                                .put("role", "user")
                                .put(
                                    "content",
                                    chatCompletionContent(
                                        PHOTO_TAKEN_MESSAGE,
                                        listOf(photo),
                                    ),
                                ),
                        )
                    }
                }
                val finalPass = streamPass(replayMessages, toolDefinitions = emptyList())
                if (finalPass.textToolPayload != null) {
                    TEXT_TOOL_RESULT_COULD_NOT_BE_USED_MESSAGE
                } else {
                    finalPass.text
                }
            } else {
                firstPass.text
            }

            emit(
                AiProviderEvent.MessageDone(
                    ChatMessage(
                        id = messageId,
                        role = "assistant",
                        content = finalText,
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val detail = error.conciseProviderMessage("Request failed.")
            val message = if (detail.contains(preset.displayName, ignoreCase = true)) {
                detail
            } else {
                "${preset.displayName} request failed: $detail".take(240)
            }
            emit(
                AiProviderEvent.Failed(message),
            )
            return@flow
        }
    }

    override suspend fun cancel(requestId: String) {
        apiClient.cancel(requestId)
    }
}

private fun assistantToolCallMessage(
    text: String,
    calls: List<AssistantToolCall>,
): JSONObject =
    JSONObject()
        .put("role", "assistant")
        .put("content", text)
        .put(
            "tool_calls",
            JSONArray().apply {
                calls.forEach { call ->
                    put(
                        JSONObject()
                            .put("id", call.callId)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("arguments", call.argumentsJson),
                            ),
                    )
                }
            },
        )

private fun parseTextToolCalls(
    payload: String,
    allowedNames: Set<String>,
): List<AssistantToolCall>? = runCatching {
    val tokener = JSONTokener(payload)
    val value = tokener.nextValue()
    require(tokener.nextClean() == '\u0000')
    val objects = when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> {
            require(value.length() > 0)
            (0 until value.length()).map { index -> value.get(index) as JSONObject }
        }
        else -> error("Text tool payload must be an object or array.")
    }
    objects.mapIndexed { index, item ->
        val name = item.opt("name") as? String
            ?: error("Text tool name must be a string.")
        require(name in allowedNames)
        // A tool that takes nothing is usually called without an arguments key at all.
        val arguments = when (val raw = item.opt("arguments")) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> raw
            else -> error("Text tool arguments must be an object.")
        }
        AssistantToolCall(
            callId = "text_tool_$index",
            name = name,
            argumentsJson = arguments.toString(),
        )
    }
}.getOrNull()

private fun toolResultMessage(
    call: AssistantToolCall,
    result: AssistantToolResult,
): JSONObject {
    val content = when (result) {
        is AssistantToolResult.Json -> result.text
        is AssistantToolResult.Image ->
            JSONObject()
                .put("status", "captured")
                .put("note", "The photo is attached to the next user message.")
        is AssistantToolResult.Error ->
            JSONObject()
                .put("status", "error")
                .put("code", result.code)
                .apply {
                    result.detailsJson?.let { detailsJson ->
                        val details = JSONObject(detailsJson)
                        val keys = details.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, details.get(key))
                        }
                    }
                }
    }
    return JSONObject()
        .put("role", "tool")
        .put("tool_call_id", call.callId)
        .put("content", content.toString())
}

private fun textToolResultLine(
    call: AssistantToolCall,
    result: AssistantToolResult,
): String = when (result) {
    is AssistantToolResult.Error -> "${call.name}: failed with error code ${result.code}."
    is AssistantToolResult.Image -> "${call.name}: the photo is attached to the next message."
    is AssistantToolResult.Json -> "${call.name}: ${result.text}"
}

private fun JSONArray.copyJsonArray(): JSONArray = JSONArray().also { copy ->
    for (index in 0 until length()) copy.put(get(index))
}

private fun cleanCompatResponse(response: String): String =
    response.replace(INLINE_THINK_BLOCKS, "").trim()

internal fun ChatRequest.forVisionSupport(supportsVision: Boolean): ChatRequest {
    if (supportsVision) return this
    val hadCurrentPhoto = photos.any { photo -> !photo.isOmittedHistoryPhoto() }
    val textOnlyUserText = if (hadCurrentPhoto) {
        listOf(userText, PHOTO_UNSUPPORTED_NOTE)
            .filter(String::isNotBlank)
            .joinToString("\n\n")
    } else {
        userText
    }
    return copy(
        userText = textOnlyUserText,
        history = history.map { message -> message.copy(photos = emptyList()) },
        photos = emptyList(),
    )
}

internal const val PHOTO_UNSUPPORTED_NOTE =
    "[A photo was attached, but the selected model cannot view images.]"

private val INLINE_THINK_BLOCKS =
    Regex("<think>.*?</think>|<think>.*$", RegexOption.DOT_MATCHES_ALL)

private const val PHOTO_TAKEN_MESSAGE =
    "Photo just taken through the glasses camera for the current question. " +
        "The image is attached now; do not request another photo."

private const val TEXT_TOOL_RESULTS_MESSAGE =
    "Nexus ran the phone tools you asked for and these are their results. Answer the wearer " +
        "from them, and do not send another control line for this question."

private const val TEXT_TOOL_CALL_MALFORMED_MESSAGE =
    "The Nexus phone tool call was malformed. Do not claim that any requested action succeeded; " +
        "tell the wearer the tool request could not be completed."

private const val TEXT_TOOL_RESULT_COULD_NOT_BE_USED_MESSAGE =
    "The selected model could not use the Nexus phone tool result."
