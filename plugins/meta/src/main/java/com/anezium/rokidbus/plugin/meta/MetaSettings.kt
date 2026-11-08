package com.anezium.rokidbus.plugin.meta

import android.content.Context

/** The owner's choices for the glasses pages, shared by the settings screen and the service. */
internal object MetaSettings {
    val HISTORY_CHOICES = listOf(10, 30, 50)
    private const val DEFAULT_HISTORY = 30

    private fun prefs(context: Context) = context.getSharedPreferences("meta-settings", Context.MODE_PRIVATE)

    /** How many recent messages a conversation page shows. */
    fun historyLimit(context: Context): Int =
        prefs(context).getInt("history", DEFAULT_HISTORY).takeIf { it in HISTORY_CHOICES } ?: DEFAULT_HISTORY

    fun setHistoryLimit(context: Context, value: Int) {
        if (value in HISTORY_CHOICES) prefs(context).edit().putInt("history", value).apply()
    }
}
