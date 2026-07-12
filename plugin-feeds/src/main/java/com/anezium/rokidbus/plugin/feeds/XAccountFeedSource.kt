package com.anezium.rokidbus.plugin.feeds

import com.anezium.rokidbus.plugin.feeds.xtransaction.XClientTransaction
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant

class XAccountFeedSource internal constructor(
    private val cookies: XAccountCookies,
    private val httpClient: FeedHttpClient = UrlConnectionFeedHttpClient(),
    private val now: () -> Instant = Instant::now,
    private val transactionFactory: () -> XClientTransaction = { XClientTransaction.initialize(httpClient) },
) : FeedSource {
    private var transaction: XClientTransaction? = null
    private var rateLimitedUntil: Instant? = null

    override fun fetchPage(cursor: String?): FeedPage {
        if (!cookies.isConnected) return missingCookiesPage(now())
        rateLimitedUntil?.takeIf { now().isBefore(it) }?.let { return rateLimitedPage(now()) }

        val path = API_PATH
        val transactionId = (transaction ?: transactionFactory().also { transaction = it })
            .generateTransactionId("GET", path)
        val response = httpClient.getResponse(
            url = buildUrl(cursor),
            headers = accountHeaders(cookies, transactionId),
        )
        return when (response.statusCode) {
            in 200..299 -> parsePage(response.body, cursor, now())
            404 -> reconnectPage(now())
            429 -> {
                rateLimitedUntil = response.headers["x-rate-limit-reset"]
                    ?.toLongOrNull()
                    ?.let(Instant::ofEpochSecond)
                    ?.takeIf { it.isAfter(now()) }
                    ?: now().plusSeconds(RATE_LIMIT_FALLBACK_SECONDS)
                rateLimitedPage(now())
            }
            else -> throw IOException("HTTP ${response.statusCode}: ${response.body.take(120)}")
        }
    }

    private fun buildUrl(cursor: String?): String {
        val variables = JSONObject(VARIABLES_JSON)
        cursor?.takeIf(String::isNotBlank)?.let { variables.put("cursor", it) }
        return buildString {
            append(API_URL)
            append("?variables=")
            append(urlEncode(variables.toString()))
            append("&features=")
            append(urlEncode(FEATURES_JSON))
            append("&fieldToggles=")
            append(urlEncode(FIELD_TOGGLES_JSON))
        }
    }

    companion object {
        // X-internal query IDs and request flags are not stable API and must be refreshed together.
        internal const val QUERY_ID = "W4Tpu1uueTGK53paUgxF0Q"
        internal const val API_PATH = "/i/api/graphql/$QUERY_ID/HomeTimeline"
        internal const val API_URL = "https://twitter.com$API_PATH"
        internal const val AUTHENTICATED_BEARER =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        internal const val VARIABLES_JSON =
            "{\"userId\":\"160534877\",\"count\":20,\"includePromotedContent\":false," +
                "\"withQuickPromoteEligibilityTweetFields\":true,\"withVoice\":true,\"withV2Timeline\":true}"
        internal const val FEATURES_JSON =
            "{\"rweb_lists_timeline_redesign_enabled\":true," +
                "\"responsive_web_graphql_exclude_directive_enabled\":true," +
                "\"verified_phone_label_enabled\":true," +
                "\"creator_subscriptions_tweet_preview_api_enabled\":true," +
                "\"responsive_web_graphql_timeline_navigation_enabled\":true," +
                "\"responsive_web_graphql_skip_user_profile_image_extensions_enabled\":false," +
                "\"tweetypie_unmention_optimization_enabled\":true," +
                "\"responsive_web_edit_tweet_api_enabled\":true," +
                "\"graphql_is_translatable_rweb_tweet_is_translatable_enabled\":true," +
                "\"view_counts_everywhere_api_enabled\":true," +
                "\"longform_notetweets_consumption_enabled\":true," +
                "\"responsive_web_twitter_article_tweet_consumption_enabled\":false," +
                "\"tweet_awards_web_tipping_enabled\":false," +
                "\"freedom_of_speech_not_reach_fetch_enabled\":true," +
                "\"standardized_nudges_misinfo\":true," +
                "\"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled\":true," +
                "\"longform_notetweets_rich_text_read_enabled\":true," +
                "\"longform_notetweets_inline_media_enabled\":true," +
                "\"responsive_web_media_download_video_enabled\":false," +
                "\"responsive_web_enhance_cards_enabled\":false}"
        internal const val FIELD_TOGGLES_JSON =
            "{\"withAuxiliaryUserLabels\":false,\"withArticleRichContentState\":false}"
        internal const val RATE_LIMIT_FALLBACK_SECONDS = 15 * 60L
        internal fun accountHeaders(cookies: XAccountCookies, transactionId: String): Map<String, String> =
            mapOf(
                "accept" to "*/*",
                "accept-language" to "en-US,en;q=0.9",
                "authorization" to "Bearer $AUTHENTICATED_BEARER",
                "cache-control" to "no-cache",
                "content-type" to "application/json",
                "pragma" to "no-cache",
                "priority" to "u=1, i",
                "referer" to "https://x.com/",
                "user-agent" to
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.3",
                "Cookie" to cookies.asCookieHeader(),
                "x-csrf-token" to cookies.ct0,
                "x-twitter-active-user" to "yes",
                "x-twitter-client-language" to "en",
                "x-client-transaction-id" to transactionId,
            )

        fun parsePage(json: String, requestedCursor: String? = null, fallbackNow: Instant = Instant.now()): FeedPage {
            val instructions = JSONObject(json)
                .optJSONObject("data")
                ?.optJSONObject("home")
                ?.optJSONObject("home_timeline_urt")
                ?.optJSONArray("instructions")
                ?: JSONArray()
            var entries = JSONArray()
            for (index in 0 until instructions.length()) {
                val instruction = instructions.optJSONObject(index) ?: continue
                if (instruction.optString("type") == "TimelineAddEntries") {
                    entries = instruction.optJSONArray("entries") ?: JSONArray()
                    break
                }
            }

            var bottomCursor: String? = null
            val posts = buildList {
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val content = entry.optJSONObject("content") ?: continue
                    cursor(content, "Bottom")?.let { bottomCursor = it }
                    if (!entry.optString("entryId").startsWith("tweet-")) continue
                    val itemContent = content.optJSONObject("itemContent") ?: continue
                    if (itemContent.has("promotedMetadata")) continue
                    XGraphQlParser.parseTweetResult(
                        itemContent.optJSONObject("tweet_results")?.optJSONObject("result"),
                        FeedSourceKind.X_ACCOUNT.tag,
                        fallbackNow,
                    )?.let(::add)
                }
            }
            val nextCursor = bottomCursor
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.takeUnless { it == requestedCursor }
                ?.takeIf { posts.isNotEmpty() }
            return FeedPage(posts = posts, nextCursor = nextCursor)
        }

        fun missingCookiesPage(now: Instant = Instant.now()): FeedPage = messagePage(
            id = "x-account-connect",
            text = "X: connect your account in phone settings",
            now = now,
        )

        private fun reconnectPage(now: Instant): FeedPage = messagePage(
            id = "x-account-reconnect",
            text = "X: reconnect your account in phone settings",
            now = now,
        )

        private fun rateLimitedPage(now: Instant): FeedPage = messagePage(
            id = "x-account-rate-limited",
            text = "X: rate limited; try again later",
            now = now,
        )

        private fun messagePage(id: String, text: String, now: Instant): FeedPage = FeedPage(
            posts = listOf(
                FeedPost(
                    id = id,
                    authorName = "X",
                    authorHandle = "x",
                    text = text,
                    createdAt = now,
                    source = FeedSourceKind.X_ACCOUNT.tag,
                ),
            ),
            nextCursor = null,
        )

        private fun cursor(content: JSONObject, type: String): String? {
            if (content.optString("cursorType") == type) return content.optString("value")
            val cursor = content.optJSONObject("operation")?.optJSONObject("cursor") ?: return null
            return cursor.optString("value").takeIf { cursor.optString("cursorType") == type }
        }

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, Charsets.UTF_8.name())
    }
}
