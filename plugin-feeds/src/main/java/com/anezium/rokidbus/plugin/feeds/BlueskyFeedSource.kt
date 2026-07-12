package com.anezium.rokidbus.plugin.feeds

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

class BlueskyFeedSource(
    private val feedGeneratorUri: String = DEFAULT_FEED_GENERATOR_URI,
    private val httpClient: FeedHttpClient = UrlConnectionFeedHttpClient(),
) : FeedSource {
    override fun fetchPage(cursor: String?): FeedPage {
        val url = buildString {
            append(APP_VIEW_ENDPOINT)
            append("?feed=")
            append(urlEncode(feedGeneratorUri.ifBlank { DEFAULT_FEED_GENERATOR_URI }))
            append("&limit=")
            append(PAGE_SIZE)
            cursor?.takeIf(String::isNotBlank)?.let {
                append("&cursor=")
                append(urlEncode(it))
            }
        }
        return parsePage(httpClient.get(url, emptyMap()))
    }

    override fun fetchThread(post: FeedPost): FeedThread {
        val threadRef = post.threadRef.ifBlank { post.id }
        val url = buildString {
            append(POST_THREAD_ENDPOINT)
            append("?uri=")
            append(urlEncode(threadRef))
            append("&depth=10&parentHeight=20")
        }
        val parsed = parseThread(httpClient.get(url, emptyMap()))
        if (parsed.posts.isEmpty()) return FeedThread(listOf(post), 0)
        val focusIndex = parsed.posts.indexOfFirst { it.threadRef == threadRef }
            .takeIf { it >= 0 }
            ?: parsed.focusIndex.coerceIn(parsed.posts.indices)
        return parsed.copy(focusIndex = focusIndex)
    }

    companion object {
        const val DEFAULT_FEED_GENERATOR_URI =
            "at://did:plc:z72i7hdynmk6r22z27h6tvur/app.bsky.feed.generator/whats-hot"
        const val APP_VIEW_ENDPOINT = "https://public.api.bsky.app/xrpc/app.bsky.feed.getFeed"
        const val POST_THREAD_ENDPOINT = "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread"
        const val PAGE_SIZE = 25

        fun parsePage(json: String): FeedPage {
            val root = JSONObject(json)
            val feed = root.optJSONArray("feed") ?: JSONArray()
            val posts = buildList {
                for (index in 0 until feed.length()) {
                    val item = feed.optJSONObject(index) ?: continue
                    if (item.has("reason") || item.has("reply")) continue
                    val post = item.optJSONObject("post") ?: continue
                    parsePost(post)?.let(::add)
                }
            }
            return FeedPage(
                posts = posts,
                nextCursor = root.optString("cursor").trim().takeIf(String::isNotBlank),
            )
        }

        fun parseThread(json: String): FeedThread {
            val focalNode = JSONObject(json).optJSONObject("thread") ?: return FeedThread(emptyList(), 0)
            val focal = focalNode.optJSONObject("post")?.let(::parsePost)
                ?: return FeedThread(emptyList(), 0)
            val ancestors = mutableListOf<FeedPost>()
            var parent = focalNode.optJSONObject("parent")
            while (parent != null) {
                parent.optJSONObject("post")?.let(::parsePost)?.let(ancestors::add)
                parent = parent.optJSONObject("parent")
            }
            ancestors.reverse()
            val replies = buildList {
                val replyNodes = focalNode.optJSONArray("replies") ?: JSONArray()
                for (index in 0 until replyNodes.length()) {
                    replyNodes.optJSONObject(index)
                        ?.optJSONObject("post")
                        ?.let(::parsePost)
                        ?.let(::add)
                }
            }
            return FeedThread(
                posts = ancestors + focal + replies,
                focusIndex = ancestors.size,
            )
        }

        internal fun parsePost(post: JSONObject): FeedPost? {
            val author = post.optJSONObject("author") ?: return null
            if (hasLoggedOutOptOut(author.optJSONArray("labels"))) return null
            val record = post.optJSONObject("record") ?: return null
            if (record.optString("\$type") != "app.bsky.feed.post") return null
            val id = post.optString("uri").trim()
            val createdAt = parseInstant(record.optString("createdAt")) ?: return null
            val handle = author.optString("handle").trim()
            val name = author.optString("displayName").trim().ifBlank { handle }
            if (id.isBlank() || name.isBlank()) return null
            return FeedPost(
                id = id,
                authorName = name,
                authorHandle = handle,
                text = record.optString("text").trim(),
                createdAt = createdAt,
                source = FeedSourceKind.BLUESKY.tag,
                media = extractMedia(post.optJSONObject("embed")),
                threadRef = id,
            )
        }

        internal fun extractMedia(embed: JSONObject?): List<FeedMedia> {
            if (embed == null) return emptyList()
            return when (embed.optString("\$type")) {
                "app.bsky.embed.recordWithMedia#view" -> extractMedia(embed.optJSONObject("media"))
                "app.bsky.embed.images#view" -> {
                    val images = embed.optJSONArray("images") ?: JSONArray()
                    buildList {
                        for (index in 0 until images.length()) {
                            val image = images.optJSONObject(index) ?: continue
                            val fullsize = image.optString("fullsize").trim()
                            val thumb = image.optString("thumb").trim()
                            if (fullsize.isBlank() && thumb.isBlank()) continue
                            add(
                                FeedMedia(
                                    type = FeedMediaType.PHOTO,
                                    url = fullsize.ifBlank { thumb },
                                    previewUrl = thumb.ifBlank { fullsize },
                                    altText = image.optString("alt").trim(),
                                    durationMs = null,
                                ),
                            )
                        }
                    }
                }
                "app.bsky.embed.video#view" -> {
                    val playlist = embed.optString("playlist").trim()
                    val thumbnail = embed.optString("thumbnail").trim()
                    if (playlist.isBlank()) emptyList() else listOf(
                        FeedMedia(
                            type = FeedMediaType.VIDEO,
                            url = playlist,
                            previewUrl = thumbnail.ifBlank { playlist },
                            altText = embed.optString("alt").trim(),
                            durationMs = null,
                        ),
                    )
                }
                "app.bsky.embed.external#view" -> {
                    val external = embed.optJSONObject("external")
                    val thumb = external?.optString("thumb").orEmpty().trim()
                    if (thumb.isBlank()) emptyList() else listOf(
                        FeedMedia(
                            type = FeedMediaType.PHOTO,
                            url = thumb,
                            previewUrl = thumb,
                            altText = external?.optString("description").orEmpty().trim(),
                            durationMs = null,
                        ),
                    )
                }
                else -> emptyList()
            }
        }

        private fun hasLoggedOutOptOut(labels: JSONArray?): Boolean {
            if (labels == null) return false
            for (index in 0 until labels.length()) {
                if (labels.optJSONObject(index)?.optString("val") == "!no-unauthenticated") return true
            }
            return false
        }

        private fun parseInstant(value: String): Instant? =
            runCatching { Instant.parse(value) }.getOrNull()

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
