package com.anezium.rokidbus.plugin.transit

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TransitFavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<TransitStop> {
        val raw = prefs.getString(KEY_STOPS, "[]").orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                val lat = item.optDouble("lat", Double.NaN)
                val lon = item.optDouble("lon", Double.NaN)
                if (id.isBlank() || name.isBlank() || lat.isNaN() || lon.isNaN()) continue
                add(
                    TransitStop(
                        id = id,
                        name = name,
                        lat = lat,
                        lon = lon,
                        distanceMeters = UNKNOWN_DISTANCE_METERS,
                    ),
                )
            }
        }
    }

    fun add(stop: TransitStop) {
        val current = list()
        if (current.any { it.id == stop.id }) return
        write(current + stop.withUnknownDistance())
    }

    fun remove(id: String) {
        write(list().filterNot { it.id == id })
    }

    fun lastMode(): TransitMode =
        TransitMode.fromPref(prefs.getString(KEY_LAST_MODE, TransitMode.NEAR_ME.prefValue))

    fun setLastMode(mode: TransitMode) {
        prefs.edit().putString(KEY_LAST_MODE, mode.prefValue).apply()
    }

    private fun write(stops: List<TransitStop>) {
        val array = JSONArray()
        stops.forEach { stop ->
            array.put(
                JSONObject()
                    .put("id", stop.id)
                    .put("name", stop.name)
                    .put("lat", stop.lat)
                    .put("lon", stop.lon),
            )
        }
        prefs.edit().putString(KEY_STOPS, array.toString()).apply()
    }

    private companion object {
        private const val PREFS = "transit_favorites"
        private const val KEY_STOPS = "stops"
        private const val KEY_LAST_MODE = "lastMode"
    }
}
