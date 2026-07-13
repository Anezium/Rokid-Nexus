package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

data class CameraOverlayBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class CameraOverlayLayout(
    val kind: String,
    val version: Int,
    val medianLineHeight: Float,
    val growDown: Float,
    val column: Int? = null,
)

data class CameraOverlayItem(
    val text: String,
    val box: CameraOverlayBounds,
    val role: String,
    val id: String? = null,
    val layout: CameraOverlayLayout? = null,
)

data class CameraOverlayPayload(
    val sessionId: String,
    val requestId: Long?,
    val seq: Long?,
    val items: List<CameraOverlayItem>,
)

object CameraOverlayContract {
    const val VERSION = 1
    const val MAX_ITEMS = 128
    const val MAX_TEXT_CHARS = 1_024
    const val MAX_ROLE_CHARS = 32
    const val MAX_ID_CHARS = 64
    const val PARAGRAPH_LAYOUT_KIND = "paragraph"
    const val PARAGRAPH_LAYOUT_VERSION = 1
    const val MAX_LAYOUT_COLUMN = MAX_ITEMS - 1

    fun parse(payload: JSONObject, requireRequestId: Boolean): CameraOverlayPayload? {
        if (payload.optInt("version", VERSION) != VERSION) return null
        val sessionId = payload.optString("sessionId")
        if (sessionId.isBlank()) return null
        val requestId = if (payload.has("requestId")) payload.optLong("requestId") else null
        if (requireRequestId && requestId == null) return null
        val seq = if (payload.has("seq")) payload.optLong("seq") else null
        val values = payload.optJSONArray("items") ?: return null
        if (values.length() > MAX_ITEMS) return null
        val items = buildList {
            repeat(values.length()) { index ->
                val value = values.optJSONObject(index) ?: return null
                val text = value.optString("text")
                val role = value.optString("role")
                val id = if (value.has("id")) {
                    (value.opt("id") as? String)?.takeIf {
                        it.isNotBlank() && it.length <= MAX_ID_CHARS
                    } ?: return null
                } else {
                    null
                }
                if (text.isBlank() || text.length > MAX_TEXT_CHARS ||
                    role.isBlank() || role.length > MAX_ROLE_CHARS
                ) return null
                val box = parseBox(value.opt("box")) ?: return null
                val layout = parseLayout(value.opt("layout"))
                add(CameraOverlayItem(text, box, role, id, layout))
            }
        }
        return CameraOverlayPayload(sessionId, requestId, seq, items)
    }

    private fun parseLayout(raw: Any?): CameraOverlayLayout? {
        val value = raw as? JSONObject ?: return null
        val rawVersion = value.opt("version") as? Number ?: return null
        if (value.opt("kind") != PARAGRAPH_LAYOUT_KIND ||
            rawVersion.toDouble() != PARAGRAPH_LAYOUT_VERSION.toDouble()
        ) return null
        val medianLineHeight = (value.opt("medianLineHeight") as? Number)?.toFloat() ?: return null
        val growDown = (value.opt("growDown") as? Number)?.toFloat() ?: return null
        if (!medianLineHeight.isFinite() || medianLineHeight !in 0f..1f || medianLineHeight == 0f ||
            !growDown.isFinite() || growDown !in 0f..1f
        ) return null
        val column = if (value.has("column")) {
            val rawColumn = value.opt("column") as? Number ?: return null
            val intColumn = rawColumn.toInt()
            if (rawColumn.toDouble() != intColumn.toDouble() || intColumn !in 0..MAX_LAYOUT_COLUMN) return null
            intColumn
        } else {
            null
        }
        return CameraOverlayLayout(
            kind = PARAGRAPH_LAYOUT_KIND,
            version = PARAGRAPH_LAYOUT_VERSION,
            medianLineHeight = medianLineHeight,
            growDown = growDown,
            column = column,
        )
    }

    private fun parseBox(raw: Any?): CameraOverlayBounds? {
        val values = when (raw) {
            is JSONObject -> floatArrayOf(
                raw.optDouble("left", Double.NaN).toFloat(),
                raw.optDouble("top", Double.NaN).toFloat(),
                raw.optDouble("right", Double.NaN).toFloat(),
                raw.optDouble("bottom", Double.NaN).toFloat(),
            )
            is JSONArray -> if (raw.length() == 4) {
                FloatArray(4) { raw.optDouble(it, Double.NaN).toFloat() }
            } else {
                return null
            }
            else -> return null
        }
        if (values.any { !it.isFinite() || it !in 0f..1f }) return null
        if (values[0] >= values[2] || values[1] >= values[3]) return null
        return CameraOverlayBounds(values[0], values[1], values[2], values[3])
    }
}
