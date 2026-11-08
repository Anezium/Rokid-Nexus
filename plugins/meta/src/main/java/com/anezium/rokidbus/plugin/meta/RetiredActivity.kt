package com.anezium.rokidbus.plugin.meta

import android.content.Context
import android.content.SharedPreferences
import com.anezium.rokidbus.client.plugin.NexusSdkResult

/**
 * Messages once offered a "Keep in activities" card, since removed. A phone that turned it on
 * still holds the retained entry, which only its owner can end: the first approved registration
 * after the upgrade ends it once, and forgets the old choice only once the end was sent; anything
 * else (no glasses capabilities yet, a lost registration) leaves it owed for the next one.
 */
internal object RetiredActivity {
    private const val PREFS = "meta-settings"
    private const val KEEP = "keep_in_activities"
    private const val END_PENDING = "activity_end_pending"

    /** Whether an entry may still be retained: it was kept, or an end was still owed. */
    fun owesEnd(preferences: SharedPreferences): Boolean =
        preferences.getBoolean(KEEP, false) || preferences.getBoolean(END_PENDING, false)

    /** Ends the old entry through [end] when one may remain; true when nothing is owed any more. */
    fun settle(preferences: SharedPreferences, end: () -> NexusSdkResult): Boolean {
        if (!owesEnd(preferences)) return true
        if (end() != NexusSdkResult.SENT) return false
        preferences.edit().remove(KEEP).remove(END_PENDING).apply()
        return true
    }

    fun preferences(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
