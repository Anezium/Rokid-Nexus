package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PhotoAttachment(
    val mimeType: String,
    val base64: String,
) {
    init {
        require(mimeType.startsWith("image/")) { "Photo MIME type must be an image." }
        require(base64.isNotBlank()) { "Photo data is blank." }
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val photos: List<PhotoAttachment> = emptyList(),
)

data class ChatRequest(
    val userText: String,
    val systemPrompt: String? = null,
    val history: List<ChatMessage> = emptyList(),
    val photos: List<PhotoAttachment> = emptyList(),
    val model: String? = null,
    val requestId: String = UUID.randomUUID().toString(),
    val conversationId: String? = null,
    val workspaceVersion: Pair<Long, Long>? = null,
    val workspaceTurn: WorkspaceTurnAccess? = null,
    val beforeSend: (() -> Unit)? = null,
)

sealed interface AiProviderEvent {
    data class Started(val messageId: String) : AiProviderEvent
    data class Progress(val messageId: String, val message: String) : AiProviderEvent
    data class TextReset(val messageId: String) : AiProviderEvent
    data class TextDelta(val messageId: String, val delta: String) : AiProviderEvent
    data class MessageDone(val message: ChatMessage) : AiProviderEvent
    data class Failed(val message: String) : AiProviderEvent
}

interface AiProvider {
    val id: String
    val displayName: String

    fun streamEvents(request: ChatRequest): Flow<AiProviderEvent>
    suspend fun cancel(requestId: String)
}

class ProviderRouter(
    providers: List<AiProvider>,
    private val defaultProviderId: String,
) {
    private val byId = providers.associateBy(AiProvider::id)

    init {
        require(providers.isNotEmpty()) { "At least one AI provider is required." }
        require(byId.size == providers.size) { "AI provider IDs must be unique." }
        require(defaultProviderId in byId) { "Default AI provider is not registered." }
    }

    fun providerFor(providerId: String = defaultProviderId): AiProvider =
        byId[providerId] ?: error("No provider registered for $providerId")
}

internal fun ChatRequest.toChatCompletionMessages(): JSONArray {
    val messages = JSONArray()
    systemPrompt
        ?.takeIf(String::isNotBlank)
        ?.let { prompt ->
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", prompt),
            )
        }
    history
        .filter { it.content.isNotBlank() }
        .filter { it.role in CHAT_ROLES }
        .takeLast(MAX_HISTORY_MESSAGES)
        .forEach { message ->
            messages.put(
                JSONObject()
                    .put("role", message.role)
                    .put("content", chatCompletionContent(message.content, message.photos)),
            )
        }
    messages.put(
        JSONObject()
            .put("role", "user")
            .put("content", chatCompletionContent(userText, photos)),
    )
    return messages
}

internal fun chatCompletionContent(text: String, photos: List<PhotoAttachment>): Any {
    val actualPhotos = photos.filterNot(PhotoAttachment::isOmittedHistoryPhoto)
    if (actualPhotos.isEmpty()) return text
    return JSONArray().apply {
        put(
            JSONObject()
                .put("type", "text")
                .put("text", text.ifBlank { "Use the attached image." }),
        )
        actualPhotos.forEach { photo ->
            put(
                JSONObject()
                    .put("type", "image_url")
                    .put(
                        "image_url",
                        JSONObject().put(
                            "url",
                            "data:${photo.mimeType};base64,${photo.base64}",
                        ),
                    ),
            )
        }
    }
}

internal fun PhotoAttachment.isOmittedHistoryPhoto(): Boolean =
    mimeType == OMITTED_HISTORY_PHOTO_MIME_TYPE &&
        base64 == OMITTED_HISTORY_PHOTO_BASE64

private val CHAT_ROLES = setOf("system", "user", "assistant")
private const val MAX_HISTORY_MESSAGES = 24
