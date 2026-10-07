package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal sealed interface ChatGptCodexSseEvent {
    data class TextDelta(val text: String) : ChatGptCodexSseEvent
    data class OutputItemAdded(val item: JSONObject) : ChatGptCodexSseEvent
    data class OutputItemDone(val item: JSONObject) : ChatGptCodexSseEvent
    data class WebSearchStarted(val itemId: String) : ChatGptCodexSseEvent
    data class WebSearchCompleted(val itemId: String) : ChatGptCodexSseEvent
    data class StreamError(
        val message: String,
        val type: String,
        val code: String,
        val isTransient: Boolean,
    ) : ChatGptCodexSseEvent
    data object Completed : ChatGptCodexSseEvent
    data object Ignored : ChatGptCodexSseEvent
}

internal object ChatGptCodexSseParser {
    fun parseData(payload: String): ChatGptCodexSseEvent {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return ChatGptCodexSseEvent.Ignored
        if (trimmed == "[DONE]") return ChatGptCodexSseEvent.Completed

        val json = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return ChatGptCodexSseEvent.Ignored
        return when (json.optString("type")) {
            "response.output_text.delta" -> {
                val delta = json.optString("delta")
                if (delta.isEmpty()) {
                    ChatGptCodexSseEvent.Ignored
                } else {
                    ChatGptCodexSseEvent.TextDelta(delta)
                }
            }
            "response.completed" -> ChatGptCodexSseEvent.Completed
            "response.output_item.added" ->
                json.optJSONObject("item")
                    ?.let { item -> ChatGptCodexSseEvent.OutputItemAdded(item) }
                    ?: ChatGptCodexSseEvent.Ignored
            "response.output_item.done" ->
                json.optJSONObject("item")
                    ?.let { item -> ChatGptCodexSseEvent.OutputItemDone(item) }
                    ?: ChatGptCodexSseEvent.Ignored
            "response.web_search_call.in_progress",
            "response.web_search_call.searching",
            -> json.webSearchItemId()
                ?.let { itemId -> ChatGptCodexSseEvent.WebSearchStarted(itemId) }
                ?: ChatGptCodexSseEvent.Ignored
            "response.web_search_call.completed" -> json.webSearchItemId()
                ?.let { itemId -> ChatGptCodexSseEvent.WebSearchCompleted(itemId) }
                ?: ChatGptCodexSseEvent.Ignored
            "response.failed", "error" -> streamError(json)
            else -> ChatGptCodexSseEvent.Ignored
        }
    }

    fun parseLine(line: String): ChatGptCodexSseEvent {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return ChatGptCodexSseEvent.Ignored
        return parseData(trimmed.removePrefix("data:").trim())
    }

    private fun streamError(json: JSONObject): ChatGptCodexSseEvent.StreamError {
        val responseError = json.optJSONObject("response")?.optJSONObject("error")
        val directError = json.optJSONObject("error")
        val error = responseError ?: directError
        val type = error?.optString("type").orEmpty().trim()
        val code = error?.optString("code").orEmpty().trim()
        val message = sequenceOf(
            responseError?.optString("message"),
            directError?.optString("message"),
            json.optString("message"),
        )
            .filterNotNull()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
            ?: "ChatGPT Codex response failed."
        return ChatGptCodexSseEvent.StreamError(
            message = message,
            type = type,
            code = code,
            isTransient = type in TRANSIENT_ERROR_MARKERS ||
                code in TRANSIENT_ERROR_MARKERS,
        )
    }

    private val TRANSIENT_ERROR_MARKERS = setOf(
        "server_error",
        "service_unavailable_error",
        "server_is_overloaded",
    )

    private fun JSONObject.webSearchItemId(): String? =
        optString("item_id").takeIf(String::isNotBlank)
}

internal data class ChatGptCodexHttpRequest(
    val endpoint: String,
    val headers: Map<String, String>,
    val body: String,
)

internal data class ChatGptCodexHttpResponse(
    val statusCode: Int,
    val errorBody: String = "",
)

