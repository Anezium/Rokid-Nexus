package com.anezium.rokidbus.client.plugin

import android.content.Context
import android.content.SharedPreferences

/**
 * A user's opt-in to an ongoing activity. Keeping an entry never starts a service: publish on
 * existing events, and Nexus retains its offline destination while the plugin sleeps. On disable,
 * the owner must call endActivity(), retrying an owed end after its next approved registration.
 * This choice is separate from Nexus's grant, floating visibility and pin ordering.
 */
class NexusActivityPreference(
    private val preferences: SharedPreferences,
    private val key: String = "keep_in_activities",
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("nexus_activity", Context.MODE_PRIVATE),
    )

    var enabled: Boolean
        get() = preferences.getBoolean(key, false)
        set(value) { preferences.edit().putBoolean(key, value).apply() }

    /** Returns an ongoing entry only while opted in; null means end any existing entry. */
    fun retained(activity: NexusActivity): NexusActivity? =
        activity.copy(persistence = NexusActivityPersistence.ONGOING).takeIf { enabled }

    companion object {
        const val TITLE = "Keep in activities"
        const val DESCRIPTION = "Open this app from Activities anytime. Turn off to remove its entry."

        /** Opens Nexus's user-owned floating behavior choices; false when this hub is too old. */
        fun openDisplaySettings(context: Context, pluginId: String): Boolean = try {
            context.startActivity(android.content.Intent().apply {
                setClassName("com.anezium.rokidbus.phone", "com.anezium.rokidbus.phone.ActivityDisplaySettingsActivity")
                putExtra("pluginId", pluginId)
                putExtra("pluginPackage", context.packageName)
                if (context !is android.app.Activity) addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
