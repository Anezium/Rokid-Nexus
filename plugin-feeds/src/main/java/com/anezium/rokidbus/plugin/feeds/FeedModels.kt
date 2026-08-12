package com.anezium.rokidbus.plugin.feeds

import java.time.Instant

data class FeedPost(
    val id: String,
    val authorName: String,
    val authorHandle: String,
    val text: String,
    val createdAt: Instant,
    val source: String,
    val media: List<FeedMedia> = emptyList(),
    val threadRef: String = id,
) {
    val hasMedia: Boolean get() = media.isNotEmpty()
}

enum class FeedMediaType { PHOTO, GIF, VIDEO }

data class FeedMedia(
    val type: FeedMediaType,
    val url: String,
    val previewUrl: String,
    val altText: String,
    val durationMs: Long?,
    /** All advertised streams, kept so playback can choose a glasses-compatible rendition. */
    val variants: List<FeedMediaVariant> = emptyList(),
)

enum class FeedMediaContainer { MP4, HLS }

data class FeedMediaVariant(
    val url: String,
    val container: FeedMediaContainer,
    val mimeType: String,
    val bitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val codecs: String? = null,
) {
    val isAvc: Boolean
        get() = codecs.isNullOrBlank() || codecs.contains("avc", ignoreCase = true)
}

/** Prefer an AVC MP4 that fits the first glasses MVP, but keep a deterministic fallback. */
fun FeedMedia.selectPlaybackVariant(): FeedMediaVariant? {
    val candidates = variants.filter { it.url.startsWith("https://", ignoreCase = true) }
    if (candidates.isEmpty()) return null
    val avcMp4 = candidates.filter {
        it.container == FeedMediaContainer.MP4 && it.isAvc &&
            (it.width == null || it.width <= 1280) && (it.height == null || it.height <= 720) &&
            (it.bitrate == null || it.bitrate <= 4_500_000L)
    }
    if (avcMp4.isNotEmpty()) return avcMp4.maxByOrNull { it.bitrate ?: 0L }
    return candidates.firstOrNull { it.container == FeedMediaContainer.HLS } ?: candidates.first()
}

data class FeedPage(
    val posts: List<FeedPost>,
    val nextCursor: String?,
)

data class FeedThread(
    val posts: List<FeedPost>,
    val focusIndex: Int,
)

interface FeedSource {
    fun fetchPage(cursor: String?): FeedPage

    fun fetchThread(post: FeedPost): FeedThread = FeedThread(listOf(post), 0)
}

enum class FeedSourceKind(
    val preferenceValue: String,
    val displayName: String,
    val tag: String,
    val title: String,
    val blurb: String,
) {
    BLUESKY("bsky", "Bluesky", "bsky", "Bluesky", "What's Hot — no account"),
    X_ACCOUNT("x", "X (QuaX)", "x", "X · QuaX", "Your home timeline"),
    X_WEBVIEW("x-web", "X (WebView)", "x-web", "X · WebView", "Home timeline, read in-page"),
    X_OFFICIAL("x_official", "X (official API)", "x-api", "X · official API", "Needs a paid API token"),
    DEMO("demo", "Demo", "demo", "Demo", "Sample posts, offline"),
    ;

    val isXSession: Boolean get() = this == X_ACCOUNT || this == X_WEBVIEW

    companion object {
        fun fromPreference(value: String?): FeedSourceKind =
            entries.firstOrNull { it.preferenceValue == value } ?: BLUESKY
    }
}
