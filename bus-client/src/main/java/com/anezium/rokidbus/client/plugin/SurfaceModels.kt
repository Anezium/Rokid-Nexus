package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.MediaArtworkContract
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

data class NexusCardLine(
    val text: String,
    val badge: String? = null,
    val trail: List<String> = emptyList(),
) {
    init {
        require(text.length <= MAX_LINE_CHARS)
        require(badge == null || badge.length <= MAX_BADGE_CHARS)
        require(trail.size <= MAX_TRAIL_ITEMS)
        require(trail.all { it.length <= MAX_TRAIL_ITEM_CHARS })
    }

    internal fun toJsonValue(): Any = if (badge.isNullOrBlank() && trail.isEmpty()) {
        text
    } else {
        JSONObject()
            .put("text", text)
            .put("badge", badge.orEmpty())
            .put("trail", JSONArray(trail))
    }
}

data class NexusCard(
    val title: String,
    val lines: List<String>,
    val footer: String? = null,
    val contentKey: String? = null,
    val richLines: List<NexusCardLine>? = null,
    val handlesBack: Boolean = false,
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
        require(lines.size <= MAX_LINES)
        require(lines.all { it.length <= MAX_LINE_CHARS })
        require(richLines == null || lines.isEmpty())
        require(richLines == null || richLines.size <= MAX_LINES)
        require(footer == null || footer.length <= MAX_LINE_CHARS)
        require(contentKey == null || contentKey.length <= MAX_CONTENT_KEY_CHARS)
    }

    internal fun toPayload(surfaceId: String): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", "card")
        .put("title", title)
        .put(
            "lines",
            JSONArray().also { array ->
                if (richLines == null) {
                    lines.forEach { line -> array.put(line) }
                } else {
                    richLines.forEach { array.put(it.toJsonValue()) }
                }
            },
        )
        .apply {
            footer?.let { put("footer", it) }
            contentKey?.let { put("contentKey", it) }
            if (handlesBack) put("handlesBack", true)
        }
}

data class NexusTimedLine(val timeMs: Long, val text: String) {
    init {
        require(timeMs >= 0)
        require(text.length <= MAX_LINE_CHARS)
    }

    internal fun toJson(): JSONObject = JSONObject().put("timeMs", timeMs).put("text", text)
}

data class NexusPlaybackAnchor(
    val positionMs: Long,
    val playing: Boolean,
    val sentAtElapsedRealtime: Long,
) {
    init {
        require(positionMs >= 0)
        require(sentAtElapsedRealtime >= 0)
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("positionMs", positionMs)
        .put("playing", playing)
        .put("sentAtElapsedRealtime", sentAtElapsedRealtime)
}

data class NexusTimedLines(
    val title: String,
    val contentKey: String,
    val lines: List<NexusTimedLine>,
    val anchor: NexusPlaybackAnchor,
    val subtitle: String? = null,
    val footer: String? = null,
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
        require(contentKey.isNotBlank() && contentKey.length <= MAX_CONTENT_KEY_CHARS)
        require(lines.size <= MAX_TIMED_LINES)
        require(subtitle == null || subtitle.length <= MAX_LINE_CHARS)
        require(footer == null || footer.length <= MAX_LINE_CHARS)
    }

    internal fun toPayload(surfaceId: String): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", "timed-lines")
        .put("title", title)
        .put("contentKey", contentKey)
        .put("lines", JSONArray().also { array -> lines.forEach { array.put(it.toJson()) } })
        .put("anchor", anchor.toJson())
        .apply {
            subtitle?.let { put("subtitle", it) }
            footer?.let { put("footer", it) }
        }
}

