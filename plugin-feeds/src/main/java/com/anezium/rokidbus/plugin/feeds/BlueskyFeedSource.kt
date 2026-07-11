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

    companion object {
        const val DEFAULT_FEED_GENERATOR_URI =
            "at://did:plc:z72i7hdynmk6r22z27h6tvur/app.bsky.feed.generator/whats-hot"
        const val APP_VIEW_ENDPOINT = "https://public.api.bsky.app/xrpc/app.bsky.feed.getFeed"
        const val PAGE_SIZE = 25

        fun parsePage(json: String): FeedPage {
            val root = JSONObject(json)
            val feed = root.optJSONArray("feed") ?: JSONArray()
            val posts = buildList {
                for (index in 0 until feed.length()) {
                    val item = feed.optJSONObject(index) ?: continue
                    if (item.has("reason") || item.has("reply")) continue
                    val post = item.optJSONObject("post") ?: continue
                    val author = post.optJSONObject("author") ?: continue
                    if (hasLoggedOutOptOut(author.optJSONArray("labels"))) continue
                    val record = post.optJSONObject("record") ?: continue
                    if (record.optString("\$type") != "app.bsky.feed.post") continue
                    val id = post.optString("uri").trim()
                    val text = record.optString("text").trim()
                    val createdAt = parseInstant(record.optString("createdAt")) ?: continue
                    val handle = author.optString("handle").trim()
                    val name = author.optString("displayName").trim().ifBlank { handle }
                    if (id.isBlank() || name.isBlank()) continue
                    add(
                        FeedPost(
                            id = id,
                            authorName = name,
                            authorHandle = handle,
                            text = text,
                            createdAt = createdAt,
                            source = FeedSourceKind.BLUESKY.tag,
                            hasMedia = post.has("embed"),
                        ),
                    )
                }
            }
            return FeedPage(
                posts = posts,
                nextCursor = root.optString("cursor").trim().takeIf(String::isNotBlank),
            )
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