internal data class ChatGptCodexStreamResult(
    val textDeltas: List<String>,
    val outputItems: List<JSONObject>,
)

internal interface ChatGptCodexHttpTransport {
    suspend fun execute(
        requestId: String,
        request: ChatGptCodexHttpRequest,
        consumeData: suspend (String) -> Boolean,
    ): ChatGptCodexHttpResponse

    fun cancel(requestId: String)
}

internal class HttpUrlConnectionChatGptCodexTransport : ChatGptCodexHttpTransport {
    private val activeConnections = ConcurrentHashMap<String, HttpURLConnection>()

    override suspend fun execute(
        requestId: String,
        request: ChatGptCodexHttpRequest,
        consumeData: suspend (String) -> Boolean,
    ): ChatGptCodexHttpResponse {
        val connection = (URL(request.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        activeConnections.put(requestId, connection)?.disconnect()
        try {
            connection.outputStream.use { output ->
                output.write(request.body.toByteArray(Charsets.UTF_8))
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                return ChatGptCodexHttpResponse(
                    statusCode = status,
                    errorBody = connection.safeErrorBody(),
                )
            }
            readSse(connection, consumeData)
            return ChatGptCodexHttpResponse(statusCode = status)
        } finally {
            activeConnections.remove(requestId, connection)
            connection.disconnect()
        }
    }

    override fun cancel(requestId: String) {
        activeConnections.remove(requestId)?.disconnect()
    }

    private suspend fun readSse(
        connection: HttpURLConnection,
        consumeData: suspend (String) -> Boolean,
    ) {
        BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
            val data = StringBuilder()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = reader.readLine() ?: break
                when {
                    line.isBlank() -> {
                        if (data.isNotEmpty()) {
                            val keepReading = consumeData(data.toString())
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
            if (data.isNotEmpty()) consumeData(data.toString())
        }
    }
}

internal class ChatGptCodexApiClient(
    private val tokenProvider: () -> CodexChatGptOAuthTokenBundle?,
    private val refreshTokens: suspend () -> CodexChatGptOAuthTokenBundle,
    private val transport: ChatGptCodexHttpTransport = HttpUrlConnectionChatGptCodexTransport(),
    private val endpointProvider: () -> String = { DEFAULT_ENDPOINT },
    private val sessionId: String = UUID.randomUUID().toString(),
) {
    fun streamResponses(
        request: ChatRequest,
        modelId: String,
        reasoningEffort: String = DEFAULT_REASONING_EFFORT,
        toolDefinitions: List<AssistantToolDefinition> = emptyList(),
        requestId: String = request.requestId,
    ): Flow<String> = flow {
        executeResponses(
            request = request,
            modelId = modelId,
            reasoningEffort = reasoningEffort,
            input = request.toCodexResponsesInput(),
            toolDefinitions = toolDefinitions,
            requestId = requestId,
        ).textDeltas.forEach { delta -> emit(delta) }
    }

    suspend fun executeResponses(
        request: ChatRequest,
        modelId: String,
        reasoningEffort: String = DEFAULT_REASONING_EFFORT,
        input: JSONArray,
        toolDefinitions: List<AssistantToolDefinition>,
        requestId: String = request.requestId,
        onTextDelta: suspend (String) -> Unit = {},
        onStreamRestart: suspend () -> Unit = {},
        onWebSearchStateChanged: suspend (Boolean) -> Unit = {},
    ): ChatGptCodexStreamResult {
        var tokens = tokenProvider()
            ?: throw IllegalStateException("No ChatGPT sign-in is stored. Sign in again.")
        var refreshedAfterUnauthorized = false
        var retriedTransientStreamFailure = false

        while (true) {
            currentCoroutineContext().ensureActive()
            val textDeltas = mutableListOf<String>()
            val outputItems = mutableListOf<JSONObject>()
            var streamError: ChatGptCodexSseEvent.StreamError? = null
            var completed = false
            val activeWebSearchItemIds = mutableSetOf<String>()
            var webSearchReported = false

            suspend fun reportWebSearch(searching: Boolean) {
                if (webSearchReported == searching) return
                webSearchReported = searching
                onWebSearchStateChanged(searching)
            }

            suspend fun startWebSearch(itemId: String) {
                if (activeWebSearchItemIds.add(itemId) && activeWebSearchItemIds.size == 1) {
                    reportWebSearch(true)
                }
            }

            suspend fun completeWebSearch(itemId: String) {
                if (activeWebSearchItemIds.remove(itemId) && activeWebSearchItemIds.isEmpty()) {
                    reportWebSearch(false)
                }
            }

            suspend fun clearWebSearches() {
                if (activeWebSearchItemIds.isEmpty()) return
                activeWebSearchItemIds.clear()
                reportWebSearch(false)
            }

            val httpRequest = buildHttpRequest(
                request = request,
                modelId = supportedModel(modelId),
                reasoningEffort = supportedReasoningEffort(reasoningEffort),
                input = input,
                toolDefinitions = toolDefinitions,
                tokens = tokens,
            )
            val response = try {
                withContext(Dispatchers.IO) {
                    transport.execute(requestId, httpRequest) { payload ->
                        when (val event = ChatGptCodexSseParser.parseData(payload)) {
                            is ChatGptCodexSseEvent.TextDelta -> {
                                textDeltas += event.text
                                onTextDelta(event.text)
                                true
                            }
                            is ChatGptCodexSseEvent.OutputItemAdded -> {
                                if (event.item.isWebSearchCall()) {
                                    event.item.itemId()?.let { itemId -> startWebSearch(itemId) }
                                }
                                true
                            }
                            is ChatGptCodexSseEvent.OutputItemDone -> {
                                if (event.item.isWebSearchCall()) {
                                    event.item.itemId()?.let { itemId -> completeWebSearch(itemId) }
                                }
                                outputItems += event.item
                                true
                            }
                            is ChatGptCodexSseEvent.WebSearchStarted -> {
                                startWebSearch(event.itemId)
                                true
                            }
                            is ChatGptCodexSseEvent.WebSearchCompleted -> {
                                completeWebSearch(event.itemId)
                                true
                            }
                            is ChatGptCodexSseEvent.StreamError -> {
                                streamError = event
                                false
                            }
                            ChatGptCodexSseEvent.Completed -> {
                                clearWebSearches()
                                completed = true
                                false
                            }
                            ChatGptCodexSseEvent.Ignored -> true
                        }
                    }
                }
            } finally {
                clearWebSearches()
            }

            if (
                response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED &&
                !refreshedAfterUnauthorized
            ) {
                tokens = try {
                    refreshTokens()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    throw IllegalStateException(
                        "ChatGPT session expired (401). Sign in with ChatGPT again.",
                    )
                }
                refreshedAfterUnauthorized = true
                textDeltas.clear()
                outputItems.clear()
                onStreamRestart()
                continue
            }

            if (response.statusCode !in 200..299) {
                throw IllegalStateException(httpFailureMessage(response))
            }

            streamError?.let { error ->
                if (error.isTransient && !retriedTransientStreamFailure) {
                    retriedTransientStreamFailure = true
                    textDeltas.clear()
                    outputItems.clear()
                    onStreamRestart()
                    continue
                }
                throw IllegalStateException(error.message)
            }

            if (!completed) {
                throw IllegalStateException("ChatGPT Codex response ended unexpectedly.")
            }
            return ChatGptCodexStreamResult(
                textDeltas = textDeltas,
                outputItems = outputItems,
            )
        }
    }

    fun cancel(requestId: String) {
        transport.cancel(requestId)
    }

    private fun buildHttpRequest(
        request: ChatRequest,
        modelId: String,
        reasoningEffort: String,
        input: JSONArray,
        toolDefinitions: List<AssistantToolDefinition>,
        tokens: CodexChatGptOAuthTokenBundle,
    ): ChatGptCodexHttpRequest {
        require(tokens.accessToken.isNotBlank()) { "Stored ChatGPT access token is blank." }
        require(tokens.accountId.isNotBlank()) { "Stored ChatGPT account ID is blank." }
        val body = JSONObject()
            .put("model", modelId)
            .put("instructions", request.systemPrompt?.trim().orEmpty())
            .put("input", input)
            .put("tools", codexTools(toolDefinitions))
            .put("tool_choice", "auto")
            .put("parallel_tool_calls", false)
            .put("store", false)
            .put("stream", true)
            .put("include", JSONArray())
            .put(
                "reasoning",
                JSONObject()
                    .put("effort", reasoningEffort)
                    .put("summary", "auto"),
            )
        return ChatGptCodexHttpRequest(
            endpoint = endpointProvider().trim().ifBlank { DEFAULT_ENDPOINT },
            headers = linkedMapOf(
                "Authorization" to "Bearer ${tokens.accessToken}",
                "chatgpt-account-id" to tokens.accountId,
                "OpenAI-Beta" to "responses=experimental",
                "originator" to "codex_cli_rs",
                "session_id" to sessionId,
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream",
            ),
            body = body.toString(),
        )
    }

    private fun httpFailureMessage(response: ChatGptCodexHttpResponse): String {
        val body = response.errorBody
            .redactProviderSecrets()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(480)
            .ifBlank { "no error body" }
        return if (response.statusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            "ChatGPT session expired (401). Sign in with ChatGPT again. Server: $body"
        } else {
            "ChatGPT Codex request failed (${response.statusCode}): $body"
        }
    }

    private fun codexTools(toolDefinitions: List<AssistantToolDefinition>): JSONArray =
        JSONArray()
            .put(JSONObject().put("type", "web_search"))
            .apply {
                toolDefinitions.forEach { definition ->
                    put(codexToolDeclaration(definition))
                }
            }

    private fun codexToolDeclaration(definition: AssistantToolDefinition): JSONObject =
        JSONObject()
            .put("type", "function")
            .put("name", definition.name)
            .put("description", definition.description)
            .put("parameters", definition.parametersSchema.toJsonObject())
            .put("strict", true)

    companion object {
        const val DEFAULT_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses"
        const val FAST_MODEL_ID = "gpt-5.6-luna"
        const val BALANCED_MODEL_ID = "gpt-5.6-terra"
        const val DEEP_MODEL_ID = "gpt-5.6-sol"

        // Luna leads: the glasses are a voice surface where the answer has to land
        // before the wearer gives up on it. Measured against the same question with
        // web search, luna answers in ~3.4 s where sol takes ~5.3 s. Sol is one tap
        // away in settings when a question deserves the wait.
        const val DEFAULT_MODEL_ID = FAST_MODEL_ID

        // Measured against the private backend: these three ids are the whole GPT-5.6
        // family a ChatGPT account may use. Everything else -- older gpt-5 ids, mini or
        // max variants, codex-mini-latest -- comes back "not supported when using Codex
        // with a ChatGPT account".
        val SUPPORTED_MODEL_IDS = listOf(FAST_MODEL_ID, BALANCED_MODEL_ID, DEEP_MODEL_ID)
        // "none" measured ~2x faster than "low" on web-searched questions
        // (8.6s vs 14.4s end to end) and answer quality holds for voice Q&A.
        const val DEFAULT_REASONING_EFFORT = "none"
        val SUPPORTED_REASONING_EFFORTS = setOf("none", "low", "medium", "high", "xhigh")

        fun supportedModel(modelId: String): String =
            modelId.trim().takeIf(SUPPORTED_MODEL_IDS::contains) ?: DEFAULT_MODEL_ID

        fun supportedReasoningEffort(reasoningEffort: String): String =
            reasoningEffort.takeIf(SUPPORTED_REASONING_EFFORTS::contains)
                ?: DEFAULT_REASONING_EFFORT
    }
}

internal class ChatGptCodexProvider(
    private val apiClient: ChatGptCodexApiClient,
    private val oauthConfigured: () -> Boolean,
    private val toolRegistry: AssistantToolRegistry,
    private val modelProvider: () -> String = { ChatGptCodexApiClient.DEFAULT_MODEL_ID },
    private val reasoningEffortProvider: () -> String = {
        ChatGptCodexApiClient.DEFAULT_REASONING_EFFORT
    },
) : AiProvider {
    override val id: String = ID
    override val displayName: String = "ChatGPT"

    override fun streamEvents(request: ChatRequest): Flow<AiProviderEvent> = channelFlow {
        val messageId = UUID.randomUUID().toString()
        send(AiProviderEvent.Started(messageId))
        if (!oauthConfigured()) {
            send(AiProviderEvent.Failed("Sign in with ChatGPT in settings."))
            return@channelFlow
        }

        try {
            val toolPhase = toolRegistry.newExecutionPhase(CODEX_PROVIDER_FEATURES, request.workspaceVersion)
            val originalInput = request.toCodexResponsesInput()
            val modelId = request.model ?: modelProvider()
            val reasoningEffort = reasoningEffortProvider()
            val response = StringBuilder()

            suspend fun streamDelta(delta: String) {
                if (delta.isEmpty()) return
                response.append(delta)
                send(AiProviderEvent.TextDelta(messageId, delta))
            }

            suspend fun resetStreamedText() {
                response.clear()
                send(AiProviderEvent.TextReset(messageId))
            }

            suspend fun streamWebSearchState(searching: Boolean) {
                send(
                    AiProviderEvent.Progress(
                        messageId = messageId,
                        message = if (searching) SEARCHING_WEB_LABEL else THINKING_LABEL,
                    ),
                )
            }

            val firstResponse = apiClient.executeResponses(
                request = request,
                modelId = modelId,
                reasoningEffort = reasoningEffort,
                input = originalInput,
                toolDefinitions = toolPhase.availableDefinitions,
                requestId = request.requestId,
                onTextDelta = ::streamDelta,
                onStreamRestart = ::resetStreamedText,
                onWebSearchStateChanged = ::streamWebSearchState,
            )
            currentCoroutineContext().ensureActive()

            val functionCalls = firstResponse.outputItems
                .mapNotNull(::parseFunctionCall)
            if (functionCalls.isNotEmpty()) {
                resetStreamedText()
                val replayInput = JSONArray()
                originalInput.forEachJsonValue(replayInput::put)
                functionCalls.forEach { call ->
                    val result = toolPhase.execute(call)
                    currentCoroutineContext().ensureActive()
                    replayInput.put(functionCallReplay(call))
                    replayInput.put(functionCallOutput(call, result))
                }
                apiClient.executeResponses(
                    request = request,
                    modelId = modelId,
                    reasoningEffort = reasoningEffort,
                    input = replayInput,
                    toolDefinitions = emptyList(),
                    requestId = request.requestId,
                    onTextDelta = ::streamDelta,
                    onStreamRestart = ::resetStreamedText,
                    onWebSearchStateChanged = ::streamWebSearchState,
                )
            }

            send(
                AiProviderEvent.MessageDone(
                    ChatMessage(
                        id = messageId,
                        role = "assistant",
                        content = response.toString(),
                    ),
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            send(
                AiProviderEvent.Failed(
                    error.conciseProviderMessage("ChatGPT Codex request failed."),
                ),
            )
            return@channelFlow
        }
    }.buffer(capacity = 0)

    override suspend fun cancel(requestId: String) {
        apiClient.cancel(requestId)
    }

    companion object {
        const val ID = "chatgpt_codex"
    }
}

internal fun parseFunctionCall(item: JSONObject): AssistantToolCall? {
    if (item.optString("type") != "function_call") return null
    val callId = item.optString("call_id")
    if (callId.isBlank()) return null
    return AssistantToolCall(
        callId = callId,
        name = item.optString("name"),
        argumentsJson = item.optString("arguments"),
    )
}

internal fun functionCallReplay(call: AssistantToolCall): JSONObject =
    JSONObject()
        .put("type", "function_call")
        .put("name", call.name)
        .put("arguments", call.argumentsJson)
        .put("call_id", call.callId)

internal fun functionCallOutput(
    call: AssistantToolCall,
    result: AssistantToolResult,
): JSONObject {
    val output = when (result) {
        is AssistantToolResult.Json -> result.text
        is AssistantToolResult.Image ->
            JSONArray().put(
                JSONObject()
                    .put("type", "input_image")
                    .put(
                        "image_url",
                        "data:${result.mimeType};base64,${result.base64}",
                    )
                    .put("detail", "high"),
            )
        is AssistantToolResult.Error ->
            assistantToolErrorJson(result)
    }
    return JSONObject()
        .put("type", "function_call_output")
        .put("call_id", call.callId)
        .put("output", output)
}

// Hand-built so the key order is stable on every org.json implementation
// (Android preserves insertion order, the JVM artifact does not). detailsJson is
// validated at construction to be a JSON object without "ok"/"code" keys, so its
// interior can be spliced verbatim.
internal fun assistantToolErrorJson(result: AssistantToolResult.Error): String =
    buildString {
        append("{\"ok\":false,\"code\":")
        append(JSONObject.quote(result.code))
        result.detailsJson?.let { detailsJson ->
            val interior = JSONObject(detailsJson).toString()
                .removePrefix("{")
                .removeSuffix("}")
            if (interior.isNotEmpty()) {
                append(',')
                append(interior)
            }
        }
        append('}')
    }

internal fun ChatRequest.toCodexResponsesInput(): JSONArray {
    val input = JSONArray()
    history
        .filter { it.role in CODEX_MESSAGE_ROLES }
        .filter { it.content.isNotBlank() || it.photos.isNotEmpty() }
        .takeLast(MAX_CODEX_HISTORY_MESSAGES)
        .forEach { message ->
            val replayedPhotos = message.photos.filterNot(
                PhotoAttachment::isOmittedHistoryPhoto,
            )
            val historyText = buildString {
                append(message.content)
                if (message.photos.isNotEmpty()) {
                    if (isNotBlank()) append('\n')
                    append(PHOTO_HISTORY_MARKER)
                }
            }
            input.put(codexMessage(message.role, historyText, replayedPhotos))
        }
    input.put(codexMessage("user", userText, photos))
    return input
}

private fun codexMessage(
    role: String,
    text: String,
    photos: List<PhotoAttachment>,
): JSONObject {
    // The Responses API types content by direction: an assistant turn replays
    // as output_text and cannot carry input_image; everything else is input.
    val isAssistant = role == "assistant"
    val content = JSONArray()
    val inputText = text.takeIf(String::isNotBlank)
        ?: if (photos.isNotEmpty() && !isAssistant) "Use the attached image." else ""
    content.put(
        JSONObject()
            .put("type", if (isAssistant) "output_text" else "input_text")
            .put("text", inputText),
    )
    if (!isAssistant) {
        photos.forEach { photo ->
            content.put(
                JSONObject()
                    .put("type", "input_image")
                    .put("image_url", "data:${photo.mimeType};base64,${photo.base64}")
                    .put("detail", "high"),
            )
        }
    }
    return JSONObject()
        .put("type", "message")
        .put("role", role)
        .put("content", content)
}

private val CODEX_MESSAGE_ROLES = setOf("system", "developer", "user", "assistant")
private const val MAX_CODEX_HISTORY_MESSAGES = 24
private const val PHOTO_HISTORY_MARKER = "[photo]"

private val CODEX_PROVIDER_FEATURES = AssistantProviderFeatures(
    supportsTools = true,
    supportsVision = true,
)

private const val SEARCHING_WEB_LABEL = "Searching the web…"
private const val THINKING_LABEL = "Thinking…"

private fun JSONObject.isWebSearchCall(): Boolean = optString("type") == "web_search_call"

private fun JSONObject.itemId(): String? = optString("id").takeIf(String::isNotBlank)

private inline fun JSONArray.forEachJsonValue(block: (Any) -> Unit) {
    for (index in 0 until length()) block(get(index))
}
