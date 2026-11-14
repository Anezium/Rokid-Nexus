package com.anezium.rokidbus.phone

import android.Manifest
import android.app.Activity
import android.content.Context
import android.os.Build

/** Foreground consent for recovery; a denial never blocks starting the hub. */
internal object GlassesRepairPermission {
    const val REQUEST_CODE = 86
    private const val ASKED = "glasses_repair_wifi_permission_asked"

    fun request(activity: Activity, once: Boolean): Boolean {
        if (GlassesFilesHotspotHost.permitted(activity)) return false
        val prefs = activity.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
        if (once && (!GlassesRepairSettingsStore(activity).isAutoRepairEnabled() || prefs.getBoolean(ASKED, false))) {
            return false
        }
        prefs.edit().putBoolean(ASKED, true).apply()
        val permissions = if (Build.VERSION.SDK_INT in 31..32) {
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        } else arrayOf(GlassesFilesHotspotHost.permission())
        activity.requestPermissions(permissions, REQUEST_CODE)
        return true
    }
}