data class NexusMonoArtwork(
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val hash: String,
) {
    init {
        require(width in 1..MAX_ARTWORK_DIMENSION)
        require(height in 1..MAX_ARTWORK_DIMENSION)
        require(bytes.size == (width * height + 7) / 8)
        require(hash.isNotBlank() && hash.length <= MAX_CONTENT_KEY_CHARS)
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("encoding", "mono1")
        .put("width", width)
        .put("height", height)
        .put("hash", hash)
        .put("data", Base64.getEncoder().encodeToString(bytes))
}

data class NexusMediaImageArtwork(
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
) {
    init {
        require(mimeType == ImageSurfaceContract.MIME_JPEG || mimeType == ImageSurfaceContract.MIME_PNG)
        require(pixelWidth in 1..MediaArtworkContract.MAX_EDGE_PIXELS)
        require(pixelHeight in 1..MediaArtworkContract.MAX_EDGE_PIXELS)
    }

    internal fun toJson(bytes: ByteArray): JSONObject = JSONObject()
        .put("encoding", MediaArtworkContract.ENCODING_BINARY)
        .put("mimeType", mimeType)
        .put("pixelWidth", pixelWidth)
        .put("pixelHeight", pixelHeight)
        .put("sha256", ImageSurfaceContract.sha256(bytes))
}

data class NexusMediaAnchor(
    val positionMs: Long,
    val playing: Boolean,
    val playbackSpeed: Float,
    val sentAtElapsedRealtime: Long,
    val durationMs: Long? = null,
) {
    init {
        require(positionMs >= 0)
        require(playbackSpeed.isFinite() && playbackSpeed >= 0f)
        require(sentAtElapsedRealtime >= 0)
        require(durationMs == null || durationMs >= 0)
    }

    internal fun toJson(): JSONObject = JSONObject()
        .put("positionMs", positionMs)
        .put("playing", playing)
        .put("playbackSpeed", playbackSpeed.toDouble())
        .put("sentAtElapsedRealtime", sentAtElapsedRealtime)
        .apply { durationMs?.let { put("durationMs", it) } }
}

data class NexusMedia(
    val title: String,
    val contentKey: String,
    val mediaTitle: String,
    val anchor: NexusMediaAnchor,
    val subtitle: String? = null,
    val mediaArtist: String? = null,
    val mediaAlbum: String? = null,
    val footer: String? = null,
    val artwork: NexusMonoArtwork? = null,
    val imageArtwork: NexusMediaImageArtwork? = null,
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
        require(contentKey.isNotBlank() && contentKey.length <= MAX_CONTENT_KEY_CHARS)
        require(mediaTitle.isNotBlank() && mediaTitle.length <= MAX_TITLE_CHARS)
        require(subtitle == null || subtitle.length <= MAX_LINE_CHARS)
        require(mediaArtist == null || mediaArtist.length <= MAX_LINE_CHARS)
        require(mediaAlbum == null || mediaAlbum.length <= MAX_LINE_CHARS)
        require(footer == null || footer.length <= MAX_LINE_CHARS)
        require(artwork == null || imageArtwork == null)
    }

    internal fun toPayload(surfaceId: String, imageBytes: ByteArray? = null): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", "media")
        .put("mediaVersion", 1)
        .put("contentKey", contentKey)
        .put("title", title)
        .put("mediaTitle", mediaTitle)
        .put("anchor", anchor.toJson())
        .apply {
            subtitle?.let { put("subtitle", it) }
            mediaArtist?.let { put("mediaArtist", it) }
            mediaAlbum?.let { put("mediaAlbum", it) }
            footer?.let { put("footer", it) }
            artwork?.let { put("artwork", it.toJson()) }
            imageArtwork?.let { metadata ->
                imageBytes?.let { put("artwork", metadata.toJson(it)) }
            }
        }
}

data class NexusImage(
    val contentKey: String,
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val title: String? = null,
    val caption: String? = null,
    val footer: String? = null,
    val handlesBack: Boolean = false,
) {
    init {
        require(contentKey.isNotBlank() && contentKey.length <= ImageSurfaceContract.MAX_CONTENT_KEY_CHARS)
        require(mimeType == ImageSurfaceContract.MIME_JPEG || mimeType == ImageSurfaceContract.MIME_PNG)
        require(pixelWidth in 1..ImageSurfaceContract.MAX_EDGE_PIXELS)
        require(pixelHeight in 1..ImageSurfaceContract.MAX_EDGE_PIXELS)
        require(pixelWidth.toLong() * pixelHeight.toLong() <= ImageSurfaceContract.MAX_TOTAL_PIXELS)
        require(title == null || title.length <= ImageSurfaceContract.MAX_TITLE_CHARS)
        require(caption == null || caption.length <= ImageSurfaceContract.MAX_TEXT_CHARS)
        require(footer == null || footer.length <= ImageSurfaceContract.MAX_TEXT_CHARS)
    }

    internal fun toPayload(surfaceId: String, bytes: ByteArray): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", ImageSurfaceContract.KIND)
        .put("imageVersion", ImageSurfaceContract.VERSION)
        .put("contentKey", contentKey)
        .put("mimeType", mimeType)
        .put("pixelWidth", pixelWidth)
        .put("pixelHeight", pixelHeight)
        .put("sha256", ImageSurfaceContract.sha256(bytes))
        .apply {
            title?.let { put("title", it) }
            caption?.let { put("caption", it) }
            footer?.let { put("footer", it) }
            if (handlesBack) put("handlesBack", true)
        }
}

