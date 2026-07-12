package com.anezium.rokidbus.plugin.feeds

import android.content.Context

data class FeedsSettings(
    val source: FeedSourceKind,
    val xAccountCookies: XAccountCookies,
    val xBearerToken: String,
    val xUserId: String,
    val blueskyFeedGeneratorUri: String,
)

const val INCLUDE_DEMO_SOURCE = false

fun FeedSourceKind.isConfigured(settings: FeedsSettings): Boolean = when (this) {
    FeedSourceKind.BLUESKY -> true
    FeedSourceKind.X_ACCOUNT,
    FeedSourceKind.X_WEBVIEW,
    -> settings.xAccountCookies.isConnected
    FeedSourceKind.X_OFFICIAL -> settings.xBearerToken.isNotBlank()
    FeedSourceKind.DEMO -> INCLUDE_DEMO_SOURCE
}

fun availableSources(settings: FeedsSettings): List<FeedSourceKind> =
    FeedSourceKind.entries.filter { it.isConfigured(settings) }

data class XAccountCookies(
    val authToken: String = "",
    val ct0: String = "",
    val guestId: String = "",
    val gt: String = "",
    val att: String = "",
) {
    val isConnected: Boolean
        get() = authToken.isNotBlank() && ct0.isNotBlank()

    fun asCookieHeader(): String = listOf(
        "guest_id" to guestId,
        "gt" to gt,
        "att" to att,
        "auth_token" to authToken,
        "ct0" to ct0,
    ).filter { (_, value) -> value.isNotBlank() }
        .joinToString(";") { (name, value) -> "$name=$value" }

    companion object {
        private val CAPTURED_NAMES = setOf("guest_id", "gt", "att", "auth_token", "ct0")

        fun fromCookieHeader(header: String): XAccountCookies {
            val values = header.split(';').mapNotNull { part ->
                val pieces = part.trim().split('=', limit = 2)
                pieces.takeIf { it.size == 2 && it[0] in CAPTURED_NAMES }?.let { it[0] to it[1] }
            }.toMap()
            return XAccountCookies(
                authToken = values["auth_token"].orEmpty(),
                ct0 = values["ct0"].orEmpty(),
                guestId = values["guest_id"].orEmpty(),
                gt = values["gt"].orEmpty(),
                att = values["att"].orEmpty(),
            )
        }
    }
}

class FeedsSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): FeedsSettings = FeedsSettings(
        source = FeedSourceKind.fromPreference(preferences.getString(KEY_SOURCE, null)),
        xAccountCookies = loadXAccountCookies(),
        xBearerToken = preferences.getString(KEY_X_TOKEN, "").orEmpty(),
        xUserId = preferences.getString(KEY_X_USER_ID, "").orEmpty(),
        blueskyFeedGeneratorUri = preferences.getString(
            KEY_BLUESKY_FEED,
            BlueskyFeedSource.DEFAULT_FEED_GENERATOR_URI,
        ).orEmpty().ifBlank { BlueskyFeedSource.DEFAULT_FEED_GENERATOR_URI },
    )

    fun save(settings: FeedsSettings) {
        preferences.edit()
            .putString(KEY_SOURCE, settings.source.preferenceValue)
            .putString(KEY_X_TOKEN, settings.xBearerToken.trim())
            .putString(KEY_X_USER_ID, settings.xUserId.trim())
            .putString(KEY_X_AUTH_TOKEN, settings.xAccountCookies.authToken)
            .putString(KEY_X_CT0, settings.xAccountCookies.ct0)
            .putString(KEY_X_GUEST_ID, settings.xAccountCookies.guestId)
            .putString(KEY_X_GT, settings.xAccountCookies.gt)
            .putString(KEY_X_ATT, settings.xAccountCookies.att)
            .putString(
                KEY_BLUESKY_FEED,
                settings.blueskyFeedGeneratorUri.trim()
                    .ifBlank { BlueskyFeedSource.DEFAULT_FEED_GENERATOR_URI },
            )
            .apply()
    }

    fun saveXAccountCookies(cookies: XAccountCookies) {
        preferences.edit()
            .putString(KEY_X_AUTH_TOKEN, cookies.authToken)
            .putString(KEY_X_CT0, cookies.ct0)
            .putString(KEY_X_GUEST_ID, cookies.guestId)
            .putString(KEY_X_GT, cookies.gt)
            .putString(KEY_X_ATT, cookies.att)
            .apply()
    }

    fun clearXAccountCookies() {
        preferences.edit()
            .remove(KEY_X_AUTH_TOKEN)
            .remove(KEY_X_CT0)
            .remove(KEY_X_GUEST_ID)
            .remove(KEY_X_GT)
            .remove(KEY_X_ATT)
            .apply()
    }

    private fun loadXAccountCookies(): XAccountCookies = XAccountCookies(
        authToken = preferences.getString(KEY_X_AUTH_TOKEN, "").orEmpty(),
        ct0 = preferences.getString(KEY_X_CT0, "").orEmpty(),
        guestId = preferences.getString(KEY_X_GUEST_ID, "").orEmpty(),
        gt = preferences.getString(KEY_X_GT, "").orEmpty(),
        att = preferences.getString(KEY_X_ATT, "").orEmpty(),
    )

    private companion object {
        const val PREFS_NAME = "feeds_settings"
        const val KEY_SOURCE = "source"
        const val KEY_X_TOKEN = "x_bearer_token"
        const val KEY_X_USER_ID = "x_user_id"
        const val KEY_X_AUTH_TOKEN = "x_account_auth_token"
        const val KEY_X_CT0 = "x_account_ct0"
        const val KEY_X_GUEST_ID = "x_account_guest_id"
        const val KEY_X_GT = "x_account_gt"
        const val KEY_X_ATT = "x_account_att"
        const val KEY_BLUESKY_FEED = "bluesky_feed_generator_uri"
    }
}
