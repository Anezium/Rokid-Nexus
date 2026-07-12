package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal interface TransitLegacyStateStorage {
    fun readStops(): String?
    fun readLastMode(): String?
    fun clearLegacy()
    fun isComplete(key: String): Boolean
    fun markComplete(key: String)
}

internal class AndroidTransitLegacyStateStorage(context: Context) : TransitLegacyStateStorage {
    private val legacy = context.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)
    private val migration = context.getSharedPreferences(MIGRATION_PREFERENCES, Context.MODE_PRIVATE)

    override fun readStops(): String? = legacy.getString(KEY_STOPS, null)
    override fun readLastMode(): String? = legacy.getString(KEY_LAST_MODE, null)
    override fun clearLegacy() {
        legacy.edit().remove(KEY_STOPS).remove(KEY_LAST_MODE).apply()
    }
    override fun isComplete(key: String): Boolean = migration.getBoolean(key, false)
    override fun markComplete(key: String) {
        migration.edit().putBoolean(key, true).apply()
    }

    private companion object {
        const val LEGACY_PREFERENCES = "transit_favorites"
        const val MIGRATION_PREFERENCES = "transit_legacy_migration"
        const val KEY_STOPS = "stops"
        const val KEY_LAST_MODE = "lastMode"
    }
}

internal class TransitLegacyStateExporter(
    private val storage: TransitLegacyStateStorage,
) {
    data class PendingMigration(
        val eventId: String,
        val payload: JSONObject,
    )

    fun prepare(
        principal: PhonePluginPrincipal,
        grantState: PluginGrantState,
    ): PendingMigration? {
        if (!isAuthorizedTransit(principal, grantState)) return null
        val completionKey = completionKey(principal)
        if (storage.isComplete(completionKey)) return null
        val state = readState()
        if (state.stops.isEmpty() && state.lastMode == null) {
            storage.markComplete(completionKey)
            return null
        }
        val checksum = checksum(state)
        val eventId = digest(
            "${principal.packageName}|${principal.descriptor.id}|${principal.signingDigestSha256}|$checksum",
        ).take(32)
        return PendingMigration(
            eventId = eventId,
            payload = JSONObject()
                .put("version", 1)
                .put("type", "transit_legacy_state")
                .put("id", eventId)
                .put("pluginId", TRANSIT_ID)
                .put("count", state.stops.size)
                .put("checksum", checksum)
                .put("state", state.toJson()),
        )
    }

    fun acknowledge(
        principal: PhonePluginPrincipal,
        grantState: PluginGrantState,
        acknowledgement: JSONObject,
    ): Boolean {
        val pending = prepare(principal, grantState) ?: return false
        val valid = acknowledgement.optInt("version") == 1 &&
            acknowledgement.optString("type") == "transit_legacy_ack" &&
            acknowledgement.optString("pluginId") == TRANSIT_ID &&
            acknowledgement.optString("id") == pending.eventId &&
            acknowledgement.optInt("count", -1) == pending.payload.getInt("count") &&
            acknowledgement.optString("checksum") == pending.payload.getString("checksum")
        if (!valid) return false
        storage.clearLegacy()
        storage.markComplete(completionKey(principal))
        return true
    }

    private fun isAuthorizedTransit(
        principal: PhonePluginPrincipal,
        grantState: PluginGrantState,
    ): Boolean = principal.descriptor.id == TRANSIT_ID &&
        grantState is PluginGrantState.Approved &&
        PluginCapability.SURFACES in grantState.capabilities

    private fun readState(): LegacyState {
        val stops = runCatching { JSONArray(storage.readStops().orEmpty()) }
            .getOrDefault(JSONArray())
        val valid = buildList {
            val ids = linkedSetOf<String>()
            for (index in 0 until stops.length()) {
                val item = stops.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val name = item.optString("name")
                val lat = item.optDouble("lat", Double.NaN)
                val lon = item.optDouble("lon", Double.NaN)
                if (id.isBlank() || name.isBlank() || !lat.isFinite() || !lon.isFinite()) continue
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || !ids.add(id)) continue
                add(LegacyStop(id, name, lat, lon))
            }
        }
        val mode = storage.readLastMode()?.takeIf { it == "near_me" || it == "favorites" }
        return LegacyState(valid, mode)
    }

    private fun checksum(state: LegacyState): String = digest(
        buildString {
            append(state.lastMode.orEmpty())
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

    private fun completionKey(principal: PhonePluginPrincipal): String =
        "complete_${digest("${principal.packageName}|${principal.descriptor.id}|${principal.signingDigestSha256}")}"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class LegacyState(
        val stops: List<LegacyStop>,
        val lastMode: String?,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("lastMode", lastMode ?: JSONObject.NULL)
            .put(
                "stops",
                JSONArray().also { array -> stops.forEach { array.put(it.toJson()) } },
            )
    }

    private data class LegacyStop(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("lat", lat)
            .put("lon", lon)
    }

    companion object {
        const val IMPORT_PATH = "/plugin/transit/migration/legacy"
        const val ACK_PATH = "/plugin/transit/migration/ack"
        private const val TRANSIT_ID = "transit"
    }
}
