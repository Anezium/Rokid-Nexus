package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.ActivityAction
import com.anezium.rokidbus.shared.ActivityProgress
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.GlyphContract
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import com.anezium.rokidbus.shared.MediaArtworkContract
import com.anezium.rokidbus.shared.PinSurfaceContent
import com.anezium.rokidbus.shared.PinSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceEmphasis
import com.anezium.rokidbus.shared.NoticeAction
import com.anezium.rokidbus.shared.NoticeTextInput
import com.anezium.rokidbus.shared.NoticeCloseReason
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PinSurfaceLine
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.PinSurfaceSize
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * Weight of a row in a list surface. The HUD maps these to real typographic
 * hierarchy; plugins never pick sizes or colours themselves.
 */
enum class NexusRowTone(val wireValue: String) {
    /** Needs the wearer's attention: brightest treatment on the surface. */
    ALERT("alert"),

    /** Ordinary entry. */
    NORMAL("normal"),

    /** Present but not competing for attention (finished, stale, muted). */
    DIM("dim"),

    /** Prose that must wrap over several lines — a chat message, a log excerpt. */
    BODY("body"),
}

data class NexusCardLine(
    val text: String,
    val badge: String? = null,
    val trail: List<String> = emptyList(),
    /** Secondary line rendered under [text], dimmer and smaller. */
    val sub: String? = null,
    val tone: NexusRowTone = NexusRowTone.NORMAL,
    /** Marks the row the wearer is on; the HUD draws the selection affordance. */
    val selected: Boolean = false,
) {
    init {
        require(text.length <= MAX_LINE_CHARS)
        require(badge == null || badge.length <= MAX_BADGE_CHARS)
        require(trail.size <= MAX_TRAIL_ITEMS)
        require(trail.all { it.length <= MAX_TRAIL_ITEM_CHARS })
        require(sub == null || sub.length <= MAX_LINE_CHARS)
    }

    private val isListRow: Boolean
        get() = !sub.isNullOrBlank() || tone != NexusRowTone.NORMAL || selected

    internal fun toJsonValue(): Any = if (badge.isNullOrBlank() && trail.isEmpty() && !isListRow) {
        text
    } else {
        JSONObject()
            .put("text", text)
            .put("badge", badge.orEmpty())
            .put("trail", JSONArray(trail))
            .apply {
                if (!sub.isNullOrBlank()) put("sub", sub)
                if (tone != NexusRowTone.NORMAL) put("tone", tone.wireValue)
                if (selected) put("selected", true)
            }
    }
}

data class NexusCard(
    val title: String,
    val lines: List<String>,
    val footer: String? = null,
    val contentKey: String? = null,
    val richLines: List<NexusCardLine>? = null,
    val handlesBack: Boolean = false,
    /** One dim line under the title: counts, state, context. */
    val subtitle: String? = null,
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
        require(lines.size <= MAX_LINES)
        require(lines.all { it.length <= MAX_LINE_CHARS })
        require(richLines == null || lines.isEmpty())
        require(richLines == null || richLines.size <= MAX_LINES)
        require(footer == null || footer.length <= MAX_LINE_CHARS)
        require(subtitle == null || subtitle.length <= MAX_LINE_CHARS)
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
            subtitle?.takeIf(String::isNotBlank)?.let { put("subtitle", it) }
            contentKey?.let { put("contentKey", it) }
            if (handlesBack) put("handlesBack", true)
        }
}

enum class NexusReaderSegmentKind(val wireValue: String) {
    /** One quiet line introducing a speaking turn: "CX · il y a 5 min". */
    HEADER("header"),

    /** Full-width prose; the renderer wraps it, no line clamp. */
    PROSE("prose"),

    /** One small dim line between turns: collapsed tool calls, events. */
    ASIDE("aside"),
}

enum class NexusReaderAnchor(val wireValue: String) {
    /** Stream-shaped content (chat, log, transcript): opens at the end, follows the tail. */
    BOTTOM("bottom"),

