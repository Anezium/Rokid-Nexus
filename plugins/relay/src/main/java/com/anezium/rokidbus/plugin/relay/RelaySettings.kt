package com.anezium.rokidbus.plugin.relay

import android.content.Context

internal class RelaySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun imagePreviewsEnabled(): Boolean =
        prefs.getBoolean(KEY_IMAGE_PREVIEWS, DEFAULT_IMAGE_PREVIEWS)

    fun setImagePreviewsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IMAGE_PREVIEWS, enabled).apply()
    }

    fun messagesPerThread(): Int =
        prefs.getInt(KEY_MESSAGES_PER_THREAD, DEFAULT_MESSAGES_PER_THREAD)
            .coerceIn(MIN_MESSAGES_PER_THREAD, MAX_MESSAGES_PER_THREAD)

    fun setMessagesPerThread(value: Int) {
        prefs.edit()
            .putInt(KEY_MESSAGES_PER_THREAD, value.coerceIn(MIN_MESSAGES_PER_THREAD, MAX_MESSAGES_PER_THREAD))
            .apply()
    }

    fun noticeDisplaySeconds(): Int {
        migrateLegacyDisplayTime()
        return coerceNoticeDisplaySeconds(prefs.getInt(KEY_NOTICE_SECONDS, DEFAULT_NOTICE_DISPLAY_SECONDS))
    }

    fun setNoticeDisplaySeconds(value: Int) {
        prefs.edit()
            .putInt(KEY_NOTICE_SECONDS, coerceNoticeDisplaySeconds(value))
            .apply()
    }

    fun noticeScalesWithLength(): Boolean {
        migrateLegacyDisplayTime()
        return prefs.getBoolean(KEY_NOTICE_SCALES_WITH_LENGTH, DEFAULT_NOTICE_SCALES_WITH_LENGTH)
    }

    fun setNoticeScalesWithLength(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTICE_SCALES_WITH_LENGTH, enabled).apply()
    }

    /**
     * Carries the old "notice_display_seconds" key forward once. The legacy key
     * meant 0 = Auto (hub-derived, length-scaled) and 5..45 = fixed; the two new
     * keys are a plain seconds value and whether that value scales with length.
     */
    private fun migrateLegacyDisplayTime() {
        if (prefs.contains(KEY_NOTICE_SECONDS) || !prefs.contains(KEY_LEGACY_NOTICE_DISPLAY_SECONDS)) return
        val mapped = legacyDisplayTime(prefs.getInt(KEY_LEGACY_NOTICE_DISPLAY_SECONDS, 0))
        prefs.edit()
            .putInt(KEY_NOTICE_SECONDS, mapped.seconds)
            .putBoolean(KEY_NOTICE_SCALES_WITH_LENGTH, mapped.scalesWithLength)
            .remove(KEY_LEGACY_NOTICE_DISPLAY_SECONDS)
            .apply()
    }

    fun pauseWhilePhoneScreenOn(): Boolean =
        prefs.getBoolean(KEY_PAUSE_SCREEN_ON, DEFAULT_PAUSE_SCREEN_ON)

    fun setPauseWhilePhoneScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_SCREEN_ON, enabled).apply()
    }

    fun clearAfterReply(): Boolean =
        prefs.getBoolean(KEY_CLEAR_AFTER_REPLY, DEFAULT_CLEAR_AFTER_REPLY)

    fun setClearAfterReply(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLEAR_AFTER_REPLY, enabled).apply()
    }

    fun noticeBackdrop(): Boolean =
        prefs.getBoolean(KEY_NOTICE_BACKDROP, DEFAULT_NOTICE_BACKDROP)

    fun setNoticeBackdrop(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTICE_BACKDROP, enabled).apply()
    }

    fun hideNoticeText(): Boolean =
        prefs.getBoolean(KEY_HIDE_NOTICE_TEXT, DEFAULT_HIDE_NOTICE_TEXT)

    fun setHideNoticeText(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_NOTICE_TEXT, enabled).apply()
    }

    fun hideInboxPreviews(): Boolean =
        prefs.getBoolean(KEY_HIDE_INBOX_PREVIEWS, DEFAULT_HIDE_INBOX_PREVIEWS)

    fun setHideInboxPreviews(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_INBOX_PREVIEWS, enabled).apply()
    }

    fun readAloud(): Boolean =
        prefs.getBoolean(KEY_READ_ALOUD, DEFAULT_READ_ALOUD)

    fun setReadAloud(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_READ_ALOUD, enabled).apply()
    }

    fun admits(): Boolean = NotificationAdmission.appIsAdmitted(enabled())

    data class LegacyDisplayTime(val seconds: Int, val scalesWithLength: Boolean)

    companion object {
        const val DEFAULT_ENABLED = true
        const val DEFAULT_IMAGE_PREVIEWS = false
        const val DEFAULT_MESSAGES_PER_THREAD = 20
        const val MIN_MESSAGES_PER_THREAD = 4
        const val MAX_MESSAGES_PER_THREAD = 40
        const val DEFAULT_NOTICE_DISPLAY_SECONDS = 3
        const val MIN_NOTICE_DISPLAY_SECONDS = 2
        const val MAX_NOTICE_DISPLAY_SECONDS = 45
        const val DEFAULT_NOTICE_SCALES_WITH_LENGTH = true
        const val DEFAULT_PAUSE_SCREEN_ON = false
        const val DEFAULT_CLEAR_AFTER_REPLY = true
        const val DEFAULT_NOTICE_BACKDROP = false
        const val DEFAULT_READ_ALOUD = false
        const val DEFAULT_HIDE_NOTICE_TEXT = false
        const val DEFAULT_HIDE_INBOX_PREVIEWS = false

        fun coerceNoticeDisplaySeconds(value: Int): Int =
            value.coerceIn(MIN_NOTICE_DISPLAY_SECONDS, MAX_NOTICE_DISPLAY_SECONDS)

        /**
         * What the old "notice_display_seconds" preference meant. Auto (0) was
         * "scale with length"; any fixed value was exactly that, held for its
         * whole duration.
         */
        fun legacyDisplayTime(legacySeconds: Int): LegacyDisplayTime =
            if (legacySeconds == 0) {
                LegacyDisplayTime(DEFAULT_NOTICE_DISPLAY_SECONDS, true)
            } else {
                LegacyDisplayTime(coerceNoticeDisplaySeconds(legacySeconds), false)
            }

        private const val PREFS = "relay_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_IMAGE_PREVIEWS = "image_previews"
        private const val KEY_MESSAGES_PER_THREAD = "messages_per_thread"
        private const val KEY_NOTICE_SECONDS = "notice_seconds"
        private const val KEY_LEGACY_NOTICE_DISPLAY_SECONDS = "notice_display_seconds"
        private const val KEY_NOTICE_SCALES_WITH_LENGTH = "notice_scales_with_length"
        private const val KEY_PAUSE_SCREEN_ON = "pause_screen_on"
        private const val KEY_CLEAR_AFTER_REPLY = "clear_after_reply"
        private const val KEY_NOTICE_BACKDROP = "notice_backdrop"
        private const val KEY_HIDE_NOTICE_TEXT = "hide_notice_text"
        private const val KEY_HIDE_INBOX_PREVIEWS = "hide_inbox_previews"
        private const val KEY_READ_ALOUD = "read_aloud"
    }
}
