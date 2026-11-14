package com.anezium.rokidbus.plugin.meta

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A conversation the owner pinned: the engine's id, and the name the phone shows while the service is not connected. */
internal data class MetaPin(val id: String, val title: String, val group: Boolean)

/**
 * The pinned conversations of each service, in the owner's order, kept on this phone only.
 * A service's page on the glasses lists them first; nothing here leaves the phone. Forgetting
 * or unlinking a service drops its pins.
 */
internal object MetaPins {
    private fun prefs(context: Context) = context.getSharedPreferences("meta-pins", Context.MODE_PRIVATE)

    fun get(context: Context, service: MessagingService): List<MetaPin> = runCatching {
        val rows = JSONArray(prefs(context).getString(service.id, null) ?: return emptyList())
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            MetaPin(row.getString("id"), row.optString("title"), row.optBoolean("group"))
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    fun ids(context: Context, service: MessagingService): List<String> = get(context, service).map { it.id }

    fun set(context: Context, service: MessagingService, pins: List<MetaPin>) {
        val rows = JSONArray()
        pins.distinctBy { it.id }.forEach { rows.put(JSONObject().put("id", it.id).put("title", it.title).put("group", it.group)) }
        prefs(context).edit().putString(service.id, rows.toString()).apply()
    }

    /** Takes the names the service lists now, so a renamed contact reads right on the phone while offline. */
    fun rename(context: Context, service: MessagingService, chats: List<MetaChat>) {
        val titles = chats.associate { it.id to it.title }
        val pins = get(context, service)
        val renamed = pins.map { pin -> titles[pin.id]?.takeIf { it != pin.title }?.let { pin.copy(title = it) } ?: pin }
        if (renamed != pins) set(context, service, renamed)
    }

    fun clear(context: Context, service: MessagingService) {
        prefs(context).edit().remove(service.id).apply()
    }

    /** Calls [changed] on the main thread whenever a pin changes; the returned function stops it. */
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> changed() }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