enum class NexusSdkResult {
    SENT,
    NOT_REGISTERED,
    CAPABILITY_NOT_GRANTED,
    CAPABILITY_NOT_AVAILABLE,
    INVALID_PAYLOAD,
    IMAGE_RATE_LIMITED,
}

class NexusSurfaceSession internal constructor(
    private val client: NexusPluginClient,
    val localSurfaceId: String,
) {
    private var lastImageSendNanos = Long.MIN_VALUE
    init {
        require(LOCAL_SURFACE_ID.matches(localSurfaceId))
    }

    fun showCard(card: NexusCard): NexusSdkResult = sendSurface(BusPaths.SURFACE_SHOW, card.toPayload(localSurfaceId))
    fun updateCard(card: NexusCard): NexusSdkResult = sendSurface(BusPaths.SURFACE_UPDATE, card.toPayload(localSurfaceId))
    fun showImage(image: NexusImage, bytes: ByteArray): NexusSdkResult =
        sendImage(BusPaths.SURFACE_SHOW, image, bytes)
    fun updateImage(image: NexusImage, bytes: ByteArray): NexusSdkResult =
        sendImage(BusPaths.SURFACE_UPDATE, image, bytes)
    fun showTimedLines(lines: NexusTimedLines): NexusSdkResult =
        sendSurface(BusPaths.SURFACE_SHOW, lines.toPayload(localSurfaceId))
    fun updateTimedLines(lines: NexusTimedLines): NexusSdkResult =
        sendSurface(BusPaths.SURFACE_UPDATE, lines.toPayload(localSurfaceId))

    fun updateTimedLinesAnchor(
        contentKey: String,
        anchor: NexusPlaybackAnchor,
    ): NexusSdkResult {
        if (contentKey.isBlank() || contentKey.length > MAX_CONTENT_KEY_CHARS) return NexusSdkResult.INVALID_PAYLOAD
        return sendSurface(
            BusPaths.SURFACE_UPDATE,
            JSONObject()
                .put("surfaceId", localSurfaceId)
                .put("kind", "timed-lines")
                .put("contentKey", contentKey)
                .put("anchor", anchor.toJson()),
        )
    }

    fun showMedia(media: NexusMedia): NexusSdkResult =
        if (media.imageArtwork == null) {
            sendSurface(BusPaths.SURFACE_SHOW, media.toPayload(localSurfaceId))
        } else {
            NexusSdkResult.INVALID_PAYLOAD
        }

    fun showMedia(media: NexusMedia, artworkBytes: ByteArray): NexusSdkResult =
        sendMediaImage(BusPaths.SURFACE_SHOW, media, artworkBytes)

    fun updateMedia(media: NexusMedia): NexusSdkResult =
        if (media.imageArtwork == null) {
            sendSurface(BusPaths.SURFACE_UPDATE, media.toPayload(localSurfaceId))
        } else {
            NexusSdkResult.INVALID_PAYLOAD
        }

    fun updateMedia(media: NexusMedia, artworkBytes: ByteArray): NexusSdkResult =
        sendMediaImage(BusPaths.SURFACE_UPDATE, media, artworkBytes)

    fun updateMediaAnchor(
        contentKey: String,
        anchor: NexusMediaAnchor,
    ): NexusSdkResult {
        if (contentKey.isBlank() || contentKey.length > MAX_CONTENT_KEY_CHARS) return NexusSdkResult.INVALID_PAYLOAD
        return sendSurface(
            BusPaths.SURFACE_UPDATE,
            JSONObject()
                .put("surfaceId", localSurfaceId)
                .put("kind", "media")
                .put("mediaVersion", 1)
                .put("contentKey", contentKey)
                .put("anchor", anchor.toJson()),
        )
    }

    fun hide(): NexusSdkResult = sendSurface(
        BusPaths.SURFACE_HIDE,
        JSONObject().put("surfaceId", localSurfaceId),
    )

    private fun sendImage(path: String, image: NexusImage, bytes: ByteArray): NexusSdkResult {
        if (!client.isApproved) return NexusSdkResult.NOT_REGISTERED
        if (!client.hasCapability(PluginCapability.SURFACES)) return NexusSdkResult.CAPABILITY_NOT_GRANTED
        if (!client.supportsImageSurface) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        val payload = image.toPayload(localSurfaceId, bytes)
        if (ImageSurfaceContract.validate(payload, bytes) !is ImageSurfaceValidationResult.Valid) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return sendValidatedBinary(path, payload, bytes)
    }

    private fun sendMediaImage(path: String, media: NexusMedia, bytes: ByteArray): NexusSdkResult {
        if (!client.isApproved) return NexusSdkResult.NOT_REGISTERED
        if (!client.hasCapability(PluginCapability.SURFACES)) return NexusSdkResult.CAPABILITY_NOT_GRANTED
        if (!client.supportsImageSurface) return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (media.imageArtwork == null || media.artwork != null) return NexusSdkResult.INVALID_PAYLOAD
        val payload = media.toPayload(localSurfaceId, bytes)
        if (MediaArtworkContract.validate(payload, bytes) !is ImageSurfaceValidationResult.Valid) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return sendValidatedBinary(path, payload, bytes)
    }

    private fun sendValidatedBinary(path: String, payload: JSONObject, bytes: ByteArray): NexusSdkResult {
        synchronized(this) {
            val now = System.nanoTime()
            if (lastImageSendNanos != Long.MIN_VALUE &&
                now - lastImageSendNanos < ImageSurfaceContract.MIN_FRAME_INTERVAL_MS * 1_000_000L
            ) {
                return NexusSdkResult.IMAGE_RATE_LIMITED
            }
            return if (client.sendBinary(path, UUID.randomUUID().toString(), payload, bytes)) {
                lastImageSendNanos = now
                NexusSdkResult.SENT
            } else {
                NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            }
        }
    }

    private fun sendSurface(path: String, payload: JSONObject): NexusSdkResult {
        if (!client.isApproved) return NexusSdkResult.NOT_REGISTERED
        if (!client.hasCapability(PluginCapability.SURFACES)) return NexusSdkResult.CAPABILITY_NOT_GRANTED
        if (payload.toString().toByteArray(Charsets.UTF_8).size > MAX_SURFACE_PAYLOAD_BYTES) {
            return NexusSdkResult.INVALID_PAYLOAD
        }
        return if (client.send(path, UUID.randomUUID().toString(), payload)) {
            NexusSdkResult.SENT
        } else {
            NexusSdkResult.NOT_REGISTERED
        }
    }
}