    /** Document-shaped content (article, note, recipe): opens at the start, never auto-follows. */
    TOP("top"),
}

data class NexusReaderSegment(
    val kind: NexusReaderSegmentKind,
    val text: String,
    /** On a HEADER: the leading token (before the first "·") is drawn
     *  bright — the wearer's own turns. Ignored on other kinds. */
    val emphasis: Boolean = false,
)

data class NexusReader(
    val title: String,
    val subtitle: String? = null,
    val footer: String? = null,
    val contentKey: String? = null,
    /** True hands BACK to the plugin instead of the hub hiding the surface. */
    val handlesBack: Boolean = false,
    val segments: List<NexusReaderSegment>,
    val anchor: NexusReaderAnchor = NexusReaderAnchor.BOTTOM,
) {
    init {
        require(title.isNotBlank() && title.length <= MAX_TITLE_CHARS)
        require(subtitle == null || subtitle.length <= MAX_LINE_CHARS)
        require(footer == null || footer.length <= MAX_LINE_CHARS)
        require(contentKey == null || contentKey.length <= MAX_CONTENT_KEY_CHARS)
        require(segments.size in 1..MAX_READER_SEGMENTS)
        require(segments.all { it.text.length <= MAX_READER_SEGMENT_CHARS })
        require(segments.sumOf { it.text.length } <= MAX_READER_TOTAL_CHARS)
    }

    internal fun toPayload(surfaceId: String): JSONObject = JSONObject()
        .put("surfaceId", surfaceId)
        .put("kind", "reader")
        .put("handlesBack", handlesBack)
        .put("title", title)
        .put(
            "segments",
            JSONArray().also { array ->
                segments.forEach { segment ->
                    array.put(
                        JSONObject()
                            .put("kind", segment.kind.wireValue)
                            .put("text", segment.text)
                            .apply { if (segment.emphasis) put("emphasis", true) },
                    )
                }
            },
        )
        .apply {
            subtitle?.let { put("subtitle", it) }
            footer?.let { put("footer", it) }
            contentKey?.let { put("contentKey", it) }
            if (anchor != NexusReaderAnchor.BOTTOM) put("readerAnchor", anchor.wireValue)
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

enum class NexusPinPosition(internal val wireValue: String) {
    TOP_LEFT(PinSurfacePosition.TOP_LEFT.wireValue),
    TOP_RIGHT(PinSurfacePosition.TOP_RIGHT.wireValue),
    BOTTOM_LEFT(PinSurfacePosition.BOTTOM_LEFT.wireValue),
    BOTTOM_RIGHT(PinSurfacePosition.BOTTOM_RIGHT.wireValue),
}

/**
 * Pin size tier. [SMALL] is the default and keeps the original caps (title of 24
 * trimmed characters, two lines of 28); [MEDIUM] allows a 28-character title and
 * three lines of 32 and renders slightly larger and wider.
 */
enum class NexusPinSize(internal val contract: PinSurfaceSize) {
    SMALL(PinSurfaceSize.SMALL),
    MEDIUM(PinSurfaceSize.MEDIUM),
}

/**
 * Per-line emphasis. [DEFAULT] is the muted body tone every pin line has always
 * used, [BRIGHT] promotes the line to the phosphor title tone, and [DIM] states
 * the muted tone explicitly. The pin title is always bright.
 */
enum class NexusPinEmphasis(internal val contract: PinSurfaceEmphasis) {
    DEFAULT(PinSurfaceEmphasis.DEFAULT),
    BRIGHT(PinSurfaceEmphasis.BRIGHT),
    DIM(PinSurfaceEmphasis.DIM),
}

/**
 * One emphasized pin line, passed through [NexusPin.richLines].
 *
 * Length caps depend on the pin's size tier, so they are enforced by [NexusPin]
 * rather than here.
 */
data class NexusPinLine(
    val text: String,
    val emphasis: NexusPinEmphasis = NexusPinEmphasis.DEFAULT,
) {
    internal fun toContent(): PinSurfaceLine =
        PinSurfaceLine(text = text.trim(), emphasis = emphasis.contract)
}

/**
 * A tiny persistent glasses pin.
 *
 * [title] is optional and limited to the tier's title cap (24 trimmed characters
 * for [NexusPinSize.SMALL], 28 for [NexusPinSize.MEDIUM]). [lines] accepts plain
 * strings up to the tier's line count and length (2 x 28, or 3 x 32 for medium);
 * pass [richLines] instead to set per-line emphasis. At least one title or line
 * must remain non-empty after trimming. [ttlMs] is optional and clamped to 1
 * second through 24 hours; leave it null and the hub applies its 30-minute
 * default, since a pin outlives the process that pushed it. Every `showPin`
 * restarts the clock.
 */
data class NexusPin(
    val title: String? = null,
    val lines: List<String> = emptyList(),
    val position: NexusPinPosition = NexusPinPosition.TOP_RIGHT,
    val ttlMs: Long? = null,
    val size: NexusPinSize = NexusPinSize.SMALL,
    val richLines: List<NexusPinLine>? = null,
) {
    init {
        val caps = size.contract
        require(title == null || title.trim().length <= caps.maxTitleChars)
        require(richLines == null || lines.isEmpty())
        require(contentLines().size <= caps.maxLines)
        require(contentLines().all { it.text.trim().length <= caps.maxLineChars })
        require(!title?.trim().isNullOrEmpty() || contentLines().any { it.text.trim().isNotEmpty() })
    }

    private fun contentLines(): List<NexusPinLine> =
        richLines ?: lines.map { NexusPinLine(it) }

    internal fun toPayload(): JSONObject = PinSurfaceContract.toPayload(
        surfaceId = PinSurfaceContract.LOCAL_SURFACE_ID,
        content = PinSurfaceContent(
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            lines = contentLines().map { it.toContent() },
            position = PinSurfacePosition.fromWireValue(position.wireValue)!!,
            ttlMs = ttlMs?.coerceIn(PinSurfaceContract.MIN_TTL_MS, PinSurfaceContract.MAX_TTL_MS),
            size = size.contract,
        ),
    )
}

enum class NexusSdkResult {
    SENT,
    NOT_REGISTERED,
    CAPABILITY_NOT_GRANTED,
    CAPABILITY_NOT_AVAILABLE,
    INVALID_PAYLOAD,
    SURFACE_BUSY,
    IMAGE_RATE_LIMITED,
    TTS_RATE_LIMITED,
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
    fun showReader(reader: NexusReader): NexusSdkResult =
        sendSurface(BusPaths.SURFACE_SHOW, reader.toPayload(localSurfaceId))
    fun updateReader(reader: NexusReader): NexusSdkResult =
        sendSurface(BusPaths.SURFACE_UPDATE, reader.toPayload(localSurfaceId))
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

private val LOCAL_SURFACE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private const val MAX_TITLE_CHARS = 120
private const val MAX_LINE_CHARS = 240
private const val MAX_BADGE_CHARS = 24
private const val MAX_TRAIL_ITEMS = 8
private const val MAX_TRAIL_ITEM_CHARS = 24
private const val MAX_LINES = 64
private const val MAX_TIMED_LINES = 2_000
private const val MAX_CONTENT_KEY_CHARS = 128
private const val MAX_READER_SEGMENTS = 240
private const val MAX_READER_SEGMENT_CHARS = 4_096
private const val MAX_READER_TOTAL_CHARS = 40_000
private const val MAX_ARTWORK_DIMENSION = 192
private const val MAX_SURFACE_PAYLOAD_BYTES = 64 * 1024

/** Why a notice stopped being visible, as delivered to the plugin that raised it. */
enum class NexusNoticeCloseReason(internal val contract: NoticeCloseReason) {
    /** The wearer pressed back. */
    USER(NoticeCloseReason.USER),

    /** Its own deadline, or the sixty-second ceiling every notice has. */
    TIMEOUT(NoticeCloseReason.TIMEOUT),

    /** This plugin called hideNotice. */
    OWNER(NoticeCloseReason.OWNER),

    /** Another plugin took the band. */
    REPLACED(NoticeCloseReason.REPLACED),

    /** The plugin lost its grant or its connection. Best-effort. */
    DISCONNECT(NoticeCloseReason.DISCONNECT),
    ;

    internal companion object {
        fun fromWire(value: String): NexusNoticeCloseReason? =
            NoticeCloseReason.fromWireValue(value)?.let { reason ->
                entries.firstOrNull { it.contract == reason }
            }
    }
}

/**
 * One answer the wearer can pick from a notice's platform-rendered action row.
 *
 * The same three fields as [NexusActivityAction], because it is the same
 * affordance one tier up: the platform draws a row of glyphs, the wearer steps
 * along it and confirms one.
 *
 * @property id Stable value returned through
 * [NexusPluginCallbacks.onNoticeAction].
 * @property glyph Well-formed open-set glyph name. Unknown names degrade to
 * the platform's `dot` fallback.
 * @property label Human-readable answer label. The platform owns whether and
 * where it is shown.
 */
data class NexusNoticeAction(
    val id: String,
    val glyph: String,
    val label: String,
) {
    init {
        require(id.trim().isNotEmpty())
        require(GlyphContract.isWellFormedName(glyph))
        require(label.trim().isNotEmpty())
    }

    internal fun toContract(): NoticeAction = NoticeAction(
        id = id.trim(),
        glyph = glyph.trim(),
        label = label.trim(),
    )
}

/** A single-line, platform-owned notice editor submitted on phone-keyboard Enter. */
data class NexusNoticeTextInput(
    val id: String,
    val hint: String,
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")))
        require(hint.trim().isNotEmpty())
        require(hint.trim().length <= NoticeSurfaceContract.MAX_TEXT_INPUT_HINT_CHARS)
    }

    internal fun toContract(): NoticeTextInput = NoticeTextInput(id.trim(), hint.trim())
}

/** Metadata for the JPEG or PNG frame sent with a [NexusNotice]. */
data class NexusNoticeImage(
    val contentKey: String,
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
) {
    init {
        require(contentKey.isNotBlank() && contentKey.length <= ImageSurfaceContract.MAX_CONTENT_KEY_CHARS)
        require(mimeType == ImageSurfaceContract.MIME_JPEG || mimeType == ImageSurfaceContract.MIME_PNG)
        require(pixelWidth in 1..ImageSurfaceContract.MAX_EDGE_PIXELS)
        require(pixelHeight in 1..ImageSurfaceContract.MAX_EDGE_PIXELS)
        require(pixelWidth.toLong() * pixelHeight.toLong() <= ImageSurfaceContract.MAX_TOTAL_PIXELS)
    }
}

/**
 * A transient band across the top of the wearer's view.
 *
 * Use it for a single real-world event that has just happened and may deserve
 * one gesture of reply. Anything the wearer follows over minutes is not a
 * notice, and anything static that should simply stay put is a pin.
 *
 * [title] is capped at 32 trimmed characters, [body] at 8192, [footer] at 40,
 * and at least one of title, body, or [lines] must survive trimming. Body and
 * lines are mutually exclusive. Up to 64 lines share the body's 8192-character
 * budget, including one separator per line. Newlines collapse to spaces inside
 * either representation; only the lines array asks the renderer for hard
 * breaks. When [ttlMs] is absent the hub derives one from the text length; an
 * explicit value is clamped to 2-45 seconds.
 *
 * @property interactive Ask for a single confirming gesture, answered on
 * [NexusPluginCallbacks.onNoticeInput]. Redundant when [actions] is set:
 * offering answers is already asking for one.
 * @property actions Up to three answers the wearer can choose between,
 * answered on [NexusPluginCallbacks.onNoticeAction]. A fourth is refused, not
 * dropped. They do not extend the band's life: a notice with a choice on it
 * still leaves on its own deadline, and Back still dismisses it.
 * @property wakeDisplay Ask the hub to wake a dark display for this new event.
 * Subject to one platform-wide wake per five seconds; never keeps it on.
 * @property lines Structured alternative to [body]. Empty entries are dropped;
 * an empty result is absent from the wire.
 * @property backdrop Hide the rest of the glasses display behind this notice.
 * @property textInput A platform-owned single-line editor. It cannot be combined
 * with [interactive] or [actions]; the entered text is returned only on Enter.
 */
data class NexusNotice(
    val title: String? = null,
    val body: String? = null,
    val footer: String? = null,
    val interactive: Boolean = false,
    val actions: List<NexusNoticeAction> = emptyList(),
    val ttlMs: Long? = null,
    val image: NexusNoticeImage? = null,
    val wakeDisplay: Boolean = false,
    val lines: List<String> = emptyList(),
    val backdrop: Boolean = false,
    val textInput: NexusNoticeTextInput? = null,
) {
    init {
        val normalizedLines = normalizedNoticeLines(lines)
        require(title == null || title.trim().length <= NoticeSurfaceContract.MAX_TITLE_CHARS)
        require(body == null || body.trim().length <= NoticeSurfaceContract.MAX_BODY_CHARS)
        require(body == null || lines.isEmpty())
        require(lines.size <= NoticeSurfaceContract.MAX_LINES)
        require(
            normalizedLines.sumOf { it.length.toLong() + 1L } <=
                NoticeSurfaceContract.MAX_BODY_CHARS,
        )
        require(footer == null || footer.trim().length <= NoticeSurfaceContract.MAX_FOOTER_CHARS)
        require(
            !title?.trim().isNullOrEmpty() ||
                !body?.trim().isNullOrEmpty() ||
                normalizedLines.isNotEmpty(),
        )
        require(actions.size <= NoticeSurfaceContract.MAX_ACTIONS)
        require(textInput == null || (!interactive && actions.isEmpty()))
    }

    internal fun toPayload(imageBytes: ByteArray? = null): JSONObject = JSONObject()
        .put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
        .put("kind", NoticeSurfaceContract.KIND)
        .apply {
            title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
            body?.trim()?.takeIf { it.isNotEmpty() }?.let { put("body", it) }
            if (lines.isNotEmpty()) putNoticeLines(lines)
            footer?.trim()?.takeIf { it.isNotEmpty() }?.let { put("footer", it) }
            if (interactive) put("interactive", true)
            // A notice with no answers sends no actions key at all, so every
            // banner written before this existed still goes out unchanged.
            if (actions.isNotEmpty()) putActions(actions)
            textInput?.let { input ->
                put(
                    "textInput",
                    JSONObject()
                        .put("id", input.id)
                        .put("hint", input.hint.trim()),
                )
            }
            ttlMs?.let { put("ttlMs", it) }
            if (wakeDisplay) put("wakeDisplay", true)
            if (backdrop) put("backdrop", true)
            image?.let { metadata ->
                if (imageBytes != null) putNoticeImage(metadata, imageBytes)
            }
        }
}

/**
 * A change to the visible notice. Only the fields you set are sent, and only
 * those are replaced: leaving one out keeps whatever is on screen, while
 * passing an empty string clears it. An update cannot leave the band with no
 * text at all.
 *
 * @property interactive Ask again on a band that has already been answered, or
 * stop asking. Null leaves the current state alone, which is what an ordinary
 * text update does. Setting it either way reopens the band for a new answer:
 * see [NexusPluginCallbacks.onNoticeInput] for why a band only answers once.
 * @property actions Replaces the whole action row, and reopens the band for a
 * new answer. An empty list leaves the current row alone rather than clearing
 * it, so this cannot take a question's answers away from a wearer halfway
 * through reading them; hide the notice instead. The wearer's selection follows
 * its action id across the swap, so reordering answers does not move their
 * finger onto a different one.
 * @property lines A non-empty structured body replacement. An empty list is
 * absent from the wire and leaves the current text alone.
 */
data class NexusNoticeUpdate(
    val title: String? = null,
    val body: String? = null,
    val footer: String? = null,
    val interactive: Boolean? = null,
    val actions: List<NexusNoticeAction> = emptyList(),
    val ttlMs: Long? = null,
    val lines: List<String> = emptyList(),
    val textInput: NexusNoticeTextInput? = null,
) {
    init {
        require(body == null || lines.isEmpty())
        require(lines.size <= NoticeSurfaceContract.MAX_LINES)
        require(
            normalizedNoticeLines(lines).sumOf { it.length.toLong() + 1L } <=
                NoticeSurfaceContract.MAX_BODY_CHARS,
        )
        require(actions.size <= NoticeSurfaceContract.MAX_ACTIONS)
        require(textInput == null || (interactive != true && actions.isEmpty()))
    }

    internal fun toPayload(): JSONObject = JSONObject()
        .put("surfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
        .apply {
            title?.let { put("title", it.trim()) }
            body?.let { put("body", it.trim()) }
            if (lines.isNotEmpty()) putNoticeLines(lines)
            footer?.let { put("footer", it.trim()) }
            // Sent only when the plugin actually set it. An update that leaves
            // it null must not carry the field, or every text update would read
            // as the owner asking the question again.
            interactive?.let { put("interactive", it) }
            if (actions.isNotEmpty()) putActions(actions)
            textInput?.let { input ->
                put(
                    "textInput",
                    JSONObject()
                        .put("id", input.id)
                        .put("hint", input.hint.trim()),
                )
            }
            ttlMs?.let { put("ttlMs", it) }
        }
}

private fun JSONObject.putActions(actions: List<NexusNoticeAction>) {
    put(
        "actions",
        JSONArray().apply {
            actions.map(NexusNoticeAction::toContract).forEach { action ->
                put(
                    JSONObject()
                        .put("id", action.id)
                        .put("glyph", action.glyph)
                        .put("label", action.label),
                )
            }
        },
    )
}

private fun JSONObject.putNoticeLines(lines: List<String>) {
    val normalized = normalizedNoticeLines(lines)
    if (normalized.isNotEmpty()) put("lines", JSONArray(normalized))
}

private fun normalizedNoticeLines(lines: List<String>): List<String> = lines
    .map { it.replace(NOTICE_NEWLINES, " ").trim() }
    .filter(String::isNotEmpty)

private val NOTICE_NEWLINES = Regex("[\\r\\n]+")

private fun JSONObject.putNoticeImage(image: NexusNoticeImage, bytes: ByteArray) {
    put("imageVersion", ImageSurfaceContract.VERSION)
    put("contentKey", image.contentKey)
    put("mimeType", image.mimeType)
    put("pixelWidth", image.pixelWidth)
    put("pixelHeight", image.pixelHeight)
    put("sha256", ImageSurfaceContract.sha256(bytes))
}

/**
 * Progress for an ongoing [NexusActivity].
 *
 * A percentage is an integer from 0 through 100. [Indeterminate] asks the
 * platform to show ongoing work without inventing a completion estimate.
 */
sealed interface NexusActivityProgress {
    data class Percent(val value: Int) : NexusActivityProgress {
        init {
            require(value in 0..100)
        }
    }

    data object Indeterminate : NexusActivityProgress
}

/**
 * One one-shot command in an activity's platform-rendered action row.
 *
 * @property id Stable value returned through
 * [NexusPluginCallbacks.onActivityAction].
 * @property glyph Well-formed open-set glyph name. Unknown names degrade to
 * the platform's `dot` fallback.
 * @property label Human-readable command label. The platform owns whether and
 * where it is shown.
 */
data class NexusActivityAction(
    val id: String,
    val glyph: String,
    val label: String,
) {
    init {
        require(id.trim().isNotEmpty())
        require(GlyphContract.isWellFormedName(glyph))
        require(label.trim().isNotEmpty())
    }

    internal fun toContract(): ActivityAction = ActivityAction(
        id = id.trim(),
        glyph = glyph.trim(),
        label = label.trim(),
    )
}

/**
 * One ongoing real-world process that the wearer follows over time.
 *
 * Use an activity for an ongoing process, a notice for a discrete event, a
 * surface for interaction the wearer is actively driving, and a pin for a
 * trivial static fact. The plugin declares state only; the platform always
 * chooses chip, panel, flare, pulse, or hidden presentation.
 *
 * @property glyph Required open-set state glyph name. It is shape-validated,
 * not checked against this SDK's built-in set.
 * @property primary Required glance value, capped at 12 trimmed characters.
 * @property secondary Optional label for [primary], capped at 28 trimmed
 * characters.
 * @property progress Optional percentage or indeterminate progress state.
 * @property eta Optional trailing value, capped at 8 trimmed characters.
 * @property detail Up to two panel-only detail lines, each capped at 32
 * trimmed characters.
 * @property actions Up to three one-shot, platform-rendered actions.
 * @property maxDurationMs Optional safety deadline, clamped on start to one
 * minute through twelve hours. Null means the activity lasts until ended,
 * evicted, or disconnected.
 * @property wakeDisplay Allow significant updates in this activity to ask the
 * hub to wake a dark display. Quiet updates never wake it, and the platform
 * applies one global wake per five seconds.
 */
data class NexusActivity(
    val glyph: String,
    val primary: String,
    val secondary: String? = null,
    val progress: NexusActivityProgress? = null,
    val eta: String? = null,
    val detail: List<String> = emptyList(),
    val actions: List<NexusActivityAction> = emptyList(),
    val maxDurationMs: Long? = null,
    val wakeDisplay: Boolean = false,
) {
    init {
        require(GlyphContract.isWellFormedName(glyph))
        require(primary.trim().isNotEmpty())
        require(primary.trim().length <= ActivitySurfaceContract.MAX_PRIMARY_CHARS)
        require(
            secondary == null ||
                secondary.trim().length <= ActivitySurfaceContract.MAX_SECONDARY_CHARS,
        )
        require(eta == null || eta.trim().length <= ActivitySurfaceContract.MAX_ETA_CHARS)
        require(detail.size <= ActivitySurfaceContract.MAX_DETAIL_LINES)
        require(detail.all { it.trim().length <= ActivitySurfaceContract.MAX_DETAIL_CHARS })
        require(actions.size <= ActivitySurfaceContract.MAX_ACTIONS)
    }

    internal fun toStartPayload(): JSONObject = ActivitySurfaceContract.toPayload(
        surfaceId = ActivitySurfaceContract.LOCAL_SURFACE_ID,
        content = toContent(),
    )

    internal fun toUpdatePayload(significant: Boolean): JSONObject =
        ActivitySurfaceContract.toUpdatePayload(
            surfaceId = ActivitySurfaceContract.LOCAL_SURFACE_ID,
            content = toContent(),
            significant = significant,
        )

    private fun toContent(): ActivitySurfaceContent = ActivitySurfaceContent(
        glyph = glyph.trim(),
        primary = primary.trim(),
        secondary = secondary?.trim()?.takeIf { it.isNotEmpty() },
        progress = when (progress) {
            is NexusActivityProgress.Percent -> ActivityProgress.Percent(progress.value)
            NexusActivityProgress.Indeterminate -> ActivityProgress.Indeterminate
            null -> null
        },
        eta = eta?.trim()?.takeIf { it.isNotEmpty() },
        detail = detail.map(String::trim),
        actions = actions.map(NexusActivityAction::toContract),
        maxDurationMs = maxDurationMs,
        wakeDisplay = wakeDisplay,
    )
}
