package com.anezium.rokidbus.glasses

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.MediaArtworkContract
import org.json.JSONObject

enum class SurfaceDisplayPath(val prefValue: String) {
    ACTIVITY("activity"),
    OVERLAY("overlay"),
}

data class TimedLine(
    val timeMs: Long,
    val text: String,
)

enum class ReaderSegmentKind(val wireValue: String) {
    HEADER("header"),
    PROSE("prose"),
    ASIDE("aside");

    companion object {
        fun fromWireValue(value: String): ReaderSegmentKind? = entries.firstOrNull { it.wireValue == value }
    }
}

enum class ReaderAnchor {
    BOTTOM,
    TOP,
}

data class ReaderSegment(
    val kind: ReaderSegmentKind,
    val text: String,
    val emphasis: Boolean = false,
)

/**
 * One card body row. Plain rows carry only [text]; board rows add a route
 * [badge] and a [trail] of wait times so the HUD can lay them out with
 * real visual hierarchy instead of pre-formatted monospace strings.
 *
 * List rows add a [sub] line, a [tone] and a [selected] flag: they render as
 * an attention-ordered list (rail, weights, secondary line) rather than as a
 * departure board. A row is a list row as soon as any of those is set, so
 * board senders keep the board renderer untouched.
 */
data class SurfaceRow(
    val text: String,
    val badge: String = "",
    val trail: List<String> = emptyList(),
    val sub: String = "",
    val tone: String = TONE_NORMAL,
    val selected: Boolean = false,
) {
    val isStructured: Boolean
        get() = badge.isNotBlank() || trail.isNotEmpty() || isListRow

    val isListRow: Boolean
        get() = sub.isNotBlank() || tone != TONE_NORMAL || selected

    /** Rows that must read first: what needs the wearer, and where they are. */
    val isEmphasised: Boolean
        get() = tone == TONE_ALERT || selected

    companion object {
        const val TONE_ALERT = "alert"
        const val TONE_NORMAL = "normal"
        const val TONE_DIM = "dim"
        const val TONE_BODY = "body"
    }
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

data class SurfaceImageMetadata(
    val version: Int,
    val contentKey: String,
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val sha256: String,
    val caption: String,
) {
    companion object {
        fun fromPayload(payload: JSONObject): SurfaceImageMetadata {
            val version = payload.getInt("imageVersion")
            val contentKey = payload.getString("contentKey")
            val mimeType = payload.getString("mimeType")
            val width = payload.getInt("pixelWidth")
            val height = payload.getInt("pixelHeight")
            val sha256 = payload.getString("sha256")
            require(version == ImageSurfaceContract.VERSION)
            require(contentKey.isNotBlank() && contentKey.length <= ImageSurfaceContract.MAX_CONTENT_KEY_CHARS)
            require(mimeType == ImageSurfaceContract.MIME_JPEG || mimeType == ImageSurfaceContract.MIME_PNG)
            require(width in 1..ImageSurfaceContract.MAX_EDGE_PIXELS)
            require(height in 1..ImageSurfaceContract.MAX_EDGE_PIXELS)
            require(width.toLong() * height.toLong() <= ImageSurfaceContract.MAX_TOTAL_PIXELS)
            require(sha256.matches(Regex("[0-9a-f]{64}")))
            val caption = payload.optString("caption")
            require(caption.length <= ImageSurfaceContract.MAX_TEXT_CHARS)
            return SurfaceImageMetadata(
                version = version,
                contentKey = contentKey,
                mimeType = mimeType,
                pixelWidth = width,
                pixelHeight = height,
                sha256 = sha256,
                caption = caption,
            )
        }

        fun fromMediaPayload(payload: JSONObject): SurfaceImageMetadata? {
            val artwork = payload.optJSONObject("artwork") ?: return null
            if (artwork.optString("encoding") != MediaArtworkContract.ENCODING_BINARY) return null
            val contentKey = payload.getString("contentKey")
            val mimeType = artwork.getString("mimeType")
            val width = artwork.getInt("pixelWidth")
            val height = artwork.getInt("pixelHeight")
            val sha256 = artwork.getString("sha256")
            require(contentKey.isNotBlank() && contentKey.length <= ImageSurfaceContract.MAX_CONTENT_KEY_CHARS)
            require(mimeType == ImageSurfaceContract.MIME_JPEG || mimeType == ImageSurfaceContract.MIME_PNG)
            require(width in 1..MediaArtworkContract.MAX_EDGE_PIXELS)
            require(height in 1..MediaArtworkContract.MAX_EDGE_PIXELS)
            require(sha256.matches(Regex("[0-9a-f]{64}")))
            return SurfaceImageMetadata(
                version = ImageSurfaceContract.VERSION,
                contentKey = contentKey,
                mimeType = mimeType,
                pixelWidth = width,
                pixelHeight = height,
                sha256 = sha256,
                caption = "",
            )
        }
    }
}

data class InkSurfacePayload(
    val documentJson: String? = null,
    val patchJson: String? = null,
    val debugActions: Boolean = false,
    val debugFrameMeter: Boolean = false,
)

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
    val mediaArtworkMetadata: SurfaceImageMetadata? = null,
    val imageMetadata: SurfaceImageMetadata? = null,
    val imageBitmap: Bitmap? = null,
    val readerSegments: List<ReaderSegment> = emptyList(),
    val readerAnchor: ReaderAnchor = ReaderAnchor.BOTTOM,
    val ink: InkSurfacePayload? = null,
    val ownerPluginId: String = "",
    val epoch: Long = 0L,
) {
    val isTimed: Boolean
        get() = kind == KIND_TIMED_LINES && timedLines.isNotEmpty()
    val isMedia: Boolean
        get() = kind == KIND_MEDIA && mediaTitle.isNotBlank()
    val isImage: Boolean
        get() = kind == KIND_IMAGE && imageMetadata != null
    val isReader: Boolean
        get() = kind == KIND_READER
    val isInk: Boolean
        get() = kind == KIND_INK && ink != null

    companion object {
        const val KIND_CARD = "card"
        const val KIND_READER = "reader"
        const val KIND_TIMED_LINES = "timed-lines"
        const val KIND_MEDIA = "media"
        const val KIND_IMAGE = "image"
        const val KIND_INK = "ink"

        private const val MAX_TITLE_CHARS = 120
        private const val MAX_LINE_CHARS = 240
        private const val MAX_CONTENT_KEY_CHARS = 128
        private const val MAX_READER_SEGMENTS = 240
        private const val MAX_READER_SEGMENT_CHARS = 4_096
        private const val MAX_READER_TOTAL_CHARS = 40_000

        fun fromPayload(payload: JSONObject, previous: NexusSurface? = null): NexusSurface {
            val kind = payload.optString("kind", KIND_CARD).ifBlank { KIND_CARD }
            require(
                kind == KIND_CARD || kind == KIND_READER || kind == KIND_TIMED_LINES ||
                    kind == KIND_MEDIA || kind == KIND_IMAGE || kind == KIND_INK,
            ) {
                "Unknown surface kind: $kind"
            }
            val surfaceId = payload.getString("surfaceId")
            val contentKey = payload.optString("contentKey").let { value ->
                if (kind == KIND_READER) value.take(MAX_CONTENT_KEY_CHARS) else value
            }
            val canMergePrevious = previous != null &&
                previous.surfaceId == surfaceId &&
                previous.kind == kind &&
                (contentKey.isBlank() || previous.contentKey == contentKey)
            val linesPresent = payload.has("lines")
            val segmentsPresent = payload.has("segments")
            val readerAnchorPresent = payload.has("readerAnchor")
            val artworkPresent = payload.has("artwork")
            val mediaArtworkMetadata = when {
                kind != KIND_MEDIA -> null
                artworkPresent -> SurfaceImageMetadata.fromMediaPayload(payload)
                canMergePrevious -> previous?.mediaArtworkMetadata
                else -> null
            }
            val preservedImageBitmap = if (
                kind == KIND_MEDIA && canMergePrevious &&
                previous?.mediaArtworkMetadata?.sha256 == mediaArtworkMetadata?.sha256
            ) {
                previous?.imageBitmap
            } else {
                null
            }
            return NexusSurface(
                surfaceId = surfaceId,
                seq = payload.optLong("seq", 0L),
                kind = kind,
                contentKey = contentKey.ifBlank { previous?.takeIf { canMergePrevious }?.contentKey.orEmpty() },
                title = payload.optString("title")
                    .ifBlank { previous?.takeIf { canMergePrevious }?.title.orEmpty() }
                    .let { value -> if (kind == KIND_READER) value.take(MAX_TITLE_CHARS) else value },
                subtitle = if (kind == KIND_IMAGE) {
                    payload.optString("caption")
                } else {
                    payload.optString("subtitle").ifBlank {
                        previous?.takeIf { canMergePrevious }?.subtitle.orEmpty()
                    }.let { value -> if (kind == KIND_READER) value.take(MAX_LINE_CHARS) else value }
                },
                footer = payload.optString("footer")
                    .ifBlank { previous?.takeIf { canMergePrevious }?.footer.orEmpty() }
                    .let { value -> if (kind == KIND_READER) value.take(MAX_LINE_CHARS) else value },
                rows = if (kind == KIND_READER) {
                    emptyList()
                } else if (!linesPresent && canMergePrevious) {
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
                                        sub = value.optString("sub"),
                                        tone = value.optString("tone")
                                            .ifBlank { SurfaceRow.TONE_NORMAL },
                                        selected = value.optBoolean("selected", false),
                                    ),
                                )
                                else -> add(SurfaceRow(text = value?.toString().orEmpty()))
                            }
                        }
                    }
                }.orEmpty(),
                timedLines = if (kind == KIND_READER) {
                    emptyList()
                } else if (!linesPresent && canMergePrevious) {
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
                anchor = if (kind == KIND_READER) {
                    null
                } else {
                    payload.optJSONObject("anchor")?.let { anchor ->
                        SurfaceAnchor(
                            positionMs = anchor.optLong("positionMs", 0L),
                            playing = anchor.optBoolean("playing", false),
                            sentAtElapsedRealtime = anchor.optLong("sentAtElapsedRealtime", 0L),
                            durationMs = anchor.optLong("durationMs", -1L).takeIf { it > 0L },
                            playbackSpeed = anchor.optDouble("playbackSpeed", 1.0)
                                .toFloat()
                                .coerceIn(0f, 4f),
                        )
                    }
                },
                handlesBack = payload.optBoolean("handlesBack", false),
                mediaTitle = if (kind == KIND_READER) {
                    ""
                } else payload.optString("mediaTitle")
                    .ifBlank { previous?.takeIf { canMergePrevious }?.mediaTitle.orEmpty() },
                mediaArtist = if (kind == KIND_READER) {
                    ""
                } else payload.optString("mediaArtist")
                    .ifBlank { previous?.takeIf { canMergePrevious }?.mediaArtist.orEmpty() },
                mediaAlbum = if (kind == KIND_READER) {
                    ""
                } else payload.optString("mediaAlbum")
                    .ifBlank { previous?.takeIf { canMergePrevious }?.mediaAlbum.orEmpty() },
                artwork = if (kind == KIND_READER) {
                    null
                } else when {
                    artworkPresent -> MonoArtwork.fromPayload(payload.optJSONObject("artwork"))
                    canMergePrevious -> previous?.artwork
                    else -> null
                },
                mediaArtworkMetadata = mediaArtworkMetadata,
                imageMetadata = if (kind == KIND_IMAGE) SurfaceImageMetadata.fromPayload(payload) else null,
                imageBitmap = preservedImageBitmap,
                readerSegments = when {
                    kind != KIND_READER -> emptyList()
                    !segmentsPresent && canMergePrevious -> previous?.readerSegments.orEmpty()
                    else -> parseReaderSegments(payload)
                },
                readerAnchor = when {
                    kind != KIND_READER -> ReaderAnchor.BOTTOM
                    !readerAnchorPresent && canMergePrevious -> previous?.readerAnchor ?: ReaderAnchor.BOTTOM
                    payload.optString("readerAnchor") == "top" -> ReaderAnchor.TOP
                    else -> ReaderAnchor.BOTTOM
                },
                ink = if (kind == KIND_INK) {
                    payload.optJSONObject("ink")?.let { ink ->
                        InkSurfacePayload(
                            documentJson = ink.opt("document") as? String,
                            patchJson = ink.opt("patch") as? String,
                            debugActions = ink.opt("debugActions") as? Boolean ?: false,
                            debugFrameMeter = ink.opt("debugFrameMeter") as? Boolean ?: false,
                        )
                    }
                } else {
                    null
                },
                ownerPluginId = payload.optString("ownerPluginId")
                    .ifBlank { previous?.takeIf { canMergePrevious }?.ownerPluginId.orEmpty() },
                epoch = payload.optLong("epoch", 0L),
            )
        }

        private fun parseReaderSegments(payload: JSONObject): List<ReaderSegment> {
            val array = payload.optJSONArray("segments") ?: return emptyList()
            var totalChars = 0
            return buildList {
                for (index in 0 until array.length()) {
                    if (size >= MAX_READER_SEGMENTS) break
                    val item = array.optJSONObject(index) ?: continue
                    val segmentKind = ReaderSegmentKind.fromWireValue(item.optString("kind")) ?: continue
                    val remainingChars = (MAX_READER_TOTAL_CHARS - totalChars).coerceAtLeast(0)
                    val text = item.optString("text")
                        .take(MAX_READER_SEGMENT_CHARS)
                        .take(remainingChars)
                    add(
                        ReaderSegment(
                            kind = segmentKind,
                            text = text,
                            emphasis = item.optBoolean("emphasis", false),
                        ),
                    )
                    totalChars += text.length
                }
            }
        }
    }
}
