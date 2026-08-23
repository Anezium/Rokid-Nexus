package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONArray
import org.json.JSONObject

internal fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }

internal fun JSONObject.requiredInt(key: String): Int {
    if (!has(key) || isNull(key)) {
        throw AlleycatException("missing required field '$key'")
    }
    return try {
        getInt(key)
    } catch (e: Exception) {
        throw AlleycatException("field '$key' must be an integer", e)
    }
}

internal fun JSONObject.requiredBoolean(key: String): Boolean {
    if (!has(key) || isNull(key)) {
        throw AlleycatException("missing required field '$key'")
    }
    return try {
        getBoolean(key)
    } catch (e: Exception) {
        throw AlleycatException("field '$key' must be a boolean", e)
    }
}

internal fun JSONObject.requiredString(key: String): String {
    val value = optionalString(key)
        ?: throw AlleycatException("missing required field '$key'")
    if (value.isBlank()) {
        throw AlleycatException("field '$key' must not be blank")
    }
    return value
}

internal fun JSONObject.optionalString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return try {
        getString(key).takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        throw AlleycatException("field '$key' must be a string", e)
    }
}

internal fun JSONObject.optionalObject(key: String): JSONObject? {
    if (!has(key) || isNull(key)) return null
    return optJSONObject(key)
        ?: throw AlleycatException("field '$key' must be an object")
}

internal fun JSONObject.optionalArray(key: String): JSONArray? {
    if (!has(key) || isNull(key)) return null
    return optJSONArray(key)
        ?: throw AlleycatException("field '$key' must be an array")
}

internal fun JSONArray.toObjectList(): List<JSONObject> =
    (0 until length()).map { i ->
        optJSONObject(i) ?: throw AlleycatException("array element $i must be an object")
    }
