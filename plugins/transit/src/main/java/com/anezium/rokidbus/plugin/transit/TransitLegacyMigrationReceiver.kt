package com.anezium.rokidbus.plugin.transit

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

internal interface TransitMigrationStorage {
    fun isComplete(id: String): Boolean
    fun import(stops: List<TransitStop>, mode: TransitMode?)
    fun markComplete(id: String)
}

internal class AndroidTransitMigrationStorage(context: Context) : TransitMigrationStorage {
    private val appContext = context.applicationContext
    private val favorites = TransitFavoritesStore(appContext)
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun isComplete(id: String): Boolean = id in preferences.getStringSet(KEY_COMPLETE, emptySet()).orEmpty()

    override fun import(stops: List<TransitStop>, mode: TransitMode?) {
        favorites.importLegacy(stops, mode)
    }

    override fun markComplete(id: String) {
        val completed = preferences.getStringSet(KEY_COMPLETE, emptySet()).orEmpty().toMutableSet()
        completed += id
        preferences.edit().putStringSet(KEY_COMPLETE, completed).apply()
    }

    private companion object {
        const val PREFERENCES = "transit_legacy_migration"
        const val KEY_COMPLETE = "completedIds"
    }
}

internal class TransitLegacyMigrationReceiver(
    private val storage: TransitMigrationStorage,
) {
    fun receive(payload: JSONObject): JSONObject? {
        if (payload.optInt("version") != 1 ||
            payload.optString("type") != "transit_legacy_state" ||
            payload.optString("pluginId") != TRANSIT_ID
        ) return null
        val id = payload.optString("id")
        val checksum = payload.optString("checksum")
        val expectedCount = payload.optInt("count", -1)
        if (id.isBlank() || checksum.isBlank() || expectedCount < 0) return null
        val state = parseState(payload.optJSONObject("state") ?: return null) ?: return null
        if (state.stops.size != expectedCount || checksum(state) != checksum) return null
        if (!storage.isComplete(id)) {
            storage.import(state.stops, state.mode)
            storage.markComplete(id)
        }
        return JSONObject()
            .put("version", 1)
            .put("type", "transit_legacy_ack")
            .put("pluginId", TRANSIT_ID)
            .put("id", id)
            .put("count", expectedCount)
            .put("checksum", checksum)
    }

    private fun parseState(json: JSONObject): MigrationState? {
        val rawMode = json.opt("lastMode")
        val modeText = if (rawMode == null || rawMode == JSONObject.NULL) null else rawMode.toString()
        val mode = when (modeText) {
            null -> null
            "near_me", "favorites" -> TransitMode.fromPref(modeText)
            else -> return null
        }
        val array = json.optJSONArray("stops") ?: return null
        val ids = linkedSetOf<String>()
        val stops = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: return null
                val id = item.optString("id")
                val name = item.optString("name")
                val lat = item.optDouble("lat", Double.NaN)
                val lon = item.optDouble("lon", Double.NaN)
                if (id.isBlank() || name.isBlank() || !lat.isFinite() || !lon.isFinite()) return null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || !ids.add(id)) return null
                add(TransitStop(id, name, lat, lon))
            }
        }
        return MigrationState(stops, mode)
    }

    private fun checksum(state: MigrationState): String = digest(
        buildString {
            append(state.mode?.prefValue.orEmpty())
            state.stops.forEach { stop ->
                append('\n')
                append(JSONObject.quote(stop.id))
                append('|')
                append(JSONObject.quote(stop.name))
                append('|')
                append(stop.lat)
                append('|')
                append(stop.lon)
            }
        },
    )

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class MigrationState(
        val stops: List<TransitStop>,
        val mode: TransitMode?,
    )

    companion object {
        const val IMPORT_PATH = "/plugin/transit/migration/legacy"
        const val ACK_PATH = "/plugin/transit/migration/ack"
        private const val TRANSIT_ID = "transit"
    }
}
