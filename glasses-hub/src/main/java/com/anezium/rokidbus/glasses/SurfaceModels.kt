package com.anezium.rokidbus.glasses

import android.os.SystemClock
import org.json.JSONObject

enum class SurfaceDisplayPath(val prefValue: String) {
    ACTIVITY("activity"),
    OVERLAY("overlay"),
}

data class TimedLine(
    val timeMs: Long,
    val text: String,
)

data class SurfaceAnchor(
    val positionMs: Long,
    val playing: Boolean,
    val sentAtElapsedRealtime: Long,
    val receivedAtElapsedRealtime: Long = SystemClock.elapsedRealtime(),
) {
    fun effectivePositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!playing) return positionMs
        val localElapsed = (now - receivedAtElapsedRealtime).coerceAtLeast(0L)
        return positionMs + localElapsed
    }
}

data class NexusSurface(
    val surfaceId: String,
    val seq: Long,
    val kind: String,
    val contentKey: String,
    val title: String,
    val subtitle: String,
    val footer: String,
    val textLines: List<String>,
    val timedLines: List<TimedLine>,
    val anchor: SurfaceAnchor?,
) {
    val isTimed: Boolean
        get() = kind == KIND_TIMED_LINES && timedLines.isNotEmpty()

    companion object {
        const val KIND_CARD = "card"
        const val KIND_TIMED_LINES = "timed-lines"

        fun fromPayload(payload: JSONObject, previous: NexusSurface? = null): NexusSurface {
            val kind = payload.optString("kind", KIND_CARD).ifBlank { KIND_CARD }
            val surfaceId = payload.getString("surfaceId")
            val contentKey = payload.optString("contentKey")
            val canMergePrevious = previous != null &&
                previous.surfaceId == surfaceId &&
                previous.kind == kind &&
                (contentKey.isBlank() || previous.contentKey == contentKey)
            val linesPresent = payload.has("lines")
            return NexusSurface(
                surfaceId = surfaceId,
                seq = payload.optLong("seq", 0L),
                kind = kind,
                contentKey = contentKey.ifBlank { previous?.takeIf { canMergePrevious }?.contentKey.orEmpty() },
                title = payload.optString("title").ifBlank { previous?.takeIf { canMergePrevious }?.title.orEmpty() },
                subtitle = payload.optString("subtitle").ifBlank { previous?.takeIf { canMergePrevious }?.subtitle.orEmpty() },
                footer = payload.optString("footer").ifBlank { previous?.takeIf { canMergePrevious }?.footer.orEmpty() },
                textLines = if (!linesPresent && canMergePrevious) {
                    previous?.textLines.orEmpty()
                } else payload.optJSONArray("lines")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val value = array.opt(index)
                            when (value) {
                                is JSONObject -> add(value.optString("text"))
                                else -> add(value?.toString().orEmpty())
                            }
                        }
                    }
                }.orEmpty(),
                timedLines = if (!linesPresent && canMergePrevious) {
                    previous?.timedLines.orEmpty()
                } else payload.optJSONArray("lines")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.optJSONObject(index)
                            if (item != null) {
                                add(
                                    TimedLine(
                                        timeMs = item.optLong("timeMs"),
                                        text = item.optString("text"),
                                    ),
                                )
                            }
                        }
                    }
                }.orEmpty(),
                anchor = payload.optJSONObject("anchor")?.let { anchor ->
                    SurfaceAnchor(
                        positionMs = anchor.optLong("positionMs", 0L),
                        playing = anchor.optBoolean("playing", false),
                        sentAtElapsedRealtime = anchor.optLong("sentAtElapsedRealtime", 0L),
                    )
                },
            )
        }
    }
}
