package com.anezium.rokidbus.plugin.feeds

import java.time.Instant

data class FeedPost(
    val id: String,
    val authorName: String,
    val authorHandle: String,
    val text: String,
    val createdAt: Instant,
    val source: String,
    val hasMedia: Boolean,
)

data class FeedPage(
    val posts: List<FeedPost>,
    val nextCursor: String?,
)

fun interface FeedSource {
    fun fetchPage(cursor: String?): FeedPage
}

enum class FeedSourceKind(val preferenceValue: String, val displayName: String, val tag: String) {
    BLUESKY("bsky", "Bluesky", "bsky"),
    X("x", "X", "x"),
    DEMO("demo", "Demo", "demo"),
    ;

    companion object {
        fun fromPreference(value: String?): FeedSourceKind =
            entries.firstOrNull { it.preferenceValue == value } ?: BLUESKY
    }
}
