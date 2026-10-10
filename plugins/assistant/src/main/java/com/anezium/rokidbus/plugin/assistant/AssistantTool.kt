package com.anezium.rokidbus.plugin.assistant

data class AssistantToolCall(
    val callId: String,
    val name: String,
    val argumentsJson: String,
)

sealed interface AssistantToolResult {
    data class Json(
        val text: String,
    ) : AssistantToolResult

    data class Image(
        val mimeType: String,
        val base64: String,
        // Runtime provenance sent beside the pixels; a model-supplied label never fills it.
        val caption: String? = null,
    ) : AssistantToolResult

    data class Error(
        val code: String,
        val detailsJson: String? = null,
    ) : AssistantToolResult {
        init {
            require(ASSISTANT_TOOL_ERROR_CODE.matches(code)) {
                "Assistant tool error codes must be stable lowercase identifiers."
            }
            require(
                detailsJson == null || runCatching {
                    org.json.JSONObject(detailsJson).apply {
                        require(!has("ok") && !has("code"))
                    }
                }.isSuccess,
            ) {
                "Assistant tool error details must be a JSON object without ok or code."
            }
        }
    }
}

internal const val TAKE_PHOTO_TOOL_NAME = "take_photo"
internal const val COMPAT_TEXT_TOOL_REQUEST_TOKEN = "[[NEXUS_TOOL]]"
internal const val TOOL_ERROR_NOT_AUTHORIZED = "not_authorized"
internal const val TOOL_ERROR_GLASSES_DISCONNECTED = "glasses_disconnected"
internal const val TOOL_ERROR_CAMERA_BUSY = "camera_busy"
internal const val TOOL_ERROR_ALREADY_USED = "already_used"
internal const val TOOL_ERROR_CANCELLED = "cancelled"
internal const val TOOL_ERROR_CAPTURE_FAILED = "capture_failed"
internal const val TOOL_ERROR_INVALID_CALL = "invalid_call"

internal val HERMES_TEXT_TOOL_NAMES = setOf(
    TAKE_PHOTO_TOOL_NAME,
    TAKE_NOTE_TOOL_NAME,
    LIST_NOTES_TOOL_NAME,
    SEARCH_NOTES_TOOL_NAME,
    DELETE_NOTE_TOOL_NAME,
    SET_REMINDER_TOOL_NAME,
    LIST_REMINDERS_TOOL_NAME,
    CANCEL_REMINDER_TOOL_NAME,
    SET_TIMER_TOOL_NAME,
    CREATE_CALENDAR_EVENT_TOOL_NAME,
    LIST_CALENDAR_EVENTS_TOOL_NAME,
    DELETE_CALENDAR_EVENT_TOOL_NAME,
    // Bounded template cards fit one control line; render_ink_page stays out because a
    // whole freeform page does not.
    RENDER_TEMPLATE_TOOL_NAME,
)

private val ASSISTANT_TOOL_ERROR_CODE = Regex("[a-z][a-z0-9_]{0,95}")
