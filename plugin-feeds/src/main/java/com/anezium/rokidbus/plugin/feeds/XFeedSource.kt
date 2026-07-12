package com.anezium.rokidbus.plugin.feeds

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant

class XFeedSource(
    private val bearerToken: String,
    private val userId: String,
    private val httpClient: FeedHttpClient = UrlConnectionFeedHttpClient(),
    private val now: () -> Instant = Instant::now,
) : FeedSource {
    override fun fetchPage(cursor: String?): FeedPage {
        if (bearerToken.isBlank()) return missingTokenPage(now())
        if (!NUMERIC_USER_ID.matches(userId.trim())) return missingUserIdPage(now())
        val url = buildString {
            append("https://api.x.com/2/users/")
            append(userId.trim())
            append("/timelines/reverse_chronological")
            append("?max_results=25")
            append("&tweet.fields=author_id,created_at,attachments")
            append("&expansions=author_id")
            append("&user.fields=name,username")
            cursor?.takeIf(String::isNotBlank)?.let {
                append("&pagination_token=")
                append(URLEncoder.encode(it, Charsets.UTF_8.name()))
            }
        }
        return parsePage(
            json = httpClient.get(url, mapOf("Authorization" to "Bearer ${bearerToken.trim()}")),
            fallbackNow = now(),
        )
    }

    companion object {
        private val NUMERIC_USER_ID = Regex("[0-9]+")

        fun missingTokenPage(now: Instant = Instant.now()): FeedPage = FeedPage(
            posts = listOf(
                FeedPost(
                    id = "x-settings-token",
                    authorName = "X",
                    authorHandle = "x",
                    text = "X: add API token in phone settings",
                    createdAt = now,
                    source = FeedSourceKind.X_OFFICIAL.tag,
                ),
            ),
            nextCursor = null,
        )

        private fun missingUserIdPage(now: Instant): FeedPage = FeedPage(
            posts = listOf(
                FeedPost(
                    id = "x-settings-user-id",
                    authorName = "X",
                    authorHandle = "x",
                    text = "X: add numeric user id in phone settings",
                    createdAt = now,
                    source = FeedSourceKind.X_OFFICIAL.tag,
                ),
            ),
            nextCursor = null,
        )

        fun parsePage(json: String, fallbackNow: Instant = Instant.now()): FeedPage {
            val root = JSONObject(json)
            val users = linkedMapOf<String, Pair<String, String>>()
            val userArray = root.optJSONObject("includes")?.optJSONArray("users") ?: JSONArray()
            for (index in 0 until userArray.length()) {
                val user = userArray.optJSONObject(index) ?: continue
                val id = user.optString("id")
                if (id.isNotBlank()) users[id] = user.optString("name") to user.optString("username")
            }
            val data = root.optJSONArray("data") ?: JSONArray()
            val posts = buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue
                    val authorId = item.optString("author_id")
                    val author = users[authorId]
                    val handle = author?.second.orEmpty().ifBlank { authorId }
                    val name = author?.first.orEmpty().ifBlank { handle.ifBlank { "X" } }
                    add(
                        FeedPost(
                            id = id,
                            authorName = name,
                            authorHandle = handle,
                            text = item.optString("text").trim(),
                            createdAt = runCatching { Instant.parse(item.optString("created_at")) }
                                .getOrDefault(fallbackNow),
                            source = FeedSourceKind.X_OFFICIAL.tag,
                            // Official v1 intentionally leaves typed media empty: media_keys do not include usable URLs.
                        ),
                    )
                }
            }
            return FeedPage(
                posts = posts,
                nextCursor = root.optJSONObject("meta")
                    ?.optString("next_token")
                    ?.trim()
                    ?.takeIf(String::isNotBlank),
            )
        }
    }
}
