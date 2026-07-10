package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONArray
import org.json.JSONObject
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

enum class NexusSdkResult {
    SENT,
    NOT_REGISTERED,
    CAPABILITY_NOT_GRANTED,
    CAPABILITY_NOT_AVAILABLE,
    INVALID_PAYLOAD,
}

class NexusSurfaceSession internal constructor(
    private val client: NexusPluginClient,
    val localSurfaceId: String,
) {
    init {
        require(LOCAL_SURFACE_ID.matches(localSurfaceId))
    }

    fun showCard(card: NexusCard): NexusSdkResult = sendSurface(BusPaths.SURFACE_SHOW, card.toPayload(localSurfaceId))
    fun updateCard(card: NexusCard): NexusSdkResult = sendSurface(BusPaths.SURFACE_UPDATE, card.toPayload(localSurfaceId))
    fun showTimedLines(lines: NexusTimedLines): NexusSdkResult =
        sendSurface(BusPaths.SURFACE_SHOW, lines.toPayload(localSurfaceId))

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

    fun hide(): NexusSdkResult = sendSurface(
        BusPaths.SURFACE_HIDE,
        JSONObject().put("surfaceId", localSurfaceId),
    )

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
private const val MAX_SURFACE_PAYLOAD_BYTES = 64 * 1024