fun NexusPluginClient.surfaceSession(localSurfaceId: String): NexusSurfaceSession =
    NexusSurfaceSession(this, localSurfaceId)

fun NexusPluginClient.requestHttp(request: JSONObject): NexusSdkResult {
    if (!isApproved) return NexusSdkResult.NOT_REGISTERED
    if (!hasCapability(PluginCapability.HTTP_PROXY)) return NexusSdkResult.CAPABILITY_NOT_GRANTED
    return if (send(BusPaths.HTTP_REQUEST, UUID.randomUUID().toString(), request)) {
        NexusSdkResult.SENT
    } else {
        NexusSdkResult.NOT_REGISTERED
    }
}

fun NexusPluginClient.requestAudioLease(): NexusSdkResult =
    NexusSdkResult.CAPABILITY_NOT_AVAILABLE

private val LOCAL_SURFACE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private const val MAX_TITLE_CHARS = 120
private const val MAX_LINE_CHARS = 240
private const val MAX_BADGE_CHARS = 24
private const val MAX_TRAIL_ITEMS = 8
private const val MAX_TRAIL_ITEM_CHARS = 24
private const val MAX_LINES = 64
private const val MAX_TIMED_LINES = 2_000
private const val MAX_CONTENT_KEY_CHARS = 128
private const val MAX_ARTWORK_DIMENSION = 192
private const val MAX_SURFACE_PAYLOAD_BYTES = 64 * 1024
