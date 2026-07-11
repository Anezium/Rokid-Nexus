package com.anezium.rokidbus.plugin.feeds

import android.content.Context

data class FeedsSettings(
    val source: FeedSourceKind,
    val xBearerToken: String,
    val xUserId: String,
    val blueskyFeedGeneratorUri: String,
)

class FeedsSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): FeedsSettings = FeedsSettings(
        source = FeedSourceKind.fromPreference(preferences.getString(KEY_SOURCE, null)),
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
            .putString(
                KEY_BLUESKY_FEED,
                settings.blueskyFeedGeneratorUri.trim()
                    .ifBlank { BlueskyFeedSource.DEFAULT_FEED_GENERATOR_URI },
            )
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "feeds_settings"
        const val KEY_SOURCE = "source"
        const val KEY_X_TOKEN = "x_bearer_token"
        const val KEY_X_USER_ID = "x_user_id"
        const val KEY_BLUESKY_FEED = "bluesky_feed_generator_uri"
    }
}
