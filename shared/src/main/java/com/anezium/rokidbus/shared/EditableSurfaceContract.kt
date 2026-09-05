package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * The optional editable field on an ordinary card. There is no separate
 * surface kind for it: it rides the same `/surface/show|update` payload as
 * [ImageSurfaceContract]'s sibling contracts, so it inherits the foreground
 * slot's existing `SURFACE_BUSY`/replacement/BACK/link-loss ownership rules
 * for free. Committed or cancelled text comes back once, on
 * [BusConstants.SURFACE_TEXT_COMMITTED] — never as a live stream, the same
 * "small, bounded, one-shot" shape [RemoteInputContract] uses for the
 * separate hub-owned keyboard bridge.
 */
object EditableSurfaceContract {
    const val MAX_TEXT_UTF16_LENGTH = 512
    const val MAX_LABEL_CHARS = 64
    const val MAX_PLACEHOLDER_CHARS = 64
    const val MAX_SUBMIT_LABEL_CHARS = 24

    fun toJson(field: EditableSurfaceField): JSONObject = JSONObject()
        .apply {
            field.label?.let { put("label", it) }
            field.placeholder?.let { put("placeholder", it) }
            field.initialText?.let { put("initialText", it) }
            field.submitLabel?.let { put("submitLabel", it) }
        }

    fun parse(payload: JSONObject?): EditableSurfaceField? {
        if (payload == null) return null
        return EditableSurfaceField(
            label = payload.optString("label", "").take(MAX_LABEL_CHARS).ifBlank { null },
            placeholder = payload.optString("placeholder", "")
                .take(MAX_PLACEHOLDER_CHARS)
                .ifBlank { null },
            initialText = payload.optString("initialText", "")
                .take(MAX_TEXT_UTF16_LENGTH)
                .ifBlank { null },
            submitLabel = payload.optString("submitLabel", "")
                .take(MAX_SUBMIT_LABEL_CHARS)
                .ifBlank { null },
        )
    }

    fun committedPayload(surfaceId: String, text: String, cancelled: Boolean): JSONObject =
        JSONObject()
            .put("surfaceId", surfaceId)
            .put("cancelled", cancelled)
            .apply { if (!cancelled) put("text", text.take(MAX_TEXT_UTF16_LENGTH)) }

    fun parseCommitted(payload: JSONObject): EditableSurfaceCommitted? {
        val surfaceId = payload.optString("surfaceId").takeIf(String::isNotBlank) ?: return null
        val cancelled = payload.optBoolean("cancelled", false)
        val text = payload.optString("text", "").take(MAX_TEXT_UTF16_LENGTH)
        return EditableSurfaceCommitted(surfaceId, text, cancelled)
    }
}

/** What a plugin asks the foreground card to add: one bounded editable field. */
data class EditableSurfaceField(
    val label: String? = null,
    val placeholder: String? = null,
    val initialText: String? = null,
    val submitLabel: String? = null,
) {
    init {
        require(label == null || label.length <= EditableSurfaceContract.MAX_LABEL_CHARS)
        require(
            placeholder == null ||
                placeholder.length <= EditableSurfaceContract.MAX_PLACEHOLDER_CHARS,
        )
        require(
            initialText == null ||
                initialText.length <= EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH,
        )
        require(
            submitLabel == null ||
                submitLabel.length <= EditableSurfaceContract.MAX_SUBMIT_LABEL_CHARS,
        )
    }
}

/** One terminal answer for an editable card's field: what was typed, or that it was cancelled. */
data class EditableSurfaceCommitted(
    val surfaceId: String,
    val text: String,
    val cancelled: Boolean,
)
