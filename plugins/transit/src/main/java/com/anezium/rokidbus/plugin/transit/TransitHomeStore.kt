package com.anezium.rokidbus.plugin.transit

import android.content.Context

internal interface TransitHomeSource {
    fun home(): TransitHome?
}

/**
 * The wearer's saved home, chosen once from the geocoder in Transit's settings. Only its label
 * may leave Transit; the coordinates are read by journey planning and nothing else.
 */
internal class TransitHomeStore(context: Context) : TransitHomeSource {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun home(): TransitHome? {
        val label = prefs.getString(KEY_LABEL, null)?.takeIf(String::isNotBlank) ?: return null
        val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return TransitHome(label, lat, lon)
    }

    fun save(home: TransitHome) {
        prefs.edit()
            .putString(KEY_LABEL, home.label.take(TransitSkillContract.MAX_NAME_CHARS))
            .putString(KEY_LAT, home.lat.toString())
            .putString(KEY_LON, home.lon.toString())
            .apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_LABEL).remove(KEY_LAT).remove(KEY_LON).apply()
    }

    private companion object {
        const val PREFS = "nexus_plugin_transit_home"
        const val KEY_LABEL = "label"
        const val KEY_LAT = "lat"
        const val KEY_LON = "lon"
    }
}
