package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

private const val MAX_WIDGET_LINE_CHARS = 240
private const val MAX_WIDGET_TIMED_LINES = 2_000
private const val MAX_WIDGET_CONTENT_KEY_CHARS = 128

/**
 * One synced lyric line in a lyrics home widget, mirroring the timed-lines shape so
 * the glasses-side anchor clock can reuse the same selects-next-line math.
 */
data class WidgetTimedLine(val timeMs: Long, val text: String) {
    init {
        require(timeMs >= 0)
        require(text.length <= MAX_WIDGET_LINE_CHARS)
    }
}

/**
 * The playback anchor a widget is synchronised against. `playing == false` means
 * "held at this position, not advancing" -- the renderer keeps the current line but
 * stops its local clock, exactly like the timed-lines surface.
 */
data class WidgetAnchor(
    val positionMs: Long,
    val playing: Boolean,
    val sentAtElapsedRealtime: Long,
) {
    init {
        require(positionMs >= 0)
        require(sentAtElapsedRealtime >= 0)
    }
}

data class WidgetSurfaceContent(
    val contentKey: String,
    val lines: List<WidgetTimedLine>,
    val anchor: WidgetAnchor,
    /**
     * Implies the plugin is in Karaoke mode: the glasses may hold the display while this
     * widget is visible and playing. Glance mode (false) must never wake or hold the display.
     */
    val holdDisplay: Boolean = false,
)

sealed interface WidgetSurfaceValidationResult {
    data class Valid(val content: WidgetSurfaceContent) : WidgetSurfaceValidationResult
    data class Invalid(val reason: String) : WidgetSurfaceValidationResult
}

/**
 * Pure validation and normalization for the ambient widget channel. The widget
 * mirrors the timed-lines philosophy: full timed lines ride once with a playback
 * anchor, and the glasses advance locally from that anchor; later `/widget/update`
 * carries an anchor-only patch for seek/drift/pause. Unlike a surface it is ambient,
 * so it never enters the foreground-surface policy -- see the hub routing.
 */
object WidgetSurfaceContract {
    const val KIND = "widget"
    const val VERSION = 1
    const val LOCAL_SURFACE_ID = "widget"
    const val MAX_PAYLOAD_BYTES = 65_536

    const val ERROR_INVALID_WIDGET = "INVALID_WIDGET"
    const val ERROR_CAPABILITY_NOT_AVAILABLE = "CAPABILITY_NOT_AVAILABLE"

    val MAX_LINE_CHARS = MAX_WIDGET_LINE_CHARS
    val MAX_TIMED_LINES = MAX_WIDGET_TIMED_LINES
    val MAX_CONTENT_KEY_CHARS = MAX_WIDGET_CONTENT_KEY_CHARS

    fun validateShow(payload: JSONObject): WidgetSurfaceValidationResult {
        if (payload.toString().toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return invalid("payload exceeds 64 KiB")
        if (payload.opt("kind") != KIND) return invalid("kind must be widget")
        val contentKey = payload.optString("contentKey").trim()
        if (contentKey.isBlank() || contentKey.length > MAX_CONTENT_KEY_CHARS) {
            return invalid("contentKey must be 1..$MAX_CONTENT_KEY_CHARS characters")
        }

        val lines = parseLines(payload.opt("lines")) ?: return invalid("lines must be a non-empty array")
        if (lines.isEmpty()) return invalid("lines must not be empty")

        val anchor = parseAnchor(payload.opt("anchor")) ?: return invalid("anchor is invalid")
        val holdDisplay = when (val raw = payload.opt("holdDisplay")) {
            null -> false
            is Boolean -> raw
            else -> return invalid("holdDisplay must be a boolean")
        }
        return WidgetSurfaceValidationResult.Valid(
            WidgetSurfaceContent(contentKey = contentKey, lines = lines, anchor = anchor, holdDisplay = holdDisplay),
        )
    }

    /** Anchor-only update: used for seek/drift/pause, never a per-line stream. */
    fun validateAnchorUpdate(payload: JSONObject): WidgetSurfaceValidationResult {
        if (payload.toString().toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return invalid("payload exceeds 64 KiB")
        if (payload.opt("kind") != KIND) return invalid("kind must be widget")
        val contentKey = payload.optString("contentKey").trim()
        if (contentKey.isBlank() || contentKey.length > MAX_CONTENT_KEY_CHARS) {
            return invalid("contentKey must be 1..$MAX_CONTENT_KEY_CHARS characters")
        }
        val anchor = parseAnchor(payload.opt("anchor")) ?: return invalid("anchor is invalid")
        return WidgetSurfaceValidationResult.Valid(
            WidgetSurfaceContent(contentKey = contentKey, lines = emptyList(), anchor = anchor),
        )
    }

    fun toPayload(surfaceId: String, content: WidgetSurfaceContent): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", KIND)
        .put("contentKey", content.contentKey)
        .apply {
            if (content.lines.isNotEmpty()) {
                put(
                    "lines",
                    JSONArray().also { array ->
                        content.lines.forEach { line ->
                            array.put(JSONObject().put("timeMs", line.timeMs).put("text", line.text))
                        }
                    },
                )
            }
            put("anchor", content.anchor.toJson())
            if (content.holdDisplay) put("holdDisplay", true)
        }

    fun toAnchorOnlyPayload(surfaceId: String, contentKey: String, anchor: WidgetAnchor): JSONObject =
        toPayload(surfaceId, WidgetSurfaceContent(contentKey, emptyList(), anchor))

    private fun parseLines(value: Any?): List<WidgetTimedLine>? {
        if (value !is JSONArray) return null
        if (value.length() > MAX_TIMED_LINES) return null
        return buildList {
            for (index in 0 until value.length()) {
                val entry = value.opt(index) as? JSONObject ?: return null
                val timeMs = (entry.opt("timeMs") as? Number)?.longNumber() ?: return null
                val text = (entry.opt("text") as? String)?.trim() ?: return null
                if (timeMs < 0 || text.length > MAX_LINE_CHARS) return null
                if (lastOrNull()?.timeMs?.let { timeMs < it } == true) return null
                add(WidgetTimedLine(timeMs, text))
            }
        }
    }

    private fun parseAnchor(value: Any?): WidgetAnchor? {
        if (value !is JSONObject) return null
        val positionMs = (value.opt("positionMs") as? Number)?.longNumber() ?: return null
        val playing = value.opt("playing")
        if (playing !is Boolean) return null
        val sentAtElapsedRealtime = (value.opt("sentAtElapsedRealtime") as? Number)?.longNumber() ?: return null
        if (positionMs < 0 || sentAtElapsedRealtime < 0) return null
        return WidgetAnchor(positionMs, playing, sentAtElapsedRealtime)
    }

    private fun invalid(reason: String) = WidgetSurfaceValidationResult.Invalid(reason)
}

internal fun WidgetAnchor.toJson(): JSONObject = JSONObject()
    .put("positionMs", positionMs)
    .put("playing", playing)
    .put("sentAtElapsedRealtime", sentAtElapsedRealtime)

private fun Number.longNumber(): Long? {
    val double = toDouble()
    val long = toLong()
    return long.takeIf { double.isFinite() && double == long.toDouble() }
}