package com.anezium.rokidbus.plugin.transit

import android.content.Context
import org.json.JSONArray

internal interface TransitLegacyStorage {
    fun hasLegacyState(): Boolean
    fun readStops(): String?
    fun readLastMode(): String?
    fun isComplete(): Boolean
    fun markComplete()
}

internal class AndroidTransitLegacyStorage(context: Context) : TransitLegacyStorage {
    private val legacy = context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
    private val migration = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)

    override fun hasLegacyState(): Boolean = legacy.contains(KEY_STOPS) || legacy.contains(KEY_LAST_MODE)
    override fun readStops(): String? = legacy.getString(KEY_STOPS, null)
    override fun readLastMode(): String? = legacy.getString(KEY_LAST_MODE, null)
    override fun isComplete(): Boolean = migration.getBoolean(KEY_COMPLETE, false)
    override fun markComplete() {
        migration.edit().putBoolean(KEY_COMPLETE, true).apply()
    }

    private companion object {
        const val LEGACY_PREFERENCES = "transit_favorites"
        const val MIGRATION_PREFERENCES = "transit_legacy_migration"
        const val KEY_STOPS = "stops"
        const val KEY_LAST_MODE = "lastMode"
        const val KEY_COMPLETE = "directImportComplete"
    }
}

internal class TransitLegacyImporter(
    private val storage: TransitLegacyStorage,
) {
    fun importIfNeeded(importLegacy: (List<TransitStop>, TransitMode?) -> Unit): Boolean {
        if (storage.isComplete() || !storage.hasLegacyState()) return false
        val stops = parseStops(storage.readStops())
        val mode = storage.readLastMode()
            ?.takeIf { it == TransitMode.NEAR_ME.prefValue || it == TransitMode.FAVORITES.prefValue }
            ?.let(TransitMode::fromPref)
        importLegacy(stops, mode)
        storage.markComplete()
        return true
    }

    private fun parseStops(raw: String?): List<TransitStop> {
        val array = runCatching { JSONArray(raw.orEmpty()) }.getOrDefault(JSONArray())
        val ids = linkedSetOf<String>()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                val lat = item.optDouble("lat", Double.NaN)
                val lon = item.optDouble("lon", Double.NaN)
                if (id.isBlank() || name.isBlank() || !lat.isFinite() || !lon.isFinite()) continue
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || !ids.add(id)) continue
                add(TransitStop(id, name, lat, lon))
            }
        }
    }
}
