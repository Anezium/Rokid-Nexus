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
)

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
    X_ACCOUNT("x", "X (account)", "x", "X · account", "Your home timeline"),
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
