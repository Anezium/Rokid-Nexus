package com.anezium.rokidbus.plugin.feeds

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

internal object XGraphQlParser {
    private val createdAtFormat = DateTimeFormatter.ofPattern(
        "EEE MMM dd HH:mm:ss Z yyyy",
        Locale.ENGLISH,
    )

    fun parseThread(
        json: String,
        focalTweetId: String,
        source: String,
        fallbackNow: Instant,
    ): FeedThread {
        val instructions = JSONObject(json)
            .optJSONObject("data")
            ?.optJSONObject("threaded_conversation_with_injections_v2")
            ?.optJSONArray("instructions")
            ?: JSONArray()
        val posts = mutableListOf<FeedPost>()
        val knownIds = mutableSetOf<String>()
        for (instructionIndex in 0 until instructions.length()) {
            val instruction = instructions.optJSONObject(instructionIndex) ?: continue
            if (instruction.optString("type") != "TimelineAddEntries") continue
            val entries = instruction.optJSONArray("entries") ?: continue
            for (entryIndex in 0 until entries.length()) {
                val entry = entries.optJSONObject(entryIndex) ?: continue
                when {
                    entry.optString("entryId").startsWith("tweet-") -> {
                        val itemContent = entry.optJSONObject("content")?.optJSONObject("itemContent")
                        addTweet(posts, knownIds, itemContent, source, fallbackNow)
                    }
                    entry.optString("entryId").startsWith("conversationthread-") -> {
                        val items = entry.optJSONObject("content")?.optJSONArray("items") ?: continue
                        for (itemIndex in 0 until items.length()) {
                            val itemContent = items.optJSONObject(itemIndex)
                                ?.optJSONObject("item")
                                ?.optJSONObject("itemContent")
                            addTweet(posts, knownIds, itemContent, source, fallbackNow)
                        }
                    }
                }
            }
        }
        return FeedThread(
            posts = posts,
            focusIndex = posts.indexOfFirst { it.id == focalTweetId }.coerceAtLeast(0),
        )
    }

    fun parseTweetResult(
        result: JSONObject?,
        source: String,
        fallbackNow: Instant,
    ): FeedPost? {
        var tweet = result ?: return null
        repeat(3) {
            if (tweet.optString("rest_id").isNotBlank()) return@repeat
            tweet = tweet.optJSONObject("tweet") ?: return null
        }
        val legacy = tweet.optJSONObject("legacy") ?: return null
        val id = tweet.optString("rest_id").trim()
        if (id.isBlank()) return null
        val user = tweet.optJSONObject("core")
            ?.optJSONObject("user_results")
            ?.optJSONObject("result")
        val userCore = user?.optJSONObject("core")
        val userLegacy = user?.optJSONObject("legacy")
        val handle = userCore?.optString("screen_name").orEmpty()
            .ifBlank { userLegacy?.optString("screen_name").orEmpty() }
        val name = userCore?.optString("name").orEmpty()
            .ifBlank { userLegacy?.optString("name").orEmpty() }
            .ifBlank { handle.ifBlank { "X" } }
        val noteText = tweet.optJSONObject("note_tweet")
            ?.optJSONObject("note_tweet_results")
            ?.optJSONObject("result")
            ?.optString("text")
            ?.trim()
            .orEmpty()
        return FeedPost(
            id = id,
            authorName = name,
            authorHandle = handle,
            text = noteText.ifBlank { legacy.optString("full_text").trim() },
            createdAt = parseCreatedAt(legacy.optString("created_at")) ?: fallbackNow,
            source = source,
            media = extractMedia(legacy),
        )
    }

    fun extractMedia(legacy: JSONObject): List<FeedMedia> {
        val items = legacy.optJSONObject("extended_entities")
            ?.optJSONArray("media")
            ?.takeIf { it.length() > 0 }
            ?: legacy.optJSONObject("entities")?.optJSONArray("media")
            ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val type = when (item.optString("type")) {
                    "photo" -> FeedMediaType.PHOTO
                    "animated_gif" -> FeedMediaType.GIF
                    "video" -> FeedMediaType.VIDEO
                    else -> continue
                }
                val poster = item.optString("media_url_https").trim()
                if (poster.isBlank()) continue
                val videoInfo = item.optJSONObject("video_info")
                val videoUrl = bestMp4Url(videoInfo?.optJSONArray("variants"))
                val url = when (type) {
                    FeedMediaType.PHOTO -> photoUrl(poster, "large")
                    FeedMediaType.GIF, FeedMediaType.VIDEO -> videoUrl ?: continue
                }
                add(
                    FeedMedia(
                        type = type,
                        url = url,
                        previewUrl = if (type == FeedMediaType.PHOTO) photoUrl(poster, "small") else poster,
                        altText = item.optString("ext_alt_text").trim(),
                        durationMs = videoInfo?.optLong("duration_millis")
                            ?.takeIf { videoInfo.has("duration_millis") && it >= 0L },
                    ),
                )
            }
        }
    }

    private fun addTweet(
        posts: MutableList<FeedPost>,
        knownIds: MutableSet<String>,
        itemContent: JSONObject?,
        source: String,
        fallbackNow: Instant,
    ) {
        if (itemContent == null || itemContent.has("promotedMetadata")) return
        val post = parseTweetResult(
            itemContent.optJSONObject("tweet_results")?.optJSONObject("result"),
            source,
            fallbackNow,
        ) ?: return
        if (knownIds.add(post.id)) posts += post
    }

    private fun bestMp4Url(variants: JSONArray?): String? {
        if (variants == null) return null
        var bestUrl: String? = null
        var bestBitrate = Long.MIN_VALUE
        for (index in 0 until variants.length()) {
            val variant = variants.optJSONObject(index) ?: continue
            if (variant.optString("content_type") != "video/mp4") continue
            val url = variant.optString("url").trim()
            if (url.isBlank()) continue
            val bitrate = variant.optLong("bit_rate", -1L)
            if (bestUrl == null || bitrate > bestBitrate) {
                bestUrl = url
                bestBitrate = bitrate
            }
        }
        return bestUrl
    }

    private fun photoUrl(url: String, size: String): String = "${url.substringBefore('?')}?name=$size"

    private fun parseCreatedAt(value: String): Instant? =
        runCatching { Instant.from(createdAtFormat.parse(value)) }.getOrNull()
}
