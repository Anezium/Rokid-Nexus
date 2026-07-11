package com.anezium.rokidbus.glasses

import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject

enum class SurfaceDisplayPath(val prefValue: String) {
    ACTIVITY("activity"),
    OVERLAY("overlay"),
}

data class TimedLine(
    val timeMs: Long,
    val text: String,
)

/**
 * One card body row. Plain rows carry only [text]; board rows add a route
 * [badge] and a [trail] of wait times so the HUD can lay them out with
 * real visual hierarchy instead of pre-formatted monospace strings.
 */
data class SurfaceRow(
    val text: String,
    val badge: String = "",
    val trail: List<String> = emptyList(),
) {
    val isStructured: Boolean
        get() = badge.isNotBlank() || trail.isNotEmpty()
}

/** Compact one-bit artwork. Set bits are rendered in phosphor; unset bits stay transparent. */
data class MonoArtwork(
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val hash: String,
) {
    val identityKey: String
        get() = "${hash.take(32)}:${width}x$height:${bytes.contentHashCode()}"

    companion object {
        private const val MAX_DIMENSION = 192
        private const val MAX_BASE64_CHARS = 16 * 1024

        fun fromPayload(payload: JSONObject?): MonoArtwork? {
            payload ?: return null
            if (payload.optString("encoding") != "mono1") return null
            val width = payload.optInt("width")
            val height = payload.optInt("height")
            if (width !in 16..MAX_DIMENSION || height !in 16..MAX_DIMENSION) return null
            val encoded = payload.optString("data")
            if (encoded.isBlank() || encoded.length > MAX_BASE64_CHARS) return null
            val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
            val expectedBytes = (width * height + 7) / 8
            if (bytes.size != expectedBytes) return null
            return MonoArtwork(
                width = width,
                height = height,
                bytes = bytes,
                hash = payload.optString("hash").take(64),
            )
        }
    }
}

data class SurfaceAnchor(
    val positionMs: Long,
    val playing: Boolean,
    val sentAtElapsedRealtime: Long,
    val durationMs: Long? = null,
    val playbackSpeed: Float = 1f,
    val receivedAtElapsedRealtime: Long = SystemClock.elapsedRealtime(),
) {
    fun effectivePositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        if (!playing) return positionMs
        val localElapsed = (now - receivedAtElapsedRealtime).coerceAtLeast(0L)
        val predicted = positionMs + (localElapsed * playbackSpeed.coerceIn(0f, 4f)).toLong()
        return durationMs?.let { predicted.coerceAtMost(it) } ?: predicted
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
    val rows: List<SurfaceRow>,
    val timedLines: List<TimedLine>,
    val anchor: SurfaceAnchor?,
    val handlesBack: Boolean,
    val mediaTitle: String = "",
    val mediaArtist: String = "",
    val mediaAlbum: String = "",
    val artwork: MonoArtwork? = null,
) {
    val isTimed: Boolean
        get() = kind == KIND_TIMED_LINES && timedLines.isNotEmpty()
    val isMedia: Boolean
        get() = kind == KIND_MEDIA && mediaTitle.isNotBlank()

    companion object {
        const val KIND_CARD = "card"
        const val KIND_TIMED_LINES = "timed-lines"
        const val KIND_MEDIA = "media"

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
                rows = if (!linesPresent && canMergePrevious) {
                    previous?.rows.orEmpty()
                } else payload.optJSONArray("lines")?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) {
                            val value = array.opt(index)
                            when (value) {
                                is JSONObject -> add(
                                    SurfaceRow(
                                        text = value.optString("text"),
                                        badge = value.optString("badge"),
                                        trail = value.optJSONArray("trail")?.let { trail ->
                                            buildList {
                                                for (trailIndex in 0 until trail.length()) {
                                                    add(trail.optString(trailIndex))
                                                }
                                            }
                                        }.orEmpty(),
                                    ),
                                )
                                else -> add(SurfaceRow(text = value?.toString().orEmpty()))
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
                        durationMs = anchor.optLong("durationMs", -1L).takeIf { it > 0L },
                        playbackSpeed = anchor.optDouble("playbackSpeed", 1.0)
                            .toFloat()
                            .coerceIn(0f, 4f),
                    )
                },
                handlesBack = payload.optBoolean("handlesBack", false),
                mediaTitle = payload.optString("mediaTitle")
                    .ifBlank { previous?.takeIf { canMergePrevious }?.mediaTitle.orEmpty() },
                mediaArtist = payload.optString("mediaArtist")
                    .ifBlank { previous?.takeIf { canMergePrevious }?.mediaArtist.orEmpty() },
                mediaAlbum = payload.optString("mediaAlbum")
                    .ifBlank { previous?.takeIf { canMergePrevious }?.mediaAlbum.orEmpty() },
                artwork = when {
                    payload.has("artwork") -> MonoArtwork.fromPayload(payload.optJSONObject("artwork"))
                    canMergePrevious -> previous?.artwork
                    else -> null
                },
            )
        }
    }
}
